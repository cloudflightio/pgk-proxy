# Technische Abläufe

Dieses Dokument beschreibt die beiden zentralen Abläufe des PGK-Proxys mit Sequenzdiagrammen und
konkreten Beispielanfragen:

1. **Föderierte Benutzersuche** über den Proxy und den FHIR-Server
   (`/_matrix/client/v3/user_directory/search`)
2. **Prüfung einer föderierten Raumeinladung** (`/_matrix/federation/v2/invite`) sowie die
   analoge Prüfung ausgehender Einladungen (`createRoom` / `rooms/{roomId}/invite`)

Verwandte Dokumente: [Setup](setup.md), [Benutzerhandbuch](user-guide.md),
[Administrationshandbuch](admin-guide.md), [FHIR-Leitfaden](fhir-guide.md).

## Architektur-Notiz

Traefik leitet `matrix.traefik.me` mit Ausnahme der Proxy-Pfade direkt an den Matrix-Server weiter
(siehe `dynamic.yml`):

- `/_matrix/client/v3/user_directory/search` → **pgk-proxy**
- `/_matrix/client/v3/createRoom`, `/_matrix/client/v3/rooms/{roomId}/invite` → **pgk-proxy**
- `/_matrix/federation/v2/invite` (Client-Traffic 443 und Föderations-Traffic 8448) → **pgk-proxy**
- alles andere → Matrix-Server

---

## 1. Föderierte Benutzersuche über Proxy und FHIR

Ein Matrix-Client sucht über das Benutzerverzeichnis. Der Proxy authentifiziert den Absender,
fragt das FHIR-Provider Directory ab, konsolidiert die Ergebnisse und filtert sie nach
Sichtbarkeit und Einladungspolitik, bevor er die Matrix-konforme Antwort zurückgibt.

```mermaid
sequenceDiagram
    participant C as Matrix-Client
    participant T as Traefik (matrix.traefik.me)
    participant P as pgk-proxy
    participant S as Synapse (matrix.traefik.me)
    participant F as HAPI FHIR (Provider Directory)

    C->>T: POST /_matrix/client/v3/user_directory/search<br/>(Authorization: Bearer ...)
    T->>P: Weiterleitung an den Proxy (nur dieser Pfad)
    P->>S: POST /_matrix/client/v3/account/whoami (Bearer Token)
    S-->>P: user_id des Absenders (@mxid:matrix.traefik.me)
    P->>F: Suche Practitioner nach telecom = matrix:u/<mxid> (aktueller Benutzer)
    F-->>P: Practitioner + PractitionerRole + Organization (+ partOf-Kette)
    P->>F: Suche Practitioner nach Name, Rolle (role-text) und Organisation
    P->>F: Suche HealthcareService nach Name
    F-->>P: PractitionerSearchBundle / HealthcareServiceSearchBundle
    P->>P: Konsolidierung pro MXID (Anzeigename, Rollen, Organisationsketten, Sichtbarkeit)
    P->>P: Prüfung: Absender darf senden, Empfänger öffentlich oder gleiche Wurzelorganisation, keine Org-Blockade (gesperrte MXID/Rolle, Empfangen-Recht deaktiviert)
    P-->>T: 200 { "limited": false, "results": [ { "user_id", "display_name" } ] }
    T-->>C: Antwort
```

### Beteiligte FHIR-Abfragen

Die Namenssuche läuft im Standardmodus (`pgk.fhir.search-mode: revinclude`) über drei parallele
Suchen, die per `_revinclude`/`include` zu einem Bundle kombiniert werden:

- `Practitioner?name=monika` + `_revinclude=PractitionerRole:practitioner` +
  `include=PractitionerRole:organization` + `include=Organization:partof`
- `PractitionerRole?role-text=monika` + `include=PractitionerRole:practitioner` +
  `include=PractitionerRole:organization`
- `Organization?name=monika` + `_revinclude`/`include` der partOf-Kette und Rollen

Die Auflösung des Absenders (MXID → Practitioner) nutzt die Suche über `Practitioner.telecom`
mit dem Wert `matrix:u/<mxid>:<domain>`.

---

## 2. Prüfung einer föderierten Raumeinladung

Eine eingehende Föderations-Einladung (vom Partnerserver `partner.traefik.me`) erreicht den Proxy
über den Föderations-Port 8448. Der Proxy wertet die Einladung (PDU) gegen Föderations-Whitelist,
Verzeichnis, Organisationsregeln und persönliche Regeln aus und leitet sie bei Erfolg an den
lokalen Synapse weiter.

```mermaid
sequenceDiagram
    participant PS as Synapse Partner (partner.traefik.me)
    participant T as Traefik (matrix.traefik.me :8448)
    participant P as pgk-proxy
    participant F as HAPI FHIR (Provider Directory)
    participant S as Synapse (matrix.traefik.me)

    PS->>T: PUT /_matrix/federation/v2/invite/{roomId}/{eventId}<br/>(X-Matrix Origin=partner.traefik.me)
    T->>P: Weiterleitung (Host matrix.traefik.me, PathPrefix /_matrix/federation/v2/invite)
    P->>P: PDU parsen (sender, recipient/state_key, origin)
    P->>P: Origin in Föderations-Whitelist? (FHIR Endpoint, connection-type matrix-homeserver)
    P->>F: Sender bekannt? (Practitioner mit telecom = sender-MXID)
    P->>F: Empfänger bekannt? (Practitioner mit telecom = recipient-MXID)
    P->>F: Haben Sender und Empfänger mindestens eine Rolle? (PractitionerRole)
    P->>P: Org-Blockade? (gesperrte MXID, deaktiviertes Empfangen-Recht, empfangen-gesperrte Rolle)
    P->>F: Rollen des Senders laden (für persönliche Regeln)
    P->>P: Persönliche Regeln des Empfängers (personal_invitation_blocks / personal_blocked_users)
    alt Erlaubt
        P->>S: PUT /_matrix/federation/v2/invite/{roomId}/{eventId}<br/>(Body + X-Matrix-Header weitergeleitet)
        S-->>P: 200 mit Signatur-/State-Antwort
        P-->>T: 200 (Body von Synapse)
        T-->>PS: Antwort
    else Abgelehnt
        P-->>T: 403 { "errcode": "M_FORBIDDEN", "error": "..." }
        T-->>PS: Antwort
    end
```

### Beispielanfrage (PDU)

```http
PUT /_matrix/federation/v2/invite/!dGVzdHJvb20:matrix.traefik.me/%24ev_1 HTTP/1.1
Host: matrix.traefik.me:8448
Authorization: X-Matrix origin=partner.traefik.me,key="ed25519:aa ...",sig="..."
Content-Type: application/json

{
  "event": {
    "type": "m.room.member",
    "room_id": "!dGVzdHJvb20:matrix.traefik.me",
    "sender": "@monika-partner:partner.traefik.me",
    "state_key": "@cb5915dc-4124-4bd8-a217-03465c545359:matrix.traefik.me",
    "event_id": "$ev_1",
    "origin": "partner.traefik.me",
    "origin_server_ts": 1720000000000,
    "content": {
      "membership": "invite",
      "displayname": "Monika Partner"
    },
    "unsigned": {
      "age": 1234
    }
  },
  "pdus": [],
  "signed_events": []
}
```

### Beispielantworten

Erlaubt (der Proxy gibt die Antwort von Synapse weiter):

```json
{
  "event": {
    "type": "m.room.member",
    "room_id": "!dGVzdHJvb20:matrix.traefik.me",
    "sender": "@monika-partner:partner.traefik.me",
    "state_key": "@cb5915dc-4124-4bd8-a217-03465c545359:matrix.traefik.me",
    "event_id": "$ev_1",
    "signatures": { "matrix.traefik.me": { "ed25519:bb": "..." } }
  },
  "state": []
}
```

Abgelehnt (je nach Grund unterschiedliche Fehlermeldung):

| Grund | HTTP | Body |
| --- | --- | --- |
| Föderations-Whitelist / Verzeichnis / Rolle fehlt | 403 | `{"errcode": "M_FORBIDDEN", "error": "Invite not allowed by policy"}` |
| Persönliche Blacklist des Empfängers | 403 | `{"errcode": "M_FORBIDDEN", "error": "Invitation blocked by user policy."}` |
| Org-Blockade (gesperrte MXID, gesperrte Rolle, Empfangen-Recht deaktiviert) | 403 | `{"errcode": "M_FORBIDDEN", "error": "Invitation blocked by organization policy."}` |

### Prüfungsreihenfolge (Serverseite, `InvitationControlServiceImpl`)

1. `origin` ∈ Föderations-Whitelist (aus FHIR-`Endpoint`)
2. Sender **und** Empfänger existieren im Provider Directory
3. Sender **und** Empfänger haben mindestens eine `PractitionerRole`
4. Keiner der beiden steht auf der organisationsweiten MXID-Blockliste
5. Empfangen-Recht des Empfängers ist nicht deaktiviert; Empfänger-Rolle nicht empfangen-gesperrt
6. Persönliche Regeln des Empfängers (Rollen + gesperrte Benutzer)
7. Bei Erfolg: Weiterleitung an Synapse + `InvitationAllowedEvent`; sonst 403 +
   `InvitationBlockedEvent`

---

## 3. Ausgehende Einladungen (createRoom / invite)

Auch ausgehende Einladungen von Benutzern der eigenen Organisation werden vom Proxy geprüft
(`MatrixClientInvitationController`), damit Organisationsregeln in beide Richtungen gelten.
Traefik leitet die Pfade `/_matrix/client/v3/createRoom` und
`/_matrix/client/v3/rooms/{roomId}/invite` an den Proxy.

```mermaid
sequenceDiagram
    participant C as Matrix-Client
    participant P as pgk-proxy
    participant F as HAPI FHIR (Provider Directory)
    participant S as Synapse (matrix.traefik.me)

    C->>P: POST /_matrix/client/v3/createRoom (oder /rooms/{id}/invite)<br/>(Bearer Token + invitees)
    P->>P: Absender per whoami auflösen, Invitees aus dem Body extrahieren
    P->>P: Ziel-Domain in Föderations-Whitelist? Sender/Invitee haben Rollen?
    P->>P: Org-Blockade? (gesperrte MXID, Senden-Recht des Absenders,<br/>Empfangen-Recht der Invitees, senden-gesperrte Rolle des Absenders)
    alt Erlaubt
        P->>S: createRoom bzw. rooms/{id}/invite (Original-Body + Authorization)
        S-->>P: Antwort
        P-->>C: Antwort
    else Abgelehnt
        P-->>C: 403 { "errcode": "M_FORBIDDEN", "error": "Invitation not allowed by organizational policy" }
    end
```

### Beispielanfrage (Einladung über Client-API)

```http
POST /_matrix/client/v3/rooms/!dGVzdHJvb20:matrix.traefik.me/invite HTTP/1.1
Host: matrix.traefik.me
Authorization: Bearer syt_b2F1dGhl...
Content-Type: application/json

{
  "user_id": "@monika-partner:partner.traefik.me"
}
```

Abgelehnt:

```json
{
  "errcode": "M_FORBIDDEN",
  "error": "Invitation not allowed by organizational policy"
}
```

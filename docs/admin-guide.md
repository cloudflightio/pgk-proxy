# Administrationshandbuch

Dieses Handbuch richtet sich an Administratoren des PGK-Netzwerks. Es beschreibt, wie Benutzer im
IDP angelegt und verwaltet werden, wie ihnen Rollen zugewiesen werden, wie Einträge intern oder
öffentlich geschaltet werden, wie organisationsweite Einladungs-/Föderationsregeln gepflegt werden
und wie die Anzeigenamen der Benutzer gesteuert werden.

Verwandte Dokumente: [Setup](setup.md), [Benutzerhandbuch](user-guide.md),
[FHIR-Leitfaden](fhir-guide.md), [Technische Abläufe](technical-flows.md).

## Rollen und Admin-Berechtigung

- **Jeder angemeldete Benutzer** sieht den Bereich *Persönlich* (Rollen, Einladungen).
- Der Bereich **Admin** (Sichtbarkeit, Organisationen, Einladungsrechte, Rollen zuweisen) ist nur für
  Benutzer mit der Realm-Rolle **`admin`** sichtbar. Die Rollen werden aus
  `realm_access.roles` des OIDC-Tokens auf `ROLE_*`-Autoritäten gemappt (siehe `SecurityConfig`).

## Benutzer im IDP verwalten

Die Mitgliedschaft im PGK-Netzwerk wird in Keycloak verwaltet (lokales Setup:
https://idp.traefik.me, Login `admin`/`admin`, Realm `pgk`). Ein Keycloak-Event-Listener (`pgk-idp-provisioning`, siehe
unten) meldet jede Benutzeränderung an den Proxy, der das
FHIR-Provider Directory synchron hält:

- Das **Anlegen eines Benutzers** mit gesetzter Matrix-ID erzeugt einen FHIR-`Practitioner`.
- Das **Ändern oder Löschen der Matrix-ID** aktualisiert bzw. entfernt den Practitioner wieder.
- Das **Löschen des Benutzers** löscht den zugehörigen FHIR-Practitioner.

### Benutzer anlegen/ändern

1. Keycloak-Admin-Konsole öffnen (https://idp.traefik.me) und Realm `pgk` wählen.
2. Unter **Users** einen Benutzer anlegen bzw. bearbeiten und Vor- und Nachname setzen.
3. Unter **Attributes** das PGK-Attribut **`mxid`** setzen (nur durch Administratoren änderbar).
   Der Wert ist der Matrix-Localpart, z. B. `max`. Er wird beim Updaten gegen das Provider
   Directory geprüft und abgelehnt, wenn er bereits von jemand anderem verwendet wird.
4. Der Realm-Rolle `user` wird automatisch zugewiesen. Für Verwaltungsrechte zusätzlich die
   Rolle **`admin`** zuweisen.

Details zur Zuordnung:

- Die Matrix-ID ist **optional**. Ein Benutzer ohne `mxid` bleibt in Keycloak und wird nicht nach
  FHIR geschrieben.
- Die Keycloak-Benutzer-UUID wird zum FHIR-Identifier des Practitioners. System,
  Practitioner-Profil und Matrix-Telecom-System werden über `PGK_IDENTIFIER_SYSTEM`,
  `PGK_PRACTITIONER_PROFILE` und `PGK_MATRIX_TELECOM_SYSTEM` konfiguriert.
- Sobald eine MXID zugewiesen ist, trägt der resultierende Practitioner Trust-Level `tl-2`, den
  Vor- und Nachnamen und `matrix:u/<mxid>:matrix.traefik.me` im `telecom`-Feld.
- Eine Zuordnung von PGK-Masteridentitäten (zum Verlinken von Approved Unique Records z.B. aus dem
  eHVD) ist derzeit noch nicht im IDP möglich. Der Master-Identifier muss manuell über FHIR
  hinzugefügt werden.

### Technischer Hintergrund

- `pgk-idp-provisioning`, ein Keycloak-Event-Listener, postet Benutzeränderungen an
  `POST /internal/idp/user-events`. Er liegt im Gradle-Subprojekt `keycloak-provider`
  (Package `io.cloudflight.pgk.keycloakprovider`) und wird durch die Gradle-Task
  `keycloakProviderJar` gepackt und von `docker-compose.yml` aus
  `keycloak-provider/build/keycloak` eingehängt.
- Die Anfrage authentifiziert sich mit HTTP Basic (Benutzer `pgk-idp`): Das
  `PGK_PROXY_WEBHOOK_SECRET` des `keycloak`-Service in `docker-compose.yml` muss zu
  `pgk.idp.webhook-secret` des Proxys passen.

Läuft der Proxy nicht, funktioniert die Benutzerverwaltung in Keycloak trotzdem – die Änderungen
erreichen nur nicht das Provider Directory.

## Organisationen verwalten

Auf der Seite **„Untergeordnete Organisationen“** (Bereich *Admin*, URL `/admin/organizations`)
im Admin-Portal wird der Organisationsbaum des Provider Directory gepflegt. Die linke Spalte
zeigt den gesamten Baum (ausgehend von den Rootorganizations); ein Klick auf einen Eintrag
lädt ihn rechts zur Bearbeitung. Diese Organisationen sind die Grundlage für die
Rollenzuweisung (siehe unten) und für die Root-Organisations-Prüfung bei Sichtbarkeit und
Föderation.

- **Anlegen:** Name eingeben, eine **übergeordnete Organisation** über die Such-Combobox
  auswählen (Pflichtfeld – Root-Organisationen selbst lassen sich hier nicht anlegen, sie
  müssen bereits im Provider Directory vorhanden sein) und optional eine **Art der
  Einrichtung** setzen.
- **Bearbeiten/Verschieben:** Name, übergeordnete Organisation und Art können jederzeit
  geändert werden. Eine Organisation kann nicht sich selbst, einer ihrer eigenen
  Unterorganisationen oder einer Organisation zugeordnet werden, die den Baum tiefer als drei
  Ebenen (Root → Unterorganisation → Unterorganisation) werden lässt.
- **Löschen:** Nur möglich, wenn die Organisation keine Unterorganisationen mehr hat;
  Wurzelorganisationen können nie gelöscht werden. Beim Löschen werden alle `PractitionerRole`-
  Ressourcen entfernt, die auf die Organisation verweisen.
- Die **Art der Einrichtung** stammt aus dem lokalen ValueSet
  `src/main/resources/valuesets/organizations/ValueSet-hl7-at-organizationtype.json`
  (System `https://termgit.elga.gv.at/ValueSet/hl7-at-organizationtype`) und wird als
  `Organization.type`-Coding gespeichert. Name und übergeordnete Organisation werden als
  `Organization.name` bzw. `Organization.partOf` geschrieben.

## Rollen zuweisen

Auf der Seite **„Rollen zuweisen“** (Bereich *Admin*, URL `/admin/practitioner-roles`)
im Admin-Portal (`http://localhost:8080` im lokalen Setup)
vergeben Sie Rollen für alle Gesundheitsdiensteanbieter im Verzeichnis:

1. Einen Practitioner suchen/auswählen.
2. **„Rollen zuweisen“** öffnet den Dialog. Optional kann eine (Haupt-)Organisation gesetzt
   werden; dann eine oder mehrere Rollen aus dem Rollenkatalog auswählen.
3. Speichern. Bereits zugewiesene Rollen können einzeln entfernt werden.

Jede Zuweisung wird als FHIR-`PractitionerRole` gespeichert, die über `practitioner` und
`organization` auf die jeweiligen Ressourcen verweist und den Rollen-Code aus dem Katalog trägt (`CodeableConcept` mit
`coding {system}|{code}` plus `text`). Details: [FHIR-Leitfaden](fhir-guide.md).

Diese Rollen sind die Grundlage für Föderation und Einladungsprüfung: Ein Benutzer ohne Rolle
kann weder einladen noch eingeladen werden, und organisationsweite Rollen-Blöcke wirken über die
Rollen-Tokens.

## Intern / Öffentlich schalten (Sichtbarkeit)

Auf der Seite **„Sichtbarkeit“** (Bereich *Admin*, URL `/admin/visibility`) im Admin-Portal (`http://localhost:8080` im
lokalen Setup)  wird gesteuert,
wer im Benutzerverzeichnis sichtbar ist. Es werden die von der IDP provisionierten Practitioner
und die Healthcare Services aufgelistet (jeweils nur Einträge mit Matrix-ID). Ein Klick auf den
Segment-Schalter **Öffentlich / Intern** persistiert die Auswahl sofort:

- **Öffentlich:** Der Eintrag erscheint in den Suchergebnissen aller Organisationen im Netzwerk.
- **Intern:** Der Eintrag ist nur für Benutzer derselben Root-Organisation sichtbar.

Technisch wird die Sichtbarkeit als FHIR-Meta-Security-Tag
`https://pgk.elga.gv.at/CodeSystem/visibility` mit `public` bzw. `internal` gespeichert (ersetzt jeweils das vorherige
Tag). Bei der Suche gilt: Ein Benutzer ist für den suchenden
Benutzer sichtbar, wenn er öffentlich ist **oder** dieselbe Root-Organisation hat.

## Organisationsweite Einladungs- und Föderationsregeln

Auf der Seite **„Einladungsrechte“** (Bereich *Admin*, URL `/admin/invitation-rights`)
werden die organisationsweiten Regeln gepflegt. Es gibt drei Bereiche:

### 1. Blockierte Rollen

Pro Richtung (Einladungen **empfangen** / Einladungen **senden**) kann eine Liste von Rollen
gesperrt werden. Der Dialog „Gesperrte Rollen festlegen“ ersetzt die komplette Liste pro Richtung:

- Ein **Absender mit einer gesendet-gesperrten Rolle** darf keine Einladungen versenden.
- Ein **Empfänger mit einer empfangen-gesperrten Rolle** darf keine Einladungen empfangen.

Eine Rolle wird über ihr Katalog-Token blockiert, z. B.
`https://termgit.elga.gv.at/CodeSystem/elga-gtelvogdarollen|222`. Der `codeSystem` der FHIR-Rolle
muss exakt zum Token passen.

### 2. Blockierte Benutzer

Über das Formular „Blockierte Benutzer“ kann jede Matrix-MXID organisationsweit gesperrt werden.
Gesperrte MXIDs dürfen weder Einladungen senden noch empfangen.

### 3. Einladungsberechtigungen pro Benutzer

Über die Tabelle „Einladungsberechtigungen“ lassen sich pro Practitioner die Schalter **Einladungen Empfangen** und
**Einladungen Senden** umschalten. Fehlende Zeilen bedeuten
„erlaubt“. Die Werte werden in der Datenbank gespeichert (Tabelle `practitioner_invitation_rights`).

### Föderations-Whitelist

Zusätzlich zu den Organisationsregeln prüft der Proxy die **Föderations-Whitelist**: Nur Server,
die als FHIR-`Endpoint` mit connection-type `matrix-homeserver` im Provider Directory registriert
sind, dürfen eingehende Einladungen senden bzw. als Empfänger angesprochen werden. Die Whitelist
wird regelmäßig aus dem FHIR-Server geladen (`PGK_FEDERATION_REFRESH_INTERVAL_MINUTES`,
Standard 60 Minuten). Details: [FHIR-Leitfaden](fhir-guide.md) und
[Technische Abläufe](technical-flows.md).

## Anzeigenamen steuern

Der Anzeigename eines Benutzers wird aus dem Provider Directory abgeleitet und besteht aus dem
Practitioner-Namen plus – sofern gewünscht – den Rollen und der Organisationskette:

```
Max Mustermann (Ärztin/Arzt für Allgemeinmedizin, Ordination Dr. Mustermann)
```

So wird er durchgereicht:

1. Der Proxy berechnet die Anzeigenamen aller Benutzer mit Matrix-ID aus FHIR (Service
   `DisplayNameQueryService`; ohne Rollensuffix für die Chat-Anzeige).
2. Unter `GET /internal/idp/display-names` liefert der Proxy eine Map
   `{ "@mxid:matrix.traefik.me": "Anzeigename" }` aus.
3. Der Keycloak-Protokoll-Mapper **„PGK Display Name“** (`pgk-display-name-mapper`) ruft diese
   Map beim Ausstellen jedes Tokens ab (mit Cache über `PGK_DISPLAYNAME_REFRESH_MINUTES`,
   Standard 60 Minuten) und setzt den Claim `displayname`.
4. Synapse verwendet im OIDC-`user_mapping_provider` den Template-Wert
   `display_name_template: "{{ user.displayname }}"` und übernimmt den Anzeigenamen in das
   Matrix-Konto.

Zum **Ändern eines Anzeigenamens** genügt es, Name und/oder Rollen des Benutzers zu pflegen (siehe oben) – der
Anzeigename folgt automatisch. Der Refresh des Keycloak-Caches bestimmt, wie
schnell die Änderung in neuen Tokens ankommt.

Konfigurierbar über Umgebungsvariablen des Keycloak-Containers (`docker-compose.yml`):

| Variable                          | Bedeutung                                  | Standard                           |
|-----------------------------------|--------------------------------------------|------------------------------------|
| `PGK_PROXY_BASE_URL`              | Basis-URL des Proxys                       | `http://host.docker.internal:8080` |
| `PGK_PROXY_WEBHOOK_SECRET`        | HTTP-Basic-Passwort für `/internal/idp/**` | `pgk-dev-webhook-secret`           |
| `PGK_IDP_REALM`                   | Zu provisionierende Realm                  | `pgk`                              |
| `PGK_MATRIX_DOMAIN`               | Matrix-Domain hinter der `mxid`            | `matrix.traefik.me`                |
| `PGK_DISPLAYNAME_REFRESH_MINUTES` | Cache-Dauer der Anzeigenamen-Map           | `60`                               |

### Konfiguration des Keycloak-Plugins für mehrere verknüpfte PGK-Proxys

Für den Testbetrieb kann es gewünscht sein, mehrere Matrixserver und PGK-Proxys an einem Keycloak zu betreiben. Dafür
muss pro PGK-Proxy ein eigener Realm im Keycloak-IDP erstellt werden und die PGK-Proxys und Matrix Server auf diesen
Realm verweisen. Damit die Integration (automatisches Erstellen der Practitioner-Ressourcen und Erstellung der Display
Names) auch in diesen Realms funktioniert, sind die folgenden Keycloak Realm-Attribute über die Admin-API oder per
Realm-Import zu setzen um die per Umgebungsvariable im Deployment konfigurierten Einstellungen für diesen Realm zu
überschreiben:

```
"pgk.matrix-domain": "<URL des verbundenen Matrix-Servers>"
"pgk.proxy-base-url": "<URL des verbundenen PGK-Proxys>"
```

Dieser Schritt ist nur dann notwendig, wenn ein Keycloak pro Realm unterschiedliche PGK-Proxys / Matrixserver
synchronisieren soll.

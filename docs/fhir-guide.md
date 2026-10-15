# FHIR-Leitfaden

Dieser Leitfaden beschreibt, welche FHIR-Ressourcentypen der PGK-Proxy verwendet, wie sie
miteinander verknüpft sind und wie die Verzeichnis- und Föderationsfunktionen auf dem HAPI-FHIR-
Server abgebildet werden.

Verwandte Dokumente: [Setup](setup.md), [Benutzerhandbuch](user-guide.md),
[Administrationshandbuch](admin-guide.md), [Technische Abläufe](technical-flows.md).

## Überblick

Der Proxy verwendet den **HAPI FHIR Server (R4)** als „Provider Directory“. Konfiguriert wird
der Server über `pgk.fhir.base-url` (Standard `http://localhost:8081/fhir`). Alle Such- und
Schreibadapter liegen unter `search/adapter/outbound`, `invitation/adapter/outbound` und
`provisioning/adapter/outbound`.

```
Practitioner <── PractitionerRole ──> Organization ──> Organization (partOf-Kette bis Wurzel)
    │  │           | 
    │  │           └── code: Coding {system}|{code} (Rollenkatalog aus CodeSystem)
    │  └── telecom: matrix:u/<mxid>  ◄── MXID-Auflösung
    └── meta.security: trust-level + visibility

HealthcareService ── telecom: matrix:u/<mxid>
Endpoint ── connection-type matrix-homeserver, address = <fqdn>  (Föderations-Whitelist)
```

## Verwendete Ressourcentypen

### Practitioner

Repräsentiert einen im IDP verwalteten Benutzer (Gesundheitsdiensteanbieter). Er wird vom
Proxy **automatisch erzeugt, aktualisiert und gelöscht**, sobald sich im IDP etwas ändert
(Keycloak-Event-Listener → `POST /internal/idp/user-events`):

| Feld | Wert |
| --- | --- |
| `identifier` | System `PGK_IDENTIFIER_SYSTEM` (Standard `https://idp.traefik.me/realms/pgk`), Wert = Keycloak-Benutzer-UUID |
| `name` | Vor- und Nachname aus dem IDP (`HumanName`, use `official`) |
| `telecom` | System `PGK_MATRIX_TELECOM_SYSTEM` (Standard `url`), Wert `matrix:u/<mxid>:<domain>`, use `work` |
| `meta.profile` | `PGK_PRACTITIONER_PROFILE` (Standard HL7 Austria `at-core-practitioner`) |
| `meta.security` | Trust-Level-Coding (`tl-2` bei provisionierten Benutzern) |

Ein Benutzer ohne gesetztes `mxid`-Attribut wird nicht nach FHIR geschrieben; wird die `mxid`
entfernt oder der Benutzer gelöscht, wird der Practitioner gelöscht.

### PractitionerRole

Beschreibt, welche Rolle ein Practitioner in einer Organisation ausübt. Wird über die
Admin-Seite **„Rollen zuweisen“** oder die Benutzer-Seite **„Rollen“** erzeugt:

| Feld | Wert                                                                              |
| --- |-----------------------------------------------------------------------------------|
| `practitioner` | Referenz auf den `Practitioner` (`Practitioner/<id>`)                             |
| `organization` | Referenz auf die `Organization` (`Organization/<id>`)                             |
| `code` | `CodeableConcept` mit Coding `{system}\|{code}` aus dem Rollenkatalog plus `text` |
| `meta.profile` | HL7 Austria `at-core-practitionerRole`                                            |
| `meta.security` | Trust-Level-Coding                                                                |

Der **Rollenkatalog** stammt aus lokalen FHIR-`CodeSystem`-Dateien unter
`src/main/resources/valuesets/` (z. B. `elga-gtelvogdarollen`, `elga-fachaerzte`). Ein Rollen-Token
ist `{CodeSystem-URL}|{Code}`, z. B. `https://termgit.elga.gv.at/CodeSystem/elga-gtelvogdarollen|222`.
Der Katalog wird gecacht und periodisch neu geladen.

### Organization

Repräsentiert Ordinationen, Krankenanstalten usw. Organisationen können über `Organization.partOf`
verschachtelt sein; die Such- und Konsolidierungslogik baut daraus eine **Organisationskette** bis
zur Root-Organisation. Bei der Sichtbarkeitsprüfung zählt die Wurzelorganisation: Ein interner
Eintrag ist nur für Benutzer mit derselben Root-Organisation sichtbar.

### HealthcareService

Alternative Darstellung eines Gesundheitsdienstes im Verzeichnis. Wie der Practitioner trägt er
seine Matrix-ID im `telecom`-Feld (das die Suchadapter über den `healthcareservice-telecom`-
SearchParameter durchsuchen). Er besitzt ebenfalls Visibility- und Trust-Level-Tags und kann
somit ebenfalls intern/öffentlich geschaltet werden.

### Endpoint

Trägt die **Föderations-Whitelist**. Der Proxy sucht regelmäßig alle `Endpoint`-Ressourcen mit
`connection-type` = `https://pgk.elga.gv.at/CodeSystem/endpoint-connection-type` +
Code `matrix-homeserver` und übernimmt deren `address` (FQDN) in die in-memory-Whitelist
(Refresh-Intervall `PGK_FEDERATION_REFRESH_INTERVAL_MINUTES`, Standard 60 Minuten). Ein Server
darf nur einladen bzw. adressiert werden, wenn er in dieser Whitelist steht.

### SearchParameter

Der FHIR-Server wird per Init-Container mit zusätzlichen Suchparametern versorgt
(`config/pgk-fhir/searchparams/`, Upload über `pgk-fhir-post-init`):

| Datei | Suchparameter                                                                                 | Zweck |
| --- |-----------------------------------------------------------------------------------------------| --- |
| `healthcareservice-telecom.json` | `telecom` (token) auf `HealthcareService.telecom`                                             | Suche von Healthcare Services über die Matrix-ID |
| `practitionerrole-role-text.json` | `role-text` (string) auf `PractitionerRole.code.text \| PractitionerRole.code.coding.display` | Namenssuche zusätzlich über Rollenbezeichnungen |

### CodeSystem (lokal)

Die unter `src/main/resources/valuesets/` abgelegten `CodeSystem`-Ressourcen werden nicht an den
FHIR-Server geschrieben, sondern vom Proxy direkt aus dem Klassenpfad gelesen (`classpath:valuesets/`,
konfigurierbar über `pgk.role-catalog.local-folder`).

## Suche und Verknüpfung (revinclude-Modus)

Die Standardsuche (`pgk.fhir.search-mode: revinclude`) lädt bei einer Benutzersuche die passenden
`Practitioner`-Ressourcen zusammen mit ihren `PractitionerRole` (via `_revinclude
PractitionerRole:practitioner`), den zugehörigen `Organization` (via `PractitionerRole:organization`)
und der gesamten `partOf`-Kette (`Organization:partof` rekursiv). Die Suche erfolgt dreifach:

1. `Practitioner.name` – Namenssuche
2. `PractitionerRole` mit `role-text` – Rollensuche
3. `Organization.name` – Organisationssuche

Die gefundenen Ressourcen werden zu einem `PractitionerSearchBundle` zusammengeführt, und der
`PractitionerConsolidator` baut daraus je MXID einen `ConsolidatedUser` (Anzeigename, Rollen,
Organisationsketten, Sichtbarkeit). Server ohne `_revinclude`-Unterstützung (z. B. pineit) nutzen
den Modus `pgk.fhir.search-mode: forward` mit separaten Nachschlage-Adapter.

## Sicherheits-Metadaten (Tags)

Zwei `meta.security`-Tags steuern Trust-Level und Sichtbarkeit:

| CodeSystem | Codes |
| --- | --- |
| `https://pgk.elga.gv.at/CodeSystem/trust-level` | `tl-1` … `tl-4` |
| `https://pgk.elga.gv.at/CodeSystem/visibility` | `public`, `internal` |

Die Sichtbarkeit wird über die Admin-Seite **„Sichtbarkeit“** als Security-Tag an
`Practitioner` bzw. `HealthcareService` gesetzt (bzw. vorheriges Tag ersetzt).

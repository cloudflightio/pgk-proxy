# PGK Proxy – PoC

Dieses Repository enthält einen Proof of Concept für die Proxy-Komponente des Projekts
„Gerichtete ad-hoc-Kommunikation“ (PGK) im österreichischen Gesundheitswesen, ursprünglich
entwickelt von Cloudflight für die Stadt Wien (MA 01 Wien Digital).

Der `pgk-proxy` ist ein Kotlin-/Spring-Boot-Service, der einen lokalen Matrix-Synapse-Server mit
einem HAPI-FHIR-Server verbindet. Er stellt die FHIR-gestützte Benutzerverzeichnis-Suche am
Matrix-Endpunkt `POST /_matrix/client/v3/user_directory/search` (Backing-Store) und die Prüfung
von föderierten Raumeinladungen bereit. Der restliche Matrix-Traffic geht unverändert direkt an
Synapse (der Proxy liegt nicht im Datenpfad).

## Architektur im Überblick

```
Matrix-Client (Element, Element X, FluffyChat …)
        │  HTTPS
        ▼
   Traefik (:443 / :8448)
   │  ├─ /_matrix/client/v3/user_directory/search ──► pgk-proxy ──► FHIR (Provider Directory)
   │  ├─ /_matrix/client/v3/createRoom, /_matrix/client/v3/rooms/{roomId}/invite (Ausgangsprüfung) ──► pgk-proxy ──► Matrix-Server
   │  ├─ /_matrix/federation/v2/invite* (Einlassprüfung) ──► pgk-proxy ──► Matrix-Server
   │  └─ alles andere ──► Matrix Server
   ▼
Matrix-Server ◄── Föderation (8448) ──► externe föderierte Matrix-Server
```

Der Proxy liegt nur im Datenpfad der oben markierten Endpunkte; sämtlicher übriger
Matrix-Traffic geht unverändert an den lokalen Matrix-Server (Synapse).

## Dokumentation

Die Dokumentation ist nach Themen in Unterordner `docs/` aufgeteilt:

| Thema | Inhalt |
| --- | --- |
| [Lokales Setup](docs/setup.md) | Voraussetzungen, Zertifikatserstellung (mkcert), Build des Keycloak-Providers, Starten des docker-compose-Stacks, Zugriff auf die lokale Umgebung, Föderations-Partnerumgebung und Tests |
| [Benutzerhandbuch](docs/user-guide.md) | Anmeldung, Konfiguration von Matrix-Clients (Element, Element X, FluffyChat), Föderation, eigene Rollen und persönliche Einladungsregeln |
| [Administrationshandbuch](docs/admin-guide.md) | Benutzer im IDP anlegen, Rollen zuweisen, intern/öffentlich schalten, organisationsweite Einladungs-/Föderationsregeln, Anzeigenamen steuern |
| [FHIR-Leitfaden](docs/fhir-guide.md) | Verwendete FHIR-Ressourcentypen und wie sie miteinander verknüpft sind |
| [Technische Abläufe](docs/technical-flows.md) | Ablaufdiagramme für die föderierte Benutzersuche über den Proxy und den FHIR-Server sowie für die Prüfung einer föderierten Raumeinladung – inklusive Beispielanfragen |

## Schnellstart

1. Zertifikate erzeugen (siehe [docs/setup.md](docs/setup.md#zertifikate-erzeugen)).
2. Keycloak-Provider bauen: `./gradlew :keycloak-provider:keycloakProviderJar`
3. Stack starten: `docker-compose up -d`
4. Proxy lokal starten (Profil `local`)
5. Im IDP https://idp.element.traefik.me/admin mit (`admin`/ `admin`) einloggen und im `pgk` Realm neue User anlegen.
5. Unter https://element.traefik.me mit diesen Usern anmelden und loslegen.

Ausführliche Schritte: [docs/setup.md](docs/setup.md).

## Technische Eckdaten

- **Backing-Store:** HAPI FHIR R4 unter `pgk.fhir.base-url` (Standard `http://localhost:8081/fhir`).
- **Routing:** Traefik leitet `matrix.traefik.me` – mit Ausnahme der oben genannten
  Proxy-Endpunkte – direkt an Synapse weiter. Siehe `dynamic.yml`.
- **IDP-Provisionierung:** Ein Keycloak-Event-Listener (`keycloak-provider`-Gradle-Subprojekt)
  meldet Benutzeränderungen über `POST /internal/idp/**` an den Proxy, der daraus FHIR-Practitioner
  erstellt/aktualisiert/löscht.
- **Föderation:** Föderierte Einladungen an `matrix.traefik.me` laufen über
  u.a. `/_matrix/federation/v2/invite` durch den Proxy, der Whitelist-, Organisations- und
  persönliche Regeln prüft, bevor er an Synapse weiterleitet.

## Lizenz

Diese Software ist unter der [Apache License 2.0](LICENSE.txt) lizensiert.
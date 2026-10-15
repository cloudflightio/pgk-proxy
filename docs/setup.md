# Lokales Setup

Diese Anleitung beschreibt, wie die komplette lokale Entwicklungsumgebung für den PGK-Proxy
aufgesetzt wird: der docker-compose-Stack (Synapse, Element, HAPI FHIR, Keycloak, Traefik,
Squid) inklusive Zertifikatserstellung, das Bauen des Keycloak-Providers, der Start des
Proxy-Prozesses und die Föderations-Partnerumgebung.

## Übersicht der Dokumente

- [README (Start)](../README.md)
- [Benutzerhandbuch](user-guide.md)
- [Administrationshandbuch](admin-guide.md)
- [FHIR-Leitfaden](fhir-guide.md)
- [Technische Abläufe](technical-flows.md)

## Voraussetzungen

Die Einrichtung wurde auf Windows 11 und macOS getestet. Es wird eine Docker-Runtime mit
docker-compose benötigt (z. B. [Rancher Desktop](https://www.rancher.com/products/rancher-desktop)).
Dem Docker-Orchestrator müssen mindestens **4 GB RAM** zuordenbar sein.

Für den Build von Proxy und Keycloak-Provider ist ein **Java-Toolchain 21** erforderlich.

## Zertifikate erzeugen

Lokale Hostnamen verwenden die Wildcard-Domain `*.traefik.me`, die über den öffentlichen DNS auf
`127.0.0.1` aufgelöst wird. Das umgeht den `.localhost`-Sonderfall (RFC 6761), bei dem
serverseitige Föderation immer auf das eigene Loopback-Interface zeigen würde, und erspart
`/etc/hosts`-Einträge auf dem Host.

1. **mkcert installieren**

   - Windows: `winget install FiloSottile.mkcert` (als Administrator)
   - macOS: `brew install mkcert`

2. **Lokale Root-CA anlegen:** `mkcert -install`

3. **Zertifikat für die App erstellen** – es muss alle Client- UND Föderations-Partner-Hostnamen
   abdecken, damit beide Server und IDPs über HTTPS/8448 erreichbar sind:

   ```shell
   mkcert --cert-file certs/app.traefik.me.crt -key-file certs/app.traefik.me.key \
     idp.traefik.me matrix.traefik.me element.traefik.me \
     idp-partner.traefik.me partner.traefik.me element-partner.traefik.me
   ```

4. **Zertifikat für die Spring-App** (localhost):

   ```shell
   mkcert --cert-file src/main/resources/certs/localhost.crt -key-file src/main/resources/certs/localhost.key localhost
   ```

5. **Root-CA in das `./certs`-Verzeichnis kopieren:** mit `mkcert -CAROOT` das CA-Verzeichnis
   ermitteln und daraus `rootCA.pem` nach `./certs` kopieren.

Wenn das Verzeichnis `./certs` so aussieht, kann es weitergehen:

```
certs
- .gitkeep
- app.traefik.me.crt
- app.traefik.me.key
- rootCA.pem
```

Beachten Sie den Hinweis von mkcert, dass Firefox ohne weitere Konfiguration nicht unterstützt wird.

## Build des Keycloak-Providers

Die PGK-Realm registriert einen Keycloak-Provider, der das Provider Directory mit dem IDP
synchron hält (siehe [Administrationshandbuch](admin-guide.md#benutzer-im-idp-verwalten)). Er
liegt im Gradle-Subprojekt `keycloak-provider`. Das JAR muss vor dem Start von Keycloak gebaut
werden:

```shell
./gradlew :keycloak-provider:keycloakProviderJar
```

Das resultierende `keycloak-provider/build/keycloak/pgk-idp-event-listener.jar` wird von
`docker-compose.yml` in das Verzeichnis `/opt/keycloak/providers` von Keycloak eingehängt.

## docker-compose-Stack starten

Mit vorhandenen Zertifikaten, gebautem Keycloak-Provider-JAR und laufender Docker-Runtime aus dem
Repository-Root:

```shell
docker-compose up -d
```

Der Stack richtet alle Abhängigkeiten ein und konfiguriert sie automatisch:

- **matrix** / **matrix-partner** – Synapse-Server `matrix.traefik.me` und `partner.traefik.me`
  inklusive Init-Container (Signing-Key-Erzeugung, CA-Bundle, Dateirechte).
- **pgk-fhir** – HAPI-FHIR-Server (R4) mit Postgres (`pgk-db`) und Lucene-Index, inklusive
  Init-Container, die die SearchParameters aus `config/pgk-fhir/searchparams/` hochladen.
- **keycloak** / **keycloak-partner** – IDP-Instanzen mit den Realms `pgk` bzw. `pgk-partner`.
- **traefik** – HTTP(S)- und Föderations-Routing (Ports 80, 443, 8448).
- **egress-proxy** – Squid-Proxy, über den der Primärserver seine ausgehende Föderation leitet.
- **element** / **element-partner** – Element-Web-Clients.

### Persistenz der PGK-Datenbank

Die pgk-proxy-Persistenz (Rolle `pgk` + Datenbank, siehe `config/pgk-db/init/01-pgk-db.sql`) wird
beim ersten Start eines leeren Volumes `pgk-db-data` initialisiert. Wenn Sie von einem Stack
aufgerüstet haben, der älter als das Einladungsrechte-Feature ist, muss das Volume einmal
verworfen werden, damit Rolle und Datenbank angelegt werden:

```shell
docker-compose down
docker volume rm "$(docker volume ls -q | grep pgk-db-data)"
docker-compose up -d
```

Analog gilt das für die Keycloak-Datenbank bei Änderungen an `config/keycloak/realm-export.json`
(siehe [Administrationshandbuch](admin-guide.md)).

## Zugriff auf die lokale Umgebung

| URL | Zweck |
| --- | --- |
| https://element.traefik.me | Vorkonfigurierter Element-Client („Continue with PGK Keycloak“ zum Anmelden) |
| https://matrix.traefik.me | Matrix-Server-URL (für andere Matrix-Clients) |
| https://idp.traefik.me | Keycloak-Admin (Realm `pgk`), Login `admin` / `admin` |

## Föderations-Partnerumgebung

Der Compose-Stack betreibt zusätzlich einen zweiten, isolierten Matrix-Server
(`partner.traefik.me`) mit eigenem Synapse, Postgres, Element-Client und Keycloak-IDP
(`idp-partner.traefik.me`, Realm `pgk-partner`), damit die Matrix-Föderation Ende-zu-Ende
getestet werden kann. Dieser Server ist NICHT durch einen PGK-Proxy abgesichert, sodass
auch Szenarien getestet werden können, in denen ein nicht-zugelassener Server in das
Netzwerk einzudringen versucht:

| URL | Zweck |
| --- | --- |
| https://element-partner.traefik.me | Element-Client für den Partnerserver |
| https://partner.traefik.me | Partner-Matrix-URL |
| https://idp-partner.traefik.me | Partner-Keycloak-Admin (`admin`/`admin`) |

Die Föderation zwischen den beiden Servern läuft über Port **8448** durch Traefik
(`Host(matrix.traefik.me)` / `Host(partner.traefik.me)`); beide Synapse-Container betreiben einen
eigenen Föderations-Listener auf 8448. Eingehende Föderations-Einladungen an
`matrix.traefik.me` werden vom pgk-proxy abgefangen (`/_matrix/federation/v2/invite` → Proxy),
der vor der Weiterleitung an Synapse die Föderations-Whitelist prüft.

> **Föderations-Auflösung der Peers:** Da `*.traefik.me` über den öffentlichen DNS auf
> `127.0.0.1` zeigt, erreichen sich die Peers nie über den öffentlichen Eintrag. Der
> Primärserver (`matrix`) schickt seine ausgehende Föderation über den `egress-proxy`
> (squid, siehe `HTTPS_PROXY` in `docker-compose.yml`), der `partner.traefik.me` auf das
> Host-Gateway pinnt (`extra_hosts: partner.traefik.me:host-gateway`). Der Partner
> (`matrix-partner`) löst `matrix.traefik.me` über die Docker-Netzwerk-Aliase im gemeinsamen
> `partner-net`-Netzwerk zum Traefik-Container auf. In beide Richtungen kommt der Traffic am
> Traefik-Entrypoint `federation` (Port 8448) an, der ihn an den jeweiligen Peer-Synapse
> weiterleitet.

## DNS für das lokale Setup

Es sind keine `/etc/hosts`-Einträge auf dem Host erforderlich: `*.traefik.me` ist ein öffentlicher
Wildcard-DNS-Eintrag, der auf `127.0.0.1` aufgelöst wird.

## Föderation und Proxy-Abfang testen

1. Zertifikate neu erzeugen (siehe oben) und `docker-compose up -d` ausführen.
2. In den PGK-Realms der IDPs `idp.traefik.me` und `idp-partner.traefik.me` User anlegen, falls nicht schon geschehen.
2. In Element auf `element.traefik.me` (matrix.traefik.me) und auf
   `element-partner.traefik.me` (partner.traefik.me) anmelden.
3. Von matrix.traefik.me einen Raum erstellen und über die vollständige MXID
   `@<mxid>:partner.traefik.me` einladen. Die Einladung geht an
   `partner.traefik.me:8448/_matrix/federation/v2/invite`.
4. Mit MXIDs des Users am Homeserver
   `@<mxid>:matrix.traefik.me` (aus dem `mxid`-Attribut des IDP)
   zurück einladen, um zu beobachten, wie der Proxy eingehende Föderation an
   `matrix.traefik.me` abfängt.

Um zu bestätigen, dass der Proxy (und nicht Synapse) die Einladung verarbeitet hat, während einer
Einladung in die pgk-proxy-Logs schauen: Der `InvitationController` protokolliert/verarbeitet die
Föderationsanfrage vor der Weiterleitung an Synapse.

## Proxy-App starten

Die Anwendung wird mit Gradle gebaut und mit dem Profil `local` gestartet, damit sie die
Ressourcen in der Docker-Umgebung erreicht:

```shell
./gradlew bootRun --args='--spring.profiles.active=local'
```

Alternativ die IDE-Run-Konfiguration `.run/pgk-proxy.run.xml` verwenden. Details zu den
Konfigurationswerten (FHIR-URL, Synapse-URL, IDP-Webhook-Secret usw.) finden sich in
`src/main/resources/application.yml` und `application-local.yml`.

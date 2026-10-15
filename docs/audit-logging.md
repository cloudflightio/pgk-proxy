# Audit-Logging

Dieses Dokument beschreibt das Audit-Logging des PGK-Proxys: wer welche Logzeilen schreibt,
welche Felder jede Zeile enthält und wie die Pipeline vom Aufruf bis zur Logausgabe funktioniert.

## Architektur

Das Audit-Logging folgt einem Producer-/Consumer-Modell:

- **`AuditLogger`** (`shared/events/AuditLogger.kt`) ist der einzige Producer. Der restliche
  Anwendungscode berührt nie einen SLF4J-Logger direkt, sondern ruft `auditLogger.info(...)` etc.
  auf. Er publiziert `AuditLogEvent` auf dem Spring-Event-Bus.
- **`AuditEventListener`** (`infrastructure/audit/AuditEventListener.kt`) ist die einzige Stelle,
  die pgk-proxy-Logausgabe schreibt. Er konsumiert die generischen `AuditLogEvent`s sowie die
  Domain-Audit-Events und rendert jede Zeile mit den Feldern `timestamp`, `actor` und `action`.

Die Logzeile hat das Format:

```
timestamp=<Instant> actor=<mxid|system> action=<action-token> <message>
```

## `AuditLogEvent`

Generischer Audit-Logeintrag, der über den Spring-Event-Bus publiziert wird:

- **`level`** – der Log-Level (`DEBUG`, `INFO`, `WARN`, `ERROR`).
- **`actor`** – die handelnde Entität: die Matrix-Mxid bei benutzergetriggerten Aktionen,
  `system` bei Hintergrund-Jobs und Startcode.
- **`action`** – kurzes dot-getrenntes Aktions-Token, siehe `AuditAction`.
- **`message`** – die menschenlesbare Nachricht (bereits vollständig formatiert).
- **`timestamp`** – wann die Aktion passiert ist.
- **`cause`** – optionale zugrunde liegende Exception.

## `AuditAction`

`AuditAction` ist die einzige Quelle für das kurze dot-getrennte Aktions-Token, das in jedem
`AuditLogEvent` steckt und im Feld `action=` jeder Audit-Logzeile erscheint. Die Werte sind
stabile Log-/Wire-Formate – bei Umbenennungen den Konstantennamen, nicht den Wert ändern.

## `AuditLogger`

Producer-Fassade mit zwei Aufrufarten:

- **Expliziter Actor:** `auditLogger.info("system", AuditAction.X, "...")` für Hintergrund-Jobs
  oder konkrete Mxids.
- **Impliziter Actor:** `auditLogger.info(AuditAction.X, "...")` – der Actor wird aus dem
  Security-Context aufgelöst (der OIDC-Subject, mit `system` als Fallback, wenn kein Benutzer
  authentifiziert ist).

Ohne verfügbaren Publisher (z. B. in Unit-Tests) werden Logaufrufe verworfen.

## Pufferung (`PendingAuditLogs`)

`PendingAuditLogs` puffert `AuditLogEvent`s, die erzeugt werden, bevor der Spring-Event-Bus
verfügbar ist – also aus `BeanFactoryPostProcessor`s oder Bean-Konstruktoren beim Start, wo noch
keine Event-Listener registriert sind. Gepuffert wird statt publiziert, weil ein Publishing vor
der Listener-Registrierung das Event verlieren würde.

Die Felder `publisher` und `ready` werden von der Audit-Infrastruktur beim `ApplicationReadyEvent`
befüllt. Der Publisher lebt hier und nicht im `AuditLogger`, weil `AuditLogger` bereits während
der `BeanFactoryPostProcessor`-Phase instanziiert werden kann – vor Verfügbarkeit der
Konstruktor-Injektion. Ein eigener injizierter Publisher im `AuditLogger` bliebe in diesem Fall
`null`.

## `AuditEventListener`

Der `AuditEventListener` ist der einzige Writer und verarbeitet sowohl generische `AuditLogEvent`s
als auch alle Domain-Audit-Events:

- `afterSingletonsInstantiated()` – loggt alles, was vor der Verfügbarkeit des Event-Buses
  erzeugt wurde (Startup-Logging).
- `flushPendingLogs()` beim `ApplicationReadyEvent` – setzt `publisher` und `ready` auf dem Bus,
  leert verbleibende gepufferte Einträge und loggt anschließend den Marker
  `action=system.ready Audit pipeline ready` über den `AuditLogger` selbst als End-to-End-Prüfung
  des generischen Audit-Pfads.

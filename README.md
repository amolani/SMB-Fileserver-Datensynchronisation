# SMB-Fileserver-Datensynchronisation

Samba-Audit-basierte Dateisynchronisation mit Java 17, rsync und OpenSSH. Der am 22.09.2026 abgeglichene Produktionsquellstand ist als Tag `production-import-2026-09-22` erhalten. `main` ergänzt persistente Aufträge, automatische Wiederholung, gespeicherte Lesestände und Schutzmaßnahmen für Betrieb und Pfade.

**Status:** Am 22.09.2026 wurde die gehärtete Anwendung nach automatischen Tests und isolierten Übertragungstests auf drei Produktionsservern aktiviert. Die eingesetzte Topologie sieht für jede Datei nur einen schreibenden Standort vor. Den geprüften Umfang, die kurze Sync-Unterbrechung und die weiterhin bestehenden Grenzen beschreibt der [Rollout-Bericht](docs/ROLLOUT-2026-09-22.md).

- Aufträge und Audit-Cursor werden gemeinsam auf Disk gesichert; bestätigte Arbeit wird je Ziel verfolgt.
- Ein ausgefallenes Ziel behält seine Aufträge; andere Ziele können weiterarbeiten.
- Vollständige Audit-Zeilen, erhaltene rotierte Logs und ein persistentes Volume ermöglichen Wiederaufnahme nach Neustarts.
- Fehlgeschlagene Renames löschen keine alte Zieldatei. Ein älteres Delete überschreibt keine inzwischen wieder vorhandene Quelle.
- SSH mit Host-Key-Prüfung, geschützte Argumente, schreibgeschützte Quellmounts, Ressourcen- und Log-Grenzen.
- Java-Regressionen, echte rsync-Kopien, Prozessabbruch/Wiederanlauf und Docker-Tests in GitHub Actions.

**Lokal bauen und testen**

Benötigt werden ein JDK 17, rsync, Python 3 und für den Container-Test Docker mit Compose. Der Java-Code benötigt keine externen Bibliotheken.

```sh
./scripts/test.sh
python3 scripts/integration-test.py
docker compose config --quiet
docker compose build
python3 scripts/container-test.py
```

Das ausführbare Artefakt entsteht unter `build/fileserversync.jar`; ein erfolgreiches CI stellt es außerdem als Workflow-Artefakt bereit.

**Konfiguration vorbereiten**

```sh
cp config.example.properties config.properties
cp .env.example .env
mkdir -p secrets
chmod 700 secrets
```

Ziele und standortspezifische Einstellungen in `config.properties` setzen. Einen dedizierten SSH-Schlüssel sowie die separat verifizierten Host-Keys unter `secrets/` bereitstellen oder die Hostpfade in `.env` anpassen. Echte Konfiguration, Schlüssel, Statusdateien und Logs sind vom Git-/Docker-Kontext ausgeschlossen. Das gesamte Audit-Verzeichnis muss eingebunden bleiben, damit umbenannte Logs erreichbar sind.

Vor einem Start zuerst [Betrieb und Migrationsverfahren](docs/OPERATIONS.md) lesen. Im vorbereiteten Container kann die Konfiguration ohne Übertragung geprüft werden:

```sh
docker compose run --rm fileserversync --check-config
```

Ein Start mit `docker compose up -d` überträgt anschließend tatsächliche Dateivorgänge an die eingetragenen Ziele und gehört erst nach der Staging-Abnahme zum Rollout. `docker compose logs -f fileserversync` und `docker compose ps` zeigen den Betrieb. Das benannte Volume `filesync-state` dauerhaft erhalten.

**Bewusste Grenzen**

Löschweitergabe (`propagate.deletes`) und unverschlüsselter rsync-Daemon sind in der Beispielkonfiguration deaktiviert. Zustellung ist mindestens einmal; gleichzeitige Änderungen derselben Datei auf mehreren Servern haben keine automatische Konfliktauflösung. Ein fehlender Audit-Verlauf wird nicht durch Vermutungen über den Sollbestand ersetzt. Die Anwendung ist kein Backup und keine vollständige Hochverfügbarkeitslösung für den Quellserver.

Der [Produktionsvergleich](docs/PRODUCTION-COMPARISON.md) dokumentiert Herkunft, Unterschiede und den Umfang der Prüfung. Die [Betriebsdokumentation](docs/OPERATIONS.md) beschreibt Queue, Rotation, Relay, Monitoring, Migration und Rollback.

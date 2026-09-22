# Abgleich mit dem Produktionsstand

Am 22.09.2026 wurde der vorhandene Produktionsquellcode lesend übernommen. Die Quelldatei vom 27.04.2026 erzeugte bei separater Kompilierung alle sechs Klassendateien der laufenden Container-JAR bytegenau. Das ältere JAR neben dem Dockerfile war nicht das verwendete Build-Artefakt. Produktionsadressen, SSH-Schlüssel, echte Konfigurationen und Audit-Inhalte wurden nicht in dieses Repository übernommen.

Der Tag `production-import-2026-09-22` enthält diesen ursprünglichen Quellstand mit neutraler Beispielkonfiguration. Er dokumentiert die Herkunft und ist keine Empfehlung, diese ältere Fassung neu auszurollen.

| Bereich | Produktionsfassung | Aktuelle Fassung |
|---|---|---|
| Konfiguration und Build | Externe Properties, eigenständiger Java-17-Build | Beibehalten; Validierung und `--config`/`--check-config` ergänzt |
| Audit-Fortschritt | Nur RAM; Neustart überspringt vorhandene Einträge | Lesestand und angenommene Aufträge gemeinsam in einem synchronisierten Journal |
| Zielausfall | Fehler wird geloggt; Auftrag geht verloren | Auftrag bleibt für dieses Ziel offen; automatische Wiederholung mit begrenztem Backoff |
| Reihenfolge | Parallele Verarbeitung kann Operationen überholen | Je Ziel seriell, Ziele unabhängig; faire Verteilung begrenzter Worker |
| Warteschlange | Begrenzte Worker-Queue, aber unbegrenzte vorgelagerte Sammlungen | Begrenzte persistente Aufträge; bei Überlast wird der Leser angehalten |
| Logrotation | Einzeldatei-Bind-Mount; Offset nur im RAM | Verzeichnismount, persistente Dateiidentität, Drainage alter unkomprimierter Logs |
| Teilzeilen | `readLine()` kann unfertige Zeilen konsumieren | Nur vollständig mit Zeilenumbruch abgeschlossene Datensätze werden bestätigt |
| Lücken | Neustart oder Truncation kann Arbeit überspringen | Fehlende Inodes und veränderte Cursor-Umgebung führen zu einem sichtbaren Fehler |
| Healthcheck | Auch fehlendes Log kann Heartbeat erzeugen | Erfolgreicher Audit-Zyklus erforderlich; Rückstand und Zielausfälle werden sichtbar |
| Hängender Leser | Unhealthy führt allein nicht zum Neustart | Watchdog beendet bei dauerhaft fehlgeschlagenem Audit-Zyklus den Prozess |
| Löschungen | Verzeichnis-rsync mit `--delete-delay`; remote `rm -rf` | Keine pauschalen rsync-Löschungen; explizite Audit-Löschungen nur bei aktivierter Option und geprüften Pfaden |
| Rename | Risiko durch zusammengefasste Operationen und temporäre Dateien | Kopieren vor Löschen; Save-via-Temp bleibt erhalten; bekannte Rename-Ketten werden aufgelöst |
| Verbindungsprüfung | Positive/negative Erreichbarkeit dauerhaft gecacht | Kurzer Cache mit Ablauf und Invalidierung bei Fehlern |
| SSH | Genereller Zugriff auf das Host-SSH-Verzeichnis | Einzelne dedizierte Schlüssel-/Known-Hosts-Dateien, strikte Hostprüfung und geschützte Argumente |
| Laufzeit | Schreibbarer Quellmount, wenig Container-Grenzen | Quellmount und Root-Dateisystem schreibgeschützt, persistentes State-Volume, Speicher-/PID-/Log-Grenzen |
| Tests | Keine automatisierte Regression im Verzeichnis | Java-Regressionen, echter Prozessabbruch/Wiederanlauf, rsync- und isolierter Container-Test, GitHub CI |

Die tatsächlichen Audit-Formate wurden ohne Ausgabe von Benutzer-/Dateinamen gezählt: In einer Stichprobe passten 2.521 Datensätze zum Parserformat; Schreib-, Verzeichnis- und Löschoperationen hatten sechs Felder, Umbenennungen sieben, und die Pfade waren absolut. Das ersetzt keinen vollständigen Last-/Abnahmetest auf allen Standorten.

Auf dem Produktionssystem wurden keine Quellen, Konfigurationen, Container oder Dienste verändert. Ein Rollout der neuen Fassung ist ein eigener, noch ausstehender Schritt nach Klärung der Schreibtopologie und Staging-Abnahme.

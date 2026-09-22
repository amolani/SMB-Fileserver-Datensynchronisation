# Betrieb, Wiederanlauf und Migration

**Zuständigkeit und Schreibtopologie**

Diese Anwendung überträgt erfolgreiche Samba-Audit-Ereignisse aus einem konfigurierten Quellbaum zu konfigurierten Zielservern. Die Produktion unterstützte mehrere Ziele und optional einen Relay-Host; diese Möglichkeit bleibt erhalten. Alle beteiligten Server müssen den gleichen absoluten Datenpfad verwenden. Änderungen außerhalb von Samba werden durch das Audit-Verfahren nicht zuverlässig erfasst.

Gleichzeitige Änderungen derselben Datei auf mehreren Standorten haben keine automatische Konfliktauflösung. Vor einem Rollout muss geklärt werden, welche Seite für eine Datei maßgeblich ist. Es gibt absichtlich keinen automatischen vollständigen Spiegelabgleich mit `--delete`. Ein verteilter Lock, Versionskonflikte, Offline-Bearbeitung und ein geplanter Bestandsabgleich gehören zu einer weiteren, von der Topologie abhängigen Ausbaustufe. Das Journal macht Dienstneustarts und zeitweilige Zielausfälle beherrschbar; es ersetzt weder einen zweiten Quellserver noch Daten-Backups.

**Persistenz und Zustellgarantie**

`state.directory` enthält ein exklusiv gesperrtes, versioniertes Write-ahead-Journal. Ein gelesener Audit-Batch und sein neuer Lesestand werden in einem CRC-geprüften Datensatz gespeichert und vor der Übernahme in den Arbeitsspeicher mit `force(true)` auf den Datenträger geschrieben. Eine Zustellung wird je Ziel erst nach erfolgreichem Prozessabschluss separat bestätigt. Nach einem Abbruch kann ein Auftrag erneut ausgeführt werden: Die Garantie ist mindestens einmal, nicht genau einmal. Quelle, Statusvolume und Storage müssen zuverlässig bleiben.

Das Statusverzeichnis benötigt ein lokales Dateisystem mit Dateisperren, `fsync` und atomarem Umbenennen; kein gemeinsam genutztes NFS-Volume und keine zwei aktiven Instanzen auf demselben Status. Ein unvollständiger letzter Datensatz nach einem abgebrochenen Schreibvorgang wird abgeschnitten. Ein vollständiger Datensatz mit fehlerhafter Prüfsumme wird nicht übersprungen. Nach 32 MiB wird das Journal zu einer atomar ersetzten Momentaufnahme verdichtet; Speicher und freien Plattenplatz überwachen.

Aufträge bleiben bei einem ausgefallenen Ziel erhalten. Wiederholungen beginnen standardmäßig nach fünf Sekunden und steigen bis auf 300 Sekunden. Gesunde Ziele arbeiten unabhängig weiter. Je Ziel bleibt die Reihenfolge seriell, einschließlich Eltern-/Kindpfaden. Der Puffer ist begrenzt: Ist er voll, bleibt der Audit-Lesestand stehen, statt Einträge zu verlieren. Daher müssen Audit-Aufbewahrung und Storage die längste geplante Störung plus Rückstand abdecken. Ein dauerhafter Ausfall kann nicht mit endlichem Speicher unbegrenzt überbrückt werden.

Die Queue-Grenze gilt gemeinsam für alle offenen Aufträge. Eine lange Störung eines Ziels kann deshalb den Audit-Leser anhalten und auch neue Aufträge für gesunde Ziele verzögern. Bereits gelesene Aufträge bleiben je Ziel unabhängig. Kapazität und Log-Aufbewahrung anhand der tatsächlichen Ereignisrate dimensionieren und rechtzeitig alarmieren.

`buffer.time.seconds` ist eine Verzögerung ab dem ersten akzeptierten Auftrag. Nur direkt aufeinanderfolgende, noch nicht gestartete Updates derselben Datei werden zusammengefasst. Neue Writes während einer laufenden Übertragung erhalten einen Folgeauftrag. Es gibt keine dauernd nach hinten geschobene Frist bei kontinuierlichen Schreibzugriffen.

Die Compose-Konfiguration verwendet ein vom Dienst beschreibbares benanntes Docker-Volume. Bei einem eigenen Bind-Mount für `state.directory` müssen Besitzer und Schreibrechte zum Containerbenutzer passen (in dieser Konfiguration UID 0, empfohlen Modus 0700). Der Container besitzt absichtlich kein `DAC_OVERRIDE` und kann daher nicht beliebige Host-Verzeichnisse beschreiben. Journaldateien werden mit Modus 0600 angelegt.

**Audit-Log und Rotation**

Das gesamte Audit-Verzeichnis wird schreibgeschützt eingebunden. Unkomprimierte rotierte Dateien im selben Verzeichnis müssen erhalten bleiben, solange ein gespeicherter Lesestand auf sie zeigen kann. Unterstützt wird fortlaufende numerische Benennung (`samba_audit.log.1`, `.2`, …); mehrere zwischenzeitliche Rotationen werden in Reihenfolge gelesen. Bei abweichender Benennung oder fehlenden Zwischenständen wird nicht zum aktuellen Log gesprungen. Rotation durch Umbenennen und Neuanlegen verwenden; `copytruncate`, `dateext` und vorzeitige Komprimierung vermeiden. Der Leser prüft Dateiidentität, Länge und einen Hash der Bytes vor dem Lesestand und stoppt bei einer erkannten Lücke. Ein Austausch, der außerhalb dieser Prüfung liegt, ist nicht grundsätzlich erkennbar; es handelt sich nicht um ein transaktionales Änderungsjournal des Dateisystems.

`replay.existing.log.on.startup=false` gilt ausschließlich für den ersten Start ohne gespeicherten Cursor. Spätere Starts lesen immer ab dem gespeicherten Stand, auch wenn die Option weiterhin `false` ist. Ein Replay alter Audit-Einträge kann historische Löschungen ausführen; es ersetzt keinen kontrollierten Bestandsvergleich.

Der Parser erwartet den Produktionsprefix `Benutzer|Client|Freigabe` und danach `Operation|Status|AbsoluterPfad[|NeuerPfad]`. Es werden nur erfolgreiche `mkdirat`, `pwrite_send`, `pwrite_recv`, `unlinkat` und `renameat` verarbeitet. Dateinamen mit dem Audit-Trennzeichen `|` oder Steuerzeichen werden nicht unterstützt. Relative Pfade und Pfade außerhalb des Quellbaums werden verworfen. Temporäre `.tmp`-/`~$`-Dateien werden übersprungen; Umbenennungen von temporären zu regulären Dateien werden als Update übertragen. Diese Grenzen bei der Abnahme mit den eingesetzten Anwendungen prüfen.

**Löschungen und Änderungen während einer Störung**

`propagate.deletes=false` ist die Voreinstellung der Beispielkonfiguration. Sie bewahrt entfernte Dateien und alte Namen; übersprungene Löschungen werden trotzdem als erledigt bestätigt und später nicht automatisch nachgeholt. Für das bisherige Produktionsverhalten muss die Option nach Prüfung von Topologie und Backups ausdrücklich auf `true` gesetzt werden. Anders als die alte Fassung löscht auch dann ein normaler Verzeichnisabgleich keine zusätzlichen Dateien auf dem Ziel.

Eine neu angelegte Quelldatei wird nicht wegen eines älteren Delete-Ereignisses entfernt, sondern erneut kopiert. Bei einer Umbenennung wird erst die neue Datei übertragen und danach der alte Name entfernt. Ist das Kopieren fehlgeschlagen, bleibt der alte Name erhalten. Bereits gelesene Rename-Ketten werden bis zum derzeitigen Namen verfolgt. Wenn der neue Name fehlt und weder eine nachfolgende Umbenennung noch eine explizite Löschung dafür bekannt ist, bleibt der Auftrag offen. Das kann dieses Ziel bis zur Klärung blockieren; der Dienst löscht nicht auf Verdacht. Bei sehr langen Ereignisketten und vollem Puffer kann ein kontrollierter manueller Abgleich nötig werden.

Löschungen prüfen lokal die Pfadgrenze und entfernte Elternpfade. SSH und ein vertrauenswürdiger Zieladministrator bleiben erforderlich; diese Prüfungen sind keine Sandbox gegen konkurrierende, böswillige Änderungen am entfernten Dateibaum. Ein Zielserver sollte den rsync-/SSH-Zugang auf den vorgesehenen Baum und das Managementnetz begrenzen.

**Transport und Relay**

Standard ist SSH mit `BatchMode`, strenger Host-Key-Prüfung, einem dedizierten Schlüssel, Verbindungs-/Keepalive-Zeitlimits und rsync `--secluded-args`. Nur der Schlüssel und die verifizierte Known-Hosts-Datei werden einzeln eingebunden. `--contimeout` wird nur im Daemon-Modus verwendet. Plain-rsync-Daemon-Verbindungen sind nicht verschlüsselt; `rsync.daemon.enabled=true` nur für bewusst dafür vorgesehenes vertrauenswürdiges Netzwerk oder einen bereits abgesicherten Tunnel verwenden. Siehe [offizielle rsync-Dokumentation](https://download.samba.org/pub/rsync/rsync.1).

ACLs, erweiterte Attribute und numerische IDs werden mit `-aAX --numeric-ids` übertragen. `--relative` erhält die Pfadstruktur und erstellt benötigte Unterverzeichnisse. Es werden keine Devices oder Spezialdateien erzeugt. Symlink-Eltern im Quellpfad werden abgewiesen; `--safe-links` verwirft Links, die aus dem übertragenen Teilbaum herausführen. Solche Links sind daher kein garantiert unterstützter Synchronisationsinhalt. Der Prozess läuft im Container als root, um ACL-geschützte Quellen lesen zu können, mit schreibgeschützter Quelle und auf `DAC_READ_SEARCH` begrenzten zusätzlichen Rechten.

Mit `relay.host` kann ein direkt nicht erreichbares Ziel über einen Relay-Server erreicht werden. Die erste Kopie zum Relay muss erfolgreich sein; die zweite erfolgt derzeit über dessen rsync-Daemon-Verbindung zum Ziel. Der Relay benötigt denselben Datenpfad, rsync und für weitergereichte Löschungen einen funktionsfähigen SSH-Zugang zum Ziel einschließlich verifizierter Host-Keys. Die lokale Schlüsseldatei wird nicht automatisch an den Relay weitergereicht. Dieser Pfad benötigt einen eigenen Staging-Test mit der realen Netzwerkstruktur; die lokalen Tests prüfen unter anderem die verschachtelte Argumentquotierung, nicht das Produktivrouting.

**Monitoring und Wiederanlauf**

Der Heartbeat enthält `status`, PID, Anzahl wartender Aufträge, Alter des ältesten Auftrags und Zeitpunkt. Er wird nur nach erfolgreichem Audit-Zyklus erneuert. Zielausfälle, voller Puffer oder zu alter Rückstand führen zu `degraded`. Fehlendes Log oder ein persistenter Lesefehler lassen den Heartbeat veralten. Nach `watchdog.seconds` ohne erfolgreichen Zyklus beendet der Watchdog die Anwendung; `restart: unless-stopped` kann sie dann neu starten.

Ein Docker-Healthcheck allein startet einen Container nicht neu; Restart-Policies greifen beim Beenden eines Containers. Ein offline gebliebenes Ziel wird mit Backoff erneut probiert, ohne dafür fortlaufend den ganzen Dienst neu zu starten. Monitoring muss daher sowohl `unhealthy` als auch Wiederanlauf-Schleifen, Queue-Alter, Plattenfüllstand und Logfehler melden. Siehe [Docker Restart Policies](https://docs.docker.com/engine/containers/start-containers-automatically/).

Beim Stoppen nimmt der Dienst keine neuen Batches an und gibt Workern eine begrenzte Frist. Nicht abgeschlossene Prozesse werden beendet; nicht bestätigte Aufträge bleiben im Journal. Das Statusvolume bei `docker compose down` nicht mit `-v` löschen. Für eine konsistente externe Sicherung des Status zuerst den Dienst stoppen oder einen geeigneten Storage-Snapshot verwenden. Der Verlust dieses Volumes erfordert einen geplanten Neuabgleich; blindes Löschen zur Fehlerbehebung überspringt Arbeit.

**Vorgehen für die Migration**

1. Schreibtopologie, Löschsemantik, Zielpfade und Relay-Routing bestätigen. Unabhängige Datensicherungen und Rückkehr zum bisherigen Image vorbereiten.
2. Das bisherige Image, Docker-/Properties-Dateien und die Logs außerhalb dieses öffentlichen Repositories sichern. Die Produktions-JAR nicht mit der älteren JAR im Hostverzeichnis verwechseln.
3. Beispielkonfiguration kopieren und Ziele, Quellpfad, Zeitlimits und SSH-Dateien lokal setzen. Vorhandene Produktionskonfiguration nicht unverändert als neue Vorlage übernehmen: sichere Defaults für Daemon und Löschungen sind bewusst anders.
4. In einer isolierten Umgebung echte Windows-/Office-Dateivorgänge, große Dateien, ACLs/xattrs, Zielausfälle, Relay, Logrotation und einen Containerneustart abnehmen. Anschließend Queue und Stichproben auf allen Zielen vergleichen.
5. Für den Wechsel Schreibzugriffe kontrolliert anhalten und den bisherigen Dienst auslaufen lassen. Den anfänglichen Audit-Cursor und einen separat kontrollierten Bestandsabgleich planen. Ohne Zugriffspause oder belastbaren Übergabepunkt kann die erste Aktivierung Einträge zwischen altem und neuem Dienst verpassen.
6. Neuen Dienst mit eigenem Statusvolume starten, Healthcheck und Zielzustände prüfen, danach Schreibzugriffe wieder freigeben. Niemals beide Versionen parallel gegen dieselben Ziele laufen lassen.

Für einen Rollback den neuen Dienst stoppen, seinen Status erhalten und das bisherige Image mit seiner passenden Konfiguration verwenden. Die alte Version versteht das neue Journal nicht und überspringt bei ihrem Start möglicherweise Audit-Historie. Offene Aufträge und während des Wechsels entstandene Änderungen müssen deshalb kontrolliert abgeglichen werden. Ein Rollback ist kein automatischer verlustfreier Rücksprung.

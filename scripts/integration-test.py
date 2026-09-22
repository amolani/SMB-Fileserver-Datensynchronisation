#!/usr/bin/env python3
"""Exercise the real service lifecycle with temporary data and simulated remote commands."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

REPO = Path(__file__).resolve().parent.parent


def until(predicate, description, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError(description)


with tempfile.TemporaryDirectory(prefix="filesync-lifecycle-") as temporary:
    base = Path(temporary)
    source = base / "source"
    source.mkdir()
    audit = base / "audit.log"
    audit.touch()
    heartbeat = base / "health"
    failure = base / "fail"
    failure.touch()
    delivered = base / "delivered.jsonl"
    binaries = base / "bin"
    binaries.mkdir()
    fake = binaries / "rsync"
    fake.write_text("""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
base = Path(os.environ['FILESYNC_TEST_BASE'])
if (base / 'fail').exists():
    sys.exit(42)
with (base / 'delivered.jsonl').open('a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\\n')
""")
    fake.chmod(0o755)
    config = base / "config.properties"
    config.write_text(f"""sync.servers=replica-one.example,replica-two.example
root.path={source}
audit.log.path={audit}
state.directory={base / 'state'}
health.file={heartbeat}
buffer.time.seconds=0
replay.existing.log.on.startup=false
retry.initial.seconds=1
retry.max.seconds=1
watchdog.seconds=10
shutdown.timeout.seconds=1
rsync.daemon.enabled=false
propagate.deletes=false
""")
    env = {**os.environ, "FILESYNC_TEST_BASE": str(base), "PATH": str(binaries) + ":" + os.environ.get("PATH", "/usr/bin:/bin")}
    process = None
    output_file = (base / "application.log").open("w+")

    def start():
        return subprocess.Popen(["java", "-jar", str(REPO / "build/fileserversync.jar"), "--config", str(config)],
                                env=env, stdout=output_file, stderr=subprocess.STDOUT)

    def event(name):
        path = source / name
        path.write_text(name)
        with audit.open("a") as log:
            log.write(f"Sep 22 09:00:00 fileserver smbd_audit: user|client|share|pwrite_recv|ok|{path}\n")

    def deliveries():
        if not delivered.exists():
            return set()
        return {(Path(arguments[-2]).name, arguments[-1].split(":")[0])
                for arguments in map(json.loads, delivered.read_text().splitlines())}

    try:
        process = start()
        until(heartbeat.exists, "initial heartbeat was not written")
        event("before-crash.txt")
        until(lambda: "status=degraded" in heartbeat.read_text(), "offline targets were not reported")
        process.kill()
        process.wait(timeout=5)
        event("during-outage.txt")
        audit.rename(base / "audit.log.1")
        audit.touch()
        event("after-rotation.txt")
        failure.unlink()
        process = start()
        until(lambda: len(deliveries()) == 6, "restart did not recover all three events for both targets")
        until(lambda: heartbeat.exists() and "status=healthy" in heartbeat.read_text()
              and "queued=0\n" in heartbeat.read_text(), "delivery queue did not drain")
        health_env = {**env, "HEALTH_FILE": str(heartbeat)}
        assert subprocess.run([str(REPO / "scripts/healthcheck.sh")], env=health_env, capture_output=True).returncode == 0
        audit.unlink()
        original_mtime = heartbeat.stat().st_mtime_ns
        time.sleep(2)
        assert heartbeat.stat().st_mtime_ns == original_mtime, "missing audit log falsely refreshed heartbeat"
        stale_env = {**health_env, "HEALTH_MAX_AGE_SECONDS": "1"}
        assert subprocess.run([str(REPO / "scripts/healthcheck.sh")], env=stale_env, capture_output=True).returncode != 0
        until(lambda: process.poll() is not None, "watchdog did not exit on lost audit input", timeout=15)
        assert process.returncode == 2, process.returncode
        print("PASS: real service SIGKILL/restart, offline-target replay, rotated-log recovery, health degradation and watchdog exit")
    except Exception:
        output_file.flush()
        output_file.seek(0)
        print(output_file.read()[-12000:])
        raise
    finally:
        if process is not None and process.poll() is None:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        output_file.close()

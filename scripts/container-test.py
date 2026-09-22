#!/usr/bin/env python3
"""Start an isolated container with disposable data, no network and no credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)


def healthy(name):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if docker("exec", name, "/usr/local/bin/fileserversync-healthcheck", check=False).returncode == 0:
            return
        time.sleep(0.25)
    raise AssertionError("Container did not become healthy: " + docker("logs", name).stdout)


name = "filesync-validation-" + uuid.uuid4().hex[:12]
with tempfile.TemporaryDirectory(prefix="filesync-container-") as directory:
    base = Path(directory)
    for path in ["source", "audit", "state"]:
        (base / path).mkdir()
    (base / "audit/audit.log").touch()
    config = base / "config.properties"
    config.write_text("""sync.servers=unused.example
root.path=/source
audit.log.path=/audit/audit.log
state.directory=/state
buffer.time.seconds=0
replay.existing.log.on.startup=false
shutdown.timeout.seconds=1
""")
    args = ["run", "-d", "--name", name, "--network", "none", "--read-only", "--memory", "512m",
            "--pids-limit", "128", "--cap-drop", "ALL", "--cap-add", "DAC_READ_SEARCH",
            "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m,mode=1777"]
    for source, target, readonly in [(config, "/app/config.properties", True), (base / "source", "/source", True),
                                     (base / "audit", "/audit", True), (base / "state", "/state", False)]:
        args += ["--mount", f"type=bind,src={source},dst={target}" + (",readonly" if readonly else "")]
    args += [os.environ.get("FILESYNC_TEST_IMAGE", "smb-fileserver-datensynchronisation:local")]
    try:
        docker(*args)
        healthy(name)
        assert (base / "state/queue.wal").stat().st_size > 0
        assert docker("exec", name, "touch", "/source/must-not-write", check=False).returncode != 0
        assert docker("exec", name, "touch", "/app/must-not-write", check=False).returncode != 0
        (base / "audit/audit.log").rename(base / "audit/audit.log.1")
        (base / "audit/audit.log").touch()
        time.sleep(2)
        healthy(name)
        docker("kill", name)
        docker("start", name)
        healthy(name)
        docker("stop", "--time", "5", name)
        assert docker("inspect", name, "--format", "{{.State.ExitCode}}").stdout.strip() == "143"
        print("PASS: isolated Docker startup, read-only mounts, state persistence, log rotation, kill/restart and graceful stop")
    except Exception:
        result = docker("logs", name, check=False)
        print(result.stdout[-8000:] + result.stderr[-8000:])
        raise
    finally:
        docker("rm", "-f", name, check=False)

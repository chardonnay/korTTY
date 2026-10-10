#!/usr/bin/env python3
"""Check that korTTY still detects and can drive the coding agents installed on this machine.

For every agent in agents.json this script

* reads the installed version and compares it with ``verified_version``;
* starts the agent in a pseudo-terminal (throwaway home/config dirs, an empty working dir),
  records every distinct screen, and asks korTTY's real ``LocalProcessInspector`` which agent
  kind runs below the shell;
* in ``live`` mode sends a prompt the way korTTY's prompt box does (a single line followed by
  Enter), waits for the agent's tool-approval dialog, answers it with the agent's *deny* keys (the
  quick-answer path of korTTY's Coding Agents panel) and waits for the agent to settle again;
* classifies every recorded screen with korTTY's bundled rules (``CodingAgentDetector``).

Nothing is ever approved: every approval dialog is denied, the working directory stays empty.
Live agents talk to an OpenAI-compatible endpoint (``--base-url``, e.g. LM Studio), never to a
vendor account. ``smoke`` agents (account-bound, or no account here) are only started and recorded,
no prompt is sent.

Writes ``<out>/report.json`` and ``<out>/report.md`` and keeps every frame under
``<out>/<agent-id>/frames`` for review. Exit code 0 = everything as verified, 1 = something needs a
look (a version changed, a problem was found), 2 = the check itself could not run.

Usage::

    python3 scripts/coding-agents/check.py --out /path/to/scratch/agent-check \\
        [--base-url http://host:1234/v1] [--model openai/gpt-oss-20b] [--only codex,aider]

Requires pyte (installed into ``<out>/.venv`` automatically on first run) and a JDK; the korTTY
classes are compiled with Gradle.
"""

from __future__ import annotations

import argparse
import json
import os
import pty
import re
import select
import shutil
import signal
import struct
import subprocess
import sys
import time
import fcntl
import termios
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
COLS, ROWS = 120, 40
BRACKETED_ON = re.compile(rb"\x1b\[\?2004h")
ALT_ON = re.compile(rb"\x1b\[\?(?:1049|1047|47)h")
ALT_OFF = re.compile(rb"\x1b\[\?(?:1049|1047|47)l")


def ensure_pyte(out: Path) -> None:
    """Re-executes this script inside <out>/.venv with pyte when pyte is not importable."""
    try:
        import pyte  # noqa: F401
        return
    except ImportError:
        pass
    venv = out / ".venv"
    py = venv / "bin" / "python"
    if os.environ.get("KORTTY_AGENT_CHECK_VENV") == "1":
        sys.exit("pyte is still missing inside the venv; install it with: " + f"{py} -m pip install pyte")
    if not py.exists():
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        subprocess.run([str(py), "-m", "pip", "install", "-q", "pyte"], check=True)
    os.environ["KORTTY_AGENT_CHECK_VENV"] = "1"
    os.execv(str(py), [str(py), *sys.argv])


def korTTY_classpath(out: Path) -> str:
    """Compiles korTTY and returns its main runtime classpath (classes + resources + jars)."""
    init = out / "print-classpath.init.gradle.kts"
    init.write_text(
        'allprojects {\n  afterEvaluate {\n    tasks.register("printKorttyMainClasspath") {\n'
        "      doLast {\n        val ss = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)\n"
        '        println("KORTTY_CP=" + ss.getByName("main").runtimeClasspath.files.joinToString(":"))\n'
        "      }\n    }\n  }\n}\n")
    proc = subprocess.run(
        ["./gradlew", "-q", "--init-script", str(init), "compileJava", "processResources", "printKorttyMainClasspath"],
        cwd=REPO, capture_output=True, text=True)
    for line in proc.stdout.splitlines():
        if line.startswith("KORTTY_CP="):
            return line[len("KORTTY_CP="):]
    sys.stderr.write(proc.stdout[-2000:] + proc.stderr[-4000:])
    sys.exit(2)


class Probe:
    """korTTY's detector, kept running in one JVM (``CodingAgentProbe serve``)."""

    def __init__(self, classpath: str, kind: str):
        self.proc = subprocess.Popen(
            ["java", "-cp", classpath, str(HERE / "CodingAgentProbe.java"), "serve", kind],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)

    def classify(self, frame: Path) -> dict:
        self.proc.stdin.write(str(frame) + "\n")
        self.proc.stdin.flush()
        return json.loads(self.proc.stdout.readline())

    def close(self) -> None:
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()


def process_kind(classpath: str, shell_pid: int) -> dict | None:
    proc = subprocess.run(["java", "-cp", classpath, str(HERE / "CodingAgentProbe.java"), "process", str(shell_pid)],
                          capture_output=True, text=True, timeout=120)
    line = proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else "null"
    return json.loads(line)


class Session:
    """One agent in a pty, rendered by pyte; every distinct screen is written as a frame."""

    def __init__(self, argv: list[str], env: dict, cwd: Path, frames: Path):
        import pyte

        class TolerantScreen(pyte.Screen):
            # Private-marker SGR (CSI > … m) and device status requests (CSI ? … n) are ignored,
            # as by a terminal without support for them; pyte would raise on them.
            def select_graphic_rendition(self, *attrs, private=False, **kw):
                if not private:
                    super().select_graphic_rendition(*attrs)

            def report_device_status(self, mode=0, private=False, **kw):
                if not private:
                    super().report_device_status(mode)

        self.screen = TolerantScreen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)
        self.frames = frames
        frames.mkdir(parents=True, exist_ok=True)
        self.raw = open(frames.parent / "raw.bin", "wb")
        self.alternate = False
        self.bracketed = False
        self.count = 0
        self.last = None
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.chdir(cwd)
            # A shell stays the pty's foreground parent, as in a korTTY tab: korTTY looks for the agent
            # among the shell's descendants. The trailing ':' keeps sh from exec-ing the agent in place.
            os.execvpe("/bin/sh", ["/bin/sh", "-c", '"$@"; :', "sh", *argv], env)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))

    def pump(self, seconds: float) -> None:
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([self.fd], [], [], 0.05)
            if not r:
                continue
            try:
                data = os.read(self.fd, 65536)
            except OSError:
                return
            if not data:
                return
            self.raw.write(data)
            if ALT_ON.search(data):
                self.alternate = True
            if ALT_OFF.search(data):
                self.alternate = False
            if BRACKETED_ON.search(data):
                self.bracketed = True
            if b"\x1b[6n" in data:  # answer cursor position queries like a terminal
                os.write(self.fd, f"\x1b[{self.screen.cursor.y + 1};{self.screen.cursor.x + 1}R".encode())
            self.stream.feed(data)

    def snap(self, phase: str) -> Path | None:
        text = "\n".join(line.rstrip() for line in self.screen.display).rstrip()
        if text == self.last:
            return None
        self.last = text
        self.count += 1
        path = self.frames / f"{self.count:03d}-{phase}.txt"
        path.write_text(f"#! alt={str(self.alternate).lower()}\n{text}\n")
        return path

    def send(self, data: bytes) -> None:
        try:
            os.write(self.fd, data)
        except OSError:  # the agent already exited; nothing left to type into
            return
        self.pump(0.3)

    def alive(self) -> bool:
        try:
            pid, _ = os.waitpid(self.pid, os.WNOHANG)
            return pid == 0
        except ChildProcessError:
            return False

    def close(self) -> None:
        # pty.fork() made the shell a session (and process-group) leader: end the whole group, and
        # the shell itself in case the group is already gone.
        for sig in (signal.SIGTERM, signal.SIGKILL):
            for kill in (os.killpg, os.kill):
                try:
                    kill(self.pid, sig)
                except (ProcessLookupError, PermissionError):
                    pass
            time.sleep(1)
        try:
            os.waitpid(self.pid, 0)
        except ChildProcessError:
            pass
        self.raw.close()


def keys(spec: list[str]) -> list[bytes]:
    return [k.encode().decode("unicode_escape").encode("latin-1") for k in spec]


def fill(value: str, ctx: dict) -> str:
    for k, v in ctx.items():
        value = value.replace("{" + k + "}", v)
    return value


def resolve_bin(name: str) -> str | None:
    name = os.path.expanduser(name)
    if os.sep in name:
        return name if os.access(name, os.X_OK) else None
    return shutil.which(name)


def installed_version(cmd: list[str]) -> str | None:
    cmd = [os.path.expanduser(c) for c in cmd]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None
    out = (proc.stdout or proc.stderr).strip().splitlines()
    return out[0].strip() if out else None


def cleanup(pattern: str, home: Path) -> None:
    """Kills leftover helper processes (e.g. OpenCode's server) that belong to this run's home."""
    proc = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True)
    for pid in proc.stdout.split():
        env = subprocess.run(["ps", "-E", "-o", "command=", "-p", pid], capture_output=True, text=True).stdout
        if str(home) in env:
            try:
                os.kill(int(pid), signal.SIGKILL)
            except ProcessLookupError:
                pass


def run_agent(agent: dict, ctx: dict, classpath: str, out: Path, endpoint_ok: bool) -> dict:
    result = {"id": agent["id"], "kind": agent["kind"], "mode": agent["mode"],
              "verified_version": agent.get("verified_version")}
    binary = resolve_bin(agent["bin"])
    if not binary:
        result["status"] = "not-installed"
        return result
    result["binary"] = binary
    result["version"] = installed_version(agent["version_cmd"])
    # A user typing the bare name gets whatever comes first in PATH; another program of the same
    # name there (e.g. xAI's Grok Build in ~/.grok/bin before Superagent's grok-cli) is worth a note.
    name = os.path.basename(binary)
    on_path = shutil.which(name)
    shadowed_by = None
    if on_path and os.path.realpath(on_path) != os.path.realpath(binary):
        shadowed_by = on_path
        result["path_shadowed_by"] = on_path
    if agent["mode"] == "live" and not endpoint_ok:
        result["status"] = "skipped"
        result["reasons"] = ["the OpenAI-compatible endpoint is not reachable"]
        return result

    base = out / agent["id"]
    shutil.rmtree(base, ignore_errors=True)
    home, work, frames = base / "home", base / "work", base / "frames"
    work.mkdir(parents=True)
    home.mkdir(parents=True)
    actx = dict(ctx, home=str(home), work=str(work))
    for rel, content in agent.get("files", {}).items():
        target = home / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(fill(content, actx))
    env = dict(os.environ, TERM="xterm-256color")
    env.update({k: fill(v, actx) for k, v in agent.get("env", {}).items()})
    argv = [binary] + [fill(a, actx) for a in agent.get("args", [])]

    probe = Probe(classpath, agent["kind"])
    session = Session(argv, env, work, frames)
    classified: list[dict] = []

    def tick(phase: str, seconds: float) -> None:
        end = time.time() + seconds
        while time.time() < end and session.alive():
            session.pump(0.8)
            frame = session.snap(phase)
            if frame:
                classified.append(dict(probe.classify(frame), phase=phase))

    def last_state() -> str | None:
        return classified[-1]["state"] if classified else None

    def wait_for(states: set[str], phase: str, timeout: float) -> bool:
        end = time.time() + timeout
        while time.time() < end and session.alive():
            tick(phase, 1.0)
            if last_state() in states and classified[-1]["rule"]:
                return True
        return False

    notes: list[str] = []
    try:
        tick("startup", 12)
        if agent.get("startup_blocked_keys") and last_state() == "BLOCKED":
            for k in keys(agent["startup_blocked_keys"]):
                session.send(k)
            tick("startup", 5)
        result["process"] = process_kind(classpath, session.pid) if session.alive() else None
        if agent["mode"] == "live" and session.alive():
            result["bracketed_paste"] = session.bracketed
            session.send(agent["prompt"].encode())
            tick("prompt", 0.7)
            session.send(b"\r")
            saw_blocked = wait_for({"BLOCKED"}, "working", 120)
            result["saw_working"] = any(c["state"] == "WORKING" and c["rule"] for c in classified if c["phase"] == "working")
            result["saw_blocked"] = saw_blocked
            if saw_blocked:
                tick("blocked", 2)
                for k in keys(agent["deny_keys"]):
                    session.send(k)
                    tick("denied", 0.5)
                # The deny keys must take the agent out of its approval dialog.
                left = wait_for({"WORKING", "IDLE"}, "denied", 30)
                result["deny_left_blocked"] = left
                result["ended_idle"] = wait_for({"IDLE"}, "after", 60)
            else:
                notes.append("no approval dialog within 120 s (the model may have refused or answered without a tool)")
                result["ended_idle"] = last_state() == "IDLE"
        else:
            tick("smoke", 3)
    finally:
        session.close()
        probe.close()
        if agent.get("cleanup_pkill"):
            cleanup(agent["cleanup_pkill"], home)

    states: dict[str, int] = {}
    for c in classified:
        key = f"{c['state']}/{c['rule'] or 'fallback'}"
        states[key] = states.get(key, 0) + 1
    result["frames"] = len(classified)
    result["states"] = states
    result["unmatched_frames"] = [c["frame"] for c in classified if not c["rule"] and c["nonEmptyLines"] >= 4]

    problems: list[str] = []
    proc = result.get("process")
    if proc is None:
        problems.append("korTTY found no coding-agent process below the shell")
    elif proc.get("kind") != agent["kind"]:
        problems.append(f"korTTY detected the process as {proc.get('kind')}, expected {agent['kind']}")
    if not any(c["rule"] for c in classified):
        problems.append("no screen matched any rule, so korTTY would never report this agent")
    if agent["mode"] == "live":
        if result.get("saw_blocked") and not result.get("deny_left_blocked"):
            problems.append("the deny keys did not take the agent out of its approval dialog")
        if not result.get("saw_working"):
            notes.append("no WORKING screen was recognised after the prompt was sent")
    if result["unmatched_frames"]:
        notes.append(f"{len(result['unmatched_frames'])} non-blank frame(s) matched no rule (fallback state); review them")
    version_changed = agent.get("verified_version") and result.get("version") != agent["verified_version"]
    if version_changed:
        notes.append(f"version changed: verified {agent['verified_version']!r}, installed {result.get('version')!r}")
    if shadowed_by:
        notes.append(f"typing '{name}' in a shell runs {shadowed_by} (first in PATH), not {binary}")
    result["problems"] = problems
    result["notes"] = notes
    result["status"] = "problem" if problems else ("version-changed" if version_changed else "ok")
    return result


def write_report(out: Path, results: list[dict], ctx: dict) -> None:
    (out / "report.json").write_text(json.dumps({"endpoint": ctx["base_url"], "model": ctx["model"],
                                                  "results": results}, indent=2) + "\n")
    lines = ["# Coding-agent check", "", f"Endpoint: `{ctx['base_url']}` · model `{ctx['model']}`", "",
             "| Agent | Status | Installed | Verified | Process | States | Notes |", "|---|---|---|---|---|---|---|"]
    for r in results:
        proc = r.get("process") or {}
        states = ", ".join(f"{k}×{v}" for k, v in sorted(r.get("states", {}).items()))
        notes = "; ".join(r.get("problems", []) + r.get("notes", []) + r.get("reasons", []))
        lines.append(f"| {r['id']} | {r['status']} | {r.get('version') or ''} | {r.get('verified_version') or ''} "
                     f"| {proc.get('kind', '')} | {states} | {notes} |")
    (out / "report.md").write_text("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", required=True, type=Path, help="scratch directory for frames and the report")
    ap.add_argument("--base-url", default=os.environ.get("KORTTY_AGENT_CHECK_BASE_URL", "http://192.168.178.100:1234/v1"))
    ap.add_argument("--model", default=os.environ.get("KORTTY_AGENT_CHECK_MODEL", "openai/gpt-oss-20b"))
    ap.add_argument("--only", default="", help="comma-separated agent ids")
    args = ap.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    ensure_pyte(out)

    base_url = args.base_url.rstrip("/")
    ctx = {"base_url": base_url, "base_host": re.sub(r"/v1$", "", base_url), "model": args.model}
    try:
        with urllib.request.urlopen(base_url + "/models", timeout=10) as resp:
            endpoint_ok = resp.status == 200
    except Exception:
        endpoint_ok = False
    print(f"endpoint {base_url}: {'reachable' if endpoint_ok else 'NOT reachable'}", flush=True)

    classpath = korTTY_classpath(out)
    agents = json.loads((HERE / "agents.json").read_text())["agents"]
    only = {a.strip() for a in args.only.split(",") if a.strip()}
    results = []
    for agent in agents:
        if only and agent["id"] not in only:
            continue
        print(f"== {agent['id']} ({agent['mode']})", flush=True)
        try:
            r = run_agent(agent, ctx, classpath, out, endpoint_ok)
        except Exception as e:  # keep going; one broken agent must not stop the check
            import traceback
            r = {"id": agent["id"], "kind": agent["kind"], "mode": agent["mode"], "status": "problem",
                 "problems": [f"the check crashed: {e!r}"], "notes": [],
                 "traceback": traceback.format_exc()}
        print(f"   {r['status']}: {'; '.join(r.get('problems', []) + r.get('notes', []) + r.get('reasons', []))}", flush=True)
        results.append(r)
    write_report(out, results, ctx)
    print(f"report: {out / 'report.md'}")
    if not endpoint_ok:
        return 2
    return 0 if all(r["status"] in ("ok", "not-installed") for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Record real coding-agent terminal screens as detection fixtures.

Runs a coding agent (Claude Code, Codex CLI, Gemini CLI, ...) inside a pseudo-terminal, renders its
output with a VT100 emulator and writes the visible screen at interesting moments to plain-text
files in the fixture format understood by ``BundledRuleFixturesTest``::

    #! expect state=BLOCKED rule=permission-prompt
    #! title=<OSC window title, if any>
    #! alt=<true|false>
    <verbatim screen rows>

The ``expect`` line is written with ``state=REVIEW rule=REVIEW`` so a human fills in the expected
state and rule id before the file is copied to ``src/test/resources/coding-agents/<kind-id>/``.

Usage::

    scripts/capture-coding-agent-fixtures.py --out /tmp/claude-fixtures -- claude
    scripts/capture-coding-agent-fixtures.py --out /tmp/codex-fixtures --cols 120 --rows 40 -- codex

While the agent runs, type into it as usual; press F12 (or send SIGUSR1 to this script) to snapshot
the current screen, and Ctrl-] to stop recording. The agent's stdin/stdout are passed through, so
the session looks and behaves like a normal terminal session.

Requires the ``pyte`` package (``pip install pyte``) and a POSIX pty (macOS, Linux, WSL).
"""

from __future__ import annotations

import argparse
import fcntl
import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import tty

try:
    import pyte
except ImportError:  # pragma: no cover - dependency hint
    sys.stderr.write("pyte is required: pip install pyte\n")
    sys.exit(2)

OSC_TITLE = re.compile(rb"\x1b\]([02]);([^\x07\x1b]*)(?:\x07|\x1b\\)")
ALT_SCREEN_ON = re.compile(rb"\x1b\[\?(?:1049|1047|47)h")
ALT_SCREEN_OFF = re.compile(rb"\x1b\[\?(?:1049|1047|47)l")
SNAPSHOT_KEY = b"\x1b[24~"  # F12
STOP_KEY = b"\x1d"  # Ctrl-]


class Recorder:
    def __init__(self, out_dir: str, cols: int, rows: int) -> None:
        self.out_dir = out_dir
        self.screen = pyte.Screen(cols, rows)
        self.stream = pyte.ByteStream(self.screen)
        self.title: str | None = None
        self.alternate = False
        self.count = 0
        os.makedirs(out_dir, exist_ok=True)

    def feed(self, data: bytes) -> None:
        for match in OSC_TITLE.finditer(data):
            self.title = match.group(2).decode("utf-8", "replace")
        if ALT_SCREEN_ON.search(data):
            self.alternate = True
        if ALT_SCREEN_OFF.search(data):
            self.alternate = False
        self.stream.feed(data)

    def snapshot(self, label: str) -> str:
        self.count += 1
        path = os.path.join(self.out_dir, f"{self.count:02d}-{label}.txt")
        rows = [line.rstrip() for line in self.screen.display]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("#! expect state=REVIEW rule=REVIEW\n")
            if self.title:
                handle.write(f"#! title={self.title}\n")
            handle.write(f"#! alt={'true' if self.alternate else 'false'}\n")
            handle.write("\n".join(rows).rstrip("\n") + "\n")
        return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="directory for the fixture files")
    parser.add_argument("--cols", type=int, default=100)
    parser.add_argument("--rows", type=int, default=30)
    parser.add_argument("command", nargs=argparse.REMAINDER, help="agent command after '--'")
    args = parser.parse_args()
    command = [token for token in args.command if token != "--"]
    if not command:
        parser.error("agent command missing (e.g. -- claude)")

    recorder = Recorder(args.out, args.cols, args.rows)
    env = dict(os.environ)
    env.setdefault("TERM", "xterm-256color")
    pid, master = pty.fork()
    if pid == 0:  # child: the agent
        os.execvpe(command[0], command, env)

    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", args.rows, args.cols, 0, 0))
    stdin_fd = sys.stdin.fileno()
    saved = termios.tcgetattr(stdin_fd)
    tty.setraw(stdin_fd)
    pending_snapshot = {"flag": False}
    signal.signal(signal.SIGUSR1, lambda *_: pending_snapshot.__setitem__("flag", True))

    def status(text: str) -> None:
        os.write(sys.stderr.fileno(), f"\r\n[fixtures] {text}\r\n".encode())

    status(f"recording {' '.join(command)} -> {args.out}; F12 = snapshot, Ctrl-] = stop")
    try:
        while True:
            if pending_snapshot["flag"]:
                pending_snapshot["flag"] = False
                status(f"wrote {recorder.snapshot('snapshot')}")
            readable, _, _ = select.select([master, stdin_fd], [], [], 0.2)
            if master in readable:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    break
                if not data:
                    break
                recorder.feed(data)
                os.write(sys.stdout.fileno(), data)
            if stdin_fd in readable:
                data = os.read(stdin_fd, 4096)
                if not data:
                    break
                if data == STOP_KEY:
                    break
                if SNAPSHOT_KEY in data:
                    status(f"wrote {recorder.snapshot('snapshot')}")
                    data = data.replace(SNAPSHOT_KEY, b"")
                if data:
                    os.write(master, data)
    finally:
        termios.tcsetattr(stdin_fd, termios.TCSADRAIN, saved)
        try:
            os.kill(pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
    status(f"{recorder.count} fixture file(s) written to {args.out}; set the '#! expect' line before adding them to src/test/resources/coding-agents/")
    return 0


if __name__ == "__main__":
    sys.exit(main())

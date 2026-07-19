#!/usr/bin/env python3
# korTTY authenticated launcher around mlx_lm.server.
#
# mlx_lm.server ships without any authentication, so korTTY never starts it directly. This
# wrapper enforces a per-session Bearer token on every route except GET /health, refuses to run
# against an unpinned mlx-lm version, and self-exits after a configurable idle period because
# mlx_lm.server has no server-side sleep. It is stdlib-only apart from the pinned mlx_lm
# dependency and runs on the relocatable CPython shipped in the korTTY MLX runtime package.

import argparse
import hmac
import os
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

EXPECTED_MLX_LM_VERSION = "0.31.3"
VERSION_DRIFT_ENV = "KORTTY_MLX_ALLOW_VERSION_DRIFT"
READY_MARKER = "KORTTY-MLX-READY"
MIN_API_KEY_LENGTH = 16


def fail(message):
    print("kortty_mlx_server: " + message, file=sys.stderr, flush=True)
    raise SystemExit(1)


def parse_arguments():
    parser = argparse.ArgumentParser(description="korTTY authenticated wrapper around mlx_lm.server.")
    parser.add_argument("--model", required=True, help="Path to the local MLX model directory.")
    parser.add_argument("--host", default="127.0.0.1", help="Listen address; loopback only.")
    parser.add_argument("--port", required=True, type=int, help="Listen port.")
    parser.add_argument("--api-key-file", required=True, help="File containing the Bearer token.")
    parser.add_argument(
        "--max-idle-seconds",
        type=int,
        default=0,
        help="Exit after this many seconds without authenticated /v1 traffic (0 disables).",
    )
    return parser.parse_args()


def read_api_key(path_value):
    path = Path(path_value)
    try:
        key = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        fail("cannot read API key file %s: %s" % (path, error))
    if len(key) < MIN_API_KEY_LENGTH or any(character.isspace() for character in key):
        fail("API key file does not contain a usable key.")
    return key


def require_model_directory(path_value):
    path = Path(path_value)
    if not path.is_dir():
        fail("model directory does not exist: %s" % path)
    if not (path / "config.json").is_file():
        fail("model directory does not look like an MLX model (missing config.json): %s" % path)
    return path


def import_pinned_server_module():
    try:
        import mlx_lm
        from mlx_lm import server as server_module
    except Exception as error:  # noqa: BLE001 - any import failure must produce one clear line.
        fail("mlx_lm is not importable from this runtime package: %s" % error)
    version = getattr(mlx_lm, "__version__", None) or "unknown"
    if version != EXPECTED_MLX_LM_VERSION and os.environ.get(VERSION_DRIFT_ENV) != "1":
        fail(
            "pinned mlx-lm %s is required but %s is installed; set %s=1 only for local experiments."
            % (EXPECTED_MLX_LM_VERSION, version, VERSION_DRIFT_ENV)
        )
    # The auth wrapper depends on these pinned internals; a runtime with a different server API
    # must fail closed instead of serving an unreviewed, possibly unauthenticated surface.
    for required in ("APIHandler", "_run_http_server", "main"):
        if not hasattr(server_module, required):
            fail("mlx_lm.server no longer exposes %s; refusing to start an unreviewed server API." % required)
    return server_module


def main():
    args = parse_arguments()
    if args.host != "127.0.0.1":
        fail("korTTY sidecars are loopback-only; --host must be 127.0.0.1.")
    if args.port < 1 or args.port > 65535:
        fail("--port must be between 1 and 65535.")
    if args.max_idle_seconds < 0:
        fail("--max-idle-seconds must be zero or positive.")
    model_directory = require_model_directory(args.model)
    api_key = read_api_key(args.api_key_file)
    server_module = import_pinned_server_module()

    expected_authorization = ("Bearer " + api_key).encode("utf-8")
    activity_lock = threading.Lock()
    activity = {"last": time.monotonic(), "inflight": 0}

    def request_started():
        with activity_lock:
            activity["last"] = time.monotonic()
            activity["inflight"] += 1

    def request_finished():
        with activity_lock:
            activity["last"] = time.monotonic()
            activity["inflight"] -= 1

    class KorttyAuthHandler(server_module.APIHandler):
        """Rejects every request without the exact Bearer token; GET /health stays open."""

        def _kortty_authorized(self):
            header = self.headers.get("Authorization") or ""
            return hmac.compare_digest(header.encode("utf-8"), expected_authorization)

        def _kortty_reject(self):
            body = b'{"error": "Unauthorized"}'
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("WWW-Authenticate", "Bearer")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _kortty_handle(self, upstream):
            request_started()
            try:
                upstream()
            finally:
                request_finished()

        def do_GET(self):
            # /health must answer without credentials so the manager's readiness probe and the
            # idle watchdog never require the key on the wire; it does not reset the idle clock.
            if self.path == "/health":
                super().do_GET()
                return
            if not self._kortty_authorized():
                self._kortty_reject()
                return
            self._kortty_handle(super().do_GET)

        def do_POST(self):
            if not self._kortty_authorized():
                self._kortty_reject()
                return
            self._kortty_handle(super().do_POST)

        def do_OPTIONS(self):
            # Default-deny: korTTY's client never sends preflights, so an unauthenticated OPTIONS
            # only ever probes the API surface.
            if not self._kortty_authorized():
                self._kortty_reject()
                return
            super().do_OPTIONS()

    class KorttyReadyServer(ThreadingHTTPServer):
        def serve_forever(self, poll_interval=0.5):
            print("%s %d" % (READY_MARKER, self.server_address[1]), flush=True)
            super().serve_forever(poll_interval)

    if args.max_idle_seconds > 0:

        def idle_watchdog():
            interval = min(5.0, max(0.5, args.max_idle_seconds / 4.0))
            while True:
                time.sleep(interval)
                with activity_lock:
                    idle_for = time.monotonic() - activity["last"]
                    busy = activity["inflight"] > 0
                    if not busy and idle_for >= args.max_idle_seconds:
                        print("kortty_mlx_server: idle for %ds, exiting." % int(idle_for), flush=True)
                        # Exit while still holding the lock: request_started() acquires it first,
                        # so a request racing this tick cannot be admitted into a dying server.
                        # serve_forever blocks the main thread; only a hard exit reliably stops
                        # the threading server without leaving generation threads behind.
                        os._exit(0)

        threading.Thread(target=idle_watchdog, name="kortty-mlx-idle", daemon=True).start()

    original_run_http_server = server_module._run_http_server

    def kortty_run_http_server(host, port, response_generator, server_class=None, handler_class=None):
        # In the pinned mlx-lm, run() does not forward server/handler classes here, so this hook
        # is the single supported point to force korTTY's hardened server and handler.
        return original_run_http_server(
            host,
            port,
            response_generator,
            server_class=KorttyReadyServer,
            handler_class=KorttyAuthHandler,
        )

    server_module._run_http_server = kortty_run_http_server
    # Reuse the pinned upstream entry point (and with it every parser default of the pinned
    # version) instead of duplicating its argument namespace. An empty --allowed-origins list
    # disables CORS response headers entirely; korTTY's client is not a browser.
    sys.argv = [
        "kortty_mlx_server",
        "--model",
        str(model_directory),
        "--host",
        args.host,
        "--port",
        str(args.port),
        "--allowed-origins",
        "",
    ]
    server_module.main()


if __name__ == "__main__":
    main()

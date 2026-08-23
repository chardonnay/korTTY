#!/usr/bin/env python3
"""Verify korTTY's external API registry still matches the code and the guide.

Reads external-apis.yaml — the record of which third-party HTTP API version each
client speaks — and checks it two ways:

  --strict (CI gate, offline)
      Every entry's `endpoint` must still appear literally in its `source` file,
      and every page listed under `documented_in` must still name the same
      version. This is what stops an endpoint migration from landing with the
      registry and the guide left behind.

  --stale (scheduled job, offline)
      Lists entries whose `review_after` date has passed, i.e. the ones due for a
      fresh read of the vendor's docs. Exits non-zero when any are due so a
      workflow can turn the output into a tracking issue.

Neither mode calls the vendors: an API key would be needed, and a live probe
proves nothing about a version that still answers today but is announced for
retirement. The dated review is the signal.

Requires PyYAML (ships with the MkDocs toolchain in .venv-docs). Run via that
interpreter, e.g. `.venv-docs/bin/python scripts/external-api-check.py --strict`.
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:
    sys.exit("PyYAML is required. Run via .venv-docs/bin/python or `pip install pyyaml`.")

REPO_ROOT = Path(__file__).resolve().parent.parent
REGISTRY = REPO_ROOT / "external-apis.yaml"

REQUIRED_FIELDS = ("id", "name", "version", "endpoint", "source", "docs_url", "review_after")


def load_registry() -> list[dict]:
    if not REGISTRY.exists():
        sys.exit(f"Registry not found: {REGISTRY}")
    data = yaml.safe_load(REGISTRY.read_text(encoding="utf-8")) or {}
    apis = data.get("apis") or []
    if not apis:
        sys.exit("external-apis.yaml lists no APIs.")
    return apis


def check_shape(api: dict, problems: list[str]) -> bool:
    """False when the entry is too incomplete for the later checks to mean anything."""
    missing = [f for f in REQUIRED_FIELDS if not api.get(f)]
    if missing:
        problems.append(f"{api.get('id', '<no id>')}: missing field(s) {', '.join(missing)}")
        return False
    return True


def check_code(api: dict, problems: list[str]) -> None:
    source = REPO_ROOT / api["source"]
    if not source.exists():
        problems.append(f"{api['id']}: source file {api['source']} does not exist")
        return
    text = source.read_text(encoding="utf-8")
    if api["endpoint"] not in text:
        problems.append(
            f"{api['id']}: {api['source']} no longer contains the registered endpoint "
            f"{api['endpoint']} — update external-apis.yaml (and the guide) to match the code"
        )


def check_docs(api: dict, problems: list[str]) -> None:
    """
    The guide tells users which endpoint each provider talks to, so it is checked against the same
    literal as the code. The endpoint is the needle rather than the version string because a bare
    "v2" is shared by three of these providers and would match a page describing the wrong one.
    """
    endpoint = api["endpoint"]
    for page in api.get("documented_in") or []:
        path = REPO_ROOT / page
        if not path.exists():
            problems.append(f"{api['id']}: documented_in page {page} does not exist")
            continue
        if endpoint not in path.read_text(encoding="utf-8"):
            problems.append(
                f"{api['id']}: {page} does not name the endpoint {endpoint} — "
                f"the guide still describes an older API"
            )


def parse_review_date(api: dict, problems: list[str]) -> dt.date | None:
    try:
        return dt.date.fromisoformat(str(api["review_after"]))
    except ValueError:
        problems.append(f"{api['id']}: review_after '{api['review_after']}' is not an ISO date")
        return None


def run_strict(apis: list[dict]) -> int:
    problems: list[str] = []
    for api in apis:
        if not check_shape(api, problems):
            continue
        check_code(api, problems)
        check_docs(api, problems)
        parse_review_date(api, problems)

    if problems:
        print("External API registry is out of sync:\n")
        for problem in problems:
            print(f"  ✗ {problem}")
        print(f"\n{len(problems)} problem(s). See external-apis.yaml.")
        return 1

    print(f"External API registry OK: {len(apis)} API(s) match their client code and the guide.")
    return 0


def run_stale(apis: list[dict], today: dt.date, markdown: bool) -> int:
    problems: list[str] = []
    due: list[tuple[dict, dt.date]] = []
    for api in apis:
        if not check_shape(api, problems):
            continue
        review = parse_review_date(api, problems)
        if review and review <= today:
            due.append((api, review))

    if markdown:
        print("Scheduled review of the external HTTP APIs korTTY calls. Dependabot and the")
        print("pinned-artifact check cannot see these: a retired REST endpoint breaks no build,")
        print("it only starts failing on users' machines.")
        print()
        if due:
            print("| API | Version in korTTY | Due since | Vendor docs |")
            print("| --- | --- | --- | --- |")
            for api, review in sorted(due, key=lambda item: item[1]):
                print(f"| {api['name']} | `{api['version']}` | {review.isoformat()} | {api['docs_url']} |")
            print()
            print("For each row: re-read the vendor docs, confirm the version korTTY speaks is")
            print("still current and supported, then either open a migration PR or push")
            print("`review_after` forward in `external-apis.yaml`.")
        else:
            print("Every registered API is within its review window. ✅")
        print()
        print("<sub>Generated by `scripts/external-api-check.py --stale --markdown`.</sub>")
    else:
        for api, review in sorted(due, key=lambda item: item[1]):
            print(f"DUE {review.isoformat()}  {api['id']:<24} {api['version']:<10} {api['docs_url']}")
        if not due:
            print("No API is due for review.")

    for problem in problems:
        print(f"  ✗ {problem}", file=sys.stderr)
    return 1 if (due or problems) else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--strict", action="store_true",
                        help="fail when the registry no longer matches the code or the guide")
    parser.add_argument("--stale", action="store_true",
                        help="list APIs whose review_after date has passed; non-zero when any are due")
    parser.add_argument("--markdown", action="store_true",
                        help="with --stale, render an issue body instead of a plain list")
    parser.add_argument("--today", default=None,
                        help="override today's date (ISO) — for testing")
    args = parser.parse_args()

    apis = load_registry()
    if args.stale:
        today = dt.date.fromisoformat(args.today) if args.today else dt.date.today()
        return run_stale(apis, today, args.markdown)
    return run_strict(apis)


if __name__ == "__main__":
    raise SystemExit(main())

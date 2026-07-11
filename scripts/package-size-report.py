#!/usr/bin/env python3
"""Report and enforce korTTY native-package size budgets using only the stdlib."""

from __future__ import annotations

import argparse
import json
import math
import sys
import zipfile
from pathlib import Path
from typing import Any


MIB = 1024 * 1024


def parse_artifact(value: str) -> tuple[str, Path]:
    key, separator, path = value.partition("=")
    if not separator or not key or not path:
        raise argparse.ArgumentTypeError("artifact must be KEY=PATH")
    return key, Path(path)


def file_bytes(root: Path) -> int:
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def app_layout(root: Path) -> tuple[Path, Path]:
    contents = root / "Contents"
    base = contents if contents.is_dir() else root
    return base / "app", base / "runtime"


def app_bucket(relative: Path) -> str:
    parts = relative.parts
    if not parts:
        return "other"
    if parts[0] == "runtime":
        return "runtime"
    if parts[0] != "app" or len(parts) < 2:
        return "launcher-and-resources"
    name = parts[1]
    if name == "formatters":
        return "app/formatters"
    if name == "mosh4j":
        return "app/mosh4j"
    if name.startswith("korTTY-") and name.endswith(".jar"):
        return "app/kortty-jar"
    if name.startswith("javafx-web-"):
        return "app/javafx-web"
    if name.endswith(".jar"):
        return "app/other-jars"
    return "app/other"


def jar_bucket(name: str) -> str:
    first = name.split("/", 1)[0]
    if first in {"monaco", "guide", "chatrender", "formatters-web", "fonts", "icon", "i18n"}:
        return first
    if name.endswith(".class"):
        return "classes"
    return "other"


def analyze_app_image(root: Path) -> dict[str, Any]:
    if not root.is_dir():
        raise FileNotFoundError(f"app image not found: {root}")
    base = root / "Contents" if (root / "Contents").is_dir() else root
    app_dir, runtime_dir = app_layout(root)
    buckets: dict[str, int] = {}
    files = [path for path in base.rglob("*") if path.is_file()]
    for path in files:
        relative = path.relative_to(base)
        bucket = app_bucket(relative)
        buckets[bucket] = buckets.get(bucket, 0) + path.stat().st_size

    app_jar = next(iter(sorted(app_dir.glob("korTTY-*.jar"))), None)
    runtime_jvm = next(
        (
            path
            for path in runtime_dir.rglob("*")
            if path.is_file() and path.name.lower() in {"jvm.dll", "libjvm.so", "libjvm.dylib"}
        ),
        None,
    ) if runtime_dir.is_dir() else None
    jar_buckets: dict[str, dict[str, int]] = {}
    if app_jar:
        with zipfile.ZipFile(app_jar) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                bucket = jar_bucket(entry.filename)
                current = jar_buckets.setdefault(bucket, {"raw_bytes": 0, "compressed_bytes": 0})
                current["raw_bytes"] += entry.file_size
                current["compressed_bytes"] += entry.compress_size

    return {
        "path": str(root),
        "logical_bytes": sum(path.stat().st_size for path in files),
        "file_count": len(files),
        "app_bytes": file_bytes(app_dir) if app_dir.is_dir() else 0,
        "runtime_bytes": file_bytes(runtime_dir) if runtime_dir.is_dir() else 0,
        "buckets": dict(sorted(buckets.items())),
        "app_jar": str(app_jar) if app_jar else None,
        "runtime_jvm": str(runtime_jvm) if runtime_jvm else None,
        "app_jar_buckets": dict(sorted(jar_buckets.items())),
    }


def mib(value: int | None) -> str:
    return "—" if value is None else f"{value / MIB:.2f}"


def markdown(report: dict[str, Any]) -> str:
    lines = ["# korTTY package size report", ""]
    app = report.get("app_image")
    if app:
        lines.extend(
            [
                "## Application image",
                "",
                f"- Path: `{app['path']}`",
                f"- Logical size: **{mib(app['logical_bytes'])} MiB**",
                f"- Files: {app['file_count']}",
                "",
                "| Component | MiB |",
                "| --- | ---: |",
            ]
        )
        lines.extend(f"| {key} | {mib(value)} |" for key, value in app["buckets"].items())
        lines.extend(["", "### Application JAR", "", "| Resource | Raw MiB | Deflated MiB |", "| --- | ---: | ---: |"])
        lines.extend(
            f"| {key} | {mib(value['raw_bytes'])} | {mib(value['compressed_bytes'])} |"
            for key, value in app["app_jar_buckets"].items()
        )

    lines.extend(["", "## Native artifacts", "", "| Key | Size MiB | Budget MiB | Reduction | Status |", "| --- | ---: | ---: | ---: | --- |"])
    for item in report["artifacts"]:
        reduction = item.get("reduction_percent")
        reduction_text = "—" if reduction is None else f"{reduction:.1f}%"
        lines.append(
            f"| {item['key']} | {mib(item['bytes'])} | {mib(item.get('budget_bytes'))} | "
            f"{reduction_text} | {item['status']} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-image", type=Path)
    parser.add_argument("--artifact", action="append", default=[], type=parse_artifact, metavar="KEY=PATH")
    parser.add_argument("--baselines", type=Path, default=Path("package/size-baselines.json"))
    parser.add_argument("--min-app-bytes", type=int)
    parser.add_argument("--max-app-bytes", type=int)
    parser.add_argument("--fail-on-budget", action="store_true")
    parser.add_argument("--output-json", type=Path)
    parser.add_argument("--output-markdown", type=Path)
    args = parser.parse_args()

    if args.fail_on_budget and not args.baselines.is_file():
        print(f"ERROR: baseline configuration not found: {args.baselines}", file=sys.stderr)
        return 2
    config = json.loads(args.baselines.read_text(encoding="utf-8")) if args.baselines.is_file() else {}
    if args.fail_on_budget and config.get("schema_version") != 1:
        print("ERROR: unsupported or missing package-size baseline schema", file=sys.stderr)
        return 2
    configured = config.get("artifacts", {})
    minimum_reduction = config.get("minimum_reduction_percent")
    default_tolerance = config.get("budget_tolerance_percent", 2)
    failures: list[str] = []
    report: dict[str, Any] = {"schema_version": 1, "baseline_release": config.get("release"), "artifacts": []}

    if args.app_image:
        report["app_image"] = analyze_app_image(args.app_image)
        if report["app_image"]["app_jar"] is None:
            failures.append("app image does not contain a korTTY application JAR")
        if report["app_image"]["runtime_jvm"] is None:
            failures.append("app image does not contain a runtime JVM")
        if args.min_app_bytes and report["app_image"]["logical_bytes"] < args.min_app_bytes:
            failures.append(
                f"app image is {report['app_image']['logical_bytes']} bytes; plausible minimum is {args.min_app_bytes}"
            )
        if args.max_app_bytes and report["app_image"]["logical_bytes"] > args.max_app_bytes:
            failures.append(
                f"app image is {report['app_image']['logical_bytes']} bytes; maximum is {args.max_app_bytes}"
            )

    for key, path in args.artifact:
        if not path.is_file():
            failures.append(f"artifact not found for {key}: {path}")
            continue
        size = path.stat().st_size
        baseline = configured.get(key)
        if baseline is None:
            failures.append(f"artifact key is not configured: {key}")
            baseline = {}
        baseline_bytes = baseline.get("baseline_bytes")
        verified_bytes = baseline.get("verified_bytes")
        tolerance = baseline.get("budget_tolerance_percent", default_tolerance)
        regression_budget = (
            math.ceil(verified_bytes * (1.0 + tolerance / 100.0))
            if verified_bytes is not None
            else None
        )
        acceptance_budget = baseline.get("budget_bytes")
        budgets = [value for value in (acceptance_budget, regression_budget) if value is not None]
        budget_bytes = min(budgets) if budgets else None
        reduction = None
        if baseline_bytes:
            reduction = (baseline_bytes - size) * 100.0 / baseline_bytes
        status = "unbudgeted"
        if budget_bytes is not None:
            status = "pass" if size <= budget_bytes else "FAIL"
            if size > budget_bytes:
                failures.append(f"{key} is {size} bytes; budget is {budget_bytes}")
        elif args.fail_on_budget:
            failures.append(f"artifact key has no enforced budget: {key}")
        required_reduction = baseline.get("minimum_reduction_percent", minimum_reduction)
        if baseline_bytes and required_reduction is not None and reduction < required_reduction:
            failures.append(
                f"{key} reduction is {reduction:.2f}%; minimum is {required_reduction}%"
            )
        elif args.fail_on_budget and not baseline_bytes and not baseline.get("baseline_exempt", False):
            failures.append(f"artifact key has no v2.4.3 baseline and is not exempt: {key}")
        report["artifacts"].append(
            {
                "key": key,
                "path": str(path),
                "bytes": size,
                "baseline_bytes": baseline_bytes,
                "verified_bytes": verified_bytes,
                "budget_tolerance_percent": tolerance if verified_bytes is not None else None,
                "acceptance_budget_bytes": acceptance_budget,
                "regression_budget_bytes": regression_budget,
                "budget_bytes": budget_bytes,
                "reduction_percent": reduction,
                "status": status,
            }
        )

    rendered = markdown(report)
    if args.output_json:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.output_markdown:
        args.output_markdown.parent.mkdir(parents=True, exist_ok=True)
        args.output_markdown.write_text(rendered, encoding="utf-8")
    print(rendered)
    if failures:
        print("\n".join(f"ERROR: {failure}" for failure in failures), file=sys.stderr)
    return 2 if failures and args.fail_on_budget else 0


if __name__ == "__main__":
    raise SystemExit(main())

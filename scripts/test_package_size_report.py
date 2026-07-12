#!/usr/bin/env python3
"""Focused regression tests for the native package-size report."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("package-size-report.py")
SPEC = importlib.util.spec_from_file_location("package_size_report", SCRIPT)
assert SPEC and SPEC.loader
PACKAGE_SIZE_REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE_SIZE_REPORT)


class PackageSizePlantUmlGuardTest(unittest.TestCase):
    def create_app_image(self, root: Path, entries: dict[str, bytes]) -> Path:
        app_image = root / "korTTY.app"
        app_dir = app_image / "Contents" / "app"
        runtime_dir = app_image / "Contents" / "runtime" / "lib" / "server"
        app_dir.mkdir(parents=True)
        runtime_dir.mkdir(parents=True)
        (runtime_dir / "libjvm.dylib").write_bytes(b"jvm")
        with zipfile.ZipFile(app_dir / "korTTY-test.jar", "w", zipfile.ZIP_DEFLATED) as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        return app_image

    def test_generic_migration_cleanup_class_is_not_reported_as_a_renderer_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_image = self.create_app_image(
                Path(directory),
                {
                    "de/kortty/core/LegacyDiagramCacheCleanup.class": b"cleanup",
                    "de/kortty/core/LegacyDiagramCacheCleanup$DeleteVisitor.class": b"cleanup-inner",
                },
            )

            report = PACKAGE_SIZE_REPORT.analyze_app_image(app_image)

            self.assertEqual("pass", report["plantuml_artifact_status"])
            self.assertEqual([], report["plantuml_artifacts"])

    def test_named_and_shaded_plantuml_artifacts_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_image = self.create_app_image(
                Path(directory),
                {"net/sourceforge/plantuml/Run.class": b"shaded-renderer"},
            )
            direct_jar = app_image / "Contents" / "app" / "plantuml-1.2026.2.jar"
            direct_jar.write_bytes(b"downloaded-renderer")

            report = PACKAGE_SIZE_REPORT.analyze_app_image(app_image)

            self.assertEqual("FAIL", report["plantuml_artifact_status"])
            self.assertEqual(
                {
                    "app/korTTY-test.jar!/net/sourceforge/plantuml/Run.class",
                    "app/plantuml-1.2026.2.jar",
                },
                {item["path"] for item in report["plantuml_artifacts"]},
            )

    def test_cli_rejects_artifacts_only_when_budget_enforcement_is_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_image = self.create_app_image(
                root,
                {"net/sourceforge/plantuml/Run.class": b"shaded-renderer"},
            )
            baselines = root / "baselines.json"
            baselines.write_text(
                json.dumps({"schema_version": 1, "release": "test", "artifacts": {}}),
                encoding="utf-8",
            )
            report_json = root / "report.json"
            command = [
                sys.executable,
                str(SCRIPT),
                "--app-image",
                str(app_image),
                "--baselines",
                str(baselines),
                "--output-json",
                str(report_json),
            ]

            report_only = subprocess.run(command, text=True, capture_output=True, check=False)
            enforced = subprocess.run(
                [*command, "--fail-on-budget"], text=True, capture_output=True, check=False
            )

            self.assertEqual(0, report_only.returncode, report_only.stderr)
            self.assertEqual(2, enforced.returncode)
            self.assertIn("bundled PlantUML dependency/resource artifact", enforced.stderr)
            persisted = json.loads(report_json.read_text(encoding="utf-8"))
            self.assertEqual("FAIL", persisted["app_image"]["plantuml_artifact_status"])


if __name__ == "__main__":
    unittest.main()

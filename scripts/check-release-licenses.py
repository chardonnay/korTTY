#!/usr/bin/env python3
"""Fail when a korTTY release package stops declaring and shipping the MIT license."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
LICENSE = ROOT / "LICENSE"
PKGBUILD = ROOT / "package/arch/PKGBUILD"
FLATPAK_MANIFEST = ROOT / "package/flatpak/io.github.chardonnay.korTTY.yml"
FLATPAK_METAINFO = ROOT / "package/flatpak/io.github.chardonnay.korTTY.metainfo.xml"
GRADLE = ROOT / "build.gradle.kts"


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    failures: list[str] = []
    license_text = LICENSE.read_text(encoding="utf-8")
    require(license_text.startswith("MIT License\n"), "LICENSE is not the canonical MIT license", failures)
    require("Copyright (c) 2026 Daniel Mengel" in license_text, "LICENSE copyright owner is incorrect", failures)

    pkgbuild = PKGBUILD.read_text(encoding="utf-8")
    require("license=('MIT')" in pkgbuild, "Arch PKGBUILD does not declare MIT", failures)
    require('"LICENSE"' in pkgbuild, "Arch PKGBUILD does not source LICENSE", failures)
    require("/usr/share/licenses/kortty/LICENSE" in pkgbuild, "Arch package does not install LICENSE", failures)

    metainfo_root = ET.parse(FLATPAK_METAINFO).getroot()
    require(metainfo_root.findtext("project_license") == "MIT", "Flatpak project_license is not MIT", failures)
    require(
        metainfo_root.findtext("metadata_license") == "CC0-1.0",
        "Flatpak metadata_license must remain CC0-1.0",
        failures,
    )
    manifest = FLATPAK_MANIFEST.read_text(encoding="utf-8")
    require(
        "/app/share/licenses/io.github.chardonnay.korTTY/LICENSE" in manifest,
        "Flatpak manifest does not install the canonical LICENSE",
        failures,
    )

    gradle = GRADLE.read_text(encoding="utf-8")
    require(
        gradle.count('"--license-file"') == 4,
        "DMG, MSI, DEB and RPM must each receive --license-file exactly once",
        failures,
    )
    require(
        '"--linux-rpm-license-type", "MIT"' in gradle,
        "RPM package metadata does not declare MIT",
        failures,
    )
    require('rename { "LICENSE-korTTY" }' in gradle, "Executable JAR does not carry LICENSE-korTTY", failures)
    require("distributions {" in gradle and 'file("LICENSE")' in gradle, "Java distributions do not carry LICENSE", failures)

    package_apache = [
        str(path.relative_to(ROOT))
        for path in (ROOT / "package").rglob("*")
        if path.is_file() and "Apache-2.0" in path.read_text(encoding="utf-8", errors="ignore")
    ]
    require(not package_apache, f"korTTY package metadata still declares Apache-2.0: {package_apache}", failures)

    if failures:
        print("Release-license validation failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("Release-license validation passed: all korTTY package formats declare MIT.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

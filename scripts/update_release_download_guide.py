#!/usr/bin/env python3
"""Render the idempotent, architecture-aware download guide in release notes."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from urllib.parse import quote


START = "<!-- kortty-download-guide:start -->"
END = "<!-- kortty-download-guide:end -->"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--pacman-pkgrel", required=True, help="Positive package release number or 'auto'")
    parser.add_argument("--body", required=True, type=Path)
    parser.add_argument("--assets", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def resolve_pacman_pkgrel(version: str, requested: str | int, assets: set[str]) -> int:
    if str(requested) != "auto":
        try:
            pkgrel = int(requested)
        except (TypeError, ValueError) as error:
            raise ValueError("pacman pkgrel must be a positive integer or 'auto'") from error
        if pkgrel < 1:
            raise ValueError("pacman pkgrel must be positive")
        return pkgrel

    pattern = re.compile(rf"^kortty-{re.escape(version)}-([1-9][0-9]*)-(?:x86_64|aarch64)\.pkg\.tar\.zst$")
    available = [int(match.group(1)) for asset in assets if (match := pattern.fullmatch(asset))]
    return max(available, default=1)


def render_guide(repository: str, tag: str, version: str, pkgrel: str | int, assets: set[str]) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError(f"Invalid repository: {repository}")
    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+){1,2}", tag):
        raise ValueError(f"Invalid release tag: {tag}")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", version):
        raise ValueError(f"Invalid version: {version}")
    pkgrel = resolve_pacman_pkgrel(version, pkgrel, assets)

    base_url = f"https://github.com/{repository}/releases/download/{tag}"
    lines: list[str] = [
        START,
        "### Downloads",
        "",
        "Choose your operating system and processor architecture. `x86_64` is for Intel and AMD PCs; `aarch64` is for Apple Silicon and ARM Linux devices.",
        "",
        "**Windows**",
        "",
        "*x86_64 (Intel and AMD)*",
    ]

    def asset_link(filename: str, label: str) -> None:
        if filename in assets:
            lines.append(f"- [{label}]({base_url}/{quote(filename)})")

    asset_link(f"korTTY-Windows-{version}-x86_64.msi", "Installer (.msi)")
    asset_link(f"korTTY-Windows-{version}-x86_64.zip", "Portable app (.zip)")
    lines.extend([
        "",
        "*Windows on ARM (x64 emulation)*",
        "korTTY does not currently have a native Windows ARM64 build. Windows on ARM runs the tested `x86_64` packages through its built-in x64 emulation.",
    ])
    asset_link(f"korTTY-Windows-{version}-x86_64.msi", "Installer for Windows on ARM (.msi, x64 emulated)")
    asset_link(f"korTTY-Windows-{version}-x86_64.zip", "Portable app for Windows on ARM (.zip, x64 emulated)")
    lines.extend(["", "**macOS**", "", "*Apple Silicon (aarch64)*"])
    asset_link(f"korTTY-macOS-{version}-aarch64.dmg", "Disk image (.dmg)")
    asset_link(f"korTTY-macOS-{version}-aarch64.zip", "App archive (.zip)")
    lines.extend(["", "*Intel (x86_64)*"])
    asset_link(f"korTTY-macOS-{version}-x86_64.dmg", "Disk image (.dmg)")
    asset_link(f"korTTY-macOS-{version}-x86_64.zip", "App archive (.zip)")
    lines.extend([
        "",
        "**Linux**",
        "",
        "Flatpak bundles can be installed manually from a host terminal with `flatpak install --user ./FILENAME.flatpak`.",
        "",
    ])
    for arch in ("aarch64", "x86_64"):
        lines.append(f"*{arch}*")
        asset_link(f"kortty-Linux-{version}-{arch}.flatpak", "Flatpak bundle (.flatpak)")
        asset_link(f"kortty-Linux-{version}-{arch}.deb", "Debian/Ubuntu (.deb)")
        asset_link(f"kortty-Linux-{version}-{arch}.rpm", "Fedora/openSUSE (.rpm)")
        asset_link(f"kortty-Linux-{version}-{arch}.tar.gz", "Portable archive (.tar.gz)")
        asset_link(f"kortty-Linux-{version}-{arch}.zip", "Portable archive (.zip)")
        lines.append("")

    if pkgrel > 1 and any(f"kortty-{version}-1-{arch}.pkg.tar.zst" in assets for arch in ("x86_64", "aarch64")):
        lines.extend([
            f"The earlier `{version}-1` pacman package is superseded by `{version}-{pkgrel}` because its package metadata and installed license were corrected. Existing `-1` assets remain available for auditability; use `-{pkgrel}`.",
            "",
        ])

    for title, arch in (("Arch Linux", "x86_64"), ("Arch Linux ARM", "aarch64")):
        lines.append(f"*{title} ({arch})*")
        package = f"kortty-{version}-{pkgrel}-{arch}.pkg.tar.zst"
        asset_link(package, "pacman package (.pkg.tar.zst)")
        asset_link(f"{package}.sig", "Detached GPG signature (.sig)")
        lines.append("")

    lines.extend(["**Java (all supported platforms)**", ""])
    asset_link(f"korTTY-Java-{version}.jar", "Executable JAR (.jar)")
    asset_link(f"korTTY-Java-{version}.zip", "Distribution archive (.zip)")
    asset_link(f"korTTY-Java-{version}.tar", "Distribution archive (.tar)")
    lines.append(END)
    return "\n".join(lines)


def replace_guide(body: str, guide: str) -> str:
    if START in body or END in body:
        if body.count(START) != 1 or body.count(END) != 1:
            raise ValueError("Release notes contain incomplete or duplicate download-guide markers")
        begin = body.index(START)
        finish = body.index(END, begin) + len(END)
        return body[:begin] + guide + body[finish:]
    separator = "" if not body else ("\n" if body.endswith("\n") else "\n\n")
    return body + separator + guide + "\n"


def main() -> None:
    args = parse_args()
    assets = {line.strip() for line in args.assets.read_text(encoding="utf-8").splitlines() if line.strip()}
    body = args.body.read_text(encoding="utf-8")
    guide = render_guide(args.repository, args.tag, args.version, args.pacman_pkgrel, assets)
    updated = replace_guide(body, guide)
    if replace_guide(updated, guide) != updated:
        raise SystemExit("Download-guide update is not idempotent")
    original_prefix = body[:body.index(START)] if START in body else body
    updated_prefix = updated[:updated.index(START)]
    if not updated_prefix.startswith(original_prefix):
        raise SystemExit("Release description changed while updating the download guide")
    args.output.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()

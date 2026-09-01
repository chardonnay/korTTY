import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github/workflows/build-release.yml").read_text(encoding="utf-8")
PKGBUILD = (ROOT / "package/arch/PKGBUILD").read_text(encoding="utf-8")
PACKAGER = (ROOT / "scripts/package-pacman.sh").read_text(encoding="utf-8")
VERIFIER = (ROOT / "scripts/verify-pacman-package.sh").read_text(encoding="utf-8")


def job(name: str) -> str:
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [a-z0-9-]+:\n|\Z)", WORKFLOW)
    if not match:
        raise AssertionError(f"Workflow job not found: {name}")
    return match.group(1)


class PacmanWorkflowContractTest(unittest.TestCase):
    def test_pkgbuild_is_copy_only_and_disables_binary_mutation(self):
        self.assertIn("arch=('x86_64' 'aarch64')", PKGBUILD)
        self.assertIn("options=('!strip' '!debug')", PKGBUILD)
        self.assertIn("license=('MIT')", PKGBUILD)
        self.assertNotRegex(PKGBUILD, r"(?m)^\s*(prepare|build|check)\s*\(")

    def test_regular_pacman_job_is_a_two_architecture_matrix(self):
        text = job("build-pacman")
        self.assertIn("arch: [x86_64, aarch64]", text)
        self.assertIn("inputs.scope == 'all'", text)
        self.assertIn("korTTY-Linux-${{ steps.version.outputs.version }}-${{ matrix.arch }}", text)
        self.assertIn("KorTTY-Arch-${{ steps.version.outputs.version }}-${{ matrix.arch }}", text)
        self.assertIn("jdk-openjdk", text)
        self.assertNotIn("head -n1", text)
        self.assertIn("gpg --batch --verify", text)

    def test_pacman_only_inputs_and_no_release_ci_mode_exist(self):
        inputs = WORKFLOW[: WORKFLOW.index("jobs:")]
        self.assertIn("- pacman-only", inputs)
        self.assertIn("pacman_pkgrel:", inputs)
        self.assertIn("expected_tag_commit:", inputs)
        backfill = job("build-pacman-backfill")
        self.assertIn("RELEASE_TAG\" != 'ci-only'", backfill)
        self.assertIn("git rev-list -n 1", backfill)
        self.assertIn("^[0-9a-fA-F]{40}$", backfill)

    def test_backfill_authenticates_release_inputs(self):
        text = job("build-pacman-backfill")
        self.assertIn("EXPECTED_FINGERPRINT: 270E6DE92A8BB6D1841F758B9D2F81982AAD80CC", text)
        self.assertIn("verify_github_digest", text)
        self.assertIn("--status-fd 1 --verify \"release-input/$signature\"", text)
        self.assertIn("primary_fingerprints", text)
        self.assertIn('count == 1 && matched', text)
        self.assertIn("kortty-Linux-${VERSION}-${ARCH}.tar.gz", text)
        self.assertIn("arch: [x86_64, aarch64]", text)
        self.assertIn("jdk-openjdk", text)

    def test_backfill_publish_is_collision_safe_and_digest_checked(self):
        text = job("publish-pacman-backfill")
        self.assertIn("existing-assets.txt", text)
        self.assertIn("existing-assets-immediate.txt", text)
        upload_lines = [line.strip() for line in text.splitlines() if "gh release upload" in line]
        self.assertEqual(len(upload_lines), 1)
        self.assertNotIn("--clobber", upload_lines[0])
        self.assertIn("local-backfill-sha256.txt", text)
        self.assertIn(".digest", text)
        self.assertIn("isImmutable", text)
        self.assertIn("verify-pacman-package.sh", text)
        self.assertIn("verify_pinned_signature", text)

    def test_normal_upload_preserves_highest_existing_pacman_pkgrel(self):
        text = job("upload-release")
        self.assertIn("--pacman-pkgrel auto", text)

    def test_packager_has_cross_build_guard_and_exact_output(self):
        self.assertIn("Cross-packaging guard", PACKAGER)
        self.assertIn("(prepare|build|check)", PACKAGER)
        self.assertIn('expected_package="kortty-${version}-${pkgrel}-${target_arch}.pkg.tar.zst"', PACKAGER)
        self.assertIn("makepkg-aarch64.conf", PACKAGER)
        self.assertIn("CHOST='aarch64-unknown-linux-gnu'", PACKAGER)
        self.assertIn("verify-pacman-package.sh", PACKAGER)

    def test_verifier_checks_metadata_files_symlink_launcher_and_jvm(self):
        for expected in (
            "pkgname = kortty",
            "pkgver = ${version}-${pkgrel}",
            "arch = ${target_arch}",
            "license = MIT",
            "usr/share/licenses/kortty/LICENSE",
            "usr/share/applications/kortty.desktop",
            "usr/bin/kortty",
            "Bundled JVM",
        ):
            self.assertIn(expected, VERIFIER)


if __name__ == "__main__":
    unittest.main()

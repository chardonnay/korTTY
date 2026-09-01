import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("update_release_download_guide.py")
SPEC = importlib.util.spec_from_file_location("update_release_download_guide", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
END = MODULE.END
START = MODULE.START
render_guide = MODULE.render_guide
replace_guide = MODULE.replace_guide
resolve_pacman_pkgrel = MODULE.resolve_pacman_pkgrel


class ReleaseDownloadGuideTest(unittest.TestCase):
    def test_renders_both_pacman_architectures_and_supersedes_pkgrel_one(self):
        assets = {
            "kortty-2.14.0-1-x86_64.pkg.tar.zst",
            "kortty-2.14.0-2-x86_64.pkg.tar.zst",
            "kortty-2.14.0-2-x86_64.pkg.tar.zst.sig",
            "kortty-2.14.0-2-aarch64.pkg.tar.zst",
            "kortty-2.14.0-2-aarch64.pkg.tar.zst.sig",
        }

        guide = render_guide("chardonnay/korTTY", "v2.14.0", "2.14.0", 2, assets)

        self.assertIn("*Arch Linux (x86_64)*", guide)
        self.assertIn("*Arch Linux ARM (aarch64)*", guide)
        self.assertIn("kortty-2.14.0-2-x86_64.pkg.tar.zst", guide)
        self.assertIn("kortty-2.14.0-2-aarch64.pkg.tar.zst", guide)
        self.assertIn("superseded", guide)
        self.assertIn("Existing `-1` assets remain available", guide)

    def test_replacement_is_idempotent_and_preserves_release_notes(self):
        guide = render_guide("chardonnay/korTTY", "v2.15.0", "2.15.0", 1, set())
        original = "Release notes\n\nKeep this paragraph.\n"

        once = replace_guide(original, guide)
        twice = replace_guide(once, guide)

        self.assertEqual(once, twice)
        self.assertTrue(once.startswith(original))
        self.assertEqual(once.count(START), 1)
        self.assertEqual(once.count(END), 1)

    def test_auto_pkgrel_preserves_highest_existing_backfill(self):
        assets = {
            "kortty-2.14.0-1-x86_64.pkg.tar.zst",
            "kortty-2.14.0-2-x86_64.pkg.tar.zst",
            "kortty-2.14.0-2-aarch64.pkg.tar.zst",
        }

        self.assertEqual(resolve_pacman_pkgrel("2.14.0", "auto", assets), 2)
        guide = render_guide("chardonnay/korTTY", "v2.14.0", "2.14.0", "auto", assets)
        self.assertIn("kortty-2.14.0-2-x86_64.pkg.tar.zst", guide)
        self.assertNotIn("releases/download/v2.14.0/kortty-2.14.0-1-x86_64", guide)

    def test_rejects_incomplete_markers(self):
        with self.assertRaises(ValueError):
            replace_guide(f"notes\n{START}\n", "guide")


if __name__ == "__main__":
    unittest.main()

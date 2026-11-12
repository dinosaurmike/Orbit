"""Tests for the release helper: python -m unittest discover -s tools/release"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bump  # noqa: E402

GRADLE = '''android {
    defaultConfig {
        versionCode = 1          // raise by 1 for every release
        versionName = "0.1.0"    // what people see
    }
}
'''
LOG = '''# Changelog

## 0.1.1 (unreleased)

- New: a thing
- Fixed: another thing

## 0.1.0 (2026-10-31, first public release)

- Hello
'''


class BumpTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        (self.dir / "app").mkdir()
        (self.dir / "app" / "build.gradle.kts").write_text(GRADLE)
        (self.dir / "CHANGELOG.md").write_text(LOG)
        self.notes = self.dir / "notes.md"

    def test_release(self):
        msg = bump.bump(self.dir, "0.1.1", "2026-11-20", self.notes)
        gradle = (self.dir / "app" / "build.gradle.kts").read_text()
        self.assertIn("versionCode = 2          // raise by 1", gradle)  # comment kept
        self.assertIn('versionName = "0.1.1"    // what people see', gradle)
        log = (self.dir / "CHANGELOG.md").read_text()
        self.assertIn("## 0.1.1 (2026-11-20)\n", log)
        self.assertNotIn("unreleased", log)
        self.assertIn("## 0.1.0 (2026-10-31, first public release)", log)
        self.assertEqual(self.notes.read_text(), "- New: a thing\n- Fixed: another thing\n")
        self.assertIn("versionCode 2", msg)

    def test_heading_version_can_differ(self):
        bump.bump(self.dir, "0.2.0", "2026-11-20", self.notes)
        self.assertIn("## 0.2.0 (2026-11-20)", (self.dir / "CHANGELOG.md").read_text())

    def refuses(self, version, why):
        before = ((self.dir / "app" / "build.gradle.kts").read_text(), (self.dir / "CHANGELOG.md").read_text())
        with self.assertRaises(SystemExit) as e:
            bump.bump(self.dir, version, "2026-11-20", self.notes)
        self.assertIn(why, str(e.exception))
        after = ((self.dir / "app" / "build.gradle.kts").read_text(), (self.dir / "CHANGELOG.md").read_text())
        self.assertEqual(before, after)  # nothing changed
        self.assertFalse(self.notes.exists())

    def test_refuses_bad_version(self):
        self.refuses("1.2", "isn't a version")
        self.refuses("v0.1.1beta", "isn't a version")

    def test_refuses_older_or_same_version(self):
        self.refuses("0.1.0", "isn't newer")
        self.refuses("0.0.9", "isn't newer")

    def test_refuses_without_unreleased_section(self):
        (self.dir / "CHANGELOG.md").write_text(LOG.replace("(unreleased)", "(2026-11-01)"))
        self.refuses("0.1.1", "no")

    def test_refuses_empty_section(self):
        (self.dir / "CHANGELOG.md").write_text("# Changelog\n\n## 0.1.1 (unreleased)\n\n## 0.1.0 (x)\n- a\n")
        self.refuses("0.1.1", "empty")

    def test_real_files_are_releasable(self):
        # The project's own files have what the release needs (without changing them)
        root = Path(__file__).resolve().parents[2]
        gradle = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(gradle, r"versionCode = \d+")
        self.assertRegex(gradle, r'versionName = "\d+\.\d+\.\d+"')


if __name__ == "__main__":
    unittest.main()

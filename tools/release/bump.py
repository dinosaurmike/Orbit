#!/usr/bin/env python3
"""Gets the code ready for a release: used by the "Release" workflow (.github/workflows/release.yml).

    python tools/release/bump.py 0.1.2 [--date 2026-11-20] [--notes release-notes.md]

1. app/build.gradle.kts: versionCode goes up by one, versionName becomes the new version.
2. CHANGELOG.md: the "## <anything> (unreleased)" section becomes "## 0.1.2 (2026-11-20)".
3. Writes that section's text to --notes (the GitHub release notes).

It changes nothing if anything looks wrong (bad version, no unreleased section, version not
newer), and says why.
"""
import argparse
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def parse(version: str):
    return tuple(int(x) for x in version.split("."))


def bump(root: Path, version: str, date: str, notes_path: Path) -> str:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit(f"'{version}' isn't a version like 0.1.2")

    gradle_path = root / "app" / "build.gradle.kts"
    gradle = gradle_path.read_text(encoding="utf-8")
    code = re.search(r"versionCode = (\d+)", gradle)
    name = re.search(r'versionName = "([^"]+)"', gradle)
    if not code or not name:
        raise SystemExit("Couldn't find versionCode / versionName in app/build.gradle.kts")
    if parse(version) <= parse(name.group(1)):
        raise SystemExit(f"{version} isn't newer than the current version {name.group(1)}")

    log_path = root / "CHANGELOG.md"
    log = log_path.read_text(encoding="utf-8")
    head = re.search(r"^## [^\n]*\(unreleased\)[^\n]*\n", log, re.M)
    if not head:
        raise SystemExit('CHANGELOG.md has no "## … (unreleased)" section to release')
    following = re.search(r"^## ", log[head.end():], re.M)
    body_end = head.end() + following.start() if following else len(log)
    body = log[head.end():body_end].strip()
    if not body:
        raise SystemExit("The unreleased section of CHANGELOG.md is empty: nothing to release")

    new_code = int(code.group(1)) + 1
    gradle = gradle.replace(code.group(0), f"versionCode = {new_code}", 1)
    gradle = gradle.replace(name.group(0), f'versionName = "{version}"', 1)
    log = log[:head.start()] + f"## {version} ({date})\n" + log[head.end():]

    gradle_path.write_text(gradle, encoding="utf-8")
    log_path.write_text(log, encoding="utf-8")
    notes_path.write_text(body + "\n", encoding="utf-8")
    return f"Version {version} (versionCode {new_code}); {len(body.splitlines())} lines of notes"


def main():
    p = argparse.ArgumentParser(description="Prepare a release: version number and changelog.")
    p.add_argument("version", help="the new version, e.g. 0.1.2")
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p.add_argument("--notes", default="release-notes.md", help="where to write the release notes")
    p.add_argument("--root", default=str(ROOT), help=argparse.SUPPRESS)
    a = p.parse_args()
    print(bump(Path(a.root), a.version.strip().lstrip("v"), a.date, Path(a.notes)))


if __name__ == "__main__":
    sys.exit(main())

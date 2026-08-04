"""Build the submission SOURCE.zip from a strict, reviewable allowlist."""

from __future__ import annotations

import argparse
import os
import zipfile
from pathlib import Path


EXACT_FILES = (
    "android/build.gradle.kts",
    "android/gradle.properties",
    "android/gradlew",
    "android/gradlew.bat",
    "android/gradle/wrapper/gradle-wrapper.jar",
    "android/gradle/wrapper/gradle-wrapper.properties",
    "android/keystore.properties.example",
    "android/settings.gradle.kts",
    "android/app/build.gradle.kts",
    "android/app/libs/scpc-probe-starter-3.0.0-draft.aar",
    "android/test-fixtures/probe/PUBLIC_PROBE_INPUT_13_STEP.json",
    "BUILD_AND_SUBMISSION_INFO.md",
    "THIRD_PARTY_NOTICES.md",
)

SOURCE_TREES = (
    "android/app/src/main",
    "android/app/src/test",
    "android/app/src/androidTest",
)

FORBIDDEN_PARTS = {
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "local.properties",
    "keystore.properties",
}

FORBIDDEN_SUFFIXES = {".apk", ".jks", ".keystore", ".p12", ".pem", ".key"}


def collect(root: Path) -> list[Path]:
    files = [root / relative for relative in EXACT_FILES]
    for relative in SOURCE_TREES:
        files.extend(path for path in (root / relative).rglob("*") if path.is_file())

    unique = sorted(set(files), key=lambda path: path.relative_to(root).as_posix())
    missing = [path for path in unique if not path.is_file()]
    if missing:
        raise FileNotFoundError("missing source files: " + ", ".join(map(str, missing)))

    for path in unique:
        relative = path.relative_to(root)
        lowered_parts = {part.lower() for part in relative.parts}
        if lowered_parts & FORBIDDEN_PARTS:
            raise ValueError(f"forbidden path selected: {relative}")
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            raise ValueError(f"forbidden artifact selected: {relative}")
        if path.is_symlink():
            raise ValueError(f"symlink is not allowed: {relative}")
    return unique


def write_zip(root: Path, destination: Path) -> None:
    files = collect(root)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    if temporary.exists():
        temporary.unlink()

    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in files:
                relative = path.relative_to(root).as_posix()
                info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o755 if path.name in {"gradlew"} else 0o644) << 16
                archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()

    print(f"built {destination} with {len(files)} files ({destination.stat().st_size} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    destination = (args.output or root / "output" / "submission" / "SOURCE.zip").resolve()
    write_zip(root, destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

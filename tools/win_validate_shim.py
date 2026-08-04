r"""Windows launcher for the official SAMPLE_EXPORT validator.

The Release v3 validator addresses Android SDK tools by extensionless POSIX
names, while the Windows SDK provides ``apkanalyzer.bat`` and
``apksigner.bat``. This launcher changes only that platform resolution; all
schema, digest, APK/source binding, and evidence checks remain the official
implementation.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys


KIT = pathlib.Path(__file__).resolve().parent.parent / "release_v3" / "candidate_kit"
if not KIT.is_dir():
    raise SystemExit(f"official candidate kit not found: {KIT}")
sys.path.insert(0, str(KIT))

_real_run = subprocess.run
_real_is_file = pathlib.Path.is_file


def _is_file_with_windows_suffix(path: pathlib.Path) -> bool:
    if _real_is_file(path):
        return True
    return not path.suffix and _real_is_file(path.with_suffix(".bat"))


def _run_with_windows_suffix(args, *rest, **kwargs):
    if isinstance(args, (list, tuple)) and args:
        executable = pathlib.Path(str(args[0]))
        if not executable.suffix:
            batch = executable.with_suffix(".bat")
            if _real_is_file(batch):
                args = [str(batch), *[str(item) for item in args[1:]]]
    return _real_run(args, *rest, **kwargs)


pathlib.Path.is_file = _is_file_with_windows_suffix
subprocess.run = _run_with_windows_suffix

import VALIDATE_SAMPLE_EXPORT as official  # noqa: E402


if __name__ == "__main__":
    sys.argv[0] = str(KIT / "VALIDATE_SAMPLE_EXPORT.py")
    raise SystemExit(official.main())

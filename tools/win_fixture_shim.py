"""Windows launcher for the official fixture generator.

`release_v3/public_harness/apk_release_info.py` addresses the SDK tools by their
extensionless names:

    sdk_root / "cmdline-tools" / "latest" / "bin" / "apkanalyzer"
    sdk_root / "build-tools" / "35.0.0" / "apksigner"

Those names exist on macOS and Linux. The Windows SDK ships only `apkanalyzer.bat`
and `apksigner.bat`, and CreateProcess refuses to start an extensionless batch file
(WinError 193), so the official script cannot run here at all. The runbook's Phase 0
check (`apkanalyzer -h`) passes anyway, because a shell resolves `.bat` through
PATHEXT while Python addresses the file directly — a green light that hides this.

This module runs the official code **unmodified** and corrects only the process
launch: an extensionless executable is redirected to its `.bat` sibling. No harness
logic is touched and nothing under `release_v3/` is written to. It affects our local
rehearsal only; the graded run executes Dacon's own harness on their machines.

Usage mirrors the official script exactly:

    .venv\\Scripts\\python.exe tools\\win_fixture_shim.py --public-input ... \\
        --candidate-apk APP.apk --output-dir PUBLIC_RUN
"""

from __future__ import annotations

import pathlib
import subprocess
import sys

HARNESS = pathlib.Path(__file__).resolve().parent.parent / "release_v3" / "public_harness"
if not HARNESS.is_dir():
    raise SystemExit(f"official harness not found: {HARNESS}")
sys.path.insert(0, str(HARNESS))

_real_run = subprocess.run


def _run_with_windows_suffix(args, *rest, **kwargs):
    """Redirect an extensionless tool path to its .bat sibling, else defer."""
    if isinstance(args, (list, tuple)) and args:
        executable = pathlib.Path(str(args[0]))
        if not executable.suffix:
            batch = executable.with_suffix(".bat")
            if batch.is_file():
                args = [str(batch), *[str(a) for a in args[1:]]]
    return _real_run(args, *rest, **kwargs)


subprocess.run = _run_with_windows_suffix

import make_local_integration_fixture as official  # noqa: E402

if __name__ == "__main__":
    sys.argv[0] = str(HARNESS / "make_local_integration_fixture.py")
    raise SystemExit(official.main())

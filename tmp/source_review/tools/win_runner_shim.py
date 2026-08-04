"""Windows launcher for the official Runner controller.

`release_v3/public_harness/runnerctl.py` finishes `collect()` with the POSIX
durability idiom of fsyncing the *directory* that just received new files:

    directory_descriptor = os.open(output_dir, os.O_RDONLY)
    os.fsync(directory_descriptor)

Windows refuses to open a directory as a file (`PermissionError: [Errno 13]`).
The failure lands inside the `except Exception` block that rolls the collection
back, so every file the Runner just returned is deleted again — the 13 steps
really did execute on the device, and the results are thrown away at the last
moment. Nothing about that is visible unless you read the traceback.

This module runs the official code **unmodified** and neutralises only that one
idiom: opening a *directory* read-only yields a sentinel descriptor, and fsync or
close on that sentinel does nothing. Files the Runner collected are still opened,
written and fsynced for real, so their durability is unchanged. Nothing under
`release_v3/` is written to.

Directory fsync guarantees the directory entry survives a power cut. Losing it
costs nothing here: this is a local rehearsal we can repeat, and the graded run
executes Dacon's own harness on their machines.

Usage mirrors the official controller exactly:

    .venv\\Scripts\\python.exe tools\\win_runner_shim.py run --assignment ... \\
        --input ... --output-dir ... --runner-apk ... --candidate-apk ...
"""

from __future__ import annotations

import os
import pathlib
import sys

HARNESS = pathlib.Path(__file__).resolve().parent.parent / "release_v3" / "public_harness"
if not HARNESS.is_dir():
    raise SystemExit(f"official harness not found: {HARNESS}")
sys.path.insert(0, str(HARNESS))

_DIRECTORY_SENTINEL = -0x5C9C  # not a descriptor any OS hands out

_real_open = os.open
_real_fsync = os.fsync
_real_close = os.close


def _open(path, flags, mode=0o777, **kwargs):
    """Directory opened read-only -> sentinel. Everything else is untouched."""
    if flags == os.O_RDONLY:
        try:
            if pathlib.Path(path).is_dir():
                return _DIRECTORY_SENTINEL
        except OSError:
            pass
    return _real_open(path, flags, mode, **kwargs)


def _fsync(descriptor):
    if descriptor == _DIRECTORY_SENTINEL:
        return None
    return _real_fsync(descriptor)


def _close(descriptor):
    if descriptor == _DIRECTORY_SENTINEL:
        return None
    return _real_close(descriptor)


os.open = _open
os.fsync = _fsync
os.close = _close

import runnerctl as official  # noqa: E402

if __name__ == "__main__":
    sys.argv[0] = str(HARNESS / "runnerctl.py")
    raise SystemExit(official.main())

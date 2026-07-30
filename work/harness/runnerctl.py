#!/usr/bin/env python3
"""Host-side controller for the SCPC R2 Dacon Runner.

The APK owns the Probe protocol and URI grants. This controller owns the Android
operations an ordinary APK cannot perform reliably: install, force-stop,
relaunch, network toggles, and artifact collection.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
from typing import Any

RUNNER_PACKAGE = "org.scpc.r2.runner"
RUNNER_ACTIVITY = f"{RUNNER_PACKAGE}/.RunnerCommandActivity"
RUNNER_ACTION = "org.scpc.r2.runner.COMMAND"
REMOTE_ROOT = f"/sdcard/Android/data/{RUNNER_PACKAGE}/files"
COLLECTED_ARTIFACT_NAMES = (
    "PROBE_RESULT.json",
    "RUNNER_COMPLETION_RECEIPT.json",
    "RUNNER_STATUS.json",
)


class ControllerError(RuntimeError):
    pass


@dataclass(frozen=True)
class NetworkState:
    wifi_enabled: bool
    mobile_data_enabled: bool


class Adb:
    def __init__(self, executable: str, serial: str | None):
        self.prefix = [executable]
        if serial:
            self.prefix += ["-s", serial]

    def run(
        self,
        *arguments: str,
        capture: bool = True,
        check: bool = True,
        timeout: float = 90,
        input_bytes: bytes | None = None,
    ) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [*self.prefix, *arguments],
            check=check,
            input=input_bytes,
            stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.PIPE if capture else None,
            timeout=timeout,
        )

    def text(self, *arguments: str, timeout: float = 90) -> str:
        completed = self.run(*arguments, timeout=timeout)
        return completed.stdout.decode("utf-8", errors="replace")


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ControllerError(f"{path} must contain a JSON object")
    return value


def invoke(
    adb: Adb,
    command: str,
    run_id: str | None = None,
    step_index: int | None = None,
) -> dict[str, Any]:
    remote_status = f"{REMOTE_ROOT}/status.json"
    adb.run("shell", "rm", "-f", remote_status)
    arguments = [
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        RUNNER_ACTION,
        "-n",
        RUNNER_ACTIVITY,
        "--es",
        "command",
        command,
    ]
    if run_id is not None:
        arguments += ["--es", "run_id", run_id]
    if step_index is not None:
        arguments += ["--ei", "step_index", str(step_index)]
    adb.run(*arguments)
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        completed = adb.run(
            "exec-out",
            "cat",
            remote_status,
            check=False,
            timeout=5,
        )
        if completed.returncode == 0 and completed.stdout.strip():
            encoded = completed.stdout.decode("utf-8")
            if not encoded.lstrip().startswith("{"):
                time.sleep(0.2)
                continue
            status = json.loads(encoded)
            if not isinstance(status, dict):
                raise ControllerError("Runner status is not an object")
            if status.get("status") != "OK":
                raise ControllerError(
                    f"Runner {command} failed: "
                    f"{status.get('error_code')} {status.get('message')}"
                )
            if status.get("command") != command:
                time.sleep(0.2)
                continue
            return status
        time.sleep(0.2)
    raise ControllerError(f"Runner {command} did not publish status before timeout")


def requested_network(roles: Any) -> str | None:
    if not isinstance(roles, dict):
        return None
    allowed = {"ONLINE", "OFFLINE", "DELAYED"}
    for value in roles.values():
        normalized = str(value).upper()
        if normalized in allowed:
            return normalized
    return None


def set_network(adb: Adb, state: str) -> None:
    if state == "OFFLINE":
        adb.run("shell", "svc", "wifi", "disable")
        adb.run("shell", "svc", "data", "disable")
    else:
        adb.run("shell", "svc", "wifi", "enable")
        adb.run("shell", "svc", "data", "enable")
        if state == "DELAYED":
            time.sleep(1)


def read_boolean_global_setting(adb: Adb, name: str) -> bool:
    completed = adb.run(
        "shell",
        "settings",
        "get",
        "global",
        name,
        check=False,
    )
    value = completed.stdout.decode("utf-8", errors="replace").strip()
    if completed.returncode != 0 or value not in {"0", "1"}:
        raise ControllerError(
            f"cannot determine Android network setting {name!r}; "
            "refusing a run that could leave the device network changed"
        )
    return value == "1"


def capture_network_state(adb: Adb) -> NetworkState:
    return NetworkState(
        wifi_enabled=read_boolean_global_setting(adb, "wifi_on"),
        mobile_data_enabled=read_boolean_global_setting(adb, "mobile_data"),
    )


def restore_network(adb: Adb, state: NetworkState) -> None:
    commands = (
        ("wifi", "enable" if state.wifi_enabled else "disable"),
        ("data", "enable" if state.mobile_data_enabled else "disable"),
    )
    first_error: Exception | None = None
    for service, action in commands:
        try:
            adb.run("shell", "svc", service, action)
        except (
            subprocess.CalledProcessError,
            subprocess.TimeoutExpired,
        ) as error:
            if first_error is None:
                first_error = error
    if first_error is not None:
        raise ControllerError(
            "failed to restore the Android device's pre-run network state"
        ) from first_error


def force_stop_and_relaunch(adb: Adb, candidate_package: str) -> None:
    adb.run("shell", "am", "force-stop", candidate_package)
    completed = adb.run(
        "shell",
        "monkey",
        "-p",
        candidate_package,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
        check=False,
    )
    if completed.returncode != 0:
        raise ControllerError("candidate relaunch failed after force-stop")
    time.sleep(0.5)


def push_inputs(
    adb: Adb,
    assignment_path: pathlib.Path,
    input_path: pathlib.Path,
) -> None:
    for kind, path in (("assignment", assignment_path), ("input", input_path)):
        adb.run(
            "shell",
            "content",
            "write",
            "--uri",
            f"content://org.scpc.r2.runner.files/inbox/{kind}",
            input_bytes=path.read_bytes(),
        )


def output_artifact_paths(output_dir: pathlib.Path) -> tuple[pathlib.Path, ...]:
    return tuple(output_dir / name for name in COLLECTED_ARTIFACT_NAMES)


def ensure_output_targets_available(output_dir: pathlib.Path) -> None:
    if output_dir.is_symlink():
        raise ControllerError(f"output directory must not be a symlink: {output_dir}")
    if output_dir.exists() and not output_dir.is_dir():
        raise ControllerError(f"output path is not a directory: {output_dir}")
    occupied = [
        path for path in output_artifact_paths(output_dir)
        if path.exists() or path.is_symlink()
    ]
    if occupied:
        names = ", ".join(path.name for path in occupied)
        raise ControllerError(
            f"refusing to overwrite existing Runner output: {names}; "
            "use a fresh output directory"
        )


def collect(adb: Adb, run_id: str, output_dir: pathlib.Path) -> None:
    ensure_output_targets_available(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    ensure_output_targets_available(output_dir)
    collected: list[tuple[pathlib.Path, bytes]] = []
    for remote_name, local_name in (
        (f"result-{run_id}.json", "PROBE_RESULT.json"),
        (f"completion-{run_id}.json", "RUNNER_COMPLETION_RECEIPT.json"),
        ("status.json", "RUNNER_STATUS.json"),
    ):
        completed = adb.run(
            "exec-out",
            "cat",
            f"{REMOTE_ROOT}/{remote_name}",
            check=False,
        )
        if completed.returncode != 0 or not completed.stdout:
            raise ControllerError(f"cannot collect {remote_name}")
        collected.append((output_dir / local_name, completed.stdout))

    created: list[pathlib.Path] = []
    try:
        for target, payload in collected:
            try:
                descriptor = os.open(
                    target,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                    0o600,
                )
            except FileExistsError as exc:
                raise ControllerError(
                    f"refusing to overwrite existing Runner output: {target.name}"
                ) from exc
            created.append(target)
            with os.fdopen(descriptor, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
        directory_descriptor = os.open(output_dir, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    except Exception:
        for target in created:
            try:
                target.unlink()
            except FileNotFoundError:
                pass
        raise


def run_flow(args: argparse.Namespace) -> None:
    assignment_path = pathlib.Path(args.assignment).resolve()
    input_path = pathlib.Path(args.input).resolve()
    assignment = load_json(assignment_path)
    input_document = load_json(input_path)
    run_id = assignment.get("run_id")
    candidate_package = assignment.get("candidate_package")
    steps = input_document.get("steps")
    if not isinstance(run_id, str) or not isinstance(candidate_package, str):
        raise ControllerError("assignment is missing run_id or candidate_package")
    if not isinstance(steps, list) or not steps:
        raise ControllerError("input steps must be a non-empty array")
    output_dir = pathlib.Path(args.output_dir).absolute()
    ensure_output_targets_available(output_dir)

    adb = Adb(args.adb, args.serial)
    adb.run("get-state")
    if args.runner_apk:
        adb.run("install", "-r", str(pathlib.Path(args.runner_apk).resolve()))
    if args.candidate_apk:
        adb.run("install", "-r", str(pathlib.Path(args.candidate_apk).resolve()))
    if args.clean:
        adb.run("shell", "pm", "clear", candidate_package)
    if getattr(args, "clear_runner_data", False):
        adb.run("shell", "pm", "clear", RUNNER_PACKAGE)

    changes_network = any(
        isinstance(step, dict) and step.get("operation") == "SET_NETWORK"
        for step in steps
    )
    prior_network = capture_network_state(adb) if changes_network else None
    try:
        push_inputs(adb, assignment_path, input_path)
        invoke(adb, "PREPARE")
        invoke(adb, "BEGIN", run_id)
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                raise ControllerError(f"step {index} is not an object")
            operation = step.get("operation")
            if operation == "PROCESS_KILL_RELAUNCH":
                force_stop_and_relaunch(adb, candidate_package)
            if operation == "SET_NETWORK":
                network = requested_network(step.get("roles"))
                if network:
                    set_network(adb, network)
            status = invoke(adb, "STEP", run_id, index)
            if status.get("next_step_index") != index + 1:
                raise ControllerError(f"Runner did not commit step {index}")
        invoke(adb, "FINISH", run_id)
        collect(adb, run_id, output_dir)
    finally:
        if prior_network is not None:
            restore_network(adb, prior_network)


def build_parser() -> argparse.ArgumentParser:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    discovered_adb = shutil.which("adb")
    home_sdk_adb = pathlib.Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"
    if android_home:
        default_adb = str(pathlib.Path(android_home) / "platform-tools" / "adb")
    elif discovered_adb:
        default_adb = discovered_adb
    elif home_sdk_adb.is_file():
        default_adb = str(home_sdk_adb)
    else:
        default_adb = "adb"
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="subcommand", required=True)
    run = subparsers.add_parser(
        "run",
        help="execute one complete Runner-driven Probe run",
    )
    run.add_argument(
        "--assignment", default="PUBLIC_RUN/ASSIGNMENT.json"
    )
    run.add_argument("--input", default="PUBLIC_RUN/PROBE_INPUT.json")
    run.add_argument("--output-dir", default="PUBLIC_RUN")
    run.add_argument(
        "--runner-apk",
        default="probe/scpc-dacon-runner-3.0.0-draft.apk",
    )
    run.add_argument("--candidate-apk", default="APP.apk")
    run.add_argument("--serial")
    run.add_argument("--adb", default=default_adb)
    run.add_argument(
        "--clean",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="clear candidate app data before the run (default: true)",
    )
    run.add_argument(
        "--clear-runner-data",
        action="store_true",
        help=(
            "explicit lab recovery only: clear Runner data, including the "
            "anti-replay ledger (default: false)"
        ),
    )
    run.set_defaults(function=run_flow)
    return parser


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.function(args)
        return 0
    except (ControllerError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

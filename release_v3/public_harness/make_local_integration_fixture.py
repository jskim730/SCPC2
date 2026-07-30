#!/usr/bin/env python3
"""Create a fresh LOCAL_TEST_ONLY assignment/input pair."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import secrets
import time

from apk_release_info import calculate_release_attestation_id, inspect_apk


def require_fresh_output(path: pathlib.Path) -> None:
    if path.is_symlink():
        raise ValueError(f"output directory must not be a symlink: {path}")
    if path.exists() and not path.is_dir():
        raise ValueError(f"output path is not a directory: {path}")
    if path.exists():
        existing = sorted(item.name for item in path.iterdir())
        if existing:
            raise ValueError(
                "refusing to overwrite a non-empty fixture directory "
                f"({', '.join(existing)}); use a fresh empty output directory"
            )


def write_new_file(path: pathlib.Path, payload: bytes) -> None:
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
    except FileExistsError as error:
        raise ValueError(
            f"refusing to overwrite existing fixture file: {path.name}"
        ) from error
    with os.fdopen(descriptor, "wb") as output:
        output.write(payload)
        output.flush()
        os.fsync(output.fileno())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--public-input", default="probe/PUBLIC_PROBE_INPUT_13_STEP.json"
    )
    parser.add_argument("--candidate-apk", default="APP.apk")
    parser.add_argument("--sdk-root")
    parser.add_argument("--output-dir", default="PUBLIC_RUN")
    parser.add_argument(
        "--mission-receipt",
        help=(
            "Dacon-issued MISSION_LOCK.json; when omitted, the tool uses "
            "./MISSION_LOCK.json if present"
        ),
    )
    parser.add_argument(
        "--candidate-id",
        help="candidate ID to inject; defaults to the public input value",
    )
    parser.add_argument(
        "--mission-id",
        help="mission ID to inject; defaults to the public input value",
    )
    args = parser.parse_args()
    output = pathlib.Path(args.output_dir)
    try:
        require_fresh_output(output)
    except ValueError as error:
        parser.error(str(error))

    try:
        source = json.loads(
            pathlib.Path(args.public_input).read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        parser.error(f"cannot read public input JSON: {error}")
    mission_receipt_path = (
        pathlib.Path(args.mission_receipt)
        if args.mission_receipt
        else pathlib.Path("MISSION_LOCK.json")
    )
    mission_receipt = {}
    if mission_receipt_path.is_file():
        try:
            mission_receipt = json.loads(
                mission_receipt_path.read_text(encoding="utf-8")
            )
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            parser.error(f"cannot read MISSION_LOCK.json: {error}")
    candidate_id = (
        args.candidate_id
        or mission_receipt.get("candidate_id")
        or source.get("candidate_id")
    )
    mission_id = (
        args.mission_id
        or mission_receipt.get("mission_id")
        or source.get("mission_id")
    )
    for label, value in (
        ("candidate_id", candidate_id),
        ("mission_id", mission_id),
    ):
        if (
            not isinstance(value, str)
            or not value
            or len(value) > 128
            or value != value.strip()
        ):
            parser.error(
                f"{label} must be 1..128 characters without outer whitespace"
            )
    sdk_root_value = (
        args.sdk_root
        or os.environ.get("ANDROID_SDK_ROOT")
        or os.environ.get("ANDROID_HOME")
    )
    if not sdk_root_value:
        parser.error(
            "Android SDK path is unavailable; set ANDROID_SDK_ROOT or pass --sdk-root"
        )
    try:
        release = inspect_apk(
            pathlib.Path(args.candidate_apk),
            pathlib.Path(sdk_root_value),
        )
    except (OSError, ValueError) as error:
        parser.error(str(error))
    release_attestation_id = calculate_release_attestation_id(release)
    suffix = secrets.token_hex(8)
    source["content_class"] = "OFFICIAL_RESTRICTED_INPUT_NO_ORACLE"
    source["instance_id"] = f"local-instance-{suffix}"
    source["request_nonce"] = f"local_nonce_{suffix}"
    source["operator_run_token"] = f"local_token_{suffix}"
    source["candidate_id"] = candidate_id
    source["mission_id"] = mission_id
    source["release_attestation_id"] = release_attestation_id
    run_id = f"local-run-{suffix}"
    input_bytes = json.dumps(
        source,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    assignment = {
        "assignment_id": f"local-assignment-{suffix}",
        "candidate_package": release["package_name"],
        "candidate_id": source["candidate_id"],
        "candidate_apk_sha256": release["apk_sha256"],
        "candidate_signing_certificate_sha256":
            release["signing_certificate_sha256"],
        "candidate_version_name": release["version_name"],
        "candidate_version_code": release["version_code"],
        "release_attestation_id": release_attestation_id,
        "probe_input_sha256": hashlib.sha256(input_bytes).hexdigest(),
        "run_id": run_id,
        "run_token_secret": f"local_secret_{secrets.token_urlsafe(32)}",
        "expires_at_epoch_ms": int(time.time() * 1000) + 15 * 60 * 1000,
    }
    output.mkdir(parents=True, exist_ok=True)
    try:
        require_fresh_output(output)
        assignment_bytes = json.dumps(
            assignment,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        write_new_file(output / "PROBE_INPUT.json", input_bytes)
        try:
            write_new_file(
                output / "ASSIGNMENT.json",
                assignment_bytes,
            )
        except Exception:
            (output / "PROBE_INPUT.json").unlink(missing_ok=True)
            raise
    except ValueError as error:
        parser.error(str(error))
    print(run_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

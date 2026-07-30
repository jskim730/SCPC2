#!/usr/bin/env python3
"""Build a complete v3 SAMPLE_EXPORT from immutable run inputs.

The output directory must not exist.  The finalizer validates all inputs,
copies the Runner result without changing a byte, derives the remaining
indexes, validates the completed export, and only then publishes the directory.

Exit codes:
  0 PASS
  2 CONTRACT_ERROR
  4 TOOL_ERROR
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from typing import Any
import zipfile

import VALIDATE_SAMPLE_EXPORT as validator


EXIT_PASS = validator.EXIT_PASS
EXIT_CONTRACT_ERROR = validator.EXIT_CONTRACT_ERROR
EXIT_TOOL_ERROR = validator.EXIT_TOOL_ERROR
ContractError = validator.ContractError
# Kept as a module attribute so a test can replace Android SDK inspection.
inspect_apk = validator.inspect_apk


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def collect_evidence(
    evidence_dir: Path,
    step_map: dict[str, list[str]],
) -> tuple[list[dict[str, Any]], list[tuple[Path, Path]]]:
    if not evidence_dir.is_dir():
        raise ContractError(f"evidence directory does not exist: {evidence_dir}")
    by_id: dict[str, tuple[Path, Path]] = {}
    for source in sorted(evidence_dir.rglob("*")):
        if source.is_symlink():
            raise ContractError(f"evidence symlink is forbidden: {source}")
        if source.is_dir():
            continue
        if not source.is_file():
            raise ContractError(f"evidence contains a non-file entry: {source}")
        relative = source.relative_to(evidence_dir)
        output_relative = Path("evidence") / relative
        validator.safe_relative(output_relative.as_posix())
        evidence_id = source.stem
        if evidence_id in by_id:
            raise ContractError(
                f"duplicate evidence filename stem/evidence_id: {evidence_id}"
            )
        validator.media_type_for(source)
        by_id[evidence_id] = (source, output_relative)

    if set(by_id) != set(step_map):
        raise ContractError(
            "evidence filenames differ from Runner result evidence IDs: "
            f"missing={sorted(set(step_map) - set(by_id))} "
            f"extra={sorted(set(by_id) - set(step_map))}"
        )

    items: list[dict[str, Any]] = []
    copies: list[tuple[Path, Path]] = []
    # Python dictionaries preserve the Runner result evidence_ids order because
    # evidence_step_map builds its keys while walking the declared step results.
    for evidence_id, step_ids in step_map.items():
        source, output_relative = by_id[evidence_id]
        media_type = validator.validate_evidence_file(source)
        items.append(
            {
                "evidence_id": evidence_id,
                "relative_path": output_relative.as_posix(),
                "media_type": media_type,
                "step_ids": step_ids,
            }
        )
        copies.append((source, output_relative))
    return items, copies


def manifest_for(sample_root: Path) -> tuple[list[dict[str, Any]], int]:
    files = validator.collect_export_files(sample_root)
    rows = [
        {
            "relative_path": relative,
            "sha256": validator.file_sha256(path),
            "size_bytes": path.stat().st_size,
        }
        for relative, path in sorted(files.items())
    ]
    total = sum(row["size_bytes"] for row in rows)
    if total > 20 * 1024 * 1024:
        raise ContractError("SAMPLE_EXPORT exceeds the 20 MiB limit")
    return rows, total


def finalize_sample_export(
    output_dir: Path,
    *,
    mission_receipt_path: Path,
    app_apk: Path,
    source_zip: Path,
    public_input_path: Path,
    runner_result_path: Path,
    evidence_dir: Path,
    schema_root: Path,
    sdk_root: Path,
) -> dict[str, Any]:
    """Create and validate an export, publishing it only after every check passes."""

    output_dir = output_dir.resolve()
    if output_dir.exists():
        raise ContractError(
            f"output directory already exists; use a fresh path: {output_dir}"
        )
    output_dir.parent.mkdir(parents=True, exist_ok=True)

    mission_bytes = mission_receipt_path.read_bytes()
    result_bytes = runner_result_path.read_bytes()
    mission = validator.parse_json_bytes(mission_bytes, str(mission_receipt_path))
    public_input = validator.load_json(public_input_path)
    probe_result = validator.parse_json_bytes(result_bytes, str(runner_result_path))
    apk_identity = inspect_apk(app_apk, sdk_root)
    external = validator.validate_external_contract(
        mission_lock=mission,
        public_input=public_input,
        probe_result=probe_result,
        app_apk=app_apk,
        source_zip=source_zip,
        public_input_path=public_input_path,
        schema_root=schema_root,
        sdk_root=sdk_root,
        apk_identity=apk_identity,
    )
    items, evidence_copies = collect_evidence(
        evidence_dir, external["evidence_step_map"]
    )

    common = {
        "candidate_id": probe_result["candidate_id"],
        "release_id": external["release_id"],
        "run_id": probe_result["run_id"],
        "operator_run_token": probe_result["operator_run_token"],
    }
    identity = {
        **apk_identity,
        "source_archive_sha256": external["source_archive_sha256"],
        "model_configured": external["model_configured"],
        "probe_contract_profile": probe_result["probe_contract_profile"],
    }
    runtime = {
        "schema_version": "3.0.0-draft",
        "artifact_kind": "runtime_identity_pair",
        **common,
        "runtime_identity_start": {
            **identity,
            "cumulative_invocations": probe_result["runtime_identity_start"][
                "cumulative_invocations"
            ],
        },
        "runtime_identity_end": {
            **identity,
            "cumulative_invocations": probe_result["runtime_identity_end"][
                "cumulative_invocations"
            ],
        },
    }
    evidence = {
        "schema_version": "3.0.0-draft",
        "artifact_kind": "evidence_index",
        **common,
        "items": items,
    }
    validator.validate_schema(
        runtime, schema_root / "RUNTIME_IDENTITY.schema.json", "runtime_identity"
    )
    validator.validate_schema(
        evidence, schema_root / "EVIDENCE_INDEX.schema.json", "evidence_index"
    )
    validator.reject_placeholders(runtime, "runtime_identity")
    validator.reject_placeholders(evidence, "evidence_index")

    temporary = Path(
        tempfile.mkdtemp(
            prefix=f".{output_dir.name}.tmp-",
            dir=str(output_dir.parent),
        )
    )
    try:
        # These two contract records are immutable inputs, not rewritten JSON.
        (temporary / "MISSION_LOCK.json").write_bytes(mission_bytes)
        (temporary / "PUBLIC_PROBE_RESULT.json").write_bytes(result_bytes)
        write_json(temporary / "RUNTIME_IDENTITY.json", runtime)
        write_json(temporary / "EVIDENCE_INDEX.json", evidence)
        for source, relative in evidence_copies:
            destination = temporary / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)

        file_manifest, total = manifest_for(temporary)
        export = {
            "schema_version": "3.0.0-draft",
            "artifact_kind": "sample_export_index",
            "content_class": "REFERENCE_SAMPLE_NOT_OFFICIAL",
            **common,
            "mission_id": probe_result["mission_id"],
            "app_release_binding": {
                "package_name": apk_identity["package_name"],
                "version_name": apk_identity["version_name"],
                "version_code": apk_identity["version_code"],
                "app_release_sha256": apk_identity["app_release_sha256"],
                "signing_certificate_sha256": apk_identity[
                    "signing_certificate_sha256"
                ],
            },
            "source_binding": {
                "source_archive_sha256": external["source_archive_sha256"],
                "mission_adapter_relative_path": external[
                    "mission_adapter_relative_path"
                ],
                "mission_adapter_sha256": external[
                    "mission_adapter_sha256"
                ],
            },
            "file_manifest": file_manifest,
            "total_uncompressed_bytes": total,
        }
        validator.validate_schema(
            export, schema_root / "EXPORT_INDEX.schema.json", "export_index"
        )
        validator.reject_placeholders(export, "export_index")
        write_json(temporary / "EXPORT_INDEX.json", export)

        validation = validator.validate_sample_export(
            temporary,
            app_apk=app_apk,
            source_zip=source_zip,
            public_input_path=public_input_path,
            schema_root=schema_root,
            sdk_root=sdk_root,
            apk_identity=apk_identity,
        )
        if output_dir.exists():
            raise ContractError(
                f"output directory appeared during finalization: {output_dir}"
            )
        os.replace(temporary, output_dir)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise

    return {
        **validation,
        "output_dir": str(output_dir),
        "runner_result_bytes_preserved": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output_dir", type=Path)
    parser.add_argument(
        "--mission-receipt", type=Path, default=Path("MISSION_LOCK.json")
    )
    parser.add_argument("--app-apk", type=Path, default=Path("APP.apk"))
    parser.add_argument("--source-zip", type=Path, default=Path("SOURCE.zip"))
    parser.add_argument(
        "--public-input",
        type=Path,
        default=Path("PUBLIC_RUN/PROBE_INPUT.json"),
    )
    parser.add_argument(
        "--runner-result",
        type=Path,
        default=Path("PUBLIC_RUN/PROBE_RESULT.json"),
    )
    parser.add_argument(
        "--evidence-dir", type=Path, default=Path("PUBLIC_RUN/evidence")
    )
    parser.add_argument(
        "--schema-root", type=Path, default=Path(__file__).resolve().parent
    )
    parser.add_argument("--sdk-root", type=Path)
    args = parser.parse_args()
    try:
        sdk_root = validator.resolve_sdk_root(args.sdk_root)
        result = finalize_sample_export(
            args.output_dir,
            mission_receipt_path=args.mission_receipt.resolve(),
            app_apk=args.app_apk.resolve(),
            source_zip=args.source_zip.resolve(),
            public_input_path=args.public_input.resolve(),
            runner_result_path=args.runner_result.resolve(),
            evidence_dir=args.evidence_dir.resolve(),
            schema_root=args.schema_root.resolve(),
            sdk_root=sdk_root,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return EXIT_PASS
    except (
        ContractError,
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        zipfile.BadZipFile,
        subprocess.CalledProcessError,
    ) as error:
        print(f"CONTRACT_ERROR: {error}", file=sys.stderr)
        return EXIT_CONTRACT_ERROR
    except Exception as error:
        print(f"TOOL_ERROR: {error}", file=sys.stderr)
        return EXIT_TOOL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())

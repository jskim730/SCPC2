#!/usr/bin/env python3
"""Calculate a non-official Q/80 estimate from public rehearsal anchors."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
LOOKUP_PATH = ROOT / "PUBLIC_SCORING_LOOKUP.json"
EXPECTED_PROBES = [f"CORE-{index}" for index in range(1, 7)]
REQUIRED_FACT_FIELDS = {
    "probe_id",
    "observed",
    "expected_property",
    "difference",
    "evidence_ids",
    "anchor",
}


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON at line {exc.lineno}, column {exc.colno}: {path}") from exc


def validate_submission(value: Any) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("top level must be an object")
    allowed_top_fields = {"schema_version", "content_class", "notes", "facts"}
    required_top_fields = {"schema_version", "content_class", "facts"}
    missing_top = required_top_fields - set(value)
    extra_top = set(value) - allowed_top_fields
    if missing_top or extra_top:
        raise ValueError(
            "top-level fields mismatch; "
            f"missing={sorted(missing_top)}, extra={sorted(extra_top)}"
        )
    if value.get("schema_version") != "3.0.0-draft":
        raise ValueError("schema_version must be 3.0.0-draft")
    if value.get("content_class") != "PUBLIC_REHEARSAL_SELF_ASSESSMENT_NOT_OFFICIAL":
        raise ValueError("content_class is not the public self-assessment value")
    if "notes" in value and (
        not isinstance(value["notes"], str) or len(value["notes"]) > 4000
    ):
        raise ValueError("notes must be a string of at most 4000 characters")
    facts = value.get("facts")
    if not isinstance(facts, list) or len(facts) != 6:
        raise ValueError("facts must contain exactly six items")

    anchors: dict[str, int] = {}
    for index, fact in enumerate(facts):
        if not isinstance(fact, dict):
            raise ValueError(f"facts[{index}] must be an object")
        missing = REQUIRED_FACT_FIELDS - set(fact)
        extra = set(fact) - REQUIRED_FACT_FIELDS
        if missing or extra:
            raise ValueError(
                f"facts[{index}] fields mismatch; missing={sorted(missing)}, extra={sorted(extra)}"
            )
        probe_id = fact["probe_id"]
        if probe_id not in EXPECTED_PROBES:
            raise ValueError(f"facts[{index}].probe_id is invalid: {probe_id!r}")
        if probe_id in anchors:
            raise ValueError(f"duplicate probe_id: {probe_id}")
        text_limits = {
            "observed": 4000,
            "expected_property": 2000,
            "difference": 2000,
        }
        for field, maximum in text_limits.items():
            if (
                not isinstance(fact[field], str)
                or not fact[field].strip()
                or len(fact[field]) > maximum
            ):
                raise ValueError(f"facts[{index}].{field} must be a non-empty string")
        evidence = fact["evidence_ids"]
        if (
            not isinstance(evidence, list)
            or not evidence
            or any(not isinstance(item, str) or not item.strip() for item in evidence)
            or any(len(item) > 256 for item in evidence)
            or len(evidence) != len(set(evidence))
        ):
            raise ValueError(f"facts[{index}].evidence_ids must be unique non-empty strings")
        anchor = fact["anchor"]
        if isinstance(anchor, bool) or not isinstance(anchor, int) or not 0 <= anchor <= 4:
            raise ValueError(f"facts[{index}].anchor must be an integer from 0 to 4")
        anchors[probe_id] = anchor

    if sorted(anchors) != EXPECTED_PROBES:
        raise ValueError("facts must contain CORE-1 through CORE-6 exactly once")
    return anchors


def validate_lookup(lookup: Any) -> None:
    if not isinstance(lookup, dict) or lookup.get("lookup_id") != "Q80_CORE_ANCHOR_LOOKUP_V3_PUBLIC_1":
        raise ValueError("unexpected public lookup manifest")
    profiles = lookup.get("profiles")
    if not isinstance(profiles, list) or [item.get("id") for item in profiles] != [
        "Q1",
        "Q2",
        "Q3",
        "Q4",
        "Q5",
        "Q6",
    ]:
        raise ValueError("public lookup must contain ordered Q1 through Q6 profiles")
    if sum(item["maximum"] for item in profiles) != 80:
        raise ValueError("public lookup profile maxima must sum to 80")
    for profile in profiles:
        for component in profile.get("components", []):
            if component.get("probe_id") not in EXPECTED_PROBES:
                raise ValueError("lookup contains an unknown CORE probe")
            points = component.get("lookup_points")
            if not isinstance(points, list) or len(points) != 5:
                raise ValueError("each lookup component needs five anchor values")


def calculate(anchors: dict[str, int], lookup: dict[str, Any]) -> dict[str, Any]:
    profiles: dict[str, float] = {}
    for profile in lookup["profiles"]:
        value = sum(
            component["lookup_points"][anchors[component["probe_id"]]]
            for component in profile["components"]
        )
        profiles[profile["id"]] = value
    total = sum(profiles.values())
    if total < 40:
        band = "REWORK_CORE_FLOW"
    elif total < 56:
        band = "DEVELOP_AND_RETEST"
    elif total < 68:
        band = "REHEARSE_HIDDEN_VARIANTS"
    else:
        band = "STRONG_SELF_REPORTED_EVIDENCE"
    return {
        "artifact_kind": "public_self_score_result",
        "official_score": False,
        "lookup_id": lookup["lookup_id"],
        "anchors": anchors,
        "profiles": profiles,
        "q_total": total,
        "q_maximum": 80,
        "readiness_band": band,
        "warning": (
            "Self-assigned public rehearsal anchors are not official machine-oracle outcomes. "
            "Hidden values, order, oracle, qualification and pool cuts are not represented."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("self_score", type=Path, nargs="?")
    parser.add_argument("--json", action="store_true", help="print machine-readable result")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    try:
        lookup = load_json(LOOKUP_PATH)
        validate_lookup(lookup)
        if args.self_test:
            anchors = {probe: 3 for probe in EXPECTED_PROBES}
            result = calculate(anchors, lookup)
            if result["q_total"] != 60:
                raise ValueError(f"self-test expected 60, got {result['q_total']}")
            sample_fact = {
                "observed": "observed",
                "expected_property": "expected",
                "difference": "difference",
                "evidence_ids": ["evidence-1"],
                "anchor": 3,
            }
            invalid_duplicate = {
                "schema_version": "3.0.0-draft",
                "content_class": "PUBLIC_REHEARSAL_SELF_ASSESSMENT_NOT_OFFICIAL",
                "facts": [
                    {**sample_fact, "probe_id": "CORE-1"},
                    {**sample_fact, "probe_id": "CORE-1"},
                    {**sample_fact, "probe_id": "CORE-3"},
                    {**sample_fact, "probe_id": "CORE-4"},
                    {**sample_fact, "probe_id": "CORE-5"},
                    {**sample_fact, "probe_id": "CORE-6"},
                ],
            }
            try:
                validate_submission(invalid_duplicate)
            except ValueError:
                pass
            else:
                raise ValueError("self-test expected duplicate CORE rejection")
            invalid_extra = {
                "schema_version": "3.0.0-draft",
                "content_class": "PUBLIC_REHEARSAL_SELF_ASSESSMENT_NOT_OFFICIAL",
                "facts": [
                    {**sample_fact, "probe_id": probe}
                    for probe in EXPECTED_PROBES
                ],
                "official_score": 80,
            }
            try:
                validate_submission(invalid_extra)
            except ValueError:
                pass
            else:
                raise ValueError("self-test expected top-level extra-field rejection")
            print("PASS: public self-score lookup and calculator")
            return 0
        if args.self_score is None:
            parser.error("self_score is required unless --self-test is used")
        submission = load_json(args.self_score)
        anchors = validate_submission(submission)
        result = calculate(anchors, lookup)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print("SCPC R2 v3 PUBLIC SELF-SCORE — NOT OFFICIAL")
        print(" ".join(f"{key}={value:g}" for key, value in result["profiles"].items()))
        print(f"Q={result['q_total']:g}/80  band={result['readiness_band']}")
        print(result["warning"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Build the V1-V4 metamorphic probe inputs described in PROBE_METAMORPHIC_TEST_PLAN.

The plan deliberately left the generator unwritten until the app existed, because a
real Runner input needs a release attestation and a run token that only the official
`make_local_integration_fixture.py` can mint. So this writes *public input* documents
in the same shape as `release_v3/probe/PUBLIC_PROBE_INPUT_13_STEP.json`, and the
official generator is then pointed at each one with `--public-input`. That keeps every
digest and assignment field computed by the official tool; nothing here forges them.

Each variant is validated against `release_v3/candidate_kit/PROBE_INPUT.schema.json`
before it is written, so a malformed variant fails here rather than after an emulator
run. `release_v3/` is only ever read.

    .venv\\Scripts\\python.exe tools\\make_probe_variants.py
"""

from __future__ import annotations

import copy
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / "release_v3" / "probe" / "PUBLIC_PROBE_INPUT_13_STEP.json"
SCHEMA = ROOT / "release_v3" / "candidate_kit" / "PROBE_INPUT.schema.json"
OUT = ROOT / "test-fixtures" / "probe"

# The ten contract roles carry opaque tokens. NETWORK_STATE is excluded on purpose:
# its value is a closed contract enum, so substituting it would make the input invalid
# rather than testing anything. REPLAY_OF_EVENT_ID and OLDER_EVENT_ID hold event ids
# and are remapped with the event ids themselves.
OPAQUE_ROLES = (
    "PRIMARY_GOAL",
    "TARGET_ENTITY",
    "DISTRACTOR_ENTITY",
    "STABLE_VALUE",
    "ONE_OFF_VALUE",
    "CURRENT_AUTHORITY",
    "REVOKED_SCOPE",
    "PRESERVED_SCOPE",
    "DELAYED_OUTCOME",
    "EPHEMERAL_VALUE",
)
EVENT_ID_ROLES = ("REPLAY_OF_EVENT_ID", "OLDER_EVENT_ID")

NETWORK_STATES = ("ONLINE", "OFFLINE", "DELAYED", "UNKNOWN")


def load_base() -> dict:
    return json.loads(BASE.read_text(encoding="utf-8"))


def envelope(source: dict, pack_suffix: str) -> dict:
    """Copy the base envelope, keeping every field the official generator overwrites."""
    document = {key: value for key, value in source.items() if key != "steps"}
    document["probe_pack_id"] = f"{source['probe_pack_id']}-{pack_suffix}"
    document["steps"] = []
    return document


def step(
    ordinal: int,
    operation: str,
    session: str,
    roles: dict,
    minute: int,
    prefix: str,
) -> dict:
    return {
        "event_id": f"EVENT-{prefix}-{ordinal:02d}-{operation}",
        "operation": operation,
        "roles": roles,
        "session_id": session,
        "step_id": f"{prefix}-{ordinal:02d}",
        "virtual_time": f"2026-02-01T{minute // 60:02d}:{minute % 60:02d}:00Z",
    }


# --------------------------------------------------------------------- V1


def build_v1(source: dict) -> dict:
    """Replace every opaque token, preserving only equality between them."""
    document = envelope(source, "V1")
    token_map: dict[str, str] = {}
    event_map: dict[str, str] = {}

    for index, original in enumerate(source["steps"], start=1):
        event_map[original["event_id"]] = f"EVENT-V1-{index:02d}-OPAQUE"

    for index, original in enumerate(source["steps"], start=1):
        roles: dict[str, str] = {}
        for name, value in original["roles"].items():
            if name in EVENT_ID_ROLES:
                roles[name] = event_map[value]
            elif name == "NETWORK_STATE":
                roles[name] = value
            elif name in OPAQUE_ROLES:
                if value not in token_map:
                    token_map[value] = f"v1-tok-{len(token_map):02d}"
                roles[name] = token_map[value]
            else:
                roles[name] = value
        document["steps"].append(
            {
                "event_id": event_map[original["event_id"]],
                "operation": original["operation"],
                "roles": roles,
                "session_id": f"V1-{original['session_id']}",
                "step_id": f"V1-{index:02d}",
                "virtual_time": original["virtual_time"],
            }
        )
    return document


# --------------------------------------------------------------------- V2


def build_v2(source: dict) -> dict:
    """Move the old target to the distractor slot and give the distractor recency.

    The old target keeps its restaurant-specific facts and receives a *newer*
    authority than the new target, so choosing by "most recent" or by the token that
    appeared first both give the wrong answer.
    """
    document = envelope(source, "V2")
    old_target = "PUBLIC_ENTITY_A"
    new_target = "v2-entity-c"
    new_goal = "v2-goal-beta"

    event_map = {
        original["event_id"]: f"EVENT-V2-{index:02d}-{original['operation']}"
        for index, original in enumerate(source["steps"], start=1)
    }

    for index, original in enumerate(source["steps"], start=1):
        roles: dict[str, str] = {}
        for name, value in original["roles"].items():
            if name in EVENT_ID_ROLES:
                roles[name] = event_map[value]
            elif name == "TARGET_ENTITY":
                roles[name] = new_target
            elif name == "DISTRACTOR_ENTITY":
                roles[name] = old_target
            elif name == "PRIMARY_GOAL":
                roles[name] = new_goal
            else:
                roles[name] = value
        document["steps"].append(
            {
                "event_id": f"EVENT-V2-{index:02d}-{original['operation']}",
                "operation": original["operation"],
                "roles": roles,
                "session_id": f"V2-{original['session_id']}",
                "step_id": f"V2-{index:02d}",
                "virtual_time": original["virtual_time"],
            }
        )

    # ADVANCE_SESSION is step 3; the distractor fact lands right after it so it is
    # both newer than the target's fact and present before the first decision.
    distractor_fact = {
        "event_id": "EVENT-V2-03B-UPSERT_FACT",
        "operation": "UPSERT_FACT",
        "roles": {
            "CURRENT_AUTHORITY": "PUBLIC_AUTHORITY_V9",
            "PRESERVED_SCOPE": "v2-scope-distractor-only",
            "STABLE_VALUE": "v2-stable-wrong-entity",
            "TARGET_ENTITY": old_target,
        },
        "session_id": "V2-SESSION-B",
        "step_id": "V2-03B",
        "virtual_time": "2026-01-01T00:02:30Z",
    }
    document["steps"].insert(3, distractor_fact)
    return document


# --------------------------------------------------------------------- V3


def build_v3(source: dict) -> dict:
    """The plan's 18-step order variation: repeats, a mid-run reset, network cycling."""
    document = envelope(source, "V3")
    goal = "v3-goal"
    entity = "v3-entity-1"
    second_entity = "v3-entity-2"
    scope = "v3-scope-keep"
    plan: list[tuple[str, str, dict]] = [
        ("RESET_AND_START", "V3-SESSION-A", {}),
        (
            "UPSERT_FACT",
            "V3-SESSION-A",
            {
                "CURRENT_AUTHORITY": "v3-authority-v1",
                "PRESERVED_SCOPE": scope,
                "PRIMARY_GOAL": goal,
                "STABLE_VALUE": "v3-stable-1",
                "TARGET_ENTITY": entity,
            },
        ),
        ("REQUEST_DECISION", "V3-SESSION-A", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        # Repeat with no state change in between.
        ("REQUEST_DECISION", "V3-SESSION-A", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        ("SET_NETWORK", "V3-SESSION-A", {"NETWORK_STATE": "OFFLINE"}),
        ("REQUEST_DECISION", "V3-SESSION-A", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        ("SET_NETWORK", "V3-SESSION-A", {"NETWORK_STATE": "ONLINE"}),
        ("REQUEST_DECISION", "V3-SESSION-A", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        (
            "ADVANCE_SESSION",
            "V3-SESSION-B",
            {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity},
        ),
        (
            "CORRECT_FACT",
            "V3-SESSION-B",
            {
                "CURRENT_AUTHORITY": "v3-authority-v2",
                "ONE_OFF_VALUE": "v3-corrected",
                "PRESERVED_SCOPE": scope,
                "TARGET_ENTITY": entity,
            },
        ),
        ("REQUEST_DECISION", "V3-SESSION-B", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        (
            "REVOKE_SCOPE",
            "V3-SESSION-B",
            {"PRESERVED_SCOPE": scope, "REVOKED_SCOPE": "v3-scope-revoke"},
        ),
        ("REQUEST_DECISION", "V3-SESSION-B", {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}),
        # Second reset: active state clears, the first run stays in the audit trail.
        ("RESET_AND_START", "V3-SESSION-C", {}),
        (
            "UPSERT_FACT",
            "V3-SESSION-C",
            {
                "CURRENT_AUTHORITY": "v3-authority-v3",
                "PRESERVED_SCOPE": "v3-scope-second-run",
                "PRIMARY_GOAL": "v3-goal-second",
                "STABLE_VALUE": "v3-stable-2",
                "TARGET_ENTITY": second_entity,
            },
        ),
        (
            "REQUEST_DECISION",
            "V3-SESSION-C",
            {"PRIMARY_GOAL": "v3-goal-second", "TARGET_ENTITY": second_entity},
        ),
        ("PROCESS_KILL_RELAUNCH", "V3-SESSION-D", {}),
        ("EXPORT_AND_END", "V3-SESSION-D", {}),
    ]
    for index, (operation, session, roles) in enumerate(plan, start=1):
        document["steps"].append(step(index, operation, session, roles, index, "V3"))
    return document


# --------------------------------------------------------------------- V4


def build_v4(source: dict) -> dict:
    """The plan's 26-step long run: delayed outcome, replay, out-of-order, delete."""
    document = envelope(source, "V4")
    goal = "v4-goal"
    entity = "v4-entity"
    scope = "v4-scope-keep"
    side_scope = "v4-scope-side"
    decision = {"PRIMARY_GOAL": goal, "TARGET_ENTITY": entity}
    fact_event = "EVENT-V4-02-UPSERT_FACT"
    first_decision_event = "EVENT-V4-03-REQUEST_DECISION"

    plan: list[tuple[str, str, dict]] = [
        ("RESET_AND_START", "V4-SESSION-A", {}),
        (
            "UPSERT_FACT",
            "V4-SESSION-A",
            {
                "CURRENT_AUTHORITY": "v4-authority-v1",
                "DELAYED_OUTCOME": "v4-outcome-scheduled",
                "EPHEMERAL_VALUE": "v4-ephemeral-request",
                "ONE_OFF_VALUE": "v4-once",
                "PRESERVED_SCOPE": scope,
                "PRIMARY_GOAL": goal,
                "STABLE_VALUE": "v4-stable",
                "TARGET_ENTITY": entity,
            },
        ),
        ("REQUEST_DECISION", "V4-SESSION-A", dict(decision)),
        ("REQUEST_DECISION", "V4-SESSION-A", dict(decision)),  # idempotent repeat
        ("ADVANCE_SESSION", "V4-SESSION-B", dict(decision)),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        (
            "CORRECT_FACT",
            "V4-SESSION-B",
            {
                "CURRENT_AUTHORITY": "v4-authority-v2",
                "ONE_OFF_VALUE": "v4-corrected",
                "PRESERVED_SCOPE": scope,
                "TARGET_ENTITY": entity,
            },
        ),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        (
            "REVOKE_SCOPE",
            "V4-SESSION-B",
            {"PRESERVED_SCOPE": scope, "REVOKED_SCOPE": "v4-scope-revoke"},
        ),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        ("SET_NETWORK", "V4-SESSION-B", {"NETWORK_STATE": "DELAYED"}),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        ("SET_NETWORK", "V4-SESSION-B", {"NETWORK_STATE": "OFFLINE"}),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        ("SET_NETWORK", "V4-SESSION-B", {"NETWORK_STATE": "ONLINE"}),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        (
            "UPSERT_FACT",
            "V4-SESSION-B",
            {
                "CURRENT_AUTHORITY": "v4-authority-v3",
                "PRESERVED_SCOPE": side_scope,
                "STABLE_VALUE": "v4-side-out-of-stock",
                "TARGET_ENTITY": entity,
            },
        ),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        ("ADVANCE_TIME", "V4-SESSION-B", {"DELAYED_OUTCOME": "v4-outcome-scheduled"}),
        ("REQUEST_DECISION", "V4-SESSION-B", dict(decision)),
        ("PROCESS_KILL_RELAUNCH", "V4-SESSION-C", {}),
        ("REQUEST_DECISION", "V4-SESSION-C", dict(decision)),
        ("REPLAY_EVENT", "V4-SESSION-C", {"REPLAY_OF_EVENT_ID": first_decision_event}),
        (
            "DELIVER_OUT_OF_ORDER",
            "V4-SESSION-C",
            {"CURRENT_AUTHORITY": "v4-authority-v1", "OLDER_EVENT_ID": fact_event},
        ),
        (
            "DELETE_FACT",
            "V4-SESSION-C",
            {"EPHEMERAL_VALUE": "v4-ephemeral-request", "TARGET_ENTITY": entity},
        ),
        ("EXPORT_AND_END", "V4-SESSION-C", {}),
    ]
    for index, (operation, session, roles) in enumerate(plan, start=1):
        document["steps"].append(step(index, operation, session, roles, index, "V4"))
    return document


# --------------------------------------------------------------------- checks


def check_invariants(name: str, document: dict) -> list[str]:
    """The plan's common invariants, checked before anything touches a device."""
    problems: list[str] = []
    steps = document["steps"]
    if not 1 <= len(steps) <= 80:
        problems.append(f"step count {len(steps)} outside 1..80")

    step_ids = [entry["step_id"] for entry in steps]
    event_ids = [entry["event_id"] for entry in steps]
    if len(set(step_ids)) != len(step_ids):
        problems.append("duplicate step_id")
    if len(set(event_ids)) != len(event_ids):
        problems.append("duplicate event_id")

    times = [entry["virtual_time"] for entry in steps]
    if times != sorted(times):
        problems.append("virtual_time is not non-decreasing")

    known = set(event_ids)
    for entry in steps:
        for role in EVENT_ID_ROLES:
            referenced = entry["roles"].get(role)
            if referenced is not None and referenced not in known:
                problems.append(f"{entry['step_id']} {role} references unknown {referenced}")
        state = entry["roles"].get("NETWORK_STATE")
        if state is not None and state not in NETWORK_STATES:
            problems.append(f"{entry['step_id']} NETWORK_STATE {state} is not a contract enum")

    if name in {"v1", "v3", "v4"}:
        leaked = sorted(
            value
            for entry in steps
            for role, value in entry["roles"].items()
            if role not in EVENT_ID_ROLES
            and role != "NETWORK_STATE"
            and isinstance(value, str)
            and value.startswith("PUBLIC_")
        )
        if leaked:
            problems.append(f"public token still present in role values: {leaked}")
    return problems


def main() -> int:
    source = load_base()
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    try:
        import jsonschema
    except ImportError:
        print("jsonschema is required; install the candidate kit requirements", file=sys.stderr)
        return 2

    variants = {
        "v1-role-token-permutation": ("v1", build_v1(copy.deepcopy(source))),
        "v2-entity-goal-swap": ("v2", build_v2(copy.deepcopy(source))),
        "v3-order-repeat-reset": ("v3", build_v3(copy.deepcopy(source))),
        "v4-extended-26-step": ("v4", build_v4(copy.deepcopy(source))),
    }

    failed = False
    for directory, (name, document) in variants.items():
        problems = check_invariants(name, document)
        try:
            jsonschema.validate(document, schema)
        except jsonschema.ValidationError as error:
            problems.append(f"schema: {error.message} at {list(error.absolute_path)}")
        if problems:
            failed = True
            print(f"{name}: FAILED")
            for problem in problems:
                print(f"    {problem}")
            continue
        target = OUT / directory
        target.mkdir(parents=True, exist_ok=True)
        path = target / "PUBLIC_INPUT.json"
        path.write_text(
            json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"{name}: {len(document['steps'])} steps -> {path.relative_to(ROOT)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

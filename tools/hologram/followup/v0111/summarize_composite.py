"""Compare exact shared-seed/native-addon cases; unfinished endpoints never become zero-duration successes."""
import argparse
import json
from pathlib import Path

import run_composite as native


ENDPOINTS = tuple(f"{kind}.{stage}" for kind in ("self", "share")
                  for stage in ("importNanos", "processingNanos", "saveNanos", "privateLoadNanos", "placementNanos",
                                "privatePlacementWorkflowNanos", "constructionConfirmToCompleteNanos",
                                "longFlowProductCompleteNanos", "longFlowVerifiedNanos")) + (
    "raw.cutNanos", "raw.previewHydrationNanos", "raw.previewRenderNanos", "raw.previewNanos",
    "raw.pasteNanos", "raw.longFlowProductCompleteNanos", "raw.longFlowVerifiedNanos")


def summarize(case):
    receipt = json.loads((case / "process-receipt.json").read_text(encoding="utf-8"))
    client = native.properties(case / "composite-client.properties")
    server = native.properties(case / "composite-server.properties")
    timing = native.properties(case / "composite-client-timing.properties")
    result = {
        "case": case.name, "status": receipt["status"], "failure": receipt.get("failure"),
        "timing_scope": {"placementNanos": "historical alias: private-file upload through construction completion; not construction-only",
                         "constructionConfirmToCompleteNanos": "actual confirmation click55 through native job3 completion; excludes private upload and review"},
        "failure_kind": receipt.get("failure_kind"),
        "scenario": receipt.get("scenario", "all"),
        "failure_phase": client.get("failurePhase"), "product_sha256": receipt["normal_sha256"],
        "addon_sha256": receipt["addon_sha256"], "seed_manifest_sha256": receipt.get("seed_manifest_sha256"),
        "seed_receipt_sha256": receipt.get("seed_receipt_sha256"),
        "actual_seed_actor_nbt_sha256": receipt.get("actual_seed_actor_nbt_sha256"),
        "native_seed_playerdata_sha256": receipt.get("native_seed_playerdata_sha256"),
        "server_library_config_sha256": receipt.get("before_files", {}).get("server-library-config", {}).get("sha256"),
        "dependency_hashes": receipt.get("dependency_hashes"),
        "native_environment": {**{key: receipt.get(key) for key in
                               ("allow_nether", "allow_flight", "view_distance", "testlab_force_lease_ceiling",
                                "client_Xmx", "server_Xmx")},
                               "pause_on_lost_focus_applied": client.get("pauseOnLostFocusApplied"),
                               "lighting_profile": client.get("lightingProfile", "legacy-dark-v1"),
                               "server_lighting_profile": server.get("lightingProfile", "legacy-dark-v1"),
                               "native_eu_ledger_enabled": receipt.get("native_eu_ledger_profile", {}).get("enabled", False),
                               "cut_disk_diagnostics_enabled": receipt.get("cut_disk_diagnostics", {}).get("enabled", False),
                               "render_state_witness_enabled": receipt.get("render_state_witness_profile", {}).get("enabled", False)},
        "seed_files": receipt.get("seed_files"),
        "fixture_static_source_sha256": server.get("fixtureStaticSourceSha256"),
        "source_volume": server.get("actualSourceVolume"),
        "expected_blueprint_cell_count": server.get("expectedBlueprintCells"),
        "actual_blueprint_cell_count": client.get("actualBlueprintCellCount"),
        "raw_cell_count": client.get("rawCellCount"),
        "endpoints": {}, "cleanup": {key: receipt.get(key) for key in
                                       ("processClosed", "addonsCleanup", "client_library_restored", "cleanup_errors")},
    }
    for endpoint in ENDPOINTS:
        value = timing.get(endpoint)
        result["endpoints"][endpoint] = {
            "status": "COMPLETED" if value is not None else "NOT_REACHED",
            "seconds": int(value) / 1e9 if value is not None else None,
        }
    native.save(case / "composite-summary.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = [summarize(native.within(case, native.OUTPUTS, exists=True)) for case in args.cases]
    shared = {}
    for key in ("addon_sha256", "seed_manifest_sha256", "seed_receipt_sha256", "actual_seed_actor_nbt_sha256", "native_seed_playerdata_sha256", "fixture_static_source_sha256",
                "server_library_config_sha256", "source_volume", "expected_blueprint_cell_count"):
        values = [row[key] for row in cases]
        shared[key] = bool(values and all(values) and len(set(values)) == 1)
    shared["seed_files"] = bool(cases and all(row["seed_files"] == cases[0]["seed_files"] for row in cases))
    shared["dependency_hashes"] = bool(cases and all(row["dependency_hashes"] and
                                                    row["dependency_hashes"] == cases[0]["dependency_hashes"]
                                                    for row in cases))
    shared["native_environment"] = bool(cases and all(row["native_environment"]["allow_nether"] is True and
                                                     row["native_environment"]["pause_on_lost_focus_applied"] == "false" and
                                                     row["native_environment"] == cases[0]["native_environment"]
                                                     for row in cases))
    shared["no_cut_disk_diagnostic_overhead"] = bool(cases and all(
        not row["native_environment"]["cut_disk_diagnostics_enabled"] for row in cases))
    shared["no_preview_gl_readback_overhead"] = bool(cases and all(
        not row["native_environment"]["render_state_witness_enabled"] for row in cases))
    shared["lighting_profile_agrees_on_both_sides"] = bool(cases and all(
        row["native_environment"]["lighting_profile"] == row["native_environment"]["server_lighting_profile"]
        for row in cases))
    comparable = all(shared.values())
    comparisons = []
    baselines = {}
    for candidate in cases:
        if candidate["scenario"] not in baselines:
            baselines[candidate["scenario"]] = candidate
            continue
        baseline = baselines[candidate["scenario"]]
        row = {"baseline": baseline["case"], "candidate": candidate["case"], "comparable": comparable, "ratios": {}}
        for endpoint in ENDPOINTS:
            before, after = baseline["endpoints"][endpoint], candidate["endpoints"][endpoint]
            if (comparable and baseline["status"] == candidate["status"] == "PASS"
                    and before["status"] == after["status"] == "COMPLETED" and after["seconds"] > 0):
                row["ratios"][endpoint] = before["seconds"] / after["seconds"]
            else:
                row["ratios"][endpoint] = None
        comparisons.append(row)
    native.save(args.output, {"scope": "one actual sample per case; product completion and independently verified completion are separate endpoints; missing/failed endpoints are not speedups", "shared_inputs": shared, "comparable": comparable, "cases": cases, "comparisons": comparisons})
    print(json.dumps({"comparable": comparable, "shared_inputs": shared, "output": str(args.output)}, indent=2))


if __name__ == "__main__":
    main()

"""Report the one-round large-capture interaction case; preserve actual status and endpoint kinds."""
import argparse
import csv
import json
from pathlib import Path

import run_loaded_latency as native


def summarize(case):
    case = native.within(case, native.OUTPUTS, exists=True)
    receipt = json.loads((case / "process-receipt.json").read_text(encoding="utf-8"))
    client = native.properties(case / "latency-client.properties")
    server = native.properties(case / "latency-server.properties")
    metrics = native.properties(case / "client-latency.properties")
    actions = []
    if (case / "client-requests.tsv").is_file():
        with (case / "client-requests.tsv").open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream, delimiter="\t"):
                if int(row["round"]) != 8:
                    continue
                prefix = "request." + row["number"] + "."
                actions.append({"action": row["action"], "completion_kind": row["completionKind"],
                                "completion_ms": int(row["elapsedNanos"]) / 1e6,
                                "local_ms": int(metrics[prefix + "localElapsedNanos"]) / 1e6
                                if prefix + "localElapsedNanos" in metrics else None,
                                "authority_ms": int(metrics[prefix + "authorityElapsedNanos"]) / 1e6
                                if prefix + "authorityElapsedNanos" in metrics else None,
                                "cancel_authority_settled_ms": int(metrics[prefix + "cancelAuthoritySettledElapsedNanos"]) / 1e6
                                if prefix + "cancelAuthoritySettledElapsedNanos" in metrics else None,
                                "codec_deltas": {key.removeprefix(prefix + "codec."): int(value)
                                                 for key, value in metrics.items() if key.startswith(prefix + "codec.")},
                                "actual_envelopes": {key.removeprefix(prefix): value
                                                     for key, value in metrics.items() if key.startswith(prefix + "actualEnvelope")}})
    result = {"case": case.name, "status": receipt["status"], "failure": receipt.get("failure"),
              "product_sha256": receipt["normal_sha256"], "addon_sha256": receipt["addon_sha256"],
              "cells": receipt["requested_source_cells"], "loaded_rounds": 1,
              "import_prefix_ms": int(client["importElapsedNanos"]) / 1e6 if "importElapsedNanos" in client else None,
              "loaded_actions": actions,
              "client_capture_retained": client.get("loadedClientCaptureRetained"),
              "server_capture_cells": server.get("loadedServerCaptureCells"),
              "settings_natural_render_generation": client.get("settingsNaturalRenderGeneration"),
              "prompt_server_confirmed": server.get("loadedServerPromptConfirmed"),
              "prompt_revision_before": client.get("promptBaselineRevision"),
              "prompt_revision_after": client.get("promptAuthoritativeRevision"),
              "prompt_actual_sequence": client.get("promptAuthoritativeUiSequence"),
              "scope": "One actual loaded-workspace round, not a distribution. Compare equal endpoint kinds only. Client-local completion is not server authority; fragment timestamps are causal transport witnesses, not decoded ACKs. Product-installed partial/cellDelta flags are recorded separately from raw receiver-envelope shapes. A 20x heavy-task claim requires comparable authoritative end-to-end measurements."}
    native.save(case / "loaded-latency-summary.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", nargs="+", type=Path)
    for case in parser.parse_args().cases:
        print(json.dumps(summarize(case), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

"""Summarize completed or incomplete real latency receipts; never upgrades case status."""
import argparse
import csv
import json
from pathlib import Path
import statistics

import run_latency as native


def summarize(path):
    path = native.within(path, native.OUTPUTS, exists=True)
    receipt = json.loads((path / "process-receipt.json").read_text(encoding="utf-8"))
    rows = []
    if (path / "client-requests.tsv").is_file():
        with (path / "client-requests.tsv").open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
    groups = {}
    kinds = {}
    for row in rows:
        # Round 7 contains import preparation, not a measured selection replicate.
        if not 2 <= int(row["round"]) < 7:
            continue
        groups.setdefault(row["action"], []).append(int(row["elapsedNanos"]) / 1e6)
        kinds.setdefault(row["action"], set()).add(row.get("completionKind", "installedAuthority"))
    actions = {key: {"n": len(values), "median_ms": statistics.median(values),
                     "min_ms": min(values), "max_ms": max(values), "samples_ms": values,
                     "completion_kinds": sorted(kinds[key])}
               for key, values in groups.items()}
    client = native.properties(path / "latency-client.properties")
    metrics = native.properties(path / "client-latency.properties")
    begin_local = [int(metrics["request." + row["number"] + ".localElapsedNanos"]) / 1e6
                   for row in rows if row["action"] == "beginAreaSelection"
                   and 2 <= int(row["round"]) < 7
                   and "request." + row["number"] + ".localElapsedNanos" in metrics]
    ticks = []
    if (path / "server-ticks.tsv").is_file():
        with (path / "server-ticks.tsv").open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream, delimiter="\t"):
                ticks.append(int(row["wallNanos"]) / 1e6)
    ticks.sort()
    result = {"case": path.name, "status": receipt["status"], "failure": receipt.get("failure"),
              "product_sha256": receipt["normal_sha256"], "addon_sha256": receipt["addon_sha256"],
              "phase": receipt["phase_mode"], "source_cells": receipt["requested_source_cells"],
              "selection": actions,
              "begin_local_response_ms": {"n": len(begin_local), "median": statistics.median(begin_local),
                                          "samples": begin_local} if begin_local else {"available": False},
              "endpoint_comparison_rule": "Compare installedAuthority only with installedAuthority, and clientLocal only with clientLocal. A clientLocal/old installedAuthority ratio is a change in response path, never an authoritative ACK or heavy-task speedup.",
              "import_ms": int(client["importElapsedNanos"]) / 1e6 if "importElapsedNanos" in client else None,
              "packet_deltas": {key: int(value) for key, value in metrics.items() if ".codec." in key},
              "server_tick_wall_ms": {"n": len(ticks), "median": statistics.median(ticks),
                                      "p95": ticks[min(len(ticks)-1, int(len(ticks)*.95))], "max": max(ticks)} if ticks else {},
              "limits": receipt.get("observed_product_limit_log_lines", []),
              "scope": "Request to observed installed authority. Codec deltas are individual client endpoints, not TCP. Tick wall includes fixture preparation; use server-ticks.tsv and stage transitions for phase windows. No PASS or speedup inferred for incomplete runs."}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", nargs="+", type=Path)
    args = parser.parse_args()
    for case in args.cases:
        result = summarize(case)
        target = native.within(case / "latency-summary.json", native.OUTPUTS)
        native.save(target, result)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

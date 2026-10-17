# Selected-scale native fixture contract (prepared, not executed)

The earlier32768 case was a mechanism probe. It does not cover the user's usual50000+ input. These choices generate exact, real source volumes through the same dedicated server capture and client hydration path; no synthetic state/ACK replaces a large input.

| Option | Actual source dimensions X×Y×Z | Source Y range | Exact source cells |
| --- | --- | --- | ---: |
| no scale option |16×16×16|80..95|4096|
| `--cells 50000` |50×20×50|80..99|50000|
| `--cells 100000` |50×40×50|80..119|100000|
| `--cells 1000000` |100×100×100|80..179|1000000|

`--cells` implies the large local-edit scenario. `--large-edit` alone selects50000. With neither flag, the4096 background lifecycle scenario stays unchanged. Example root-owned runs after building the current addon:

```
python tools/hologram/followup/v0110/run_native.py --name RC2-S50K01 --jar build/libs/<selected-RC2-jar>.jar --addon build/verification/render-v0110/<current-addon-build>/gtmb-v0110-background-save-probe.jar --cells 50000
python tools/hologram/followup/v0110/run_native.py --name RC2-S100K01 --jar build/libs/<selected-RC2-jar>.jar --addon build/verification/render-v0110/<current-addon-build>/gtmb-v0110-background-save-probe.jar --cells 100000
python tools/hologram/followup/v0110/run_native.py --name RC2-S1M01 --jar build/libs/<selected-RC2-jar>.jar --addon build/verification/render-v0110/<current-addon-build>/gtmb-v0110-background-save-probe.jar --cells 1000000
```

The scale fixture writes a real padded platform and real stone/wool/glass source blocks in bounded batches of256 per server END tick. It replaces the first cell with one real chest for the authoritative virtual rotation. Source coordinates are unique and every fixture block write is read back. Platform blocks and the extra chest replacement write are counted separately from the requested source-cell volume. Before filling the tower, the prepared platform/spawn at8,80,-40 keeps vanilla spawn fuzz outside the source if preparation outlasts client startup. After source completion the large-case actor is moved outside the source to z-5.5; client checks the real source chunk0,0 and actor chunk0,-1. Background actor/source behavior is unchanged.

Known production boundaries were read from current source, not inferred from the dimension cap:

- `HologramArea.begin`: bounding volume≤1000000, Y≥0 and below `world.getHeight()`, world-coordinate boundary checks. All listed dimensions meet this eligibility check.
- `HologramCapture.LIMIT`: `Integer.MAX_VALUE-8`. The one-million area volume check is not a general cell-storage limit.
- `HologramBlueprintSharing.MAX_BYTES` and `HologramBlueprintArtifact.MAX_JSON_BYTES`:8MiB JSON. Sharing/local saved-artifact support is a separate question from capturing/editing a large virtual preview.
- `HologramArea`: expanded device NBT≤64MiB across the source.
- `HologramSnapshotCodec`: raw serialized NBT≤128MiB; normal decode also uses512MiB NBT accounting. `HologramSnapshotAssembly.MAX_BYTES`: compressed snapshot≤128MiB, with512KiB fragments and a30-second assembly deadline. Full cell rows include substantial per-cell metadata, so legal volume can still exceed transport/raw-memory limits.

No limit is removed, raised, or bypassed. Scale mode deliberately does not Save; a successful large local-edit run would not establish8MiB artifact support for that volume. A million-cell volume passing `Area.begin` is only eligibility. Actual source generation, capture, full hydration, natural render, bounded delta, and remote-cache reuse must all finish before native PASS. An actual snapshot-budget exception or JVM memory failure is recorded as FAIL with the real log line and reached phase; it cannot become synthetic/component PASS. The runner detects explicit native byte-budget/OOM exceptions during large runs and stops its owned pair with normal cleanup instead of waiting for a later phase timeout. Other missing terminal evidence remains INCONCLUSIVE.

Client heap remains exactly4096M and server heap6G. Large50000/100000 cases have bounded1800-second addon totals and900-second import/baseline budgets; million has3600-second total and2400-second import/baseline budgets. Edit phase71 retains240 seconds. These are harness deadlines, not performance acceptance criteria. Default background budgets remain unchanged.

Receipts separate actual input and observations:

- `background-server.properties`: requested dimensions/count, actual unique source blocks; `background-client.properties`: exact captured cells. Runner requires all requested/source/capture counts to match, preventing accidental smaller-addon substitution.
- `client-phase-timings.properties`: driver transition durations and current elapsed time, measured on the client clock.
- `server-tick-and-stage-metrics.properties`: FML START at HIGHEST to this END at LOWEST; elapsed tick samples, exact observed maximum/sum/count, and histogram p50/p95/p99 upper bounds. It includes main tick and preceding event listeners; this observer's later receipt-write work and scheduled inter-tick sleep are outside that interval. It is not an exact TPS measurement. Stage wall times are observed END-boundary transitions with last observed done/total counters, not detached worker CPU times.
- `area-{client,server}-wait-NNN.{nbt,properties}`: existing five-second phase5 diagnostics, including actual metadata/capture/owner/progress/task/transport state. `area-import-wait-render-end.png` is one actual native framebuffer.
- `large-local-edit-native.nbt`: actual delivered DTOs, revisions, dirty row parses≤2, dirty/rebuilt preview regions≤8, retained remote cell/region/preview object identities. Baseline denominators accompany retained-partition counts. These are real cache-reuse observations, not per-lookup cache hit rates. Product cache lookup counters remain explicitly unavailable until their real interface is available.
- `large-baseline.png` and `large-local-edit-result.png`: natural RenderTick.END frames, separate from component counters.

The independent addon also writes `metrics-client-NNN-*.nbt` and `metrics-server-NNN-*.nbt` from the public `HologramRuntimeMetrics` API. Each process saves its original enabled flag at its first main-thread END tick, temporarily enables collection, and restores the original flag in terminal cleanup, including a `finally` fallback on failure. Counters are never reset. No disk write is added to ordinary per-tick polling: client samples occur at driver transitions, before/after import, before/after the large edit or each actual Save, and at termination; server samples occur at first tick, observed work-stage changes (capped at96), and termination. Missing checkpoints after forced termination or a reached-stage failure remain missing, and runner forced cleanup needs no enabled flag across JVMs.

Every small detached NBT snapshot retains the actual `network`, `activity`, and `queues` trees, enabled state and production sample time; addon metadata adds checkpoint, side, case, phase, observed session/task token, observer before/after nanoseconds, wall milliseconds and driver elapsed nanoseconds. A zero token is explicitly unavailable/not yet issued; server task identity is last-polled client evidence and can lag. Independent server/client clocks and boundary times must not be treated as synchronized. Root should subtract per-side cumulative packet/byte and activity call/total-time counters across the relevant checkpoints. `maxNanos`, depth gauges and high-water values are absolute observations, not subtractable work totals. Concurrent atomic fields are read separately, so a snapshot is not a transaction. Network rows cover action/state/fragment/libraryRequest/libraryReply/librarySnapshot/cutAction/cutFragment, with `c2sTx`, `c2sRx`, `s2cTx`, `s2cRx` packet and byte counters. These are actual codec bytes excluding Forge/TCP framing; TX/RX endpoints must not be added as distinct end-to-end traffic. Snapshot-build/compress, server-actions, workspace-tick and transport-tick activity calls/total/max times are scoped to the existing production hooks, not complete UI/render wall cost. The addon does not add product instrumentation or fabricate a counter for paths without a real hook.

Million-cell component tests can assess geometry arithmetic, budget accounting, sparse-history and bounded dirty-row algorithms without proving a million-cell game run. Only actual completed native receipts at the requested volume can establish that native scope. No million-cell native result exists from this preparation, and no scale case was executed by the addon author.

The phase5 receiver watch forwards every original packet unchanged. It can terminate FAIL only for an actual `transportError` carrying one of the production snapshot error codes, exact session and typed-long `uiSequence`, matched against the locally tracked area task token/current generation. The server contract has no separate generation field, so none is invented. The exact error packet and local identity are preserved in `area-owned-transport-failure.nbt`. Generic errors from other mods, stale/untagged transport errors, and `succeeded=false` are not failure predicates. The production typed FAILURE-versus-CANCELLED owner interface is still pending at the safe-pause checkpoint; this addon does not guess that distinction. With no matched explicit error the original strict success conditions remain in force.

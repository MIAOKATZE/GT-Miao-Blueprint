# Real loaded-workspace interaction (04; prepared, root runs)

This is a separate case from common03's empty-workspace selection samples. `V0110LatencyProbe.java` common03 remains byte-identical at SHA-256 `5741681746a1a2b54f341755778b958e89fb65b56b8a586fdda59845c1821fd7`. No prior active case, output, runner, product class, recovery jar, native process or Git operation is changed by this preparation.

`V0110LoadedLatencyProbe.java` imports a real50k ordinary decorative-block source using exactly the same fixture/palette/material settings as03; it does not rerun03's two warm/five empty selection rounds. After complete authoritative hydration, it preserves that same large session/capture and measures one round of:

1. Begin selection.
2. First corner.
3. Second corner.
4. Undo the last region.
5. Cancel selection, retaining the imported draft.
6. Open settings until the actual current workstation has naturally rendered.
7. Edit the real selected cell-zero prompt through the actual current UI session command, and wait for authoritative installation.

The same04 addon must be used on old/new. Old versions without local APIs keep normal product Actions with real incoming transport and installed authority. In the new branch, begin/points/undo/cancel invoke production local APIs and read the production local model; these completion samples are explicitly `clientLocal`, with no fabricated incoming envelope or ACK. The new begin endpoint does not require that `HologramClient.state.areaSelecting` has already changed. The final import uses the real one-shot finish command and waits for real full installed50k authority. It may exercise the product's final-intent wait if registration is still pending; that boundary is not asserted as an independently completed coverage item.

The client checks same session/exact50k installed cell count after each loaded action; the server checks its real active capture count every observed loaded-stage tick. Cancellation records local response separately and waits for actual server cleanup before proceeding. No capture is cleared to make interaction cheaper.

`openSettings` calls `HologramClient.openSettings()`. The completion is observed only from real RenderTick.END, using `actualGui.ui.canvas.renderGeneration()`>0 and `actualGui.ui.session.state == HologramClient.state`, same large session/cell count. It calls no smokeRenderGeneration or legacy smoke hook and enables no unrelated driver. No OS input is used.

Prompt input uses the actual current `ui.preview.selection().click(0,false)` and `preview.selected(0)` to set only the real UI selection, then calls `ui.session.send("editCellFlags", {indices:[0],prompt:unique}, true)`. The command goes through the normal production request builder/network/server operation. There is no pin, NBT edit or synthetic product state. Completion requires a real post-request receiver witness, matching installed cell-zero prompt, same large session/cell count and an installed uiSequence at least the actual UI send sequence. The server also verifies its actual capture cell-zero prompt before natural completion. Prompt edits may leave planRevision unchanged; before/after revisions are factual observations, not forced predicates.

Raw receiver-envelope shape summaries distinguish fragments, progress, partial DTOs and cellDelta DTOs when exposed by the actual receiver. Fragmented full snapshots remain fragments in that evidence, not invented decoded replies. Product-installed partial/cellDelta flags are separate fields. Codec snapshots/deltas and server tick/observed-stage timing retain03's scope limitations.

Build using root's Java8 executable and existing FML compile jar into a fresh directory:

```powershell
python tools/hologram/followup/v0110/build_addon.py --javac <root-selected-javac> --fml-compile-jar build/rfg/recompiled_minecraft-1.7.10.jar --source tools/hologram/followup/v0110/V0110LoadedLatencyProbe.java --jar-name gtmb-v0110-loaded-latency-probe.jar --output build/verification/render-v0110/loaded-latency-addon-01
```

Run old/new sequentially with fresh case names and the same addon. The independent runner inherits the strict fixture lifecycle and the reviewed atomic receipt reader; port25678, client4096M/server6G and explicit productSHA binding remain mandatory:

```powershell
python tools/hologram/followup/v0110/run_loaded_latency.py --name LOAD019-50k-01 --jar build/libs/gtmb019.jar --expect-product-sha256 c2bfebfc03fb4bdbe48d91f26ce8185ec67b9d21b8cc77166a6264ca65319aa8 --addon build/verification/render-v0110/loaded-latency-addon-01/gtmb-v0110-loaded-latency-probe.jar --cells 50000
```

The runner only accepts the import prefix phase and additionally requires loaded round completion, natural UI generation, retained client/server capture and authoritative server prompt confirmation. A negative, timeout or cleanup failure is not PASS. Each loaded operation has a300-second deadline, and import keeps the30-minute deadline; root may select the runner's existing bounded overall deadline. All artifacts stay in the workspace.

After natural completion or diagnosed failure:

```powershell
python tools/hologram/followup/v0110/summarize_loaded_latency.py build/verification/render-v0110/LOAD019-50k-01 build/verification/render-v0110/LOAD110-50k-01
```

One round is a first actual sample, not a latency distribution. Local response / old server-authoritative response must not be labeled a server-ACK or20x heavy-task speedup. Heavy import and authoritative prompt endpoints need like-for-like measurements. This ordinary-block baseline makes no claim about same-block/different-NBT isolation; that remains a separate fixture.

The runner sets the server's FML socket read timeout to300 seconds. Before issuing any measured command, the addon replaces only the client's real Netty idle-timeout handler with the same300-second handler on both versions. This benchmark-only timeout gives the slow baseline time to finish; product work, codecs, authority, capture and endpoint assertions are unchanged. The earlier default-timeout v0.1.9 disconnect remains a separate failure, and a completed benchmark does not establish that the old version works at its default timeout. Losing the connected test world is terminal FAIL rather than an idle RUNNING receipt.

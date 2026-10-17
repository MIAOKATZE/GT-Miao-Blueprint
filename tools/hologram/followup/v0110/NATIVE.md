# v0.1.10 packaged native stimulus (prepared, not executed)

`V0110BackgroundSaveProbe.java` is a separate FML addon. It replaces no product class and supplies no synthetic state, transfer, disk result, or Save ACK. Root owns all compilation, installation, game/server runs, OS input, and Git. This preparation has not run javac, Gradle, or Minecraft and is not a PASS receipt.

Build through the new `build_addon.py` using root's existing Java8 `--javac` and `--fml-compile-jar` (same FML-only compile contract as v019). Choose a fresh `--output` under workspace `build`, and keep the default `gtmb-v0110-background-save-probe.jar`. Install the same resulting addon on the two existing owned `build/hologram-packaged-v018-mui/{client,server}` directories. Use actual packaged RC2 GTMB on both sides; only root determines/version-binds that artifact.

Root can use the standalone paired runner instead of manually installing and launching:

```
python tools/hologram/followup/v0110/run_native.py --name RC2-BG01 --jar build/libs/<selected-RC2-jar>.jar --addon build/verification/render-v0110/addon-01/gtmb-v0110-background-save-probe.jar
```

For the separate large case choose a fresh name and append `--large-edit`. The runner requires all workspace-owned Java processes stopped and port25678 free before staging. It generates its own v0110 argument files, checks the single4096M client heap and6G server heap, backs up complete server properties/library configuration/client UI preferences, and restores their original bytes or absence even after a forced stop. Existing GTMB JARs move into the case's `staging-original-{role}` directories and return after both JVMs close; this run's installed JARs are archived under `staged-used-{role}`. The v016 probe is preserved with its flag false. The observer's fixed workstation screenshot is also captured into the case and its previous file/absence restored. Logs, commands, byte backups, hashes, cleanup records, and `process-receipt.json` remain under the case directory. PASS requires natural zero exits, clientPASS/preferencesRestored, serverCOMPLETE/configRestored/clientVerdict, and successful cleanup; preflight refusal occurs before fixture mutation. This runner invokes no OS input helper and leaves the fresh world in the owned server directory. Generated library pairs still require the isolation described below before later CUT fixtures.

For every run use a fresh server world with prefix `hologram-v0110-`, a fresh precreated evidence directory under `build/verification/render-v0110`, and a unique evidence basename of 1–20 ASCII letters/digits/underscore/hyphen. This basename becomes part of saved blueprint names and screenshots. Both sides need:

```
-Dgtmb.hologram.followupRoot=E:/CodeGT/GT-Miao-Blueprint/build/hologram-packaged-v018-mui
-Dgtmb.v0110.output=E:/CodeGT/GT-Miao-Blueprint/build/verification/render-v0110/RC2-BG01
```

Server adds `-Dgtmb.v0110.backgroundServer=true`. Client adds:

```
-Xmx4096M
-Dgtmb.v0110.backgroundClient=true
-Dgtmb.v0110.address=127.0.0.1:<actual-owned-port>
-Dgtmb.hologram.backgroundProbe=true
-Dgtmb.hologram.previewCaptureWitness=true
-Dgtmb.hologram.backgroundProbeDir=E:/CodeGT/GT-Miao-Blueprint/build/verification/render-v0110/RC2-BG01
```

Disable every other optional client driver, including v019 background/download/CTM and the native CUT driver. The fixture's actual loopback connection must be a dedicated Netty socket. The addon checks actual loaded source and actor chunks before submitting the normal area commands. The server builds 4096 source blocks including a real chest, delivers a projector, and changes download bandwidth through the actual library admin console command to1 KiB/s; all original limits are restored on terminal cleanup. This slower link makes UI-close background ownership observable; it is not a throughput benchmark.

The background run checks production CUT entry then the production GuiOpenEvent Escape handler. This is a bounded handler assertion, not OS keyboard evidence. Existing production CUT native flow remains responsible for comprehensive keyboard/cache/late-ACK coverage.

The actual source import then renders146 and134. A production client selection model supplies cell0 and nondefault yaw37.25/pitch-17.5; the buttons themselves use MUI2 input dispatch.146 must open selected-cell scope1.134 must open its actual reset review, and136 dismisses it. This proves the restored control and review, not a destructive reset commit. Natural screenshots are `selected-cell-prompt.png` and `reset-default-review.png` with matching NBT.

The run makes all six real processing choices, types a unique Save name through the native MUI name field, and clicks actual Save140. Optional AE review is confirmed through181. The production `HologramBackgroundNativeProbe` exclusively controls the two background reopen cycles; the addon waits for its completed, passing, actual-IO receipt with the exact task token before resuming. The production observer checks fresh screen/panel allocation, restored page/scroll/selection/camera, no replacement business request, retained placement nonce, background completion, genuine local JSON/PNG, owner ACK and server commit.

After the real commit, the addon captures the actual guidance child panel, clicks its `知道了` button through MUI input, verifies that suppression was not persisted and cyan148 remains, and captures the restored workspace. It then performs a SECOND actual Save140 with another unique name and fresh task token. Its actual ownerDiskAck/saveCommitted and local JSON/PNG pair must exist before the second guidance capture and actual `不再显示` click. The persisted key must be read by a fresh production preference instance, and cyan148 must remain. No dialog model is reopened manually and no committed notification is fabricated.

Before interaction, the addon records the complete original `config/gtmb-hologram-ui.properties` bytes as `ui-preferences-original.bin`, removes only temporary suppression, and clears the product's cached preference object. On PASS/FAIL it first closes the real screen normally, then restores the original complete file (or original absence) and clears that cache. `preferencesRestored=true` is required. Forced JVM termination cannot execute this cleanup: root must use the saved bytes and `originalPreferencesExisted` receipt to restore the exact file/absence before any later run.

Each evidence screenshot comes from actual RenderTick.END, with the exact currently displayed screen and positive natural render generation. Matching NBT records actual drawn controls, exact framebuffer color totals, per-control pixel bounds, four edge color counts, and interior text color counts. Saved-workspace screenshots require actual cyan148 text and all four cyan frame edges. `semantic-component.nbt` separately tests all four production text/border methods and2px width. Those component assertions are not a claim of four-color visual coverage; root should inspect the actual screenshots and per-control records to establish which colors were rendered. No OS screenshot/input claim is made.

Both `background-client.properties status=PASS` and the production observer's exact task-bound `background-save-native.nbt` are needed for background success. Server must report `status=COMPLETE`, `configRestored=true`, and `clientVerdict=PASS`. The first generated pair ID is in the observer `localId`; the second is `secondSaveLocalId` in client properties. Root must isolate BOTH generated JSON/PNG pairs together before later library-count fixtures. Unique case names do not remove the resulting library rows.

## Actual large virtual local-edit mode

Run a separate fresh paired case with `-Dgtmb.v0110.largeEdit=true` on BOTH sides, e.g. output basename `RC2-LARGE01`. Keep client `-Xmx4096M`, but do not enable `gtmb.hologram.backgroundProbe` or any other driver. This mode keeps normal download limits and does not save library pairs.

The same normal area-command path captures32768 actual world blocks (32³). After initial full hydration and natural rendering across at least8 native 16³ regions, it sends cell0 `rotateCell` using production `UiPreview.dispatchEdit -> UiSession.send -> HologramNetwork.Action` over the actual dedicated Netty socket. Cell0 must be the source chest. A read-only receiver decorator copies the actual incoming DTO for evidence and forwards the exact original packet to the original receiver; progress-only messages pass through without geometry assertions. Cleanup reinstates the receiver.

The bounded final predicates require a real authoritative revision increase, planning completed, a changed target cell, the remote cell object retained, all but the affected cell-region list retained, and native preview partition reuse after the actual delta. Every post-baseline geometry reply must be `partial/cellDelta`, carry the actual session generation and full32768 cell count, and contain at most2 rows (dirty plus prior authoritative selection). No post-edit full DTO is accepted. Observed installed state parses must remain ≤2 and observed dirty/rebuilt preview region counts ≤8. Actual packet copies plus reuse counts and revisions are stored in `large-local-edit-native.nbt`; natural screenshots are `large-baseline.png` and `large-local-edit-result.png` with matching diagnostic NBT. The eight-region ceiling includes diagonal neighbor regions at a partition corner; this scenario does not itself prove native CTM texture dependency behavior. The server independently checks that the original real chest metadata is still2, proving virtual rotation did not change that source world block.

This mode tests actual Netty delivery, state identity reuse, and actual rendered preview cache reuse. It does not claim FPS/TPS, all edits/modules, world display-list reuse, benchmarked world-read bounds, or production Save. The server sparse-history/world-read unit regressions remain component tests and must be reported separately.

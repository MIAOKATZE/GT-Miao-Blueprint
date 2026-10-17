# Real background Save stimulus

`V019BackgroundSaveProbe.java` is a disposable FML addon containing only FML links and reflective production/SRG calls. It replaces no product class. Build separately with existing `build_addon.py --source tools/hologram/followup/v019/V019BackgroundSaveProbe.java --jar-name gtmb-v019-background-save-probe.jar`, a fresh `--output`, and the normal `--javac` argument. This preparation did not compile or run Java.

Use only the existing owned `build/hologram-packaged-v018-mui` client/server directories and a fresh server world named `hologram-cut-v020-*`. Install the same addon on both sides. Both processes need:

```
-Dgtmb.hologram.followupRoot=E:/CodeGT/GT-Miao-Blueprint/build/hologram-packaged-v018-mui
-Dgtmb.v019.output=E:/CodeGT/GT-Miao-Blueprint/build/verification/render-v019/background
```

Precreate that fresh evidence directory. Server adds `-Dgtmb.v019.backgroundServer=true`. Client adds:

```
-Xmx4096M
-Dgtmb.v019.backgroundClient=true
-Dgtmb.v019.address=127.0.0.1:<actual-owned-port>
-Dgtmb.hologram.backgroundProbe=true
-Dgtmb.hologram.previewCaptureWitness=true
-Dgtmb.hologram.backgroundProbeDir=E:/CodeGT/GT-Miao-Blueprint/build/verification/render-v019/background
```

Disable other opt-in client drivers. No old sulfur, workbench, CTM, or download driver is needed.

The server places 4096 real stone/wool/stained-glass blocks, including one real chest, at `[0,80,0]..[15,95,15]` and provides the connected actor a projector. It sets the owned world spawn to the nearby platform and updates the normal PlayerManager watches after teleport. The client checks actual non-EmptyChunk source/actor regions and the real source chest before sending the production begin-area / two-point / finish packets over its dedicated Netty socket and waiting for actual server capture. It then uses the actual rendered MUI controls for all six explicit processing stages, native name-field mouse/keyboard input, and Save 140. If production presents an AE review, it confirms the actual review through 181; a source without AE follows the normal direct-save branch. No synthetic snapshot, preset DTO, preview PNG, save ACK, or IO result is supplied to the product. Area gestures are production command entry calls, not OS/Forge mouse claims.

To allow the small compressed real draft transfer to span native frames, the server invokes the normal level-4 console `HologramLibraryCommand` to set download bandwidth to 1 KiB/s. Original upload/download/quota are recorded. Normal completion restores all three through the same command before stopping the server; server exception cleanup restores them through the same production backend config path. This is a UI background ownership scenario, not a 128 KiB/s throughput benchmark. Check `background-server.properties` for `configRestored=true`. Root timeout/forced process termination remains responsible for restoring the recorded values if the JVM cannot execute cleanup.

After optional AE 181 or observing the real `downloadBeginReply` matching the task token captured after Save 140, the client driver relinquishes all UI control. It waits for the production observer's `background-save-native.nbt`, requires its exact task token and completed/pass/actual-IO fields, then writes `background-client.properties` PASS and closes Minecraft normally. The server observes that terminal receipt, restores config, records COMPLETE, and shuts down normally. Observer failure, peer failure, and bounded phase/overall timeouts produce FAIL receipts and controlled shutdown. The observer receipt is the authoritative full-save/background result; addon property files describe stimulus and lifecycle only.

While phase 10 waits for either optional AE review or the actual direct-save transfer, it writes one `save-wait-NNN.nbt` every five seconds. These contain actual UI, task busy/token/label, library status and full production `actualSaveState`/`actualSaveWitness` diagnostics, including the transfer header, terminal outcome, and error/status fields. Only source state scalars are copied, avoiding repeated copies of all 4096 cells. Matching summary fields are published in client properties. The first wait also queues one actual `RenderTick.END` framebuffer screenshot as `save-wait-render-end.png`; this diagnostic screenshot does not create or refresh a GUI and is distinct from the product's native blueprint preview PNG.

Addon-owned properties polling treats a `FileNotFoundException` during a Windows replace/open race as an empty pending observation until the next tick. Other read failures still fail the probe, and final predicates and phase/overall deadlines are unchanged. Receipt writes retain the bounded ten-attempt `FileSystemException` rename retry (10 ms between attempts).

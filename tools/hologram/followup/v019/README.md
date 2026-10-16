# Native Chisel CTM regression witness

Test-only addon, compatible with the existing normal v0.1.8 JAR. It uses FML events and production SRG reflection; it replaces no product classes and needs no new installation hook. Root owns building, fixture staging, and process launches.

Build with the same JDK and FML compilation JAR used by the v016 addon:

```powershell
python -B tools/hologram/followup/v019/build_addon.py --javac '<JDK>/bin/javac.exe' --output build/verification/render-v019/build
```

Place the resulting addon beside the selected normal GTMB JAR on both sides of the **existing** `build/hologram-packaged-v018-mui` fixture. Use a fresh precreated receipt directory for each old/new normal run. Set these JVM properties on both processes:

```text
-Dgtmb.hologram.followupRoot=E:/CodeGT/GT-Miao-Blueprint/build/hologram-packaged-v018-mui
-Dgtmb.v019.output=E:/CodeGT/GT-Miao-Blueprint/build/verification/render-v019/<run>/receipts
-Dgtmb.hologram.cutNativeClient=false
-Dgtmb.hologram.cutNativeServer=false
-Dgtmb.v016.productionGui=false
```

Client additionally needs `-Dgtmb.v019.ctmClient=true`, `-Dgtmb.v019.expect=negative` for the old JAR or `positive` for the fixed JAR, and optionally `-Dgtmb.v019.address=127.0.0.1:25678`. Keep the existing client `-Xmx4096M`. Server needs `-Dgtmb.v019.ctmServer=true`. The dedicated server must remain at the loopback address, with a fresh `level-name` beginning `hologram-cut-v020-`. The addon rejects any process working directory except the exact fixture side and checks the named world before placing blocks. It never reads user worlds or blueprints.

The fixture consists of real server Chisel CTM blocks, vanilla stone, a platform, and one real player equipped with the projector. The diagnostic preview entry is deliberately constructed from actual registry blocks (CTM marble, slab, glass, brick stairs); **it is not claimed to be a captured user CUT**. Both the actual world raw renderer and the actual MUI storage screen/browser renderer run. Screen opening and closing are direct calls, not physical keyboard or mouse coverage.

The sequence records the CTM ID, selected block render type, original registry mapping, and renderer object identity before construction, after world/GUI previews, after release, after real terrain rebuild, and after disconnect/reconnect in the same Java process. A server-side stone stripe replacing CTM and then restoring it must visibly alter the terrain ROI; this is independent evidence that real received chunks were rebuilt. Baseline and post-preview/reconnect terrain PNGs are stored alongside the properties. The Celeritas world binding and chunk debug state are additional diagnostics.

`ctm-client.properties` exposes independent `renderIdOrRegistryIdentityDrift`, `terrainPixelsMatchBaseline`, `actualSectionRebuildWitness`, and `sameJvmReconnect` results. An expected old-JAR failure is `scenarioVerdict=NEGATIVE_REPRODUCED`, **productVerdict=FAIL**, and `status=FAIL`; it is never product PASS. A complete unchanged positive run is `POSITIVE_VERIFIED` / `PASS`. An exception or missing observation records `productVerdict=INCONCLUSIVE`. Root should not classify a run using only the status field.

The server watches the final client receipt and shuts itself down after completion. The client exits after its completed result. Root's launcher should retain timeout cleanup and process/normal-JAR/addon hashes. Running this addon does not cover the old 482-step input suite, full GT/AE/FMP native coverage, or arbitrary resource-pack equivalence.

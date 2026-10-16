# Native blueprint download comparison

`V019BlueprintDownloadProbe.java` is a disposable FML addon compatible with the normal v0.1.8 and v0.1.9 JARs. It replaces no product classes. Root owns compilation, fixture staging, and both game processes.

Compile with the shared `build_addon.py --source tools/hologram/followup/v019/V019BlueprintDownloadProbe.java --jar-name gtmb-v019-download-probe.jar` options and a fresh output directory. Use the identical compiled addon on both sides of both comparison runs.

Both processes require the existing owned fixture and a fresh, precreated receipt directory:

```text
-Dgtmb.hologram.followupRoot=E:/CodeGT/GT-Miao-Blueprint/build/hologram-packaged-v018-mui
-Dgtmb.v019.output=E:/CodeGT/GT-Miao-Blueprint/build/verification/download-v019/<run>/receipts
```

Client additionally needs `-Dgtmb.v019.downloadClient=true` and optionally `-Dgtmb.v019.address=127.0.0.1:25678`; server needs `-Dgtmb.v019.downloadServer=true`. Disable the other native/UI/socket drivers. In particular, **leave `gtmb.hologram.librarySocketSmoke` false**, since it starts an unrelated socket fixture. This addon observes the existing post-delivery `libraryProbeReceiver` field directly; the production library receiver, packet handlers, codecs, and Netty channel stay installed.

Use a new owned server world whose name starts with `hologram-cut-v020-`. Working directories must be exactly the existing fixture's client/server directories. The addon checks these paths before touching anything. Both sides shut down naturally after completion or a paired failure; root should retain process timeouts and normal/addon hashes.

The first, old-normal run constructs a 32 by 32 by 32 stone/glass/brick region in that disposable server world, 512 cells per tick. Each construction tick uses the production explicit chunk loader, which rejects shared EmptyChunk, coordinate mismatches and duplicate identities. Every cell reads the native block and metadata back and calls production `HologramPresets.cell`; the resulting DTO is serialized with production `encodeShare`. This is a constructed benchmark DTO derived from real world blocks, **not the full authoring/review workflow or a user blueprint**. The resulting `benchmark-source.json` and independent SHA are retained in the old run's receipts.

For the candidate run, pass **on both processes**:

```text
-Dgtmb.v019.downloadSource=<absolute old receipt directory>/benchmark-source.json
-Dgtmb.hologram.libraryDownloadTrace=true
```

The source must remain inside `build/verification/download-v019`. Candidate imports preserve the old metadata; production `encodeShare` must reproduce the entire old file byte for byte, or the benchmark fails. Upload/download configuration values must match across runs; the addon records them and never changes them.

A real authenticated player receives the projector and opens a real library session. `downloadDraft` calls the production `prepareDraft`, worker encoder, post-worker capability check, download queue, fair aggregate budget, chunk sender, FML serialization and real Netty receiver. The normal client ignores this diagnostic unsolicited draft; the addon observes the delivered packets, checks bounded ordered chunks, invokes the candidate production decoder (or the old identity format), checks the original SHA independently, validates through the production native JSON reader, writes through production `atomicWrite`, and compares the actual disk readback to the original source bytes.

Compare only results where both client/server receipts say PASS, source SHA and bytes match, and the actual bandwidth configuration is identical. Principal fields:

For a repeated comparison, use the same newly compiled addon and baseline source on both versions, with `-Dgtmb.v019.downloadRounds=3` and `-Dgtmb.v019.downloadWarmupSeconds=30` on both processes. Warmup starts after the real player is alive, its actual chunk and platform have arrived, and source/configuration are ready. Each round performs a fresh authorized transfer and writes a separate validated JSON file. Keep every `download-client-round-N.properties`, its JSON readback, and the aggregate median/min/max; retain earlier cold-start cases separately. The repeated test includes a five-second gap between rounds and needs a launcher timeout of at least 600 seconds including pack startup.

- `headerToLastByteNanos`: client receipt of first metadata through the final payload packet.
- `headerToVerifiedNanos`: metadata through worker decompression and final original SHA verification.
- `headerToVerifiedJsonDiskNanos`: metadata through production JSON validation, atomic JSON write and exact disk readback.
- `rawBytes`, `wirePayloadBytes`, `serializedLibraryBytesIncludingHeaders`, `chunkCount`, `rawSha256`, `verifiedJsonDiskSha256`.
- Candidate `server.transferNanos` and `server.budgetPolls` when the trace flag is enabled.

`wholeGzipRatio` is a size measurement, **not elapsed-time acceleration**. The user target is 50 times faster for the measured server-to-client download case; calculate that from the old/new elapsed times. Small or incompressible payloads may have much smaller gains. This benchmark does not measure PNG rendering, paired artifact commit, final owner ACK, or the total duration of a complete Save button action. Those require the separate UI/save acceptance run.

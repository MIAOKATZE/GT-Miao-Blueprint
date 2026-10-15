# v0.1.1 real quantum sulfur fixture

Only the root operator starts Gradle, Java or game processes. Prepare/export never start Minecraft. Keep `src/main`, `src/mixin`, the engine JAR and reviewed runtime JARs frozen from export until collection. A failed run remains failed; retry with a new run suffix and new log names. The `tooling-origin.json` records the SHA comparison against unchanged Sulfur02 tools before adapting this independent directory.

The source contains three native large chemical reactors, real automatic maintenance and ME hatches. Seven GTSWN nodes connect directly to the SOUTH fronts of the three fluid ME inputs, three fluid ME outputs and one sulfur ME bus. There is no production ME cable. Outside the captured footprint, the drive connects through an eighth GTSWN storage node to its owner's controller and finite AE cell. Each reactor uses the explicitly user-authorized native Debug energy hatch (GT ID 11304), configured through its public setters for LV, 1 amp and a 20-tick refill interval. Its native onFirstTick/onPreTick generates machine EU. The driver waits until all three actual hatches contain EU, then enables the reactors exactly once. The fixture never writes EU into a hatch and never supplies a reactor input directly. The external finite AE cell receives exactly 1,000,000 AE once. The WAP sits directly above that cell, connects DOWN, and carries four real native wireless boosters (24-block native range); no bank ME cable is placed. Source stored EU is omitted from both saved pairs; rebuilt native Debug hatches generate their own authorized test power. The failed finite-battery candidate remains unchanged in its separate failure archive.

Each operating run produces acid, physically removes all seven production nodes, waits at least 200 natural ticks to drain in-flight work, then requires another 200 consecutive ticks of unchanged remote stock, recipe/progress/output-cache readings, while the independent authorized native Debug power remains available. A native END screenshot witnesses the stopped state. The original native nodes are restored physically; ordinary GTSWN/AE ticks must reconnect and consume more remote sulfur/water while producing additional acid, with no second fixture supply. A live graph traversal which omits all seven production nodes must never reach the remote drive.

Four fresh worlds are required. Both source runs use the same offline author. Self uses the real `preserve` tendency default and confirms the default one-group classification. Its recipient, in another dimension/world, must receive the explicit unavailable original-world message and leave the entire footprint air without consuming construction stock. Share uses `rebind`, performs actual preview selection, T/Ctrl+T, Ctrl selection, incomplete-save gating and Ctrl+Z/Y, then saves the Chinese groups `前段供料` and `后段供料`. Its recipient must select a carried native terminal separately for both groups; the first selection cannot enable confirmation.

After the root's build/check, export the two current descriptors from the repository root:

```powershell
.\gradlew.bat --console=plain -I tools/hologram/followup/v011/export-launch.gradle -PfollowupProfile=sulfur -PhologramInteractionServer -PhologramExtendedCompatibility -PhologramEssentiaSmoke -PhologramCoordinateSmoke -PhologramSulfurLineSmoke exportFollowupLaunch
.\gradlew.bat --console=plain -I tools/hologram/followup/v011/export-launch.gradle -PfollowupProfile=sulfur -PhologramInteractionClient -PhologramExtendedCompatibility -PhologramEssentiaSmoke -PhologramCoordinateSmoke -PhologramSulfurLineSmoke exportFollowupLaunch
```

Prepare each source immediately before running it; both roles use the same run/port/intent/dimension:

```powershell
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-server-launch.json --run v011-sulfur-source-self-01 --port 25601 --phase source --intent self --dimension 0
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-client-launch.json --run v011-sulfur-source-self-01 --port 25601 --phase source --intent self --dimension 0 --username GTMBLineAuthor
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-server-launch.json --run v011-sulfur-source-share-01 --port 25603 --phase source --intent share --dimension 0
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-client-launch.json --run v011-sulfur-source-share-01 --port 25603 --phase source --intent share --dimension 0 --username GTMBLineAuthor
```

After each corresponding source server and client finish, prepare its recipient using the exact source transfer:

```powershell
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-server-launch.json --run v011-sulfur-target-self-01 --port 25602 --phase target --intent self --dimension 777 --source-fixture build/hologram-followup/v011-sulfur-source-self-01
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-client-launch.json --run v011-sulfur-target-self-01 --port 25602 --phase target --intent self --dimension 777 --source-fixture build/hologram-followup/v011-sulfur-source-self-01 --username GTMBLineSelf
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-server-launch.json --run v011-sulfur-target-share-01 --port 25604 --phase target --intent share --dimension 778 --source-fixture build/hologram-followup/v011-sulfur-source-share-01
python tools/hologram/followup/v011/prepare_run.py --launch plan/tmp/v0.1.1/sulfur-client-launch.json --run v011-sulfur-target-share-01 --port 25604 --phase target --intent share --dimension 778 --source-fixture build/hologram-followup/v011-sulfur-source-share-01 --username GTMBLineShare
```

Run **one paired world at a time**, in order source-self, target-self, source-share, target-share. For each run name substitute it below, start the server, then use a separate root tool session for the client. The runner checks current source/engine/runtime SHA values before starting Java and records the actual exit/log hash afterwards.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/hologram/followup/v011/run_launch.ps1 -Launch build/hologram-followup/v011-sulfur-source-self-01/server-launch.json -Log plan/tmp/v0.1.1/v011-sulfur-source-self-01-server.log
powershell -NoProfile -ExecutionPolicy Bypass -File tools/hologram/followup/v011/run_launch.ps1 -Launch build/hologram-followup/v011-sulfur-source-self-01/client-launch.json -Log plan/tmp/v0.1.1/v011-sulfur-source-self-01-client.log
```

Validate one finished candidate before continuing:

```powershell
python tools/hologram/followup/v011/collect_sulfur.py --label source-self --fixture build/hologram-followup/v011-sulfur-source-self-01 --server-log plan/tmp/v0.1.1/v011-sulfur-source-self-01-server.log --client-log plan/tmp/v0.1.1/v011-sulfur-source-self-01-client.log
```

For the full archive, a JSON file maps exactly `source-self`, `target-self`, `source-share`, `target-share` to objects with `fixture`, `server_log`, `client_log` paths. Use the actual candidate names, including retry suffixes:

```powershell
python tools/hologram/followup/v011/collect_sulfur.py --suite plan/tmp/v0.1.1/sulfur-four-runs.json --check-only
python tools/hologram/followup/v011/collect_sulfur.py --suite plan/tmp/v0.1.1/sulfur-four-runs.json --output plan/evidence/v0.1.1/sulfur-four-worlds-01
```

The collector checks real paired exits, all four source snapshots, current reviewed GTSWN 1.8.67 and AE2FC 1.5.113 SHA values, exact JSON/PNG transfers, actual native preview/save ACKs, typed END/UI hashes, graph/finite-stock/native-Debug-power/startup/counter witnesses and saved external storage nodes. Only after validation succeeds does it create four world ZIPs. A tooling check or historical receipt cannot produce an end-to-end PASS.

On any client failure marker or server exception, every server stage now stops through the same failure path. `sulfur-native-failure-diagnostic.nbt` records one ServerTick.END of the native reactors, Debug energy hatches, input/output/maintenance hatches, remote drive/power, stock readings and actual graph. It is failure evidence and cannot be collected as a successful candidate.

The reviewed GTSWN v1.8.67 dev JAR must have SHA256 `6f377dc7be30d7ce3df712762a4851a54e1c0e981a09148e27a166ebc4c223ac`. The exporter, preparer, runner and collector retain this exact runtime dependency digest. Historical v1.8.66 failures and user-interrupted partial evidence are not reused.

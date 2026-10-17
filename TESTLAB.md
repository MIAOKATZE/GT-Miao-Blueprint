# GTMB agent test laboratory

The agent laboratory is an administrator tool for repeatable, common-side tests. It is disabled by default. It uses an isolated, save-local dimension and a survival Forge FakePlayer; no human needs to enter it. It is not a claim that every installed mod, client renderer, GUI or network connection has passed testing.

## Enable locally

Start GTMB once to generate `config/gtmb-testlab.cfg`. In its `testlab` category set `B:enabled=true`, then restart. Commands, network messages and JVM properties cannot enable it. On the disabled path GTMB registers no laboratory provider, dimension, tick handler or command, enables no laboratory metrics, and creates no laboratory world, actor, ticket or report.

| Setting | Default | Allowed range / meaning |
| --- | --- | --- |
| `enabled` | `false` | Local configuration opt-in |
| `performanceDiagnostics` | `true` | Common counters while the enabled lab lifecycle is active; ineffective when disabled |
| `dimensionId`, `providerId` | `1048578` | Each 2–2,000,000; an occupied ID disables registration rather than taking it over or finding another ID |
| `baseX`, `baseZ` | `4096` | Multiples of 16, absolute value at most 1,000,000 |
| `zoneSpacing` | `4096` | Multiple of 16, 256–65,536 |
| `zoneY` | `80` | 32–224 |
| `maxOwnedChunks` | `8` | 4–32; also limited by Forge ticket capacity; each current facility leases four chunks |
| `operationsPerTick` | `512` | 1–4,096 cooperative steps |
| `tickBudgetMillis` | `5` | 1–20 ms wall-time deadline between steps |
| `runTimeoutSeconds` | `60` | 10–300 seconds per run |
| `stressIterations` | `64` | 1–10,000 finite codec iterations |
| `stressPayloadKiB` | `512` | 1–1,024 KiB deterministic, incompressible test payload |

The ID follows the high-ID approach used by GTSR; its default is separate from GTSR's 1,048,577 and Outpost's 1,048,576. Configuration bounds and collision checks still apply. A configuration error fails closed. Existing configuration bytes are not rewritten while reading. Initialization registers only the provider type. After normal server worlds have loaded, `serverStarting` validates the actual save's ownership marker before registering the dimension. This avoids Forge eagerly loading the lab with its ordinary startup dimension list. `providerReady` and `dimensionRegistered` are separate status fields. After server worlds stop, the owned dimension is unregistered so another integrated-server save requires its own ownership check.

There is no laboratory teleport command. A client with the default disabled setting does not register the provider. Administrators should keep ordinary players in their normal dimension; enabled-server/default-disabled-client Overworld login is a separate integration check, not something these headless probes establish.

## Commands

Use the server console or a command sender with permission level 4. Commands have no arbitrary coordinate, dimension, path or enable argument.

| Command | Effect |
| --- | --- |
| `/gtmbtestlab status` | Read enabled/registered/active state, IDs, current run and owned ticket count; does not create the lab world |
| `/gtmbtestlab refresh` | Restore queued lab cuts through production undo, clear only owned facilities, then rebuild small floors and identifying markers |
| `/gtmbtestlab reset` | Restore queued lab cuts through production undo and clear only owned facilities |
| `/gtmbtestlab run minimal` | Run the bounded small-unit suite below |
| `/gtmbtestlab run stress` | Explicitly run finite in-memory frame/codec and server task queue load |
| `/gtmbtestlab stop` | Cancel the current run cooperatively and release its tickets |
| `/gtmbtestlab report` | Persist the current in-memory report; refuses if no run/report exists |

Only one run may be active. Repeated start commands are refused. No suite starts automatically. Cancellation and timeout happen between steps; a single native world save, factory call or compression operation cannot be preempted. `atomicStepsOverTickBudget` records ticks that exceed the cooperative budget. Reset and refresh also obey the operation and time limits rather than synchronously scanning their whole volume.

## Facilities and ownership

Five facilities are named `minimal`, `capture`, `cut`, `codec` and `stress`. Each owns exactly a 32×16×32 volume. Facility index `i` has `x=baseX+i*zoneSpacing`, `y=zoneY`; `z=baseZ`, except stress uses `baseZ+2*zoneSpacing`. Refresh builds a 16×16 floor and one marker inside each facility. Capture and codec subtests may share an already leased facility; the names do not imply all five are simultaneously loaded.

The provider generates empty, already-populated chunks without terrain generation, decorators or creatures. It does not change the Overworld's shared weather information. Forge transient tickets are tagged `gtmbTestLab`, save owner and run ID. Switching facilities releases the previous four chunks before acquiring the next four. Cleanup unforces only these tickets and queues only their chunks for ordinary unloading; a world remaining loaded immediately afterward is not evidence of a leaked forced ticket. Existing chunks loaded by another actor are not globally unloaded or erased.

Before registering the save's laboratory dimension, GTMB creates or validates `<save>/gtmb-testlab/ownership-<dimensionId>.nbt` without loading the dimension. Its owner UUID, dimension/provider IDs and exact facility layout must agree on later runs. An existing `DIM<id>` without this marker, changed ownership/layout or symbolic-link ownership paths are refused. Restore the original configuration instead of using reset to adopt an unknown world. Reset never deletes DIM directories, region files, unrelated chunks or another player's clipboard. Human presence in the lab dimension stops automated mutation. The existing production clipboard recovery may initialize an enabled, already validated laboratory to redo an interrupted cut; ordinary fresh-save startup and status do not load it. A disabled/unregistered dimension leaves that interrupted journal retained.

The FakePlayer has a save-specific namespace-derived UUID, is separate from online human identities, has creative and flight disabled, and holds the actual GTMB projector. It does not have a real Netty connection. Its tagged test supply effect checks the enabled active run, exact actor/world, current leased facility, owner/run NBT and actual item. The finite effect supplies seven diamonds once per run; it cannot supply resources outside the owned lab. This effect is an explicit `TestLab.useTestItem` fixture entry, not a global right-click hook or an extra permission for ordinary projector actions. Equipment and inventory are cleared at completion. Dedicated diagnostics include the actor's actual dimension, coordinates and provider class.

## What is checked

`minimal` invokes real common-side production entry points and records the precise scope of each result:

- `special_item_scope`: reject the actual finite supply-effect entry against the Overworld with complete actor inventory unchanged, admit it once in the leased lab, then reject its second use.
- `idle_network_observation`: observe global codec counters over 20 otherwise idle lab ticks. Zero is PASS; unrelated traffic produces INCONCLUSIVE; disabled metrics produce SKIP. This check neither generates network traffic nor attributes another player's packets to the lab.
- `ordinary_blueprint_minimal`: a separate guarded common bridge exercises ordinary blueprint planning, material costs/payment and native world transactions. Its detailed `ordinaryBlueprint` receipt states the actual production paths used. FakePlayer execution does not prove real C2S/S2C delivery or client UI behavior.
- `raw_capture`: capture native inventory and air without changing the source tile identity.
- `cut_source_and_cache`, `selected_raw_preview_dto`, `paste_and_replay`, `undo_original_pose`: actual production raw clipboard storage/world transactions, complete selected DTO, immutable original cache during preview, rotated facing, air overwrite, native inventory preservation, request replay and original-pose undo. DTO evidence does not establish GL rendering. Existing lab-actor cache is validated before any production recovery; reset restores it through normal undo rather than deleting it.
- `codec_roundtrip`, `dedicated_safe_backend`: production snapshot compression, cut packet frame codecs and ordered assembly roundtrip using common/server objects.

`stress` records production codec bytes/frames and finite iterations in memory. It also submits real, inert production `ServerTaskScheduler` callbacks with at most eight outstanding. It waits for submitted callbacks to execute; cancelled or stale callbacks cannot mutate the world or a newer report. The suite never clears the global scheduler queue. It reports `codec_frame_assembly`, `stress_bound` and `bounded_server_task_queue`. It is not a FakePlayer Netty throughput test, a TPS benchmark, or a general machine performance certification.

## Evidence and timing

Each run gets a fresh UUID. Public `TestLab.status()` and `TestLab.report()` return detached NBT. Terminal states are PASS, FAIL or CANCELLED. PASS means the listed executable checks completed; the idle observation can remain explicitly INCONCLUSIVE or SKIP. Exceptions, ownership failures and actual assertion failures fail the run and retain details.

The final report is persisted once to `<save>/gtmb-testlab/latest-report.nbt` and `latest-report.json`, each by synced temporary file plus atomic replacement, capped at 512 KiB each. The pair is not a single filesystem transaction. `reportPersisted=true` and matching `persistedRunId` are exposed only after both writes complete; consumers must check these before associating disk files with a terminal run. Report write errors remain visible in memory and the server log. The report command intentionally requests another save.

`timing` contains `tickCalls`, `tickTotalNanos`, `tickMaxNanos` and frozen terminal `elapsedNanos`. These measure laboratory work and cleanup, excluding final report persistence. Public status/report separately expose the completed `reportWriteNanos`; the file cannot recursively include its own final write duration. Native saves/factories and codec work can exceed one step's soft deadline, and their observed cost is not hidden.

When configured, `metricsStart` and `metricsEnd` are snapshots of the global common-side `HologramRuntimeMetrics`: actual codec packet counts/payload bytes, snapshot build/compression wall time, scheduler/action/transport activity and queue depth/high-water/rejection/coalescing counters. Payload bytes exclude TCP/IP overhead. These global observations may include other users and do not represent TPS. The lab does not reset unrelated global counters.

## Lifecycle and validation boundaries

Main integration calls `TestLab.preInit(configDirectory)`, `init()`, `serverStarting(event)`, `serverStopping()` and `serverStopped()`. Default-off pure regressions validate configuration/bounds/ownership/report isolation. The separately installed test-only headless observer drives real server commands and observes actual dimensions, actors, cleanup and reports. It cannot enable the lab through a property. Builds, dedicated launch, real mod-pack behavior and default-disabled-client login require independent run receipts; source inspection and this document do not replace those checks.

The design follows local GTSWN and GTSR opt-in dimension, FakePlayer and bounded facility patterns. It does not reuse their running worlds or identities. The evidence design is recorded in `plan/tmp/progress-testlab/testlab-design.md`.

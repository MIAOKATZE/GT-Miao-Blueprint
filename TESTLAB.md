# 2026-10-28：客户端作者重构的验证边界

## 大图片来源兼容补充（2026-10-28）

作者结构字符串上限采用既有原生 8 MiB NBT 压缩字节上限的最大 Base64 长度 **11,184,812 字符**，不再误用 compact 内容池的 2 MiB 单项限制去拒绝未入池的 `textBoxNbt`。64 MiB 文档总量、深度及原生 8 MiB 解压／压缩保护均未降低。新增真实 1024×1024 高熵像素图片定义回归：原生编码超过 2 MiB，作者导入／草稿重开保留原编码，原生再次解码、定义校验与逐像素比较通过。

修正后的 `check assemble reobfJar` 在 `27-compat-check.log` **PASS，1m50s**；179/179、12/12、Industrial、ownership、CLIENT／SERVER 隔离及静态 GUI ABI 9/9 均通过，1,994 个 main／mixin／test class 均为 major 52。实机游戏、GL 与完整业务 UI 仍待外部验收。下文 log25 和核心测量保留其各自快照身份，不将其自动套用为其他提交的运行证据。


> 以下实验室历史文档原样保留。新作者路径采用本地 `AuthorDocument`，不能用服务端 FakePlayer、DTO、codec 或旧作者 Session 的通过记录代替真实客户端业务 UI、原生渲染与性能验收。

## 最终集成验证与证据边界

首次完整集成验证使用 `gtmb-build-support/logs/25-integrated-check-build.log`：`check assemble reobfJar` **PASS，2m18s**；服务端回归 **179/179**、独立 JVM 本地作者 **12/12**、Industrial 回归及 snapshot ownership 检查通过；实际 Forge `SideTransformer` 的 CLIENT／SERVER 隔离测试均通过。普通 GTMB＋MUI2 2.3.92 原始 class 的静态 GUI ABI 检查 **9/9**（2,822 个引用），不加载业务 GUI、不运行 FML／GL，不能当作实机界面通过。编译产物 main 1,522／mixin 7／test 465 个 class，全部 major 52。

格式器曾因只读 `~/.m2` 缓存写入失败；仅将外部 Equo/P2 缓存迁到可写工作区，未修改固定版本或关闭检查。原失败日志保留，后续 Spotless apply/check 与集成验证通过。五条原有 Mixin public-target 警告及 Netty direct-buffer fallback 警告保留；这是有边界的 PASS，不是零警告声明。

两份实际私有输入构造的服务端源图已通过有界交接回归：48,003 格／375 slices／owned 13,340,090 B；123,369 格／963 slices／owned 33,561,552 B。quota 保持 **67,108,864 B**，并非放宽限额换取通过。owned 是受控图的所有权计费估算，**不是实测堆占用，也不是活世界 capture 测量**；原始私有输入不随文档公开。

**最终无 overlay 核心实测已完成**：`implementation_measurements/after-integrated-results.md` 与 `48003~after-integrated.log`／`123369~after-integrated.log`。使用实际集成 `build/classes/java/main`、官方 Gson 2.2.4、JDK 8u512、`-Xms64m -Xmx768m -XX:+UseSerialGC`，每输入一次新 JVM，旧 editor/history 在 reopen 时仍存活；31/31 源码／class SHA-256 测前测后不变。

| 实际输入 | readExpanded before → 最终集成 | draft read/reopen（旧 editor 存活） | 进程最大 RSS |
| --- | --- | --- | --- |
| 48,003 格 | 2,348.153 → 981.971 ms | 1,495.639 ms，PASS | 637,852 KiB |
| 123,369 格 | 7,165.699 → 973.460 ms | before OOM → 3,858.441 ms，PASS | 950,852 KiB |

两输入全源 JSON（含未知字段）、编码 NBT、文档 ID／revision、全部稳定格 ID／payload origin 校验通过；指定 native NBT decoder 类加载审计匹配 0（不是对所有未知 decoder 的方法计数）。123,369 格 parse 1,553.399 ms＋readExpanded 973.460 ms 约 2.527 s，**只是纯数据两阶段，不是游戏端到端 3–4 秒达标**。该输入完整校验另用 15,313.512 ms，全进程 27.584 s；校验同时持有旧／新文档与 sourceCopy，其 RSS 不代表日常编辑峰值。单次结果无 p95／统计置信结论；采样 heap 不等于 peak/live set，heap 上限不等于 RSS。文件写入计时未包含 fsync，不能代替产品原子保存耗时。

测量绑定的 `AuthorDocumentIO.class` SHA-256：`524c53c1601f04aa5d5dbfe476e35a312c6001d22273a7b694bfc727484d10d0`；完整源码／class 指纹见 `after-integrated-snapshot.txt` 与 `after-integrated-postcheck.txt`。早期 frozen／overlay 数据和原 OOM 证据保留，但不冒充最终集成成绩；发布后按同核心 class hash 复核，核心变化须重跑。

独立入口 `HologramLocalAuthorRegressionTest` 在单独 JVM 中完成 12 项检查：源 revision／COW／provenance、选中原生 NBT 与旧模板迁移、持久渲染目录、增量投影与延迟 payload、草稿恢复、原生附件、配置、结构再生、显式候选编译、候选配对持久化，以及真实材料样本的隐私／配置和客户端关联。独立 JVM 避免原生 fixture 初始化影响服务端进程级 lease 基线。真实业务 UI、GL、世界 capture／placement、Cut、整包内存／FPS／TPS 与完整五样本 3–4 秒目标仍需外测；不与上述已完成检查混称“未执行”。

## 外部验收必须分层记录

- **纯作者**：从已获得的完整本地源开始，打开、编辑、查看所有详情、撤销重做、保存重开；单独测 GTMB 作者 RPC 和服务器调度／编码活动，剔除连接基础与其他模组流量。首轮源交接、下载、终端取样、提交、施工另列，不能混入零网络断言。
- **源保真与义务**：未知 JSON、精确 NBT 字符串、结构／分组／链接、缺失模组、未解析声明提示、移动／复制／旋转后的 payload origin 与待兑现义务；成功与失败都核对源前后差异。
- **业务 UI 与原生效果**：结构配置、处理六阶段、NBT 保留策略和路径、AE 只标记编辑、网络分组、文字、图片、提示区、图纸库与来源切换逐项实际操作。不能用空白报告或通用方块替代原生业务。
- **大样本**：42,319／47,192／48,003／74,499／123,369 格分别记录冷／热打开、首次可见、当前视口原生完成、全稿原生覆盖、交互延迟、内存峰值／稳定值、GC、FPS／帧时、独立服务器 MSPT／TPS。3–4 秒仅是待测目标，不是现有结果；有界可见缓存下“全稿全驻留”不是合理的完成定义。
- **权威负例**：仅在用户隔离测试世界检查材料不足、保护／权限拒绝、工具／世界／revision 改变、重复包／过期包、物理 Cut 与作者剪切区别；确认无越权世界写入、资产复制或收费重复。
- **兼容与压力**：新旧 peer 配对、重连、取消、队列满、过大源／NBT、缓存回收及多次开关窗口。拒绝必须明确，草稿不丢；codec 限额不等于进程内存已达标。

完整外部测试步骤与回执模板随交付文档 `GTMB_客户端作者重构_外部测试清单.md` 提供。本节不自动启用实验室、不操作实际世界、不替用户运行破坏性用例。手工测试前备份存档、实例配置、库和草稿；生产存档不用于 Cut／回滚验证。测试结果必须保留具体 commit、实例／模组版本、样本 hash、日志及 PASS／FAIL／BLOCKED／NOT RUN 状态；不可把未执行计入通过。

---

# GTMB agent test laboratory

The agent laboratory is an administrator tool for repeatable, common-side tests. It is disabled by default. It uses an isolated, save-local dimension and a survival Forge FakePlayer for the small suites; the separately opted-in composite suite uses one explicitly configured, connected survival test client. It is not a claim that every installed mod, client renderer, GUI or network connection has passed testing.

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
On an explicitly enabled physical client, a separate connection lifecycle maps the already configured provider
before remote play packets create its client world. It does not set server admission, load a world or run
save ownership checks. Disconnect removes only that connection's own mapping; stale connections and
local integrated-server connections cannot remove a server/foreign mapping. Disabled clients install no
such handler. Provider entrance coordinates are read-only local configuration hints, not server admission.

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
| `/gtmbtestlab run composite` | Build the explicitly enabled native 40 cubed fixture and wait for connected self/share/RAW workflow verification; fixture readiness alone is never PASS |
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


## Opt-in native composite suite (v0.1.11 work in progress)

This is a separate local opt-in on top of `testlab.enabled`. Set `B:compositeEnabled=true`,
`S:compositeActorUuid=<exact owned test-client UUID>`, `I:maxOwnedChunks=32` (at least 22),
and an appropriate `I:runTimeoutSeconds` (composite permits up to 3600). The machine-count default is
`I:compositeMachines=100`: alternating registered EBF/LCR controllers gives 50 electric blast furnaces and
50 large chemical reactors. The fixed capture dimensions are **40 by 40 by 40 = 64,000 selected cells**,
including actual machine air cavities; this is not a claim of 64,000 solid blocks. Configuration changes
require restart. No command, message, JVM property or native probe enables either opt-in.

The configured client must already be online, alive and in survival mode, with a real server network
handler. A FakePlayer is explicitly rejected by this suite. Both owned fixture client and server must
have the laboratory provider enabled for dimension transfer. The ordinary default-disabled-client
Overworld login check remains a separate case. The lab saves that test client's original inventory,
hotbar slot, dimension, position and camera pose in memory, temporarily equips its owner/run-tagged
projector, transfers it to the bank safety platform, then restores the inventory and returns the client
on completion/cancellation. An already disconnected client is restored without online dimension-change
hooks or packet sends: GTMB restores the actor's world/pose and invokes the native player-data writer,
then reads back its compressed data to verify inventory, selected slot, dimension, position and rotation.
A failed native write/readback remains a cleanup failure. This is for an owned test client and isolated save; in-memory return state
is not crash recovery. Only this exact actor is exempt from the lab's human-presence stop condition.
Another human entering the dimension stops mutation. Existing unrelated online identities cannot be
selected implicitly, and an existing nonzero wireless EU balance is not overwritten by fixture setup.

The five original 32 by 16 by 32 facilities and their version-1 ownership-layout string remain unchanged.
Composite adds four 40 by 40 by 40 facilities and a 32 by 16 by 32 bank. With `sx=baseX+5*zoneSpacing`,
source/self/share/raw origins are `(sx,zoneY,baseZ+0/48/96/144)`; bank origin is
`(sx+48,zoneY,baseZ)`. Their proximity permits actual finite-range AE wireless payment. An additive
`composite-ownership-<dimension>.nbt` claim binds the exact dimensions/layout to the same save owner.
A missing composite claim refuses any already-existing target region file; enabling composite never
adopts another region. The original marker is not rewritten to expand ownership. Reset/refresh clear
only the configured cells and never delete DIM/region files. Ordinary suites keep their four-chunk
leases. Composite keeps source plus the bank loaded (13 chunks), and source plus the current target
plus the bank loaded during target work (22 chunks); Forge's actual ticket depth still limits this.

`TestLabCompositeFixture` places real registered machines through GT's `ItemMachines.placeBlockAt`,
sets their actual facing/owner, fills the native EBF and LCR casing/coil shapes, gives each controller
an integrated-circuit configuration, and disables working. Every controller has actual configured ME
item/fluid input and output hatches, standard HV and wireless HV energy hatches, and automatic-maintenance
supplies. Formation is observed from natural GT world ticks. The fixture never invokes a machine tick,
never calls `checkMachine` itself and never sets `mMachine` or a recipe-completion flag. An unformed,
working or nonzero-progress controller cannot pass the fixture gate. A one-time finite native wireless
EU credit of 100,000,000,000 EU powers the owned fixture account; native wireless hatches perform their
own transfers. Unused account credit is removed through the same native manager during normal cleanup,
and initial credit, cumulative actual placement credits, remaining credit and net consumption are reported.
Cleanup checks the remainder against the complete native credit ledger, including cancellation after funding.
The bridge releases its live world/baseline reference after its detached final report is retained. There is no
debug energy hatch or creative payment.

Additional source contents include twenty GT single-block recipe-map roles with reproducible actual
covers, three copies of each of 24 AE device definitions (including interfaces, crafting devices,
independent drives/chests with physical cells), three copies of 12 EnderIO device registrations, forty
actual AE cable multipart hosts with real attached terminals, four GTSWN information panels with native
owner/monitor configuration, and four GT machines linked through the actual GTSWN link terminal. Link hosts are selected from real
placed electrical machines with nonzero native EU capacity and voltage; steam machines remain in the
additional-device mix and are not treated as electrical link hosts. The native terminal is actually held
in the main hand, used without sneaking at the center of the UP face, and must install the specific
valid GTSWN energy cover. Native terminal/cover NBT, target coordinates, capacity and voltage are reported;
there is no direct cover-NBT installation fallback. Native
bound quantum-terminal interactions create the gateway nodes next to the configured ME devices.
A separate real GT crafting-input ME hatch and AE interface contain actual encoded processing patterns.
An actual placed AE pattern-terminal part encodes sulfur plus oxygen into sulfur-dioxide output, consuming
one physical blank through its native encoder. The GT hatch creates its real nested `PatternSlot`, whose
native insertion API receives 13 sulfur dust and 26,000 mB oxygen as finite buffered inputs. The source
receipt retains the native encoder's blank-before/consumed evidence, encoded recipe, complete native
hatch/interface NBT and `PatternSlot.writeToNBT` output; the independent baseline therefore compares real
nonempty pattern and buffer payloads. The terminal's encoded output also remains an actual physical item.
These standalone devices do not replace any of the 100 multiblock structures or run a fixture recipe. Missing
registrations, invalid native placement or unavailable current GTSWN APIs fail the run; there is no
placeholder tile or invented terminal NBT fallback. Distinct recipe roles and device definitions are
counted, rather than inflating the content count with decorative metadata or unformed large controllers.

The common-side `HologramCompositeLabBridge` owns finite AE banks, terminal binding, immutable direct-world
baseline observations and independent paid-placement/RAW checks. Source and share quantum anchors keep
their original positions. A third, physically separate payment controller at bank-relative `(18,3,12)`
has its own finite dense cells, drive, WAP and ordinary bound wireless terminal. Source/share each retain
358M initial AE; payment has 64 reserve cells plus one bank cell, each supplied exactly 1M AE, for 65M.
The three native grids must be pairwise distinct. The 781 finite power cells receive 781M AE once in total;
none are infinite or automatically refilled. Only the payment grid is checked empty, supplied from the
actual quote, and checked for exact native debits. Source cells cannot receive quoted payment stock.
The explicit `paymentReceipt.physicalDrivePosition` identifies the actual payment drive for native seed
replays. AE chests retain their physical cells with empty automatic input ports; source interfaces have
real storage items and matching native configuration counts, so natural idle behavior retains those
items instead of exporting them into other source cells and changing the immutable baseline. The task exposes `NATIVE_READY` only
after bank readiness, natural idle formation and the complete 64k baseline. At that point the separate
owned-client native probe must drive actual import, self processing, owner Save and placement, share
processing, owner Save and placement, and raw CUT/paste through production client/server paths. The
suite remains active while those actions run. Only the bridge's completed self/share/RAW postchecks,
actual finite material debit and both connected owner Save commits permit terminal PASS. Encode/readback,
common backend calls, disconnected FakePlayer snapshots, manually written PNGs or fixture readiness
cannot establish native Save completion.

Reports retain the fixture's real controller/device registration and classes, configuration counts,
gateway/wireless-hatch coordinates, reproducible cover seed and live formation/recipe-progress evidence.
Stage observations contain wall time, real lab ticks and global metrics snapshots; normal run timing
also reports maximum lab tick duration and budget overruns. Metrics are global observations, not exclusive
packet attribution or TPS. Full server tick-stall observations and matching client JSON/PNG owner artifacts
belong to the separate native probe receipts. Fair version comparison requires the same fixture inputs,
input digest, assertions, heap limits, mod environment and actor mode. Each absent runtime receipt remains
**PENDING**. Source implementation and this document do not certify that a new composite run passed.

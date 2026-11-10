# GTMB agent test laboratory

## 当前测试入口与制品边界

实验室、Smoke/Probe、合成输入及反射诊断实现位于 `src/testlab/java`。普通 JAR 只保留可选生命周期和被动诊断桥；不携带这些实现，也不会因旧 JVM 探针属性自动施工或关服。正常玩家教程、生产 Deadline、付款与恢复 API 保留。

测试使用 **完整替换包** `<base>-testlab.jar`，由 `reobfTestlabJar` 生成；`testlabJar` 生成开发映射的 `-testlab-dev.jar`。它们包含普通包的生产代码与资源，沿用同一 modid，不能与普通 GTMB JAR 并装。统一 `/miaotest` 入口还需配套 Core 0.2.9 的完整测试变体，Core 普通包只提供被动 API。已存在的原生平台、ownership、真实付款后端与 Actor/Lease 流程继续复用。

测试命令由 **console 或 OP2** 执行，指定真实 actor 也须具备 OP2；生产 `/gtmb` 恢复裁决、蓝图库管理员等既有权限不随测试门槛降低。实验室仍须本地配置启用，JVM UI profile 不会开启服务端实验室。

统一入口为 `/miaotest gtmb list`、`status`、`run <kind>`、`stop`、`reset`、`report`。统一入口的 `run all` 仅映射目录声明的默认任务，当前为 `minimal`。旧 `/gtmbtestlab` 继续支持八种 run：`minimal`、`stress`、`composite`、`native-build`、`native-test`、`recovery-stage`、`recovery-resume`、`construction-performance`，以及独立 `reset/refresh`；不新增任意坐标入口。目录中的 `legacy-*` 与 `client-ui` 只声明独立 JVM profile，不能通过维度 `run` 隐式启动；此路径明确拒绝并记录 `UNSUPPORTED`。

宿主统一使用工作区 [`tools/testlab/host.py`](../tools/testlab/host.py)，固定实例来自 [`plan/testlab-instances.json`](../plan/testlab-instances.json)。GTMB 复用 `E:/CodeGT/测试实例-GTMB` 的客户端和服务端，不按轮次克隆整包。客户端必须运行在 private desktop，创建失败即停止，不回退用户前台，不使用系统鼠标；Java 26、默认 4096 MiB，最大 5120 MiB。`doctor/catalog`、未带 `--execute` 的计划及 helper 编译都不是实机通过；使用方法见[测试维度指南](plan/测试维度指南.md)。

环境隔离仅作用于已核归属的测试世界：抑制自然刷怪和天气，不切 Peaceful、不改全局 gamerule、不赋予无敌，生存付款、逐位置权限与精确扣料断言保留。原生平台准备就绪、`NATIVE_READY`、在线真人或远程 channel 存在，都不证明 Netty 操作或渲染通过。报告分别记录 actor、可用 transport、实际 transport/render 覆盖、工件身份、结果、落盘和清理状态。

`recovery-stage` 留下真实持久故障现场后终态为 `RECOVERY_PENDING`，不是完整恢复 PASS。自有测试 actor 存在未结算的施工恢复日志时，`reset/refresh` 和新场景变更均被只读检查阻断；只有专属 `recovery-resume` 可继续该恢复夹具。不要删日志、清场或改写背包绕过生产恢复；退出清理失败保留资源与 `cleanupPending`，供显式 stop 重试。

本轮已验证编译、JVM 合同、普通 UI 模块图缺测试实现时的构造、样式及双变体生产字节一致性。真实客户端、Netty、生存施工和跨进程恢复仍须各自运行回执；本节不新增实机 PASS。实施记录见[本轮 workflow](plan/workflow/testlab-source-isolation_20261110.md)。

## 持久原生 Mod 平台（显式选择）

本地 `testlab.enabled=true` 之外，还需明确设置 `nativePlatformsEnabled=true`。
`nativePlatformMods` 默认空，填写实际 Item 注册表的 namespace（区分大小写）；
`nativePlatformMachines` 默认空，只接受 `forge-of-gods`、`bec-observation`、
`electric-blast-furnace`、`large-chemical-reactor`。装饰类与设备类使用同一真实注册表目录，
不会因为没有 TileEntity 而被排除。候选 namespace 如 `chisel`、`Ztones`、
`ArchitectureCraft`、`CarpentersBlocks`、`StorageDrawers`、`appliedenergistics2`、
`EnderIO`、`ExtraUtilities`、`BiblioCraft`、`malisisdoors`、`OpenBlocks`、`Botania`、
`Thaumcraft`、`Forestry`；这些候选需由当前实例的真实注册表确认。请求的 namespace
没有任何原生目录条目时会记录原因并拒绝构建，不把不存在或大小写错误的 Mod 算作通过。

| 本地设置 | 默认值与范围 |
| --- | --- |
| `nativePlatformsEnabled` | `false`；命令不能启用 |
| `nativePlatformMods` | 空列表；实际注册 namespace |
| `nativePlatformMachines` | 空列表；上述四种固定机器 ID |
| `nativePlatformActorUuid` | 空；`native-test` 必须指定已连接、存活的生存模式真实测试客户端 |
| `nativePlatformCatalogFile` | 空；可指定真实客户端导出的压缩 NBT；相对路径从 config 目录解析，常规文件最多 16 MiB |
| `nativePlatformMaxOwnedChunks` | `128`，4..256；仅原生任务有效，仍受 Forge 单票深度限制；普通页同时需要 8，诸神之锻炉源区需要 117 |
| `nativePlatformTimeoutSeconds` | `3600`，10..3600；原生构建或测试会话的有限总时长 |

Forge 1.7.10 的部分 creative/subitems 回调只有客户端保留。Dedicated server 要使用
同一 GTNH 实例真实客户端导出的目录，不能把服务端缺失回调或 metadata 0 当作全部变种。
测试辅助程序在客户端线程创建 `new TestLabNativeCatalogExport(String[] registryMods)`，
每个客户端 tick 在有限步数与时间预算内调用 `step()`；返回 `true` 后读取 `snapshot()`，用
`CompressedStreamTools.writeCompressed` 写入该辅助程序自己的受管目录。此接口要求
客户端本地启用 testlab，调用的是真实 Item 的 `getCreativeTabs/getSubItems`。
它不写产品蓝图、不发送新游戏协议，也不接收远程启用参数。导出 schema 1 包含
选中 namespace、Mod 版本、实际 registry 名称、damage、完整原生 ItemStack NBT 与枚举问题。
服务端导入会校验 namespace 和双方已安装 Mod 的版本，按服务端真实注册表重映射 item 数字 ID，
保留 damage 与物品 NBT。枚举异常、输入声明不完整或注册物品不存在都会明确失败。
无客户端输入时仍可直接调用当前运行侧的原生 creative API，但出现客户端方法缺失时必须改用导出输入。

管理员命令与生产探针分离：

- `/gtmbtestlab run native-build`：枚举或导入已选择 Mod 的所有真实 subitems，按物品实际所属 Mod
  排序并持久构建全部分页，然后结束。此处 PASS 仅证明展示平台准备完成，蓝图结果保持 `NOT_RUN`。
- `/gtmbtestlab run native-test`：复用现有平台，准入指定真实客户端并保持活动，供后续探针逐页调用
  GTMB 生产选择、处理、保存和重载。准备完成不会自行产生蓝图 PASS。
- `/gtmbtestlab stop`：结束会话，恢复真实客户端原位置和完整背包，释放本次票据；持久平台保留。
- 原有 `reset/refresh` 只处理原有五区及其原 composite 扩展，不清除或重新扫描这些新平台。

每个 Mod 每页最多 128 个精确物品变种。源区与目标区各为 32×16×32，同页位于独立
512×512 region 内，相邻页相隔一个 region。平台源区有玻璃地面；每个 ItemBlock 用实际物品
调用原生放置路径，等待 40 个实际实验室 tick 后复核；回调拒绝、异常或检查时已变化会逐项记录，
不以直接 setBlock 代替成功。流动液体只保留精确原生物品展示，标记 `ITEM_ONLY_DYNAMIC_FLUID`，
不向共享展示页释放会流动、起火或改写邻块的液体，也不把它记成已放置。
所有变种（包括不能独立放置的物品）还会以准确 ItemStack 放入真实原生箱子，manifest
保留箱子坐标和 slot。非 ItemBlock 只展示库存，不把它当空手激活方块。
没有 Item 的注册方块和没有原生 subitems 的变种物品也会明确记录，未猜造其 metadata。

新平台有独立 `native-ownership-<dimension>.nbt` 及逐页指纹归属，原五区与 composite marker
字节协议不变。第一次认领一页或机器源区时拒绝已存在的未归属 region，不接管旧世界。
已完成且目录一致的页直接复用；只有未完成的已归属普通页需要有限分片重建。
每次只持有当前页源区/目标区或一台大型机器源区的一组票据，切换会释放旧组。
普通玩家在测试维度的自动变更禁入规则保留；测试客户端使用已有 composite 的生存模式准入、
背包保护与退出恢复路径。平台不会在退出时全部拆除。

只读文件位于 `<save>/gtmb-testlab/native-platforms/`：`index.json/nbt` 为全量页清单，
`page-<index>.json/nbt` 为精确条目与位置，`machine-<id>.json/nbt` 为独立机器证据。
活动会话还提供分离 NBT 副本 `TestLab.nativeManifest()` 与
`TestLab.nativePageManifest(String id)`。当前准入 actor 可调用
`TestLab.useNativeFacility(world, actor, "<page-id>:source")` 或 `:target` 切页，不必重启会话。
这些接口不接受任意坐标，也不替代普通 GTMB 权限或客户端 focus 保护。
`sourceFingerprint` 对 Mod 展示页是原生目录堆栈指纹，不能据此声称运行中 TileEntity NBT
从未变化；探针应实扫 `first/second`、`expectedSolid`、箱子原始 slot 和摆放记录。

四个 GT 机器使用单独源平台和真实注册控制器/原生 construct 路径。大型结构只提供
`sourceOnly=true` 的源选择、处理、保存和重载场景，本轮不把目标施工标成通过。
诸神之锻炉采用 trigger stackSize 1 的主结构，玻色爱因斯坦凝聚态观测阵列使用
`MTEBECAssembler`，并与 EBF/LCR 各自隔离。manifest 区分原子 construct 耗时和后续
蓝图阶段，记录自然形成状态、实际非空气块数与结构指纹；构建外壳不等于机器已自然成型或运行。

The agent laboratory is an administrator tool for repeatable, common-side tests. It is disabled by default. It uses an isolated, save-local dimension and a survival Forge FakePlayer for the small suites; the separately opted-in composite suite uses one explicitly configured, connected survival test client. It is not a claim that every installed mod, client renderer, GUI or network connection has passed testing.

## Enable locally

Start the complete GTMB testlab replacement variant once to generate `config/gtmb-testlab.cfg`. In its `testlab` category set `B:enabled=true`, then restart. Commands, network messages and JVM properties cannot enable it. The ordinary JAR does not generate or load laboratory configuration. On the disabled path GTMB registers no laboratory provider, dimension, tick handler or command, enables no laboratory metrics, and creates no laboratory world, actor, ticket or report.

| Setting | Default | Allowed range / meaning |
| --- | --- | --- |
| `enabled` | `false` | Local configuration opt-in |
| `performanceDiagnostics` | `true` | Common counters while the enabled lab lifecycle is active; ineffective when disabled |
| `dimensionId`, `providerId` | `1048578` | Each 2–2,000,000; an occupied ID disables registration rather than taking it over or finding another ID |
| `baseX`, `baseZ` | `4096` | Multiples of 16, absolute value at most 1,000,000 |
| `zoneSpacing` | `4096` | Multiple of 16, 256–65,536 |
| `zoneY` | `80` | 32–224 |
| `maxOwnedChunks` | `8` | 4–32; also limited by Forge ticket capacity; ordinary facilities lease four chunks |
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

Use the server console or a command sender with permission level 2. Commands have no arbitrary coordinate, dimension, path or enable argument. Production administrator/recovery permissions retain their separate existing gates.

| Command | Effect |
| --- | --- |
| `/gtmbtestlab status` | Read enabled/registered/active state, IDs, current run and owned ticket count; does not create the lab world |
| `/gtmbtestlab refresh` | Restore queued lab cuts through production undo, clear only owned facilities, then rebuild small floors and identifying markers |
| `/gtmbtestlab reset` | Restore queued lab cuts through production undo and clear only owned facilities |
| `/gtmbtestlab run minimal` | Run the bounded small-unit suite below |
| `/gtmbtestlab run stress` | Explicitly run finite in-memory frame/codec and server task queue load |
| `/gtmbtestlab run composite` | Build the explicitly enabled native 40 cubed fixture and wait for connected self/share/RAW workflow verification; fixture readiness alone is never PASS |
| `/gtmbtestlab run native-build` | Build/reuse explicitly selected persistent native platforms; blueprint workflow remains NOT_RUN |
| `/gtmbtestlab run native-test` | Admit the configured connected survival OP2 actor to existing platforms and wait for the separate production probe |
| `/gtmbtestlab run recovery-stage` | Stage the owned durable fault fixture; terminal outcome is RECOVERY_PENDING |
| `/gtmbtestlab run recovery-resume` | Resume the dedicated staged fixture and verify production rollback/payment conservation |
| `/gtmbtestlab run construction-performance` | Exercise bounded real tick construction and exact survival payment; not a full-pack performance certification |
| `/gtmbtestlab stop` | Cancel cooperatively and attempt actor/ticket cleanup; failed cleanup remains visible and retained for retry |
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

v0.3.1 候选仅保留通用测试设施。此前未发布的 GTNL 五机专属套件、命令和扩展区域已撤出产品；历史报告仍绑定原候选，不能用来证明当前通用实现通过了五机验收。原五区及 composite 的存档归属协议保持不变，旧额外标记和区域不会被接管或删除。

新的纯声明、来源有效性与材料扩展回归使用自有元素、控制器和 Block/Item 夹具，检查未知回调不执行、保存数据不能恢复权限、注销后失效和真实材料身份。它们属于 JVM 合同验证；完整客户端界面、真实付款施工与外部机器成型各自需要独立实机证据。

## Evidence and timing

Each run gets a fresh UUID. Test-variant `TestLab.status()` and `TestLab.report()` return detached NBT. Runtime terminal states include PASS, FAIL, CANCELLED, TIMEOUT and RECOVERY_PENDING. PASS means the listed executable checks completed; the idle observation can remain explicitly INCONCLUSIVE or SKIP. Staging durable recovery is RECOVERY_PENDING, not a completed recovery. Exceptions, ownership failures and actual assertion failures retain their details.

The final report is persisted once to `<save>/gtmb-testlab/latest-report.nbt` and `latest-report.json`, each by synced temporary file plus atomic replacement, capped at 512 KiB each. The pair is not a single filesystem transaction. `reportPersisted=true` and matching `persistedRunId` are exposed only after both writes complete; consumers must check these before associating disk files with a terminal run. Report write errors remain visible in memory and the server log. The report command intentionally requests another save.

`timing` contains `tickCalls`, `tickTotalNanos`, `tickMaxNanos` and frozen terminal `elapsedNanos`. These measure laboratory work and cleanup, excluding final report persistence. Public status/report separately expose the completed `reportWriteNanos`; the file cannot recursively include its own final write duration. Native saves/factories and codec work can exceed one step's soft deadline, and their observed cost is not hidden.

When configured, `metricsStart` and `metricsEnd` are snapshots of the global common-side `HologramRuntimeMetrics`: actual codec packet counts/payload bytes, snapshot build/compression wall time, scheduler/action/transport activity and queue depth/high-water/rejection/coalescing counters. Payload bytes exclude TCP/IP overhead. These global observations may include other users and do not represent TPS. The lab does not reset unrelated global counters.

## Lifecycle and validation boundaries

Main integration uses optional bootstrap calls for the test-variant lifecycle; missing implementation classes are inert. Default-off pure regressions validate configuration/bounds/ownership/report isolation. The separately installed test-only headless observer drives real server commands and observes actual dimensions, actors, cleanup and reports. It cannot enable the lab through a property. Builds, dedicated launch, real mod-pack behavior and default-disabled-client login require independent run receipts; source inspection and this document do not replace those checks.

The design follows local GTSWN and GTSR opt-in dimension, FakePlayer and bounded facility patterns. It does not reuse their running worlds or identities. Current contracts and validation limits are recorded in [the implementation workflow](plan/workflow/testlab-source-isolation_20261110.md).


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

# GTMB 社区 API（0.1.5 / contract 1）

新集成应依赖 `com.miaokatze.gtmb.api`。`GTMBApi.getExtensionContractVersion()` 返回扩展契约版本；原有投影仪身份和 legacy mapping 契约保持兼容。API 使用宿主的 Minecraft、GTNH 和 StructureLib 类型。

API 开放结构来源、声明解释、旋转、原生持久化与材料核算、显示、静态渲染，以及快照检查和处理报告。它不暴露 `HologramSession`、可变内部 Cell 或服务器世界操作入口。第三方代码是受信任的进程内 Java 代码，这些契约不构成代码沙箱。

## 注册与生命周期

```java
GTMBExtensions.Registration registration = Declarations.registerReader(
    "my_mod:my_wrapper", MyWrapper.class,
    wrapper -> new Declarations.Declaration(wrapper.declaredBacking()));
registration.close();
```

公共注册返回 `GTMBExtensions.Registration`。ID 是小写命名空间 ID，例如 `my_mod:my_wrapper`，在所有公共扩展种类间唯一。重复注册失败，不覆盖旧项。`descriptor()` 提供 ID、kind 和 contractVersion；`GTMBExtensions.descriptors()` 返回按注册顺序排列的不可变快照。旧 SPI 继续生效，不伪装成公共目录条目。

在相应 FML 初始化阶段注册并保存 handle；客户端渲染只能在 client init 注册。close 可以重复调用，只删除本项。关闭不追溯删除已保存的快照数据。结构来源注册与有效关闭会推进 source revision，使保留旧来源证明的续批及操作资格失效，已有规划需要重新采集。声明 reader 的注册和关闭更新 generation，使后续候选查询重新解析。

reader generation 变化后，既有服务端会话在下一次候选/舱口查询清除 per-cell 候选、舱口部件/需求及 capture 的 casing/family/role/channel 解释缓存，并重新解释角色与家族。续批采集也会在下一批清除这些解释缓存。已冻结的坐标、element 图和原有外观快照保留；要完整重采外观或采用新的结构来源，应重新扫描。保存成独立蓝图的声明快照不会因后来卸载 reader 被追溯改写。

## 扩展面与实际运行路径

| 公共模块 | 输入与结果 | 实际运行路径 |
| --- | --- | --- |
| `StructureSources` | 精确控制器类、trigger 副本与可选用途 → `Plan` 或带状态/有效性证明的 `Resolution` | GT 控制器采集前替代 native construct/buildPiece 来源，并区分缺席与明确不支持 |
| `Declarations` | 精确 wrapper 类 → backing declaration 与可选 signal 投影 | 提示、候选解析、共享格交集和服务端目标验证 |
| `BlockTransforms` | 独立 metadata、tile/item NBT → 旋转后的数据 | 编辑、预览和原生放置的旋转流程 |
| `NativeCapture` | 已审阅的 TileEntity/conduit → 完整独立 NBT | 原生 capture 边界，可替代有副作用的 serializer |
| `NativePlacement` | block 与独立 payload → 新 tile 和完整材料账单 | payload 验证、材料核算和既有服务器事务 |
| `ResourceContainers` | 完整流体容器 NBT 副本 → 精确 long 数量与流体身份 | 普通蓝图所选库存的材料核算；注册及关闭使报价缓存失效 |
| `GTMBExtensions.nativeDisplay` | 仅检查 tile 类型的 predicate | 原生预览快照 family gate |
| `ClientRendering` | 静态 renderer、独立 tile/multipart 工厂 | 静态预览 registry，专服无需加载此客户端类 |
| `BlueprintSnapshots` | 不可变 snapshot → 命名空间 NBT 报告或 IOException | 完整采集后、作者处理完成后 |
| `CutTransforms` | 完整原始 block/meta/tile 副本与姿态 → 已知空间字段变换 | 独立剪切缓存的预览与放置派生快照 |

现有 `HologramDeclarationAdapters`、`HologramBlockTransformApi`、`HologramNativeCapture`、`HologramNativePlacement`、`HologramNativeDisplayRegistry` 和客户端 `HologramNativeRenderRegistry` 保持兼容。公共注册桥接到相同的运行 registry，不另建没有运行效果的目录。

公共 `Declarations` 当前提供精确类 reader。只给某一个 element 实例绑定声明时，继续使用兼容入口 `HologramDeclarationAdapters.register(element, declaration)`；该旧入口没有公共 Registration handle，不应宣称支持自动卸载。原工厂 RETURN 记录器也继续使用这一实例入口。

## 动态结构与结构来源

```java
GTMBExtensions.Registration source = StructureSources.register(
    "my_mod:controller_structure", MyController.class, (controller, trigger) -> {
        int tier = readPreviewTier(trigger);
        return new StructureSources.Plan(declarationsFor(tier), Collections.singletonList(
            new StructureSources.Piece(pieceName(tier), horizontalOffset(tier),
                verticalOffset(tier), depthOffset(tier), trigger)));
    });
```

来源接口目前服务于 `MTEEnhancedMultiBlockBase` 控制器。原 `register(id, type, Source)` 和 `Source.describe(controller, detachedTrigger)` 保持兼容：返回 null 表示允许继续 native `construct(..., true)` 和 buildPiece 捕获；非空 Plan 不调用 native construct 或控制器的 definition 工厂。只匹配精确类，子类可能改变验收语义，不自动继承适配；同一精确类不能重复注册，也不能由新旧两种注册同时占用。

需要区分用途、明确拒绝或跨批复核时，使用 `registerContextual(id, type, ContextualSource)`。回调接收 `CaptureIntent.NEW_TEMPLATE` 或 `CaptureIntent.LIVE_SNAPSHOT`，返回 `Resolution.ready(plan, readSet)` 或 `Resolution.unsupported(failureCode)`。新建模板是默认用途；区域采集显式使用现场快照。用途改变需要重新采集，不能只修改显示标题。

| 解析结果 | 含义与行为 |
| --- | --- |
| `ABSENT` | 没有精确来源，或旧 `Source` 返回 null；保留旧 native fallback 语义 |
| `READY` | 使用提供的 Plan，并保留其来源身份与有效性证明 |
| `UNSUPPORTED` | 已匹配来源明确不支持，不能退回 native 来补全 |

contextual 回调返回 null 或 `Resolution.absent()` 都会规范化为 `UNSUPPORTED / SOURCE_NO_DECLARATION`。三参 `resolve(controller, trigger, intent)` 返回上述显式状态，在已匹配 contextual 来源而 trigger 缺失时返回 `SOURCE_TRIGGER_MISSING`。原两参 `resolve(controller, trigger)` 仍返回 Plan/null，controller 或 trigger 为空时直接返回 null，否则默认使用 `NEW_TEMPLATE`；遇到显式不支持时抛出含 `failureCode` 的 `UnsupportedSourceException`，不会把拒绝转换为 null。

`Resolution` 暴露 `status`、`plan`、`failureCode`、`sourceId` 和 `sourceRevision`，后两者由 registry 绑定。`StructureSources.revision()` 是全局来源版本：成功安装任一来源或有效关闭来源时递增。解析过程中 registry 变化会返回 `SOURCE_REVISION_CHANGED`；关闭旧 handle 不会删除后续注册。

`ReadSet.invalidReason()` 只复读生成 Plan 所用的状态，未变返回 null，失效返回稳定原因码。`Resolution.validityFailure()` 先检查来源版本，再检查 ReadSet；`validityProof()` 返回不保留 definition 图的等价复核回调。复核不重新执行 describe、构造 definition 或调用机器构造/检查。ReadSet 可以为空，此时只能证明来源版本未变，不能据此声称世界状态未变。ReadSet 抛出运行时或链接异常时，复核返回 `SOURCE_READ_SET_FAILED`。

采集器另外绑定控制器、世界、坐标、姿态、区块加载、声明 generation，以及续批的 trigger/用途。来源状态变化会使旧资格失效；区域派生可继承来源证明。该机制只覆盖实际记录并复核的观察，不提供通用原子世界快照，也不能证明任意未观察字段或发生后恢复的状态没有变化。

来源回调只能读取声明，不能运行 checkMachine、hatch adder、放置、tick、世界修改或未知 deferred controller factory。ReadSet 同样必须只读，并在合法 owner 线程检查状态。definition 图必须在本次采集期间保持不变；Plan 保留 definition 引用，不对 element 图深复制。pieces 列表复制后不可变，Piece 保存并返回 trigger 副本。`Plan(definition, pieces, metadata)` 和 `metadata()` 分别复制 NBT；旧两参 Plan 构造器仍可用，metadata 默认为空。metadata 用于来源说明，不授予放置权限。来源每次新采集调用一次，续批复用同一图；main/channel 改动、重新扫描或另一采集重新求值，不能永久缓存旧 tier/offset。

默认 native 路径同样每次采集只取得一个 definition，续批复用它。GTNL rc2 的 `NuclearReactor`、`AtomicEnergyExcitationPlant`、`AssemblerMatrix` 等每次 `getStructureDefinition()` 都新建 element 图。旧实现每次续批重建图，因同坐标的 element 身份变化而触发 `CONFLICTING_CELL`。修复冻结的是单次采集图，不是跨会话缓存。

piece 名、main/channel、负数 offset 和 Controller 坐标规则须来自已审阅的声明，不以机器名、形状相似或控制器居中猜测。内置 GTNL 适配按精确机型和目标版本读取已知状态，重建五机声明；AEEP 新建模板选择实体 sphere，现场快照按观察到的 `isRenderActive` 选择 sphere/sphere_air。未知 GTNL 控制器即使没有已注册来源，采集器也会明确拒绝 native fallback。GTMB 不调用 checkMachine 试探语义。

以上为当前源码合同。API/fixture、实际依赖加载、客户端交互与世界施工属于不同验证层，不能据此认定真实 GTNL 组合已经通过验收。

## 声明、旋转与原生数据契约

声明 reader 只能解释不可变数据，禁止执行原 wrapper 的 check、pass/fail callback、setter、hatch adder 或未知 controller lambda。`Declaration(Supplier, signalProjection)` 只允许延迟读取已审阅的无上下文声明。注册仍需经过候选交集、结构归属、原生验收和服务器权限检查。

旋转 accepts 得到一次性副本，其修改被丢弃；rotate 修改独立 State。不可表示的旋转应抛 IOException，不得悄悄删除 payload。新旋转注册优先，关闭后恢复原链。references 为只读 map，坐标数组也从调用方复制，不作为共享可变缓存。

原生 capture 必须返回完整 schema，不得清空 live 库存或网络来伪造安全结果。placement 的 create 返回独立 tile，只操作 detachedWorld；materials 核算全部物理部件、cover 和所选物理库存。只有账单确实覆盖库存实体物品，includesInventoryDrops 才能为 true。登记格式不会绕过材料、归属、量子绑定、受保护格、世界权限或回滚。

显示 predicate 只检查类型。client renderer 只读独立 access/tile，禁止 tick、事件、放置钩子、网络注册和任意 TESR。原 registry 的内置特殊渲染优先级不变。

剪切预览直接复用 Forge 正式注册的 `ISimpleBlockRenderingHandler`，不通过反射构造通用 renderer 副本。构造器可能分配或改写全局 render ID；未注册的新 ID 会同时破坏世界几何和物品的 3D 判定。renderer 应在 Mod 客户端初始化阶段注册，渲染回调中不得注册或替换全局 handler。

## 流体容器材料解码

```java
GTMBExtensions.Registration fluids = ResourceContainers.register("my_mod:contents", context -> {
    if (!context.nativeTileId.equals("my_mod:tank") || !context.name.equals("contents")) return null;
    return ResourceContainers.numberedFluidSlots(context.container());
});
```

`FluidCodec` 返回 null 表示不适用；非 null 结果声明已经完整核算该子树，空列表表示合法空库存。适配器必须验证所有字段、类型和数量，遇到未知或无法计价的数据抛出 IOException。`Context` 提供原生 tile ID、GT 机器 ID、根键及当前路径；NBT 和返回的流体身份均为独立副本。适配器不接触世界、库存或会话，也不授予放置权限。

内置通用 numbered 槽格式接受连续 `#0..#N` compound：空槽、Forge `FluidName/Amount/Tag` 或 AE `FluidName/Count=0/Cnt/Req/Craft/tag`。AE 的实际数量来自 long `Cnt`，不能用占位的 `Count` 计价。只在流体上下文或 fluid/tank 名称下自动识别；其他原生容器可通过上述公开方法适配。未知字段、缺号、错误类型、负数和混合数量格式明确拒绝；保留的 payload 不会被改写。`FluidAmount.amount` 使用 long，`identity()` 保留流体标签；后续宿主材料支付仍会检查标签是否可被实际存储后端保真处理。

注册按顺序尝试，首个非 null 结果负责完整子树；`generation()` 在注册及关闭 handle 时递增，用于材料计划缓存。现有去标识等级保留所选物理库存；用户显式取消该 NBT 根键后，恢复内容和对应材料账单才一并移除。

## 快照检查与作者处理报告

```java
GTMBExtensions.Registration review = BlueprintSnapshots.register(
    "my_mod:review", BlueprintSnapshots.Stage.AUTHORING_COMPLETE, snapshot -> {
        if (hasUnsupportedConfiguration(snapshot)) throw new IOException("Review required");
        NBTTagCompound report = new NBTTagCompound();
        report.setInteger("reviewedCells", snapshot.cells.size());
        return report;
    });
```

`CAPTURE_COMPLETE` 在完整采集后运行一次，包含受保护的 controller anchor，不在每个续批运行；报告随服务端快照 extensionReports 提供。`AUTHORING_COMPLETE` 在内置去标识、坐标、量子、NBT 和提示处理后运行，报告保存到 processing receipt 的 `extensions.<registration ID>`。后续编辑使处理记录失效时，旧报告也失效，需要重新处理。

Snapshot 包含不可变值和 cell 列表；tile()/item() 每次返回独立 NBT。处理器可分析、验证和报告，不能修改作者结果、原始结构、会话或施工授权。报告字段不会成为已付款、已绑定或可放置授权。`BlueprintSnapshots.inspect(snapshot)` 也可分析外部工具的独立数据，不导入服务器会话、不操作世界。

回调按注册顺序运行。IOException 拒绝该次完成，错误包含扩展 ID，原始作者来源保持不变。回调应确定、无副作用，避免无界工作和网络阻塞。capture、authoring、原生资源回调沿用服务端线程；renderer 在客户端线程运行。

## 模块与验证边界

`HologramService` 现在只保留原有外部协议入口及其委托，不再持有 Session 注册表、材料计划、作者处理或世界事务实现。`HologramWorkspace` 独占会话创建、激活与断线清理；包内 `HologramSession` 是引擎上下文，不属于第三方 API。

区域、编辑、作者处理、库传输、预设和世界定位分别由对应 Workflow/Editor 负责。`HologramBuildPreparation` 协调采集完成后的施工请求；`HologramControllerCapture` 只负责结构数据与重试策略，`HologramPlacementJobs` 执行有界任务，`HologramWorldTransactions` 持有逐格付款、原生回调、世界修改和回滚。规划与单元策略层不回调上层 Workflow。`HologramSnapshots` 组装快照，通过 `HologramSnapshotSink` 端口传输。

`HologramStructureDiscovery`、`HologramDeclarationCollector` 和 `HologramExtensionSnapshots` 继续承担发现、声明缓存和公共快照转换。完整职责、依赖方向与执行路径见 [ENGINE.md](ENGINE.md)。这些包内模块共同组成服务器引擎，采用明确的上下文和模块调用协作；稳定的社区接入边界仍是本页的公共 API。

新增 `HologramDynamicDefinitionRegression` 验证 collect/续批中的新建 element、超过 4096 格、多 piece、动态 channel/main/offset、新采集重新冻结和来源 SPI。raw harness 调用与 buildPiece Mixin 相同的捕获 hook。`HologramCommunityApiRegression` 验证公共注册进入声明、旋转、持久化、材料、显示和作者处理路径，并检查生命周期、旧 SPI 共存、快照隔离与拒绝保留原源。

可选 `HologramDynamicDefinitionNativeSmoke.run(WorldServer)` 在真实 FML 中调用原生 buildPiece 和 hintParticle，由实际 Mixin 捕获；断言两次采集的 main/channel、负 offset、多 piece、跨批、独立 definition，以及逐格原世界 block/meta/tile identity 不变。通过现有隔离驱动加 `-Dgtmb.hologram.dynamicDefinitionNativeSmoke=true` 启用，`gtmb.hologram.dynamicDefinitionReceipt` 可指定压缩 NBT 回执文件。此 fixture 不注册或放置合成控制器到世界，也不使用 GTNL 类名伪装原模组。

该原生 fixture 有意让普通 A 声明覆盖 controller 坐标，验证真实 hint 样本集合与最终几何 union 完全相同，controller 由同一 protected anchor 同时替换 cell 列表和坐标索引中的旧格。几何计数包含 controller 原有位置，不凭空再加一格；raw 来源回归也检查此重叠保护边界。

GTNL 根因已核对本地 `GT-Not-Leisure-0.2.7-rc2` 源码。源码分析、代表 fixture 和真实 GT/StructureLib 接口测试不等于安装了 GTNL 原模组，也不能宣称所有 GTNL 机器已完成 FML 或施工验收。
## 剪切缓存的原始方向变换扩展

`CutTransforms` 是 contract 1 的兼容扩展，专用于当前存档中该玩家的原始剪切缓存。它不进入蓝图库、分享、作者处理、材料或普通蓝图 schema 审核。`CutTransforms.register("my_mod:raw_pose", adapter)` 返回 `GTMBExtensions.Registration`，descriptor kind 为 `cut-transform`；ID 与所有扩展目录共享唯一性，关闭句柄只卸载该次注册，新注册优先。

`Adapter.accepts(State)` 获得一次性副本；`transform(State, Transform)` 操作另一个独立副本。State 包含 block、metadata、完整 tile NBT，以及原始和目标绝对坐标。适配器只修改已知空间字段，保留库存、owner、原生身份和未知字段；不得接触真实 world、执行 tick、加载钩子或注册网络。异常会丢弃该格的部分修改，保留原始 metadata/NBT，并在预览中报告未识别方向，不会删除原件或拒绝整次放置。

Transform 按源局部轴 mirror（bit 0/1/2 为 X/Y/Z），再 X、Y、Z 四分之一转执行；`rotation` 是 Y，`turnX`、`turnZ` 为其余轴。`point` 将完整体积含空气归一化到目标最小角，`size`、`vector`、`direction` 提供同一矩阵。已知原生方向字段变换，未知私有字段按 WorldEdit/MatterManipulator 的保留原则原样携带，不猜测其语义；不照搬 MM 复制重建路径的连接缓存清理。

预览和放置从原始缓存生成派生快照，不回写原件。持久 journal 同时保留原始队列记录和派生放置快照，世界读回成功并提交后才消费原件；撤销始终恢复原维度、原位、原姿态。所选条目的完整原始 NBT 仅经独立剪切通道发送给已认证所有者，目录列表不包含方块内容。客户端独立预览 world 不主动 tick；无法渲染的原生 TESR 显示诊断，数据仍完整保留。

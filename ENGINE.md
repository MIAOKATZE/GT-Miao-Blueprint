# 2026-10-28 发布前复核的修正与后续边界

两轮独立审查后的实现补充：

- 附件任务的原生预检从构造前就进入执行边界；需要旧扩展时复用同一个附件引擎，在私有编辑器/owner续行中准备增量，客户端在原版本仍有效时一次提交。
- 旧Gson的strict开关不足以拒绝原始控制字符、非法转义及混合大小写字面量；作者入口加入常量状态的流式词法保护，不再解析第二份整稿，也不改变既有数字语义。
- 复制关系按原点/数值位移建索引，避免每个宿主线性扫描所有副本；跨维度的原生hub先判定外部关系，不查询无关本地副本。未知空间字段仍保守拒绝。
- 结构行复制复用容器复制与不可变标量；compact准入复用正则，计量Writer直接计数字符/字符串，保留Gson转义和原预算公式。
- 审核传输按实际大小及协商速率分阶段预算，保留2分钟计算和30分钟总上限；结果大小就绪后重新准入，轮询不是进度。不能保证未知后续带宽争用下必然完成。
- 历史公共库启动不再在IO线程调用旧原生扩展：内置路径仍是带粘性保护的IO快速路径，外部路径一次只保留一个待准入DTO，heartbeat按128工作量/约2ms协作推进原生验证与sanitation，纯数据和最终索引发布留给单IO worker。停止/重启/注册代次变化不会发布旧结果；原文件不因失败被删除。单次第三方回调、header验证及schema1完整验证仍不可抢占，不宣称硬实时。

仍值得后续独立优化：格式适配与编辑核心的依赖方向；registry不可变快照与安全适用性元数据；typed intent/configuration计划减少分散字符串派发；最终客户端大增量准备的协作处理；稀疏ID页的空间利用率；完整原生编译的分阶段数据复用。这些不是本轮已完成性能承诺，也不能以抽象化为由放松原始NBT、权限或事务边界。

---

# 2026-10-28 增量修复 外部回调与来源坐标

基线为已合并 PR #64（776159f），外测反馈 PR #65 是报告与复现工具，不是产品修复。本节描述后续增量；下方旧版本记录保留，旧 PASS 不自动适用于新提交。

- 作者文档现在持久保存真实采集 session 的维度与世界原点，独立于每格局部 payload origin；交接、草稿、历史及原生处理/旋转/编译使用同一来源框架。无法恢复来源的旧 raw 链接保留原稿并明确拒绝，不猜当前玩家位置。详见 AUTHOR_SOURCE_PROVENANCE.md。
- 作者来源采用一次严格流式 JSON 读取、受控 compact 展开和内部所有权转移；外部 JsonObject 接口仍防御性复制。重复字段不再在第一次 DOM 解析中悄悄丢失；数值语义与既有 Gson 兼容（不是逐字符 JSON 格式保真）。
- NativePlacement、ResourceContainers 和 StructureSources 的旧注册保持默认执行边界，客户端安全能力需要显式 opt-in；在 supports/decode/describe 前检查，不能探测后再判断或静默绕过第三方覆盖。公开同步调用接口不擅自改线程。
- 合成结构声明不触发真实 CAPTURE_COMPLETE；本地处理不借旧 AUTHORING_COMPLETE 生成服务端凭据。最终发布/私有激活刷新不可信报告，确认续接只复用服务端持有的精确已审核来源，避免重复回调。
- 显式兼容审核绑定原文档、版本、任务、哈希、连接、世界和工具；选中格查询只携带所需记录和引用结构。结构再生、选中配置及显式文字/图片附加对象回退复用原生引擎，在 owner tick 上分段推进并返回原子增量。普通文档编辑不恢复成远程作者 Session；不伪造 ACK、触碰世界或消费资产。
- 没有外部扩展时，原有库规范化仍留在 IO，最终回执清理不重复扫描全稿；注册变化由粘性边界阻止越线程调用。任意第三方单次回调不可抢占，显式完整处理/编译兼容路径也不是已证明的恒定 tick 延迟。
- 分段快照先扫描有界页头，保护本帧未来引用；空间不足的完整页采用当前帧私有所有权，不提高缓存容量。编码端超过热集的顺序扫描污染另列优化事项。

API.md 列出仍未镜像的第三方原生操作边界；新增协议与组件回归不代替实际整合包、UI、GL、材料及施工验收。MatterManipulator 的进一步借鉴方向是语义工作集缩小、稳定描述共享和意图通信，不是继续添加全量缓存或取消权威检查。

---

# 2026-10-28：客户端作者文档重构（当前分支）

## 大图片来源兼容补充（2026-10-28）

作者结构字符串上限采用既有原生 8 MiB NBT 压缩字节上限的最大 Base64 长度 **11,184,812 字符**，不再误用 compact 内容池的 2 MiB 单项限制去拒绝未入池的 `textBoxNbt`。64 MiB 文档总量、深度及原生 8 MiB 解压／压缩保护均未降低。新增真实 1024×1024 高熵像素图片定义回归：原生编码超过 2 MiB，作者导入／草稿重开保留原编码，原生再次解码、定义校验与逐像素比较通过。

修正后的 `check assemble reobfJar` 在 `27-compat-check.log` **PASS，1m50s**；179/179、12/12、Industrial、ownership、CLIENT／SERVER 隔离及静态 GUI ABI 9/9 均通过，1,994 个 main／mixin／test class 均为 major 52。实机游戏、GL 与完整业务 UI 仍待外部验收。下文 log25 和核心测量保留其各自快照身份，不将其自动套用为其他提交的运行证据。


> 本节说明 `refactor/client-author-document-20261028` 相对 `c249291` 的新作者路径。以下原版本章节全部保留作历史背景；其中“服务端作者会话／快照驱动作者编辑”的表述，不再代表已接入本地文档的新作者路径。服务器规划、真实施工、材料与物理 Cut 的权威边界没有随作者迁移而移交客户端。本文描述实现，不替代实机验收。

## 一份源文档、单向视图、明确跨界

```text
完整源 JSON / gtmb-author-draft / 一次性服务端源交接
  → AuthorDocumentIO（有界语法、结构与来源检查）
  → AuthorDocument + AuthorEditor（本地源真值、原子修改、历史）
  → HologramLocalAuthorRuntime（作者生命周期与既有业务 UI 适配）
  → HologramAuthorProjection → HologramState → 渲染 / 只读 UI

显式保存草稿 → 本地恢复文件（不是可施工证明）
显式处理 + 导出 → PortableCompiler → 原生 PNG + JSON → 未审核候选
显式放置 → 既有私有 contentOnly admission → 服务端核验 / 规划 / 材料 / 世界事务
```

`blueprint.editor` 中的 `AuthorDocument`、`AuthorEditor`、`AuthorDocumentIO`、`AuthorTree` 不依赖 Minecraft、注册表、服务器会话、网络 codec 或原生 NBT 解码器。源 JSON 是唯一可写真值；向调用方返回防御性副本。未修改的未知字段与编码 NBT 字符串保留，不从显示 DTO、快照或渲染缓存反向拼回源数据。结构准入不等于原生兼容、资产、所有权、材料或可移植性审查。

`AuthorDocument` 使用稳定文档／单元 ID、64 格写时复制页及持久位置／分组索引。编辑按事务发布一次或完全不发布；撤销重做恢复内容、选择及来源信息，但发布新的单调 revision，废弃分支的单元 ID 不复用。当前 runtime 使用最多 64 条历史，同时受保守保留源字节预算约束；这不是 JVM 总堆占用上限。普通选中格编辑不应通过完整快照往返；导入、保存、显式全源处理仍可能遍历全稿。

几何位置与原生 payload 的坐标基准不是一回事。文档记录每格 payload origin 和有序 pending obligations；移动／复制后可以先保留原始 payload，由显式原生编译边界兑现坐标、链接、附件与兼容性义务。保存、撤销、再打开不能自行清除这些义务。未知变换语义、歧义链接、不完整原生结构必须保留原稿并明确拒绝，不能以“预览有形状”冒充可安全施工。

## 模块职责与限制

| 模块 | 当前职责 | 不代表什么 |
| --- | --- | --- |
| `HologramLocalAuthorRuntime` | 持有编辑器、选区／剪贴板、处理报告版本、草稿保存状态及可丢弃投影；窗口关闭重开不把源所有权交给窗口 | 不是服务端 Session，也不能凭本地 revision 获得权限 |
| `HologramLocalAuthor*Actions` 与 common-side `HologramLocalAuthor*` | 连接现有注释、NBT、处理、配置、结构、AE、文字／图片等业务能力；需要原生事实的工作仍通过明确适配器执行 | 不是仅保留空壳预览的简化编辑器；也不表示所有业务控件已实机验证 |
| `HologramAuthorProjection` / `HologramDocumentIndex` | 稳定源 ID 与稠密渲染槽位分离，单向增量投影，可见格原生 payload 延迟解码与缓存回收 | 不持有源写权限；失败占位不删原 NBT，也不成为审核通过 |
| `HologramSourceTransfer` | 从实际服务端捕获／规划源，一次性、有界、owner-thread 交接完整作者源；检查玩家、连接、世界、session、revision、任务 token | 不可用显示快照补成“完整源”；不是持续远程作者写循环 |
| `HologramAuthorPortableCompiler` | 冻结源、兑现支持的原生空间义务、按明确策略重新处理、原生验证并产生独立候选 | 不修改活动草稿，不制造作者身份或服务端审核回执 |
| `HologramAuthorSubmission` | 版本／上下文栅栏、owner-thread 原生编译、原生渲染上下文 PNG、受限 IO 与 JSON/PNG 配对 | 当前同步原生编译不是已证明有 tick 预算的导出流水线 |
| `HologramClientLibrary` | 恢复草稿原子替换及回读、候选配对回读；区分本地草稿和审核库条目 | 不把候选自动列为已审核库，不以导出清除源 dirty 状态 |

投影当前常量：待解码队列 512、依赖边 65,536、原生驻留计费预算 32 MiB；每次推进使用约 2 ms 的协作 deadline，并以本次待解码队列长度（最多 512）限制尝试数；不再额外限制每个 UI tick 最多 4 格。延后预算恢复仍每次最多轮转 8 格。单个原生调用及批量发布不可据此宣称可抢占或硬实时，缓存计费也不等于整个客户端内存；确定性调度回归不等于真实整包的模型完成速度验收。源传递当前最多保留 2 个 lease、单份计费上限 64 MiB、每次最多 256 步并检查协作时间预算。整包总内存、FPS、TPS 与五个样本的游戏端到端表现仍须独立测量；已完成的两份真实输入核心测量及源交接回归见本节验证回执。

## 三种文件／准入状态不可混称

1. **恢复草稿**：`MiaoBlueprints/drafts/<UUID>.gtmb-draft.json`，格式 `gtmb-author-draft` v1。保留完整源、稳定身份、payload origin 和未兑现义务；无需 PNG、作者身份证明或服务器。未知草稿版本／字段拒绝而非丢弃。成功落盘与仍在保存、失败、过期版本必须区分。
2. **未审核候选**：显式处理并导出后，`MiaoBlueprints/candidates/unreviewed_<UUID>.json` 与 `.png`。配对哈希／回读一致性只证明文件配对；不证明服务器授权，也不应进入已审核库列表。导出失败或期间源变化，原草稿与义务保留。
3. **服务器 admission／施工**：显式放置将冻结候选交给既有私有 `contentOnly` 内容准入；之后仍由服务器校验内容、身份、世界／工具上下文、材料、权限、预期现场与真实事务。正式审核发布另有工作流。客户端不能制造 ACK 或跳过权威材料／Cut 资产检查。

## UI、传输及兼容边界

本地作者的常规编辑从 UI 进入本地 runtime／actions；`HologramUiSession.send` 在已有本地作者时留有安全栅栏，不为旧控件模拟线协议或服务器 ACK。未取得完整作者源时，界面应明确等待／失败，不能把空报告标成已完成，也不能允许显示快照成为作者真值。已有结构配置、来源选择、处理、NBT、AE、网络分组、注释、文字图片、库保存及世界施工能力都在验收范围，不能仅以主菜单启动通过宣称功能完整。

纯作者阶段的目标是 **零 GTMB 作者业务 RPC、零由这些编辑引起的服务器作者负载**，不是游戏连接上绝无 keepalive、移动或其他模组流量。源获取、远端库下载、真实终端／库存访问、正式提交与施工是单列的显式网络边界。新能力通过连接绑定的 `supportsAuthorDocuments` 协商；旧 peer、重连、任务取消、限额及过期传输应分别验证，不能把旧协议兼容说明扩展成所有混装版本均已实测。

schema-1 模板通过原有原生适配器尝试明确升级；升级失败保留可读原稿及恢复保存，不因缺失模组捏造兼容性。物理 Cut 与 `editCut`（作者规划排除／剪贴板）仍是不同功能。世界资源、剪切资产和持久恢复的历史安全规则继续适用。

## 本轮验证回执（不替代实机验收）

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

---

v0.1.23 的 `HologramPictureBox` 在公共侧只处理有界 ARGB 样本、固定材质色板、滤镜、量化和边缘连通抠图，不引用客户端或 AWT。`HologramPictureFiles` 仅在客户端访问实例内 `MiaoBlueprintPicture`，在解码前核对文件和图像尺寸；原生 `HologramPictureEditorScreen` 使用有界单工作线程处理读取、预览和几何，过期回调按代数丢弃。图片定义嵌入既有 `textBoxNbt` 的 `picture` 分量，以 `gtmb_picture_` 区分整体身份；文字与图片共用 `HologramTextWorkflow` 的权限、版本、冲突与原子 staging。共享 `HologramMicroTiles` 按材质分别精确合并，混色整格不误折叠为单一普通方块。文字旧布局及旧序列化保留；复制、快照、世界姿态、撤销和删除均沿用既有附加链。

v0.1.22 使用共用 `HologramGizmo` / `HologramGizmoRenderer` 提供轴线与 XY/XZ/YZ 面平移。文字的 R/Alt+R 先经工作区权限/结构范围检查，再通过 `HologramTextWorkflow.rotateSelected` 重建整个对象并原子校验；有界立方体朝向替代无限追加旋转历史。`layoutVersion` 区分历史旋转式竖向与新逐字直立栅格；当前弧形、弯曲及扭转以横行或竖列中点为基准。微体素用 BitSet 去重后按 X/Z/Y 贪心合并精确长方体，填满整格且无额外染色的等价普通实心立方体在服务端落为无 TileEntity 的普通方块，仍保留文字定义与归属。`editDelete` 仅接纳无活动控制器结构的作者蓝图，复用几何 staging/单步撤销，控制器 `restoreCell` 原意保留。文字属性共用稳定草稿值，原生 MUI2 调色及只读背包采样子窗口不触发真实槽位搬运。加载材料时的 `HologramPlacementLoadingScreen` 属于快捷放置允许的过渡界面。

# GTMB 服务器引擎模块

v0.1.21 新增 `HologramTextBox`（无世界访问的有限文字栅格/形变/LittleTiles 打包）、`HologramTextWorkflow`（服务端位置与冲突校验后原子加入草稿）、`HologramVoucherEditor`（等级 4 的配置入口、版本冲突检查与权威回显）。文字定义保存在结构描述的 `textBoxNbt`，普通原生 tile NBT 不携带文本身份；原有结构归属、复制及撤销链维护整体选择。放置锁定的 schema-2 材料规划使用 `DeferredPlanRefresh`；`placementValidating` 阻止提前打开材料确认，完成后回到原核算流程。

本文描述 0.1.4 的内部职责和依赖。社区集成契约见 [API.md](API.md)。这里的包内类、字段和方法不是第三方兼容接口；客户端请求也不能携带这些对象。

## 入口、状态与生命周期

`HologramService` 是旧网络、物品和库接口的兼容 facade：保留原有外部方法签名，将请求交给实际负责的模块。它没有 Session 表、作者流水线、规划器、存档实现或世界事务，也没有供其他模块回调的一组私有 helper。

`HologramWorkspace` 独占会话创建、激活、查询和断线移除，维护每个玩家的区域上下文。`HologramSession` 保存同一服务端工作区的可变状态；`HologramEditState` 只记录可撤销的规划快照，不记录世界或背包。二者均为包内类型，没有继承一个 Service 基类，也不通过公共 API 返回。

Workspace 的 `forEachConnected` 负责连接生命周期，Scheduler 只为有效连接选择下一阶段。原先的 tick 主循环已拆为区域导入、采集续批和施工任务三个实际实现，调度器不内嵌这些业务。

```mermaid
flowchart TD
    Facade[HologramService] --> Dispatcher[ActionDispatcher]
    Facade --> Opening[Projector / Area / WorldPlacement workflows]
    Facade --> Library[LibraryWorkflow]
    Facade --> Scheduler[WorkspaceScheduler]
    Scheduler --> Workspace[Workspace.forEachConnected]
    Workspace --> Stage[选择当前阶段]
    Stage --> Area[AreaWorkflow.tickImport]
    Stage --> Prepare[BuildPreparation.tickPending]
    Stage --> Jobs[PlacementJobs.tickJob]
    Prepare --> Capture[ControllerCapture.collect]
    Prepare --> Start[PlacementJobs.start]
    Jobs --> Transaction[WorldTransactions.operate]
    Transaction --> Materials[MaterialAccess / Materials.Transaction]
    Transaction --> World[Forge / 原生方块回调 / World]
```

## 职责表

以下类均位于 `com.miaokatze.gtmb.hologram`，省略共同的 `Hologram` 前缀。

| 模块 | 所有职责 | 边界 |
| --- | --- | --- |
| `Service` | 旧协议入口委托 | 保留外部签名，不实现引擎业务 |
| `Workspace` | Session 生命周期、区域上下文、出站端口组合 | 运行工作流通过 create/find/activate 操作会话，不能访问其私有 Session 表 |
| `Session` / `SessionKeys` | 包内工作区状态、状态复位、原存档键 | 不暴露给社区 API，不改变既有 NBT 键 |
| `WorkspaceScheduler` | 选择区域、采集或施工的 tick 阶段 | 不负责区域遍历、采集实现和材料付款 |
| `ActionDispatcher` | session/请求序列、限流、配置和操作分发 | NBT 是请求；授权仍由服务端身份、策略与事务重新验证 |
| `ProjectorWorkflow` | 打开、取消、设置与工作区 UI 意图 | 使用 Workspace 生命周期和各领域入口 |
| `AreaWorkflow` | 选区、导入、切换成员结构、同步区域编辑 | 导入续批在 tickImport 中执行，不进入施工事务 |
| `AreaDeclarations` | 区域成员声明视图及共享格归属 | 不依赖区域交互工作流，不执行世界修改 |
| `WorldPlacementWorkflow` | 虚拟蓝图移动、旋转、锁定与位置确认 | 只修改规划；已放置原生控制器的对齐由事务实现 |
| `BlueprintEditor` / `EditState` | 单元编辑、几何、剪贴板、撤销重做 | 更改作者数据，不支付材料、不修改实际世界 |
| `AuthoringWorkflow` | 作者选择、六阶段处理、来源切换与保存审阅 | 使用现有纯处理模块与库提交边界 |
| `AuthoringQueries` | 处理来源、AE 问题和 NBT 选择的投影 | 可维护工作区的延迟计算结果，不调度作者操作、不修改世界 |
| `BlueprintProjection` | 实际规划到独立蓝图的投影、虚拟蓝图载入 | 统一作者/预设所用的投影规则，保存原身份与 schema 校验 |
| `BlueprintData` | 深拷贝、来源描述、结构配置和成员值对象 | 不调用 UI 或世界事务 |
| `BuildPreparation` | 重新采集、校准、确认计划、网络分组准备、完成后开始任务 | 协调作者准备与 capture → job 转移，采集器和任务不反向启动上层流程 |
| `ControllerCapture` | 采集数据、预算、声明解释失效和校准数据 | 使用 HologramCapture/StructureSources，不调用上层 Workflow |
| `CellPolicy` | 目标、候选交集、原生 payload 投影、舱口与朝向 | 读取世界和声明，维护解释缓存，不运行逐格施工 |
| `PlanPolicy` | 差分分类、遮挡/归属/预期现场与整体施工前置 | 使用 CellPolicy 和会话作用域，不回调 Workflow |
| `ResourcePlanner` | 差分计划、资源/朝向分配、整机预设 | 只规划，不运行 Forge 施工事件或世界事务 |
| `MaterialAccess` / `MaterialSources` | 材料查询、库存快照与 StructureLib 物品源适配 | 保留原材料上下文和模拟规则；权威付款由事务流程持有 |
| `PlacementJobs` | 开始、推进、统计有界施工任务 | 每格调用 WorldTransactions；不回调作者或定位工作流 |
| `WorldTransactions` | 逐格世界修改、事件、原生 placement、材料支付/回滚、障碍清理 | 保留预期现场、权限、物理资源和回滚检查的单一实现 |
| `DraftPersistence` | 工具设置、world-owned draft 存取与兼容恢复 | 通过现有 DraftStorage/Presets 存储组件，不分发用户操作 |
| `PresetWorkflow` | 预设保存/选择/载入及编辑入口 | 投影交给 BlueprintProjection，审阅和库提交交给各自领域 |
| `LibraryWorkflow` | owner/session/revision 绑定的传输、接收与保存回执 | 继续使用 LibraryFacade/Service 与原子 artifact 写入协议 |
| `Snapshots` / `SnapshotSink` | 客户端快照构造和单向输送 | 读取明确的查询接口，不能用快照授予施工权限 |
| `Values` | 坐标键、材料相等和小型值变换 | 无会话注册、磁盘、网络或世界事务 |

## 依赖方向

引擎使用显式模块调用，禁止静态导入一个 Service helper 集合。主要底层链条如下：

```text
Values
  ↑ SessionPolicy / BlueprintData / MaterialAccess / Workspace
  ↑ AreaDeclarations / CellPolicy
  ↑ PlanPolicy / DraftPersistence
  ↑ ResourcePlanner / ControllerCapture / BlueprintProjection
  ↑ WorldTransactions / AuthoringQueries
  ↑ PlacementJobs / Snapshots
  ↑ BuildPreparation / 领域 Workflows / Dispatcher
  ↑ Service / Scheduler
```

这是职责依赖说明，不要求同层对象彼此完全隔离。事务在修改现场后可以重新计算差分；任务在一批结束后可以发送快照；上层作者、预设和库工作流可以通过明确的方法完成交接。规划、单元策略、材料访问、数据采集和逐格事务没有反向调用上层 Workflow 或 Dispatcher。

原有 `aeReady` 包含自动分组、去标识和规划更新等有副作用的准备步骤，因此归属上层 BuildPreparation，不作为纯查询放入 AuthoringQueries。实际作者数据更新由 AuthoringWorkflow.ensureNetworkGroups/deidentifyNetworkDraft 实现；这一协作发生在上层准备阶段，不从低层 job、planner 或 transaction 回调作者工作流。

输入与输出采用明确边界：

- 请求通过现有网络/物品 facade 进入 Dispatcher 或领域入口，Workspace 与 SessionPolicy 验证当前玩家、工具、会话、维度和位置。
- 结构来源通过 `StructureSources`，声明通过 `Declarations`，物理数据与材料通过现有原生适配器及 `HologramMaterials.Context/Transaction`，不引入第二套放置授权。
- Snapshot 构造与网络发送之间是 `HologramSnapshotSink`；Workspace 在组合处将其连接到原 `HologramNetwork.sendState`，快照模块不直接依赖网络实现。
- 文件持久化继续通过既有 DraftStorage、Presets、LibraryFacade/Service 和 BlueprintArtifact 原子写入契约；Session 类型不进入存档格式。

## 关键执行路径

**采集和续批。** BuildPreparation 请求 ControllerCapture.collect；HologramCapture 固定一次采集的 definition 图及 piece/trigger。续批只继续同一图。采集器更新声明、候选和规划数据；需要施工时，由 BuildPreparation 在完整采集后转入 PlacementJobs。改变来源或需要重取完整外观时重新扫描。

**编辑和作者处理。** Dispatcher 将编辑交给 BlueprintEditor，将处理选择交给 AuthoringWorkflow。BlueprintProjection 产生独立来源，现有 BlueprintProcessing 执行身份、量子、坐标、NBT 和提示规则，公共只读 processor 在完成后检查并产生报告。来源切换、保存审阅和最终库回执各有独立领域入口，原始作者数据不会因取消或拒绝而被改写。

**施工。** BuildPreparation 校验确认 revision 和现场，ResourcePlanner 计算资源与差分，PlacementJobs 按预算推进，WorldTransactions 对每格重新核验并执行原来的支付、事件、原生放置与补偿路径。原生 serializer、物理库存、恢复物品和量子网络规则仍使用原有专门模块。移动 facade 或方法位置不会让客户端 cell 成为世界写入授权。

**快照。** Snapshots 调用单元、规划、作者和持久化查询模块构造与旧协议兼容的 NBT，然后通过 Sink 单向发送。它没有任意执行世界操作的输出接口，公共 BlueprintSnapshots 也只收到独立数据。

## 兼容与验证

原外部 Service 方法、网络操作名、Session UUID/序列语义、工具标识、NBT 键、蓝图 schema 和存储/事务规则保持兼容。包内 Session/方法所属类属于实现细节；原生 smoke 和回归的私有反射已迁移到实际 owner。动态名称诊断只使用包内 EngineInspection，运行工作流不调用这一诊断工具，也没有为测试保留一批 Service 私有转发 stub。

验证应同时覆盖完整回归与实际引擎路径：本版 105 个回归用例、动态 definition 原生跨批、剪切双 JVM 持久化/恢复及原生蓝图/材料/放置 smoke。原生 GT/StructureLib 代表 fixture 不等于安装了 GTNL 原模组。每次结果必须绑定实际候选源码、class 字节、启动参数和日志；重构前的 PASS 不作为重构后候选的测试结果。

## 工作预算、增量与进度（0.1.5）

`WorkspaceScheduler` 在在线会话之间轮转，共用每 tick 10 ms 的协作预算；单次区域扫描、采集与施工切片至多申请 5 ms。`ServerTaskScheduler` 每 tick 最多执行 64 项或 5 ms，FIFO 余项留到下一 tick。原生单次回调、剪切事务、完整快照和编码不能被这些检查点强制抢占，不能据此承诺硬实时上限。

持续运行的施工由 `JobPublication` 发布单元索引与结果增量，不逐批重建全部 cells。客户端 `JobVisuals` 按会话、revision、审阅 nonce、批次和发布序列合并只读显示；它不能发行施工凭据。初始、终态和明确 UI 请求仍可获得完整快照。采集与导入使用轻量进度包，传输、解码、JSON/PNG 和本地提交进度由客户端实际阶段驱动。

普通动作、确认回执、剪切和库请求分别限制尚未完成请求的容量，处理结束后才释放名额，超量请求返回有界 busy 回执；它表示该请求没有入队，不能冒充旧操作的最终拒绝。私有保存遇到不确定回执时保留本地文件对，并通过原有取消/结果协议核实真实终态。网络 ingress 有界不等于所有第三方调用的通用任务队列都有硬容量上限。

库工作线程向主线程转交的五种回调另有每玩家 2 项、全局 16 项 pending 名额，执行结束才释放，避免前级队列已经出队而后级积压。生命周期清理使旧名额失效，旧回调不能释放新一轮的容量；拒绝保存回调只通过原有来源守卫终止未提交结果。

`HologramRuntimeMetrics` 默认关闭，提供独立 NBT 计数快照：GTMB codec 收发字节、快照构造/压缩耗时、队列与增量统计。字节不含 Forge/TCP 开销，耗时不是 TPS；详情及配置开启的独立测试设施见 [TESTLAB.md](TESTLAB.md)。这些 common-side 模块不导入客户端 GUI 或渲染类型。

## 客户端工作台的组合边界

`HologramScreen` 只适配 Minecraft 的 GuiScreen 生命周期、输入和父子界面返回，并保留既有包内 smoke 入口的薄转发。工作台通过组合装配 `HologramUi*` 模块；没有继承另一份大 Screen，也不静态导入 Screen 的内部状态。`HologramUiModules` 仅保存固定的模块引用，不承担原来的大量窗口布尔量、输入框和业务数据。

| 模块 | 所属状态与职责 |
| --- | --- |
| Session、Ticket | 当前快照、请求序号、等待状态及统一网络信封；Ticket 绑定 session，旧会话或较早 ACK 不能完成当前请求 |
| Router、InputChain | 原有绘制层次、模态输入优先级和生命周期协调；可见模态即使点击空白或禁用控件也消费输入 |
| Canvas | 缩放、局部坐标、控件命中、皮肤、物品图标与提示绘制 |
| Memory | 按会话恢复选区、标签页、草稿名称和引导记忆 |
| Preview | 摄像机、选区、框选、空格定位、轴拖动、背包拖放和编辑队列 |
| Configuration、Position、Shortcuts | 控制器参数、通道与候选仓室、等级预设、材料来源、坐标输入和快捷键设置 |
| Library | 本地/公共库、目录导航令牌、管理员设置、未保存切换、覆盖与删除确认、保存 ACK |
| Processing、NbtEditor、Deidentification | 作者处理流程与处理预设、分作用域 NBT 编辑、去标识选择及各自确认/ACK 状态 |
| BuildReview | 放置 revision、材料/资源与缓存报价审阅、施工范围及建造确认 |
| AeReview、NetworkBindings | AE 有界问题报告、跨结构导航、容器预览往返，以及量子分组绑定与真实 ACK 导航 |
| Annotations | 提示文字、悬浮标记、固定说明和版本面板 |
| Diagnostics | 保留原生 smoke 场景和断言，按真实 owner 定位已迁移的私有字段/方法；不是公共扩展接口 |

每个功能域保存自己的私有界面状态，并处理自己的控件动作、焦点、滚轮和 ACK。初始化输入框、游标更新、加工/NBT revision 参数、基础配置确认和选择请求记录也归对应领域。Router 保留原数字 ID 的分发顺序及原 Z 序，Session 的快照更新只按既定顺序协调领域 transition；不把原来的大 activate 或 updateState 原样隐藏在一个新协调类里。模块间的读取与动作接口限制在客户端包内，不将可变 `HologramState`、选区或控件对象加入公共 GTMB API。

原有服务端协议、session/UI sequence、确认 revision、图标坐标、按钮 ID 与窗口交互保持兼容。采集中的忙碌覆盖层、作者确认、NBT、AE 与量子绑定模态继续先于背景工作台处理输入；普通蓝图与专属剪切控制器仍是独立工作流。界面数据与显示快照不构成世界写入授权，实际修改继续由原服务端入口校验和执行。

## 客户端重构的验证边界

`HologramUiModuleRegression` 检查实际用于工作台的模态消费链，以及跨 session/旧序号/重置后的请求 Ticket。既有布局、选择、摄像机、快捷键、处理流程、记忆、放置上下文和引导回归仍须运行；这些模型断言不能替代 screen wiring 的原生验证。

原 `HologramClientSmoke` 的全部基础和扩展场景保留。PNG 捕获先在 ClientTick 请求，再等同一 Screen 的后续 `RenderTick.END`，要求 frame serial 和渲染 generation 均增加。客户端目录的 `hologram-render-frames.nbt` 逐帧记录 PNG 名称、SHA-256、字节数、尺寸、场景、缩放、请求/实际帧序号和 generation；最终写入 completed/passed。该账本明确标记 `actualNetty=false`、`sourceKind=server-snapshot-fixture`，只能证明实际原生绘制及 fixture 界面断言，不能冒称真实服务端交互通过。

真实网络交互仍由原 Interaction/AeInteraction 与 GuidanceNative 驱动验证，包括材料和施工确认、父子界面往返、框选忙碌层、来源切换和量子绑定 ACK。私有反射迁移到 Diagnostics 的真实 owner，不删除原断言，也不向运行时 Screen 增加一批私有转发桩。最终验收应绑定本轮候选重新运行完整回归、GUI END 截图和真实网络交互；各原始失败、运行日志与截图证据保留其实际归属。

## 本地预览与可信施工内容

`HologramLocalPlacement` 和蓝图库独立 browse display 只拥有显示数据，不替换服务端工作区、revision 或施工能力。源数据保持不变；24 种正交姿态、锚点、锁定与方向键微调在客户端维护。普通浏览只读取显示所需 JSON 或视觉 DTO，不打包、核验 PNG 配对、计算语义哈希或准备材料。首次显示沿用已有时间预算，去掉两遍 256 格/tick 的固定节流；完成的 state、cells 和 regions 按稳定文件身份或公库版本复用。显示缓存有界，超过常规额度的大图仅保留最近一个；文件实际变化、断线或更换玩家清退对应内容。原 Renderer 的分组、距离和视锥裁剪继续生效，完整下载沿独立原件路径进行。

原预览区域可以先显示异步解码的有界 PNG，占位延迟 200 ms，小图不强制等待占位。首批 state 使用独立的 cells/regions 容器，后续补齐不会修改已发布前缀；相机仅在实际更换来源时重置。普通浏览的原生 mesh 按自然帧时间预算渐进准备；只读蓝图库可见时暂停背后的 GTMB 世界 mesh 绘制，避免共用原生快照每帧切换而反复清空准备游标，离开后沿原路径恢复。数据完整后解除相关操作门禁，不等待全部 GL mesh，原 footer 继续展示绘制进度。读盘、解析、数据准备、绘制分别报告真实计数或未知总量及耗时，首批可见不冒充任务全部完成。作者差分和保存截图保持原完整渲染路径；单个原生材质提供者与最终投影排序仍可能超过单帧软预算。

库内点击原有放置按钮时才懒算并缓存 `HologramBlueprintContentHash`，先发送仅含内容身份和任务栅栏的 probe；原蓝图库 footer 显示准备、上传与校验进度，通过后进入世界预览。哈希忽略顶层名称、作者等描述字段，保留 NBT、保留选项和施工配置。服务端先查当前玩家的可信缓存，再查已发布库的内容索引；命中不构建 artifact、不计算传输摘要、不上传，双 miss 才读取完整配对、打包上传并执行 canonical admission。传输摘要仍单独检查实际收到的 artifact。内容哈希不是客户端提供的授权，也不能跳过新原文的首次审核。集成集合的原始内容身份与服务端规范模板按源代次绑定，避免默认字段差异或外部文件变化造成显示与实际内容错配。

`HologramTrustedBlueprintCache` 按连接、存档生命周期与策略版本隔离，保存独立模板副本。每玩家最多 10 条、100,000,000 字节紧凑 JSON，全服最多 32 条、512 MiB 展开内容计量配额；条目按 LRU 淘汰，权重是保守记账而非实际堆测量。本服保存的服务端原件在最终保存回执通过后直接成为可信条目。切维度保留内容，断线及运行重置清退；活动施工的锚点与确认能力另行约束。

`HologramBlueprintCompactCodec` 用方块描述字典和完整 NBT 内容池进行无损去重，逐格坐标引用字典；原始 JSON 蓝图继续可读，展开后的语义哈希一致。写入过程不先构建整份冗余 JSON；读取在复制前核对字典引用、行数和展开总量上限，避免小文件放大内存占用。公开蓝图库保留 64 MiB 磁盘总量限制，并另设 512 MiB 展开 DTO 计量上限。传输层原有分片与 GZIP 继续独立生效。

锁定后再次右键只请求原有 `openPlacementReview`，打开既有 `HologramMaterialConfirmationDialog`；不先核算一份客户端账单，也不自动执行建设。需求、库存、AE、抵用券、进度与最终确认沿用原 `ResourcePlanner`、`MaterialQuote`、`BuildReview` 和窗口，不另建材料 UI。最终姿态与位置经过服务器原有门禁；材料报价、当前放置者 UUID/名称、权限、区块与逐格事务继续使用服务端真实状态。显示缓存和哈希命中不授予扣款或世界写入能力，保存、导出及升级仍走既有存储和回执流程。

## 旧文件升级与单人传输

`HologramBlueprintFiles` 将稳定逻辑 ID、旧导入别名与可读文件名分开管理。独立 JSON/PNG 使用蓝图名与完整语义哈希命名；有界本地索引仅负责路径映射，不授予作者或施工权限。无索引的有效复制文件可以登记，原有 UUID 名称与分享别名继续可读。`HologramBlueprintCompactCodec.recompress` 直接转换原始 JSON，保留未知字段和 NBT 文本；升级不执行作者处理或游戏对象校验。

迁移先写入并回读新配对，再接入 `PairWrite` 与原子索引；逻辑提交成功后才清理旧文件。失败保留原件，已提交后的清理失败单列警告。集合重压缩与既有存储写入共用监视器并核对原文，避免覆盖较新的保存。批量升级按条目报告实际进度并检查取消；服务器公库升级仅限管理员，报告分页发送。

每个新的后台任务在取得 owner 时初始化活动时钟，读盘阶段与随后的内容准入各有自己的等待预算；重复进入同一任务和迟到回执不能续期。单人集成服开放私有内容分片与导入，继续核对当前玩家、投影仪、世界、session 和 revision；公库发布及管理仍受独立权限约束。

### 显式兼容审核的有界时限

`HologramAuthorReviewDeadline` 集中管理双方协商的速率/大小预算：上传和结果下载分别采用两倍最多两个审核槽位的公平份额传输时间，加 30 秒固定余量；本地准备/握手各有两分钟上限，上传完成后的服务端解码、原生处理与结果编码共用两分钟窗口。30 分钟硬保留上限独立于实际进度；轮询、等待回复、重复阶段通知不能续期。实际结果大小仅在编码后已知，因此先检查剩余硬上限才发布结果。无法满足该保守预算的上传在 Ready/上传前拒绝，结果在首个结果字节前拒绝，并报告大小、速率与阶段。共享库的其他未来流量仍可能触发真实进度超时；该策略不声称所有持续推进的原生任务或任意竞争都能完成。配置在处理中变化会明确中止并要求按新预算重试，不会静默沿用不可能满足的旧预算。

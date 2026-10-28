# 客户端作者重构合并后验证记录

日期：2026-10-28。**源码与配套文档 PR 已合并；合并提交构建和指定回归通过。受控外部回调核查发现与旧线程契约不一致的兼容问题，尚未修复。一次限时客户端观察未达到完整验收条件。未发布 Release，未更新交付 JAR。**

## 合并与构建

| 项目 | 结果 |
| --- | --- |
| 源码 PR #64 | 合并提交 `776159fed2feec351d85c4c31f16d14d1cee128d` |
| Plan PR #3 | 合并提交 `c3b91ecea7751ab4dd8b458522bca7829221ed99` |
| 技术 Wiki PR #24 | 合并提交 `3937038a9c44dde09f154bd9fe031b20f42c5bed` |
| 合并提交构建 | `clean check assemble reobfJar --offline --no-daemon --max-workers=1 --no-parallel --console=plain`；退出码 0，4m58s，源码检查后 clean |
| 回归 | 服务端 179/179、本地作者独立 JVM 12/12；Industrial、ownership、实际 Forge `SideTransformer` CLIENT/SERVER 隔离及静态 GUI ABI 9/9（2,822 个引用）通过 |
| 普通运行包 | `gtmb-v0.1.55.jar`，7,675,426 B，SHA-256 `78c49808722eb7b30bfd8a27298c5f117e7ee2956773dda9e866d44b423faa4b`；1,530 个 class 全为 major 52，ZIP 完整性检查通过 |

此结果覆盖列出的构建与回归，不代表真实作者 UI、完整游戏流程或端到端性能通过。本轮没有重跑 standalone core 性能测量；旧测量保留为各自源码快照，不作为此合并提交的成绩。

## 已确认的外部回调兼容问题

受控 headless fixture 调用了合并代码的生产入口。fixture 使用 JDK 17、headless Forge 初始化和 SERVER 物理侧；方法调用线程命名为 `controlled-client-owner`。进程退出码为 0，生产 class 未修改，也没有启动游戏。

- 本地 processing 与 portable candidate compile 都调用已注册的 `AUTHORING_COMPLETE` 回调。受控回调抛出 `IOException` 后，调用被拒绝，源内容保持不变。
- 本地 processing-state 检查调用 `ResourceContainers.decode` 的 `CustomFluidState` codec；候选枚举调用外部 `NativePlacement.supports/create/materials`；本地结构再生调用 `StructureSources.describe` 与 `CAPTURE_COMPLETE`。这些回调均在受控调用者线程执行。
- `HologramServerLibrary.canonical` 在该次 content-only 规范化调用中没有重新执行 `AUTHORING_COMPLETE`（回调计数为 0）。这不是活服务器 admission、放置或世界事务测试，不能据此断言服务器越权接受或施工。

现有 `API.md` 对自动 capture、authoring 和原生资源回调记载了服务端线程契约。公开的 `BlueprintSnapshots.inspect(snapshot)` 是调用线程上的纯检查入口，不能代替自动回调契约。受控 fixture 证实本地作者路径会在调用者线程触发这些旧回调；因 fixture 的 physical side 是 SERVER，它没有验证真实 CLIENT 物理侧。已确认的是契约边界不一致，尚无据此观测到的内置崩溃或现场施工结果。兼容修复未实施。

可重跑的合成输入探针及命令见 [回调边界复现工具](tools/client-author-validation/README.md)。仓内脚本已对上述 clean 合并提交重跑，退出码 0；该退出码表示复现成功，不能作为兼容性通过门禁。它没有修改公共 API、跳过扩展拒绝或伪造服务器授权。后续修复需要保留旧自动回调契约，明确本地扩展能力，并在真实服务器边界核对最终候选；不能只改文档追认旧消费者接受线程变化。

## 云端待修清单

| 编号 | 已确认的问题／覆盖缺口 | 定位与影响 |
| --- | --- | --- |
| C01 | 旧快照处理器被本地作者路径调用 | `HologramLocalAuthorSemantics.process` / `HologramAuthorPortableCompiler.compile` → `HologramExtensionSnapshots.authoring`；原自动 `AUTHORING_COMPLETE` 服务端线程契约不再成立。旧扩展可能拒绝或依赖服务端线程。 |
| C02 | 外部流体容器 codec 被本地状态计算调用 | `HologramLocalAuthorSemantics.processingState` → 资源检查 → `ResourceContainers.decode`；未声明接受客户端线程的旧 codec 仍会执行。 |
| C03 | 外部原生放置适配器被本地候选枚举调用 | `HologramLocalAuthorConfigurations.candidates` → `NativePlacement.supports/create/materials`；只保护 create/materials 会漏掉选型时的 supports，不能调用后才判断是否安全。部分旧路径本就有非服务端调用，不能把全部历史问题归因于此次重构。 |
| C04 | 本地结构再生调用旧来源与采集完成扩展 | `HologramLocalAuthorStructures` → `HologramCapture` → `StructureSources.describe` / `CAPTURE_COMPLETE`；只修处理／导出仍会漏掉此入口。 |
| C05 | 服务端候选规范化没有补跑作者处理器 | `HologramServerLibrary.canonical` 对注册拒绝型 `AUTHORING_COMPLETE` 的调用数为 0；客户端没有服务端扩展时，候选文件不能证明已经经过该扩展。需核对真实 owner admission/activation 的补验。尚未实测活服提交／施工，不能写成已证实越权。 |

这些是同一兼容边界问题的不同入口及一个服务端复验覆盖缺口，不代表已经发生五种内置崩溃。受控拒绝保源断言通过。3–4 秒目标、五图端到端、编辑保存及完整业务 UI 仍未验收，不能作为已确认产品错误计数。

## 一次限时客户端观察

在 4 GiB heap 的隔离新世界中，仅观察一个 42,319 格输入；本轮没有向系统窗口发送输入或取得焦点。QA 记录显示作者文档已载入、画布和标题可见，未停留在“解析收到的数据”状态。自动验收要求 35 秒内达到完整源／视口条件，但该条件超时，`performance.complete` 为 false，且没有取得所需性能帧。流程按 harness 取消后以退出码 0 正常结束，没有强制终止。

因此这次结果只是一项限时 UI 观察，不能判为产品 PASS 或 FAIL，也不能据此作出性能结论。其余四个输入以及编辑、保存用例未执行。第二轮在用户要求停止修复／输出清单前已经启动，初始化阶段未执行样本业务；通过既有取消命令以 `CANCELLED`、退出码 0 正常结束，未强制终止。第二轮源 manifest 未变化，14 份原样本与 5 对隔离副本结束哈希核验通过，不继续扩展测试。

测试期间源实例 manifest 出现 118 项外部启动／升级变动。14 份原始样本的 SHA-256 复核通过，5 对 owned-copy 哈希匹配；没有覆盖这些外部变动。

## 交付状态

- 本地 `v0.1.55` tag 指向合并提交；`RELEASE_VERSION` 为 `0.1.55`。该 tag 未推送，未发布 Release，交付目录的 JAR 仍为 v0.1.54。
- 公开玩家 Wiki 的冻结 `approvedRef` 仍为 `d3bb6753c97d56a25692dbce6ef4f48037d90def`。本轮重构尚未 Release，没有发布新的玩家文档或配图。
- 按用户要求交云端继续修复；[Draft PR #65](https://github.com/MIAOKATZE/GT-Miao-Blueprint/pull/65) 仅含报告与合成复现工具，未修改产品实现，保持未合并。
- 合并后构建与指定回归已完成；外部回调兼容修复、实际客户端完整验收、其余样本、编辑保存及性能验收仍未完成。

私有原始资产、资产路径、原文和 QA 截图未包含在本记录中。

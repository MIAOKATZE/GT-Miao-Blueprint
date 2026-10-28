# 大蓝图打开问题：云端接手

2026-10-28，按用户要求暂停实现、测试，提交并合并当前进展。**当前合并代码不是验收通过版本，3–4 秒目标未达成。未发布 Release。**

## 已证实的问题

1. **旧快照无限解码重试。** 用户基地 A 共 42,319 格，真实 GTS2 v2 为 803,930 B / 166 页；展开 43,244,978 B，现有 Validator 估算 NBT 对象占用 443,638,668 B，超过 256 MiB 工作额度。部分解码先遇到竞争额度，误当暂时繁忙，永久重试。不是 JVM 全局死锁，也不是 5 分钟 TTL。
2. **真实 S2C 主线程循环等待。** 候选 `ec4e5f368c06534dda41d2ed1fb5c9abbbce66b3` 的 candidate03：Client thread 在 `DisplayFragmentHandler → enqueueReceived → SnapshotInbox.put` 等满队列；两槽 seq29/30 都是解码成功的 modelOnly 结果，decoder idle，唯一消费者仍是这个 Client thread。已只读 Attach 证实。
3. **模型补丁反复重置预览。** 227 帧内 27 个 owner identity、18 次 refined 进度回退，累计回退 17,380 格；18.133 秒仅完成 14,938/42,319。非 dirty 区域的未完成游标也被重建。
4. **端到端首包和准备仍慢。** candidate03 点击后 activation 0.457–1.573s；metadata 1.573–3.054s；cells 3.184–4.783s；首 inbox 5.720s；客户端准备 5.953–7.000s；完整 owner/首可见 mesh 7.062s。完整 mesh 没完成。

## 已实施和验证边界

- `ec4e5f3`：按页精确 descriptor palette / 坐标共享；可变 ItemStack NBT 独立；旧 peer 保持原格式；移除每帧 256 格硬限，保留原 2ms 截止及首次提示帧。
- 真实 42,319 格组件：新压缩 488,218 B、raw 4,296,779 B、解码计费 43,770,276 B、准备 47,085,344 B；含缓存共 119,845,538 B。逐格原 NBT 等价、lease 全归零；组件解码 69ms、准备 52ms。不是端到端耗时。
- `ec4e5f3` 本地 Tag clean check/assemble/reobfJar：179/179、ownership、Industrial、GUI 静态 ABI 9/9、style 通过，139.545s。JAR SHA256 `2a9c0a1dc3eb6bd107df225ccc8829520c87c040e9ce59fe16745aee2066c7e4`。**此 JAR 的实机 candidate03 失败，不能称可用包。**
- 之后提交的补丁仅有各自定向验证，**合并前未再次统一编译、完整回归或实机验收**：owner 非阻塞 ready-drain / display busy 重试、输入 reservation 延迟到 poll；非 dirty 渲染游标保留；同 palette wire 投影省重复展开；确证 ABSENT 元数据省空证书分配。
- 最后加入的连接级双帧信用窗、组装 Complete/partial/timeout/clear 归还、绕过 ActionGate/world 的纯信用 ACK，处于整合中，**没有完成整体测试**。

## 云端优先核查

- `HologramNetwork` / `HologramSnapshotAssembly` / `HologramLibrarySnapshotAssembly` / `HologramSnapshotInbox` / `HologramClient`：信用必须直到真正 poll/clear 才归还，不能在 decode 完成时归还。覆盖普通、library、model/detail 共用两槽、首片失败、尚未发送的退役帧、跨 session/维度/connection、迟到/重复 ACK、active 旧 epoch、旧 peer。纯信用 ACK 不能被 ActionGate 忙或世界切换丢掉。当前接线及测试仍需逐项审查。
- `HologramRenderer`：保留非 dirty pending 游标，同时 dirty/halo/native-model 区域必须重做、计数精确，不能保留旧模型或虚报 mesh ready。
- metadata ABSENT 快路径必须保持 EXPLICIT_EMPTY / PRESENT_UNKNOWN / AE/node / unknown block / ResourceReview 与失效义务；定向等价测试不替代完整工业回归。
- 首包已超过 4 秒。继续分解真实 owner CPU、NBT 复制、位置敏感 native callback、跨 tick 节流；不能靠少校验、少模型、扩大 quota 或组件数字宣称目标达成。
- 5 个唯一实样本为 42,319 / 47,192 / 48,003 / 74,499 / 123,369 格。仅最小样本已做失败实机，最大和其余样本尚未验收。

## 现场与安全边界

- 本地证据：`plan/tmp/large-blueprint-open-20261028/`；组件另在 `build/verification/large-open-quota/`、`build/large-blueprint-open-20261028/`。私有 JSON/PNG/快照不提交。
- candidate03 的 `bounded-stall-stack.txt`、`attach-readonly-receipt.properties`、`attach-recovered-*.tsv`、`candidate03-summary-private.json` 保留原始失败。只在严格核对 owned PID 后 clear inbox 一次解除挂起并正常取消退出，`cleanupIntervention=true`；不算成功。
- candidate02 仅 QA 导航误走 PROJECTION，0 样本点击后取消；不算产品或性能通过。下轮 QA 已修导航、去掉计时中 raw palette 展开，尚未安装/运行。
- owned 客户端 4GiB / 后台，无残留；原始 14 个 JSON/PNG SHA 均回核。固定基线 Core0.1.27 / GTIT2.0.17；源实例外部 GTIT2.0.18/config 更新单独记录，未覆盖。
- 本地 `v0.1.55` 仍指向已 clean-build 的 ec4e5f3，**不代表后续合并进度**，未推正式 Tag。A-Jar 未替换，未发布 Release；不把此暂停交接当正式发布。

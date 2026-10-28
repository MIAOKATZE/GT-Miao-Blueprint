# 客户端作者回调边界复现

本工具复现 `CLIENT_AUTHOR_VALIDATION_20261028.md` 中的已知行为，**不是兼容性通过测试**。它只使用合成数据，不启动 Minecraft，不创建玩家、会话或施工授权。

先在干净源码工作区执行项目的 `check assemble reobfJar`，保留 main/test 编译输出。随后使用 JDK 17 或以上运行：

```powershell
python tools/client-author-validation/run_callback_fixture.py `
  --worktree <已构建且干净的源码工作区> `
  --java-home <JDK目录> `
  --output <尚不存在的证据目录>
```

脚本从现有 `runLocalAuthorTest` 导出依赖，编译独立探针，保存原始日志、源码哈希与提交回执。它不修改 Gradle 配置，也不执行构建任务；输出目录必须是新目录，不能覆盖旧证据。

探针使用 headless Forge SERVER 物理侧，将调用者线程命名为 `controlled-client-owner`，直接调用生产本地作者入口并记录扩展实际执行线程。退出码 0 表示复现观察及拒绝保源断言完成，**不代表旧服务端线程契约已经修复**，也不代表真实 CLIENT、服务器 admission、材料或世界事务验证通过。

覆盖 `BlueprintSnapshots`、`ResourceContainers`、`NativePlacement`、`StructureSources` 的已确认路径，并观察 `HologramServerLibrary.canonical` 是否重新调用作者处理器。后者只是规范化方法检查，不能推出活服施工结果。修复这些边界后应调整本工具的行为断言，并另外增加真实兼容性验收。

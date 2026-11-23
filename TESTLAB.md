# GTMB 测试指南

完整操作与设施规范已统一到 [plan/测试维度指南.md](plan/测试维度指南.md)。本页仅保留旧链接兼容跳转。


大型蓝图实机探针不附带样本。使用隔离的 TestLab 实例，在该实例的 `MiaoBlueprints` 放入你自己的测试 JSON/PNG 副本；不要使用日用实例或原件。启用 `gtmb.testlab.giantNativeEdit` 时，通过 JVM 属性 `gtmb.testlab.giantNativeEdit.fixture.id`、`.fixture.jsonSha256`、`.fixture.pngSha256`、`.fixture.cells` 提供该副本的本地 ID、两个完整 SHA-256 和格数。探针仍要求原有 TestLab 隔离和所有权检查。渲染对照探针使用自备大图时，还须传入 `gtmb.testlab.renderParityGiantCells`。

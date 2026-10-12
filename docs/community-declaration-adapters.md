# 社区多方块结构声明适配

GTMB 使用结构本身的 StructureLib/GregTech 声明确定候选和几何，不维护社区机器名称生产白名单，也不把所有注册方块自动视为合法结构材料。注册信息只有在明确声明指定的集合内才用于展开候选，重叠格仍须取各声明的交集。

`HologramDeclarationWrappers` 透明读取 `withChannel`、`onElementPass`、`onElementFail`、`withDescription`、已审查的 GT `activeCoils`/`noSurvivalAutoplace`/玻璃描述代理，以及已初始化的标准 lazy 元素。读取时不执行机器检查、仓室 adder、计数器、tier getter/setter。固定 Casings/GT++ frame 的旧只读 lazy 恢复继续保留。

GT `chainAllGlasses`、`chainAllCasings`、`chainItemPipeCasings` 工厂由 RETURN Mixin 记录来源。记录时不初始化 lazy，预览时才从这些工厂明确使用的 `GlassTier`、`CasingTier`、管道机壳列表建立只读声明副本，保留 native channel 和玻璃主值截断规则。源码和内部工厂类名改变时应核对精确 API 签名；它们不是未知模组私有 lambda 的自动执行许可。

`HologramCapture` 继续使用 native `construct` 捕获调用 `buildPiece` 的真实 piece、offset、trigger，并由标准 `IStructureDefinition.iterate` 处理 shape、transpose、ExtendedFacing。任意自行绘制粒子或直接调用接口 default `buildOrHints`、绕过 GT `buildPiece` 的 construct 路径暂不宣称已覆盖。没有安全声明适配的 `defer`、条件 predicate、context switch、trigger transform 在提示阶段也不执行其未知 controller callback。

第三方可通过公开 `HologramDeclarationAdapters` 扩展自己的声明工厂：

```java
HologramDeclarationAdapters.register(nativeElement,
    new HologramDeclarationAdapters.Declaration(readOnlyDeclaration));
```

也可为确定的 wrapper 类注册 `registerReader(ExactWrapper.class, reader)`。只匹配该精确类，不推断子类的结构合法性。reader 应仅读取声明数据，返回等价标准声明；不得调用未知 lazy supplier、check、adder、pass/fail callback、placement，或修改 controller/world。需要延迟读取注册集合时，`Declaration` 的 Supplier 应由适配代码明确提供，而不是转交原模组未知 controller factory。可选 signal projection 收到 trigger 副本，不能改变原生触发物品。

未知私有 NBT、动态模型、任意 tier extractor 接受集合、条件判定和未初始化的 controller-dependent lambda 不能由通用注册表推断。它们保持候选不完整或受保护，作者可通过 SPI 给出审查过的声明。声明副本也必须走现有候选、TE 保护和共享格交集规则，不授予额外世界写入许可。

代表性源码 schema 已锁定在 `src/test/resources/hologram/community-declarations-v004.json`：

| 源码版本 | 样本 | 声明模式 |
| --- | --- | --- |
| GNL `0.2.7-rc2` | `LargeSteamMixer` | Steam 基类、多个 hatch chain、tiered/玻璃 lazy |
| GNL `0.2.7-rc2` | `PrecisionAssembler` | machine/unit 独立通道、tiered、frame、玻璃 lazy |
| GNL `0.2.7-rc2` | `BloodSoulSacrificialArray` | wildcard 标准工厂与私有 CommonElements；后者允许保持部分识别 |
| TST `0.8.0-RC2.3` | `TST_LargeSteamForgeHammer` | Steam 基类、动态 mName piece、tiered hatch chain |
| TST `0.8.0-RC2.3` | `TST_Disassembler` | component casing channel、tiered、固定 GT 方块 |
| TST `0.8.0-RC2.3` | `GT_TileEntity_SpaceScaler` | field generator channel、量子玻璃、hatch chain |

`tools/audit_community_declarations.py --root <非官方mod源码目录>` 核对精确文件 hash 和真实 API schema；它不加载原模组，也不证明世界建造运行结果。`HologramDeclarationAdaptersRegression` 验证真实标准 API 工厂与 callback tripwire。`HologramCommunityDeclarationSmoke.runStandardFactories()` 在完整 GT runtime 验证 RETURN Mixin 的实际登记和注册集合；`runRequired(nativeClasses...)` 在原模组真正安装并注册后严格检查 detached 原生机器的声明与 construct pieces，缺少任何样本即失败，并明确打印未测试世界放置。

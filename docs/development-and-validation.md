# GTMB 构建与验证

开发基线为 GTNH 2.9.0-RC-2、GT5U 5.09.54.205、Minecraft 1.7.10。构建使用 GTNH convention 2.0.20、Jabel 与独立 mixin source set。版本入口由 `gradle.properties` 的 `RELEASE_VERSION` 和生成的 `com.miaokatze.gtmb.Tags.VERSION` 提供。

```powershell
.\gradlew.bat build --offline
.\gradlew.bat runHologramTest --offline
```

`check` 包含 `runHologramTest`。回归套件覆盖结构、蓝图、资源事务、六向旋转、大结构、材质及网络边界，并检查独立物品身份及原版无序配方。该套件使用 Forge/原版注册表，专用服验证实际 FML 注册和 `CraftingManager` 入口。

首次下载依赖时省略 `--offline`。GTMB 无 GTIT、猫猫贸易、BetterQuesting、次元口袋或周目系统依赖。容器供料使用 StructureLib 注册的原生提取接口；AE 无线供料仍验证当前玩家与终端权限，无线 EU 使用 GT5U 原生网络。

## 独立冒烟

准备脚本只从显式指定目录选取支持 jar，不复制世界、配置或整个模组目录。所需额外支持包括 IC2、AE2FluidCraft 等既有 GTNH 测试环境组件；NEI、CodeChickenCore、Waila 与 GT5U/ModularUI 所需的 Baubles 从精确版本的本机 Gradle 缓存读取。GT5U、AE、StructureLib、ModularUI 等由 Gradle运行类路径提供。

扩展兼容冒烟使用准备脚本的 `--extended-compatibility` 和 Gradle 的 `-PhologramExtendedCompatibility`，并同时指定原有冒烟或交互属性。该模式由 Gradle 唯一提供 CodeChickenCore 1.4.22、CoFHLib 2388748 与 Postea 1.2.7；准备脚本不复制这些库的物理 jar，服务端 `environment.json` 的 `runtime_managed_support` 记录确切版本，客户端沿用同一服务端回执及支持 jar 列表。Postea 1.2.7 对齐原 Prism RC-2 实例，避免旧 1.2.5 缺少 ArchitectureCraft 1.12.18 所调用的替换接口。现有 fixture 若还留有旧支持 jar，准备脚本会要求使用干净目录，不删除任何 jar；原 Prism 实例不作修改。首次解析这些依赖须省略 `--offline`。

每次准备新的 smoke 或 interaction 世界时，脚本将该独立服务端已有的 `config/gtmb/hologram-presets.json` 移至同一 fixture 的 `library-archive`，回执 `previous_library_archive` 保存原路径、归档路径与 SHA-256；测试库数据完整保留，产品预设数量限制不变。

两份准备脚本在复制支持 jar 前校验源 ZIP 与 CRC，并在目标 mods 目录内完成临时复制、大小及 SHA-256 比较后才原子替换；源和目标相同、复制不完整或替换失败时保留原目标文件。v005 首次 Socket 客户端的 GTNHExtLib 目标 jar 为零字节，依赖加载器因此未加载其请求的 java-api 2.0.1，而 1.3.5 缺少 GT5U 调用的 j9 stub；此记录说明启动失败原因，不推断文件被清空的外部过程。

首次空 jar 还进入 FalsePatternLib 按 URL 保存的负扫描缓存，恢复同路径文件后仍会跳过其依赖元数据。准备脚本成功复制含该加载器依赖元数据的 jar 后，只移除隔离 `logs/falsepattern_tmp/.depscan_cache` 中路径等同于该目标 jar 的精确 file URI；原缓存完整归档并记录 SHA-256，其余行字节保留。服务端 `environment.json` 与客户端 `client-environment.json` 的 `negative_dependency_scan_cache_invalidations` 记录精确 URI、数量和归档位置，不清空依赖目录或修改共享源缓存。

```powershell
python tools/hologram/prepare_smoke.py --mods-source <支持jar目录>
.\gradlew.bat runServer -PhologramSmoke --offline
python tools/hologram/prepare_client.py
.\gradlew.bat runClient -PhologramClientSmoke --offline
```

服务端在新建空世界执行 legacy、blueprint、AE 流体 fixture、ownership 四套检查；客户端读取该服务端生成的真实 NBT，并执行 UI/渲染检查。普通启动不会安装冒烟驱动。AE fixture 不等同于 live AE 网络验收。

真实 socket 交互需在两个进程运行：

```powershell
python tools/hologram/prepare_smoke.py --mods-source <支持jar目录> --mode interaction
python tools/hologram/prepare_client.py --mode interaction
.\gradlew.bat runServer -PhologramInteractionServer --offline
.\gradlew.bat runClient -PhologramInteractionClient --offline
```

socket 绑定 `127.0.0.1:25581`，使用独立 `build/hologram-interaction` 世界和回执目录。驱动检查真实 EBF/Muffler 声明及建造、升级、拆除后的实际世界和材料结果。

## 与 GTIT 同装验证

生产依赖不加入 GTIT。先在 GTIT 兼容分支构建 dev jar，再显式提供该 jar 及只包含 VendingMachine、VisualProspecting、Thaumcraft 三件支持 jar 绝对路径的文本文件：

```powershell
python tools/hologram/prepare_smoke.py --mods-source <支持jar目录> --coexistence --gtit-jar <GTIT兼容dev.jar> --gtit-support-classpath <支持classpath.txt>
.\gradlew.bat runServer -PhologramCoexistence --offline
```

该模式在 `build/hologram-coexistence/smoke` 执行原四套测试及第五套共装检查，核对真实 FML 注册、实际交易数据库、唯一 GTMB 引擎和隔离 Forge 旧物品映射。`--mode interaction --coexistence` 准备另一个 socket 世界；客户端使用同样参数准备，并可用 `--navigator-jar <Navigator-dev.jar>` 显式提供 VisualProspecting 的客户端支持包。服务端和客户端分别追加 `-PhologramCoexistence -PhologramInteractionServer`、`-PhologramCoexistence -PhologramInteractionClient`，回执写入独立的 `build/hologram-coexistence/interaction/receipts`。两个进程运行期间避免重新编译共享 classes。

`hologramCompatibilitySmoke` 是附加的 GoodGenerator/GT++/GT5U 实机兼容检查，需要在专用环境另行提供 GoodGenerator 和 GT++。原 GTIT 售货机样本已换为官方 EBF，该检查不要求 GTIT。

## 数据与共装

新物品是 `gtmb:neko_hologram_projector`，通信通道为 `gtmb_hologram`。服务端蓝图与分享目录分别为 `config/gtmb/hologram-presets.json`、`config/gtmb/hologram-blueprints`；客户端快捷键为 `config/gtmb-hologram-keys.properties`。世界草稿使用 `gtmb_hologram_drafts`，玩家退款队列使用 `gtmbHologramMaterialRecovery`、`gtmbHologramFluidRecovery`、`gtmbHologramEnergyRecovery`，与 GTIT 隔离。

蓝图 JSON schema、分享格式及投影仪内已有的配置 NBT 键保持兼容。GTMB 只读取和写入自身 `config/gtmb` 库，不搜索或迁移旧 GTIT 存档及预设文件。世界草稿、玩家退款队列和通信通道独立。蓝图依赖的原设备模组缺失时仍按原规则明确显示缺失并限制施工。

这里列出执行入口和覆盖范围；是否通过以本次实际日志与回执为准。

## 可选兼容接口

`com.miaokatze.gtmb.api.GTMBApi` 提供契约版本 `1`、`getProjectorItem()`、`isProjector(ItemStack)`、`isEngineOwner()` 和 `getEngineOwnerModId()`。FML init 前物品 getter 返回 null，不制造占位物品；ready 后只返回实际注册的独立物品。该 API 不暴露可变会话或绕过服务端权限/材料检查的放置入口。

兼容模组可以先用 Loader 检查 `gtmb`，再用 GameRegistry 查询独立物品或反射读取此 API。GTMB 不检查或加载 GTIT，独立承担采集、渲染和施工；GTIT 侧可选贸易及旧物品单向映射由 GTIT 自己处理。

## v0.0.5 AE 蓝图验收

功能验收已通过。最终候选记录为 raw regression 64/64（15 秒）、server 11 suites / 110 assertions（AE blueprint 445 assertions，Gradle 33 秒）、client 38 项（Gradle 2 分 6 秒）；client 覆盖 AE 原生 getters、10 组 CBC/Drive/Interface 实际材质 raster、绘制上下文恢复及 source-immutable/no-grid 预览。Socket server/client 均于 08:54:27 完成并退出 0，分别耗时 2 分 13 秒和 1 分 28 秒。

Socket 在隔离的 66-mod FML 实例中通过真实 TCP、鼠标/键盘和原版网络包运行。服务端核对 4 次 AE 原生放置、4 个真实放置事件、逐格 review 与精确材料成本；原 source、137 格库存和 pattern/card 内容保持不变。客户端覆盖真实 NEI ghost SET/CLEAR、空 pattern 拒绝、backpack pattern/card、137 格容器的空内容蓝图投影且不能取出物品、足料与缺料二次确认、普通原版配方，以及蓝图库切换和 Esc/E 退出。67×65×67（291,785 格）的自然导入期间旧操作被禁用，E 取消后旧草稿、磁盘、source/inventory 均保持且 80 个 world ticks 内不会重新打开。

AE2 适配基于 1080 已审阅的持久化 schema。蓝图不继承已审阅 host/node 的旧网络标识或 owner/security；回滚 journal 保留原始数据并恢复旧身份，运行时 Grid 生命周期仍可分配不同的 g。Service 的保留项只临时过滤预览和材料计划，来源草稿不变，施工按相同结果严格核料。Drive/Interface 新 provider 只负责视觉呈现；六种 generalized native providers 和 CBC/FMP/Protocol 的已声明 native paths 有针对性验证。GNL/TST 的验证是 source/schema audit，不等同全模组 FML 安装与施工；未知 add-on schema 不承诺自动兼容。

最后的界面修正恢复了 NEI 原生绘制坐标，使搜索条回到屏幕底部；容器预留底部 24 px，低高度窗口采用 212 px 的紧凑布局。GL 截图与真实 Socket 客户端均确认矩阵和 footer 可见，该改动不改变蓝图数据、AE 规则或施工行为。验证环境未人工复现用户原始 Prism 巨大存档；原始保存故障仍未重现。详细候选日志与交付信息见 GTMB plan 的验收记录。

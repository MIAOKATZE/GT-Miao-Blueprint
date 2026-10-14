# 源质罐内容与支付

Thaumcraft 4.2.3.5 的普通罐和虚空罐使用 tile id `TileJar` / `TileJarVoid`。世界 NBT 的 `Aspect` 是源质 tag，`Amount` 是 short，原生容量为 64 点；`AspectFilter` 是标签配置。物品罐 `ItemJarFilled` 的内容使用 `Aspects` compound list，每条是字符串 `key` 和整数 `amount`，不能拿世界罐字段读取物品内容。

蓝图保留的最终世界罐内容参与 `HologramBlueprintResources.Plan` 计价。预览和报价不修改源罐或模板。最终内容非空时，源质必须已注册且数量在 1 到 64 点之间；未知源质、不完整或超量载荷会拒绝施工。显式忽略内容或保留空罐时，内容成本为零。创造模式可以免费建造罐壳，保留的源质内容仍需要实际支付。

其它已审阅的原生源质储存格式暂时拒绝非空保留：`TileSiphon` 的小写 `amount`；普通及高级炼金炉、源质缓冲管的 `Aspects`；普通管系继承的 `type` / `amount`；晶化器的单点 `Aspect`；坩埚的 `Aspects` 和水槽载荷。作者可以明确忽略相应内容字段后建造空设备。该处理只识别具体已读原生 schema，不按字段名字扫描或删除未知模组数据。目标方块与 metadata 对应的原生 tile id 也会校验，防止自定义载荷通过修改 id 绕过计价。

本地来源仅支持已审阅的原生 `ItemJarFilled` 普通罐及虚空罐，启用背包材料来源后，从它们的真实 `AspectList` 减去点数并验证减量。未知第三方容器、安瓿瓶和晶化源质不参与部分减量。支付保留容器壳；排空无标签罐时转换为原生 `Thaumcraft:blockJar` 空壳。建造目标使用空壳材料，不能把包含 `Aspects` 的满罐模糊当作空壳消费后再写入免费的内容。

AE 来源使用已有无线终端认证，包括携带检查、绑定 key、范围和 actionable node。适配层只发现 Thaumic Energistics 的 `AEEssentiaStackType.ESSENTIA_STACK_TYPE`，通过 AE 1080 的 `WirelessTerminalGuiObject.getMEMonitor(type)` 获取真实通道。请求由该类型的 `loadStackFromNBT` 创建，使用原生 `AspectTag` / `Cnt`，再调用 monitor 的 `extractItems`，无需流体转换。每个点数的 transfer unit 由原生 stack 的 `getAmountPerUnit` 提供。

预检使用 `Actionable.SIMULATE`。预留使用 `Actionable.MODULATE`；提取返回的实际 aspect 与数量必须匹配要求。实际返回的 stack 在收费之前写入事务退款记录，所以短提取或手续费异常不会丢失已提取的点数。世界放置完成后提交；失败则通过原生 `injectItems` 退回，记录只保留原生注入返回的未接受部分。

退款重试保存在玩家 persisted NBT 的 `gtmbHologramEssentiaRecovery`，保存原生 `AspectTag` / long `Cnt` 和提取时的网络 key。原网络不可用时保留记录；更换到其它网络不会把旧退款注入新的网络。事务关闭后再次 rollback 不会重复退款。本地背包罐由施工层已有的背包快照回滚。

生产代码仅在已知可选类边界使用反射，不增加 Thaumcraft 或 Thaumic Energistics 的发布依赖。接口来源为本地 Thaumcraft 4.2.3.5 `TileJarFillable.java`、`ItemJarFilled.java`、`AspectList.java`，本地 AE 1080 `WirelessTerminalGuiObject.java` / `IMEInventory.java`，以及 [Thaumic Energistics 1.7.63 原生 stack](https://github.com/GTNewHorizons/ThaumicEnergistics/blob/1.7.63-GTNH/src/main/java/thaumicenergistics/common/storage/AEEssentiaStack.java) 和 [原生 stack type](https://github.com/GTNewHorizons/ThaumicEnergistics/blob/1.7.63-GTNH/src/main/java/thaumicenergistics/common/storage/AEEssentiaStackType.java)。GT205 的依赖坐标 1.7.63 是接口研究锚点，本轮 Prism 实例的真实版本为 1.7.64；其 [原生 stack](https://github.com/GTNewHorizons/ThaumicEnergistics/blob/1.7.64-GTNH/src/main/java/thaumicenergistics/common/storage/AEEssentiaStack.java) 仍使用相同类型与数量接口。测试运行版本以当次环境清单为准。

`HologramEssentiaRegression` 的有界通道 fixture 检查守恒、短提取、收费异常、未接受退款及重复退款，不代表真实 AE 网络运行成功。`HologramEssentiaNativeSmoke` 使用真实注册 TC 物品、原生 setter/getter 与 WorldServer 罐，检查源罐 42→10、目标 32、缺料不扣、事务后的本地快照回滚、创造模式缺料及排空返回真实空壳；它不使用 AE monitor，也不替代完整施工服务验收。真正 AE FML 世界测试必须读取原生通道的施工前后数量、目标罐内容和失败退款；以当次测试回执为准。

`HologramEssentiaMeSmoke` 在隔离 `hologram-followup-` 世界建立真实有限源质单元、ME 驱动器、有限能源单元及无线接入点，等待至少 50 个自然世界 tick 后才访问网格。该 helper 通过生产 `Context` / `Transaction` 和 `HologramService` 验证原生 monitor 的 64→32 点减量、真实目标 32 点、真实 PlaceEvent 取消后 64→32→64 退款、不重复退款，以及只有 32 点时拒绝 64 点目标。它写入 `essentia-ae-native-monitor.nbt`；仅该次运行实际 PASS 的回执证明对应场景通过。

物品资源检查不再因为名称含 `filter`、`recipe`、`ghost` 就跳过。EnderIO 原生 `blockVacuumChestTileEntity` 的根 `filter` 是可取出的整件升级，计入实际付款并保留完整物品 tag。工业蜂箱的 `usedQueen` 只有真实注册的 GT meta tile 类型匹配时按实物收费。未审核的独立物品或流体记录明确拒绝；已由原生组件报价支付的相同完整物品只能抵扣相应实际数量一次。AE ghost 字段继续由其原生审核适配器处理，GT facade 和 item-filter 配置豁免只限真实注册覆盖板及确切持久化路径。

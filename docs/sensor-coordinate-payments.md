# 传感卡坐标与付款

蓝图作者的坐标步骤支持 NC 普通／高级信息面板中已登记的传感卡。宿主 NBT 为 `IC2NCInfoPanel` 或 `IC2NCAdvancedInfoPanel`，卡片位于 `Items` 的原生 `Slot`；普通面板只处理槽 0，高级面板处理槽 0–2。目标来自卡片 `tag` 中整型 `x/y/z`，维度由面板所在世界决定。

只识别以下真实物品 class，不以包名前缀、显示名称或相似私有字段猜测适配：

- `gregtech.common.items.ItemSensorCard`，GT 的 `ItemList.NC_SensorCard`。
- `shedar.mods.ic2.nuclearcontrol.items.ItemCardReactorSensorLocation`。
- `shedar.mods.ic2.nuclearcontrol.items.ItemCardEnergySensorLocation`。
- `shedar.mods.ic2.nuclearcontrol.items.ItemCardMultipleSensorLocation`。

GT 没有安装 NC 时注册的 `GTGenericItem` 占位物品不属于此适配。GT 原生卡读取真实 `IGregTechDeviceInformation.getInfoData()` 与 `getExtraInfoData()`，将返回信息写入整型 `mString` 和字符串 `mString0`、`mString1` 等字段。适配器不伪造信息输出。

相对关系只保存相对宿主的向量，放置时随全部蓝图旋转和平移校准。绝对关系保留作者明确选择的原坐标。清除关系不保存旧目标；安装副本删除整份卡片 `tag`，同时重置已审阅的监控缓存和标题。未知附加字段或类型不符会阻止清除。原始世界卡片、审查来源副本和实际付款物品保持原数据。

付款标记 `gtmbCoordinatePayment` 是内部 NBT 字符串模式，必须对应已审阅关系；旧草稿中的 `true` 布尔标记按原相对／清除付款规则读取。未知模式、缺少关系或模式不一致会被拒绝。

| 模式 | 坐标付款核对 | 运行观测缓存 | 配置与未知数据 |
| --- | --- | --- | --- |
| `relative` | 忽略已审阅的整型 `x/y/z` | 忽略已审阅且类型正确的缓存 | 精确匹配 |
| `clear` | 忽略已审阅的整型 `x/y/z` | 忽略已审阅且类型正确的缓存 | 精确匹配；安装时按明确清除决定重置已审阅标题 |
| `absolute` | 精确匹配 `x/y/z` | 忽略已审阅且类型正确的缓存 | 精确匹配 |

纯运行观测缓存没有物品、流体或能源资产价值。共同缓存是 NC 原生整型 `state`；其余字段按具体 class 与原始 NBT 类型登记：

| 卡片 | 已审阅运行缓存 |
| --- | --- |
| GT | `mString`（int），`mString` 加数字下标（string，包含原生残留的旧信息行） |
| NC Reactor | `heat/maxHeat/output/timeLeft`（int），`reactorPoweredB/isSteam`（byte） |
| NC Energy | `energyL/maxStorageL/range_trigger_amount`（double） |
| NC Multiple | `capacity/amount/liquidId/powerType/average/production`（int），`energy/range_trigger_amount`（double），`liquidTag`（compound，原生读取目标流体的观测副本） |

`title` 是用户配置，NC Energy 的整型 `targetType` 是绑定查询配置，均不当作运行缓存免除付款核对。同名字段若类型不符也不归一；例如 `mString0` 下的 compound 会保持完整精确匹配。物品种类、damage 与实际所需数量始终严格核对。扣款和退款记录真实扣取物品的完整 NBT，不以蓝图模板替换退款数据。

源依据：GT5-Unofficial `5.09.54.205` 的 `gregtech/common/items/ItemSensorCard.java`、`BehaviourSensorKit.java` 与 `LoaderGTBlockFluid.java`；NC `2.7.16` 的 `CardWrapperImpl.java`、`TileEntityInfoPanel.processCard()` 及三种传感卡的原生 `update` 方法。GT 卡自身的 `update` 没有空目标检查；原生面板的 `processCard` 会先检查空目标并跳过卡片更新，因此测试与实际使用应经过原生面板流程。

原生面板读取库存时会给传感卡更新整型 `state`。严格放置核验共用同一份卡片与缓存登记：`sensorStateProjection` 仅投影该状态；`nativePanelProjection` 仅在两种已登记 NC 面板的 `Items` 中投影上述类型正确的观测缓存。两者都保留完整物品身份、数量、damage、槽位、最终坐标、标题、查询类型、未知配置与资产字段。面板自身的静态配置和根坐标也必须精确核验，付款的坐标归一规则不能用于判断设备已经正确放置。

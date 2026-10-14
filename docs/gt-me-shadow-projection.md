# GT ME 输入仓的模拟库存镜像

GT5-Unofficial 5.09.54.205 的 `MTEHatchInputME` 和 `MTEHatchInputBusME` 使用 `version:int=1`，以 `slots` compound list 保存最多 16 个筛选槽。每条包含 `index:int`、原生 FluidStack 或 ItemStack `config`，以及可选的 `extracted` 和 `extractedAmount:int`。

`config` 是筛选样本。`extracted` 是 ME 库存的模拟视图，实际资源没有在查看时扣除；配方执行后才根据 `extractedAmount` 与当前视图数量的差额执行原生 `poweredExtraction`。因此复制这些字段不能计为已经持有的物资，也不能把它们直接恢复到新的仓室。

`HologramGtMeShadow.project(NBTTagCompound)` 返回独立副本，只处理 tile id `BaseMetaTileEntity` 且 `mID` 实际注册为上述两个确切类的载荷。GT .205 在 `LoaderGTBlockFluid.java:907` 注册此 tile id。处理会审核 version、列表类型、索引范围及唯一性、样本与镜像栈类型、数量类型和注册身份；未知槽字段、格式版本或未迁移的旧版镜像会拒绝处理。

审核通过后保留 `config`，删除每槽的 `extracted` 与 `extractedAmount`。该投影用于作者完成选择后的模板保存、资源报价及原生安装之前。源模板、实际机器和 ME 网络库存都不会被改写。报价只对该确切 owner/version/path 的 `slots/<i>/config` 免材料，独立真实 `Inventory` 与流体载荷继续沿用精确资源计价。

旧 `version=0` 的 `storedFluids`，以及物品仓的旧 `sizes` / `Inventory` 混合配置与镜像格式，不作猜测迁移。作者可以显式忽略相关槽内容；完全省略 ME 槽格式且没有旧镜像载荷的空 host 会转成空 `version=1`。其它模组或 GT 子类不因为使用 `slots`、`config` 字段而获得配置豁免。

原文锚点：GT .205 `MTEHatchInputME.java:785` / `:1044` 保存格式，`:456` 配方后的真实网络扣取；`MTEHatchInputBusME.java:297` / `:1021` 保存格式，`:780` 配方后的真实网络扣取。

`HologramGtMeShadowRegression` 调用上述原生 `Slot.writeToNBT`，并使用明确标注的、未运行网络的确切类注册 fixture，核对投影、配置保留、独立物资计价和异常格式拒绝。真实 FML 产线验收还需证明导出模板中镜像为空、来源网络库存不变、新仓室运行库存为空，以及补料后真实配方由网络实扣；以当次运行回执为准。

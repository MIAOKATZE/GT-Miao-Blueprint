<p align="center">
  <img src="README/GTMB.png" width="1100" alt="GT-Miao-Blueprint · GTMB · 猫猫蓝图">
</p>

<h1 align="center">GT-Miao-Blueprint</h1>
<p align="center"><strong>猫猫蓝图 · GTMB</strong><br>给 GregTech New Horizons 的结构规划、蓝图编辑与分享工具。</p>

<p align="center">
  <a href="LICENSE"><img alt="AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-blue"></a>
  <img alt="Minecraft 1.7.10" src="https://img.shields.io/badge/Minecraft-1.7.10-blue">
  <img alt="GTNH 2.9.0 RC-2" src="https://img.shields.io/badge/GTNH-2.9.0%20RC--2-orange">
  <img alt="Private development" src="https://img.shields.io/badge/Status-Private%20Development-cyan">
</p>

GTMB 将猫猫投影仪从 GT-Interesting-Thing 中独立出来。它可以直接在 GTNH 整合包中运行，**不需要安装 GTIT**。手持投影仪即可查看机器结构、规划施工，或把一片建筑区域保存为可以编辑和分享的蓝图。

GTMB is a standalone structure planning and blueprint mod for GregTech New Horizons. GTIT is optional; the projector, editor, renderer and placement service belong to GTMB.

## 获取与安装

当前处于私有开发阶段，尚未发布公开 Release。使用本项目构建或本轮交付的主 jar，将其放入 GTNH 实例的 `mods` 目录；客户端与服务器均需要安装 GTMB。

首版开发基线为 **GTNH 2.9.0 RC-2**、GregTech `5.09.54.205`、StructureLib `1.4.45` 和 AE2 `rv3-beta-1080-GTNH`。旧整合包版本尚未作为本项目独立验证的兼容范围。

**制作投影仪：** 将 **1 个 GTNH「多方块结构全息投影仪」**放入合成网格，无序合成为 **1 个猫猫投影仪**。输入来自 StructureLib 的原生物品，不需要猫猫币或 GTIT。

## 可以做什么

- **结构规划：** 查看目标、现场和差分，调整结构信道、仓室、朝向及施工范围，核对材料后补建、替换或拆除。
- **区域蓝图：** 框选世界中的建筑区域，识别其中的多个结构，分别控制、选择和编辑。
- **蓝图编辑：** 多选、立体框选、复制粘贴、移动轴、全局或局部提示词，以及排除不需要放置的方块。
- **完整记录：** 可选保存设备 NBT、覆盖板和覆盖板数据；放置时校准所有者，并核对库存、流体和无线电网 EU。
- **兼容与分享：** 查看来源 Mod、版本差异和缺失方块；通过服务端 JSON 文件导入、导出蓝图。

## 快速操作

| 操作 | 用途 |
| --- | --- |
| 右键多方块控制器 | 读取结构并打开工作台。 |
| Shift + 右键 | 打开投影仪自己的工作台，切换「结构 / 蓝图」。 |
| Shift + Alt + 右键 | 开始或结束世界区域框选。 |
| 框选模式中左键 / 右键 | 设置第一点 / 第二点；按住 Alt 使用玩家坐标。 |
| Ctrl + 点击 / Shift + 拖动 | 多选 / 立体框选预览中的方块。 |
| Alt + 右键 | 选择蓝图并核对材料；进入世界预览后，再次按下锁定位置。 |
| 锁定后 Shift + 右键 | 重开工作台并确认施工。 |

详细行为、保存选项与编辑快捷键见 [投影仪操作说明](docs/neko-hologram-projector.md)。

## 材料与设备数据

保存默认包含可写的设备 NBT 根条目，用户可逐项关闭；也可以在本次放置时临时取消条目，而不修改保存的蓝图。库存从已启用的物品来源提取，流体从 AE 提取，储能 EU 从 GT 无线电网扣除。最终施工时重新检验并实际扣除，失败时按事务回执退款。

无法正确计价的未知存储格式，以及当前 AE 接口不能保留标签的流体，会明确提示取消相应条目。缺料可以二次确认，只施工有实际材料的部分。AE 自动下单保留扩展接口，首版不包含内置自动合成下单。

## 配置、蓝图与联动

GTMB 的设置、预设库和分享文件位于服务端 `config/gtmb/`；客户端快捷键位于客户端的 GTMB 配置文件中。分享文件由服务端管理，跨服务器分享时需要自行传递 JSON。

GTIT 已将投影仪功能解离。安装支持 GTMB 的 GTIT 版本时，猫猫机提供 **1 枚普通猫猫币 → 1 个猫猫投影仪**的可选兑换；未安装 GTMB 时不会出现这个兑换条目。

同装时仅 GTMB 提供投影渲染、采集与施工引擎，GTIT 不再保留另一套实现。旧 `gtit:neko_hologram_projector` 物品可在 GTMB 在场时映射为新物品；旧存档和 GTIT 预设不做专门迁移。普通旧版蓝图 JSON 的格式兼容不等于自动迁移旧配置目录。

供其它模组使用的只读接口位于 `com.miaokatze.gtmb.api.GTMBApi`，提供注册物品与引擎归属查询。放置权限、验料和事务始终由服务端控制。

## 开发与验证

```powershell
.\gradlew.bat build
```

具体回归、独立客户端与服务器运行方式见 [开发与验证](docs/development-and-validation.md)。只将有实际回执的检查写为通过；源 GTIT 的验证记录不代表迁移后的 GTMB 已经验证。

当前版本已通过 47 项手写回归、独立服务端四套检查、17 项客户端 UI 检查，以及单装和 GTIT 同装的真实 socket 建造、升级、拆除。共装额外核验实际注册表、1 币交易、唯一引擎和 Forge 旧物品映射。测试使用隔离世界；AE 流体测试使用认证 monitor fixture，尚未代替完整整合包中的 live AE 网络验收。

开发记录单独保存在 [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan) 仓库，本地落点为 `plan/`。它包含架构摘要、迁移范围、验收矩阵和每轮 workflow；不打包进模组 jar。

## 许可与来源

本项目采用 [AGPL-3.0](LICENSE)，由 GTIT 的猫猫全息投影仪模块迁移并独立维护。README 抬头图使用内置 imagegen 工具生成；透明原图位于 [README/GTMB.png](README/GTMB.png)，制作说明和提示词保存在计划仓库。

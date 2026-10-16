<p align="center">
  <img alt="MIAO Blueprint" src="README/GTMB.png">
</p>

<h1 align="center">GT-Miao-Blueprint</h1>
<p align="center"><strong><em>猫猫蓝图 · GTNH 结构与蓝图工具</em></strong><br><strong><em>Miao Blueprint · Structure & Blueprint Tools for GTNH</em></strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg"></a>
  <img alt="Minecraft 1.7.10" src="https://img.shields.io/badge/Minecraft-1.7.10-blue.svg">
  <img alt="Forge 10.13.4.1614" src="https://img.shields.io/badge/Forge-10.13.4.1614-blue.svg">
  <a href="https://github.com/GTNewHorizons/GT-New-Horizons-Modpack"><img alt="GTNH 2.9.0 RC-2" src="https://img.shields.io/badge/GTNH-2.9.0%20RC--2-orange.svg"></a>
  <a href="https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases"><img alt="Releases" src="https://img.shields.io/badge/Downloads-Releases-green.svg"></a>
</p>

**[Homepage / 我的主页](https://miaokatze.com/) · [Blueprint Library / 蓝图站](https://miaokatze.com/blueprints)**

A standalone GTNH mod for **structure previews, blueprint editing and construction with your materials**. Capture machines or world regions, optionally group quantum nodes, then prepare blueprints for private use or sharing.

Move existing builds with the persistent **Cut** queue for your player in the current save, preserving blocks, machine data and inventories.

独立的 GTNH **结构预览、蓝图编辑与材料施工**工具。捕获机器或框选区域，按需进行量子分组，再按私用或分享方式处理蓝图。

通过当前存档中该玩家的持久化**剪切缓存队列**搬移现有建筑，保留方块、机器数据与库存。剪切缓存独立保存，不参与蓝图库分享。

## Downloads & Requirements / 下载与依赖

Target: **GTNH 2.9.0 RC-2 / Minecraft 1.7.10 / Forge 10.13.4.1614**. Install GTMB on **both client and server**. Download packaged jars from [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases) when available.

目标：**GTNH 2.9.0 RC-2 / Minecraft 1.7.10 / Forge 10.13.4.1614**。**客户端与服务端均需安装** GTMB，正式成品见 [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases)。

Dependencies / 必需依赖：GregTech、StructureLib、Applied Energistics 2、GTNHLib、ModularUI、ModularUI2（included in GTNH / 整包已提供）。**GTIT is optional / GTIT 为可选模组。**

## Neko Blueprint Hologram Projector / 猫猫蓝图全息投影仪

Shapeless recipe: **1 StructureLib Hologram Projector → 1 Neko Blueprint Hologram Projector**. With compatible GTIT, the Neko Vending Machine also exchanges **1 ordinary Neko Coin** for one projector.

无序配方：**1 个 StructureLib 多方块结构全息投影仪 → 1 个猫猫蓝图全息投影仪**。同装兼容版本 GTIT 时，也可在猫猫机用 **1 枚普通猫猫币**兑换。

## Quick Start / 快速使用

| Action / 操作 | Result / 用途 |
| --- | --- |
| Right-click / 普通右键 | Open the workspace; aim at a real controller to import its structure. 打开工作台；对准真实控制器则导入其结构。 |
| Shift+right-click / Shift+右键 | Hold Shift and right-click to select Blueprint or Cut; right-click again cycles, releasing Shift enters the mode. 按住 Shift 右键打开「蓝图／剪切」切换器，继续右键切换，松开 Shift 进入。蓝图模式左右键标两角，再次 Shift+右键导入。 |
| Cut / 剪切 | Mark two corners and confirm to move the original blocks into persistent storage. Left/right-click marks the corners. 左右键标两角，确认后原样剪切到持久暂存。 |
| Stored cuts / 剪切暂存 | Right-click opens storage; Alt+right-click restores the selected cut; Shift+right-click previews placement; Shift+Alt+right-click cuts another region. Alt+A/D selects an entry. 右键打开暂存，Alt+右键撤销，Shift+右键放置，Shift+Alt+右键继续剪切，Alt+A/D 切换条目；放置或撤销成功后消费。 |
| Cut placement / 剪切放置 | Right-click locks the preview; right-click again opens confirmation. R rotates around Y, Ctrl+R around X; Alt+R opens XYZ rotation, mirror and facing controls. 右键锁定预览，再次右键确认；R 绕 Y 轴旋转，Ctrl+R 绕 X 轴旋转，Alt+R 打开三轴旋转、镜像与朝向面板。撤销恢复剪切时的原位、原姿态。 |
| Position / 选位置 | Selection uses the hit block; the world preview attaches to its face. Without a hit, it floats; Alt forces floating, Alt+wheel adjusts 0–64 blocks (0 = your feet). 框选取命中方块，世界预览贴合命中表面；无命中时悬空，Alt 强制悬空，Alt+滚轮调距 0–64 格（0 为脚下）。 |
| Place / 放置 | Select a blueprint and click Place; right-click locks the green preview cyan, then right-click to review materials and confirm. Esc cancels. 选择蓝图并点「放置所选」；右键将绿色预览锁为青色，再右键核对材料并确认，Esc 取消。 |

Cut storage belongs only to the current save and player; it stays outside the blueprint library and sharing workflow.

Cut placement previews show translucent native models with block outlines.

剪切暂存仅属于当前存档与玩家，不进入蓝图库或分享流程。

剪切放置使用带方块边框的半透明原生模型预览。

Manual sharing: copy the **same-basename JSON + PNG pair** into the recipient's game-directory `MiaoBlueprints/`; use the Client/Server library switch for uploads and downloads.

手动分享：将**同名 JSON + PNG 双文件**一起放入对方游戏目录的 `MiaoBlueprints/`；公库上传、下载使用蓝图库「客户端／服务器」切换。

Detailed controls, private/shared processing and resource rules: [GTMB Guide / 完整操作、私用分享与资源规则](https://miaokatze.com/wiki?mod=gtmb). Find and share blueprints at the [Blueprint Library / 蓝图站](https://miaokatze.com/blueprints).

Community integrations / 社区扩展：[Public API & module contracts / 公共 API 与模块契约](API.md).

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE) · Origin / 源自 GT-Interesting-Thing · Independently maintained as / 现独立维护为 GTMB · Development notes / 开发记录：[GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

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

独立的 GTNH **结构预览、蓝图编辑与材料施工**工具。捕获机器或框选区域，按需进行量子分组，再按私用或分享方式处理蓝图。

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
| Shift+right-click / Shift+右键 | Start region selection; left/right-click marks two corners. Shift+right-click again imports it. 开始框选，左右键分别标两角，再次 Shift+右键导入。 |
| Position / 选位置 | Selection uses the hit block; the world preview attaches to its face. Without a hit, it floats; Alt forces floating, Alt+wheel adjusts 0–64 blocks (0 = your feet). 框选取命中方块，世界预览贴合命中表面；无命中时悬空，Alt 强制悬空，Alt+滚轮调距 0–64 格（0 为脚下）。 |
| Place / 放置 | Select a blueprint and click Place; right-click locks the green preview cyan, then right-click to review materials and confirm. Esc cancels. 选择蓝图并点「放置所选」；右键将绿色预览锁为青色，再右键核对材料并确认，Esc 取消。 |

Manual sharing: copy the **same-basename JSON + PNG pair** into the recipient's game-directory `MiaoBlueprints/`; use the Client/Server library switch for uploads and downloads.

手动分享：将**同名 JSON + PNG 双文件**一起放入对方游戏目录的 `MiaoBlueprints/`；公库上传、下载使用蓝图库「客户端／服务器」切换。

Detailed controls, private/shared processing and resource rules: [GTMB Guide / 完整操作、私用分享与资源规则](https://miaokatze.com/wiki?mod=gtmb). Find and share blueprints at the [Blueprint Library / 蓝图站](https://miaokatze.com/blueprints).

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE) · Origin / 源自 GT-Interesting-Thing · Independently maintained as / 现独立维护为 GTMB · Development notes / 开发记录：[GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

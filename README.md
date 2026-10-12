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

A standalone GregTech New Horizons mod for **previewing structures, editing blueprints and building with your materials**. Save a machine or a selected world region as a blueprint, then edit and share it. GTIT is optional.

一个独立的 GregTech New Horizons 模组，用于**结构预览、蓝图编辑和材料施工**。把机器或框选的建筑区域保存为蓝图，再编辑、放置和分享。无需安装 GTIT。

## Downloads & Requirements / 下载与版本需求

Packaged jars will be available on [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases). The first release is being prepared for **GTNH 2.9.0 RC-2 / Minecraft 1.7.10**. Install GTMB on both the client and server.

成品 jar 将通过 [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases) 发布。首版正在准备，面向 **GTNH 2.9.0 RC-2 / Minecraft 1.7.10**。客户端和服务端均需安装 GTMB。

## Neko Projector / 猫猫投影仪

Craft **one StructureLib Hologram Projector into one Neko Projector** with a shapeless recipe. With a compatible GTIT version installed, the Neko Vending Machine also offers one projector for **one ordinary Neko Coin**.

将 **1 个 StructureLib 多方块结构全息投影仪无序合成为 1 个猫猫投影仪**。同装兼容版本的 GTIT 时，也可在猫猫机用 **1 枚普通猫猫币**兑换。

- **Structures / 结构** — Preview and adjust machine structures; plan construction, replacement or dismantling. 预览和调整机器结构，规划补建、替换或拆除。
- **Blueprints / 蓝图** — Capture regions, select and move blocks, edit hints, exclude blocks, and import or export JSON. 框选区域、多选与移动方块、编辑提示词、排除放置，并导入或导出 JSON。
- **Machine data / 设备数据** — Choose NBT and cover entries to save; check materials and resources before placement. 按需保存 NBT 与覆盖板数据，放置前核对材料和资源。
- **Compatibility / 兼容信息** — View missing blocks and source mod version differences. 查看缺失方块及来源模组的版本差异。

## Quick Start / 快速使用

| Action / 操作 | Purpose / 用途 |
| --- | --- |
| Shift + right-click / 右键 | Open the Structure / Blueprint workspace. 打开「结构 / 蓝图」工作台。 |
| Shift + Alt + right-click / 右键 | Start or finish world region selection. 开始或结束世界区域框选。 |
| Left / right-click in selection mode / 框选时左键、右键 | Set the two corners; hold Alt to use your position. 设置两个角点；按住 Alt 使用自身坐标。 |
| Alt + right-click / 右键 | Select a blueprint and check materials, then enter placement preview. 选择蓝图并核对材料，再进入放置预览。 |

See the [projector guide](docs/neko-hologram-projector.md) for detailed controls. Blueprints and shared JSON files are stored under the server's `config/gtmb/` directory.

详细操作见[投影仪说明](docs/neko-hologram-projector.md)。蓝图与分享 JSON 保存在服务端 `config/gtmb/` 目录。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE). The projector originated in GT-Interesting-Thing and is now maintained independently as GTMB. Development notes are kept in [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan).

采用 [AGPL-3.0](LICENSE)。投影仪源于 GT-Interesting-Thing，现独立为 GTMB 维护。开发记录见 [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

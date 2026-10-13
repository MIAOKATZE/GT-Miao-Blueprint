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
- **AE blueprint data / AE 蓝图数据** — Safely mark supported AE2 slots, review sanitized cell contents and installation materials, then preview and place with the selected retention rules. 安全标记已适配的 AE2 槽位，查看元件清理与施工材料，并按保留选项预览和放置。
- **Compatibility / 兼容信息** — View missing blocks and source mod version differences. 查看缺失方块及来源模组的版本差异。

## Quick Start / 快速使用

| Action / 操作 | Purpose / 用途 |
| --- | --- |
| Right-click / 普通右键 | Open the workspace on its remembered tab; right-click a real controller to import its structure directly. 打开工作台时恢复最近使用的并列标签；对准真实控制器右键仍优先直接导入结构。 |
| Shift + right-click / Shift + 右键 | Use Shift+right-click once to enter world-region selection and a second time to finish the box; Enter is not intercepted. 按 Shift+右键开始世界区域框选，再按一次完成框选；Enter 不拦截。 |
| Left / right-click; Alt + left / right-click / 左右键；Alt+左右键 | Set the two corners; hold Alt to use your position. Esc cancels selection. 设置两个角点；按住 Alt 使用玩家坐标；按 Esc 取消。 |
| Select a blueprint, then Place / 选择蓝图后点「放置」 | The ghost follows the camera look direction when no block is hit; use the wheel to set distance from 2 to 64 blocks. When the ghost preview is green, right-click locks it and turns the border cyan; right-click again opens the separate materials and placement confirmation area. While locked, Shift+right-click returns to the movable preview. 没有命中方块时，ghost 沿镜头视线悬空显示；滚轮可调 2–64 格距离。绿色 ghost 预览时普通右键锁定，边框转青；再次右键打开独立的材料与放置确认区。锁定时 Shift+右键返回可移动预览。 |
| Alt + right-click / Alt+右键 | Start a ghost for the last-used blueprint directly. Alt+右键直接为上一张蓝图生成 world ghost。 |
| Placement review / 放置预检 | Review fixed coordinates, NBT and materials, then confirm once when fully supplied. If short, a second confirmation places only ready blocks. 核对坐标、NBT 和材料；足料时确认一次施工，缺料时需再次明确确认，且只放置材料足够的方块。 |
| Ctrl+A, T, Ctrl-click, Shift-drag / Ctrl+A、T、Ctrl+点击、Shift+拖动 | Ctrl+A selects all; hold T and click to select the same block type within the current structure or loose-block area, including the exact GT MetaTile ID. Ctrl-click adds cells; Shift-drag selects a region. Ctrl+A 全选；按住 T 点击选择当前结构或非结构区域内的同种方块，GT 机器按实际 MetaTile ID 区分。Ctrl 多选，Shift 拖动框选。 |
| Grade and candidates / 等级与候选 | Grade changes refresh candidates within the declared family. The current family selection stays active; invalid options are marked. 等级变化会按机器声明家族更新候选，保留当前家族选择，并标明无效选项。 |
| Hints and save / 提示与保存 | Saved hints keep their data; icons and hover text are generated from it. Update keeps the current ID; Save As creates a new ID, and Delete asks for confirmation. 提示数据随蓝图保存，图标和悬停说明由数据生成。更新沿用当前 ID，另存生成新 ID，删除前需确认。 |
| Esc / E | Close a modal first; otherwise leave the workspace. E still types in text fields. 先关闭弹窗；否则退出工作台。文字输入框内 E 仍正常输入。 |

The Structure and Blueprint tabs sit side by side and remember the last-used page. Right-clicking a real controller still imports its structure directly. The library uses flat rows with a selected row and secondary details; actions have separate buttons. Observing or selecting a blueprint does not reduce its placement scope. See the [projector guide](docs/neko-hologram-projector.md) for detailed controls. The workspace has a separate right panel and adapts to Minecraft GUI scale. The saved blueprint library is on the server at `config/gtmb/hologram-presets.json` (64 MiB library limit, 8 MiB per blueprint). Imported and exported share files are under `config/gtmb/hologram-blueprints/`.

「结构」和「蓝图」是并列标签，普通右键打开工作台时恢复最近使用页；对准真实控制器右键仍优先直接导入结构。蓝图库用扁平列表显示选中行和补充信息，操作使用独立按钮；观察或选择蓝图不会缩小其放置范围。详细操作见[投影仪说明](docs/neko-hologram-projector.md)。工作台右侧为独立面板，并会按 Minecraft GUI 缩放适配。蓝图库保存在服务端 `config/gtmb/hologram-presets.json`（全库上限 64 MiB，单份蓝图上限 8 MiB）；导入、导出的分享文件位于 `config/gtmb/hologram-blueprints/`。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE). The projector originated in GT-Interesting-Thing and is now maintained independently as GTMB. Development notes are kept in [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan).

采用 [AGPL-3.0](LICENSE)。投影仪源于 GT-Interesting-Thing，现独立为 GTMB 维护。开发记录见 [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

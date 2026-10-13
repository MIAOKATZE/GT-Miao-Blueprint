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
- **Compatibility / 兼容信息** — View missing blocks and source mod version differences. Lit redstone lamp material checks use an ordinary redstone lamp item; preview preserves the captured block state, and vanilla redstone mechanics determines lit state after placement. 查看缺失方块及来源模组的版本差异。材料核验按普通红石灯物品计算；预览保留捕获的方块状态。实际放置后，原版红石机制决定灯的亮灭。

## Quick Start / 快速使用

| Action / 操作 | Purpose / 用途 |
| --- | --- |
| Right-click / 普通右键 | Open the workspace on its remembered tab; right-click a real controller to import its structure directly. 打开工作台时恢复最近使用的并列标签；对准真实控制器右键仍优先直接导入结构。 |
| Shift + right-click / Shift + 右键 | Use Shift+right-click to enter world-region selection. After setting the corners, use Shift+right-click again to finish and import an editable draft; Enter is not intercepted. 按 Shift+右键进入世界区域框选。设置好角点后，再按 Shift+右键完成框选并导入可编辑草稿；Enter 不拦截。|
| Left / right-click; Alt + left / right-click / 左右键；Alt+左右键 | Left-click sets the first corner; right-click sets the second. Alt uses the camera ray at the selected distance; distance 0 uses your feet. Esc cancels selection. 左键设置首角，右键设置末角；Alt 按选定距离取镜头射线坐标；距离 0 取脚下；按 Esc 取消。|
| World regions / 世界区域 | Shift+right-click enters region selection. Left-click sets the first corner and right-click sets the second. Hold Ctrl while setting the first corner to add later boxes to the existing selection; hold Ctrl+Shift to subtract the outlined box. The mode for that region locks when its first corner is set. After both corners are set, Ctrl+Z undoes the previous combined edit. Boxes may be disjoint; subtraction removes only the selected intersection and does not expand the positive bounding box. Shift+right-click again completes the selection and imports an editable draft. Shift+右键进入框选，左键设置首角、右键设置末角。设置首角时按住 Ctrl，后续框选会加入现有范围；按住 Ctrl+Shift 则减去所框区域。模式在该次首角确定时锁定；两角完成后，Ctrl+Z 可撤销上一步组合。多个框选可不相邻，减选仅移除已选交集，不扩大正选包围盒。再次 Shift+右键完成并导入可编辑草稿。|
| Select a blueprint, then Place / 选择蓝图后点「放置」 | The ghost follows the camera look direction when no block is hit. While selecting an area or moving the preview, only Alt+wheel adjusts distance from 0 to 64 blocks; 0 is at your feet. Without Alt, the ordinary wheel still changes the hotbar item. When the ghost preview is green, right-click locks it and turns the border cyan; right-click again opens the separate materials and placement confirmation area. While locked, Shift+right-click returns to the movable preview. 无方块命中时 ghost 沿镜头方向悬空显示。框选及移动预览时，仅 Alt+滚轮调整 0–64 格，0 为脚下；不按 Alt 时，普通滚轮仍切换快捷栏物品。绿色 ghost 预览时普通右键锁定，边框转青；再次右键打开独立的材料与放置确认区。锁定时 Shift+右键返回可移动预览。|
| Alt + right-click / Alt+右键 | Start a ghost for the last-used blueprint directly. Alt+右键直接为上一张蓝图生成 world ghost。 |
| Arrows; Alt+Up/Down / 方向键；Alt+上下 | While the world preview is locked cyan, move one block in a horizontal direction relative to the camera; Up moves forward, Down moves back, Left/Right strafe. Alt+Up/Down moves vertically. Hold to repeat slowly, then right-click to review the new position and materials again. 青色锁定预览时，方向键按镜头水平朝向微调一格：上键向前，下键向后，左右键侧移；Alt+上下调整高度。长按可慢速连续微调，之后右键重新核对新位置与材料。 |
| Placement review / 放置预检 | Review fixed coordinates, NBT and materials, then confirm once when fully supplied. If short, a second confirmation places only ready blocks. 核对坐标、NBT 和材料；足料时确认一次施工，缺料时需再次明确确认，且只放置材料足够的方块。 |
| Ctrl+A, T, Ctrl-click, Shift-drag / Ctrl+A、T、Ctrl+点击、Shift+拖动 | Ctrl+A selects all; hold T and click to select the same block type within the current structure or loose-block area, including the exact GT MetaTile ID. Ctrl-click toggles multi-selection; Shift-drag selects a box in the editor. Ctrl+A 全选；按住 T 点击选择当前结构或非结构区域内的同种方块，GT 机器按实际 MetaTile ID 区分。Ctrl+点击切换编辑器多选；Shift+拖动在编辑器内框选。|
| De-identification / 去标识 | Use one-click de-identification and choose a data level from 0 to 10 before saving; a new raw draft recommends level 1, and an explicit choice is remembered per blueprint. Level 1 clears reviewed network identity and leaves typed owner slots for the placer. Level 0 preserves the recorded owner; data owned by another player blocks use and recommends level 1. Removing a world blocker is a separate server-checked action. 保存前一键去标识并选择 0–10 级身份处理；新 raw 草稿推荐等级 1，明确选择会按蓝图记忆。等级 1 清理已审阅网络身份并留空有类型的来源所有者字段供放置者填写。等级 0 保留来源身份并支持原所有者自用；他人所有者数据会阻止使用并建议等级 1。未 review 不恢复 raw 身份数据；移除世界阻塞物是单独的服务端校验操作。|
| Grade and candidates / 等级与候选 | Grade changes refresh candidates within the declared family. The current family selection stays active; invalid options are marked. 等级变化会按机器声明家族更新候选，保留当前家族选择，并标明无效选项。 |
| Hints and save / 提示与保存 | Saved hints keep their data; icons and hover text are generated from it. Update keeps the current ID; Save As creates a new ID, and Delete asks for confirmation. 提示数据随蓝图保存，图标和悬停说明由数据生成。更新沿用当前 ID，另存生成新 ID，删除前需确认。 |
| Esc / E | Close a modal first; otherwise leave the workspace. E still types in text fields. 先关闭弹窗；否则退出工作台。文字输入框内 E 仍正常输入。 |

The Structure and Blueprint tabs sit side by side and remember the last-used page. Right-clicking a real controller still imports its structure directly. The library uses flat rows with a selected row and secondary details; actions have separate buttons. Observing or selecting a blueprint does not reduce its placement scope. See the [projector guide](docs/neko-hologram-projector.md) for detailed controls. The workspace has a separate right panel and adapts to Minecraft GUI scale. In single-player or integrated worlds, presets remain server-owned at `config/gtmb/hologram-presets.json`. On dedicated servers, the shared public library is stored at server `config/gtmb/server-blueprints` and configured by `config/gtmb/server-blueprint-library.cfg`; each client instance keeps its local library at `config/gtmb/client-blueprints`. Imported and exported share files are under `config/gtmb/hologram-blueprints/`.

「结构」和「蓝图」是并列标签，普通右键打开工作台时恢复最近使用页；对准真实控制器右键仍优先直接导入结构。蓝图库用扁平列表显示选中行和补充信息，操作使用独立按钮；观察或选择蓝图不会缩小其放置范围。详细操作见[投影仪说明](docs/neko-hologram-projector.md)。工作台右侧为独立面板，并会按 Minecraft GUI 缩放适配。单人或集成服务器的世界预设仍由服务端保存在 `config/gtmb/hologram-presets.json`。独立服务端使用 `config/gtmb/server-blueprints` 保存共享公库，并由 `config/gtmb/server-blueprint-library.cfg` 配置；每个客户端实例的本地库位于 `config/gtmb/client-blueprints`。导入、导出的分享文件位于 `config/gtmb/hologram-blueprints/`。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE). The projector originated in GT-Interesting-Thing and is now maintained independently as GTMB. Development notes are kept in [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan).

采用 [AGPL-3.0](LICENSE)。投影仪源于 GT-Interesting-Thing，现独立为 GTMB 维护。开发记录见 [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

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

Version **0.1.2** is in development and verification; its GitHub Release is deferred. Packaged jars will be published through [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases) when released. The target is **GTNH 2.9.0 RC-2 / Minecraft 1.7.10**; install GTMB on both client and server.

**0.1.2** 正在开发与验收，GitHub Release 暂缓。正式发布时成品 jar 将通过 [GitHub Releases](https://github.com/MIAOKATZE/GT-Miao-Blueprint/releases) 提供。目标为 **GTNH 2.9.0 RC-2 / Minecraft 1.7.10**，客户端和服务端均需安装 GTMB。

0.1.2 resource rules are being implemented and verified: restored EU caches are paid from wireless EU; quantum-node and GTSWN-cover bodies are free because native terminals generate them; laser hatches require real vacuum tubes and quantum incorporation requires anchors. ME hatches retain configuration and component bodies, with no copied contents. These rules and the final four-world run are still awaiting acceptance.

0.1.2 新资源规则正在实现与验收：EU 缓存从无线 EU 实际扣能；量子节点与 GTSWN 覆盖板本体由原生终端生成，本体免费；激光仓仍需真实真空管，量子并入仍需 anchor。ME 仓室只保留配置与元件本体，不复制内容。资源支付与最终四世界验收均待实际回执；未发布的 0.1.1 不作为已交付版本。

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
| Shift + right-click / Shift + 右键 | Enter world-region selection; after setting both corners, press again to import an editable blueprint. Enter is not intercepted. 进入世界区域框选；两角设置完成后再按一次，导入可编辑蓝图；Enter 不拦截。 |
| Left / right-click / 左右键 | Mark either corner first, then the other; repeated clicks adjust that corner. Aim at a block to select it; with no block hit, select along the camera ray. Alt forces floating; Alt+wheel changes the distance, and 0 uses your feet. Switching hotbar slots suspends selection; return to the same projector to continue. Esc cancels. 任一角可先标，再标另一角；重复点击可调整该角。命中方块时选取该方块，无命中时沿镜头射线悬空；Alt 强制悬空，Alt+滚轮调距，0 取脚下。切换快捷栏槽位挂起框选，换回同一投影仪继续；Esc 取消。 |
| World regions / 世界区域 | At the first marked corner, Ctrl adds a box and Ctrl+Shift subtracts one; that pair keeps its initial mode. Ctrl+Z undoes the previous combined edit. Disjoint boxes are supported; subtraction only removes selected intersections. 首个标记角点时 Ctrl 加选、Ctrl+Shift 减选，该对角点沿用首角模式；Ctrl+Z 撤销组合编辑。可选不相邻区域，减选只移除已选交集。 |
| Select a blueprint, then Place / 选择蓝图后点「放置」 | The ghost attaches to a hit face; with no block hit it follows the camera ray. Alt forces floating even when a block is hit. While selecting an area or moving the preview, only Alt+wheel adjusts distance from 0 to 64 blocks; 0 is at your feet. Without Alt, the ordinary wheel still changes the hotbar item. When the ghost preview is green, right-click locks it and turns the border cyan; right-click again opens the separate materials and placement confirmation area. While locked, Shift+right-click returns to the movable preview. ghost 默认贴合命中方块表面，无命中时沿镜头方向悬空；Alt 强制悬空。框选及移动预览时，仅 Alt+滚轮调整 0–64 格，0 为脚下；不按 Alt 时，普通滚轮仍切换快捷栏物品。绿色 ghost 预览时普通右键锁定，边框转青；再次右键打开独立的材料与放置确认区。锁定时 Shift+右键返回可移动预览。|
| Alt + right-click / Alt+右键 | Start a ghost for the last-used blueprint directly. Alt+右键直接为上一张蓝图生成 world ghost。 |
| Arrows; Alt+Up/Down / 方向键；Alt+上下 | While the world preview is locked cyan, move one block in a horizontal direction relative to the camera; Up moves forward, Down moves back, Left/Right strafe. Alt+Up/Down moves vertically. Hold to repeat slowly, then right-click to review the new position and materials again. 青色锁定预览时，方向键按镜头水平朝向微调一格：上键向前，下键向后，左右键侧移；Alt+上下调整高度。长按可慢速连续微调，之后右键重新核对新位置与材料。 |
| Placement review / 放置预检 | Review fixed coordinates, NBT and materials, then confirm once when fully supplied. If short, a second confirmation places only ready blocks. 核对坐标、NBT 和材料；足料时确认一次施工，缺料时需再次明确确认，且只放置材料足够的方块。 |
| Ctrl+A, T, Ctrl-click, Shift-drag / Ctrl+A、T、Ctrl+点击、Shift+拖动 | Ctrl+A selects all; hold T and click to select the same block type within the current structure or loose-block area, including the exact GT MetaTile ID. Ctrl-click toggles multi-selection; Shift-drag selects a box in the editor. Ctrl+A 全选；按住 T 点击选择当前结构或非结构区域内的同种方块，GT 机器按实际 MetaTile ID 区分。Ctrl+点击切换编辑器多选；Shift+拖动在编辑器内框选。|
| Author processing / 作者处理 | The server selects required stages from the actual content; Next skips empty stages, and plain decoration can save directly. Open manual full review to confirm all six stages. 服务端按实际内容确定必要阶段，「下一步」跳过空阶段，纯装饰可直接保存；也可打开「手动完整六步核对」，主动逐步审查全部阶段。 |
| WASD preview camera / WASD 预览摄像机 | W/S moves forward/back along the horizontal view; A/D moves sideways in both preview tabs. Text fields retain typing; camera movement leaves blueprint and world coordinates unchanged. 两个预览页均可用 W/S 沿水平视角前后移动、A/D 左右移动；输入框内正常输入文字，摄像机移动不改变蓝图或世界坐标。 |
| Quantum groups / 量子网络类群 | Grouping is optional: leave all nodes in one default network and skip grouping or click Next. Private blueprints keep the original binding without requiring a terminal or GTMB security checks. Shared blueprints use an ordinary natively bound terminal only to select a target anchor; GTMB adds no AE security pairing, BUILD check or native security validation, and does not change GTSWN ACLs or keys. 量子分类可选，不设置时全部节点使用单一默认网络，可直接跳过分类或下一步。私用保留原绑定，当场放置不要求终端、不做GTMB安全核验；分享仅用普通原生已绑定终端选择目标锚点，不额外做AE安全配对、BUILD或原生安全验证，不改GTSWN自身ACL/密钥。 |
| Coordinates and typed NBT / 坐标与 NBT | Choose clear/absolute/relative for each reviewed relationship. Auto uses actual selected cells: internal relative; external absolute for self, clear for share. Typed edits retain original types and protect identities and assets. 每个已审阅关系可选清除/绝对/相对；推荐按实际选区内相对，自用外绝对、分享外清除。数值编辑保持原类型，身份与资产字段受保护。 |
| Grade and candidates / 等级与候选 | Grade changes refresh candidates within the declared family. The current family selection stays active; invalid options are marked. 等级变化会按机器声明家族更新候选，保留当前家族选择，并标明无效选项。 |
| Hints and save / 提示与保存 | After processing, save the blueprint or its reusable flow. Overwrite selects only the target entry and asks for confirmation before saving; unsaved switches ask before discarding. Hints support light/full/none. 处理完成后保存蓝图或复用流程；覆盖模式只选目标，保存时二次确认，切换未保存蓝图须确认丢弃。自动提示词可选轻度/完整/不添加。 |
| Esc / E | Close a modal first; otherwise leave the workspace. E still types in text fields. 先关闭弹窗；否则退出工作台。文字输入框内 E 仍正常输入。 |

The Structure and Blueprint tabs sit side by side and remember the last-used page. Right-clicking a real controller still imports its structure directly. The library uses flat rows with a selected row and secondary details; actions have separate buttons. Observing or selecting a blueprint does not reduce its placement scope. See the [projector guide](docs/neko-hologram-projector.md) for detailed controls. The workspace has a separate right panel and adapts to Minecraft GUI scale. Blueprint files live in `MiaoBlueprints/` under the client's actual game directory (usually `.minecraft`) or the dedicated server's run directory. Each client stores local files as `local_<UUID>.json`; dedicated servers store public files as `public_<UUID>.json`. Single-player and integrated worlds use `MiaoBlueprints/hologram-presets.json` in the client game directory. Manual share files use the same blueprint directory. Processing presets remain at `config/gtmb/hologram-processing-presets.nbt`, client upload and source settings at `config/gtmb/client-blueprint-library.properties`, and dedicated library settings at `config/gtmb/server-blueprint-library.cfg`. Previous blueprint locations are neither read nor migrated.

「结构」和「蓝图」是并列标签，普通右键打开工作台时恢复最近使用页；对准真实控制器右键仍优先直接导入结构。蓝图库用扁平列表显示选中行和补充信息，操作使用独立按钮；观察或选择蓝图不会缩小其放置范围。详细操作见[投影仪说明](docs/neko-hologram-projector.md)。工作台右侧为独立面板，并会按 Minecraft GUI 缩放适配。蓝图文件统一放在客户端实际游戏目录（通常为 `.minecraft`）或独立服务端运行目录的 `MiaoBlueprints/` 中。客户端以 `local_<UUID>.json` 保存本地蓝图，独立服务端以 `public_<UUID>.json` 保存公共蓝图；单人或集成服务器使用客户端游戏目录下的 `MiaoBlueprints/hologram-presets.json` 集合文件。手动分享文件也在同一蓝图目录。处理流程预设仍放在 `config/gtmb/hologram-processing-presets.nbt`，客户端上传映射与上次来源设置放在 `config/gtmb/client-blueprint-library.properties`，公库设置放在 `config/gtmb/server-blueprint-library.cfg`。旧版蓝图路径不再读取，也不自动迁移。

## Paired Blueprint Files / 蓝图配对文件

Each blueprint uses a same-basename JSON and clean native 3D PNG. Save, overwrite, upload, download and deletion handle both files together; manual sharing must copy the pair. Save status updates only after verification, disk commit and the matching final server owner ACK. JSON `nativeBlueprint` uses format version 1, GTMB's independent semantic release version (current target: `0.1.2`) and DreamCore's exact `displayedModpackVersion`; unavailable pack versions are recorded as `unknown` with source `unavailable`. Old blueprint files are unsupported.

每张蓝图使用同名 JSON 与无界面的原生结构 3D PNG。保存、覆盖、上传、下载和删除均处理这对文件，手动分享须同时复制两份。完成验证、双文件落盘并收到匹配的最终服务器 owner ACK 后才更新保存状态。JSON 的 `nativeBlueprint` 使用 `formatVersion: 1`、GTMB 独立语义发布版本（当前目标为 `0.1.2`）和 DreamCore 的准确 `displayedModpackVersion`；整包版本不可用时记为 `unknown`，来源为 `unavailable`。旧蓝图文件不兼容。

GTSWN v1.8.64 previews use its native quantum-node renderer and link-node cover visuals in the workspace, world ghost and saved PNG. Supported appearances follow the captured state; rendering does not run a live node or network. See the guide for compatibility boundaries.

GTSWN v1.8.64 量子节点及链路节点覆盖板外观接入工作台、世界 ghost 和保存 PNG；按捕获状态使用原生节点渲染与覆盖板视觉接口，不运行真实节点或网络。具体兼容边界见投影仪说明。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE). The projector originated in GT-Interesting-Thing and is now maintained independently as GTMB. Development notes are kept in [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan).

采用 [AGPL-3.0](LICENSE)。投影仪源于 GT-Interesting-Thing，现独立为 GTMB 维护。开发记录见 [GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

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

Dependencies / 必需依赖：GregTech、StructureLib、Applied Energistics 2、GTNHLib、ModularUI、ModularUI2 **2.3.92-1.7.10 or newer / 及更新版本**。The target GTNH pack includes these dependencies / 目标 GTNH 整包已提供。**GTIT is optional / GTIT 为可选模组。**

## Neko Blueprint Hologram Projector / 猫猫蓝图全息投影仪

Shapeless recipe: **1 StructureLib Hologram Projector → 1 Neko Blueprint Hologram Projector**. With compatible GTIT, the Neko Vending Machine also exchanges **1 ordinary Neko Coin** for one projector.

无序配方：**1 个 StructureLib 多方块结构全息投影仪 → 1 个猫猫蓝图全息投影仪**。同装兼容版本 GTIT 时，也可在猫猫机用 **1 枚普通猫猫币**兑换。

## Quick Start / 快速使用

| Action / 操作 | Result / 用途 |
| --- | --- |
| Right-click / 普通右键 | Open the workspace; aim at a real controller to import its structure. 打开工作台；对准真实控制器则导入其结构。 |
| Mode switcher / 模式切换 | In Blueprint mode, Shift+right-click opens the selector; with no active Blueprint operation, Ctrl+Shift+right-click opens it from either mode. Hold Shift and right-click to cycle, then release Shift to enter. Choosing Cut starts corner selection. 蓝图模式下 Shift+右键打开切换器；没有正在进行的蓝图操作时，任一模式都可用 Ctrl+Shift+右键重新打开。按住 Shift 继续右键切换，松开 Shift 进入。选择「剪切」会直接开始框选。 |
| Cut / 剪切 | In Cut mode, left- and right-click mark the two corners in either order. The prompt offers Cancel, Save, or Save and don't ask again. Cancel makes no world change; Save moves the selection into persistent storage. This save-local player preference skips naming only; later cuts still require area confirmation. A completed cut stays in storage for later use. 剪切模式下左键和右键分别标两个角，顺序不限。随后可「取消」「保存」或「不再提示并保存」。取消不改动世界；保存后选区进入持久剪切暂存。当前存档中该玩家的偏好只跳过命名，之后仍确认范围。剪切完成后可稍后再放置，条目继续保留在暂存中。 |
| Stored cuts / 剪切暂存 | Right-click opens storage. Select a row to see its 3D preview on the right; browsing does not move or lock the world preview. Place starts placement, Alt+right-click restores the selected cut, and Alt+A/D changes the entry. In Cut mode, Shift+right-click previews the selected cut; Shift+Alt+right-click selects another region. Esc exits the local activity and keeps stored entries. Only a successful paste or undo consumes an entry. 右键打开暂存；选中列表行后，右侧显示该结构的 3D 预览，浏览不会移动或锁定世界预览。「放置」开始放置，Alt+右键撤销所选剪切，Alt+A/D 切换条目。剪切模式中 Shift+右键预览所选条目，Shift+Alt+右键继续框选。Esc 退出当前本地操作但保留暂存条目。只有成功粘贴或撤销才消费条目。 |
| Cut placement / 剪切放置 | Right-click locks the world preview; unless the placement preference is saved, a second right-click opens Cancel, Confirm, or Confirm and don't ask again. The save-local player placement preference is separate from cut naming and skips only this prompt. Change pose before confirming: R rotates around Y, Ctrl+R around X, and Alt+R opens rotation, mirror and facing controls. The confirmation shows position and pose but has no pose controls. Esc keeps the stored cut; a successful paste consumes it. 右键锁定世界预览；未保存放置偏好时，再次右键打开「取消」「确认」或「确认且以后不提醒」。放置偏好在当前存档中为该玩家单独保存，与剪切命名互不影响，只跳过放置确认框。确认前用 R 绕 Y 轴旋转、Ctrl+R 绕 X 轴旋转，或 Alt+R 打开旋转、镜像和朝向面板调整姿态。确认框显示位置和姿态，但没有姿态操作按钮。Esc 保留暂存；粘贴成功后才消费条目。 |
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

Waiting views show measured progress and percentages when a total is available, or an activity indicator and elapsed time otherwise. Background placement sends compact cell results; the client updates its display locally.

等待界面在总量已知时显示实际进度与百分比；未知时显示动态等待与耗时。后台放置发送单元结果增量，由客户端更新显示。

Administrators can opt into the default-disabled, save-local agent laboratory through `config/gtmb-testlab.cfg`. See [TESTLAB.md](TESTLAB.md) for `/gtmbtestlab`, fixture isolation, stress tests and report boundaries. Ordinary players do not need to enter its dimension.

管理员可通过 `config/gtmb-testlab.cfg` 开启默认关闭的存档内 agent 测试区；配置、`/gtmbtestlab` 指令、隔离设施、压力测试及报告说明见 [TESTLAB.md](TESTLAB.md)。测试无需玩家进入该维度。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE) · Origin / 源自 GT-Interesting-Thing · Independently maintained as / 现独立维护为 GTMB · Development notes / 开发记录：[GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

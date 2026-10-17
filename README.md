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
| Mode switcher / 模式切换 | In Blueprint mode, Shift+right-click opens the selector; Ctrl+Shift+right-click reopens it from either idle mode. Hold Shift and right-click to cycle, then release Shift to enter. Choosing Cut immediately begins corner selection. 蓝图模式下 Shift+右键打开切换器；空闲时可用 Ctrl+Shift+右键重开。按住 Shift 继续右键切换，松开 Shift 进入；选择「剪切」后直接开始框选。 |
| Cut / 剪切 | Left- and right-click set or adjust the current corners; Alt+right-click marks point 2 at the floating cursor. Shift+right-click confirms a complete selection; without a complete box, it opens stored cuts and retains partial corners. Saving stores the cut in this player/save cache, then starts another selection. Esc exits Cut mode and retains stored entries. 左右键设置或调整当前两角；Alt+右键按悬空光标标第二点。选区完整时 Shift+右键进入剪切确认；尚无完整框时打开暂存列表并保留已有角点。保存后继续框选下一份；Esc 完整退出剪切模式，保留全部暂存。 |
| Selection / 调整选区 | Shift+Alt+right-click starts another box; Ctrl also makes it subtractive. Wheel selects a box, Delete removes it, arrows move it, Alt+up/down adjusts height, and Ctrl+Z undoes individual selection actions. Applies to Blueprint and Cut. Shift+Alt+右键开始下一框，同时按 Ctrl 减选；滚轮切框、Delete 删除、方向键平移、Alt+上下调高低，Ctrl+Z 逐次撤销选区操作。蓝图与剪切均适用。 |
| Stored cuts / 剪切暂存 | Shift+right-click opens the list outside a complete selection. Select a row for its independent 3D preview; Place starts the world preview. Alt+A/D changes the current entry; Alt+right-click outside selection undoes it. Only successful paste or undo consumes an entry. 没有完整选区时 Shift+右键打开列表，选中行可看独立 3D 预览，「放置」进入世界预览。Alt+A/D 切换条目，非框选时 Alt+右键撤销；仅成功粘贴或撤销后消费条目。 |
| Cut placement / 剪切放置 | Right-click locks the world preview; unless the placement preference is saved, a second right-click opens Cancel, Confirm, or Confirm and don't ask again. This preference is saved per player in the current save, separate from cut naming, and skips only this prompt. Change pose before confirming: R rotates around Y, Ctrl+R around X, and Alt+R opens rotation, mirror and facing controls. The confirmation shows position and pose but has no pose controls. Esc exits Cut mode and keeps the stored cut; a successful paste consumes it. 右键锁定世界预览；未保存放置偏好时，再次右键打开「取消」「确认」或「确认且以后不提醒」。本存档为每位玩家分别保存放置偏好，与剪切命名互不影响，只跳过放置确认框。确认前用 R 绕 Y 轴旋转、Ctrl+R 绕 X 轴旋转，或 Alt+R 打开旋转、镜像和朝向面板调整姿态。确认框显示位置和姿态，但没有姿态操作按钮。Esc 完整退出剪切模式并保留暂存；粘贴成功后才消费条目。 |
| Position / 选位置 | Selection uses the hit block; the world preview attaches to its face. Without a hit, it floats; Alt forces floating, Alt+wheel adjusts 0–64 blocks (0 = your feet). 框选取命中方块，世界预览贴合命中表面；无命中时悬空，Alt 强制悬空，Alt+滚轮调距 0–64 格（0 为脚下）。 |
| Place / 放置 | Select a blueprint and click Place; right-click locks the green preview cyan, then right-click to review materials and confirm. Esc cancels. 选择蓝图并点「放置所选」；右键将绿色预览锁为青色，再右键核对材料并确认，Esc 取消。 |

Cut storage belongs only to the current save and player; it stays outside the blueprint library and sharing workflow.

Cut placement previews show translucent native models with block outlines.

剪切暂存仅属于当前存档与玩家，不进入蓝图库或分享流程。

剪切放置使用带方块边框的半透明原生模型预览。

Manual sharing: copy the **same-basename JSON + PNG pair** into the recipient's game-directory `MiaoBlueprints/`; use the Client/Server library switch for uploads and downloads.

手动分享：将**同名 JSON + PNG 双文件**一起放入对方游戏目录的 `MiaoBlueprints/`；公库上传、下载使用蓝图库「客户端／服务器」切换。

New servers default to a shared upload limit of **1024 KiB/s (1 MiB/s)** and download limit of **128 KiB/s**. Operators can adjust both in the server library's admin settings; existing configuration values are preserved.

新安装默认全服聚合上传限速 **1024 KiB/s（1 MiB/s）**、下载限速 **128 KiB/s**。管理员可在服务器蓝图库的设置中调整；已有配置值保留。

Detailed controls, private/shared processing and resource rules: [GTMB Guide / 完整操作、私用分享与资源规则](https://miaokatze.com/wiki?mod=gtmb). Find and share blueprints at the [Blueprint Library / 蓝图站](https://miaokatze.com/blueprints).

Community integrations / 社区扩展：[Public API & module contracts / 公共 API 与模块契约](API.md).

Waiting views show measured progress and percentages when a total is available, or an activity indicator and elapsed time otherwise. Long tasks continue after closing the workspace; reopening restores the business page, selection and view. Successful saves explain blueprint settings and structure adjustment, with an optional persistent dismissal. Guidance colors both button text and borders: red warnings, cyan suggestions, blue information and green next steps.

等待界面在总量已知时显示实际进度与百分比；未知时显示动态等待与耗时。长任务关闭界面后继续，再次打开恢复原业务页面、选区与视角。保存成功会说明蓝图设置与结构调整的区别，可选择不再显示。引导同时改变按钮文字与边框：红色警告、青色建议、蓝色信息、绿色下一步。

Stable blueprint edits use dirty-cell updates and preserve unchanged 16³ preview regions. Shared material planning and initial large snapshots run in stages; world changes and material authority remain on the server. Geometry changes and specialized audits retain complete refreshes where required.

稳定几何上的蓝图编辑按脏格更新，保留未改动的 16³ 预览分区。共享材料规划与大图首次快照分阶段执行；世界修改与材料结果仍由服务端决定。几何增删和专门审核在需要时使用完整刷新。

Validation reuses identical complete NBT within a bounded task cache; different machine, cover and AE configuration remains separate. `gradlew runBlueprintValidationBenchmark` compares the production validation path with its cache enabled and disabled. This component benchmark excludes live-world capture, networking and rendering.

校验通过有界任务缓存复用相同完整 NBT；不同机器、覆盖板和 AE 配置分别处理。`gradlew runBlueprintValidationBenchmark` 对照生产校验路径启用与禁用缓存的结果。该组件基准不包含实际世界采集、网络和渲染。

Administrators can opt into the default-disabled, save-local agent laboratory through `config/gtmb-testlab.cfg`. See [TESTLAB.md](TESTLAB.md) for `/gtmbtestlab`, fixture isolation, stress tests and report boundaries. Ordinary players do not need to enter its dimension.

管理员可通过 `config/gtmb-testlab.cfg` 开启默认关闭的存档内 agent 测试区；配置、`/gtmbtestlab` 指令、隔离设施、压力测试及报告说明见 [TESTLAB.md](TESTLAB.md)。测试无需玩家进入该维度。

## License & Origins / 许可与来源

[AGPL-3.0](LICENSE) · Origin / 源自 GT-Interesting-Thing · Independently maintained as / 现独立维护为 GTMB · Development notes / 开发记录：[GT-Miao-Blueprint-plan](https://github.com/MIAOKATZE/GT-Miao-Blueprint-plan)。

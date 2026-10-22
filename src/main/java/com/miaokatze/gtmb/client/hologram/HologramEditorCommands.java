package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.lwjgl.input.Keyboard;

/** One client-local command catalogue for shortcuts, tooltips and contextual tools. No direct RPCs. */
final class HologramEditorCommands {

    static final class Command {

        final String name, label, icon, help;
        final int control, defaultKey, defaultModifiers;
        final boolean held;
        int key, modifiers;

        Command(String name, String label, String icon, int control, int key, int modifiers, boolean held,
            String help) {
            this.name = name;
            this.label = label;
            this.icon = icon;
            this.control = control;
            this.key = this.defaultKey = key;
            this.modifiers = this.defaultModifiers = modifiers;
            this.held = held;
            this.help = help;
        }
    }

    private final HologramUiModules ui;
    private final List<Command> commands = new ArrayList<>();
    private File file;

    HologramEditorCommands(HologramUiModules ui) {
        this.ui = ui;
        add("editCut", "剪切目标", "cut", -1, Keyboard.KEY_X, 1, false, "复用规划剪切；不直接拆除世界方块。");
        add("editCopy", "复制目标", "copy", 112, Keyboard.KEY_C, 1, false, "复制当前选择的具体目标和配置。");
        add("editPaste", "粘贴目标", "pin", -1, Keyboard.KEY_V, 1, false, "使用已有剪贴板，仍由原编辑链校验目标。");
        add(
            "restoreCell",
            "删除 / 恢复默认壳",
            "delete",
            66,
            Keyboard.KEY_DELETE,
            0,
            false,
            "作者总览删除可编辑内容；控制器规划恢复声明默认壳。只修改规划。");
        add("rotateCell", "六面转向", "rotate", 113, Keyboard.KEY_R, 0, false, "使用已有目标转向命令及其服务端合法性校验。");
        add("editUndo", "撤销规划", "undo", 108, Keyboard.KEY_Z, 1, false, "只撤销规划编辑，不回滚已施工世界。");
        add("editRedo", "重做规划", "redo", 109, Keyboard.KEY_Y, 1, false, "重做已有规划编辑。");
        add("selectAll", "选择全部", "select", -1, Keyboard.KEY_A, 1, false, "选择当前快照中的全部格。");
        add("sameType", "同种全选 / 同种拾取", "select", -1, Keyboard.KEY_T, 0, true, "按下选择相关部件；按住点击执行原同种拾取。");
        add("sameTypeAdd", "补选同种", "select", -1, Keyboard.KEY_T, 1, false, "将相关部件加入已有选择。");
        add("clearSelection", "取消选择", "cancel", 69, Keyboard.KEY_D, 1, false, "清除选择；与取消施工或删除目标不同。");
        add("moveHandles", "显示 / 隐藏移动轴", "move", 147, Keyboard.KEY_G, 0, false, "仅在原几何编辑允许的完整选区使用；控制器坐标不能搬移，不影响其目标编辑。");
        add("air", "空气拾取", "box-select", -1, Keyboard.KEY_LMENU, 0, true, "按住此键，沿原预览拾取空气位置。");
        add("cameraLeft", "镜头向左平移", "pan", -1, Keyboard.KEY_A, 0, true, "只平移独立编辑器镜头，不移动玩家。");
        add("cameraRight", "镜头向右平移", "pan", -1, Keyboard.KEY_D, 0, true, "只平移独立编辑器镜头，不移动玩家。");
        add("cameraForward", "镜头向前平移", "pan", -1, Keyboard.KEY_W, 0, true, "只平移独立编辑器镜头，不移动玩家。");
        add("cameraBack", "镜头向后平移", "pan", -1, Keyboard.KEY_S, 0, true, "只平移独立编辑器镜头，不移动玩家。");
        add("modeBuild", "规划模式：补建", "place", 70, Keyboard.KEY_1, 0, false, "切换原补建规划，不执行施工。");
        add("modeReplace", "规划模式：替换", "structure", 71, Keyboard.KEY_2, 0, false, "切换原替换规划，不执行施工。");
        add("modeRemove", "规划模式：拆除", "delete", 72, Keyboard.KEY_3, 0, false, "实体控制器规划专用；虚拟蓝图不开放拆除。");
        add("resetView", "复位编辑视角", "reset-view", 14, Keyboard.KEY_HOME, 0, false, "复用编辑器相机复位，不改变玩家或世界。");
        add("frontView", "正面正交视角", "world", -1, Keyboard.KEY_NUMPAD1, 0, false, "只改变编辑器镜头。");
        add("rightView", "侧面正交视角", "world", -1, Keyboard.KEY_NUMPAD3, 0, false, "只改变编辑器镜头。");
        add("topView", "顶部正交视角", "world", -1, Keyboard.KEY_NUMPAD7, 0, false, "只改变编辑器镜头。");
        add("allLayers", "切换全部层 / 当前层", "layer", 11, Keyboard.KEY_F, 0, false, "复用原预览层高过滤。");
        add("layerDown", "上一层", "layer", 12, Keyboard.KEY_NEXT, 0, false, "向下切换原预览图层。");
        add("layerUp", "下一层", "layer", 13, Keyboard.KEY_PRIOR, 0, false, "向上切换原预览图层。");
        add("inventory", "背包 / 来源选材", "material", 61, Keyboard.KEY_I, 0, false, "打开真实背包与已启用材料来源；不会立即取料。");
        add("candidates", "本格声明候选", "structure", 95, Keyboard.KEY_B, 0, false, "只列所选格实际声明的合法设备与方块候选。");
        add("hatchFacing", "下一个合法仓室朝向", "rotate", 99, Keyboard.KEY_R, 2, false, "仅遍历本格回传的 hatchFacings。");
        add("hatchPreset", "实体仓室预设", "settings", 94, Keyboard.KEY_P, 0, false, "打开现有基础配额 / 自定义精确搭配；不创建通用角色数量模型。");
        add("channels", "结构等级 / 信道", "settings", 60, Keyboard.KEY_O, 0, false, "修改实际来源结构主值与具名信道。");
        add("structure", "进入所选来源结构", "structure", 163, Keyboard.KEY_RETURN, 1, false, "按真实 structureId 进入成员配置。");
        add("overview", "返回区域总览", "back", 165, Keyboard.KEY_BACK, 0, false, "保留各来源成员的当前配置并返回总览。");
        add("sources", "材料来源设置", "material", 78, Keyboard.KEY_M, 0, false, "主背包、容器与 ME 开关和优先级沿用现有配置链。");
        add("library", "蓝图库", "library", 149, Keyboard.KEY_L, 0, false, "进入已有蓝图库，不制造新的世界或会话。");
        add("projectionWorkspace", "投影工作区", "world", -1, Keyboard.KEY_1, 1, false, "返回独立控制器投影；保留蓝图草稿。");
        add("blueprintWorkspace", "蓝图工作区", "library", -1, Keyboard.KEY_2, 1, false, "切换独立作者蓝图；保留投影规划。");
        add("cutWorkspace", "剪切工作区", "cut", -1, Keyboard.KEY_3, 1, false, "在当前工作台打开剪切列表与预览。");
        add("process", "蓝图处理", "process", 220, Keyboard.KEY_F4, 0, false, "沿已有作者处理门禁打开六步流程。");
        add("save", "保存当前蓝图", "save", 140, Keyboard.KEY_S, 1, false, "复用现有保存与审核流程。");
        add("text", "文字蓝图", "text", 331, Keyboard.KEY_F7, 0, false, "复用文字编辑与附件选位流程。");
        add("image", "图片蓝图", "image", 332, Keyboard.KEY_F8, 0, false, "复用图片编辑与附件选位流程。");
        add("rotateTextVertical", "文字竖向转动", "rotate", -1, Keyboard.KEY_R, 4, false, "只对已有文字附件使用原竖向旋转操作。");
        add("floatingTools", "唤出 / 收起浮动工具", "detach", -1, Keyboard.KEY_SPACE, 0, false, "拖动标题移动工具条；收起后仍可从左侧或此快捷键唤回。");
        add("shortcuts", "编辑器快捷键", "help", 110, Keyboard.KEY_F1, 0, false, "编辑器命令独立于游戏键位；E / Esc 保留退出。");
        add("viewWorkspace", "视图与工作区", "world", -1, Keyboard.KEY_NONE, 0, false, "打开视图、切片、世界投影和现有工作区入口；不改变当前选择。");
        add("viewCycle", "目标 / 现场 / 差分", "visible", 10, Keyboard.KEY_NONE, 0, false, "切换目标结构、真实现场和施工差分。");
        add("worldPreview", "世界投影显示", "world", 15, Keyboard.KEY_NONE, 0, false, "显示或隐藏世界中的材质预览，不执行施工。");
        add(
            "areaSelection",
            "框选世界区域",
            "box-select",
            178,
            Keyboard.KEY_NONE,
            0,
            false,
            "开始世界框选：左键第一角、右键第二角，Alt使用玩家位置，再次Shift+右击完成，Esc取消。");
        add("clearWorkspace", "清空当前工作区", "clear", 330, Keyboard.KEY_NONE, 0, false, "清空当前编辑工作区；这不是拆除世界方块。");
        add(
            "networkBindings",
            "已有量子类群绑定",
            "quantum",
            196,
            Keyboard.KEY_NONE,
            0,
            false,
            "打开当前蓝图已登记量子类群的真实网络绑定，不创建虚构节点或网络。");
        add("aeMarking", "AE 容器标记", "storage", 179, Keyboard.KEY_NONE, 0, false, "打开自有AE标记容器；NEI拖入、背包样本仅修改蓝图标记。");
    }

    private void add(String name, String label, String icon, int control, int key, int modifiers, boolean held,
        String help) {
        commands.add(new Command(name, label, icon, control, key, modifiers, held, help));
    }

    int size() {
        return commands.size();
    }

    Command get(int index) {
        return index >= 0 && index < commands.size() ? commands.get(index) : null;
    }

    Command get(String name) {
        for (Command command : commands) if (command.name.equals(name)) return command;
        return null;
    }

    int index(String name) {
        for (int i = 0; i < commands.size(); i++) if (commands.get(i).name.equals(name)) return i;
        return -1;
    }

    String shortcut(String name) {
        Command command = get(name);
        if (command == null || command.key == Keyboard.KEY_NONE) return "未绑定";
        String keyName = Keyboard.getKeyName(command.key);
        return ((command.modifiers & 1) != 0 ? "Ctrl+" : "") + ((command.modifiers & 2) != 0 ? "Shift+" : "")
            + ((command.modifiers & 4) != 0 ? "Alt+" : "")
            + (keyName == null ? Integer.toString(command.key) : keyName);
    }

    String help(String name) {
        Command command = get(name);
        if (command == null) return "";
        String unavailable = reason(name);
        return command.label + " · "
            + shortcut(name)
            + "\n"
            + command.help
            + (unavailable.isEmpty() ? "" : "\n当前不可用：" + unavailable);
    }

    boolean matches(String name, int key, int modifiers) {
        Command command = get(name);
        return command != null && command.key != Keyboard.KEY_NONE
            && command.key == key
            && command.modifiers == normalizedModifiers(key, modifiers);
    }

    String match(int key, int modifiers) {
        for (Command command : commands) if (matches(command.name, key, modifiers)) return command.name;
        return "";
    }

    /** Polled only by this active editor; focused native text fields always keep their keystrokes. */
    boolean held(String name) {
        Command command = get(name);
        if (command == null || command.key == Keyboard.KEY_NONE
            || ui.host.minecraft().currentScreen != ui.host.parent()
            || ui.shell.fieldFocused()
            || ui.shortcuts.captureBinding() >= 0
            || !sceneAvailable()
            || ui.tools.modalOpen()
            || ui.tools.pointerCaptured()
            || ui.preview.inventory()
            || HologramBackgroundTasks.busy()
            || ui.library.browsePreviewActive()
            || !org.lwjgl.opengl.Display.isActive()) return false;
        return Keyboard.isKeyDown(command.key)
            && command.modifiers == normalizedModifiers(command.key, HologramKeyBindings.modifiers());
    }

    static int normalizedModifiers(int key, int modifiers) {
        if (key == Keyboard.KEY_LCONTROL || key == Keyboard.KEY_RCONTROL) return modifiers & ~1;
        if (key == Keyboard.KEY_LSHIFT || key == Keyboard.KEY_RSHIFT) return modifiers & ~2;
        if (key == Keyboard.KEY_LMENU || key == Keyboard.KEY_RMENU) return modifiers & ~4;
        return modifiers;
    }

    boolean bind(int index, int key, int modifiers) {
        Command target = get(index);
        if (target == null || key <= Keyboard.KEY_NONE
            || key >= Keyboard.KEYBOARD_SIZE
            || key == Keyboard.KEY_ESCAPE
            || key == Keyboard.KEY_E
            || modifiers < 0
            || modifiers > 7) return false;
        modifiers = normalizedModifiers(key, modifiers);
        if (HologramKeyBindings.isModifier(key) && !target.held) return false;
        for (Command command : commands)
            if (command != target && command.key == key && command.modifiers == modifiers) return false;
        target.key = key;
        target.modifiers = modifiers;
        return true;
    }

    void defaults() {
        for (Command command : commands) {
            command.key = command.defaultKey;
            command.modifiers = command.defaultModifiers;
        }
    }

    void load() {
        defaults();
        File config = new File(ui.host.minecraft().mcDataDir, "config");
        file = new File(config, "gtmb-hologram-editor-keys.properties");
        File source = file.isFile() ? file : new File(config, "gtmb-hologram-keys.properties");
        boolean legacy = !file.isFile();
        if (!source.isFile()) return;
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(source)) {
            values.load(input);
            // Validate the complete candidate map before changing any live binding.
            int[] keys = new int[size()], modifiers = new int[size()];
            for (int i = 0; i < size(); i++) {
                Command command = get(i);
                String[] pair = values.getProperty(command.name, command.defaultKey + ":" + command.defaultModifiers)
                    .split(":");
                if (pair.length != 2) throw new IllegalArgumentException("Invalid editor key binding");
                keys[i] = Integer.parseInt(pair[0]);
                modifiers[i] = normalizedModifiers(keys[i], Integer.parseInt(pair[1]));
                if (legacy && keys[i] == Keyboard.KEY_E) {
                    // E now exits the editor. Retire this one legacy binding without losing the player's other keys.
                    keys[i] = Keyboard.KEY_NONE;
                    modifiers[i] = 0;
                }
                if (keys[i] < 0 || keys[i] >= Keyboard.KEYBOARD_SIZE
                    || keys[i] == Keyboard.KEY_E
                    || keys[i] == Keyboard.KEY_ESCAPE
                    || modifiers[i] < 0
                    || modifiers[i] > 7
                    || HologramKeyBindings.isModifier(keys[i]) && !command.held)
                    throw new IllegalArgumentException("Reserved editor key binding");
                for (int j = 0; j < i; j++)
                    if (keys[i] != Keyboard.KEY_NONE && keys[i] == keys[j] && modifiers[i] == modifiers[j]) {
                        if (legacy && i >= HologramKeyBindings.ACTIONS.length) {
                            // Existing user bindings take priority over newly introduced defaults.
                            keys[i] = Keyboard.KEY_NONE;
                            modifiers[i] = 0;
                        } else throw new IllegalArgumentException("Conflicting editor key binding");
                    }
            }
            for (int i = 0; i < size(); i++) {
                get(i).key = keys[i];
                get(i).modifiers = modifiers[i];
            }
        } catch (IOException | RuntimeException invalid) {
            defaults();
            ui.session.notice("编辑器快捷键文件无效，已使用默认键位");
        }
    }

    boolean save() {
        if (file == null) return false;
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        Properties values = new Properties();
        for (Command command : commands) values.setProperty(command.name, command.key + ":" + command.modifiers);
        try (FileOutputStream output = new FileOutputStream(file)) {
            values.store(output, "GTMB independent editor shortcuts (ctrl=1, shift=2, alt=4); E/Esc remain close");
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    boolean sceneAvailable() {
        return commandContextAvailable() && !ui.shell.fieldFocused();
    }

    /** A deliberate native button press commits/removes field focus before its deferred command runs. */
    boolean commandContextAvailable() {
        return !HologramBackgroundTasks.busy() && !ui.library.browsePreviewActive()
            && ui.shell.sceneActive()
            && !ui.preview.inventory();
    }

    boolean available(String name) {
        return reason(name).isEmpty();
    }

    private boolean viewContextAvailable() {
        if (ui.session.areaImporting() || ui.session.state().data.getBoolean("snapshotPending")
            || ui.session.state().data.getBoolean("placementValidating")) return false;
        // The old read-only browse header also exposed its ready mesh's layer controls.
        if (!ui.library.browsePreviewActive()) return commandContextAvailable();
        return !HologramBackgroundTasks.busy() && ui.preview.previewInteractive()
            && !ui.preview.inventory()
            && !ui.bindings.networkDrawer()
            && !ui.library.libraryAdminDrawer()
            && !ui.review.materialDialogOpen()
            && !ui.library.saveGuidanceOpen();
    }

    private boolean legacyViewCommand(String name) {
        return "viewCycle".equals(name) || "allLayers".equals(name)
            || "layerDown".equals(name)
            || "layerUp".equals(name)
            || "resetView".equals(name)
            || "worldPreview".equals(name);
    }

    String reason(String name) {
        if (get(name) == null) return "未知编辑器命令";
        if (workspaceCommand(name)) return ui.workspaces.switching() ? "正在切换工作区，请等待回执" : "";
        if ("viewWorkspace".equals(name)) return viewContextAvailable() ? "" : "先完成当前窗口或任务，再查看视图与工作区";
        if (legacyViewCommand(name)) {
            if (!viewContextAvailable()) return "先完成当前窗口或任务，再调整视图";
            int control = get(name).control;
            if (ui.preview.viewControlEnabled(control)) return "";
            if (ui.library.browsePreviewActive())
                return control == 11 || control == 12 || control == 13 ? "只读预览网格尚未就绪或已到图层边界" : "只读浏览仅开放已就绪网格的图层查看";
            return ui.canvas.disabledReason(control);
        }
        if (ui.library.browsePreviewActive()) return "当前为只读蓝图库预览，此操作不开放";
        if (!commandContextAvailable()) return "先完成当前窗口或任务，再返回编辑画布";
        HologramState.Cell cell = ui.session.selectedCell();
        boolean editable = ui.session.editable();
        return switch (name) {
            case "frontView", "rightView", "topView", "cameraLeft", "cameraRight", "cameraForward", "cameraBack", "air", "floatingTools", "shortcuts", "library" -> "";
            case "areaSelection", "clearWorkspace" -> editable ? "" : ui.session.editingReason();
            case "networkBindings" -> !editable ? ui.session.editingReason()
                : ui.bindings.networkGroups()
                    .tagCount() > 0 ? "" : "当前蓝图没有已登记的量子类群";
            case "aeMarking" -> ui.ae.canOpenAePreview() && !ui.canvas.localPlanBlockedControl(179) ? ""
                : ui.canvas.disabledReason(179);
            case "selectAll" -> ui.session.state().cells.isEmpty() ? "当前没有可选择的格" : "";
            case "sameType", "sameTypeAdd", "clearSelection" -> cell == null ? "先选择一个目标格" : "";
            case "moveHandles" -> ui.preview.canMoveSelection() ? "" : ui.preview.moveHandleReason();
            case "editUndo" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getBoolean("editCanUndo") ? "" : "当前没有可撤销的规划编辑";
            case "editRedo" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getBoolean("editCanRedo") ? "" : "当前没有可重做的规划编辑";
            case "editCopy", "rotateCell" -> !editable ? ui.session.editingReason() : cell == null ? "先选择目标格" : "";
            case "editCut", "restoreCell" -> !editable ? ui.session.editingReason()
                : cell == null ? "先选择目标格" : cell.anchor ? "控制器锚点受保护" : "";
            case "editPaste" -> !editable ? ui.session.editingReason()
                : cell == null ? "先选择目标格" : !ui.session.state().data.getBoolean("editCanPaste") ? "当前剪贴板不能粘贴到此目标" : "";
            case "candidates", "inventory" -> !editable ? ui.session.editingReason()
                : ui.preview.canInventoryPin() ? "" : "先选中可指定目标的非锚点格；拆除模式不指定新目标";
            case "hatchFacing" -> !editable ? ui.session.editingReason()
                : cell != null && ui.session.cellTag()
                    .getIntArray("hatchFacings").length > 0 ? "" : "本格没有合法仓室朝向";
            case "hatchPreset" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getBoolean("virtual") ? "整机仓室配额仅适用于真实控制器规划"
                    : ui.session.state().data.getInteger("mode") == 2 ? "拆除模式不生成仓室预设"
                        : !ui.session.state().data.getBoolean("supported") ? "当前来源尚不支持仓室配置" : "";
            case "channels" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getBoolean("virtual") && ui.session.state().data.getString("activeStructure")
                    .isEmpty() ? "先进入实际来源结构" : "";
            case "structure" -> !editable ? ui.session.editingReason()
                : ui.config.selectedStructureId()
                    .isEmpty() ? "先选中来源结构" : cell != null && cell.sourceMissing ? "结构来源缺失" : "";
            case "overview" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getString("activeStructure")
                    .isEmpty() ? "当前已是区域总览" : "";
            case "sources" -> editable ? "" : ui.session.editingReason();
            case "modeBuild", "modeReplace" -> editable ? "" : ui.session.editingReason();
            case "modeRemove" -> !editable ? ui.session.editingReason()
                : ui.session.state().data.getBoolean("virtual") ? "虚拟蓝图不能进入实体拆除规划" : "";
            case "process" -> !editable ? ui.session.editingReason()
                : HologramGuidance.authorWorkspace(ui.session.state()) ? "" : "当前不是可处理的作者蓝图";
            case "save" -> !editable ? ui.session.editingReason()
                : ui.session.state().cells.isEmpty() ? "先载入蓝图内容" : ui.processing.processingReady() ? "" : "先完成现有蓝图处理流程";
            case "text" -> ui.textBoxes.allowed(false) ? "" : "当前模式或依赖不允许编辑文字附件";
            case "image" -> ui.textBoxes.allowed(true) ? "" : "当前模式或依赖不允许编辑图片附件";
            case "rotateTextVertical" -> !editable ? ui.session.editingReason()
                : ui.textBoxes.selectedDefinition() == null ? "先选择文字附件" : "";
            default -> "当前状态不支持此操作";
        };
    }

    boolean workspaceCommand(String name) {
        return "projectionWorkspace".equals(name) || "blueprintWorkspace".equals(name) || "cutWorkspace".equals(name);
    }

    boolean execute(String name) {
        Command command = get(name);
        if (command == null) return false;
        String reason = reason(name);
        if (!reason.isEmpty()) {
            ui.session.notice(reason);
            return true;
        }
        switch (name) {
            case "projectionWorkspace" -> ui.workspaces.activateProjection();
            case "blueprintWorkspace" -> ui.workspaces.activateBlueprint();
            case "cutWorkspace" -> ui.workspaces.activateCut();
            case "library" -> {
                if (ui.workspaces.blueprint()) ui.shell.toggleLibrary();
                else ui.workspaces.activateBlueprint();
            }
            case "save" -> ui.shell.selectDockPage(7);
            case "editCut", "editPaste", "rotateTextVertical" -> ui.preview.editAction(name);
            case "selectAll" -> {
                ui.preview.selection()
                    .all(ui.session.state());
                syncSelection();
            }
            case "sameType", "sameTypeAdd" -> {
                ui.preview.selection()
                    .selectRelated(ui.session.state(), "sameTypeAdd".equals(name));
                syncSelection();
            }
            case "frontView" -> {
                ui.preview.yaw(ui.preview.frontCameraYaw() - Math.PI / 4);
                ui.preview.pitch(0);
            }
            case "rightView" -> {
                ui.preview.yaw(ui.preview.frontCameraYaw() + Math.PI / 4);
                ui.preview.pitch(0);
            }
            case "topView" -> {
                ui.preview.yaw(ui.preview.frontCameraYaw());
                ui.preview.pitch(Math.PI / 2);
            }
            case "floatingTools" -> ui.tools.toggleFloating();
            case "viewWorkspace" -> ui.tools.openViewWorkspace();
            case "air", "cameraLeft", "cameraRight", "cameraForward", "cameraBack" -> { /* held gesture */ }
            default -> {
                if (command.control >= 0) ui.router.activate(command.control);
            }
        }
        return true;
    }

    private void syncSelection() {
        ui.preview.selected(
            ui.preview.selection()
                .first());
        ui.config.syncTargetSelectors();
    }
}

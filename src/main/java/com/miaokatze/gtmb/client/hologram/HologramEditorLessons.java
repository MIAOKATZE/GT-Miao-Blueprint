package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Domain lessons contain intent and stable control identities, never drawing or screen coordinates. */
final class HologramEditorLessons {

    enum Operation {
        CONTROL,
        HOVER,
        HOVER_NATIVE,
        OPEN_TOOL,
        OPEN_CAPABILITY,
        SELECT_CANDIDATE,
        SELECT_CELL,
        SELECT_STRUCTURE,
        HANDLES,
        MOVE_X,
        MOVE_XZ,
        COPY_X,
        CLOSE_PANEL,
        CLOSE_MATERIAL,
        LIBRARY,
        EDITOR,
        SETTINGS,
        OPEN_DOCK,
        CLOSE_TARGET,
        OPEN_TARGET,
        CLOSE_SOURCE,
        OPEN_SOURCE,
        OPEN_MATERIAL,
        TYPE_CAPABILITY,
        APPLY_CAPABILITY,
        PROMPT,
        MATERIALS,
        WORLD_MODE,
        FIRST_CORNER,
        SECOND_CORNER,
        WORLD_MOVE_XZ,
        BOX_SUBMIT,
        CUT_SOURCE_REVIEW,
        CUT_STORE,
        CUT_TAKE,
        CUT_LOCK,
        CUT_CANCEL,
        CUT_PLACE,
        CUT_LIST_CLICK,
        CUT_LIST_ORBIT
    }

    static final class Action {

        final Operation operation;
        final int control;
        final String target;

        private Action(Operation operation, int control, String target) {
            this.operation = operation;
            this.control = control;
            this.target = target;
        }

        boolean drag() {
            return operation == Operation.MOVE_X || operation == Operation.MOVE_XZ
                || operation == Operation.COPY_X
                || operation == Operation.WORLD_MOVE_XZ
                || operation == Operation.CUT_LIST_ORBIT;
        }

        boolean hover() {
            return operation == Operation.HOVER || operation == Operation.HOVER_NATIVE;
        }
    }

    static final class Step {

        final String title, description, focus;
        final List<Action> actions;

        private Step(String title, String description, String focus, Action... actions) {
            this.title = title;
            this.description = description;
            this.focus = focus;
            this.actions = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(actions)));
        }
    }

    private HologramEditorLessons() {}

    static List<Step> course(String topic, HologramEditorCommands commands) {
        List<Step> steps = new ArrayList<>();
        if (topic.equals("projection")) projection(steps);
        else if (topic.equals("blueprint")) blueprint(steps, commands);
        else if (topic.equals("structure")) structure(steps);
        else if (topic.equals("cut")) cut(steps);
        else if (topic.equals("box-fps") || topic.equals("box-iso")
            || topic.equals("cut-fps")
            || topic.equals("cut-iso")) world(steps, topic);
        else throw new IllegalArgumentException("Unknown editor lesson: " + topic);
        return Collections.unmodifiableList(steps);
    }

    static List<Step> course(String topic, HologramEditorCommands commands, HologramState observed) {
        List<Step> steps = new ArrayList<>(course(topic, commands));
        if (topic.equals("projection") || topic.equals("structure")) {
            boolean direct = !hasDeclaredStructure(observed);
            if (direct) {
                for (int i = 0; i < steps.size(); i++) {
                    Step old = steps.get(i);
                    List<Action> actions = new ArrayList<>();
                    for (Action action : old.actions)
                        if (action.operation != Operation.SELECT_STRUCTURE && action.control != 163
                            && action.control != 165) actions.add(action);
                    steps.set(i, new Step(old.title, old.description, old.focus, actions.toArray(new Action[0])));
                }
                int sourceStep = topic.equals("projection") ? 1 : 0;
                Step source = steps.get(sourceStep);
                steps.set(
                    sourceStep,
                    new Step(
                        "当前实体来源",
                        "这个真实样例是单控制器投影，直接编辑其声明参数；无需经过区域成员入口。",
                        source.focus,
                        source.actions.toArray(new Action[0])));
                if (topic.equals("structure")) {
                    Step back = steps.get(4);
                    steps.set(
                        4,
                        new Step(
                            "返回当前投影",
                            "关闭参数窗口返回当前实体投影；这个样例没有区域总览切换。",
                            back.focus,
                            back.actions.toArray(new Action[0])));
                }
            }
            boolean details = false;
            for (HologramState.Cell cell : observed.cells) if (!cell.candidates.isEmpty()) details = true;
            if (!details) {
                int candidateStep = topic.equals("projection") ? 3 : 2;
                Step old = steps.get(candidateStep);
                List<Action> actions = new ArrayList<>();
                for (Action action : old.actions)
                    if (action.operation != Operation.SELECT_CANDIDATE) actions.add(action);
                steps.set(
                    candidateStep,
                    new Step(
                        "设备候选入口",
                        "候选由真实选格详情返回；这份物理快照尚未录取该详情，只展示入口，不生成候选。",
                        old.focus,
                        actions.toArray(new Action[0])));
            }
        }
        if (topic.equals("blueprint")) {
            if (observed.data.getCompoundTag("networkBindings")
                .getTagList("groups", 10)
                .tagCount() > 0) {
                Step old = steps.get(16);
                steps.set(
                    16,
                    new Step(
                        old.title,
                        "从视图与工作区打开真实网络绑定入口，查看本例已有的网络组；演示不会修改真实网络。",
                        "modal",
                        action(Operation.CLOSE_PANEL, "modal.close"),
                        action(Operation.OPEN_TOOL, "viewWorkspace"),
                        control(HologramEditorControlIds.TOOLS + 400 + commands.index("networkBindings"))));
            }
            boolean canNbt = !observed.data.getBoolean("blueprintProcessingRequired")
                || observed.data.getBoolean("blueprintProcessingComplete")
                || observed.data.getBoolean("blueprintSaved");
            if (canNbt) {
                Step old = steps.get(15);
                steps.set(
                    15,
                    new Step(
                        old.title,
                        "处理条件已满足，可以从设置页打开实际 NBT 工具；此处仅展示窗口，不写入方块数据。",
                        "modal",
                        action(Operation.CLOSE_PANEL, "modal.close"),
                        control(166)));
            }
        }
        return Collections.unmodifiableList(steps);
    }

    /** The empty membership bucket contains loose/controller cells, and is never a declared area member. */
    static boolean hasDeclaredStructure(HologramState observed) {
        net.minecraft.nbt.NBTTagList declarations = observed.data.getTagList("structures", 10);
        for (int i = 0; i < declarations.tagCount(); i++) {
            String id = declarations.getCompoundTagAt(i)
                .getString("id");
            if (!id.isEmpty() && !observed.structureMembers.getOrDefault(id, Collections.emptyList())
                .isEmpty()) return true;
        }
        return false;
    }

    private static Action control(int id) {
        return new Action(Operation.CONTROL, id, "control." + id);
    }

    private static Action action(Operation operation, String target) {
        return new Action(operation, -1, target);
    }

    private static void add(List<Step> list, String title, String text, String focus, Action... actions) {
        List<Action> routed = new ArrayList<>();
        for (Action action : actions) {
            String page = null;
            switch (action.operation) {
                case CLOSE_TARGET:
                case OPEN_TARGET:
                    page = "launcher.0";
                    break;
                case CLOSE_SOURCE:
                case OPEN_SOURCE:
                    page = "launcher.3";
                    break;
                case OPEN_MATERIAL:
                    page = "launcher.4";
                    break;
                default:
                    break;
            }
            if (page != null) routed.add(action(Operation.OPEN_DOCK, page));
            routed.add(action);
        }
        list.add(new Step(title, text, focus, routed.toArray(new Action[0])));
    }

    private static void projection(List<Step> s) {
        add(s, "控制器锚定", "投影以控制器为锚点。这里使用当前快照的隔离副本。", "viewport", action(Operation.SELECT_CELL, "viewport"));
        add(
            s,
            "进入来源结构",
            "点轮廓整选，再进入结构编辑。各来源成员独立保存参数。",
            "inspector",
            action(Operation.SELECT_STRUCTURE, "viewport"),
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_SOURCE, "properties.source"),
            control(163));
        add(
            s,
            "声明参数与信道",
            "只编辑机器实际声明的能力；仓室电压与结构主值分开。",
            "modal",
            control(60),
            action(Operation.OPEN_CAPABILITY, "config.capability"),
            action(Operation.TYPE_CAPABILITY, "config.option.value"),
            action(Operation.APPLY_CAPABILITY, "control.68"));
        add(
            s,
            "具体设备候选",
            "类型与等级负责筛选，最终目标仍是具体设备及其精确 NBT。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.SELECT_CELL, "viewport"),
            action(Operation.CLOSE_SOURCE, "properties.source"),
            action(Operation.OPEN_TARGET, "properties.target"),
            control(95),
            action(Operation.SELECT_CANDIDATE, "config.candidate.row.0"));
        add(
            s,
            "实体仓室预设",
            "基础配额保留手选、现场仓室和最低壳体；允许降级是单独策略。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            control(94),
            control(105));
        add(s, "本机命名搭配", "自定义搭配保存具体设备及数量。这里只查看入口，不命名写盘。", "modal", control(1711));
        add(
            s,
            "材料核验",
            "先核对来源与精确需求，最后只确认一次。示例库存不代表实时库存。",
            "materials",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_MATERIAL, "properties.material"),
            control(78),
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.MATERIALS, "viewport"));
        add(s, "返回工作区", "结束后恢复原控件树、草稿、相机与选区。", "viewport", action(Operation.CLOSE_MATERIAL, "materials.cancel"));
    }

    private static void blueprint(List<Step> s, HologramEditorCommands commands) {
        add(s, "草稿与蓝图库", "右侧库与中央画布共用工作区。客户端和服务器来源分别保留。", "library", action(Operation.LIBRARY, "header.library"));
        add(s, "点行切换", "单击库行即时更新中央草稿；未保存内容沿用原有提示。", "library", control(14001));
        add(
            s,
            "精确选格",
            "Ctrl 增减选格；" + commands.shortcut("sameType") + " 扩展同类。点结构轮廓可整选成员。",
            "viewport",
            action(Operation.EDITOR, "launcher.0"),
            action(Operation.SELECT_CELL, "viewport"));
        add(
            s,
            "完整结构与手柄",
            commands.shortcut("moveHandles") + " 显示三轴与三平面；世界坐标轴不会随镜头改变。",
            "viewport",
            action(Operation.SELECT_STRUCTURE, "viewport"),
            action(Operation.HANDLES, "viewport"));
        add(s, "沿轴移动", "按住 X 手柄连续拖动，模型按整格吸附。", "viewport", action(Operation.MOVE_X, "gizmo.x"));
        add(s, "平面移动", "拖 XZ 平面同时改变两轴；XY、YZ 使用同样的原生逆解。", "viewport", action(Operation.MOVE_XZ, "gizmo.xz"));
        add(s, "起拖复制", "起拖时按 Ctrl 锁定复制意图，原处保留。", "viewport", action(Operation.COPY_X, "gizmo.x"));
        add(
            s,
            "来源成员参数",
            "先进入实际来源成员，再编辑其声明参数与具名信道。",
            "modal",
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_SOURCE, "properties.source"),
            control(163),
            control(60));
        add(
            s,
            "输入与应用",
            "字段是本地输入；点击应用才交给隔离会话处理。",
            "modal",
            action(Operation.OPEN_CAPABILITY, "config.capability"),
            action(Operation.TYPE_CAPABILITY, "config.option.value"),
            action(Operation.APPLY_CAPABILITY, "control.68"));
        add(
            s,
            "设备、默认壳与朝向",
            "候选保留具体设备身份、合法范围与配置 NBT。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.SELECT_CELL, "viewport"),
            action(Operation.CLOSE_SOURCE, "properties.source"),
            action(Operation.OPEN_TARGET, "properties.target"),
            control(95),
            action(Operation.SELECT_CANDIDATE, "config.candidate.row.0"));
        add(
            s,
            "作者蓝图的边界",
            "作者蓝图可逐格编辑目标；当前服务端不允许 virtual 套整机仓室配额。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            control(94));
        add(
            s,
            "材料来源",
            "背包、物品容器和 ME 是独立来源；ME 仍须满足绑定与权限。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_MATERIAL, "properties.material"),
            control(78));
        add(
            s,
            "文字工具入口",
            "文字工具另开独立编辑窗口。本课只悬停说明入口，不创建文字；继续演示结构与蓝图处理。",
            "control." + (HologramEditorControlIds.TOOLS + commands.index("text")),
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.HOVER, "text"));
        add(
            s,
            "图片工具入口",
            "图片工具另开独立编辑窗口。本课只悬停说明入口，不生成图片，也不模拟选位或确认。",
            "control." + (HologramEditorControlIds.TOOLS + commands.index("image")),
            action(Operation.HOVER, "image"));
        add(
            s,
            "注释与区域",
            "提示词、提示区和排除区按对象保存，不代替世界过滤执行。",
            "modal",
            action(Operation.SETTINGS, "launcher.1"),
            action(Operation.PROMPT, "control.146"));
        add(
            s,
            "NBT 审阅",
            "逐格 NBT 在处理后开放；本例尚未完成处理，先查看真实入口与禁用说明。",
            "control.166",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.HOVER_NATIVE, "control.166"));
        add(
            s,
            "量子组与网络",
            "在视图与工作区查看真实网络入口。本例没有已登记量子类群，因此绑定入口不开放。",
            "tools.network.unavailable",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.OPEN_TOOL, "viewWorkspace"),
            action(Operation.HOVER_NATIVE, "tools.network.unavailable"));
        add(
            s,
            "倾向自用",
            "从实际处理入口选择自用倾向；这是处理策略，不是仓室搭配。",
            "processing",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.SETTINGS, "launcher.1"),
            control(220),
            control(232));
        add(s, "倾向分享", "切换分享倾向，查看去标识、量子重绑与外部关系策略。", "processing", control(233));
        add(
            s,
            "保存与覆盖",
            "名称与保存按钮在独立保存侧栏。本例尚未完成处理，保存和覆盖均不执行；教程不写文件。",
            "library.save",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.OPEN_DOCK, "header.save"),
            action(Operation.HOVER_NATIVE, "library.name"),
            action(Operation.HOVER_NATIVE, "control.140"),
            action(Operation.HOVER_NATIVE, "control.222"));
        add(s, "世界位置先锁定", "真实放置先在世界移动并锁定 ghost；这里从已锁定位置解释预检。", "materials", action(Operation.MATERIALS, "viewport"));
        add(s, "唯一最终确认", "逐项核对需求、已有与缺失；本次 NBT、缓存和补料各有原有门禁。", "materials");
        add(
            s,
            "返回蓝图库",
            "完成或跳过都恢复原窗口、字段、布局与选区。",
            "library",
            action(Operation.CLOSE_MATERIAL, "materials.cancel"),
            action(Operation.LIBRARY, "header.library"));
    }

    private static void structure(List<Step> s) {
        add(
            s,
            "来源结构",
            "控制器锚定实体来源；点轮廓后进入该成员。",
            "inspector",
            action(Operation.SELECT_CELL, "viewport"),
            action(Operation.SELECT_STRUCTURE, "viewport"),
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_SOURCE, "properties.source"),
            control(163));
        add(
            s,
            "机器声明参数",
            "沿用实际能力项；未声明的能力不伪造为通用等级。",
            "modal",
            control(60),
            action(Operation.OPEN_CAPABILITY, "config.capability"),
            action(Operation.TYPE_CAPABILITY, "config.option.value"),
            action(Operation.APPLY_CAPABILITY, "control.68"));
        add(
            s,
            "逐格设备",
            "筛选之后仍选择具体设备，保留精确 NBT 和合法朝向。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.SELECT_CELL, "viewport"),
            action(Operation.CLOSE_SOURCE, "properties.source"),
            action(Operation.OPEN_TARGET, "properties.target"),
            control(95),
            action(Operation.SELECT_CANDIDATE, "config.candidate.row.0"));
        add(
            s,
            "基础与命名搭配",
            "基础按声明配额分配；自定义保留设备及数量，不覆盖手选或现场仓室。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            control(94),
            control(1711));
        add(
            s,
            "返回与施工",
            "关窗不撤已接受配置；撤自动预设保留手选。施工仍须材料核验。",
            "inspector",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.CLOSE_TARGET, "properties.target"),
            action(Operation.OPEN_SOURCE, "properties.source"),
            control(165));
    }

    private static void cut(List<Step> s) {
        add(
            s,
            "电力高炉教学列表",
            "共用工作台剪切列表和原样渲染引擎；两行来自同一份电力高炉采集规划，仅在教学内存中。",
            "cut.list",
            action(Operation.HOVER, "cut.list"));
        add(
            s,
            "切换条目预览",
            "点击另一行，右侧立即显示该条目的原样预览；切换条目重置预览相机。",
            "cut.preview",
            action(Operation.CUT_LIST_CLICK, "cut.row.tutorial-cut-b"));
        add(
            s,
            "旋转预览相机",
            "在预览中按住左键拖动旋转；滚轮缩放也只影响此预览，不移动世界目标。",
            "cut.preview",
            action(Operation.CUT_LIST_ORBIT, "cut.preview"));
        add(
            s,
            "同步列表入口",
            "真实窗口会请求服务器目录；本段只刷新教学内存，不发送请求。放置与撤回留在世界操作课程。",
            "cut.sync",
            action(Operation.CUT_LIST_CLICK, "cut.sync"));
        add(
            s,
            "返回原列表",
            "点击完成回到原工作台；真实选择、滚动与预览相机保留。教学预览显示规划，不证明机器已经成型。",
            "cut.browser",
            action(Operation.HOVER, "cut.list"));
    }

    private static void world(List<Step> s, String topic) {
        boolean iso = topic.endsWith("iso"), cut = topic.startsWith("cut-");
        add(
            s,
            iso ? "进入等距上下文" : "选择操作模式",
            (iso ? "使用等距相机查看" : "使用透视相机查看") + "真实采集的电力高炉规划。以下选区与暂存事务只改变教学内存，未证明实体机器成型。",
            "viewport",
            action(Operation.WORLD_MODE, "viewport"));
        add(
            s,
            "第一角",
            iso ? "Ctrl + 左键设置第一角，角点按世界格记录。" : "左键设置来源第一角。",
            "viewport",
            action(Operation.FIRST_CORNER, "world.corner.first"));
        add(
            s,
            "第二角",
            iso ? "Ctrl + 右键设置第二角；提交前先松 Ctrl。" : "右键设置第二角，两角围成包含边界的体积。",
            "viewport",
            action(Operation.SECOND_CORNER, "world.corner.second"));
        add(
            s,
            "调整选区",
            "拖动选区第二角沿 XZ 扩展；边框按整数格跟随，每帧重新计算框内实际采集格。",
            "viewport",
            action(Operation.WORLD_MOVE_XZ, "world.handle.xz"));
        if (!cut) {
            add(
                s,
                "提交框选",
                "读取框内电力高炉采集格，验证选区非空；结果仅保留于教学内存。真实操作按当前模式提交选区。",
                "viewport",
                action(Operation.BOX_SUBMIT, "viewport"));
            return;
        }
        add(
            s,
            "来源确认与命名",
            "将完整电力高炉规划复制到独立教学暂存，再从教学场景移除原格；真实世界与真实剪切目录不变。",
            "viewport",
            action(Operation.CUT_SOURCE_REVIEW, "viewport"),
            action(Operation.CUT_STORE, "viewport"));
        add(
            s,
            "锁定与取消",
            iso ? "左键锁定，再次左键打开确认。Esc 保留未消费条目。" : "右键锁定，再次右键打开确认。Esc 保留未消费条目。",
            "viewport",
            action(Operation.CUT_TAKE, "viewport"),
            action(Operation.CUT_LOCK, "viewport"),
            action(Operation.CUT_CANCEL, "viewport"));
        add(
            s,
            "放置与消费",
            "重新取出并锁定教学几何；逐格核对移动后的坐标与完整数据相等，才消费教学暂存。真实世界不执行放置。",
            "viewport",
            action(Operation.CUT_TAKE, "viewport"),
            action(Operation.CUT_LOCK, "viewport"),
            action(Operation.CUT_PLACE, "viewport"));
    }
}

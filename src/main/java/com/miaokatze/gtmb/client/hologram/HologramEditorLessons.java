package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Domain lessons contain intent and stable control identities, never drawing or screen coordinates. */
final class HologramEditorLessons {

    enum Operation {
        CONTROL,
        SELECT_CELL,
        SELECT_STRUCTURE,
        HANDLES,
        MOVE_X,
        MOVE_XZ,
        COPY_X,
        CLOSE_PANEL,
        LIBRARY,
        EDITOR,
        SETTINGS,
        CLOSE_TARGET,
        OPEN_TARGET,
        CLOSE_SOURCE,
        OPEN_SOURCE,
        OPEN_MATERIAL,
        TYPE_CAPABILITY,
        APPLY_CAPABILITY,
        PROMPT,
        NBT,
        NETWORK,
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
        CUT_PLACE
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
                || operation == Operation.WORLD_MOVE_XZ;
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

    private static Action control(int id) {
        return new Action(Operation.CONTROL, id, "control." + id);
    }

    private static Action action(Operation operation, String target) {
        return new Action(operation, -1, target);
    }

    private static void add(List<Step> list, String title, String text, String focus, Action... actions) {
        list.add(new Step(title, text, focus, actions));
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
            action(Operation.TYPE_CAPABILITY, "capability"),
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
            control(10000));
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
            action(Operation.MATERIALS, "viewport"));
        add(s, "返回工作区", "结束后恢复原控件树、草稿、相机与选区。", "viewport", action(Operation.CLOSE_PANEL, "modal.close"));
    }

    private static void blueprint(List<Step> s, HologramEditorCommands commands) {
        add(
            s,
            "草稿与蓝图库",
            "右侧库与中央画布共用工作区。客户端和服务器来源分别保留。",
            "library",
            action(Operation.LIBRARY, "control." + (HologramEditorControlIds.SHELL + 22)));
        add(s, "点行切换", "单击库行即时更新中央草稿；未保存内容沿用原有提示。", "library", control(14001));
        add(
            s,
            "精确选格",
            "Ctrl 增减选格；" + commands.shortcut("sameType") + " 扩展同类。点结构轮廓可整选成员。",
            "viewport",
            action(Operation.EDITOR, "control." + (HologramEditorControlIds.SHELL + 20)),
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
            action(Operation.TYPE_CAPABILITY, "capability"),
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
            control(10000));
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
            "图文与选位",
            "空稿生成可直接提交；非空稿新增先以 ghost 选位，同 ID 可重编辑。",
            "viewport",
            action(Operation.CLOSE_PANEL, "modal.close"));
        add(s, "图文确认", "轴线和平面只调整待确认预览；确认前保持原稿。", "viewport", action(Operation.HANDLES, "viewport"));
        add(
            s,
            "注释与区域",
            "提示词、提示区和排除区按对象保存，不代替世界过滤执行。",
            "modal",
            action(Operation.SETTINGS, "control." + (HologramEditorControlIds.SHELL + 21)),
            action(Operation.PROMPT, "control.146"));
        add(
            s,
            "NBT 审阅",
            "字段可解析不等于设备配置有效；配置材料与设备本体分别计费。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.NBT, "control.166"));
        add(
            s,
            "量子组与网络",
            "单独审阅类群、信道和绑定策略；示例不连接真实网络。",
            "modal",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.NETWORK, "viewport"));
        add(
            s,
            "倾向自用",
            "从实际处理入口选择自用倾向；这是处理策略，不是仓室搭配。",
            "processing",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.SETTINGS, "control." + (HologramEditorControlIds.SHELL + 21)),
            control(220),
            control(232));
        add(s, "倾向分享", "切换分享倾向，查看去标识、量子重绑与外部关系策略。", "processing", control(233));
        add(
            s,
            "保存与覆盖",
            "普通另存填写名称；覆盖需明确所选目标。示例不会创建蓝图库文件。",
            "library",
            control(234),
            action(Operation.LIBRARY, "control." + (HologramEditorControlIds.SHELL + 22)));
        add(s, "世界位置先锁定", "真实放置先在世界移动并锁定 ghost；这里从已锁定位置解释预检。", "materials", action(Operation.MATERIALS, "viewport"));
        add(s, "唯一最终确认", "逐项核对需求、已有与缺失；本次 NBT、缓存和补料各有原有门禁。", "materials");
        add(
            s,
            "返回蓝图库",
            "完成或跳过都恢复原窗口、字段、布局与选区。",
            "library",
            action(Operation.CLOSE_PANEL, "modal.close"),
            action(Operation.LIBRARY, "control." + (HologramEditorControlIds.SHELL + 22)));
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
            action(Operation.TYPE_CAPABILITY, "capability"),
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
            control(10000));
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
        add(s, "独立私有暂存", "剪切列表与蓝图库分开，教学条目只存在于隔离内存。", "viewport", action(Operation.CUT_STORE, "viewport"));
        add(s, "条目预览", "选择条目只读预览原样数据，不进入蓝图处理或图文编辑。", "viewport", action(Operation.SELECT_CELL, "viewport"));
        add(
            s,
            "取出与锁定",
            "先移动目标再锁定，锁定后再次点击才打开确认。",
            "viewport",
            action(Operation.CUT_TAKE, "viewport"),
            action(Operation.CUT_LOCK, "viewport"));
        add(s, "取消保留条目", "Esc 保留未消费条目；成功放置才消费，撤回原处是独立流程。", "viewport", action(Operation.CUT_CANCEL, "viewport"));
        add(s, "返回原列表", "完成后回到原列表对象，保留真实选择与预览相机。", "viewport");
    }

    private static void world(List<Step> s, String topic) {
        boolean iso = topic.endsWith("iso"), cut = topic.startsWith("cut-");
        add(
            s,
            iso ? "进入等距上下文" : "选择操作模式",
            iso ? "从已启动的框选或剪切上下文按默认 V 进入等距。" : "Shift + 右键选模式；松 Shift 只完成模式选择。",
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
            "六面手柄与 XZ 平面调整隔离选区，指针连续移动、世界格整格吸附。",
            "viewport",
            action(Operation.HANDLES, "viewport"),
            action(Operation.WORLD_MOVE_XZ, "world.handle.xz"));
        if (!cut) {
            add(
                s,
                "提交框选",
                iso ? "松 Ctrl 后 Shift + 左键提交；预演不会导入真实蓝图。" : "另按 Shift + 右键提交；预演不会覆盖真实蓝图。",
                "viewport",
                action(Operation.BOX_SUBMIT, "viewport"));
            return;
        }
        add(
            s,
            "来源确认与命名",
            "确认前来源保持原状；命名确认后才进入独立暂存。",
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
            "重新取出并锁定，在确认窗点击确认；成功后原条目才消费。",
            "viewport",
            action(Operation.CUT_TAKE, "viewport"),
            action(Operation.CUT_LOCK, "viewport"),
            action(Operation.CUT_PLACE, "viewport"));
    }
}

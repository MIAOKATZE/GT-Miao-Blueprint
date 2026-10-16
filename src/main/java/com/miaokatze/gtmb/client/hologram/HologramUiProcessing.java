package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

/** Internal processing workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiProcessing {

    private final HologramUiModules ui;

    HologramUiProcessing(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean retainNbt = true;

    private boolean retainCovers = true;

    private HologramProcessingFlow processingFlow;

    private boolean processingFullReview;

    private boolean processingPresetSaveReview;

    private GuiTextField processingPresetField;

    private String selectedProcessingPreset = "";

    private long processingPresetLoadSequence = -1;

    boolean processingReady() {
        return !ui.session.state().data.getBoolean("blueprintProcessingRequired")
            || ui.session.state().data.getBoolean("blueprintProcessingComplete");
    }

    boolean canEditBlueprintNbt() {
        return ui.processing.processingReady() || ui.session.state().data.getBoolean("blueprintSaved");
    }

    NBTTagCompound processingState() {
        return ui.session.state().data.getCompoundTag("processing");
    }

    boolean quantumGroupsConfirmed() {
        return ui.processing.processingState()
            .getTagList("quantumNodes", 10)
            .tagCount() == 0 || ui.processing.processingState()
                .getBoolean("quantumGroupsConfirmed");
    }

    boolean processingChoiceReady() {
        return this.processingFlow.chosen();
    }

    long saveQuantumGroups(NBTTagList groups, long sourceRevision) {
        if (sourceRevision != ui.session.revision()) return -1;
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", sourceRevision);
        request.setTag("groups", groups.copy());
        ui.session.send("configureBlueprintQuantumGroups", request, true);
        return ui.session.sequence();
    }

    void quantumGroupsSaved() {
        if (this.processingFlow != null && this.processingFlow.stage == 2) this.processingFlow.choose();
        ui.session.notice("本图量子类群已保存");
    }

    NBTTagList processingList(String key, int type) {
        String alias = "relations".equals(key) ? "processingRelations"
            : "nbtKeys".equals(key) ? "processingNbtKeys" : "processingNbtPaths";
        return ui.session.state().data.hasKey(alias) ? ui.session.state().data.getTagList(alias, type)
            : ui.processing.processingState()
                .getTagList(key, type);
    }

    void drawProcessingFlow(int mx, int my) {
        drawRect(28, 48, 650, 304, 0xff172f36);
        int stage = this.processingFlow.stage;
        NBTTagCompound policy = this.processingFlow.values();
        ui.canvas.text(
            HologramUiText.localized("gtmb.hologram.processing.title", "蓝图处理") + " · "
                + (stage + 1)
                + "/6 · "
                + HologramUiText.localized(
                    "gtmb.hologram.processing." + HologramProcessingFlow.STAGES[stage],
                    new String[] { "流程预设", "去标识等级", "量子网络", "坐标关系", "全局 NBT", "自动提示词" }[stage]),
            42,
            58,
            590,
            0xf5e4b9);
        ui.canvas.button(
            232,
            42,
            76,
            126,
            17,
            "倾向自用" + ("self".equals(policy.getString("tendency")) ? " ✓" : ""),
            true,
            mx,
            my);
        ui.canvas.button(
            233,
            174,
            76,
            126,
            17,
            "倾向分享" + ("share".equals(policy.getString("tendency")) ? " ✓" : ""),
            true,
            mx,
            my);
        ui.canvas
            .text(this.processingFlow.fullReview() ? "倾向自动预选；每一步仍需下一步确认" : "按实际内容处理；空阶段可跳过", 312, 81, 318, 0x94afb4);
        if (stage == 0) {
            ui.canvas.text("选择已保存的流程，或明确不使用预设；应用后仍逐步核对", 42, 103, 588, 0xb9d5d4);
            ui.canvas.button(
                235,
                42,
                123,
                588,
                18,
                this.processingFlow.chosen() ? "✓ 不使用其它流程预设 / 继续当前选择" : "不使用流程预设",
                true,
                mx,
                my);
            NBTTagList presets = ui.session.state().data.getTagList("processingPresets", 10);
            for (int i = this.processingFlow.offset; i
                < Math.min(presets.tagCount(), this.processingFlow.offset + 3); i++)
                ui.canvas.button(
                    70000 + i,
                    42,
                    145 + (i - this.processingFlow.offset) * 22,
                    588,
                    19,
                    presets.getCompoundTagAt(i)
                        .getString("name"),
                    true,
                    mx,
                    my);
            if (presets.tagCount() == 0) ui.canvas.text("暂无流程预设；完成一次处理后可保存供下次复用", 42, 154, 588, 0x94afb4);
            this.processingPresetField.drawTextBox();
            ui.canvas.button(
                293,
                342,
                220,
                91,
                18,
                "新建流程",
                ui.processing.processingReady() && ui.session.editable(),
                mx,
                my);
            ui.canvas.button(
                294,
                439,
                220,
                91,
                18,
                "更新所选",
                ui.processing.processingReady() && ui.session.editable() && !this.selectedProcessingPreset.isEmpty(),
                mx,
                my);
            ui.canvas.button(
                295,
                536,
                220,
                94,
                18,
                "删除所选",
                ui.session.editable() && !this.selectedProcessingPreset.isEmpty(),
                mx,
                my);
        } else if (stage == 1) {
            ui.canvas.text("等级 0 保留原身份，1 清理已登记网络身份；更高等级逐步清理位置等字段", 42, 103, 588, 0xb9d5d4);
            int minimum = Math.max(
                0,
                Math.min(
                    10,
                    ui.processing.processingState()
                        .getInteger("minimumLevel")));
            for (int level = 0; level <= 10; level++) ui.canvas.button(
                240 + level,
                42 + level * 53,
                126,
                49,
                21,
                (policy.getInteger("deidentificationLevel") == level ? "✓ " : "") + level,
                level >= minimum,
                mx,
                my);
            ui.canvas.text(
                new String[] { "保留原身份", "清理登记网络身份", "同时清理登记位置与维度", "同时清理登记频率与频道", "同时清理登记安全标识", "同时清理登记代理与链接",
                    "同时清理登记多方块屏幕关联", "同时清理运行缓存与历史采样", "同时清理界面标签状态", "同时清理停机状态", "同时清理活动与采样时间状态" }[Math
                        .max(0, Math.min(10, policy.getInteger("deidentificationLevel")))],
                42,
                163,
                588,
                0xb9d5d4);
            ui.canvas.text("按已登记设备字段逐级处理；已清除的数据无法恢复", 42, 210, 588, 0x94afb4);
            ui.canvas.text("当前最低等级 " + minimum + "；只有点击等级才确认本步骤", 42, 185, 588, 0x94afb4);
        } else if (stage == 2) {
            ui.canvas.text("自用保留原网络；分享使用终端改绑。节点分类可选，不设置时默认所有节点一组。", 42, 103, 588, 0xb9d5d4);
            ui.canvas.button(
                262,
                42,
                121,
                588,
                22,
                ("preserve".equals(policy.getString("quantum")) ? "✓ " : "") + "自用：保留原量子绑定（仅原存档、原网络位置与维度有效）",
                true,
                mx,
                my);
            ui.canvas.button(
                260,
                42,
                149,
                588,
                22,
                ("rebind".equals(policy.getString("quantum")) ? "✓ " : "") + "分享：去除源网络锚定，逐类群选择已绑定目标网络的量子终端",
                true,
                mx,
                my);
            ui.canvas.button(
                261,
                42,
                177,
                588,
                22,
                ("clear".equals(policy.getString("quantum")) ? "✓ " : "") + "完全清除分组，放置为未绑定节点",
                true,
                mx,
                my);
            ui.canvas.button(
                263,
                42,
                208,
                588,
                23,
                ui.processing.quantumGroupsConfirmed() ? "量子节点网络分类 · 可选，编辑已有分类" : "量子节点网络分类 · 可选，默认一组直接继续",
                ui.session.editable() && ui.processing.processingState()
                    .getTagList("quantumNodes", 10)
                    .tagCount() > 0,
                mx,
                my);
            ui.canvas.text("可直接下一步；需要多个网络时再分类，自定义分类必须完整分配后保存。", 42, 235, 588, 0x94afb4);
        } else if (stage == 3) {
            ui.canvas.text("每项坐标可清除、保留绝对位置、或随蓝图相对移动", 42, 103, 588, 0xb9d5d4);
            if (mx >= 42 && mx < 630 && my >= 100 && my < 140) ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(
                        "推荐规则：内部关系随蓝图相对移动；外部关系在自用时保留绝对位置，分享时清除。相对只保存映射，清除留下待设置标记。只有已登记且审阅的关系字段按该选择处理，其它设备配置、库存和资产仍精确核对。",
                        430));
            String[] defaults = { "auto", "clear", "absolute", "relative" },
                defaultNames = { "推荐规则", "清除", "绝对位置", "相对蓝图" };
            String[] modes = { "clear", "absolute", "relative" }, names = { "清除", "绝对位置", "相对蓝图" };
            for (int i = 0; i < 4; i++) ui.canvas.button(
                270 + i,
                42 + i * 148,
                120,
                142,
                18,
                (defaults[i].equals(policy.getString("coordinateDefault")) ? "✓ " : "") + "默认：" + defaultNames[i],
                true,
                mx,
                my);
            NBTTagList relations = ui.processing.processingList("relations", 10);
            for (int i = this.processingFlow.offset; i
                < Math.min(relations.tagCount(), this.processingFlow.offset + 4); i++) {
                NBTTagCompound relation = relations.getCompoundTagAt(i);
                int y = 146 + (i - this.processingFlow.offset) * 27;
                String location = "(" + relation.getInteger(
                    "targetX") + ", " + relation.getInteger("targetY") + ", " + relation.getInteger("targetZ") + ")";
                ui.canvas.text(
                    relation.getString("label") + " · "
                        + (relation.getBoolean("internal") ? "蓝图内 " : "蓝图外 ")
                        + location,
                    42,
                    y + 5,
                    270,
                    0xb9d5d4);
                if (mx >= 42 && mx < 312 && my >= y && my < y + 22) ui.canvas.tooltip(
                    ui.host.font()
                        .listFormattedStringToWidth(
                            relation.getString("label") + "：目标维度 "
                                + relation.getInteger("targetDimension")
                                + "，坐标 "
                                + location
                                + "。"
                                + (relation.getBoolean("internal") ? "目标位于蓝图内，推荐随蓝图移动和旋转校准。"
                                    : "目标位于蓝图外，请选择保留绝对位置、校准相对位置或清除后重设。"),
                            430));
                for (int mode = 0; mode < 3; mode++) ui.canvas.button(
                    71000 + i * 3 + mode,
                    316 + mode * 106,
                    y,
                    102,
                    22,
                    (modes[mode].equals(ui.processing.coordinateDisplayMode(relation)) ? "✓ " : "") + names[mode],
                    true,
                    mx,
                    my);
                if (mx >= 316 && mx < 418
                    && my >= y
                    && my < y + 22
                    && !relation.getString("clearDetail")
                        .isEmpty())
                    ui.canvas.tooltip(
                        ui.host.font()
                            .listFormattedStringToWidth(relation.getString("clearDetail"), 430));
            }
            if (relations.tagCount() == 0) ui.canvas.text("没有已登记坐标关系；请选择默认方式确认本步骤", 42, 155, 588, 0x94afb4);
        } else if (stage == 4) {
            ui.canvas.text("忽略根条目会清除所有方块的同名 NBT；方块细节可在处理后继续编辑", 42, 103, 588, 0xb9d5d4);
            ui.canvas.button(280, 42, 122, 288, 18, "全部保留并确认", true, mx, my);
            ui.canvas.button(281, 342, 122, 288, 18, "确认当前忽略清单", true, mx, my);
            NBTTagList keys = ui.processing.processingList("nbtKeys", 8);
            for (int i = this.processingFlow.offset; i
                < Math.min(keys.tagCount(), this.processingFlow.offset + 5); i++) {
                String key = keys.getStringTagAt(i);
                ui.canvas.button(
                    72000 + i,
                    42,
                    147 + (i - this.processingFlow.offset) * 20,
                    588,
                    17,
                    (this.processingFlow.contains("ignoredNbtKeys", key) ? "忽略 × " : "保留 ✓ ") + key,
                    true,
                    mx,
                    my);
                if (mx >= 42 && mx < 630
                    && my >= 147 + (i - this.processingFlow.offset) * 20
                    && my < 164 + (i - this.processingFlow.offset) * 20)
                    ui.canvas.tooltip(
                        ui.host.font()
                            .listFormattedStringToWidth(
                                key + "：" + HologramUiText.nbtMeaning(key) + " 忽略后不会保存这一根条目；资源与身份保护仍由服务端复核。",
                                430));
            }
            if (mx >= 42 && mx < 630 && my >= 103 && my < 140) ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(
                        "NBT 包含设备配置、网络身份、库存、流体与能量。忽略某根条目会从所有匹配方块移除该项；身份保护和资源校验仍由服务端执行。方块级忽略路径在后续 NBT 编辑中设置。",
                        430));
        } else {
            ui.canvas.text("自动提示补充已登记配置问题，不改写您已有的提示词", 42, 103, 588, 0xb9d5d4);
            String[] values = { "light", "full", "none" }, names = { "轻度 · 关键绑定与放置提醒", "完整 · 全部已登记问题说明", "不自动添加提示词" };
            for (int i = 0; i < 3; i++) ui.canvas.button(
                290 + i,
                42,
                126 + i * 33,
                588,
                25,
                (values[i].equals(policy.getString("autoHints")) ? "✓ " : "") + names[i],
                true,
                mx,
                my);
            ui.canvas.text("完成后可保存蓝图、复用流程预设，以及继续编辑各方块 NBT", 42, 238, 588, 0x94afb4);
        }
        ui.canvas.button(
            296,
            42,
            249,
            588,
            18,
            this.processingFlow.fullReview() ? "✓ 完整六步核对 · 点击改为按内容处理" : "手动完整六步核对 · 点击从第一步开始",
            ui.session.editable(),
            mx,
            my);
        ui.canvas.button(230, 42, 274, 126, 20, "上一步", stage > 0, mx, my);
        ui.canvas.button(234, 174, 274, 126, 20, "取消", true, mx, my);
        ui.canvas.button(
            231,
            312,
            274,
            318,
            20,
            stage == 5 ? "完成处理 · 等待服务器校验" : this.processingFlow.optional() ? "下一步 · 本步可跳过" : "下一步",
            ui.session.editable() && ui.processing.processingChoiceReady(),
            mx,
            my);
    }

    boolean activateProcessing(int id) {
        if (id == 220) {
            if (!HologramGuidance.authorWorkspace(ui.session.state().data)) return true;
            NBTTagCompound policy = ui.session.state().data.hasKey("processingPolicy")
                ? ui.session.state().data.getCompoundTag("processingPolicy")
                : ui.processing.processingState()
                    .getCompoundTag("policy");
            this.processingFlow = new HologramProcessingFlow(
                policy,
                ui.session.state().data.getBoolean("processingResumeAllowed"),
                this.processingFullReview);
            this.processingFlow.requirements(
                ui.processing.processingState()
                    .hasKey("requiredStages")
                        ? ui.processing.processingState()
                            .getInteger("requiredStages")
                        : 63);
            this.processingPresetField = new GuiTextField(ui.host.font(), 42, 220, 288, 18);
            this.processingPresetField.setMaxStringLength(64);
            this.processingPresetField.setText(
                ui.library.presetNameField()
                    .getText()
                    .trim() + " · 流程");
            return true;
        }
        if (id == 221) {
            if (!ui.processing.processingReady()) return true;
            this.processingPresetSaveReview = true;
            this.processingPresetField = new GuiTextField(ui.host.font(), 96, 150, 486, 18);
            this.processingPresetField.setMaxStringLength(64);
            this.processingPresetField.setText(
                ui.library.presetNameField()
                    .getText()
                    .trim() + " · 流程");
            this.processingPresetField.setFocused(true);
            return true;
        }
        if (this.processingFlow == null) return false;
        if (id == 296) {
            this.processingFullReview = !this.processingFlow.fullReview();
            this.processingFlow.fullReview(this.processingFullReview);
            return true;
        }
        NBTTagCompound values = this.processingFlow.values();
        if (id == 234) {
            this.processingFlow = null;
            return true;
        }
        if (id == 230) {
            this.processingFlow.previous();
            return true;
        }
        if (id == 232 || id == 233) {
            this.processingFlow.tendency(id == 233);
            values.setInteger(
                "deidentificationLevel",
                Math.max(
                    values.getInteger("deidentificationLevel"),
                    ui.processing.processingState()
                        .getInteger("minimumLevel")));
            return true;
        }
        if (id == 231) {
            if (!ui.processing.processingChoiceReady()) return true;
            if (this.processingFlow.stage < 5) this.processingFlow.next();
            else {
                this.processingFlow.next();
                if (!this.processingFlow.complete()) return true;
                NBTTagCompound request = new NBTTagCompound();
                request.setTag("policy", this.processingFlow.policy());
                this.processingFlow = null;
                ui.session.send("processBlueprint", request, true);
            }
            return true;
        }
        if (id == 293 || id == 294 || id == 295) {
            NBTTagCompound request = new NBTTagCompound();
            request.setString("presetId", this.selectedProcessingPreset);
            request.setString(
                "name",
                this.processingPresetField.getText()
                    .trim());
            request.setTag(
                "policy",
                ui.session.state().data.getCompoundTag("processingPolicy")
                    .copy());
            ui.session.send(
                id == 293 ? "saveProcessingPreset" : id == 294 ? "updateProcessingPreset" : "deleteProcessingPreset",
                request,
                true);
            if (id == 295) this.selectedProcessingPreset = "";
            return true;
        }
        if (id == 235) this.processingFlow.choose();
        else if (id >= 70000 && id < 71000) {
            NBTTagCompound row = ui.session.state().data.getTagList("processingPresets", 10)
                .getCompoundTagAt(id - 70000);
            this.processingFlow.preset(row.getCompoundTag("policy"));
            this.selectedProcessingPreset = row.getString("id");
            this.processingPresetField.setText(row.getString("name"));
            NBTTagCompound request = new NBTTagCompound();
            request.setString("presetId", this.selectedProcessingPreset);
            ui.session.send("loadProcessingPreset", request, true);
            this.processingPresetLoadSequence = ui.session.sequence();
        } else if (id >= 71000 && id < 72000) {
            int index = (id - 71000) / 3, mode = (id - 71000) % 3;
            this.processingFlow.coordinate(
                ui.processing.processingList("relations", 10)
                    .getCompoundTagAt(index)
                    .getString("id"),
                new String[] { "clear", "absolute", "relative" }[mode]);
            this.processingFlow.choose();
        } else if (id >= 72000 && id < 73000) {
            this.processingFlow.toggle(
                "ignoredNbtKeys",
                ui.processing.processingList("nbtKeys", 8)
                    .getStringTagAt(id - 72000));
            this.processingFlow.choose();
        } else if (id >= 240 && id <= 250) {
            values.setInteger("deidentificationLevel", id - 240);
            this.processingFlow.choose();
        } else if (id == 260 || id == 261 || id == 262) {
            values.setString("quantum", id == 260 ? "rebind" : id == 261 ? "clear" : "preserve");
            this.processingFlow.choose();
        } else if (id == 263) {
            if (ui.session.editable() && ui.processing.processingState()
                .getTagList("quantumNodes", 10)
                .tagCount() > 0) ui.host.minecraft()
                    .displayGuiScreen(new HologramQuantumGroupsScreen(ui.host.parent(), ui.session.state()));
        } else if (id >= 270 && id <= 273) {
            values.setString("coordinateDefault", new String[] { "auto", "clear", "absolute", "relative" }[id - 270]);
            this.processingFlow.choose();
        } else if (id == 280 || id == 281) {
            if (id == 280) values.setTag("ignoredNbtKeys", new NBTTagList());
            this.processingFlow.choose();
        } else if (id >= 290 && id <= 292) {
            values.setString("autoHints", new String[] { "light", "full", "none" }[id - 290]);
            this.processingFlow.choose();
        }
        return true;
    }

    String coordinateDisplayMode(NBTTagCompound relation) {
        String mode = this.processingFlow.coordinate(relation.getString("id"));
        if (!"auto".equals(mode)) return mode;
        return relation.getBoolean("internal") ? "relative"
            : "share".equals(
                this.processingFlow.values()
                    .getString("tendency")) ? "clear" : "absolute";
    }

    boolean confirmPresetSave(int id) {
        if (id == 224 && this.processingPresetSaveReview) {
            String name = this.processingPresetField.getText()
                .trim();
            if (name.isEmpty()) return true;
            NBTTagCompound request = new NBTTagCompound();
            request.setString("name", name);
            request.setTag(
                "policy",
                ui.session.state().data.getCompoundTag("processingPolicy")
                    .copy());
            this.processingPresetSaveReview = false;
            ui.session.send("saveProcessingPreset", request, true);
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        switch (id) {
            case 166:
                if (!ui.processing.canEditBlueprintNbt()) return true;
                ui.nbt.openNbtEditor(false);
                return true;
            case 142:
                this.retainNbt = !this.retainNbt;
                return true;
            case 143:
                this.retainCovers = !this.retainCovers;
                return true;
            case 132:
            case 140:
                if (!ui.session.editable() || !ui.processing.processingReady() || ui.session.state().cells.isEmpty())
                    return true;
                String presetName = ui.library.presetNameField()
                    .getText()
                    .trim();
                if (id == 140 && presetName.isEmpty())
                    presetName = ui.session.state().data.getString("virtualPresetName");
                if (presetName.isEmpty()) {
                    ui.session.notice("请输入预设名称");
                    return true;
                }
                NBTTagCompound savePreset = new NBTTagCompound();
                savePreset.setString("name", presetName);
                savePreset.setBoolean("retainNbt", this.retainNbt);
                savePreset.setBoolean("retainCovers", this.retainCovers);
                savePreset.setBoolean(
                    "retainAllNbt",
                    !ui.session.state().data.hasKey("blueprintNbtAll")
                        || ui.session.state().data.getBoolean("blueprintNbtAll"));
                if (ui.session.state().data.hasKey("blueprintNbtKeys")) savePreset.setTag(
                    "retainedNbtKeys",
                    ui.session.state().data.getTagList("blueprintNbtKeys", 8)
                        .copy());
                ui.identity.deidentificationSaveOptions(savePreset);
                ui.library.libraryResult(ui.session.notice(id == 140 ? "正在保存，请等待服务器确认" : "正在另存为新蓝图，请等待服务器确认"));
                HologramLibraryClient.INSTANCE.expectDraftDownload();
                ui.session.send("saveBlueprintAs", savePreset, true);
                ui.library.presetSaveSequence(ui.session.sequence());
                return true;
            default:
                return false;
        }
    }

    boolean consumeAuthoringModalMouse(int mx, int my, int button) {
        if (this.processingFlow != null || ui.library.discardReview()
            || ui.library.overwriteReview()
            || this.processingPresetSaveReview) {
            if (this.processingPresetSaveReview) {
                this.processingPresetField.mouseClicked(mx, my, button);
                if (mx >= 96 && mx < 582 && my >= 150 && my < 168) return true;
            }
            if (this.processingFlow != null && this.processingFlow.stage == 0) {
                this.processingPresetField.mouseClicked(mx, my, button);
                if (mx >= 42 && mx < 330 && my >= 220 && my < 238) return true;
            }
            if (button == 0) for (Control control : ui.canvas.controls()) if (control.contains(mx, my)) {
                if (control.enabled) ui.router.activate(control.id);
                return true;
            }
            return true;
        }
        return false;
    }

    boolean consumeProcessingKey(char c, int key) {
        if (this.processingFlow != null) {
            if (key == Keyboard.KEY_ESCAPE) ui.processing.activateProcessing(234);
            else if (this.processingFlow.stage == 0 && this.processingPresetField.isFocused())
                this.processingPresetField.textboxKeyTyped(c, key);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)
                ui.processing.activateProcessing(231);
            return true;
        }
        return false;
    }

    boolean consumeProcessingWheel(int wheel) {
        if (this.processingFlow != null) {
            int count = this.processingFlow.stage == 0 ? ui.session.state().data.getTagList("processingPresets", 10)
                .tagCount()
                : this.processingFlow.stage == 3 ? ui.processing.processingList("relations", 10)
                    .tagCount()
                    : this.processingFlow.stage == 4 ? ui.processing.processingList("nbtKeys", 8)
                        .tagCount() : 0;
            int rows = this.processingFlow.stage == 0 ? 3 : this.processingFlow.stage == 4 ? 5 : 4;
            this.processingFlow.offset = Math
                .max(0, Math.min(Math.max(0, count - rows), this.processingFlow.offset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    void acknowledgePreset(HologramState next) {
        if (processingPresetLoadSequence >= 0 && next.data.getLong("uiSequence") >= processingPresetLoadSequence) {
            processingPresetLoadSequence = -1;
            if (processingFlow != null) {
                if (next.data.getBoolean("processingPresetOperationSuccess"))
                    processingFlow.preset(next.data.getCompoundTag("processingPolicy"));
                else processingFlow.clearChoice();
            }
        }
    }

    void rejectCommand(long sequence) {
        if (processingPresetLoadSequence == sequence) processingPresetLoadSequence = -1;
    }

    HologramProcessingFlow processingFlow() {
        return this.processingFlow;
    }

    GuiTextField processingPresetField() {
        return this.processingPresetField;
    }

    boolean processingPresetSaveReview() {
        return this.processingPresetSaveReview;
    }

    boolean processingPresetSaveReview(boolean value) {
        this.processingPresetSaveReview = value;
        return value;
    }

    boolean retainCovers() {
        return this.retainCovers;
    }

    boolean retainCovers(boolean value) {
        this.retainCovers = value;
        return value;
    }

    boolean retainNbt() {
        return this.retainNbt;
    }

    boolean retainNbt(boolean value) {
        this.retainNbt = value;
        return value;
    }

    void tickField() {
        if (ui.processing.processingPresetField() != null
            && (ui.processing.processingFlow() != null || ui.processing.processingPresetSaveReview()))
            ui.processing.processingPresetField()
                .updateCursorCounter();
    }

    void prepareCommand(String op, NBTTagCompound request) {
        if (!request.hasKey("planRevision") && ("processBlueprint".equals(op) || "saveProcessingPreset".equals(op)
            || "loadProcessingPreset".equals(op)
            || "updateProcessingPreset".equals(op)
            || "deleteProcessingPreset".equals(op))) request.setLong("planRevision", ui.session.revision());
    }

    void initializeSnapshot(HologramState snapshot) {
        retainNbt = !snapshot.data.hasKey("blueprintRetainNbt") || snapshot.data.getBoolean("blueprintRetainNbt");
        retainCovers = !snapshot.data.hasKey("blueprintRetainCovers")
            || snapshot.data.getBoolean("blueprintRetainCovers");
    }
}

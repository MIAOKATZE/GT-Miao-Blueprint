package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.hologram.HologramExtensionExecution;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Internal config workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiConfiguration {

    private final HologramUiModules ui;

    HologramUiConfiguration(HologramUiModules ui) {
        this.ui = ui;
    }

    private HologramLocalAuthorRuntime nativeOwner;
    private long nativeRevision = -1, nativeCellId = -1;
    private List<ItemStack> nativeCandidates = Collections.emptyList();
    private NBTTagCompound nativeDetails = new NBTTagCompound();
    private boolean nativeReviewRequired;

    /** Explicit selected-record native reads, cached by document identity, revision and stable cell ID. */
    List<ItemStack> candidates() {
        if (!ui.session.isLocalAuthor()) {
            HologramState.Cell cell = ui.session.selectedCell();
            return cell == null ? Collections.emptyList() : cell.candidates;
        }
        if (ui.session.selectedCell() == null) return Collections.emptyList();
        selectedNativeDetails();
        return nativeCandidates;
    }

    NBTTagCompound selectedNativeDetails() {
        HologramState.Cell view = ui.session.selectedCell();
        if (view == null) return new NBTTagCompound();
        if (!ui.session.isLocalAuthor()) return view.tag();
        HologramLocalAuthorRuntime runtime = ui.session.author();
        long id;
        try {
            id = runtime.selectionIds(new int[] { ui.preview.selected() })
                .iterator()
                .next();
        } catch (RuntimeException stale) {
            return new NBTTagCompound();
        }
        if (nativeOwner == runtime && nativeRevision == runtime.document()
            .revision() && nativeCellId == id) return nativeDetails;
        nativeOwner = runtime;
        nativeRevision = runtime.document()
            .revision();
        nativeCellId = id;
        nativeCandidates = Collections.emptyList();
        nativeReviewRequired = false;
        nativeDetails = (NBTTagCompound) view.tag()
            .copy();
        try {
            com.google.gson.JsonObject source = runtime.sourceCell(id);
            nativeCandidates = com.miaokatze.gtmb.hologram.HologramLocalAuthorConfigurations.candidates(source);
            nativeDetails.setIntArray(
                "hatchFacings",
                com.miaokatze.gtmb.hologram.HologramLocalAuthorConfigurations.legalFacings(source));
            nativeDetails.setInteger(
                "hatchFacing",
                source.has("facing") ? source.get("facing")
                    .getAsInt() : -1);
            describeCandidates(nativeDetails, nativeCandidates);
            boolean complete = source.has("authorDeclarationComplete") && source.get("authorDeclarationComplete")
                .getAsBoolean();
            nativeDetails.setBoolean("candidateComplete", complete);
            if (!complete) nativeDetails.setString("candidateBoundary", "仅列出已保存并通过本格验证的声明候选；未声称原生枚举完整");
        } catch (HologramExtensionExecution.ReviewRequired serverOnly) {
            nativeReviewRequired = true;
            unavailableCandidates("此扩展需要服务器分析；点击查看可替换方块或类型选择，原稿保留");
        } catch (java.io.IOException | RuntimeException failure) {
            unavailableCandidates(failure.getMessage() == null ? "原生声明读取失败" : failure.getMessage());
        }
        return nativeDetails;
    }

    private static void describeCandidates(NBTTagCompound details, List<ItemStack> candidates) {
        NBTTagList roles = new NBTTagList(), stacks = new NBTTagList();
        int[] tiers = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ItemStack target = candidates.get(i);
            MTEHatch hatch = hatchPrototype(target);
            tiers[i] = hatch == null ? Integer.MAX_VALUE : hatch.mTier;
            roles.appendTag(new net.minecraft.nbt.NBTTagString(hatch == null ? "外壳/方块" : "仓室"));
            stacks.appendTag(target.writeToNBT(new NBTTagCompound()));
        }
        details.setTag("candidateRoles", roles);
        details.setTag("candidates", stacks);
        details.setIntArray("candidateTiers", tiers);
    }

    /** Never expose partial builtin candidates after a legacy applicability callback was withheld. */
    private void unavailableCandidates(String reason) {
        nativeCandidates = Collections.emptyList();
        describeCandidates(nativeDetails, nativeCandidates);
        nativeDetails.setIntArray("hatchFacings", new int[0]);
        nativeDetails.setBoolean("candidateComplete", false);
        nativeDetails.setString("candidateBoundary", reason == null ? "原生候选分析未完成" : reason);
    }

    private boolean selectedReviewCurrent(HologramLocalAuthorRuntime runtime, AuthorDocument frozen, long cellId) {
        if (!ui.session.isLocalAuthor() || ui.session.author() != runtime
            || HologramLocalAuthorRuntime.active() != runtime
            || runtime.document() != frozen
            || nativeOwner != runtime
            || nativeRevision != frozen.revision()
            || nativeCellId != cellId) return false;
        try {
            return runtime.selectionIds(new int[] { ui.preview.selected() })
                .contains(cellId);
        } catch (RuntimeException stale) {
            return false;
        }
    }

    /** Called only by an intentional details action; getters/rendering never send this request. */
    private void requestNativeDetails() {
        selectedNativeDetails();
        if (!nativeReviewRequired || nativeOwner == null) return;
        final HologramLocalAuthorRuntime runtime = nativeOwner;
        final AuthorDocument frozen = runtime.document();
        final long cellId = nativeCellId;
        if (!selectedReviewCurrent(runtime, frozen, cellId)) return;
        if (HologramAuthorReviewClient.busy(runtime)) {
            ui.session.notice("正在服务器核对当前请求；可取消后重新分析本格候选");
            return;
        }
        NBTTagCompound policy = new NBTTagCompound();
        policy.setLong("cellId", cellId);
        nativeDetails.setString("candidateBoundary", "正在服务器分析本格候选与合法朝向；原稿不变");
        ui.session.notice("正在服务器分析所选格的替换候选与朝向；不施工、不扣除材料");
        HologramAuthorReviewClient
            .request(runtime, "candidates", policy, () -> selectedReviewCurrent(runtime, frozen, cellId), reply -> {
                if (!selectedReviewCurrent(runtime, frozen, cellId)) return;
                try {
                    NBTTagCompound report = (NBTTagCompound) reply.getCompoundTag("report")
                        .copy();
                    NBTTagList stacks = report.getTagList("candidates", 10);
                    if (!report.hasKey("candidates", 9) || stacks.tagCount() > 4096
                        || !report.hasKey("hatchFacings", 11)
                        || !report.hasKey("hatchFacing", 3)
                        || !report.hasKey("candidateComplete", 1)) throw new IllegalArgumentException("服务器候选明细无效");
                    List<ItemStack> reviewed = new ArrayList<>();
                    for (int i = 0; i < stacks.tagCount(); i++) {
                        ItemStack candidate = ItemStack.loadItemStackFromNBT(stacks.getCompoundTagAt(i));
                        if (candidate == null || candidate.getItem() == null)
                            throw new IllegalArgumentException("服务器候选包含本客户端不可识别的物品");
                        reviewed.add(candidate);
                    }
                    describeCandidates(report, reviewed);
                    NBTTagCompound installed = (NBTTagCompound) nativeDetails.copy();
                    for (String key : new String[] { "candidates", "candidateRoles", "candidateTiers", "hatchFacings",
                        "hatchFacing", "candidateComplete", "candidateBoundary" }) {
                        installed.removeTag(key);
                        if (report.hasKey(key)) installed.setTag(
                            key,
                            report.getTag(key)
                                .copy());
                    }
                    nativeDetails = installed;
                    nativeCandidates = Collections.unmodifiableList(reviewed);
                    nativeReviewRequired = false;
                    hatchOffset = hatchRole = 0;
                    hatchFilterTier = -1;
                    ui.session.notice("服务器候选分析已返回；选择只更新规划，实际施工仍须独立核验");
                } catch (RuntimeException invalid) {
                    unavailableCandidates(invalid.getMessage());
                    ui.session.notice("服务器候选未应用，原稿保留：" + invalid.getMessage());
                }
            }, failure -> {
                if (!selectedReviewCurrent(runtime, frozen, cellId)) return;
                unavailableCandidates(failure.getMessage() == null ? "服务器候选分析未完成" : failure.getMessage());
                ui.session.notice("服务器候选分析未完成，原稿保留：" + failure.getMessage());
            });
    }

    private HologramMuiTextField mainField;

    private HologramMuiTextField channelNameField;

    private HologramMuiTextField channelValueField;

    private HologramMuiTextField optionValueField;

    private boolean sourceDrawer;

    private boolean hatchDrawer;

    private boolean presetDrawer;

    private boolean roleDropdown;

    private int roleOffset;

    private int hatchOffset;

    private int hatchRole;

    private int hatchFilterTier = -1;

    private int presetTier = 1;

    private boolean presetDowngrade;

    private HologramMuiTextField hatchPresetNameField;
    private static HologramConfigurationPresets presetStore;
    private static final HologramConfigurationPresets.Pending presetPending = new HologramConfigurationPresets.Pending();
    private boolean customPresets;
    private int presetOffset;
    private String selectedHatchPreset = "";

    private int drawerOffset;

    private boolean resetReview;

    private boolean resetCalibration;

    private boolean drawer;

    private String optionChannel = "";

    private long basicConfigurationSequence = -1;

    private long basicConfigurationRevision = -1;

    void drawResetButton(int x, int y, int width, int mx, int my) {
        ui.canvas.button(
            134,
            x,
            y,
            width,
            17,
            ui.session.state().data.getBoolean("structureBuilt") ? "校准为当前" : "重置为默认",
            ui.session.editable() && ui.preview.previewInteractive()
                && ui.session.state().data.getBoolean("target")
                && ui.session.state().data.getBoolean("supported"),
            mx,
            my);
    }

    void drawConfig(int mx, int my) {
        drawRect(
            HologramUiCanvas.panelX,
            HologramUiCanvas.panelY,
            HologramUiCanvas.panelX + HologramScreenLayout.PANEL_WIDTH,
            HologramUiCanvas.panelY + HologramScreenLayout.PANEL_HEIGHT,
            0xef172f36);
        ui.canvas.skin("panel", HologramUiCanvas.panelX, HologramUiCanvas.panelY, HologramScreenLayout.PANEL_WIDTH, 17);
        ui.canvas.text("目标属性", 286, 65, 220, 0xf5e4b9);
        String[] modes = { "补建", "替换", "拆除" };
        for (int i = 0; i < 3; i++) ui.canvas.button(
            70 + i,
            284 + i * 58,
            80,
            55,
            17,
            (ui.session.state().data.getInteger("mode") == i ? "›" : "") + modes[i],
            ui.session.editable(),
            mx,
            my);
        ui.canvas.text(
            ui.preview.selection()
                .size() == 0 ? "点击预览选择编辑范围"
                    : "编辑所选 " + ui.preview.selection()
                        .size() + " 格 · T 同种 / Ctrl 多选",
            286,
            104,
            170,
            0x94afb4);
        HologramState.Cell cell = ui.session.selectedCell();
        ui.canvas.text(
            cell == null ? "在预览中点击选择位置"
                : cell.sourceMissing ? "来源结构缺失" : "位置 " + cell.dx + "," + cell.dy + "," + cell.dz,
            286,
            122,
            168,
            cell != null && cell.sourceMissing ? 0xff7272 : 0xb9d5d4);
        NBTTagCompound grade = ui.config.stableGradeCapability();
        if (grade != null) {
            ui.canvas.text(grade.getString("label"), 286, 140, 220, 0xf5e4b9);
            ui.canvas.text("＋ / − 连续调整等级，保留当前选区", 286, 160, 220, 0x94afb4);
            ui.config.drawGradeSelector(grade, mx, my);
            if (cell != null) ui.canvas.button(95, 284, 200, 224, 17, "查看可替换方块", ui.session.editable(), mx, my);
        } else {
            List<String> roles = ui.config.hatchRoles();
            this.hatchRole = Math.floorMod(this.hatchRole, roles.size());
            ui.canvas.button(
                97,
                284,
                135,
                224,
                17,
                "类型：" + HologramUiConfiguration.roleLabel(roles.get(this.hatchRole)) + " ▾",
                cell != null,
                mx,
                my);
            ui.config.drawTierSelector(284, 155, 224, mx, my);
            if (mx >= 284 && mx < 508 && my >= 135 && my < 173) ui.canvas.tooltip(
                Collections.singletonList(
                    "目标类型：" + HologramUiConfiguration.roleLabel(roles.get(this.hatchRole))
                        + "；目标等级："
                        + (this.hatchFilterTier < 0 ? "未指定" : ui.config.tierName(this.hatchFilterTier))
                        + "；筛选候选后点击具体目标。"
                        + ui.config.candidateNote()));
            List<Integer> indices = ui.config.filteredHatches();
            this.hatchOffset = Math.max(0, Math.min(this.hatchOffset, Math.max(0, indices.size() - 2)));
            int[] available = ui.session.cellTag()
                .getIntArray("candidateAvailable");
            for (int row = 0; row < 2 && row + this.hatchOffset < indices.size(); row++) {
                int index = indices.get(row + this.hatchOffset);
                ItemStack stack = ui.config.candidates()
                    .get(index);
                ui.canvas.drawItem(stack, 284, 177 + row * 20);
                String count = ui.session.state().data.getBoolean("creativeMaterials") ? "∞"
                    : index < available.length ? Integer.toString(available[index]) : "?";
                ui.canvas.button(
                    10000 + index,
                    304,
                    177 + row * 20,
                    204,
                    18,
                    (cell.chosenChoice == index ? "✓ " : "") + stack.getDisplayName() + " ×" + count,
                    ui.session.editable() && ui.preview.canInventoryPin(),
                    mx,
                    my);
                if (mx >= 284 && mx < 508 && my >= 177 + row * 20 && my < 195 + row * 20) ui.canvas.tooltip(
                    ui.host.font()
                        .listFormattedStringToWidth(
                            stack.getDisplayName() + " · 已启用来源可用 " + count + "；仅更新规划目标。" + ui.config.candidateNote(),
                            230));
            }
            if (indices.isEmpty()) {
                NBTTagCompound primary = ui.config.primaryCapability();
                if (primary != null && !"_hatch_candidate".equals(primary.getString("id"))) {
                    ui.config.drawGradeSelector(primary, mx, my);
                } else ui.canvas.text(
                    cell != null && !cell.candidateComplete ? "未完整识别 / 无此筛选候选" : "无此类型 / 等级候选",
                    286,
                    181,
                    220,
                    0x94afb4);
            }
        }
        ui.canvas
            .text(cell == null ? "仅编辑规划，不立即施工" : "目标：" + ui.preview.cellName(cell, false), 286, 218, 220, 0x94afb4);
        if (cell != null && mx >= 284 && mx < 456 && my >= 120 && my < 134
            || cell != null && mx >= 284 && mx < 508 && my >= 216 && my < 230)
            ui.canvas.tooltip(
                ui.host.font()
                    .listFormattedStringToWidth(
                        ui.preview.cellName(cell, true) + " → "
                            + ui.preview.cellName(cell, false)
                            + "；"
                            + HologramUiText.reason(
                                ui.session.cellTag()
                                    .getString("reason"))
                            + "；"
                            + HologramUiPreview.statusName(cell.status),
                        230));
        ui.canvas.button(112, 284, 231, 40, 17, "复制", ui.session.editable() && cell != null, mx, my);
        ui.canvas.button(66, 327, 231, 75, 17, "恢复默认壳", ui.session.editable() && cell != null && !cell.anchor, mx, my);
        ui.canvas.button(113, 405, 231, 103, 17, "六面转向", ui.session.editable() && cell != null, mx, my);
        ui.canvas.button(
            94,
            284,
            251,
            107,
            15,
            "仓室预设",
            ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
            mx,
            my);
        ui.canvas.button(78, 395, 251, 113, 15, "材料来源", ui.session.editable(), mx, my);
        ui.canvas.button(
            61,
            458,
            100,
            50,
            17,
            "背包选材",
            ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
            mx,
            my);
    }

    void drawGradeSelector(NBTTagCompound grade, int mx, int my) {
        ui.canvas.button(80, 284, 177, 20, 18, "−", ui.session.editable() && ui.config.canStep(grade, -1), mx, my);
        ui.canvas.button(81, 307, 177, 178, 18, ui.config.optionLabel(grade), ui.session.editable(), mx, my);
        ui.canvas.button(82, 488, 177, 20, 18, "+", ui.session.editable() && ui.config.canStep(grade, 1), mx, my);
    }

    NBTTagCompound stableGradeCapability() {
        NBTTagCompound grade = ui.config.primaryCapability();
        if (grade == null || !grade.getBoolean("editable") || "_hatch_candidate".equals(grade.getString("id")))
            return null;
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell == null) return grade;
        if (cell.family.equals("coil") && grade.getString("id")
            .equals("coil")) return grade;
        if (cell.family.startsWith("glass:") && grade.getString("id")
            .equals("glass")) return grade;
        if (cell.family.startsWith("tiered:") && grade.getString("kind")
            .equals("material")) return grade;
        return null;
    }

    boolean structureEnabled(String id) {
        NBTTagList structures = ui.session.state().data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            NBTTagCompound row = structures.getCompoundTagAt(i);
            if (id.equals(row.getString("id"))) return row.getBoolean("enabled");
        }
        return true;
    }

    String selectedStructureId() {
        return ui.preview.selection()
            .structureId(
                ui.session.state(),
                ui.session.state().data.getString("activeStructure"),
                ui.preview.selected());
    }

    void drawResetReview(int mx, int my) {
        drawRect(58, 76, 358, 246, 0xff172f36);
        ui.canvas.text(this.resetCalibration ? "将规划校准为当前现场？" : "将规划重置为结构默认？", 70, 91, 274, 0xe4b85a);
        ui.canvas.text(this.resetCalibration ? "识别现场材质信道，保留独立仓室与朝向。" : "清除自定义目标，恢复结构默认规划。", 70, 118, 274, 0xb9d5d4);
        ui.canvas.text("仅更新规划，不施工，也不删除已存预设。", 70, 141, 274, 0xb9d5d4);
        ui.canvas
            .text(this.resetCalibration ? "未识别的尺寸与信道保留，已识别项可继续修改。" : "操作前请确认当前手改目标是否需要保留。", 70, 162, 274, 0xe4b85a);
        ui.canvas.button(136, 70, 211, 132, 21, "保留当前规划", true, mx, my);
        ui.canvas.button(
            137,
            212,
            211,
            132,
            21,
            this.resetCalibration ? "确认校准为当前" : "确认重置为默认",
            ui.session.editable(),
            mx,
            my);
    }

    void drawFooter(int mx, int my) {
        ui.canvas.drawLocalPlanProgress(38, 260, 398, mx, my);
        int job = ui.session.state().data.getInteger("job");
        ui.canvas.button(
            50,
            452,
            315,
            138,
            17,
            ui.review.placementReviewActive() ? "继续放置预检"
                : ui.session.state().data.getBoolean("virtual")
                    && !ui.session.state().data.getBoolean("virtualPlacement")
                        ? "从蓝图库放置"
                        : ui.session.state().data.getBoolean("virtual")
                            && !ui.session.state().data.getBoolean("virtualLocked")
                                ? "世界右键确定位置"
                                : new String[] { "确认构建", "确认替换", "确认拆除" }[Math
                                    .floorMod(ui.session.state().data.getInteger("mode"), 3)],
            ui.session.editable()
                && (!ui.session.state().data.getBoolean("virtual")
                    || ui.session.state().data.getBoolean("virtualPlacement")
                        && ui.session.state().data.getBoolean("virtualLocked"))
                && ui.session.state().data.getBoolean("target")
                && (ui.session.state().data.getBoolean("supported") || ui.session.state().data.getInteger("mode") == 0),
            mx,
            my);
        ui.canvas.button(
            51,
            594,
            315,
            37,
            17,
            job == 2 ? "继续" : "暂停",
            ui.session.pendingSequence() < 0 && (job == 1 || job == 2),
            mx,
            my);
        ui.canvas.button(
            52,
            635,
            315,
            37,
            17,
            "取消",
            ui.session.pendingSequence() < 0
                && (job == 1 || job == 2 || ui.session.state().data.getBoolean("capturePending")),
            mx,
            my);
    }

    void drawOptions(int mx, int my) {
        NBTTagCompound cap = ui.config.capability(this.optionChannel);
        if (cap == null) {
            this.optionChannel = "";
            return;
        }
        drawRect(160, 59, 405, 257, 0xff172f36);
        ui.canvas.text(cap.getString("label") + " · 选择目标", 168, 65, 203, 0xf5e4b9);
        ui.canvas.button(63, 375, 62, 23, 17, "×", true, mx, my);
        NBTTagList options = cap.getTagList("options", 10);
        for (int i = this.drawerOffset; i < options.tagCount() && i < this.drawerOffset + 7; i++) {
            NBTTagCompound option = options.getCompoundTagAt(i);
            boolean chosen = option.getBoolean("present")
                ? cap.getBoolean("explicit") && option.getInteger("value") == cap.getInteger("current")
                : !cap.getBoolean("explicit");
            int y = 86 + (i - this.drawerOffset) * 18;
            ui.canvas.button(
                4000 + i,
                168,
                y,
                228,
                17,
                (chosen ? "✓ " : "") + option.getString("label"),
                ui.session.editable(),
                mx,
                my);
            if (mx >= 168 && mx < 396 && my >= y && my < y + 17) ui.canvas.tooltip(
                Collections.singletonList(
                    option.getString("label") + " · "
                        + (option.getBoolean("present") ? "显式目标，原始值 " + option.getInteger("value") : "恢复默认值")
                        + (ui.session.editable() ? "；仅更新目标。" : "；" + ui.session.editingReason())));
        }
        if (!"_hatch_candidate".equals(this.optionChannel)) {
            ui.canvas.text("原始正整数（可指定列表之外的值）", 168, 215, 228, 0x94afb4);
            this.optionValueField.drawTextBox();
            ui.canvas.button(68, 311, 232, 85, 16, "应用原始值", ui.session.editable(), mx, my);
        }
    }

    void drawChannels(int mx, int my) {
        ui.canvas.tooltip(null);
        drawRect(157, 59, 405, 257, 0xff172f36);
        ui.canvas.text("等级 / 信道设置（滚轮查看全部）", 165, 65, 205, 0xf5e4b9);
        ui.canvas.button(62, 373, 62, 23, 17, "×", true, mx, my);
        NBTTagList caps = ui.config.capabilities();
        for (int i = this.drawerOffset; i < caps.tagCount() && i < this.drawerOffset + 4; i++) {
            NBTTagCompound cap = caps.getCompoundTagAt(i);
            int y = 86 + (i - this.drawerOffset) * 22;
            String source = cap.getString("id")
                .equals("main") ? "主值"
                    : cap.getBoolean("explicit") ? "指定" : "presence".equals(cap.getString("kind")) ? "默认" : "继承";
            ui.canvas.button(
                2000 + i,
                165,
                y,
                231,
                20,
                cap.getString("label") + "：" + ui.config.optionLabel(cap) + "（" + source + "）",
                ui.session.editable() && cap.getBoolean("editable"),
                mx,
                my);
            if (mx >= 165 && mx < 396 && my >= y && my < y + 20) {
                ui.canvas.tooltip(new ArrayList<>());
                ui.canvas.tooltip()
                    .add(cap.getString("label") + " / " + cap.getString("id"));
                ui.canvas.tooltip()
                    .add("当前目标：" + ui.config.optionLabel(cap) + " · 原始值 " + cap.getInteger("current"));
                ui.canvas.tooltip()
                    .add(
                        cap.getString("id")
                            .equals("main") ? "主原始信号：未独立指定的子信道继承此值。"
                                : cap.getBoolean("explicit") ? "已显式指定；选择继承项可恢复默认。" : "未指定：使用主信号或机器默认规则。");
                ui.canvas.tooltip()
                    .add("具名目标或原始正整数均可设置；机器是否使用信道由自身规则决定。");
                if (!ui.session.editable()) ui.canvas.tooltip()
                    .add(ui.session.editingReason());
            }
        }
        ui.canvas.text("手填 name（小写） / 正整数 value", 165, 178, 231, 0x94afb4);
        this.channelNameField.drawTextBox();
        this.channelValueField.drawTextBox();
        ui.canvas.button(67, 165, 213, 98, 16, "添加 / 更新", ui.session.editable(), mx, my);
        this.mainField.drawTextBox();
        ui.canvas.button(30, 330, 213, 66, 16, "应用主值", ui.session.editable(), mx, my);
        ui.canvas.button(23, 165, 234, 74, 16, "重新采集", ui.session.editable(), mx, my);
        ui.canvas.button(
            33,
            242,
            234,
            74,
            16,
            ui.session.state().data.getBoolean("noHatches") ? "只放外壳 ✓" : "允许仓室",
            ui.session.editable() && ui.session.state().data.getInteger("mode") != 2,
            mx,
            my);
        NBTTagCompound hatch = ui.config.capability("gt_hatch");
        ui.canvas.button(
            83,
            319,
            234,
            77,
            16,
            hatch != null && hatch.getBoolean("explicit") ? "自动选材" : "仓室手选",
            ui.session.editable() && ui.session.state().data.getInteger("mode") != 2
                && hatch != null
                && hatch.getBoolean("editable"),
            mx,
            my);

    }

    boolean sourceEnabled(String key) {
        return !ui.session.state().data.hasKey(key) || ui.session.state().data.getBoolean(key);
    }

    List<String> hatchRoles() {
        List<String> roles = new ArrayList<>();
        roles.add("全部类型");
        NBTTagList tags = ui.session.cellTag()
            .getTagList("candidateRoles", 8);
        for (int i = 0; i < tags.tagCount(); i++) {
            String role = tags.getStringTagAt(i);
            if (!role.isEmpty() && !roles.contains(role)) roles.add(role);
        }
        return roles;
    }

    List<Integer> filteredHatches() {
        List<Integer> indices = new ArrayList<>();
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell == null) return indices;
        List<String> roles = ui.config.hatchRoles();
        this.hatchRole = Math.floorMod(this.hatchRole, roles.size());
        NBTTagList tags = ui.session.cellTag()
            .getTagList("candidateRoles", 8);
        int[] tiers = ui.session.cellTag()
            .getIntArray("candidateTiers");
        for (int i = 0; i < ui.config.candidates()
            .size(); i++) {
            if (this.hatchRole > 0 && !roles.get(this.hatchRole)
                .equals(tags.getStringTagAt(i))) continue;
            if (this.hatchFilterTier >= 0 && (i >= tiers.length || tiers[i] != this.hatchFilterTier)) continue;
            indices.add(i);
        }
        return indices;
    }

    String tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : "固定";
    }

    List<Integer> availableTiers() {
        List<Integer> levels = new ArrayList<>();
        NBTTagList roles = ui.session.cellTag()
            .getTagList("candidateRoles", 8);
        int[] tiers = ui.session.cellTag()
            .getIntArray("candidateTiers");
        List<String> options = ui.config.hatchRoles();
        for (int i = 0; i < tiers.length; i++) {
            if (this.hatchRole > 0 && !options.get(this.hatchRole)
                .equals(roles.getStringTagAt(i))) continue;
            if (tiers[i] >= 0 && !levels.contains(tiers[i])) levels.add(tiers[i]);
        }
        Collections.sort(levels);
        return levels;
    }

    void drawTierSelector(int x, int y, int width, int mx, int my) {
        List<Integer> levels = ui.config.availableTiers();
        boolean available = ui.session.selectedCell() != null;
        ui.canvas.button(
            117,
            x,
            y,
            20,
            17,
            "−",
            available && HologramTargetTiers.steppedTier(levels, this.hatchFilterTier, -1) != this.hatchFilterTier,
            mx,
            my);
        ui.canvas.button(
            98,
            x + 23,
            y,
            width - 46,
            17,
            this.hatchFilterTier < 0 ? "等级：全部" : "等级：" + ui.config.tierName(this.hatchFilterTier),
            available,
            mx,
            my);
        ui.canvas.button(
            118,
            x + width - 20,
            y,
            20,
            17,
            "+",
            available && HologramTargetTiers.steppedTier(levels, this.hatchFilterTier, 1) != this.hatchFilterTier,
            mx,
            my);
    }

    void drawRoleDropdown(int mx, int my) {
        List<String> roles = ui.config.hatchRoles();
        int x = this.hatchDrawer ? 165 : 284, y = this.hatchDrawer ? 103 : 153, width = this.hatchDrawer ? 231 : 224;
        int visible = Math.min(7, roles.size());
        this.roleOffset = Math.max(0, Math.min(this.roleOffset, Math.max(0, roles.size() - visible)));
        drawRect(x - 2, y - 2, x + width + 2, y + visible * 18 + 2, 0xff0c1e28);
        for (int row = 0; row < visible; row++) {
            int index = this.roleOffset + row;
            ui.canvas.button(
                15000 + index,
                x,
                y + row * 18,
                width,
                17,
                (this.hatchRole == index ? "✓ " : "") + HologramUiConfiguration.roleLabel(roles.get(index)),
                true,
                mx,
                my);
        }
        ui.canvas.tooltip(Collections.singletonList("直接选择类型 · 滚轮查看更多 · Esc关闭"));
    }

    String directionName(int facing) {
        String[] names = { "下", "上", "北", "南", "西", "东" };
        return facing >= 0 && facing < names.length ? names[facing] : "默认";
    }

    void drawHatches(int mx, int my) {
        drawRect(157, 59, 405, 281, 0xff172f36);
        ui.canvas.text(
            ui.session.selectedCell() != null && ui.session.selectedCell().candidateComplete ? "本格可替换选项 · 类型 / 等级"
                : "本格已知选项 · 类型 / 等级",
            165,
            65,
            205,
            0xf5e4b9);
        if (mx >= 165 && mx < 370 && my >= 62 && my < 80) ui.canvas.tooltip(
            ui.host.font()
                .listFormattedStringToWidth(ui.config.candidateNote(), 230));
        ui.canvas.button(96, 373, 62, 23, 17, "×", true, mx, my);
        List<String> roles = ui.config.hatchRoles();
        this.hatchRole = Math.floorMod(this.hatchRole, roles.size());
        ui.canvas.button(
            97,
            165,
            84,
            231,
            18,
            "类型：" + HologramUiConfiguration.roleLabel(roles.get(this.hatchRole)) + " ▾",
            true,
            mx,
            my);
        ui.config.drawTierSelector(165, 105, 231, mx, my);
        List<Integer> indices = ui.config.filteredHatches();
        this.hatchOffset = Math.max(0, Math.min(this.hatchOffset, Math.max(0, indices.size() - 5)));
        int[] available = ui.session.cellTag()
            .getIntArray("candidateAvailable");
        int[] tiers = ui.session.cellTag()
            .getIntArray("candidateTiers");
        for (int row = 0; row < 5 && this.hatchOffset + row < indices.size(); row++) {
            int index = indices.get(this.hatchOffset + row);
            ItemStack stack = ui.config.candidates()
                .get(index);
            int y = 128 + row * 21;
            ui.canvas.drawItem(stack, 165, y);
            String count = ui.session.state().data.getBoolean("creativeMaterials") ? "无限"
                : index < available.length ? Integer.toString(available[index]) : "?";
            ui.canvas.button(
                10000 + index,
                186,
                y,
                210,
                19,
                (ui.session.selectedCell().chosenChoice == index ? "✓ " : "") + stack.getDisplayName() + " ×" + count,
                ui.session.editable() && ui.preview.canInventoryPin(),
                mx,
                my);
            if (mx >= 165 && mx < 396 && my >= y && my < y + 19) {
                ui.canvas.tooltip(new ArrayList<>());
                ui.canvas.tooltip()
                    .add(stack.getDisplayName());
                ui.canvas.tooltip()
                    .add((index < tiers.length ? ui.config.tierName(tiers[index]) : "固定等级") + " · 已启用来源可用 " + count);
                ui.canvas.tooltip()
                    .add("仅指定本格目标；施工时重新核对材料。");
                ui.canvas.tooltip()
                    .add(ui.config.candidateNote());
                if (!ui.session.editable()) ui.canvas.tooltip()
                    .add(ui.session.editingReason());
            }
        }
        if (indices.isEmpty()) ui.canvas.text("本筛选没有候选，请更换类型或等级", 165, 125, 231, 0xe4b85a);
        ui.canvas.text("滚轮翻页 · " + indices.size() + " 项", 165, 240, 115, 0x94afb4);
        ui.canvas.button(66, 282, 237, 114, 18, "恢复默认壳", ui.session.editable() && ui.preview.hasSelectedPin(), mx, my);
        int[] facings = ui.session.cellTag()
            .getIntArray("hatchFacings");
        ui.canvas.button(
            99,
            165,
            259,
            139,
            18,
            facings.length == 0 ? "仓室朝向不可用"
                : "仓室朝向：" + ui.config.directionName(
                    ui.session.cellTag()
                        .getInteger("hatchFacing")),
            ui.session.editable() && facings.length > 0,
            mx,
            my);
        ui.canvas.button(
            102,
            308,
            259,
            88,
            18,
            "恢复朝向",
            ui.session.editable() && ui.session.cellTag()
                .getBoolean("hasFacingPin"),
            mx,
            my);
    }

    String candidateNote() {
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell == null) return "请先选择预览中的位置。";
        if (ui.session.isLocalAuthor()) {
            NBTTagCompound details = selectedNativeDetails();
            return details.getBoolean("candidateComplete") ? "选项来自本格原生声明；实际成型与材料仍在放置时核验。"
                : "原生候选尚未确认：" + details.getString("candidateBoundary");
        }
        return cell.candidateComplete ? "选项来自本格结构声明；机器成型仍受整体结构条件限制。"
            : "仅列出已解析选项：" + cell.candidateBoundary + "；机器成型仍受整体结构条件限制。";
    }

    void drawPreset(int mx, int my) {
        drawRect(157, 59, 405, 291, 0xff172f36);
        ui.canvas.text(customPresets ? "仓室预设 · 保留手选及实体仓室" : "仓室预设 · 可选快捷搭配", 165, 65, 205, 0xf5e4b9);
        ui.canvas.button(96, 373, 62, 23, 17, "×", true, mx, my);
        ui.canvas.button(1710, 165, 85, 95, 18, customPresets ? "基础预设" : "✓ 基础预设", true, mx, my);
        ui.canvas.button(1711, 264, 85, 94, 18, customPresets ? "✓ 自定义" : "自定义搭配", true, mx, my);
        ui.canvas.button(1717, 362, 85, 16, 18, "‹", presetOffset > 0, mx, my);
        int count = customPresets ? hatchPresetNames().size() : ui.session.state().data.getInteger("hatchTierMax") + 1;
        presetOffset = Math.max(0, Math.min(presetOffset, Math.max(0, count - 4)));
        ui.canvas.button(1718, 380, 85, 16, 18, "›", presetOffset + 4 < count, mx, my);
        List<String> names = hatchPresetNames();
        for (int row = 0; row < 4 && presetOffset + row < count; row++) {
            int index = presetOffset + row;
            String name = customPresets ? names.get(index) : tierName(index) + " · 按结构声明配额";
            boolean selected = customPresets ? names.get(index)
                .equals(selectedHatchPreset) : index == presetTier;
            ui.canvas.button(1700 + row, 165, 108 + row * 18, 231, 17, (selected ? "✓ " : "") + name, true, mx, my);
        }
        if (customPresets) {
            if (names.isEmpty()) ui.canvas.text("尚无搭配；可命名保存当前仓室目标", 165, 124, 231, 0x94afb4);
            ui.canvas.text("名称", 165, 191, 34, 0x94afb4);
            hatchPresetNameField.drawTextBox();
            ui.canvas.button(
                1712,
                165,
                208,
                75,
                19,
                ui.session.isTutorial() ? "演示不保存" : "命名保存",
                ui.session.editable() && !ui.session.isTutorial(),
                mx,
                my);
            ui.canvas.button(
                1713,
                244,
                208,
                74,
                19,
                "覆盖所选",
                ui.session.editable() && hatchPreset(selectedHatchPreset) != null,
                mx,
                my);
            ui.canvas.button(1714, 322, 208, 74, 19, "删除所选", hatchPreset(selectedHatchPreset) != null, mx, my);
        } else {
            ui.canvas.button(105, 165, 186, 231, 19, presetDowngrade ? "缺料时允许降级 ✓" : "缺料时严格保持等级", true, mx, my);
            ui.canvas.text("保留现场仓室；不选择预设也可确认构建", 165, 213, 231, 0x94afb4);
        }
        ui.canvas.button(1715, 165, 232, 231, 19, "使用上次预设", ui.session.editable() && lastPreset(true) != null, mx, my);
        ui.canvas.button(
            106,
            165,
            260,
            142,
            20,
            customPresets ? "应用所选搭配" : "生成预设目标",
            ui.session.editable() && ui.session.state().data.getBoolean("supported")
                && (customPresets ? hatchPreset(selectedHatchPreset) != null
                    : ui.session.state().data.getBoolean("presetAvailable")),
            mx,
            my);
        ui.canvas.button(
            107,
            312,
            260,
            84,
            20,
            "撤销预设",
            ui.session.editable() && ui.session.state().data.getBoolean("presetActive"),
            mx,
            my);
    }

    private void applyHatchProfile(NBTTagCompound profile, boolean basic) {
        if (!ui.session.editable() || profile == null) return;
        NBTTagCompound config = configuration();
        config.setInteger("hatchTier", profile.getInteger("tier"));
        config.setBoolean("hatchDowngrade", profile.getBoolean("downgrade"));
        config.setBoolean("applyHatchPreset", true);
        if (!basic) config.setTag("hatchProfile", profile.copy());
        presetDrawer = false;
        if (ui.session.isLocalAuthor()) ui.session.localAction("configure", config);
        else ui.session.send("configure", config, true);
    }

    private void manageHatchPreset(int id) {
        if (ui.session.isTutorial()) {
            ui.session.notice("演示隔离：不读取或修改本机仓室搭配文件");
            return;
        }
        try {
            if (id == 1714) {
                presetStore.delete(selectedHatchPreset);
                selectedHatchPreset = "";
                hatchPresetNameField.setText("");
                ui.session.notice("本机仓室搭配已删除");
                return;
            }
            if (!ui.session.editable()) return;
            String name = id == 1713 ? selectedHatchPreset
                : hatchPresetNameField.getText()
                    .trim();
            presetStore.save(name, ui.session.state().data.getCompoundTag("hatchProfile"), id == 1713);
            selectedHatchPreset = name;
            customPresets = true;
            presetOffset = Math.max(0, hatchPresetNames().indexOf(name) - 3);
            ui.session.notice("已保存当前服务端仓室搭配到本机：" + name);
        } catch (java.io.IOException invalid) {
            ui.session.notice(invalid.getMessage());
        }
    }

    void drawSources(int mx, int my) {
        ui.canvas.tooltip(null);
        drawRect(157, 59, 405, 257, 0xff172f36);
        ui.canvas.text("材料来源", 166, 67, 200, 0xf5e4b9);
        ui.canvas.button(88, 375, 62, 23, 17, "×", true, mx, my);
        String[] keys = { "materialMain", "materialContainers", "materialMe" };
        String[] labels = { "主背包", "背包内物品容器", "携带的 ME 无线终端" };
        for (int i = 0; i < keys.length; i++) ui.canvas.button(
            84 + i,
            166,
            94 + i * 25,
            230,
            21,
            labels[i] + (ui.config.sourceEnabled(keys[i]) ? " ✓" : " ×"),
            ui.session.editable()
                && !(ui.session.isLocalAuthor() && ui.session.state().data.getBoolean("materialCataloguePending")),
            mx,
            my);
        int priority = Math.floorMod(ui.session.state().data.getInteger("materialPriority"), 3);
        ui.canvas.button(
            87,
            166,
            175,
            230,
            21,
            "优先：" + new String[] { "主背包", "物品容器", "ME 终端" }[priority],
            ui.session.editable()
                && !(ui.session.isLocalAuthor() && ui.session.state().data.getBoolean("materialCataloguePending")),
            mx,
            my);
        ui.canvas.text(
            new String[] { "主背包 → 容器 → ME", "容器 → 主背包 → ME", "ME → 主背包 → 容器" }[priority],
            166,
            203,
            230,
            0xb9d5d4);
        ui.canvas.button(
            1716,
            166,
            220,
            230,
            19,
            "使用上次预设",
            ui.session.editable()
                && !(ui.session.isLocalAuthor() && ui.session.state().data.getBoolean("materialCataloguePending"))
                && lastPreset(false) != null,
            mx,
            my);
        ui.canvas.text("已绑定、有电、在范围内；遵守网络权限", 166, 245, 230, 0x94afb4);
    }

    String facingName() {
        if (ui.session.isLocalAuthor() && !ui.session.state().data.hasKey("facing", 3)) return "原稿朝向（尚未原生解析）";
        int index = ui.session.state().data.getInteger("facing");
        if (index < 0 || index >= ExtendedFacing.values().length) return "未知";
        ExtendedFacing facing = ExtendedFacing.values()[index];
        String[] names = { "下", "上", "北", "南", "西", "东", "未知" };
        int direction = facing.getDirection()
            .ordinal();
        return names[Math.min(direction, names.length - 1)] + " / "
            + facing.getRotation()
                .ordinal() * 90
            + "°"
            + (facing.getFlip()
                .ordinal() == 0 ? ""
                    : " / 镜像 " + facing.getFlip()
                        .ordinal());
    }

    NBTTagList capabilities() {
        return ui.session.state().data.getTagList("capabilities", 10);
    }

    NBTTagCompound capability(String id) {
        if ("_hatch_candidate".equals(id)) return ui.config.hatchCandidateCapability();
        NBTTagList list = ui.config.capabilities();
        for (int i = 0; i < list.tagCount(); i++) if (id.equals(
            list.getCompoundTagAt(i)
                .getString("id")))
            return list.getCompoundTagAt(i);
        return null;
    }

    NBTTagCompound primaryCapability() {
        HologramState.Cell selected = ui.session.selectedCell();
        if (selected != null) {
            NBTTagCompound hatch = ui.config.hatchCandidateCapability();
            if (hatch != null) return hatch;
            if (selected.family.startsWith("glass:")) {
                NBTTagCompound glass = ui.config.capability("glass");
                if (glass != null && glass.getBoolean("editable")) return glass;
                return null;
            }
            if (selected.family.startsWith("tiered:")) {
                NBTTagList rows = ui.config.capabilities();
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    if (!row.getBoolean("editable") || !row.getString("kind")
                        .equals("material")) continue;
                    NBTTagList options = row.getTagList("options", 10);
                    for (int j = 0; j < options.tagCount(); j++) {
                        ItemStack item = ItemStack.loadItemStackFromNBT(
                            options.getCompoundTagAt(j)
                                .getCompoundTag("item"));
                        if (item == null) continue;
                        net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(item.getItem());
                        String member = net.minecraft.block.Block.blockRegistry.getNameForObject(block) + "@"
                            + item.getItem()
                                .getMetadata(item.getItemDamage())
                            + ";";
                        if (selected.family.substring("tiered:".length())
                            .contains(member)) return row;
                    }
                }
                return null;
            }
        }
        if (selected != null && !"coil".equals(selected.family)) return null;
        NBTTagCompound coil = ui.config.capability("coil");
        if (coil != null && coil.getBoolean("editable")) return coil;
        NBTTagList list = ui.config.capabilities();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound cap = list.getCompoundTagAt(i);
            if (!cap.getString("id")
                .equals("main")
                && !cap.getString("id")
                    .equals("gt_hatch")
                && cap.getBoolean("editable")) return cap;
        }
        return null;
    }

    NBTTagCompound hatchCandidateCapability() {
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell == null || cell.anchor || ui.session.state().data.getInteger("mode") == 2) return null;
        ItemStack target = ItemStack.loadItemStackFromNBT(
            ui.session.cellTag()
                .getCompoundTag("targetStack"));
        int current = cell.chosenChoice;
        if (current < 0 && target != null) for (int i = 0; i < ui.config.candidates()
            .size(); i++) {
                if (target.isItemEqual(
                    ui.config.candidates()
                        .get(i))
                    && ItemStack.areItemStackTagsEqual(
                        target,
                        ui.config.candidates()
                            .get(i))) {
                    current = i;
                    break;
                }
            }
        MTEHatch prototype = current >= 0 && current < ui.config.candidates()
            .size() ? HologramUiConfiguration.hatchPrototype(
                ui.config.candidates()
                    .get(current))
                : null;
        if (prototype == null) for (int i = 0; i < ui.config.candidates()
            .size(); i++) {
                prototype = HologramUiConfiguration.hatchPrototype(
                    ui.config.candidates()
                        .get(i));
                if (prototype != null) {
                    current = i;
                    break;
                }
            }
        if (prototype == null) return null;
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < ui.config.candidates()
            .size(); i++) {
            MTEHatch candidate = HologramUiConfiguration.hatchPrototype(
                ui.config.candidates()
                    .get(i));
            if (candidate != null && candidate.getClass() == prototype.getClass()) indices.add(i);
        }
        indices.sort(
            (a, b) -> Integer.compare(
                HologramUiConfiguration.hatchPrototype(
                    ui.config.candidates()
                        .get(a)).mTier,
                HologramUiConfiguration.hatchPrototype(
                    ui.config.candidates()
                        .get(b)).mTier));
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "_hatch_candidate");
        row.setString("kind", "hatchCandidate");
        row.setString("label", "空仓室目标 · 独立设备等级");
        row.setBoolean("editable", true);
        row.setBoolean("explicit", true);
        row.setInteger("current", current);
        NBTTagList options = new NBTTagList();
        for (Integer index : indices) {
            ItemStack stack = ui.config.candidates()
                .get(index);
            NBTTagCompound option = new NBTTagCompound();
            option.setInteger("value", index);
            option.setBoolean("present", true);
            option.setString(
                "label",
                stack.getDisplayName() + " · 等级 " + HologramUiConfiguration.hatchPrototype(stack).mTier);
            option.setTag("item", stack.writeToNBT(new NBTTagCompound()));
            options.appendTag(option);
        }
        row.setTag("options", options);
        return row;
    }

    static MTEHatch hatchPrototype(ItemStack stack) {
        if (stack == null) return null;
        int id = stack.getItemDamage();
        return stack.getItem() instanceof ItemMachines && id >= 0
            && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[id] instanceof MTEHatch ? (MTEHatch) GregTechAPI.METATILEENTITIES[id]
                : null;
    }

    int optionIndex(NBTTagCompound cap) {
        NBTTagList options = cap.getTagList("options", 10);
        for (int i = 0; i < options.tagCount(); i++) {
            NBTTagCompound option = options.getCompoundTagAt(i);
            if (option.getBoolean("present") && option.getInteger("value") == cap.getInteger("current")) return i;
        }
        if (!cap.getBoolean("explicit") && options.tagCount() > 0
            && !options.getCompoundTagAt(0)
                .getBoolean("present"))
            return 0;
        return -1;
    }

    String optionLabel(NBTTagCompound cap) {
        int index = ui.config.optionIndex(cap);
        return index < 0 ? "有效值 " + cap.getInteger("current")
            : cap.getTagList("options", 10)
                .getCompoundTagAt(index)
                .getString("label");
    }

    boolean canStep(NBTTagCompound cap, int delta) {
        int index = ui.config.optionIndex(cap) + delta;
        return index >= 0 && index < cap.getTagList("options", 10)
            .tagCount()
            && cap.getTagList("options", 10)
                .getCompoundTagAt(index)
                .getBoolean("present");
    }

    void chooseOption(NBTTagCompound cap, int index) {
        if (cap == null || !cap.getBoolean("editable") || !ui.session.editable()) return;
        NBTTagList options = cap.getTagList("options", 10);
        if (index < 0 || index >= options.tagCount()) return;
        NBTTagCompound option = options.getCompoundTagAt(index), config = ui.config.configuration();
        if (cap.getString("kind")
            .equals("hatchCandidate")) {
            config = new NBTTagCompound();
            config.setInteger("selected", ui.preview.selected());
            config.setInteger("choiceindex", option.getInteger("value"));
            this.optionChannel = "";
            if (ui.session.isLocalAuthor()) ui.session.localAction("pin", config);
            else ui.session.send("pin", config, true);
            return;
        }
        String id = cap.getString("id");
        if (id.equals("main")) config.setInteger("main", option.getInteger("value"));
        else if (option.getBoolean("present")) config.getCompoundTag("channels")
            .setInteger(id, option.getInteger("value"));
        else config.getCompoundTag("channels")
            .removeTag(id);
        this.optionChannel = "";
        if (ui.session.isLocalAuthor()) ui.session.localAction("configure", config);
        else ui.session.send("configure", config, true);
    }

    static String roleLabel(String role) {
        switch (role) {
            case "Energy Hatch":
                return "能源仓";
            case "Dynamo Hatch":
                return "动力仓";
            case "Multi-Amp Energy Hatch":
                return "多安能源仓";
            case "Laser Source Hatch":
                return "激光源仓";
            case "Laser/Multi-Amp Energy Hatch":
                return "激光 / 多安能源仓";
            case "Laser/Multi-Amp Dynamo Hatch":
                return "激光 / 多安动力仓";
            case "Maintenance Hatch":
                return "维护仓";
            case "Muffler Hatch":
                return "消声仓";
            case "Input Bus":
                return "输入总线";
            case "Input Hatch":
                return "输入仓";
            case "Output Bus":
                return "输出总线";
            case "Output Hatch":
                return "输出仓";
            case "Solidifier Hatch":
                return "流体固化仓";
            case "Cryotheum Cooling Hatch":
                return "极寒之凛冰冷却仓";
            case "Pyrotheum Heating Hatch":
                return "烈焰之炽焱加热仓";
            default:
                return net.minecraft.util.StatCollector.translateToLocal(role);
        }
    }

    void syncTargetSelectors() {
        HologramState.Cell cell = ui.session.selectedCell();
        if (cell == null) {
            this.hatchRole = 0;
            this.hatchFilterTier = -1;
            return;
        }
        int index = cell.chosenChoice;
        if (index < 0 && cell.targetStack != null) for (int i = 0; i < ui.config.candidates()
            .size(); i++) {
                if (cell.targetStack.isItemEqual(
                    ui.config.candidates()
                        .get(i))
                    && ItemStack.areItemStackTagsEqual(
                        cell.targetStack,
                        ui.config.candidates()
                            .get(i))) {
                    index = i;
                    break;
                }
            }
        NBTTagList roles = ui.session.cellTag()
            .getTagList("candidateRoles", 8);
        int[] tiers = ui.session.cellTag()
            .getIntArray("candidateTiers");
        this.hatchRole = index >= 0 && index < roles.tagCount() ? Math.max(
            0,
            ui.config.hatchRoles()
                .indexOf(roles.getStringTagAt(index)))
            : 0;
        this.hatchFilterTier = index >= 0 && index < tiers.length ? tiers[index] : -1;
        this.hatchOffset = 0;
    }

    void selectFilteredTarget() {
        List<Integer> candidates = ui.config.filteredHatches();
        if (!candidates.isEmpty()) ui.router.activate(10000 + candidates.get(0));
        else ui.session.notice("此类型 / 等级没有合法候选，目标未改变");
    }

    void applyRawChannel(String name, String raw) {
        if (!ui.session.editable()) return;
        if (!name.matches("[a-z0-9_.-]{1,48}") || "_hatch_candidate".equals(name)) {
            ui.session.notice("name须为1–48位小写字母、数字、_、.或-");
            return;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new NumberFormatException();
            NBTTagCompound config = ui.config.configuration();
            if ("main".equals(name)) config.setInteger("main", value);
            else config.getCompoundTag("channels")
                .setInteger(name, value);
            this.optionChannel = "";
            if (ui.session.isLocalAuthor()) ui.session.localAction("configure", config);
            else ui.session.send("configure", config, true);
        } catch (NumberFormatException invalid) {
            ui.session.notice("value须为1–2147483647");
        }
    }

    NBTTagCompound configuration() {
        NBTTagCompound n = new NBTTagCompound();
        for (String key : new String[] { "main", "facing", "mode", "scope" })
            n.setInteger(key, ui.session.state().data.getInteger(key));
        n.setInteger(
            "scope",
            !ui.session.state().data.getBoolean("wholeBlueprintPlacement")
                && !ui.session.state().data.getBoolean("virtualPlacement")
                && ui.preview.selection()
                    .size() > 0 ? 2 : 0);
        n.setInteger("layer", ui.preview.layer());
        n.setInteger("selected", ui.preview.selected());
        n.setIntArray(
            "selectedIndices",
            ui.preview.selection()
                .indices());
        n.setString("structureId", ui.session.state().data.getString("activeStructure"));
        n.setBoolean("noHatches", ui.session.state().data.getBoolean("noHatches"));
        n.setBoolean("keepController", true);
        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
            n.setBoolean(key, ui.config.sourceEnabled(key));
        n.setInteger("materialPriority", ui.session.state().data.getInteger("materialPriority"));
        n.setTag(
            "channels",
            ui.session.state().data.getCompoundTag("channels")
                .copy());
        return n;
    }

    void configure(String key, int value) {
        if (!ui.session.editable()) return;
        NBTTagCompound n = ui.config.configuration();
        n.setInteger(key, value);
        if (ui.session.isLocalAuthor() && "materialPriority".equals(key)) n.setBoolean("authorMaterialSettings", true);
        if (ui.session.isLocalAuthor()) ui.session.localAction("configure", n);
        else ui.session.send("configure", n, true);
    }

    void requestSelectedDetails() {
        if (ui.session.isLocalAuthor()) {
            if (ui.session.editable() && ui.preview.selected() >= 0) requestNativeDetails();
            return;
        }
        if (ui.session.editable() && ui.preview.selected() >= 0
            && ui.session.state().data.getInteger("selected") != ui.preview.selected()) {
            ui.session.send("configure", ui.config.configuration(), true);
        }
    }

    boolean selectRoleControl(int id) {
        if (id >= 15000) {
            int index = id - 15000;
            if (index < ui.config.hatchRoles()
                .size()) {
                this.hatchRole = index;
                this.hatchOffset = 0;
                if (!ui.config.availableTiers()
                    .contains(this.hatchFilterTier)) this.hatchFilterTier = -1;
            }
            this.roleDropdown = false;
            return true;
        }
        return false;
    }

    boolean chooseOptionControl(int id) {
        if (id >= 4000) {
            ui.config.chooseOption(ui.config.capability(this.optionChannel), id - 4000);
            return true;
        }
        return false;
    }

    boolean openOptionControl(int id) {
        if (id >= 2000) {
            this.optionChannel = ui.config.capabilities()
                .getCompoundTagAt(id - 2000)
                .getString("id");
            this.drawerOffset = 0;
            this.optionValueField.setText(
                Integer.toString(
                    ui.config.capability(this.optionChannel)
                        .getInteger("current")));
            return true;
        }
        return false;
    }

    boolean selectScopeControl(int id) {
        if (id >= 90 && id <= 93) {
            ui.config.configure("scope", id - 90);
            return true;
        }
        return false;
    }

    boolean selectModeControl(int id) {
        if (id >= 70 && id <= 72) {
            NBTTagCompound modeConfig = ui.config.configuration();
            modeConfig.setInteger("mode", id - 70);
            if (id == 72) {
                modeConfig.setBoolean("keepController", true);
            }
            if (ui.session.isLocalAuthor()) ui.session.localAction("configure", modeConfig);
            else ui.session.send("configure", modeConfig, true);
            return true;
        }
        return false;
    }

    boolean activateControl(int id) {
        if (ui.session.isTutorial() && id >= 1712 && id <= 1716) {
            ui.session.notice("演示隔离：本机命名搭配与上次配置不可用，不会读取或保存用户预设");
            return true;
        }
        if (id >= 1700 && id < 1704) {
            int index = presetOffset + id - 1700;
            if (customPresets) {
                List<String> names = hatchPresetNames();
                if (index < names.size()) {
                    selectedHatchPreset = names.get(index);
                    hatchPresetNameField.setText(selectedHatchPreset);
                }
            } else if (index <= ui.session.state().data.getInteger("hatchTierMax")) presetTier = index;
            return true;
        }
        switch (id) {
            case 163:
            case 165:
                if (ui.session.editable() && (id == 165 || !ui.config.selectedStructureId()
                    .isEmpty())) {
                    NBTTagCompound structure = new NBTTagCompound();
                    structure.setString("structureId", id == 165 ? "" : ui.config.selectedStructureId());
                    ui.session.send("selectStructure", structure, true);
                }
                return true;
            case 164:
                if (ui.session.editable() && !ui.config.selectedStructureId()
                    .isEmpty()) {
                    NBTTagCompound structure = new NBTTagCompound();
                    structure.setString("structureId", ui.config.selectedStructureId());
                    structure.setBoolean("enabled", !ui.config.structureEnabled(ui.config.selectedStructureId()));
                    ui.session.send("structureState", structure, true);
                }
                return true;
            case 134:
                if (!ui.session.editable()) return true;
                this.resetCalibration = ui.session.state().data.getBoolean("structureBuilt");
                this.resetReview = true;
                ui.library.presetNameField()
                    .setFocused(false);
                return true;
            case 136:
                this.resetReview = false;
                return true;
            case 137:
                if (ui.session.editable() && this.resetReview) {
                    this.resetReview = false;
                    ui.session.send(this.resetCalibration ? "calibrateCurrent" : "resetDraft", null, true);
                }
                return true;
            case 138:
                HologramClient.openSettings();
                return true;
            case 60:
                if (ui.session.state().data.getBoolean("virtual")
                    && ui.session.state().data.getString("activeStructure")
                        .isEmpty()) {
                    ui.session.notice("先选中结构轮廓并打开结构编辑，再设置该结构等级和信道");
                    return true;
                }
                if (ui.session.isLocalAuthor()) {
                    try {
                        HologramLocalAuthorStructureActions.inspect(ui.session.author(), ui);
                    } catch (java.io.IOException | RuntimeException failure) {
                        ui.session.notice("结构信道读取失败，原稿保留：" + failure.getMessage());
                        return true;
                    }
                }
                this.drawer = true;
                ui.preview.inventory(false);
                this.drawerOffset = 0;
                return true;
            case 61:
                this.drawer = false;
                ui.preview.inventory(true);
                ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
                ui.preview.sourceOffset(0);
                if (ui.session.editable()) {
                    if (ui.session.isLocalAuthor())
                        HologramLocalAuthorMaterialCatalog.request(ui.session.author(), ui.session);
                    else ui.session.send("scan", null, true);
                }
                return true;
            case 94:
                this.presetTier = ui.session.state().data.getInteger("hatchTier");
                this.presetDowngrade = ui.session.state().data.getBoolean("hatchDowngrade");
                this.presetDrawer = true;
                this.presetOffset = customPresets ? 0 : Math.max(0, this.presetTier - 2);
                return true;
            case 95:
                if (ui.session.selectedCell() == null) return true;
                ui.config.requestSelectedDetails();
                ui.preview.inventory(false);
                ui.preview.inventorySlot(ui.preview.sourceChoice(-1));
                this.hatchDrawer = true;
                this.hatchOffset = this.hatchRole = 0;
                this.hatchFilterTier = -1;
                return true;
            case 96:
                this.hatchDrawer = this.presetDrawer = false;
                return true;
            case 97:
                ui.config.requestSelectedDetails();
                this.roleDropdown = !this.roleDropdown;
                this.roleOffset = Math.max(
                    0,
                    Math.min(
                        this.hatchRole,
                        ui.config.hatchRoles()
                            .size() - 7));
                return true;
            case 98:
                this.hatchFilterTier = -1;
                this.hatchOffset = 0;
                return true;
            case 117:
            case 118:
                this.hatchFilterTier = HologramTargetTiers
                    .steppedTier(ui.config.availableTiers(), this.hatchFilterTier, id == 117 ? -1 : 1);
                this.hatchOffset = 0;
                if (this.hatchRole > 0 && this.hatchFilterTier >= 0) ui.config.selectFilteredTarget();
                return true;
            case 99:
            case 102:
                if (!ui.session.editable()) return true;
                if (id == 99 && ui.session.isLocalAuthor()) {
                    selectedNativeDetails();
                    if (nativeReviewRequired) {
                        requestNativeDetails();
                        return true;
                    }
                }
                int[] facings = ui.session.cellTag()
                    .getIntArray("hatchFacings");
                int nextFacing = -1;
                if (id == 99 && facings.length > 0) {
                    nextFacing = facings[0];
                    for (int i = 0; i < facings.length; i++) if (facings[i] == ui.session.cellTag()
                        .getInteger("hatchFacing")) {
                            nextFacing = facings[(i + 1) % facings.length];
                            break;
                        }
                }
                NBTTagCompound facingAction = new NBTTagCompound();
                facingAction.setInteger("selected", ui.preview.selected());
                facingAction.setInteger("facing", nextFacing);
                if (ui.session.isLocalAuthor()) ui.session.localAction("hatchFacing", facingAction);
                else ui.session.send("hatchFacing", facingAction, true);
                return true;
            case 103:
                this.presetTier = Math.max(0, this.presetTier - 1);
                return true;
            case 104:
                this.presetTier = Math.min(ui.session.state().data.getInteger("hatchTierMax"), this.presetTier + 1);
                return true;
            case 105:
                this.presetDowngrade = !this.presetDowngrade;
                return true;
            case 106:
                if (customPresets) applyHatchProfile(hatchPreset(selectedHatchPreset), false);
                else {
                    NBTTagCompound profile = new NBTTagCompound();
                    profile.setInteger("tier", presetTier);
                    profile.setBoolean("downgrade", presetDowngrade);
                    applyHatchProfile(profile, true);
                }
                return true;
            case 1710:
            case 1711:
                customPresets = id == 1711;
                presetOffset = 0;
                return true;
            case 1712:
            case 1713:
            case 1714:
                manageHatchPreset(id);
                return true;
            case 1715:
                NBTTagCompound previous = lastPreset(true);
                if (previous != null) applyHatchProfile(previous, previous.getBoolean("basic"));
                return true;
            case 1716:
                if (ui.session.editable()) {
                    NBTTagCompound previousSources = lastPreset(false);
                    if (previousSources != null) {
                        NBTTagCompound sources = configuration();
                        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
                            sources.setBoolean(key, previousSources.getBoolean(key));
                        sources.setInteger("materialPriority", previousSources.getInteger("materialPriority"));
                        if (ui.session.isLocalAuthor()) {
                            sources.setBoolean("authorMaterialSettings", true);
                            ui.session.localAction("configure", sources);
                        } else ui.session.send("configure", sources, true);
                    }
                }
                return true;
            case 1717:
            case 1718:
                presetOffset = Math.max(0, presetOffset + (id == 1717 ? -4 : 4));
                return true;
            case 107:
                if (!ui.session.editable() || !ui.session.state().data.getBoolean("presetActive")) return true;
                this.presetDrawer = false;
                ui.session.send("clearHatchPreset", new NBTTagCompound(), true);
                return true;
            case 62:
                if (ui.session.isLocalAuthor()) ui.session.author()
                    .structures()
                    .cancel();
                this.drawer = false;
                this.mainField.setFocused(false);
                this.channelNameField.setFocused(false);
                this.channelValueField.setFocused(false);
                return true;
            case 63:
                this.optionChannel = "";
                this.optionValueField.setFocused(false);
                return true;
            case 67:
                ui.config.applyRawChannel(
                    this.channelNameField.getText()
                        .trim(),
                    this.channelValueField.getText());
                return true;
            case 68:
                ui.config.applyRawChannel(this.optionChannel, this.optionValueField.getText());
                return true;
            case 30:
                if (!ui.session.editable()) return true;
                try {
                    int value = Integer.parseInt(
                        this.mainField.getText()
                            .trim());
                    if (value >= 1) ui.config.configure("main", value);
                    else ui.session.notice("主值须为1–2147483647");
                } catch (NumberFormatException ignored) {
                    ui.session.notice("主值须为1–2147483647");
                }
                return true;
            case 33:
                if (ui.session.editable()) {
                    NBTTagCompound n = ui.config.configuration();
                    n.setBoolean("noHatches", !ui.session.state().data.getBoolean("noHatches"));
                    if (ui.session.isLocalAuthor()) ui.session.localAction("configure", n);
                    else ui.session.send("configure", n, true);
                }
                return true;
            case 80:
            case 82:
                NBTTagCompound cap = ui.config.primaryCapability();
                if (cap != null) ui.config.chooseOption(cap, ui.config.optionIndex(cap) + (id == 80 ? -1 : 1));
                return true;
            case 81:
                NBTTagCompound primary = ui.config.primaryCapability();
                if (primary != null) {
                    this.optionChannel = primary.getString("id");
                    this.optionValueField.setText(Integer.toString(primary.getInteger("current")));
                    this.drawerOffset = 0;
                }
                return true;
            case 78:
                if (ui.session.isLocalAuthor())
                    HologramLocalAuthorMaterialCatalog.request(ui.session.author(), ui.session);
                this.sourceDrawer = true;
                this.drawer = ui.preview.inventory(ui.position.positionDrawer(false));
                return true;
            case 84:
            case 85:
            case 86:
                if (ui.session.editable()) {
                    String key = new String[] { "materialMain", "materialContainers", "materialMe" }[id - 84];
                    NBTTagCompound sourceConfig = ui.config.configuration();
                    sourceConfig.setBoolean(key, !ui.config.sourceEnabled(key));
                    if (ui.session.isLocalAuthor()) {
                        sourceConfig.setBoolean("authorMaterialSettings", true);
                        ui.session.localAction("configure", sourceConfig);
                    } else ui.session.send("configure", sourceConfig, true);
                }
                return true;
            case 87:
                ui.config
                    .configure("materialPriority", (ui.session.state().data.getInteger("materialPriority") + 1) % 3);
                return true;
            case 88:
                this.sourceDrawer = false;
                return true;
            case 83:
                NBTTagCompound hatch = ui.config.capability("gt_hatch");
                if (hatch != null) ui.config.chooseOption(hatch, hatch.getBoolean("explicit") ? 0 : 1);
                return true;
            default:
                return false;
        }
    }

    boolean consumeRoleDropdownMouse(int mx, int my, int button) {
        if (this.roleDropdown) {
            if (button == 0) for (int i = ui.canvas.controls()
                .size() - 1; i >= 0; i--) {
                    Control control = ui.canvas.controls()
                        .get(i);
                    if (control.id >= 15000 && control.contains(mx, my)) {
                        ui.router.activate(control.id);
                        return true;
                    }
                }
            this.roleDropdown = false;
            return true;
        }
        return false;
    }

    boolean consumeRoleDropdownWheel(int wheel) {
        if (this.roleDropdown) {
            this.roleOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        ui.config.hatchRoles()
                            .size() - 7),
                    this.roleOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    boolean consumeHatchWheel(int wheel) {
        if (presetDrawer) {
            int count = customPresets ? hatchPresetNames().size()
                : ui.session.state().data.getInteger("hatchTierMax") + 1;
            presetOffset = Math.max(0, Math.min(Math.max(0, count - 4), presetOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        if (this.hatchDrawer) {
            this.hatchOffset = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        ui.config.filteredHatches()
                            .size() - 5),
                    this.hatchOffset + (wheel > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    void acknowledgeConfiguration(HologramState next) {
        if (!ui.session.isTutorial()) observePresetSnapshot(next);
        if (basicConfigurationSequence >= 0 && next.data.getLong("uiSequence") >= basicConfigurationSequence) {
            basicConfigurationSequence = -1;
            if (next.data.getLong("planRevision") != basicConfigurationRevision
                || next.data.getBoolean("presetActive")) {
                ui.memory.basicConfigured(true);
                ui.memory.rememberGuidance();
            }
        }
    }

    /** Called for accepted snapshots even while the workspace is closed; never sends an action. */
    static void observePresetSnapshot(HologramState next) {
        if (presetStore == null) return;
        try {
            presetPending.acknowledge(next.session, next.data, presetStore);
        } catch (java.io.IOException ignored) {
            // The accepted shortcut remains available in memory when the local preference file is unwritable.
        }
    }

    void rejectCommand(long sequence) {
        if (!ui.session.isTutorial()) presetPending.reject(sequence);
        if (basicConfigurationSequence == sequence) basicConfigurationSequence = -1;
    }

    void acknowledgeSelection(HologramState next, int previousSelection, HologramState.Cell previousTarget) {
        if (ui.preview.selected() != previousSelection) {
            roleDropdown = false;
            syncTargetSelectors();
        } else if (!HologramUiSession.sameTarget(previousTarget, ui.session.selectedCell())) {
            if (hatchRole > 0) syncTargetSelectors();
            HologramState.Cell target = ui.session.selectedCell();
            if (target != null) {
                int row = filteredHatches().indexOf(target.chosenChoice);
                if (row >= 0) hatchOffset = row;
                else if (hatchRole == 0) ui.session.notice("目标已更新；所选候选不在当前等级筛选中");
            }
        }
        if (mainField != null && !mainField.isFocused())
            mainField.setText(Integer.toString(next.data.getInteger("main")));
    }

    long basicConfigurationRevision(long value) {
        this.basicConfigurationRevision = value;
        return value;
    }

    long basicConfigurationSequence(long value) {
        this.basicConfigurationSequence = value;
        return value;
    }

    HologramMuiTextField channelNameField() {
        return this.channelNameField;
    }

    HologramMuiTextField channelNameField(HologramMuiTextField value) {
        this.channelNameField = value;
        return value;
    }

    HologramMuiTextField channelValueField() {
        return this.channelValueField;
    }

    HologramMuiTextField channelValueField(HologramMuiTextField value) {
        this.channelValueField = value;
        return value;
    }

    boolean drawer() {
        return this.drawer;
    }

    boolean drawer(boolean value) {
        this.drawer = value;
        return value;
    }

    int drawerOffset() {
        return this.drawerOffset;
    }

    int drawerOffset(int value) {
        this.drawerOffset = value;
        return value;
    }

    boolean hatchDrawer() {
        return this.hatchDrawer;
    }

    boolean hatchDrawer(boolean value) {
        this.hatchDrawer = value;
        return value;
    }

    int hatchOffset() {
        return this.hatchOffset;
    }

    int hatchOffset(int value) {
        this.hatchOffset = value;
        return value;
    }

    HologramMuiTextField mainField() {
        return this.mainField;
    }

    HologramMuiTextField mainField(HologramMuiTextField value) {
        this.mainField = value;
        return value;
    }

    String optionChannel() {
        return this.optionChannel;
    }

    String optionChannel(String value) {
        this.optionChannel = value;
        return value;
    }

    HologramMuiTextField optionValueField() {
        return this.optionValueField;
    }

    HologramMuiTextField optionValueField(HologramMuiTextField value) {
        this.optionValueField = value;
        return value;
    }

    boolean presetDrawer() {
        return this.presetDrawer;
    }

    boolean presetDrawer(boolean value) {
        this.presetDrawer = value;
        return value;
    }

    boolean resetCalibration() {
        return this.resetCalibration;
    }

    boolean resetReview() {
        return this.resetReview;
    }

    boolean resetReview(boolean value) {
        this.resetReview = value;
        return value;
    }

    boolean roleDropdown() {
        return this.roleDropdown;
    }

    boolean roleDropdown(boolean value) {
        this.roleDropdown = value;
        return value;
    }

    boolean sourceDrawer() {
        return this.sourceDrawer;
    }

    boolean sourceDrawer(boolean value) {
        this.sourceDrawer = value;
        return value;
    }

    // Tutorial catalogs stay empty in memory; they never initialize or consult the shared user store.
    private List<String> hatchPresetNames() {
        return ui.session.isTutorial() || presetStore == null ? Collections.emptyList() : presetStore.names();
    }

    private NBTTagCompound hatchPreset(String name) {
        return ui.session.isTutorial() || presetStore == null ? null : presetStore.get(name);
    }

    private NBTTagCompound lastPreset(boolean hatch) {
        return ui.session.isTutorial() || presetStore == null ? null : presetStore.last(hatch);
    }

    // Native presentation adapters. Configuration, legality and command dispatch stay above.
    int nativeHatchRole() {
        return Math.floorMod(hatchRole, hatchRoles().size());
    }

    int nativeHatchTier() {
        return hatchFilterTier;
    }

    int nativeRoleOffset() {
        return roleOffset;
    }

    void nativeRoleOffset(int value) {
        roleOffset = Math.max(0, Math.min(value, Math.max(0, hatchRoles().size() - 7)));
    }

    boolean nativeCustomPresets() {
        return customPresets;
    }

    int nativePresetOffset() {
        return presetOffset;
    }

    int nativePresetCount() {
        return customPresets ? hatchPresetNames().size() : ui.session.state().data.getInteger("hatchTierMax") + 1;
    }

    String nativePresetRow(int row) {
        int index = presetOffset + row;
        if (index < 0 || index >= nativePresetCount()) return "";
        String name = customPresets ? hatchPresetNames().get(index) : tierName(index) + " · 按结构声明配额";
        boolean chosen = customPresets ? name.equals(selectedHatchPreset) : index == presetTier;
        return (chosen ? "✓ " : "") + name;
    }

    boolean nativeSelectedPreset() {
        return hatchPreset(selectedHatchPreset) != null;
    }

    boolean nativeLastPreset(boolean hatch) {
        return lastPreset(hatch) != null;
    }

    boolean nativePresetDowngrade() {
        return presetDowngrade;
    }

    HologramMuiTextField nativePresetNameField() {
        return hatchPresetNameField;
    }

    void normalizeNativeViewState() {
        // These were view-only clamps/guards in drawConfig/drawHatches/drawPreset/drawOptions.
        hatchRole = nativeHatchRole();
        hatchOffset = Math.max(0, Math.min(hatchOffset, Math.max(0, filteredHatches().size() - 5)));
        nativeRoleOffset(roleOffset);
        presetOffset = Math.max(0, Math.min(presetOffset, Math.max(0, nativePresetCount() - 4)));
        if (!optionChannel.isEmpty() && capability(optionChannel) == null) {
            optionChannel = "";
            if (optionValueField != null) optionValueField.setFocused(false);
        }
    }

    void initializeFields() {
        if (!ui.session.isTutorial() && presetStore == null) presetStore = new HologramConfigurationPresets(
            new java.io.File(ui.host.minecraft().mcDataDir, "config/gtmb-hologram-presets.nbt"));
        hatchPresetNameField = new HologramMuiTextField(ui.host.parent(), 203, 186, 193, 17);
        hatchPresetNameField.setMaxStringLength(32);
        hatchPresetNameField.setText(selectedHatchPreset);
        ui.config.mainField(new HologramMuiTextField(ui.host.parent(), 271, 213, 55, 16));
        ui.config.mainField()
            .setMaxStringLength(10);
        ui.config.mainField()
            .setText(Integer.toString(ui.session.state().data.getInteger("main")));
        ui.config.channelNameField(new HologramMuiTextField(ui.host.parent(), 165, 190, 150, 16));
        ui.config.channelNameField()
            .setMaxStringLength(48);
        ui.config.channelValueField(new HologramMuiTextField(ui.host.parent(), 321, 190, 75, 16));
        ui.config.channelValueField()
            .setMaxStringLength(10);
        ui.config.channelValueField()
            .setText("1");
        ui.config.optionValueField(new HologramMuiTextField(ui.host.parent(), 168, 232, 137, 16));
        ui.config.optionValueField()
            .setMaxStringLength(10);
    }

    void tickMainField() {
        if (ui.config.mainField() != null) ui.config.mainField()
            .updateCursorCounter();
    }

    void tickChannelFields() {
        if (ui.config.channelNameField() != null) ui.config.channelNameField()
            .updateCursorCounter();
        if (ui.config.channelValueField() != null) ui.config.channelValueField()
            .updateCursorCounter();
        if (ui.config.optionValueField() != null) ui.config.optionValueField()
            .updateCursorCounter();
    }

    void prepareCommand(String op, NBTTagCompound request, long sequence) {
        if (!ui.session.isTutorial() && "configure".equals(op)) {
            if (request.getBoolean("applyHatchPreset")) {
                boolean basic = !request.hasKey("hatchProfile", 10);
                NBTTagCompound profile = basic ? new NBTTagCompound()
                    : (NBTTagCompound) request.getCompoundTag("hatchProfile")
                        .copy();
                profile.setInteger("tier", request.getInteger("hatchTier"));
                profile.setBoolean("downgrade", request.getBoolean("hatchDowngrade"));
                profile.setBoolean("basic", basic);
                presetPending.begin(ui.session.state().session, sequence, true, profile);
            } else {
                boolean changed = request.getInteger("materialPriority")
                    != ui.session.state().data.getInteger("materialPriority");
                NBTTagCompound sources = new NBTTagCompound();
                for (String key : new String[] { "materialMain", "materialContainers", "materialMe" }) {
                    changed |= request.getBoolean(key) != sourceEnabled(key);
                    sources.setBoolean(key, request.getBoolean(key));
                }
                sources.setInteger("materialPriority", request.getInteger("materialPriority"));
                if (changed) presetPending.begin(ui.session.state().session, sequence, false, sources);
            }
        }
        if ("hatchPreset".equals(op) || "pin".equals(op)
            || "customPin".equals(op)
            || "sourcePin".equals(op)
            || "hatchFacing".equals(op)
            || "configure".equals(op) && (request.getInteger("main") != ui.session.state().data.getInteger("main")
                || !request.getCompoundTag("channels")
                    .equals(ui.session.state().data.getCompoundTag("channels")))) {
            basicConfigurationSequence = sequence;
            basicConfigurationRevision = ui.session.revision();
        }
    }

    NBTTagCompound backgroundUiState() {
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("sourceDrawer", sourceDrawer);
        state.setBoolean("hatchDrawer", hatchDrawer);
        state.setBoolean("presetDrawer", presetDrawer);
        state.setBoolean("roleDropdown", roleDropdown);
        state.setBoolean("drawer", drawer);
        state.setBoolean("resetReview", resetReview);
        state.setBoolean("resetCalibration", resetCalibration);
        state.setBoolean("customPresets", customPresets);
        state.setBoolean("presetDowngrade", presetDowngrade);
        state.setInteger("roleOffset", roleOffset);
        state.setInteger("hatchOffset", hatchOffset);
        state.setInteger("hatchRole", hatchRole);
        state.setInteger("hatchFilterTier", hatchFilterTier);
        state.setInteger("presetTier", presetTier);
        state.setInteger("presetOffset", presetOffset);
        state.setInteger("drawerOffset", drawerOffset);
        state.setString("selectedHatchPreset", selectedHatchPreset);
        state.setString("optionChannel", optionChannel);
        if (mainField != null) state.setString("main", mainField.getText());
        if (channelNameField != null) state.setString("channelName", channelNameField.getText());
        if (channelValueField != null) state.setString("channelValue", channelValueField.getText());
        if (optionValueField != null) state.setString("optionValue", optionValueField.getText());
        if (hatchPresetNameField != null) state.setString("hatchPresetName", hatchPresetNameField.getText());
        return state;
    }

    void restoreBackgroundUiState(NBTTagCompound state) {
        sourceDrawer = state.getBoolean("sourceDrawer");
        hatchDrawer = state.getBoolean("hatchDrawer");
        presetDrawer = state.getBoolean("presetDrawer");
        roleDropdown = state.getBoolean("roleDropdown");
        drawer = state.getBoolean("drawer");
        resetReview = state.getBoolean("resetReview");
        resetCalibration = state.getBoolean("resetCalibration");
        customPresets = state.getBoolean("customPresets");
        presetDowngrade = state.getBoolean("presetDowngrade");
        roleOffset = Math.max(0, state.getInteger("roleOffset"));
        hatchOffset = Math.max(0, state.getInteger("hatchOffset"));
        hatchRole = Math.max(0, state.getInteger("hatchRole"));
        hatchFilterTier = state.getInteger("hatchFilterTier");
        presetTier = state.getInteger("presetTier");
        presetOffset = Math.max(0, state.getInteger("presetOffset"));
        drawerOffset = Math.max(0, state.getInteger("drawerOffset"));
        selectedHatchPreset = state.getString("selectedHatchPreset");
        optionChannel = state.getString("optionChannel");
        if (mainField != null && state.hasKey("main")) mainField.setText(state.getString("main"));
        if (channelNameField != null) channelNameField.setText(state.getString("channelName"));
        if (channelValueField != null) channelValueField.setText(state.getString("channelValue"));
        if (optionValueField != null) optionValueField.setText(state.getString("optionValue"));
        if (hatchPresetNameField != null) hatchPresetNameField.setText(state.getString("hatchPresetName"));
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.util.StringTranslate;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Isolated language-resource and native lookup regression; no Minecraft client or world starts. */
public final class HologramProjectorLocalizationRegression {

    private static final String PREFIX = "gtmb.hologram.projector.";
    private static final Pattern PLACEHOLDER = Pattern.compile("%([1-9][0-9]*)\\$s");

    private HologramProjectorLocalizationRegression() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> english = read("en_US");
        for (String locale : new String[] { "zh_CN", "zh_TW" }) {
            Map<String, String> other = read(locale);
            SimpleAssert.eq(english.keySet(), other.keySet(), "native projector key parity: " + locale);
            for (Map.Entry<String, String> entry : english.entrySet()) {
                String key = entry.getKey();
                SimpleAssert.eq(placeholders(entry.getValue()), placeholders(other.get(key)), locale + ": " + key);
            }
        }
        for (String value : english.values()) {
            SimpleAssert.that(
                value.codePoints()
                    .noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                "English projector text contains untranslated Chinese: " + value);
        }
        inject("en_US");
        expect("Blueprint Library", "library.title");
        expect("Search blueprints", "library.search");
        expect("Place Selected Blueprint", "library.place_selected");
        expect("Edit Selected", "library.edit_selected");
        expect("Upgrade All Legacy Blueprints", "library.upgrade");
        expect("Controller Projection", "editor.controller");
        var site = new net.minecraft.nbt.NBTTagCompound();
        SimpleAssert
            .eq("Confirm Construction", HologramUiBuildReview.buildLabel(site), "uncertain native site uses English");
        site.setInteger("siteOccupied", 0);
        SimpleAssert.eq("Confirm New Build", HologramUiBuildReview.buildLabel(site), "empty native site uses English");
        site.setInteger("siteMatchingSolid", 1);
        SimpleAssert.eq(
            "Confirm Fill Missing Blocks",
            HologramUiBuildReview.buildLabel(site),
            "matching native site uses English");
        var data = new net.minecraft.nbt.NBTTagCompound();
        data.setBoolean("localStandardController", true);
        data.setLong("nativeTargetRevision", 1);
        SimpleAssert.eq(
            "Updating the complete target.",
            HologramPlacementConfirmation.localStructureReviewBlocker(new HologramState(data)),
            "native eligibility blocker uses English");
        nativeWorkflowLabels();
        constructionDiagnostics();
        expect("Construction complete. 12 cells placed.", "stock.done_placement", "Construction", 12);
        expect(
            "The selected material sources do not have enough stock. Placement did not start.",
            "stock.reason_materials");
        expect("7 cells · 2 structures", "library.row_counts", 7, 2);
        expect(
            "Permission group: Custom · Publish: Allowed · Download: Denied · Quota: 8",
            "library.permissions",
            "Custom",
            HologramUiText.projector("library.allowed"),
            HologramUiText.projector("library.denied"),
            8);
        String authored = "原作者 100% \\n";
        expect("Author: " + authored, "library.author", authored);
        expect(
            "Delete blueprint: " + authored + "\nYour current draft stays open. This cannot be undone.",
            "dialog.delete_body",
            authored);
        inject("zh_CN");
        expect("蓝图库", "library.title");
        expect("7 格 · 2 结构", "library.row_counts", 7, 2);
        inject("zh_TW");
        expect("藍圖庫", "library.title");
        expect("7 格 · 2 結構", "library.row_counts", 7, 2);
        System.out.println("PASS projector localization: " + english.size() + " keys in en_US, zh_CN, zh_TW");
    }

    private static void nativeWorkflowLabels() {
        var operations = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation.values();
        String[] labels = { "Build", "Repair", "Replace", "Demolish" };
        for (int i = 0; i < operations.length; i++) {
            SimpleAssert.eq(labels[i], HologramNativeControllerState.label(operations[i]), "native action label");
            SimpleAssert.eq(
                "Confirm " + labels[i],
                HologramNativeControllerState.confirmationLabel(operations[i]),
                "native confirmation uses a complete translated template");
            SimpleAssert.eq(
                "Review and confirm " + labels[i],
                HologramNativeControllerState.reviewLabel(operations[i]),
                "native launcher uses a complete translated template");
        }
        var state = new HologramNativeControllerState();
        state.targetReady();
        var stage = com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage.CONTROLLER_ONLY;
        state.observe(
            new com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation(
                1,
                0,
                64,
                0,
                stage,
                1,
                state.targetRevision(),
                ""));
        SimpleAssert.eq("Controller only", state.statusCaption(), "server state caption uses English");
        SimpleAssert.eq(
            "Controller only",
            HologramUiBuildReview.nativeCompletionCaption("", "", state.statusCaption()),
            "normal native summary uses observed stage without inventing a validation failure from an opening status");
        SimpleAssert.eq(
            "Reading the controller site",
            HologramUiBuildReview.nativeCompletionCaption("", "Reading the controller site", state.statusCaption()),
            "ordinary English native progress is preserved rather than passed to the blueprint diagnostic fallback");
        String progress = HologramUiText.projector("stock.confirmed_cells", "Build", 12);
        SimpleAssert.eq(
            progress,
            HologramUiBuildReview.nativeCompletionCaption(progress, "Earlier notice", state.statusCaption()),
            "live native Flow progress takes precedence over an earlier notice and observed stage");
        String complete = HologramUiText.projector("stock.done_changes", "Repair", 33);
        SimpleAssert.eq(
            complete,
            HologramUiBuildReview.nativeCompletionCaption("", complete, state.statusCaption()),
            "actual translated native completion text remains the completion summary");
        SimpleAssert.eq(
            "Only the controller is present. Build the structure first.",
            state.blocker(operations[1]),
            "wrong action explains the server stage in English");
        state.observe(
            new com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation(
                1,
                0,
                64,
                0,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage.UNFORMED,
                2,
                state.targetRevision(),
                ""));
        SimpleAssert.eq("Structure incomplete", state.statusCaption(), "incomplete server stage is translated");
        state.observe(
            new com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation(
                1,
                0,
                64,
                0,
                com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage.FORMED,
                3,
                state.targetRevision(),
                "控制器正在运行，请先停止。"));
        SimpleAssert.eq(
            "The controller is running. Stop it first.",
            state.blocker(operations[2]),
            "known native server reason is translated without changing admission");
        SimpleAssert.that(
            state.statusCaption()
                .startsWith("Structure formed · The controller is running."),
            "formed server status preserves its readable reason");
        SimpleAssert.eq(
            "unknown native diagnostic: raw-id",
            HologramNativeUiText.reason("unknown native diagnostic: raw-id"),
            "unknown diagnostics retain their original evidence");
        var config = new net.minecraft.nbt.NBTTagCompound();
        config.setBoolean("localStandardController", true);
        config.setBoolean("nativeTargetReady", true);
        var estimate = new HologramNativeTargetReview(
            new HologramState(config),
            new net.minecraft.nbt.NBTTagCompound());
        SimpleAssert.eq(
            "Complete target: update 0 cells, turn 0 cells, leave 0 matching cells unchanged."
                + " The server verifies payment, recovery and protection.",
            estimate.status(),
            "material delta summary is actually English");
        config.setInteger("nativeAction", operations[3].ordinal());
        var removal = new HologramNativeTargetReview(new HologramState(config), new net.minecraft.nbt.NBTTagCompound());
        SimpleAssert.eq(
            "Whole structure demolition preview: 0 world blocks."
                + " The server verifies payment, recovery and protection.",
            removal.status(),
            "whole demolition material summary is actually English");
        var local = new HologramMaterialConfirmationDialog.LocalView() {

            @Override
            public HologramMaterialConfirmationDialog.LocalFrame localFrame() {
                return null;
            }

            @Override
            public boolean localSubmitAvailable() {
                return false;
            }

            @Override
            public void localAction(HologramMaterialConfirmationDialog.LocalAction action) {}
        };
        SimpleAssert.eq("Cannot submit now.", local.localSubmitBlocker(), "material confirmation blocker is English");
        SimpleAssert
            .eq("Submit construction for verification…", local.localSubmitLabel(), "material footer is English");
        SimpleAssert.eq(
            "Material sources: verified by the server during execution",
            local.localSourcesLabel(),
            "source admission explanation is English");
        SimpleAssert.eq("items", HologramNativeUiText.unit("个"), "native material rows translate units");
        var commands = new HologramEditorCommands(null);
        SimpleAssert.eq(
            "Structure tier / Channels",
            commands.get("channels").label,
            "real command catalogue uses English rather than Chinese definitions");
        SimpleAssert.eq(
            "Edit the source structure's main value and named channels.",
            commands.get("channels").help,
            "real command help uses English");
        var capability = new net.minecraft.nbt.NBTTagCompound();
        capability.setString("id", "main");
        capability.setString("label", "主信号（未设子信道时回退）");
        SimpleAssert.eq(
            "Main signal (fallback for unset channels)",
            HologramNativeUiText.capabilityLabel(capability),
            "captured native main capability label is translated at presentation");
        capability.setString("id", "machine_raw");
        capability.setString("label", "machine_raw（原始整数）");
        SimpleAssert.eq(
            "machine_raw (raw integer)",
            HologramNativeUiText.capabilityLabel(capability),
            "raw channel label is translated while its identifier stays visible");
        SimpleAssert.eq(
            "machine_raw（原始整数）",
            capability.getString("label"),
            "presentation does not rewrite native metadata or raw channels");
        var option = new net.minecraft.nbt.NBTTagCompound();
        option.setString("label", "继承主信号");
        SimpleAssert.eq(
            "Inherit main signal",
            HologramNativeUiText.optionLabel(option),
            "captured channel inheritance option is translated");
        capability.setString("label", "custom source label");
        SimpleAssert.eq(
            "Allow lower tier hatches ✓",
            HologramNativeUiText.presetTierPolicy(true),
            "native preset relaxation describes model tiers rather than missing stock");
        SimpleAssert.eq(
            "Use selected tier only",
            HologramNativeUiText.presetTierPolicy(false),
            "native strict preset describes exact model tiers");
        SimpleAssert.that(
            HologramNativeUiText.presetTierPolicyHint()
                .contains("projected model")
                && HologramNativeUiText.presetTargetNote()
                    .contains("Unconfigured optional positions use casings"),
            "native preset hints explain projected material payment and complete optional casing defaults");
        SimpleAssert.that(
            HologramNativeUiText.reason("所选仓室配置未通过控制器的原生结构检查。")
                .contains("Check channels and mandatory hatch tiers"),
            "native structure-check refusal provides a generic actionable tier and channel hint");
        SimpleAssert.eq(
            "custom source label",
            HologramNativeUiText.capabilityLabel(capability),
            "unknown machine-specific channel labels are preserved");
    }

    private static void constructionDiagnostics() {
        SimpleAssert.eq(
            "Claim or mod protection cancelled placement. This cell was restored.",
            HologramNativeUiText.reason("领地或模组保护取消放置，本格已还原。"),
            "the shared native engine status is readable in English");
        SimpleAssert.eq(
            "The placed block identity, facing or configuration does not match the target.",
            HologramNativeUiText.reason("原生放置后的身份、朝向或配置与目标不符。"),
            "native target validation diagnostics retain their specific meaning");
        SimpleAssert.eq(
            "Native construction returned an invalid result: -3",
            HologramNativeUiText.reason("LOCAL_NATIVE_PREVIEW_RESULT:-3"),
            "native runner diagnostics preserve the actual return code");
        SimpleAssert.eq(
            "The native target has no payable item identity at Offset[x=3, y=-4, z=5]",
            HologramNativeUiText.reason("原生目标没有可支付的物品身份：Offset[x=3, y=-4, z=5]"),
            "unpayable material diagnostics preserve the exact coordinate");
        SimpleAssert.eq(
            "The server lacks registered construction material: fixture:block / fixture:item (no item)",
            HologramNativeUiText.reason("服务端缺少注册的施工材料：fixture:block / fixture:item（无物品）"),
            "missing material diagnostics translate the explanation and keep registry identities");
        SimpleAssert.eq(
            "Native blueprint construction complete.",
            HologramNativeUiText.reason("原生蓝图施工完成"),
            "saved native blueprint terminal status has an English presentation");
        SimpleAssert.that(
            HologramNativeUiText.reason("施工结果待核定，原世界与付款凭据已保留；请联系管理员 /gtmb recovery")
                .endsWith("/gtmb recovery."),
            "recovery diagnostics preserve the usable recovery command");
        for (String raw : new String[] { "LOCAL_NATIVE_PREVIEW_UNSTABLE", "LOCAL_NATIVE_PREVIEW_INCOMPLETE",
            "LOCAL_NATIVE_PREVIEW_CELL_LIMIT", "LOCAL_NATIVE_PREVIEW_EMPTY", "施工受阻或缺料，已停止", "所选仓室未被该位置的原生建造流程接受。" }) {
            String translated = HologramNativeUiText.reason(raw);
            SimpleAssert.that(
                !translated.equals(raw) && translated.codePoints()
                    .noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                "native generation and terminal reasons are translated: " + raw);
        }
    }

    private static void expect(String expected, String key, Object... args) {
        SimpleAssert.eq(expected, HologramUiText.projector(key, args), "native StatCollector lookup: " + key);
    }

    private static InputStream asset(String locale) {
        InputStream stream = HologramProjectorLocalizationRegression.class
            .getResourceAsStream("/assets/gtmb/lang/" + locale + ".lang");
        SimpleAssert.that(stream != null, "missing projector language resource " + locale);
        return stream;
    }

    private static void inject(String locale) throws IOException {
        try (InputStream stream = asset(locale)) {
            StringTranslate.inject(stream);
        }
    }

    private static Map<String, String> read(String locale) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(asset(locale), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(PREFIX)) continue;
                int separator = line.indexOf('=');
                SimpleAssert.that(separator > PREFIX.length(), "missing language key: " + line);
                String key = line.substring(PREFIX.length(), separator);
                String value = line.substring(separator + 1);
                SimpleAssert.that(
                    !value.trim()
                        .isEmpty(),
                    "missing language value: " + key);
                SimpleAssert.that(entries.put(key, value) == null, "duplicate language key: " + key);
            }
        }
        SimpleAssert.that(!entries.isEmpty(), "missing projector locale section: " + locale);
        return entries;
    }

    private static List<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> positions = new ArrayList<>();
        while (matcher.find()) positions.add(matcher.group(1));
        Collections.sort(positions);
        return positions;
    }
}

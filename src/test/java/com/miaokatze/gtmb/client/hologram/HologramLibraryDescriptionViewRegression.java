// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.HologramLibraryMaterialReviewScreen.CapturedDescription;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual detached view labels and cached slots, without a game, world, renderer or server. */
final class HologramLibraryDescriptionViewRegression {

    private HologramLibraryDescriptionViewRegression() {}

    static void run() throws Exception {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schema", 2);
        var tally = new HologramSavedMaterialSource.Tally(blueprint, 3);
        tally.include(0, material("stone"));
        tally.include(0, null);
        tally.include(1, material("wool"));
        tally.include(2, material("log"));
        var observation = new CapturedDescription(HologramLibraryMaterialForecast.project(tally.snapshot()));
        ItemStack[] inventory = new ItemStack[40];
        inventory[0] = new ItemStack(Blocks.stone, 50, 0);
        inventory[4] = new ItemStack(Blocks.stone, 2, 0);
        inventory[35] = new ItemStack(Blocks.stone);
        inventory[36] = new ItemStack(Blocks.stone, 60, 0);
        AtomicInteger reads = new AtomicInteger();
        Supplier<ItemStack[]> backpack = () -> {
            reads.incrementAndGet();
            return inventory;
        };
        LocalFrame first = observation.frame(backpack, 0);
        SimpleAssert.that(
            first.description()
                .size() == 3
                && first.description()
                    .get(0)
                    .blueprintCells() == 2
                && Long.valueOf(3)
                    .equals(
                        first.description()
                            .get(0)
                            .visibleBackpackCount())
                && first.description()
                    .get(1).visibleBackpackCount().equals(0L)
                && first.description().get(2).visibleBackpackCount().equals(0L)
                && !first.status().contains("格未列明"),
            "separate captured counts and observed backpack, excluding slot zero and storage past slot 35");
        inventory[4].stackSize = 8;
        SimpleAssert.that(
            first == observation.frame(backpack, 0) && reads.get() == 1,
            "ticks borrow the same frame without resampling inventory");
        observation.refresh(backpack, 0);
        LocalFrame fresh = observation.frame(backpack, 0);
        SimpleAssert.that(
            reads.get() == 2 && Long.valueOf(9)
                .equals(
                    fresh.description()
                        .get(0)
                        .visibleBackpackCount())
                && fresh.description()
                    .get(0)
                    .blueprintCells() == 2
                && fresh.rows()
                    .hasNoTags(),
            "local refresh reads another sample but never changes captured counts or creates a quote");
        observation.refresh(() -> new ItemStack[35], 0);
        SimpleAssert.that(
            observation.frame(backpack, 0)
                .description()
                .get(0)
                .visibleBackpackCount() == null,
            "an incomplete array is unknown, not zero stock");
        checkWidgets(fresh);
    }

    private static void checkWidgets(LocalFrame frame) throws Exception {
        LocalView view = new LocalView() {

            @Override
            public LocalFrame localFrame() {
                return frame;
            }

            @Override
            public boolean localSubmitAvailable() {
                return true;
            }

            @Override
            public void localAction(LocalAction action) {
                throw new AssertionError("view construction cannot invoke a material action");
            }
        };
        var dialog = new HologramMaterialConfirmationDialog(new ModularPanel("description_fixture"), view);
        var field = HologramMaterialConfirmationDialog.class.getDeclaredField("handler");
        field.setAccessible(true);
        field.set(
            dialog,
            Proxy.newProxyInstance(
                IPanelHandler.class.getClassLoader(),
                new Class<?>[] { IPanelHandler.class },
                (proxy, method, args) -> "isPanelOpen".equals(method.getName()) ? Boolean.TRUE : null));
        dialog.tick(true);
        var build = HologramMaterialConfirmationDialog.class.getDeclaredMethod("build");
        build.setAccessible(true);
        var panel = (ModularPanel) build.invoke(dialog);
        List<String> labels = panel.getChildren()
            .stream()
            .filter(TextWidget.class::isInstance)
            .map(
                widget -> ((TextWidget<?>) widget).getKey()
                    .get())
            .collect(java.util.stream.Collectors.toList());
        SimpleAssert.that(
            labels.contains("蓝图记录格数") && labels.contains("本机背包同物品")
                && frame.status().contains("现场及所选来源待核验")
                && labels.stream().anyMatch(text->text.contains("不代表可扣料")||text.contains("待核验"))
                && !labels.contains("需求")
                && !labels.contains("缺失")
                && !labels.contains("背包差额"),
            "the actual shared descriptive pane uses neutral columns, no shortage or quote fields");
        var ordinary = HologramMaterialConfirmationDialog.class.getDeclaredField("materials");
        ordinary.setAccessible(true);
        SimpleAssert.that(
            ((HologramMaterialReview) ordinary.get(dialog)).size() == 0 && dialog.probeState(null)
                .getInteger("rows") == 3,
            "the descriptive pane bypasses native quote row decoding");
    }

    private static JsonObject material(String id) {
        JsonObject row = new JsonObject();
        row.addProperty("block", "minecraft:" + id);
        row.addProperty("item", "minecraft:" + id);
        return row;
    }
}

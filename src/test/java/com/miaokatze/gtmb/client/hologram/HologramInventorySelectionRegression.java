// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Production native inventory tree and request/return callbacks; does not claim a GL or network session. */
public final class HologramInventorySelectionRegression {

    private HologramInventorySelectionRegression() {}

    public static void main(String[] args) throws Exception {
        if (!com.miaokatze.gtmb.testutil.MinecraftTestBootstrap.enter(HologramInventorySelectionRegression.class, args))
            return;
        run();
        System.out.println("HologramInventorySelectionRegression PASS");
    }

    @SuppressWarnings("unchecked")
    public static void run() {
        Field singleton = null;
        Object previous = null;
        try {
            // The headless loader does not execute MUI client preInit before this real widget tree is built.
            Field drawableTypes = field(
                com.cleanroommc.modularui.drawable.DrawableSerialization.class,
                "DRAWABLE_TYPES");
            if (((Map<?, ?>) drawableTypes.get(null)).isEmpty())
                com.cleanroommc.modularui.drawable.DrawableSerialization.init();
            for (Field field : Minecraft.class.getDeclaredFields())
                if (field.getType() == Minecraft.class && java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    singleton = field;
            if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
            singleton.setAccessible(true);
            previous = singleton.get(null);
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            net.minecraft.client.gui.FontRenderer font = (net.minecraft.client.gui.FontRenderer) unsafe
                .allocateInstance(net.minecraft.client.gui.FontRenderer.class);
            int[] widths = new int[256];
            Arrays.fill(widths, 6);
            field(net.minecraft.client.gui.FontRenderer.class, "charWidth").set(font, widths);
            field(net.minecraft.client.gui.FontRenderer.class, "glyphWidth").set(font, new byte[65536]);
            font.FONT_HEIGHT = 9;
            minecraft.fontRenderer = font;
            minecraft.thePlayer = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
            minecraft.thePlayer.inventory = new InventoryPlayer(minecraft.thePlayer);
            minecraft.thePlayer.inventory.mainInventory[1] = new ItemStack(Blocks.dirt, 32);
            singleton.set(null, minecraft);
            HologramScreen screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
            screen.width = 640;
            screen.height = 480;
            minecraft.currentScreen = screen;
            field(HologramMuiCanvasScreen.class, "fields").set(screen, new ArrayList<>());
            field(HologramMuiCanvasScreen.class, "fieldGraphs").set(screen, new IdentityHashMap<>());
            HologramUiDeferredAction deferred = new HologramUiDeferredAction();
            field(HologramMuiScreen.class, "deferredAction").set(screen, deferred);
            HologramUiModules ui = new HologramUiModules(screen);
            screen.ui = ui;
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "inventory-batch");
            data.setBoolean("target", true);
            data.setLong("planRevision", 42);
            NBTTagList cells = new NBTTagList();
            for (int i = 0; i < 3; i++) {
                NBTTagCompound cell = new NBTTagCompound();
                cell.setInteger("dx", i);
                cell.setBoolean("hasTargetOverride", i == 1);
                cells.appendTag(cell);
            }
            data.setTag("cells", cells);
            field(HologramUiSession.class, "state").set(ui.session, new HologramState(data));
            field(ui.diagnostics.getClass(), "captureRenderSmokeAction").set(ui.diagnostics, true);
            ui.preview.selection()
                .load(new int[] { 0, 1 }, 0, 3);
            ui.preview.selected(0);
            ui.router.activate(61);
            assertRefresh(ui, "opening inventory requests only its lightweight source directory");
            SimpleAssert.that(ui.preview.inventory(), "actual open control leaves the palette visible");
            ui.router.activate(18);
            assertRefresh(ui, "source refresh does not submit a full scan or own an edit ACK");
            HologramEditorConfigurationDialogs dialogs = new HologramEditorConfigurationDialogs(ui);
            Method inventory = HologramEditorConfigurationDialogs.class.getDeclaredMethod("inventory");
            inventory.setAccessible(true);
            ModularPanel panel = (ModularPanel) inventory.invoke(dialogs);
            SimpleAssert.that(
                !panel.disablePanelsBelow() && !panel.closeOnOutOfBoundsClick(),
                "the actual inventory panel permits lower scene input without closing on outside clicks");
            Method sceneOpen = HologramEditorShell.class.getDeclaredMethod("sceneOpen");
            sceneOpen.setAccessible(true);
            SimpleAssert.that(
                (Boolean) sceneOpen.invoke(ui.shell),
                "inventory no longer blocks the production scene admission gate");
            Map<String, List<IWidget>> targets = (Map<String, List<IWidget>>) field(
                HologramEditorShell.class,
                "targets").get(ui.shell);
            SimpleAssert
                .that(targets.containsKey("config.inventory.sample.35"), "real palette has all 36 display samples");
            ui.preview.inventorySlot(1);
            minecraft.thePlayer.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 32);
            SimpleAssert.that(ui.preview.hasSelectedPin(), "a secondary selected override enables restoring the batch");
            click(targets, "control.65");
            deferred.run(true);
            assertSelection(ui, "customPin", new int[] { 0, 1 });
            NBTTagCompound chosen = (NBTTagCompound) field(ui.diagnostics.getClass(), "capturedRenderSmokeAction")
                .get(ui.diagnostics);
            SimpleAssert.that(
                ItemStack.loadItemStackFromNBT(chosen.getCompoundTag("expectedSample"))
                    .getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt),
                "the packet retains the clicked sample when its inventory slot changes before apply");
            SimpleAssert.eq(
                32,
                minecraft.thePlayer.inventory.mainInventory[1].stackSize,
                "actual apply control never changes inventory");
            ui.session.pendingSequence(-1);
            ui.router.activate(17);
            NBTTagList choices = new NBTTagList();
            choices.appendTag(new ItemStack(Blocks.dirt).writeToNBT(new NBTTagCompound()));
            ui.session.state().data.setTag("materialChoices", choices);
            ui.preview.sourceChoice(0);
            ui.preview.pinInventory(1);
            assertSelection(ui, "sourcePin", new int[] { 0, 1 });
            ui.session.pendingSequence(-1);
            ui.router.activate(16);
            ui.preview.inventorySlot(1);
            ui.preview.pinInventory(2);
            assertSelection(ui, "customPin", new int[] { 2 });
            ui.session.pendingSequence(-1);
            ui.preview.selection()
                .load(new int[] { 0, 1 }, 0, 3);
            ui.preview.selected(0);
            ui.router.activate(66);
            assertSelection(ui, "restoreCell", new int[] { 0, 1 });
            ui.session.pendingSequence(-1);
            ui.session.state().data.setBoolean("virtual", true);
            ui.router.activate(66);
            assertSelection(ui, "editDelete", new int[] { 0, 1 });
            ui.session.pendingSequence(-1);

            // Physical E/Esc are intercepted before MUI's close-all fallback and execute the real local back callback.
            for (int key : new int[] { Keyboard.KEY_ESCAPE, Keyboard.KEY_E }) {
                ui.preview.inventory(true);
                SimpleAssert.that(screen.muiKeyBefore('\0', key), "physical return key is consumed by the root host");
                deferred.run(true);
                SimpleAssert.that(
                    !ui.preview.inventory() && minecraft.currentScreen == screen,
                    "physical return key closes only inventory and retains the same root screen");
            }
            ui.preview.inventory(true);
            java.util.concurrent.atomic.AtomicInteger queuedNative = new java.util.concurrent.atomic.AtomicInteger();
            screen.deferNativeCommand(queuedNative::incrementAndGet);
            SimpleAssert.that(
                screen.muiKeyBefore('\0', Keyboard.KEY_ESCAPE) && ui.preview.inventory() && queuedNative.get() == 0,
                "physical Escape accepts dismissal without acting during MUI input");
            deferred.run(true);
            SimpleAssert.that(
                !ui.preview.inventory() && queuedNative.get() == 0 && minecraft.currentScreen == screen,
                "physical Escape wins over an unexecuted native button and closes only the current owned layer");
            ui.preview.inventory(true);
            ui.router.activate(64);
            SimpleAssert.that(
                !ui.preview.inventory() && minecraft.currentScreen == screen,
                "explicit close control keeps the root screen and current selection");
            // Other editor forms retain their modal policy.
            Method reset = HologramEditorConfigurationDialogs.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            SimpleAssert.that(
                ((ModularPanel) reset.invoke(dialogs)).disablePanelsBelow(),
                "configuration confirmation remains modal");
        } catch (Exception failure) {
            throw new AssertionError("inventory native selection and return contracts", failure);
        } finally {
            if (singleton != null) try {
                singleton.set(null, previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void assertRefresh(HologramUiModules ui, String message) throws ReflectiveOperationException {
        NBTTagCompound request = (NBTTagCompound) field(ui.diagnostics.getClass(), "capturedRenderSmokeAction")
            .get(ui.diagnostics);
        SimpleAssert.that(
            "refreshMaterialChoices".equals(request.getString("op")) && ui.session.pendingSequence() < 0
                && !ui.session.awaitingScan(),
            message);
    }

    private static void assertSelection(HologramUiModules ui, String op, int[] selection)
        throws ReflectiveOperationException {
        NBTTagCompound request = (NBTTagCompound) field(ui.diagnostics.getClass(), "capturedRenderSmokeAction")
            .get(ui.diagnostics);
        SimpleAssert.that(
            op.equals(request.getString("op"))
                && (!request.hasKey("indices") || Arrays.equals(selection, request.getIntArray("indices")))
                && Arrays.equals(selection, request.getIntArray("selectedIndices"))
                && request.getLong("planRevision") == 42,
            "real " + op + " preserves all selected targets and plan revision");
    }

    private static void click(Map<String, List<IWidget>> targets, String key) {
        List<IWidget> widgets = targets.get(key);
        SimpleAssert.that(widgets != null && !widgets.isEmpty(), "actual native control exists: " + key);
        ButtonWidget<?> button = (ButtonWidget<?>) widgets.get(widgets.size() - 1);
        button.playClickSound(false);
        button.onMousePressed(0);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

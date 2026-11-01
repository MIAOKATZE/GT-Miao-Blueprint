package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramDemolitionInputRegression {

    private HologramDemolitionInputRegression() {}

    public static void run() {
        HologramCutInput.Switcher choice = new HologramCutInput.Switcher();
        for (HologramCutInput.Mode expected : HologramCutInput.Mode.values()) {
            SimpleAssert.eq(expected, choice.mode(), "selector cycles through all three tool modes");
            SimpleAssert.that(!choice.release(true), "holding Shift keeps selection local");
            choice.cycle();
        }
        SimpleAssert.eq(HologramCutInput.Mode.BLUEPRINT, choice.mode(), "selector wraps to blueprint");
        choice.cycle();
        choice.cycle();
        SimpleAssert.that(choice.release(false), "releasing Shift commits demolition once");
        choice.cycle();
        SimpleAssert.eq(HologramCutInput.Mode.DEMOLITION, choice.mode(), "committed mode cannot change");
        SimpleAssert.that(!choice.release(false), "a later tick cannot submit another selection");
        HologramCutInput.Switcher cancelled = new HologramCutInput.Switcher(HologramCutInput.Mode.DEMOLITION);
        cancelled.cancel();
        SimpleAssert.that(!cancelled.release(false), "cancelled switcher never enters demolition");

        for (boolean shift : new boolean[] { false, true })
            for (boolean alt : new boolean[] { false, true }) for (boolean control : new boolean[] { false, true }) {
                SimpleAssert.eq(
                    HologramCutInput.right(HologramCutInput.Stage.SELECTING, shift, alt, false, false, control),
                    HologramCutInput.right(
                        HologramCutInput.Stage.SELECTING,
                        shift,
                        alt,
                        false,
                        HologramCutInput.Mode.DEMOLITION,
                        control),
                    "demolition keeps the shared editable selection gestures");
                SimpleAssert.eq(
                    HologramCutInput.Intent.NONE,
                    HologramCutInput.right(
                        HologramCutInput.Stage.SELECTING,
                        shift,
                        alt,
                        true,
                        HologramCutInput.Mode.DEMOLITION,
                        control),
                    "an existing blueprint operation keeps world input priority");
            }
        SimpleAssert.eq(
            HologramCutInput.Intent.SELECT,
            HologramCutInput
                .right(HologramCutInput.Stage.IDLE, false, true, false, HologramCutInput.Mode.DEMOLITION, false),
            "Alt in demolition never undoes a stored cut");
        directoryIndependence();
        unknownOutcome();
        isometricSessionAndCompletion();
    }

    private static void directoryIndependence() {
        try {
            Constructor<HologramCutClient> constructor = HologramCutClient.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            HologramCutClient local = constructor.newInstance();
            Field demolition = HologramCutClient.class.getDeclaredField("demolitionMode");
            Field unavailable = HologramCutClient.class.getDeclaredField("directoryUnavailable");
            demolition.setAccessible(true);
            unavailable.setAccessible(true);
            unavailable.setBoolean(local, true);
            SimpleAssert.that(local.busy(), "cut operations retain the unavailable-directory gate");
            demolition.setBoolean(local, true);
            SimpleAssert.that(!local.busy(), "demolition does not require a loaded or available cut directory");
            SimpleAssert
                .eq(HologramCutInput.Mode.DEMOLITION, local.mode(), "controller exposes demolition to the switcher");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("demolition directory isolation", failure);
        }
    }

    private static void unknownOutcome() {
        HologramCutRequests requests = new HologramCutRequests();
        HologramCutContinuous continuous = new HologramCutContinuous();
        continuous.enter();
        continuous.submitted("demolition");
        requests.begin("demolition", "demolish", "", 0);
        requests.begin("directory", "list", "", 0);
        SimpleAssert.that(requests.writing(), "submitted demolition owns its world-write outcome");
        requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
        SimpleAssert.eq("demolition", requests.unresolvedDemolition(), "timeout requests demolition status directly");
        requests.acknowledge("directory");
        SimpleAssert.that(requests.writing(), "a cut-directory reply cannot resolve demolition");
        continuous.exit();
        requests.exitActivity();
        requests.begin("status", "demolitionStatus", "demolition", HologramCutRequests.WRITE_TIMEOUT_NS);
        requests.acknowledge("status");
        HologramCutRequests.Request resolved = requests.reconcile("demolition");
        SimpleAssert
            .that(resolved != null && !requests.currentActivity(resolved), "Escape retains only the pending outcome");
        SimpleAssert
            .that(!continuous.completed(resolved.id, true), "a delayed demolition ACK cannot restart after Escape");
        SimpleAssert.that(!requests.writing(), "matching status releases the original request without replay");
        SimpleAssert.that(requests.acknowledge("demolition") == null, "duplicate ACK has no local effect");
    }

    /** Actual client controller with detached Minecraft objects; no display, world work or network is fabricated. */
    private static void isometricSessionAndCompletion() {
        Field singleton = null;
        Object previous = null;
        try {
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
            minecraft.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            minecraft.thePlayer = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
            minecraft.thePlayer.inventory = new InventoryPlayer(minecraft.thePlayer);
            minecraft.thePlayer.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
            SimpleAssert.that(
                ItemNekoHologramProjector.isProjector(minecraft.thePlayer.getHeldItem()),
                "fixture holds a projector with its real Forge registry delegate");
            singleton.set(null, minecraft);

            Constructor<HologramCutClient> constructor = HologramCutClient.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            HologramCutClient local = constructor.newInstance();
            set(local, "demolitionMode", true);
            set(local, "cutSession", true);
            set(local, "stage", HologramCutInput.Stage.SELECTING);
            HologramAreaSelection chosen = new HologramAreaSelection();
            chosen.point(1, 4, 70, 8);
            chosen.point(2, 5, 71, 9);
            set(local, "selection", chosen);
            GuiScreen confirmation = (GuiScreen) unsafe.allocateInstance(HologramCutConfirmScreen.class);
            set(local, "demolitionConfirmation", confirmation);
            SimpleAssert
                .that(local.keepsCamera(confirmation), "only the current demolition confirmation keeps its camera");
            SimpleAssert.that(
                !local.keepsCamera((GuiScreen) unsafe.allocateInstance(HologramCutConfirmScreen.class))
                    && !local.keepsCamera(new GuiScreen()),
                "another confirmation or an unrelated GUI releases the old camera");
            ItemStack held = minecraft.thePlayer.inventory.mainInventory[0];
            minecraft.thePlayer.inventory.mainInventory[0] = null;
            SimpleAssert.that(!local.keepsCamera(confirmation), "losing the tool cannot retain a confirmation camera");
            minecraft.thePlayer.inventory.mainInventory[0] = held;

            HologramCutContinuous continuous = (HologramCutContinuous) get(local, "continuous");
            continuous.enter();
            continuous.submitted("first");
            Method receive = HologramCutClient.class
                .getDeclaredMethod("receiveDemolition", NBTTagCompound.class, HologramCutRequests.Request.class);
            receive.setAccessible(true);
            NBTTagCompound reply = new NBTTagCompound();
            reply.setBoolean("success", true);
            reply.setBoolean("demolitionActive", true);
            receive.invoke(local, reply, new HologramCutRequests.Request("first", "demolish", "", 0, 0));
            SimpleAssert.that(!local.selecting() && local.busy(), "start ACK waits for the server-owned job to finish");
            SimpleAssert.that(local.cameraContext(), "waiting for demolition preserves the same camera context");
            SimpleAssert.that(local.cameraClick(0), "waiting clicks cannot fall through to blueprint actions");

            HologramCutRequests requests = (HologramCutRequests) get(local, "requests");
            Method poll = HologramCutClient.class.getDeclaredMethod("pollDemolition", long.class);
            poll.setAccessible(true);
            long due = (Long) get(local, "demolitionPollAt");
            poll.invoke(local, due - 1);
            SimpleAssert.that(!requests.active("demolitionStatus"), "known-active status waits one second");
            poll.invoke(local, due);
            HologramCutRequests.Request status = requests.current(false);
            SimpleAssert
                .that(status != null && "first".equals(status.target), "status queries the accepted request only");
            poll.invoke(local, Long.MAX_VALUE);
            SimpleAssert
                .that(requests.current(false) == status, "one status request remains in flight without duplication");
            requests.acknowledge(status.id);
            reply.setString("reconciledRequestId", "first");
            reply.setBoolean("requestCompleted", true);
            reply.setBoolean("demolitionActive", false);
            receive.invoke(local, reply, status);
            SimpleAssert.that(local.selecting() && !local.busy(), "only an inactive server job resumes selection");
            SimpleAssert.that(
                local.selectionSnapshot()
                    .getTagList("areaRegions", 10)
                    .tagCount() == 0,
                "completion starts a fresh empty selection without another demolition submission");
            SimpleAssert.that(local.cameraContext(), "completion keeps the session camera context");

            set(local, "selection", chosen);
            continuous.submitted("rejected");
            reply.setBoolean("success", false);
            receive.invoke(local, reply, new HologramCutRequests.Request("rejected", "demolish", "", 0, 0));
            SimpleAssert.that(
                local.selecting() && get(local, "selection") == chosen && !local.busy(),
                "a rejected submission preserves the user's editable selection");
            local.cancel();
            SimpleAssert
                .that(!local.cameraContext() && !local.keepsCamera(confirmation), "cancel releases the session camera");
            poll.invoke(local, Long.MAX_VALUE);
            SimpleAssert.that(!requests.active("demolitionStatus"), "cancel stops status queries");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("demolition camera session and server completion", failure);
        } finally {
            if (singleton != null) try {
                singleton.set(null, previous);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("restore Minecraft singleton", failure);
            }
        }
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

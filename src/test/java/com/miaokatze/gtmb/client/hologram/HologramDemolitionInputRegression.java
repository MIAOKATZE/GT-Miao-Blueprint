// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

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
            destinationRequests(unsafe, chosen);
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

    /** Native confirmation buttons drive the actual client sender; only the socket is replaced by a recorder. */
    private static void destinationRequests(sun.misc.Unsafe unsafe, HologramAreaSelection chosen)
        throws ReflectiveOperationException {
        Constructor<HologramCutClient> constructor = HologramCutClient.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        HologramCutClient local = constructor.newInstance();
        set(local, "demolitionMode", true);
        set(local, "cutSession", true);
        set(local, "stage", HologramCutInput.Stage.SELECTING);
        set(local, "selection", chosen);
        HologramCutContinuous continuous = (HologramCutContinuous) get(local, "continuous");
        continuous.enter();
        Method submit = HologramCutClient.class.getDeclaredMethod("submitDemolition", NBTTagCompound.class, int.class);
        submit.setAccessible(true);
        Method receive = HologramCutClient.class
            .getDeclaredMethod("receiveDemolition", NBTTagCompound.class, HologramCutRequests.Request.class);
        receive.setAccessible(true);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previous = channel.get(null);
        RecordingChannel recorder = (RecordingChannel) unsafe.allocateInstance(RecordingChannel.class);
        recorder.messages = new ArrayList<>();
        int[] cancellations = { 0 };
        try {
            channel.set(null, recorder);
            IntValue destination = new IntValue((Integer) get(local, "demolitionDestination"));
            SimpleAssert.eq(0, destination.getIntValue(), "a new demolition session defaults to inventory");
            ModularPanel cancelled = destinationPanel(local, chosen, destination, submit, cancellations);
            SimpleAssert.eq(
                4L,
                cancelled.getChildren()
                    .stream()
                    .filter(ButtonWidget.class::isInstance)
                    .count(),
                "confirmation offers inventory, AE, cancel and confirm without a feet destination");
            press(cancelled, "demolition_destination_ae", 0);
            SimpleAssert.eq(1, destination.getIntValue(), "the actual AE button changes only the local draft");
            SimpleAssert.that(recorder.messages.isEmpty(), "choosing AE cannot submit demolition");
            press(cancelled, "cut_cancel", 0);
            SimpleAssert.eq(1, cancellations[0], "the native cancel button invokes cancellation once");
            SimpleAssert.that(
                recorder.messages.isEmpty() && local.selecting() && get(local, "selection") == chosen,
                "cancel sends no request and preserves the editable selection");
            SimpleAssert.eq(0, get(local, "demolitionDestination"), "cancel does not commit a destination preference");

            for (int target = 0; target < 2; target++) {
                set(local, "selection", chosen);
                destination = new IntValue((Integer) get(local, "demolitionDestination"));
                ModularPanel panel = destinationPanel(local, chosen, destination, submit, cancellations);
                String choice = target == 0 ? "demolition_destination_inventory" : "demolition_destination_ae";
                press(panel, choice, 1);
                press(panel, choice, 0);
                press(panel, "cut_confirm", 1);
                SimpleAssert.eq(target, recorder.messages.size(), "destination and secondary clicks send no request");
                press(panel, "cut_confirm", 0);
                SimpleAssert.eq(target + 1, recorder.messages.size(), "one separate primary confirmation sends once");
                ByteBuf bytes = Unpooled.buffer();
                NBTTagCompound sent;
                try {
                    recorder.messages.get(target)
                        .toBytes(bytes);
                    HologramNetwork.CutAction decoded = new HologramNetwork.CutAction();
                    decoded.fromBytes(bytes);
                    sent = (NBTTagCompound) get(decoded, "tag");
                } finally {
                    bytes.release();
                }
                SimpleAssert.eq("demolish", sent.getString("action"), "the real sender emits a demolition action");
                SimpleAssert
                    .that(sent.hasKey("demolitionDestination", 3), "every new request carries an int destination");
                SimpleAssert.eq(
                    target,
                    sent.getInteger("demolitionDestination"),
                    "the selected destination survives wire encoding");
                SimpleAssert.eq(
                    chosen.snapshot(),
                    sent.getCompoundTag("areaSelection"),
                    "destination preserves the chosen area");
                HologramCutRequests requests = (HologramCutRequests) get(local, "requests");
                HologramCutRequests.Request request = requests.acknowledge(sent.getString("requestId"));
                NBTTagCompound reply = new NBTTagCompound();
                reply.setBoolean("success", true);
                reply.setBoolean("demolitionActive", false);
                receive.invoke(local, reply, request);
                SimpleAssert
                    .that(local.selecting() && !local.busy(), "completion resumes the continuous selection session");
                SimpleAssert.eq(
                    target,
                    get(local, "demolitionDestination"),
                    "continuous selection keeps its confirmed destination");
            }
            local.cancel();
            SimpleAssert
                .eq(0, get(local, "demolitionDestination"), "leaving the session restores the inventory default");
        } finally {
            channel.set(null, previous);
        }
    }

    private static ModularPanel destinationPanel(HologramCutClient local, HologramAreaSelection chosen,
        IntValue destination, Method submit, int[] cancellations) {
        ModularPanel panel = new ModularPanel("demolition_destination_request");
        HologramCutDialogContent
            .demolitionConfirmation(panel, () -> "demolition", () -> "bounds", destination, (accepted, skip) -> {
                if (!accepted) {
                    cancellations[0]++;
                    return;
                }
                try {
                    submit.invoke(local, chosen.snapshot(), destination.getIntValue());
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("actual demolition sender", failure);
                }
            });
        return panel;
    }

    private static void press(ModularPanel panel, String name, int mouseButton) {
        for (IWidget widget : panel.getChildren()) if (name.equals(widget.getName())) {
            ((ButtonWidget<?>) widget).playClickSound(false)
                .onMousePressed(mouseButton);
            return;
        }
        throw new AssertionError("missing native confirmation button: " + name);
    }

    private static final class RecordingChannel extends SimpleNetworkWrapper {

        private List<IMessage> messages;

        private RecordingChannel() {
            super("demolition_ui_fixture");
        }

        @Override
        public void sendToServer(IMessage message) {
            messages.add(message);
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

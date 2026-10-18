package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real order fence and task-owner receipts: reordered transport, detached UI, terminal snapshots and retries. */
public final class HologramMaterialOrderLifecycleRegression {

    private HologramMaterialOrderLifecycleRegression() {}

    public static void run() {
        Object world = new Object(), connection = new Object();
        HologramState base = state();
        HologramMaterialOrderLifecycle orders = new HologramMaterialOrderLifecycle();
        orders.begin(world, connection, base, 12);
        NBTTagCompound running = event("first", 2, true);
        SimpleAssert.that(
            !orders.receive(new Object(), connection, base, running)
                && !orders.receive(world, new Object(), base, running),
            "a world or connection mismatch rejects progress");
        NBTTagCompound wrongRevision = (NBTTagCompound) running.copy();
        wrongRevision.setLong("planRevision", 10);
        SimpleAssert
            .that(!orders.receive(world, connection, base, wrongRevision), "a different plan cannot own the order");
        NBTTagCompound wrongNonce = (NBTTagCompound) running.copy();
        wrongNonce.setLong("placementReviewNonce", 6);
        SimpleAssert
            .that(!orders.receive(world, connection, base, wrongNonce), "unlock/relock invalidates old order progress");
        SimpleAssert.that(
            orders.receive(world, connection, base, running),
            "the exact current order admits its first publication");
        HologramState progressed = orders.apply(base);
        SimpleAssert.that(
            progressed.cells == base.cells && progressed.materials == base.materials
                && progressed.data.getCompoundTag("materialQuote") == base.data.getCompoundTag("materialQuote"),
            "order metadata shares geometry and quote instead of rebuilding them");
        exactTicketAcknowledgement(base, progressed);
        SimpleAssert.that(
            !orders.receive(world, connection, progressed, event("first", 1, true))
                && !orders.receive(world, connection, progressed, running)
                && !orders.receive(world, connection, progressed, event("another", 3, true)),
            "older, duplicate and unrelated order IDs cannot replace the admitted order");

        final int[] background = { 0 }, success = { 0 }, failure = { 0 };
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {
                background[0]++;
            }

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                if (result == HologramTaskOwner.Result.SUCCESS) success[0]++;
                else failure[0]++;
            }
        });
        long token = orders.restoreOwner(owner);
        owner.detach();
        owner.attach();
        SimpleAssert.that(
            orders.restoreOwner(owner) == token && owner.busy(),
            "reopening a detached material review keeps the original task owner");
        owner.detach();
        SimpleAssert.eq(1, background[0], "repeated detaches announce one material task");

        NBTTagCompound complete = event("first", 3, false);
        complete.getCompoundTag("materialOrder")
            .setString("state", "complete");
        SimpleAssert
            .that(orders.receive(world, connection, progressed, complete), "the CPU terminal publication is admitted");
        HologramState finished = orders.apply(progressed);
        SimpleAssert.that(
            orders.finishOwner(owner) && !orders.finishOwner(owner) && !owner.busy(),
            "completion has one authoritative notification boundary");
        SimpleAssert.eq(1, success[0], "the success callback that owns chat and levelup sound fires once");
        SimpleAssert.eq(0, failure[0], "job zero is not interpreted as a material-order failure");
        SimpleAssert.that(
            !orders.receive(world, connection, finished, event("first", 4, true)),
            "even a higher sequence cannot revive a terminal order ID");
        NBTTagCompound staleMetadata = new NBTTagCompound();
        staleMetadata.setTag("materialOrder", running.getCompoundTag("materialOrder"));
        HologramState stale = finished.withMetadata(staleMetadata);
        HologramState retained = orders.snapshot(world, connection, stale);
        SimpleAssert.that(
            !retained.data.getCompoundTag("materialOrder")
                .getBoolean("active")
                && retained.cells == finished.cells
                && retained.data.getCompoundTag("materialOrder")
                    .getLong("orderSequence") == 3,
            "late full-snapshot metadata cannot revive or roll back the completed order");
        SimpleAssert.eq(0L, orders.restoreOwner(owner), "a completed order cannot reacquire the projector");

        orders.begin(world, connection, finished, 13);
        SimpleAssert.that(
            !orders.receive(world, connection, finished, complete),
            "a new command fences out an old terminal event");
        NBTTagCompound retry = event("second", 1, true);
        retry.setLong("uiSequence", 13);
        retry.getCompoundTag("materialOrder")
            .setLong("orderUiSequence", 13);
        SimpleAssert.that(orders.receive(world, connection, finished, retry), "a new order command admits its new ID");
        SimpleAssert.that(orders.restoreOwner(owner) > token, "a new order owns a distinct token after completion");

        HologramMaterialOrderLifecycle recovered = new HologramMaterialOrderLifecycle();
        HologramState recoveredState = finished.withMetadata(metadata(retry));
        recovered.snapshot(world, connection, recoveredState);
        SimpleAssert.that(
            recovered.active() && "second".equals(recovered.id()) && recovered.command() == 13,
            "a current full snapshot restores an in-flight server order without its old screen");

        HologramMaterialOrderLifecycle refused = new HologramMaterialOrderLifecycle();
        refused.begin(world, connection, base, 12);
        NBTTagCompound refusal = new NBTTagCompound();
        refusal.setLong("uiSequence", 12);
        refusal.setString("status", "无线终端不可用");
        HologramState refusedState = base.withMetadata(refusal);
        SimpleAssert.that(
            refused.rejectSubmission(refusedState) && !refused.rejectSubmission(refusedState),
            "a matching command ACK with no order ID resolves admission failure exactly once");
        HologramTaskOwner refusedOwner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {}

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                SimpleAssert.that(
                    result == HologramTaskOwner.Result.FAILURE && detail.contains("终端"),
                    "submission failure retains the authoritative terminal reason");
                failure[0]++;
            }
        });
        refusedOwner.begin("material", "materials:12", "AE order");
        SimpleAssert.that(
            refused.finishOwner(refusedOwner) && !refusedOwner.busy(),
            "failed admission releases the projector without waiting for an impossible order publication");
        HologramState refusedLate = refused.snapshot(world, connection, refusedState.withMetadata(metadata(running)));
        SimpleAssert.that(
            !refusedLate.data.getCompoundTag("materialOrder")
                .getBoolean("active"),
            "a late active full snapshot cannot revive a rejected submission");
    }

    private static HologramState state() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "materials");
        data.setLong("planRevision", 9);
        data.setLong("placementReviewNonce", 5);
        data.setLong("uiSequence", 11);
        data.setInteger("job", 0);
        data.setTag("materialQuote", new NBTTagCompound());
        NBTTagList cells = new NBTTagList();
        cells.appendTag(new NBTTagCompound());
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static void exactTicketAcknowledgement(HologramState before, HologramState next) {
        try {
            HologramUiModules ui = new HologramUiModules(null);
            java.lang.reflect.Field state = HologramUiSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(ui.session, before);
            java.lang.reflect.Field operation = HologramUiSession.class.getDeclaredField("pendingOperation");
            operation.setAccessible(true);
            operation.set(ui.session, "orderMissingMaterials");
            ui.session.pendingSequence(12);
            ui.session.materialOrderMetadata(next, 11);
            SimpleAssert.eq(12L, ui.session.pendingSequence(), "an old order heartbeat cannot ACK the current ticket");
            ui.session.materialOrderMetadata(next, 12);
            SimpleAssert.eq(-1L, ui.session.pendingSequence(), "the exact order receipt clears its original UI ticket");
            operation.set(ui.session, "configure");
            ui.session.pendingSequence(13);
            ui.session.materialOrderMetadata(next, 13);
            SimpleAssert
                .eq(13L, ui.session.pendingSequence(), "order progress cannot acknowledge an unrelated UI operation");
            SimpleAssert.that(
                ui.session.state().cells == before.cells,
                "the live UI metadata path retains the exact geometry object");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot install the real client ticket fixture", failure);
        }
    }

    private static NBTTagCompound event(String id, long sequence, boolean active) {
        NBTTagCompound event = new NBTTagCompound();
        event.setBoolean("materialOrderOnly", true);
        event.setString("scope", "server");
        event.setString("phase", "material.order");
        event.setString("session", "materials");
        event.setLong("planRevision", 9);
        event.setLong("placementReviewNonce", 5);
        event.setLong("uiSequence", 12);
        event.setString("orderId", id);
        event.setLong("orderSequence", sequence);
        NBTTagCompound order = new NBTTagCompound();
        order.setString("orderId", id);
        order.setLong("orderSequence", sequence);
        order.setLong("orderUiSequence", 12);
        order.setLong("planRevision", 9);
        order.setLong("placementReviewNonce", 5);
        order.setBoolean("active", active);
        order.setString("state", active ? "running" : "complete");
        order.setString("status", active ? "AE running" : "AE complete");
        order.setLong("done", active ? 3 : 10);
        order.setLong("total", 10);
        event.setTag("materialOrder", order);
        return event;
    }

    private static NBTTagCompound metadata(NBTTagCompound event) {
        NBTTagCompound patch = new NBTTagCompound();
        patch.setTag("materialOrder", event.getCompoundTag("materialOrder"));
        return patch;
    }
}

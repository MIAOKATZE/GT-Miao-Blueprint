package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Tests the production receipt used by the actual asynchronous private receiver, not a synchronous wrapper. */
public final class HologramLibraryActivationResultRegression {

    private HologramLibraryActivationResultRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLibraryActivationResultRegression.class, args)) return;
        run();
        System.out.println("Library activation completion regressions passed");
    }

    public static void run() {
        List<NBTTagCompound> replies = new ArrayList<>();
        boolean[] source = { true }, transport = { true };
        NBTTagCompound request = request(7);
        HologramLibraryActivationResult result = new HologramLibraryActivationResult(
            request,
            "privateImported",
            () -> source[0],
            () -> transport[0],
            replies::add);
        HologramLibraryFacade.ActivationCompletion[] pending = { null };
        HologramLibraryFacade.PrivateReceiver receiver = (player, frozen, preset,
            completion) -> pending[0] = completion;
        try {
            receiver.accept(null, request, null, result);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        result.poll();
        SimpleAssert.eq(0, replies.size(), "receiver admission and in-progress polling send no success ACK");
        request.setString("session", "mutated");
        source[0] = false; // Completed presentation retires its source before calling completed.
        pending[0].completed("private_ready");
        pending[0].completed("private_duplicate");
        pending[0].failed("late failure");
        SimpleAssert.eq(1, replies.size(), "ready callback sends exactly one terminal ACK");
        SimpleAssert.eq(
            "privateImported",
            replies.get(0)
                .getString("op"),
            "success is activation completion");
        SimpleAssert.eq(
            "source",
            replies.get(0)
                .getString("session"),
            "receipt identity frozen at admission");
        SimpleAssert.eq(
            "transfer",
            replies.get(0)
                .getString("transfer"),
            "transfer preserved");
        SimpleAssert.eq(
            7L,
            replies.get(0)
                .getLong("clientTaskToken"),
            "task preserved");
        SimpleAssert.eq(
            "private_ready",
            replies.get(0)
                .getString("id"),
            "only completed candidate acknowledged");
        SimpleAssert.that(!result.supersededBy(request(6)), "older completion cannot replace newer result");
        SimpleAssert.that(!result.supersededBy(request(7)), "duplicate callback cannot replace its own result");
        SimpleAssert.that(result.supersededBy(request(8)), "new request can replace completed receipt");

        for (boolean cancelTransport : new boolean[] { false, true }) {
            replies.clear();
            source[0] = transport[0] = true;
            HologramLibraryActivationResult cancelled = new HologramLibraryActivationResult(
                request(8),
                "privateCached",
                () -> source[0],
                () -> transport[0],
                replies::add);
            if (cancelTransport) transport[0] = false;
            else source[0] = false;
            cancelled.poll();
            cancelled.poll();
            cancelled.completed("late_candidate");
            cancelled.failed("duplicate failure");
            SimpleAssert.eq(1, replies.size(), "cancel/stale terminal exactly once");
            SimpleAssert.eq(
                "error",
                replies.get(0)
                    .getString("op"),
                "cancel/stale cannot issue imported ACK");
            SimpleAssert.that(!cancelled.current(), "terminal completion cannot resume activation");
        }
        replies.clear();
        source[0] = transport[0] = true;
        boolean[] confirmation = { true };
        HologramLibraryActivationResult modal = new HologramLibraryActivationResult(
            request(9),
            "privateImported",
            () -> source[0],
            () -> transport[0],
            replies::add);
        modal.waitingForConfirmation(() -> confirmation[0]);
        modal.poll();
        SimpleAssert.eq(0, replies.size(), "projection confirmation is not an imported ACK");
        confirmation[0] = false;
        modal.poll();
        modal.completed("late_confirm");
        SimpleAssert.eq(1, replies.size(), "cancelled projection prompt terminates original receipt once");
        SimpleAssert.eq(
            "error",
            replies.get(0)
                .getString("op"),
            "modal cancel cannot masquerade as ready");
        replies.clear();
        source[0] = transport[0] = true;
        HologramLibraryActivationResult failure = new HologramLibraryActivationResult(
            request(9),
            "publicPrepared",
            () -> source[0],
            () -> transport[0],
            replies::add);
        failure.failed("native orientation rejected");
        failure.completed("late");
        SimpleAssert.eq(1, replies.size(), "failure then success race resolves once");
        SimpleAssert.eq(
            "native orientation rejected",
            replies.get(0)
                .getString("message"),
            "real failure retained");
    }

    private static NBTTagCompound request(long token) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", "source");
        request.setString("transfer", "transfer");
        request.setLong("clientTaskToken", token);
        return request;
    }
}

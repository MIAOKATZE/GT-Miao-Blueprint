// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.hologram.HologramPrivateNativeWire.*;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.INetHandler;

import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.CommitReceipt;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Outcome;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Phase;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Ticket;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramPlacementTiming;
import com.miaokatze.gtmb.hologram.HologramPrivateNativeWire;
import com.miaokatze.gtmb.hologram.HologramPrivateNativeWire.Reply;
import com.miaokatze.gtmb.hologram.HologramPrivateNativeWire.Request;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/** Submit a saved version to a dedicated server. No compressed source frame exists until its explicit cache miss. */
public final class HologramClientRemoteNativePlacement {

    private static volatile Task active;
    private static Consumer<Request> wire;

    private HologramClientRemoteNativePlacement() {}

    public static void install() {
        install(HologramPrivateNativeWire::send);
        HologramPrivateNativeWire.listen(HologramClientRemoteNativePlacement::receive);
    }

    static void install(Consumer<Request> sender) {
        wire = sender;
    }

    static boolean available() {
        return wire != null;
    }

    static boolean start(HologramLibraryClient owner, HologramLocalPlacement.Submission source, Minecraft client) {
        if (wire == null || active != null
            || !source.current()
            || client.isIntegratedServerRunning()
            || client.getNetHandler() == null) return false;
        UUID tool;
        try {
            tool = UUID.fromString(HologramLibraryClient.projectorId(client.thePlayer.getHeldItem()));
        } catch (RuntimeException bad) {
            return false;
        }
        Request req = new Request();
        req.op = BEGIN;
        req.id = UUID.randomUUID();
        req.tool = tool;
        req.intent = HologramNetwork.nextConstructionIntentSequence();
        var opts = source.intent();
        req.x = opts.getInteger("x");
        req.y = opts.getInteger("y");
        req.z = opts.getInteger("z");
        req.dimension = client.thePlayer.dimension;
        req.pose = opts.getIntArray("previewPose");
        req.sources = opts.getInteger("reviewedSources");
        req.voucher = opts.getBoolean("voucherRequested");
        req.bindingSlots = opts.getIntArray("savedBindingSlots");
        var receipt = source.source().nativeReceipt;
        req.sha = receipt.physicalHash;
        Task task = new Task(owner, req, client.getNetHandler(), client.theWorld);
        task.ticket = HologramBackgroundTasks.beginOwnedPlacement(req.id, "原生蓝图施工", task::cancel);
        if (task.ticket == null) return false;
        HologramPlacementTiming.clientRequest("blueprint", req.id);
        active = task;
        owner.projectedWork(() -> {
            var version = NativeBlueprintPairStore.watchForAdmission(receipt);
            FileChannel channel = FileChannel.open(receipt.source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                req.length = Math.toIntExact(channel.size());
                if (req.length < 1 || req.length > com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintLimits.FILE_BYTES)
                    throw new IOException("原生文件过大");
                synchronized (task) {
                    if (task.retired) {
                        channel.close();
                        return;
                    }
                    task.file = channel;
                    task.version = version;
                }
                owner.projectedMain(() -> {
                    if (task.retired) return;
                    if (task.cancelled || !version.getAsBoolean()
                        || !source.current()
                        || client.getNetHandler() != task.connection) {
                        task.finish(Outcome.CANCELLED, 0, 0, 0, "已取消或保存版本已改变");
                        return;
                    }
                    task.begun = true;
                    HologramPlacementTiming.clientPhase("blueprint", req.id, "begin");
                    wire.accept(req);
                });
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }, failure -> task.finish(Outcome.FAILED, 0, 0, 0, "无法打开指定的已保存版本"));
        return true;
    }

    /** Net thread only transfers the bounded decoded reply to the owner thread. Old connections cannot revive a job. */
    static void receive(Reply m, INetHandler connection) {
        Task t = active;
        if (t != null && !t.retired && t.connection == connection && t.begin.id.equals(m.id)) t.inbox.offer(m);
    }

    static void tick(Minecraft client) {
        Task t = active;
        if (t == null) return;
        if (client == null || client.theWorld != t.world || client.getNetHandler() != t.connection) {
            t.finish(Outcome.CANCELLED, 0, 0, 0, "连接已关闭；服务器按已落方块结算");
            return;
        }
        Reply m;
        while (t == active && (m = t.inbox.poll()) != null) t.accept(m);
        if (t == active && System.nanoTime() - t.last > TimeUnit.MINUTES.toNanos(3)) {
            t.cancel();
            t.finish(Outcome.FAILED, 0, 0, 0, "等待服务器施工回执超时，不能确认结果");
        }
    }

    static void show() {
        Task t = active;
        if (t != null) HologramBackgroundTasks.showOwnedPlacement(t.ticket);
    }

    private static final class Task {

        final HologramLibraryClient owner;
        final Request begin;
        final INetHandler connection;
        final Object world;
        final ArrayBlockingQueue<Reply> inbox = new ArrayBlockingQueue<>(64);
        Ticket ticket;
        FileChannel file;
        volatile NativeBlueprintPairStore.AdmissionVersion version;
        volatile boolean retired;
        boolean begun, cancelled, reading, started;
        long seen, last = System.nanoTime();

        Task(HologramLibraryClient owner, Request begin, INetHandler connection, Object world) {
            this.owner = owner;
            this.begin = begin;
            this.connection = connection;
            this.world = world;
        }

        boolean cancel() {
            if (retired || cancelled) return false;
            cancelled = true;
            if (begun) {
                Request m = new Request();
                m.id = begin.id;
                m.op = CANCEL;
                wire.accept(m);
            }
            return true;
        }

        void accept(Reply m) {
            if (m.seq <= seen || m.offset > begin.length) return;
            seen = m.seq;
            last = System.nanoTime();
            if (!started && (m.op == COMPLETE
                || m.op == PROGRESS && !HologramPrivateNativeWire.PREPARING_PROGRESS.equals(m.message))) {
                started = true;
                Minecraft.getMinecraft().thePlayer.closeScreen();
            }
            switch (m.op) {
                case NEED -> {
                    if (cancelled || reading) return;
                    if (version == null || !version.getAsBoolean()) {
                        cancel();
                        return;
                    }
                    if (m.offset >= begin.length) {
                        cancel();
                        finish(Outcome.FAILED, 0, 0, 0, "无效的服务器上传偏移");
                        return;
                    }
                    HologramBackgroundTasks
                        .observeOwnedPlacement(ticket, Phase.PREFLIGHT, m.seq, m.offset, begin.length);
                    reading = true;
                    owner.projectedWork(() -> {
                        ByteBuffer bytes = ByteBuffer.allocate(Math.min(FRAME, begin.length - m.offset));
                        long at = m.offset;
                        while (bytes.hasRemaining()) {
                            int n = file.read(bytes, at);
                            if (n <= 0) throw new EOFException();
                            at += n;
                        }
                        owner.projectedMain(() -> {
                            reading = false;
                            if (retired || cancelled) return;
                            if (!version.getAsBoolean()) {
                                cancel();
                                return;
                            }
                            Request data = new Request();
                            data.op = DATA;
                            data.id = begin.id;
                            data.offset = m.offset;
                            data.bytes = bytes.array();
                            wire.accept(data);
                        });
                    }, failure -> {
                        cancel();
                        finish(Outcome.FAILED, 0, 0, 0, "原生源读取失败");
                    });
                }
                case PROGRESS -> {
                    Phase phase = HologramPrivateNativeWire.PREPARING_PROGRESS.equals(m.message) ? Phase.ADMISSION
                        : Phase.EXECUTING;
                    HologramBackgroundTasks
                        .observeOwnedPlacement(ticket, phase, m.seq, (long) m.placed + m.satisfied, m.total);
                }
                case COMPLETE -> {
                    HologramPlacementTiming.clientPhase("blueprint", begin.id, "complete_seen");
                    boolean accepted = HologramBackgroundTasks.acceptOwnedPlacementCommit(
                        ticket,
                        new CommitReceipt(
                            begin.id,
                            m.seq,
                            m.placed == 0 ? Outcome.NO_WORK : Outcome.SUCCESS,
                            m.placed,
                            m.satisfied,
                            m.total),
                        m.message);
                    if (accepted) HologramLocalPlacement.INSTANCE.clear();
                    else HologramLocalPlacement.INSTANCE.retry();
                    retire();
                }
                case FAILED, CANCELLED -> finish(
                    m.op == FAILED ? Outcome.FAILED : Outcome.CANCELLED,
                    m.placed,
                    m.satisfied,
                    m.total,
                    m.message);
                default -> {}
            }
        }

        void finish(Outcome outcome, int placed, int satisfied, int total, String why) {
            if (active != this || retired) return;
            HologramBackgroundTasks.finishOwnedPlacement(ticket, seen + 1, outcome, placed, satisfied, total, why);
            HologramLocalPlacement.INSTANCE.retry();
            retire();
        }

        synchronized void retire() {
            retired = true;
            if (active == this) active = null;
            if (file != null) try {
                file.close();
            } catch (IOException ignored) {}
        }
    }
}

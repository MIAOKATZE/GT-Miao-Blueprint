// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;

import cpw.mods.fml.relauncher.Side;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/** Passive observer: every production request and reply is forwarded unchanged. No request or ACK is manufactured. */
final class HologramFourMachineWireAudit {

    private static final String HANDLER = "gtmb-four-machine-wire-audit";
    private static final int MAX_EVENTS = 4096;
    private final Object connection;
    private final Channel channel;
    private final HologramNetwork.StockNativeReplies original, observer;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Map<UUID, HologramStockNativeWire.Reply> latest = new LinkedHashMap<>();
    private boolean closed;
    private String failure = "";
    private int beginCommands;
    private Begin latestBegin;

    static final class Begin {

        final UUID request;
        final int ordinal, operation, sources, destination;
        final String targetDigest;

        Begin(UUID request, int ordinal, NBTTagCompound payload) {
            this.request = request;
            this.ordinal = ordinal;
            operation = payload.getInteger("projectionOperation");
            sources = payload.getInteger("sources");
            destination = payload.getInteger("destination");
            targetDigest = payload.getString("nativeTargetDigest");
        }
    }

    HologramFourMachineWireAudit(Minecraft mc) throws Exception {
        connection = mc.getNetHandler();
        if (connection == null || !mc.isIntegratedServerRunning())
            throw new IllegalStateException("Expected the owned integrated player's actual connection");
        Object wrapper = field(HologramNetwork.class, "channel").get(null);
        channel = (Channel) ((Map<?, ?>) field(wrapper.getClass(), "channels").get(wrapper)).get(Side.CLIENT);
        if (channel == null || channel.pipeline()
            .get(HANDLER) != null)
            throw new IllegalStateException("Actual GTMB channel unavailable or already observed");
        Field payload = field(HologramNetwork.Packet.class, "tag");
        channel.pipeline()
            .addLast(HANDLER, new ChannelOutboundHandlerAdapter() {

                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                    throws Exception {
                    if (message instanceof HologramNetwork.Action) {
                        NBTTagCompound tag = (NBTTagCompound) payload.get(message);
                        var event = new LinkedHashMap<String, Object>();
                        event.put("direction", "C2S");
                        event.put(
                            "payload",
                            tag.copy()
                                .toString());
                        event.put("nanoTime", System.nanoTime());
                        if ("serverRules".equals(tag.getString("action")) && tag.hasKey("nativeStockCommand", 1)) {
                            int command = tag.getByte("nativeStockCommand");
                            event.put("nativeCommand", command);
                            if (command == HologramStockNativeWire.Command.BEGIN.ordinal()) synchronized (events) {
                                beginCommands++;
                                try {
                                    latestBegin = new Begin(
                                        UUID.fromString(tag.getString("nativeStockRequest")),
                                        beginCommands,
                                        tag);
                                } catch (IllegalArgumentException malformed) {
                                    failure = "Real native BEGIN omitted a valid request UUID";
                                }
                            }
                        }
                        append(event);
                    }
                    context.write(message, promise);
                }
            });
        original = receiver();
        observer = (reply, inbound) -> {
            if (inbound == connection) {
                var event = new LinkedHashMap<String, Object>();
                event.put("direction", "S2C");
                event.put(
                    "request",
                    reply.key()
                        .request()
                        .toString());
                event.put(
                    "sequence",
                    reply.key()
                        .sequence());
                event.put(
                    "phase",
                    reply.phase()
                        .name());
                event.put("placed", reply.placed());
                event.put("reason", reply.reason());
                NBTTagCompound snapshot = reply.siteSnapshot();
                event.put("siteSnapshot", snapshot != null);
                if (snapshot != null) event.put(
                    "siteCells",
                    snapshot.getTagList("cells", 10)
                        .tagCount());
                event.put("nanoTime", System.nanoTime());
                var observation = reply.observation();
                if (observation != null) {
                    var summary = new LinkedHashMap<String, Object>();
                    summary.put("controller", observation.controller());
                    summary.put("x", observation.x());
                    summary.put("y", observation.y());
                    summary.put("z", observation.z());
                    summary.put(
                        "stage",
                        observation.stage()
                            .name());
                    summary.put("targetRevision", observation.targetRevision());
                    summary.put("reason", observation.reason());
                    event.put("serverObservation", summary);
                }
                synchronized (events) {
                    latest.put(
                        reply.key()
                            .request(),
                        new HologramStockNativeWire.Reply(
                            reply.key(),
                            reply.phase(),
                            reply.placed(),
                            reply.reason(),
                            reply.observation()));
                    append(event);
                }
            }
            if (original != null) original.accept(reply, inbound);
        };
        HologramNetwork.setStockNativeReplies(observer);
    }

    int eventCount() {
        synchronized (events) {
            return events.size();
        }
    }

    List<Map<String, Object>> snapshot() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    String failure() {
        synchronized (events) {
            return failure;
        }
    }

    int beginCount() {
        synchronized (events) {
            return beginCommands;
        }
    }

    Begin beginAfter(int ordinal) {
        synchronized (events) {
            return latestBegin != null && latestBegin.ordinal > ordinal ? latestBegin : null;
        }
    }

    HologramStockNativeWire.Reply latest(UUID request) {
        synchronized (events) {
            return latest.get(request);
        }
    }

    void close() throws Exception {
        if (closed) return;
        closed = true;
        if (channel.pipeline()
            .get(HANDLER) != null)
            channel.pipeline()
                .remove(HANDLER);
        if (receiver() == observer) HologramNetwork.setStockNativeReplies(original);
    }

    private void append(Map<String, Object> event) {
        synchronized (events) {
            if (events.size() >= MAX_EVENTS) {
                failure = "Four-machine wire trace exceeded its bound; evidence incomplete";
                return;
            }
            events.add(event);
        }
    }

    private static HologramNetwork.StockNativeReplies receiver() throws Exception {
        return (HologramNetwork.StockNativeReplies) field(HologramNetwork.class, "stockNativeReplies").get(null);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

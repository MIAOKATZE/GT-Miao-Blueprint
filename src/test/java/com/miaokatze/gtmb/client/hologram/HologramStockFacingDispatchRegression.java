// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.buffer.Unpooled;

/**
 * All StructureLib alignments through the real client owner, writer, wire codec and server reader.
 * Only replace Forge's final socket handoff; this is not a running client/server or a world placement test.
 */
public final class HologramStockFacingDispatchRegression {

    private HologramStockFacingDispatchRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramStockFacingDispatchRegression.class, args)) return;
        HologramStockNativeClientRegression.run(); // real MUI ID55 -> local confirmation -> Forge request/receipt
        Field channel = field(HologramNetwork.class, "channel");
        Object saved = channel.get(null);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Capture capture = (Capture) unsafe.allocateInstance(Capture.class);
        capture.frames = new ArrayList<>();
        try {
            channel.set(null, capture);
            HologramClientStockNative.Flow owner = HologramClientStockNative.OWNER;
            int tested = 0;
            for (ExtendedFacing direction : ExtendedFacing.values()) {
                Object handler = new Object();
                var ctx = new HologramClientStockNative.Context(handler, new Object(), new Object(), 0);
                var notices = new ArrayList<String>();
                var view = new HologramClientStockNative.Owner(
                    new Object(),
                    () -> true,
                    () -> true,
                    notices::add,
                    () -> {});
                int facing = direction.ordinal();
                Confirmation c = confirmation(facing);
                capture.frames.clear();
                if (!owner.submit(c, ctx, view, 100))
                    throw new AssertionError("probe rejected " + direction + ": " + notices);
                verify(capture, Command.PROBE, c, 0);
                var proved = new Key(
                    c.key()
                        .request(),
                    c.key()
                        .sequence(),
                    7);
                owner.receive(new Reply(proved, Phase.CAPABLE, 0, ""), handler, ctx, 101);
                verify(capture, Command.BEGIN_AFTER_PROBE, c, 7);
                if (capture.frames.size() != 2) throw new AssertionError("not one probe and one construction");
                owner.receive(new Reply(proved, Phase.COMPLETE, 1, ""), handler, ctx, 102);
                ++tested;
            }
            for (int invalid : new int[] { -1, ExtendedFacing.STATES_COUNT, Integer.MAX_VALUE }) {
                try {
                    HologramStockNativeWire.probe(confirmation(invalid));
                } catch (IllegalArgumentException expected) {
                    continue;
                }
                throw new AssertionError("invalid facing admitted: " + invalid);
            }
            System.out.println(
                "PASS stock-native-facing-dispatch: " + tested
                    + " alignments, probe + begin bytes/ingress; 3 invalid rejected");
        } finally {
            channel.set(null, saved);
        }
    }

    private static Confirmation confirmation(int facing) {
        var d = new HologramDeclaredNativeSelection(
            1000,
            Collections.singletonMap("coil", 2),
            0,
            0,
            Collections.emptyList(),
            Collections.emptyList());
        return Confirmation.declared(
            new Key(UUID.randomUUID(), HologramNetwork.nextConstructionIntentSequence(), 0),
            0,
            UUID.randomUUID(),
            10,
            64,
            20,
            3,
            4,
            facing,
            1,
            true,
            false,
            1 | 4 | (2 << 3),
            d);
    }

    private static void verify(Capture capture, Command cmd, Confirmation c, long gen) {
        if (capture.frames.isEmpty()) throw new AssertionError("no frame for " + cmd);
        var tag = capture.frames.get(capture.frames.size() - 1);
        if (tag.getByte("nativeStockCommand") != cmd.ordinal() || tag.getInteger("facing") != c.facing()
            || tag.getLong("nativeStockGeneration") != gen
            || !tag.getString("tool")
                .equals(
                    c.tool()
                        .toString())
            || tag.getInteger("sources") != c.sources()
            || tag.getInteger("side") != c.side()
            || tag.getInteger("slot") != c.slot()
            || tag.getCompoundTag("channels")
                .getInteger("coil") != 2)
            throw new AssertionError("incorrect dispatch " + cmd + " facing=" + c.facing());
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static final class Capture extends SimpleNetworkWrapper {

        List<NBTTagCompound> frames;

        Capture() {
            super("gtmb_stock_face_test");
        }

        @Override
        public void sendToServer(IMessage packet) {
            if (!(packet instanceof HologramNetwork.Action)) throw new AssertionError("not stock Action");
            var bytes = Unpooled.buffer();
            try {
                packet.toBytes(bytes);
                var decoded = new HologramNetwork.Action();
                decoded.fromBytes(bytes);
                if (bytes.isReadable()) throw new AssertionError("trailing bytes");
                var tag = (NBTTagCompound) field(HologramNetwork.Packet.class, "tag").get(decoded);
                Method reader = HologramStockNativeWire.class.getDeclaredMethod("readRequest", NBTTagCompound.class);
                reader.setAccessible(true);
                if (reader.invoke(null, tag) == null)
                    throw new AssertionError("server reader rejected legal orientation");
                frames.add(tag);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                bytes.release();
            }
        }
    }
}

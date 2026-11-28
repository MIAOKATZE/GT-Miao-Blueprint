// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.gtnewhorizon.structurelib.event.BlockChangeListener;
import com.gtnewhorizon.structurelib.event.BlockChangeNotifier;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.buffer.Unpooled;

/** Native packet codecs and real UI/PROBE/cache consumers in an offline fixture; no running game or GL claim. */
public final class HologramControllerNotificationsRegression {

    private HologramControllerNotificationsRegression() {}

    public static void run() {
        sparseRegions();
        try {
            HologramStockNativeClientRegression.runNotifications();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void sparseRegions() {
        int[] lookups = { 0 };
        Map<HologramRegionCache.Key, List<HologramState.Cell>> nativeView = new AbstractMap<>() {

            public List<HologramState.Cell> get(Object value) {
                lookups[0]++;
                return new HologramRegionCache.Key(-2, 15, -3).equals(value) ? java.util.Collections.emptyList() : null;
            }

            public Set<Entry<HologramRegionCache.Key, List<HologramState.Cell>>> entrySet() {
                throw new AssertionError("Packet lookup must not enumerate the native view or cells");
            }
        };
        var regions = new HologramRegionCache(
            nativeView,
            new int[] { -32, 240, -48, -17, 255, -33 },
            (x, y, z) -> { throw new AssertionError("No per-cell lookup for chunk notifications"); });
        SimpleAssert
            .that(regions.intersectsChunk(-2, -3, 1 << 15), "signed high section bit and negative chunks match");
        SimpleAssert.eq(1, lookups[0], "only the supplied section is queried");
        SimpleAssert.that(!regions.intersectsChunk(-1, -3, 0xffff), "adjacent nonmember chunk is ignored");
        SimpleAssert.eq(17, lookups[0], "a whole chunk uses at most sixteen existing index lookups");
        SimpleAssert.that(!regions.intersectsChunk(-2, -3, 0), "empty partial mask does not invalidate anything");
        SimpleAssert.eq(17, lookups[0], "empty mask reads no regions");
    }

    @SuppressWarnings("unchecked")
    static void exercise(sun.misc.Unsafe unsafe, Minecraft client, WorldClient world, HologramScreen screen,
        NetHandlerPlayClient connection, NetHandlerPlayClient other, HologramState original, List<NBTTagCompound> sent)
        throws ReflectiveOperationException {
        field(World.class, "isRemote").set(world, true);
        field(World.class, "worldAccesses").set(world, new java.util.ArrayList<>());
        field(HologramMuiCanvasScreen.class, "fieldGraphs").set(screen, new java.util.IdentityHashMap<>());
        field(HologramMuiCanvasScreen.class, "fields").set(screen, new java.util.ArrayList<>());
        field(net.minecraft.entity.Entity.class, "entityUniqueID").set(client.thePlayer, java.util.UUID.randomUUID());
        var row = original.cells.get(0);
        int x = row.x >> 4, z = row.z >> 4, section = 1 << (row.y >> 4);
        int otherSection = 1 << ((row.y >> 4) ^ 1);
        var renderer = HologramRenderer.editor();
        var visible = field(MiaoVisonLiteRenderer.class, "visibleState");
        var displayed = field(MiaoVisonLiteRenderer.class, "displayedState");
        Object oldVisible = visible.get(renderer), oldDisplayed = displayed.get(renderer);
        Object faces = field(MiaoVisonLiteRenderer.class, "cachedFaces").get(renderer);
        var textures = renderer.assets().textures;
        HologramState oldTextureSnapshot = textures.snapshot;
        var oldMaterial = textures.cells.get(row);
        var material = new HologramTextures.Material[2];
        var listeners = (List<BlockChangeListener>) field(BlockChangeNotifier.class, "listeners").get(null);
        BlockChangeListener listener = (w, c, bx, by, bz, oldBlock, newBlock, oldMeta, newMeta) -> HologramClient
            .nativeBlockChanged(w, bx, by, bz);
        BlockChangeNotifier.addListener(listener);
        var chunk = (Chunk) unsafe.allocateInstance(Chunk.class);
        try {
            visible.set(renderer, original);
            displayed.set(renderer, original);
            textures.snapshot = original;
            textures.cells.put(row, material);
            needed(screen, false);
            HologramClient.nativeChunkReceived(other, world, single(x, z, false, section, 0));
            HologramClient.nativeChunkReceived(null, world, single(x, z, false, section, 0));
            HologramClient.nativeChunkReceived(connection, world, single(x + 100, z, false, section, 0));
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, otherSection, section));
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, 0, section));
            SimpleAssert
                .that(!needed(screen), "wrong connection, unrelated chunk, wrong/empty primary mask never reads state");
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, section, 0));
            SimpleAssert.that(
                needed(screen) && "UNKNOWN".equals(screen.ui.session.state().data.getString("nativeStage")),
                "partial S21 invalidates only the authority observation");
            retained(screen, original, renderer, faces, material);
            request(screen);
            SimpleAssert.eq(1, sent.size(), "one event batch produces one existing PROBE");
            var first = pending(screen);
            HologramClient
                .nativeChunksReceived(connection, world, bulk(new int[] { x + 100, x, x }, new int[] { z, z, z }));
            request(screen);
            SimpleAssert.eq(1, sent.size(), "S26 changes during an in-flight read do not start a second request");
            reply(screen, connection, first, Stage.FORMED);
            SimpleAssert.that(
                needed(screen) && "UNKNOWN".equals(screen.ui.session.state().data.getString("nativeStage")),
                "old in-flight FORMED cannot swallow a later chunk change");
            request(screen);
            SimpleAssert.eq(2, sent.size(), "one follow-up PROBE consumes the later batch");
            reply(screen, connection, pending(screen), Stage.UNFORMED);
            SimpleAssert.that(
                !needed(screen) && "UNFORMED".equals(screen.ui.session.state().data.getString("nativeStage")),
                "only fresh server observation publishes UNFORMED");
            retained(screen, original, renderer, faces, material);
            for (int i = 0; i < 40; i++) request(screen);
            SimpleAssert.eq(2, sent.size(), "idle UI advancement issues zero periodic observations");

            HologramClient.nativeChunkReceived(connection, world, single(x, z, true, otherSection, 0));
            SimpleAssert.that(needed(screen), "full chunk can clear target sections omitted from its primary mask");
            needed(screen, false);
            HologramClient.nativeChunkReceived(connection, world, single(x, z, true, 0, 0));
            SimpleAssert.that(needed(screen), "S21 early-return unload still invalidates the target chunk");
            needed(screen, false);
            HologramClient.nativeChunksReceived(connection, world, bulk(new int[] { x + 100, x }, new int[] { z, z }));
            SimpleAssert.that(
                needed(screen),
                "S26 reaches a later relevant chunk even when its full data omits target sections");
            needed(screen, false);
            BlockChangeNotifier.onBlockChange(world, chunk, row.x, row.y, row.z, Blocks.stone, Blocks.stone, 0, 0);
            SimpleAssert.that(!needed(screen), "StructureLib rejects same block/meta notifications");
            BlockChangeNotifier.onBlockChange(world, chunk, row.x, row.y, row.z, Blocks.stone, Blocks.stone, 0, 1);
            SimpleAssert.that(needed(screen), "native metadata-only block change is relevant");
            needed(screen, false);
            BlockChangeNotifier.onBlockChange(world, chunk, row.x + 1000, row.y, row.z, Blocks.stone, Blocks.air, 0, 0);
            SimpleAssert.that(!needed(screen), "unrelated block changes do not invalidate target state");
            BlockChangeNotifier.onBlockChange(world, chunk, row.x, row.y, row.z, Blocks.stone, Blocks.air, 0, 0);
            SimpleAssert.that(needed(screen), "native ordinary block change shares the same consumer");
            needed(screen, false);
            world.markBlockForUpdate(row.x, row.y, row.z);
            world.func_147479_m(row.x, row.y, row.z);
            world.markBlockRangeForRenderUpdate(row.x, row.y, row.z, row.x, row.y, row.z);
            SimpleAssert.that(!needed(screen), "render/lighting/redundant markBlockForUpdate have no GTMB listener");
            client.currentScreen = null;
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, section, 0));
            BlockChangeNotifier.onBlockChange(world, chunk, row.x, row.y, row.z, Blocks.stone, Blocks.air, 0, 0);
            SimpleAssert.that(!needed(screen), "closed screen is not retained by global notifications");
            client.currentScreen = screen;
            client.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, section, 0));
            SimpleAssert.that(!needed(screen), "old world callbacks cannot update the current screen");
            client.theWorld = world;
            field(EntityClientPlayerMP.class, "sendQueue").set(client.thePlayer, other);
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, section, 0));
            BlockChangeNotifier.onBlockChange(world, chunk, row.x, row.y, row.z, Blocks.stone, Blocks.air, 0, 0);
            SimpleAssert.that(!needed(screen), "old connection also invalidates the ordinary callback context");
            field(EntityClientPlayerMP.class, "sendQueue").set(client.thePlayer, connection);
            var blueprint = new NBTTagCompound();
            blueprint.setBoolean("localStandardController", false);
            field(HologramUiSession.class, "state").set(
                screen.ui.session,
                screen.ui.session.state()
                    .withMetadata(blueprint));
            HologramClient.nativeChunkReceived(connection, world, single(x, z, false, section, 0));
            SimpleAssert.that(!needed(screen), "saved blueprints do not acquire controller Stage observation");
            cancelObservation(client, screen, connection, other, first, sent);
        } finally {
            listeners.remove(listener); // Fixture-only cleanup; production registers once and retains no screen
                                        // reference.
            visible.set(renderer, oldVisible);
            displayed.set(renderer, oldDisplayed);
            textures.snapshot = oldTextureSnapshot;
            if (oldMaterial == null) textures.cells.remove(row);
            else textures.cells.put(row, oldMaterial);
        }
    }

    private static void cancelObservation(Minecraft client, HologramScreen screen, NetHandlerPlayClient connection,
        NetHandlerPlayClient other, HologramStockNativeWire.Confirmation request, List<NBTTagCompound> sent)
        throws ReflectiveOperationException {
        Field pending = field(HologramScreen.class, "nativeObservationRequest");
        var retire = HologramScreen.class.getDeclaredMethod("retireLocalController");
        retire.setAccessible(true);
        field(EntityClientPlayerMP.class, "sendQueue").set(client.thePlayer, connection);
        pending.set(screen, request);
        int before = sent.size();
        retire.invoke(screen);
        SimpleAssert.eq(before + 1, sent.size(), "retiring a local controller cancels its in-flight native PROBE once");
        NBTTagCompound stop = sent.get(before);
        SimpleAssert.that(
            stop.getByte("nativeStockCommand") == HologramStockNativeWire.Command.STOP.ordinal() && request.key()
                .request()
                .toString()
                .equals(stop.getString("nativeStockRequest"))
                && request.key()
                    .sequence() == stop.getLong("nativeStockIntent"),
            "native observation STOP carries the pending PROBE request and intent key");
        SimpleAssert.eq(null, pending.get(screen), "retire clears the pending observation before any later close");
        retire.invoke(screen);
        SimpleAssert.eq(before + 1, sent.size(), "repeated retirement never sends a duplicate observation STOP");

        pending.set(screen, request);
        field(EntityClientPlayerMP.class, "sendQueue").set(client.thePlayer, other);
        try {
            retire.invoke(screen);
            SimpleAssert.eq(null, pending.get(screen), "connection change still clears the abandoned observation");
            SimpleAssert
                .eq(before + 1, sent.size(), "an old observation STOP is never sent to a new server connection");
        } finally {
            field(EntityClientPlayerMP.class, "sendQueue").set(client.thePlayer, connection);
        }
    }

    private static void retained(HologramScreen screen, HologramState original, MiaoVisonLiteRenderer renderer,
        Object faces, HologramTextures.Material[] material) throws ReflectiveOperationException {
        HologramState next = screen.ui.session.state();
        SimpleAssert.that(
            next.cells == original.cells && next.regions == original.regions && next.reusesControllerGeometry(original),
            "notification and Stage metadata retain exact geometry/index identity");
        SimpleAssert.eq(
            original.data.getString("nativeTargetDigest"),
            next.data.getString("nativeTargetDigest"),
            "target digest retained");
        SimpleAssert.eq(
            original.data.getLong("nativeTargetRevision"),
            next.data.getLong("nativeTargetRevision"),
            "target revision retained");
        SimpleAssert.eq(
            original.data.getInteger("mode"),
            next.data.getInteger("mode"),
            "FORMED replacement default to UNFORMED repair default does not change the preview mode");
        SimpleAssert.that(
            field(MiaoVisonLiteRenderer.class, "visibleState").get(renderer) == next
                && field(MiaoVisonLiteRenderer.class, "displayedState").get(renderer) == next,
            "existing renderer presentation advances without republishing geometry");
        SimpleAssert.that(
            field(MiaoVisonLiteRenderer.class, "cachedFaces").get(renderer) == faces
                && renderer.assets().textures.cells.get(original.cells.get(0)) == material,
            "face and material caches are retained, not released or rebuilt");
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        var f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static boolean needed(HologramScreen screen) throws ReflectiveOperationException {
        return field(HologramScreen.class, "nativeObservationNeeded").getBoolean(screen);
    }

    private static void needed(HologramScreen screen, boolean value) throws ReflectiveOperationException {
        field(HologramScreen.class, "nativeObservationNeeded").setBoolean(screen, value);
    }

    private static void request(HologramScreen screen) throws ReflectiveOperationException {
        var m = HologramScreen.class.getDeclaredMethod("requestNativeObservation");
        m.setAccessible(true);
        m.invoke(screen);
    }

    private static HologramStockNativeWire.Confirmation pending(HologramScreen screen)
        throws ReflectiveOperationException {
        return (HologramStockNativeWire.Confirmation) field(HologramScreen.class, "nativeObservationRequest")
            .get(screen);
    }

    private static void reply(HologramScreen screen, NetHandlerPlayClient connection,
        HologramStockNativeWire.Confirmation pending, Stage stage) {
        SimpleAssert.that(pending != null, "actual PROBE registered an in-flight confirmation");
        var observation = new HologramStockNativeWire.ServerObservation(
            pending.projection()
                .controller(),
            pending.x(),
            pending.y(),
            pending.z(),
            stage,
            pending.projection()
                .targetRevision(),
            "");
        screen.receiveNativeObservation(
            new HologramStockNativeWire.Reply(pending.key(), HologramStockNativeWire.Phase.CAPABLE, 0, "", observation),
            connection);
    }

    private static byte[] compress(byte[] raw) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new DeflaterOutputStream(bytes)) {
            stream.write(raw);
        }
        return bytes.toByteArray();
    }

    private static S21PacketChunkData single(int x, int z, boolean full, int mask, int addMask) {
        var buffer = new PacketBuffer(Unpooled.buffer());
        try {
            byte[] zipped = compress(new byte[14336 * Integer.bitCount(mask & 0xffff) + (full ? 256 : 0)]);
            buffer.writeInt(x)
                .writeInt(z)
                .writeBoolean(full)
                .writeShort(mask)
                .writeShort(addMask)
                .writeInt(zipped.length)
                .writeBytes(zipped);
            var packet = new S21PacketChunkData();
            packet.readPacketData(buffer);
            return packet;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        } finally {
            buffer.release();
        }
    }

    private static S26PacketMapChunkBulk bulk(int[] x, int[] z) {
        var buffer = new PacketBuffer(Unpooled.buffer());
        try {
            byte[] zipped = compress(new byte[256 * x.length]);
            buffer.writeShort(x.length)
                .writeInt(zipped.length)
                .writeBoolean(false)
                .writeBytes(zipped);
            for (int i = 0; i < x.length; i++) buffer.writeInt(x[i])
                .writeInt(z[i])
                .writeShort(0)
                .writeShort(0);
            var packet = new S26PacketMapChunkBulk();
            packet.readPacketData(buffer);
            return packet;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        } finally {
            buffer.release();
        }
    }
}

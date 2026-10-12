package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real schema invariants plus native optional API probes; world placement remains covered by the smoke runner. */
final class HologramBlockTransformRegression {

    private HologramBlockTransformRegression() {}

    static void run() {
        try {
            preserved();
            gregtech();
            ae();
            enderIo();
            multipart();
            architecture();
            littleTiles();
            carpenters();
            extensionPriority();
            serviceAtomicFailure();
        } catch (Exception failure) {
            throw new AssertionError("Block transform regression", failure);
        }
    }

    private static void preserved() throws IOException {
        NBTTagCompound tag = payload();
        tag.setShort("mFacing", (short) 2); // A key name alone never identifies GregTech.
        String encoded = HologramBlueprintNbt.encodeCaptured(tag);
        HologramBlockTransforms.Result result = HologramBlockTransforms
            .turn(null, "unrelated.Tile", 11, encoded, encoded, false);
        SimpleAssert.eq(11, result.metadata, "default metadata unchanged");
        SimpleAssert.eq(encoded, result.tileNbt, "unadapted tile payload byte identical");
        SimpleAssert.eq(encoded, result.itemNbt, "item payload byte identical");
    }

    private static void gregtech() {
        for (boolean vertical : new boolean[] { false, true }) for (int side = 0; side < 6; side++) {
            NBTTagCompound tag = payload(), cover = payload();
            tag.setShort("mFacing", (short) side);
            tag.setByte("mConnections", (byte) (128 | 1 << side));
            tag.setByte("mStrongRedstone", (byte) (1 << side));
            byte[] redstone = { 0, 1, 2, 3, 4, 5 };
            tag.setByteArray("mRedstoneSided", redstone);
            NBTTagList covers = new NBTTagList();
            cover.setByte("s", (byte) side);
            covers.appendTag(cover);
            tag.setTag("gt.covers", covers);
            NBTTagCompound original = (NBTTagCompound) tag.copy();
            HologramBlockTransformAdapters.gregtech(tag, vertical);
            int next = HologramVirtualTransform.front(side, vertical);
            SimpleAssert.eq(next, (int) tag.getShort("mFacing"), "GT front");
            SimpleAssert.eq(128 | 1 << next, tag.getByte("mConnections") & 255, "GT mask keeps flag bits");
            SimpleAssert.eq(
                next,
                (int) covers.getCompoundTagAt(0)
                    .getByte("s"),
                "GT cover follows connection");
            SimpleAssert.eq((byte) side, tag.getByteArray("mRedstoneSided")[next], "GT per-side array");
            for (int i = 0; i < 3; i++) HologramBlockTransformAdapters.gregtech(tag, vertical);
            SimpleAssert.eq(original, tag, "four GT turns identity, payload survives");
        }
    }

    private static void ae() throws IOException {
        for (boolean vertical : new boolean[] { false, true }) {
            NBTTagCompound tag = payload();
            tag.setString("orientation_forward", "NORTH");
            tag.setString("orientation_up", "UP");
            for (int side = 0; side < 7; side++) {
                NBTTagCompound part = payload(), extra = payload();
                part.setInteger("fixtureSide", side);
                extra.setByte("spin", (byte) (side % 4));
                tag.setTag("def:" + side, part);
                tag.setTag("extra:" + side, extra);
                if (side < 6) tag.setTag("facade:" + side, part.copy());
            }
            NBTTagCompound original = (NBTTagCompound) tag.copy();
            HologramBlockTransformAdapters.ae(tag, vertical);
            for (int side = 0; side < 6; side++) {
                int next = HologramVirtualTransform.front(side, vertical);
                SimpleAssert.eq(
                    side,
                    tag.getCompoundTag("def:" + next)
                        .getInteger("fixtureSide"),
                    "AE part mount");
                SimpleAssert.eq(
                    side,
                    tag.getCompoundTag("facade:" + next)
                        .getInteger("fixtureSide"),
                    "AE facade mount");
            }
            SimpleAssert
                .eq(original.getCompoundTag("def:6"), tag.getCompoundTag("def:6"), "AE central cable unchanged");
            for (int i = 0; i < 3; i++) HologramBlockTransformAdapters.ae(tag, vertical);
            SimpleAssert.eq(original, tag, "four AE side/basis/roll turns identity");
        }
    }

    private static void enderIo() throws IOException {
        for (boolean vertical : new boolean[] { false, true }) {
            NBTTagCompound tag = payload(), root = new NBTTagCompound(), body = payload();
            NBTTagList list = new NBTTagList();
            root.setString("conduitType", "crazypants.enderio.conduit.item.ItemConduit");
            body.setIntArray("connections", new int[] { 2, 5 });
            body.setIntArray("externalConnections", new int[] { 0, 3 });
            body.setByteArray("conModes", new byte[] { 0, 1, 2, 3, 4, 5 });
            body.setTag("inFilts.NORTH", payload());
            body.setInteger("priority.EAST", 73);
            root.setTag("conduit", body);
            list.appendTag(root);
            tag.setTag("conduits", list);
            NBTTagCompound original = (NBTTagCompound) tag.copy();
            HologramBlockTransformAdapters.enderIo(tag, vertical);
            SimpleAssert
                .eq(HologramVirtualTransform.front(2, vertical), body.getIntArray("connections")[0], "EIO connection");
            for (int i = 0; i < 3; i++) HologramBlockTransformAdapters.enderIo(tag, vertical);
            SimpleAssert.eq(original, tag, "four EIO settings/filter turns identity");
        }
    }

    private static void multipart() throws IOException {
        String[] types = { "mcr_face", "mcr_hllw", "mcr_edge", "mcr_cnr", "mcr_post" };
        int[] counts = { 6, 6, 12, 8, 3 };
        for (boolean vertical : new boolean[] { false, true })
            for (int type = 0; type < types.length; type++) for (int slot = 0; slot < counts[type]; slot++) {
                NBTTagCompound tag = payload(), part = payload();
                NBTTagList parts = new NBTTagList();
                part.setString("id", types[type]);
                part.setString("material", "minecraft:stone");
                part.setByte("shape", (byte) (64 | slot));
                parts.appendTag(part);
                tag.setTag("parts", parts);
                NBTTagCompound original = (NBTTagCompound) tag.copy();
                for (int turn = 0; turn < 4; turn++) HologramBlockTransformAdapters.multipart(tag, vertical);
                SimpleAssert.eq(original, tag, "four native FMP slot turns " + types[type] + "/" + slot);
            }
        NBTTagCompound unknown = new NBTTagCompound(), part = new NBTTagCompound();
        NBTTagList parts = new NBTTagList();
        part.setString("id", "thirdparty:not_a_microblock");
        parts.appendTag(part);
        unknown.setTag("parts", parts);
        try {
            HologramBlockTransformAdapters.multipart(unknown, true);
            throw new AssertionError("Unknown FMP part was silently rotated");
        } catch (IOException expected) {}
        for (boolean vertical : new boolean[] { false, true }) {
            NBTTagCompound bundle = new NBTTagCompound(), cable = payload(), visual = new NBTTagCompound();
            NBTTagList mounted = new NBTTagList();
            cable.setString("id", "ae2_cablebus");
            cable.setTag("def:2", payload());
            visual.setInteger("connections", 64 | 4);
            visual.setInteger("channels", 0x123456);
            cable.setTag("gtmb.aeCableVisual", visual);
            mounted.appendTag(cable);
            bundle.setTag("parts", mounted);
            NBTTagCompound original = (NBTTagCompound) bundle.copy();
            HologramBlockTransformAdapters.multipart(bundle, vertical);
            int next = HologramVirtualTransform.front(2, vertical);
            SimpleAssert.eq(64 | 1 << next, visual.getInteger("connections"), "AE FMP cable visual connection/power");
            SimpleAssert.eq(4, (visual.getInteger("channels") >>> (next * 4)) & 15, "AE FMP cable channel side");
            SimpleAssert.that(cable.hasKey("def:" + next) && !cable.hasKey("shape"), "AE FMP own schema preserved");
            for (int i = 0; i < 3; i++) HologramBlockTransformAdapters.multipart(bundle, vertical);
            SimpleAssert.eq(original, bundle, "four AE FMP cable visual and mount turns identity");
        }
    }

    private static void architecture() throws IOException {
        for (boolean vertical : new boolean[] { false, true })
            for (int side = 0; side < 6; side++) for (int turn = 0; turn < 4; turn++) {
                NBTTagCompound tag = payload();
                tag.setByte("side", (byte) side);
                tag.setByte("turn", (byte) turn);
                tag.setInteger("Disconnected", 17);
                NBTTagCompound original = (NBTTagCompound) tag.copy();
                for (int n = 0; n < 4; n++) HologramBlockTransformAdapters.architecture(tag, vertical);
                SimpleAssert.eq(original, tag, "four Architecture side/roll turns identity");
            }
    }

    private static void littleTiles() throws IOException {
        int[] original = { 1, 2, 3, 5, 8, 12 };
        for (boolean vertical : new boolean[] { false, true }) {
            int[] box = original.clone();
            for (int n = 0; n < 4; n++) box = HologramBlockTransformLittleTiles.rotateBox(box, vertical);
            SimpleAssert.that(Arrays.equals(original, box), "four LT voxel box turns identity");
        }
        SimpleAssert.that(
            Arrays
                .equals(new int[] { 4, 2, 1, 13, 8, 5 }, HologramBlockTransformLittleTiles.rotateBox(original, false)),
            "LT horizontal box coordinates");
        SimpleAssert.that(
            Arrays.equals(new int[] { 1, 4, 2, 5, 13, 8 }, HologramBlockTransformLittleTiles.rotateBox(original, true)),
            "LT vertical box coordinates");
        for (boolean vertical : new boolean[] { false, true }) {
            List<HologramPresets.Cell> cells = new ArrayList<>();
            HologramPresets.Cell main = littleCell(0, true), child = littleCell(1, false);
            cells.add(main);
            cells.add(child);
            NBTTagCompound mainBefore = HologramBlueprintNbt.decode(main.tileNbt);
            NBTTagCompound childBefore = HologramBlueprintNbt.decode(child.tileNbt);
            for (int n = 0; n < 4; n++) {
                Map<String, int[]> refs = HologramBlockTransformLittleTiles.presetReferences(cells, vertical);
                for (HologramPresets.Cell cell : cells) {
                    HologramBlockTransforms.turn(cell, vertical, refs);
                    int[] position = HologramVirtualTransform.turn(cell.x, cell.y, cell.z, vertical);
                    cell.x = position[0];
                    cell.y = position[1];
                    cell.z = position[2];
                }
            }
            SimpleAssert.eq(mainBefore, HologramBlueprintNbt.decode(main.tileNbt), "LT main relationship identity");
            SimpleAssert.eq(childBefore, HologramBlueprintNbt.decode(child.tileNbt), "LT child relationship identity");
            SimpleAssert.eq(1, child.x, "LT neighboring cell coordinate identity");
        }
    }

    private static HologramPresets.Cell littleCell(int x, boolean main) throws IOException {
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.x = x;
        NBTTagCompound tile = payload(), root = new NBTTagCompound();
        root.setString("id", "LittleTilesTileEntity");
        root.setInteger("tilesCount", 1);
        tile.setInteger("bSize", 1);
        tile.setBoolean("isStructure", true);
        tile.setBoolean("main", main);
        tile.setString("tID", "Block");
        String[] fields = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
        int[] box = { 1, 2, 3, 5, 8, 12 };
        for (int i = 0; i < fields.length; i++) tile.setInteger("bBox0" + fields[i], box[i]);
        tile.setInteger("cVecx", 1);
        tile.setInteger("cVecy", 2);
        tile.setInteger("cVecz", 3);
        String prefix = main ? "i0" : "";
        if (main) {
            tile.setString("id", "fixed");
            tile.setInteger("count", 1);
        }
        tile.setInteger(prefix + "coordX", main ? 1 : -1);
        tile.setInteger(prefix + "coordY", 0);
        tile.setInteger(prefix + "coordZ", 0);
        tile.setInteger(prefix + "posx", 1);
        tile.setInteger(prefix + "posy", 2);
        tile.setInteger(prefix + "posz", 3);
        root.setTag("t0", tile);
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(root);
        return cell;
    }

    private static void carpenters() throws IOException {
        for (boolean vertical : new boolean[] { false, true }) for (int shape = 0; shape <= 6; shape++) {
            int next = shape;
            for (int turn = 0; turn < 4; turn++)
                next = HologramBlockTransformCarpenters.metadata("BlockCarpentersBlock", next, vertical);
            SimpleAssert.eq(shape, next, "four Carpenter slab turns identity");
        }
        for (String type : new String[] { "BlockCarpentersBed", "BlockCarpentersDoor", "BlockCarpentersSafe",
            "BlockCarpentersHatch", "BlockCarpentersLadder", "BlockCarpentersLever", "BlockCarpentersGate" }) {
            int value = 0x95, next = value;
            for (int turn = 0; turn < 4; turn++) next = HologramBlockTransformCarpenters.metadata(type, next, false);
            SimpleAssert.eq(value, next, "Carpenter bits survive four horizontal turns: " + type);
        }
    }

    private static void extensionPriority() throws Exception {
        NBTTagCompound tile = payload(), item = payload();
        tile.setString("id", "savedMultipart");
        final NBTTagCompound original = (NBTTagCompound) tile.copy();
        try (AutoCloseable registration = HologramBlockTransformApi.register(new HologramBlockTransformApi.Adapter() {

            public boolean accepts(HologramBlockTransformApi.State state) {
                if (state.tile == null || !"savedMultipart".equals(state.tile.getString("id"))) return false;
                state.tile.setBoolean("discardedAcceptsMutation", true);
                return true;
            }

            public void rotate(HologramBlockTransformApi.State state, boolean vertical) {
                SimpleAssert.that(!state.tile.hasKey("discardedAcceptsMutation"), "accepts state is disposable");
                state.metadata = 9;
                state.tile.setBoolean("integrationHandled", true);
                state.item.setBoolean("integrationItemHandled", true);
            }
        })) {
            HologramBlockTransformApi.RotationResult result = HologramBlockTransformApi
                .rotate(null, 0, tile, item, false, 1, null, 0, 0, 0);
            SimpleAssert.eq(9, result.metadata, "public integration overrides built-in FMP adapter");
            SimpleAssert.that(result.tile.getBoolean("integrationHandled"), "public integration transforms tile");
            SimpleAssert.that(result.item.getBoolean("integrationItemHandled"), "public integration transforms item");
            SimpleAssert.eq(original, tile, "public adapter never aliases source tile");
            SimpleAssert.that(!item.hasKey("integrationItemHandled"), "public adapter never aliases source item");
        }
    }

    private static void serviceAtomicFailure() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.inventory = new InventoryPlayer(player);
        player.worldObj = (net.minecraft.world.World) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        Method sessionFactory = HologramVirtualRotationRegression.class
            .getDeclaredMethod("session", EntityPlayerMP.class);
        sessionFactory.setAccessible(true);
        Object session = sessionFactory.invoke(null, player);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        HologramCapture capture = new HologramCapture(null, new ItemStack(Items.stick));
        for (int i = 0; i < 2; i++) {
            HologramPresets.Cell saved = new HologramPresets.Cell();
            saved.x = i;
            saved.block = "minecraft:stone";
            NBTTagCompound nbt = payload();
            if (i == 0) {
                nbt.setString("id", "BaseMetaTileEntity");
                nbt.setShort("mFacing", (short) 2);
                nbt.setByte("mConnections", (byte) 4);
            } else {
                nbt.setString("id", "savedMultipart");
                NBTTagCompound part = new NBTTagCompound();
                part.setString("id", "thirdparty:unsupported_late_part");
                NBTTagList parts = new NBTTagList();
                parts.appendTag(part);
                nbt.setTag("parts", parts);
            }
            saved.tileNbt = HologramBlueprintNbt.encodeCaptured(nbt);
            preset.cells.add(saved);
            HologramCapture.Cell cell = new HologramCapture.Cell(null, i, 64, 0, new ItemStack(Items.stick));
            cell.block = Blocks.stone;
            cell.tileNbt = saved.tileNbt;
            capture.cells.add(cell);
            capture.positions.put(i + ",64,0", cell);
        }
        set(session, "virtualPreset", preset);
        set(session, "capture", capture);
        ItemStack pinned = new ItemStack(Items.stick);
        pinned.setTagCompound(payload());
        @SuppressWarnings("unchecked")
        Map<Integer, ItemStack> pins = (Map<Integer, ItemStack>) get(session, "pins");
        @SuppressWarnings("unchecked")
        Map<String, ItemStack> coordinatePins = (Map<String, ItemStack>) get(session, "coordinatePins");
        @SuppressWarnings("unchecked")
        Map<String, ItemStack> frozen = (Map<String, ItemStack>) get(session, "frozenAutoTargets");
        @SuppressWarnings("unchecked")
        Map<Integer, ItemStack> automatic = (Map<Integer, ItemStack>) get(session, "autoTargets");
        @SuppressWarnings("unchecked")
        List<ItemStack> targets = (List<ItemStack>) get(session, "planTargets");
        pins.put(0, pinned);
        coordinatePins.put("0,64,0", pinned);
        frozen.put("0,64,0", pinned);
        automatic.put(0, pinned);
        targets.add(pinned);
        String firstPayload = preset.cells.get(0).tileNbt;
        Method transform = HologramService.class.getDeclaredMethod(
            "transformVirtual",
            session.getClass(),
            int.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class);
        transform.setAccessible(true);
        SimpleAssert.eq(
            false,
            transform.invoke(null, session, 0, 64, 0, true, false),
            "late unsupported part rejects whole turn");
        SimpleAssert.that(get(session, "virtualPreset") == preset, "failed turn keeps live preset identity");
        SimpleAssert.that(get(session, "capture") == capture, "failed turn keeps live capture identity");
        SimpleAssert.eq(firstPayload, preset.cells.get(0).tileNbt, "earlier adapted preset cell stays unrotated");
        SimpleAssert.eq(firstPayload, capture.cells.get(0).tileNbt, "earlier adapted capture cell stays unrotated");
        SimpleAssert.eq(1, preset.cells.get(1).x, "late cell coordinate stays unrotated");
        SimpleAssert.that(
            pins.get(0) == pinned && coordinatePins.get("0,64,0") == pinned
                && frozen.get("0,64,0") == pinned
                && automatic.get(0) == pinned
                && targets.get(0) == pinned,
            "late rejection keeps every indexed/coordinate/frozen/planned target unmodified");
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static NBTTagCompound payload() {
        NBTTagCompound tag = new NBTTagCompound(), config = new NBTTagCompound();
        config.setString("custom", "inventory and mod configuration remain intact");
        config.setInteger("count", 43);
        tag.setTag("inventoryFixture", config);
        return tag;
    }
}

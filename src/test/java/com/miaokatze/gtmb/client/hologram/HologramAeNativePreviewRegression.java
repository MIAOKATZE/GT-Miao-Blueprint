package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAeNativePreview;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.AEBaseTile;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;

/** Requires genuine AE registration. No GL context, world placement, network lifecycle or synthetic server tick. */
public final class HologramAeNativePreviewRegression {

    private HologramAeNativePreviewRegression() {}

    public static void run() throws ReflectiveOperationException {
        Block driveBlock = AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get();
        Block interfaceBlock = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        check(
            HologramAeNativePreview.supports(driveBlock) && HologramAeNativePreview.supports(interfaceBlock),
            "registered reviewed AE factories");
        check(!HologramAeNativePreview.supports(Blocks.chest), "unreviewed family stays excluded");
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("ownerUUID", "legitimate-recipe-owner");
        recipe.setInteger("x", 987);
        recipe.setInteger("ic", 123456);
        cell.setTagCompound(recipe);
        NBTTagCompound row = new NBTTagCompound();
        cell.writeToNBT(row);
        NBTTagCompound inv = new NBTTagCompound();
        inv.setTag("item3", row);
        NBTTagCompound canonical = new NBTTagCompound();
        canonical.setString("orientation_forward", "EAST");
        canonical.setString("orientation_up", "NORTH");
        canonical.setByte("paintedColor", (byte) AEColor.Red.ordinal());
        canonical.setTag("inv", inv);
        NBTTagCompound hostileVisual = new NBTTagCompound();
        hostileVisual.setInteger("state", -1);
        hostileVisual.setBoolean("stuck", true);
        canonical.setTag(HologramAeNativePreview.FRAME, hostileVisual);
        NBTTagCompound before = (NBTTagCompound) canonical.copy();
        NBTTagCompound projected = HologramAeNativePreview.target(driveBlock, canonical);
        NBTTagCompound frame = HologramAeNativePreview.frame(projected, "drive");
        check(
            frame.getInteger("state") == 1 << 9 && frame.getInteger("type") == 0,
            "target keeps empty item-cell marker only, no inherited full/active/type flags");
        check(
            projected.func_150296_c()
                .size() == 1 && !frame.hasKey("inv")
                && !frame.hasKey("proxy")
                && !frame.hasKey("tag"),
            "bounded visual frame excludes physical inventory/recipe/grid");
        TileDrive preview = (TileDrive) HologramAeNativeRender.INSTANCE.createTile(driveBlock, 0, null);
        check(
            preview.getProxy()
                .getMachineRepresentation() != null
                && preview.getProxy()
                    .getMachineRepresentation()
                    .getItem() == net.minecraft.item.Item.getItemFromBlock(driveBlock),
            "preview subclass constructor uses original registered Drive item definition");
        HologramAeNativeRender.INSTANCE.loadTile(preview, projected);
        check(
            preview.getForward()
                .name()
                .equals("EAST")
                && preview.getUp()
                    .name()
                    .equals("NORTH")
                && preview.getColor() == AEColor.Red,
            "target visual orientation and paint");
        check(
            preview.getCellStatus(3) == 1 && preview.getCellStatus(2) == 0
                && preview.getCellType(3) == 0
                && !preview.isPowered(),
            "target visual cell position and offline state");
        for (int slot = 0; slot < 10; slot++) check(
            preview.getInternalInventory()
                .getStackInSlot(slot) == null,
            "preview native physical inventory stays empty");
        assertDetached(preview.getProxy());
        check(canonical.equals(before), "target projection leaves entire source recipe/inventory unchanged");
        retainedKeySelectionFrame(driveBlock);

        TileDrive source = new TileDrive();
        set(AEBaseTile.class, source, "forward", net.minecraftforge.common.util.ForgeDirection.WEST);
        set(AEBaseTile.class, source, "up", net.minecraftforge.common.util.ForgeDirection.DOWN);
        set(TileDrive.class, source, "paintedColor", AEColor.Blue);
        int sourceState = 1 << 30 | 4 << 6;
        int sourceType = 1 << 4;
        set(TileDrive.class, source, "state", sourceState);
        set(TileDrive.class, source, "type", sourceType);
        NBTTagCompound savedBefore = new NBTTagCompound();
        source.writeToNBT(savedBefore);
        NBTTagCompound captured = HologramAeNativePreview.capture(source);
        HologramAeNativeRender.INSTANCE.loadTile(preview, captured);
        check(
            preview.getForward()
                .name()
                .equals("WEST")
                && preview.getUp()
                    .name()
                    .equals("DOWN")
                && preview.getColor() == AEColor.Blue,
            "actual visual orientation and paint");
        check(
            preview.getCellStatus(2) == 4 && preview.getCellType(2) == 1 && preview.isPowered(),
            "actual 49-byte frame preserves native rendered state/type");
        NBTTagCompound savedAfter = new NBTTagCompound();
        source.writeToNBT(savedAfter);
        check(savedBefore.equals(savedAfter), "actual visual capture leaves complete native source unchanged");
        assertDetached(source.getProxy());
        assertDetached(preview.getProxy());

        NBTTagCompound interfaceNbt = new NBTTagCompound();
        interfaceNbt.setString("orientation_forward", "UP");
        interfaceNbt.setString("orientation_up", "WEST");
        interfaceNbt.setInteger("pointAt", 5);
        interfaceNbt.setByte("paintedColor", (byte) AEColor.Green.ordinal());
        interfaceNbt.setTag("patterns", row.copy());
        interfaceNbt.setTag("waitingToSend", row.copy());
        TileInterface interfacePreview = (TileInterface) HologramAeNativeRender.INSTANCE
            .createTile(interfaceBlock, 0, null);
        check(
            interfacePreview.getProxy()
                .getMachineRepresentation() != null
                && interfacePreview.getProxy()
                    .getMachineRepresentation()
                    .getItem() == net.minecraft.item.Item.getItemFromBlock(interfaceBlock),
            "preview subclass constructor uses original registered Interface item definition");
        HologramAeNativeRender.INSTANCE
            .loadTile(interfacePreview, HologramAeNativePreview.target(interfaceBlock, interfaceNbt));
        check(
            interfacePreview.getForward()
                .name()
                .equals("UP")
                && interfacePreview.getUp()
                    .name()
                    .equals("WEST")
                && interfacePreview.getColor() == AEColor.Green
                && !interfacePreview.isStuck(),
            "interface native arrows and paint from static schema only");
        for (int slot = 0; slot < 9; slot++) check(
            interfacePreview.getInternalInventory()
                .getStackInSlot(slot) == null,
            "interface preview never ingests storage/patterns/waiting items");
        assertDetached(interfacePreview.getProxy());
        NBTTagCompound malformed = (NBTTagCompound) projected.copy();
        malformed.getCompoundTag(HologramAeNativePreview.FRAME)
            .setString("state", "opaque foreign state");
        boolean rejected = false;
        try {
            HologramAeNativeRender.INSTANCE.loadTile(preview, malformed);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "malformed visual frame rejected without native deserialization");
        cableBusDeclaredTarget();
        cablePreparationCycle();
        System.out.println("AE-NATIVE-PREVIEW visual projection/getters/source-immutable/no-grid PASS");
    }

    private static void cableBusDeclaredTarget() throws ReflectiveOperationException {
        Block cable = null;
        for (Object candidate : Block.blockRegistry)
            if (candidate instanceof Block && candidate.getClass() == appeng.block.networking.BlockCableBus.class)
                cable = (Block) candidate;
        check(cable != null, "registered native CBC fixture");
        NBTTagCompound raw = com.miaokatze.gtmb.hologram.HologramNativeCompatibilityFixture.seed(cable, 0, 64, 0);
        ItemStack main = AEApi.instance()
            .definitions()
            .parts()
            .iface()
            .maybeStack(1)
            .get();
        NBTTagCompound def = new NBTTagCompound(), extra = new NBTTagCompound();
        main.writeToNBT(def);
        ((appeng.api.parts.IPartItem) main.getItem()).createPartFromItemStack(main.copy())
            .writeToNBT(extra);
        raw.setTag("def:2", def);
        raw.setTag("extra:2", extra);
        try {
            com.miaokatze.gtmb.hologram.HologramAeBlueprint.Analysis analysis = com.miaokatze.gtmb.hologram.HologramAeBlueprint
                .analyze(cable, 0, raw);
            check(!analysis.blocking, "client CBC target is an audited canonical part payload");
            NBTTagCompound canonical = analysis.payload(), original = (NBTTagCompound) canonical.copy();
            Class<?> nbt = Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintNbt");
            Method encode = nbt.getDeclaredMethod("encodeCanonical", Block.class, NBTTagCompound.class);
            encode.setAccessible(true);
            Method target = Class.forName("com.miaokatze.gtmb.hologram.HologramDisplaySnapshot")
                .getDeclaredMethod(
                    "target",
                    Block.class,
                    ItemStack.class,
                    int.class,
                    com.gtnewhorizon.structurelib.structure.IStructureElement.class,
                    String.class,
                    boolean.class);
            target.setAccessible(true);
            NBTTagCompound display = (NBTTagCompound) target
                .invoke(null, cable, main, -1, null, encode.invoke(null, cable, canonical), true);
            check(display.hasKey("nativeTile", 10), "genuine ItemMultiPart target has a native CBC display payload");
            net.minecraft.nbt.NBTTagList cells = new net.minecraft.nbt.NBTTagList();
            NBTTagCompound row = new NBTTagCompound(), scene = new NBTTagCompound();
            row.setString("id", Block.blockRegistry.getNameForObject(cable));
            row.setString("wantId", Block.blockRegistry.getNameForObject(cable));
            row.setInteger("y", 64);
            row.setTag("targetDisplay", display);
            cells.appendTag(row);
            scene.setTag("cells", cells);
            HologramBlockAccess access = new HologramBlockAccess(new HologramState(scene));
            net.minecraft.tileentity.TileEntity tile = access.getTileEntity(0, 64, 0);
            check(
                tile instanceof appeng.tile.networking.TileCableBus
                    && tile.getWorldObj() != net.minecraft.client.Minecraft.getMinecraft().theWorld,
                "real client detached CBC factory accepts the declared-host canonical target");
            check(
                canonical.equals(original),
                "actual CBC native client reader leaves complete canonical source unchanged");
        } catch (java.io.IOException invalid) {
            throw new AssertionError("CBC canonical client fixture", invalid);
        }
    }

    /** Real registered AE topology consumes DATA neighbors without imposing a cyclic LOAD dependency. */
    public static void cablePreparationCycle() throws ReflectiveOperationException {
        Block cable = null;
        for (Object candidate : Block.blockRegistry)
            if (candidate instanceof Block && candidate.getClass() == appeng.block.networking.BlockCableBus.class)
                cable = (Block) candidate;
        check(cable != null, "registered cable pair preparation fixture");
        NBTTagCompound payload = com.miaokatze.gtmb.hologram.HologramNativeCompatibilityFixture.seed(cable, 0, 64, 0);
        payload.removeTag("gtmb.aeCableVisual"); // Exercise actual reviewed topology inference, not captured mask
                                                 // replay.
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        for (int x = 0; x < 2; x++) {
            NBTTagCompound row = new NBTTagCompound(), display = new NBTTagCompound();
            row.setString("id", Block.blockRegistry.getNameForObject(cable));
            row.setString("wantId", Block.blockRegistry.getNameForObject(cable));
            row.setInteger("x", x);
            row.setInteger("y", 64);
            display.setTag("nativeTile", payload.copy());
            row.setTag("targetDisplay", display);
            rows.appendTag(row);
        }
        NBTTagCompound scene = new NBTTagCompound();
        scene.setTag("cells", rows);
        HologramState state = new HologramState(scene);
        HologramBlockAccess access = new HologramBlockAccess(state);
        try {
            check(
                access.prepare(state.cells.get(0), new HologramNativePreparation.Budget(1, 32, () -> 0L))
                    == HologramNativePreparation.Outcome.READY,
                "unprimed cable neighbor supplies decoded DATA without a false cycle");
            check(
                access.outcome(state.cells.get(1)) == HologramNativePreparation.Outcome.WAITING,
                "neighbor DATA is not falsely marked topology complete");
            check(
                access.prepare(state.cells.get(1), new HologramNativePreparation.Budget(1, 32, () -> 0L))
                    == HologramNativePreparation.Outcome.READY,
                "opposite topology completes against first cable DATA");
            appeng.tile.networking.TileCableBus left = (appeng.tile.networking.TileCableBus) access
                .getTileEntity(0, 64, 0);
            appeng.tile.networking.TileCableBus right = (appeng.tile.networking.TileCableBus) access
                .getTileEntity(1, 64, 0);
            int east = 1 << net.minecraftforge.common.util.ForgeDirection.EAST.ordinal();
            int west = 1 << net.minecraftforge.common.util.ForgeDirection.WEST.ordinal();
            check(
                HologramAeNativePreview.cableVisual(left.getCableBus())
                    .getInteger("connections") == east,
                "real left cable infers only the east connection");
            check(
                HologramAeNativePreview.cableVisual(right.getCableBus())
                    .getInteger("connections") == west,
                "real right cable infers only the west connection");
            for (appeng.tile.networking.TileCableBus tile : new appeng.tile.networking.TileCableBus[] { left, right })
                assertDetached(
                    (AENetworkProxy) get(
                        appeng.parts.AEBasePart.class,
                        tile.getCableBus()
                            .getPart(net.minecraftforge.common.util.ForgeDirection.UNKNOWN),
                        "proxy"));
        } finally {
            access.close();
        }
    }

    /** Genuine registered CBC GL regression with the real world's alpha-pass helper state deliberately preinstalled. */
    public static void cableRenderPass() throws ReflectiveOperationException {
        Block cable = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeBlock()
            .get();
        NBTTagCompound raw = com.miaokatze.gtmb.hologram.HologramNativeCompatibilityFixture.seed(cable, 0, 64, 0);
        ItemStack iface = AEApi.instance()
            .definitions()
            .parts()
            .iface()
            .maybeStack(1)
            .get();
        NBTTagCompound def = new NBTTagCompound(), extra = new NBTTagCompound();
        iface.writeToNBT(def);
        ((appeng.api.parts.IPartItem) iface.getItem()).createPartFromItemStack(iface.copy())
            .writeToNBT(extra);
        raw.setTag("def:2", def);
        raw.setTag("extra:2", extra);
        try {
            NBTTagCompound canonical = com.miaokatze.gtmb.hologram.HologramAeBlueprint.analyze(cable, 0, raw)
                .payload();
            NBTTagCompound before = (NBTTagCompound) canonical.copy();
            check(
                !canonical.hasKey("gtmb.aeCableVisual"),
                "CBC pass regression never restores live visual/network flags");
            NBTTagCompound display = new NBTTagCompound(), row = new NBTTagCompound(), scene = new NBTTagCompound();
            display.setTag("nativeTile", canonical);
            row.setString("id", Block.blockRegistry.getNameForObject(cable));
            row.setString("wantId", Block.blockRegistry.getNameForObject(cable));
            row.setInteger("y", 64);
            row.setTag("targetDisplay", display);
            net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
            rows.appendTag(row);
            scene.setTag("cells", rows);
            HologramState state = new HologramState(scene);
            HologramBlockAccess nativeAccess = new HologramBlockAccess(state);
            appeng.tile.networking.TileCableBus tile = (appeng.tile.networking.TileCableBus) nativeAccess
                .getTileEntity(0, 64, 0);
            check(
                tile != null && tile.getCableBus()
                    .getPart(net.minecraftforge.common.util.ForgeDirection.NORTH) != null
                    && tile.getCableBus()
                        .getPart(net.minecraftforge.common.util.ForgeDirection.UNKNOWN) != null,
                "canonical CBC native loader retains actual interface and cable parts before GL");
            check(
                tile.getClass() == HologramAeNativeRender.registeredCableFactory()
                    && appeng.block.networking.BlockCableBus.class
                        .getDeclaredMethod(
                            "getTileEntity",
                            net.minecraft.world.IBlockAccess.class,
                            int.class,
                            int.class,
                            int.class)
                        .invoke(cable, nativeAccess, 0, 64, 0) == tile,
                "registered CBC renderer accepts its exact combined native visual host before GL");
            appeng.client.render.BusRenderHelper realHelper = appeng.client.render.BusRenderHelper.instances.get();
            appeng.client.render.BusRenderHelper sentinel = new appeng.client.render.BusRenderHelper();
            sentinel.setPass(1);
            appeng.client.render.BusRenderHelper.instances.set(sentinel);
            appeng.client.render.BusRenderer bus = appeng.client.render.BusRenderer.INSTANCE;
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> renderers = (ThreadLocal<Object>) get(
                appeng.client.render.BusRenderer.class,
                bus,
                "renderer");
            Object rendererBefore = renderers.get();
            appeng.client.render.BlockRenderInfo info = ((appeng.block.AEBaseBlock) cable).getRendererInstance();
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> icons = (ThreadLocal<Object>) get(
                appeng.client.render.BlockRenderInfo.class,
                info,
                "threadState");
            Object iconsBefore = icons.get();
            int colorBefore = cable.colorMultiplier(null, 0, 0, 0);
            try {
                HologramBlockRender.begin(state);
                HologramState.Cell cell = state.cells.get(0);
                check(
                    HologramBlockRender.ready(cell, false),
                    "actual CBC emits native GL geometry despite preexisting alpha pass: "
                        + HologramBlockRender.failure(cell, false));
                check(
                    HologramBlockRender.vertexCount(cell, false) > 0 && !HologramBlockRender.attributes(cell, false)
                        .isEmpty() && HologramBlockRender.usesIcon(cell, false, iface.getIconIndex()),
                    "CBC opaque interface native atlas quads are actually emitted with alpha-pass sentinel");
                check(
                    appeng.client.render.BusRenderHelper.instances.get() == sentinel
                        && ((Integer) get(appeng.client.render.BusRenderHelper.class, sentinel, "currentPass")) == 1
                        && renderers.get() == rendererBefore
                        && icons.get() == iconsBefore
                        && cable.colorMultiplier(null, 0, 0, 0) == colorBefore,
                    "CBC native compile restores exact helper/renderer/icons objects, pass and paint");
                boolean failed = false;
                try (HologramAeNativeRender.CableContext context = HologramAeNativeRender.cableContext(cable, tile)) {
                    check(context != null, "reviewed CBC has scoped native context");
                    cable.canRenderInPass(0);
                    throw new IllegalStateException("expected native render failure fixture");
                } catch (IllegalStateException expected) {
                    failed = true;
                }
                check(
                    failed && appeng.client.render.BusRenderHelper.instances.get() == sentinel
                        && renderers.get() == rendererBefore
                        && icons.get() == iconsBefore,
                    "CBC exceptional render exit restores all old native thread objects");
                check(canonical.equals(before), "native CBC pass lifecycle leaves complete canonical source unchanged");
            } finally {
                appeng.client.render.BusRenderHelper.instances.set(realHelper);
            }
            System.out.println("AE-NATIVE-PREVIEW real CBC GL alpha-pass/source-parts/context-restoration PASS");
            try {
                HologramAeCablePreviewRegression.run();
            } catch (ReflectiveOperationException reflectionFailure) {
                throw reflectionFailure;
            } catch (Exception matrixFailure) {
                throw new AssertionError("AE native cable/part matrix", matrixFailure);
            }
        } catch (java.io.IOException invalid) {
            throw new AssertionError("CBC native pass fixture", invalid);
        }
    }

    /**
     * Real Service option selection and real native frame/getters; Session is an option-only constructor-free fixture.
     */
    private static void retainedKeySelectionFrame(Block driveBlock) throws ReflectiveOperationException {
        ItemStack emptyCell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        NBTTagCompound emptyCellBefore = emptyCell.writeToNBT(new NBTTagCompound());
        NBTTagCompound inventory = new NBTTagCompound();
        inventory.setTag("item3", emptyCellBefore.copy());
        NBTTagCompound draft = new NBTTagCompound();
        draft.setTag("inv", inventory);
        draft.setString("orientation_forward", "EAST");
        draft.setString("orientation_up", "NORTH");
        draft.setByte("paintedColor", (byte) AEColor.Red.ordinal());
        NBTTagCompound draftBefore = (NBTTagCompound) draft.copy();

        Class<?> nbtClass = Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintNbt");
        Method encode = nbtClass.getDeclaredMethod("encodeCanonical", Block.class, NBTTagCompound.class);
        Method decode = nbtClass.getDeclaredMethod("decode", String.class);
        Method filter = nbtClass
            .getDeclaredMethod("filterCanonical", Block.class, String.class, List.class, boolean.class);
        Method placement = nbtClass.getDeclaredMethod(
            "forPlacement",
            Block.class,
            int.class,
            String.class,
            UUID.class,
            String.class,
            int.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class,
            List.class);
        for (Method method : Arrays.asList(encode, decode, filter, placement)) method.setAccessible(true);
        String canonical = (String) encode.invoke(null, driveBlock, draft);
        NBTTagCompound canonicalBefore = (NBTTagCompound) decode.invoke(null, canonical);
        canonicalBefore = (NBTTagCompound) canonicalBefore.copy();

        Class<?> cellPolicyClass = Class.forName("com.miaokatze.gtmb.hologram.HologramCellPolicy");
        Class<?> sessionClass = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
        Class<?> presetClass = Class.forName("com.miaokatze.gtmb.hologram.HologramPresets$Preset");
        Class<?> cellClass = Class.forName("com.miaokatze.gtmb.hologram.HologramCapture$Cell");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Object session = unsafe.allocateInstance(sessionClass);
        Constructor<?> presetConstructor = presetClass.getDeclaredConstructor();
        presetConstructor.setAccessible(true);
        Object preset = presetConstructor.newInstance();
        Constructor<?> cellConstructor = cellClass.getDeclaredConstructor(
            com.gtnewhorizon.structurelib.structure.IStructureElement.class,
            int.class,
            int.class,
            int.class);
        cellConstructor.setAccessible(true);
        Object cell = cellConstructor.newInstance(null, 41, 72, 3);
        set(cellClass, cell, "block", driveBlock);
        set(cellClass, cell, "meta", 0);
        set(cellClass, cell, "tileNbt", canonical);
        set(presetClass, preset, "schema", 2);
        set(presetClass, preset, "retainNbt", true);
        List<String> keptKeys = Arrays.asList("inv", "orientation_forward", "orientation_up", "paintedColor");
        set(presetClass, preset, "retainedNbtKeys", new ArrayList<>(keptKeys));
        set(sessionClass, session, "virtual", true);
        set(sessionClass, session, "virtualPreset", preset);
        set(sessionClass, session, "placementNbtCustomized", true);
        set(sessionClass, session, "placementRetainCovers", Boolean.FALSE);
        Method serviceSelection = cellPolicyClass.getDeclaredMethod("targetPreviewTile", sessionClass, cellClass);
        serviceSelection.setAccessible(true);

        for (List<String> selected : Arrays.asList(keptKeys, Collections.<String>emptyList())) {
            List<String> optionKeys = new ArrayList<>(selected);
            set(sessionClass, session, "placementNbtKeys", optionKeys);
            String actualSelected = (String) serviceSelection.invoke(null, session, cell);
            String expectedSelected = (String) filter.invoke(null, driveBlock, canonical, selected, false);
            check(
                decode.invoke(null, actualSelected)
                    .equals(decode.invoke(null, expectedSelected)),
                "actual Service schema-2 placement options select the expected ephemeral payload");
            NBTTagCompound expectedPlacement = (NBTTagCompound) placement
                .invoke(null, driveBlock, 0, canonical, null, "", 41, 72, 3, true, false, selected);
            NBTTagCompound actualFrame = HologramAeNativePreview
                .target(driveBlock, (NBTTagCompound) decode.invoke(null, actualSelected));
            NBTTagCompound expectedFrame = HologramAeNativePreview.target(driveBlock, expectedPlacement);
            check(
                actualFrame.equals(expectedFrame),
                "kept/omitted inv, forward, up and paint native frame equals actual block-aware placement projection");
            TileDrive selectedPreview = (TileDrive) HologramAeNativeRender.INSTANCE.createTile(driveBlock, 0, null);
            HologramAeNativeRender.INSTANCE.loadTile(selectedPreview, actualFrame);
            boolean keep = !selected.isEmpty();
            check(
                selectedPreview.getCellStatus(3) == (keep ? 1 : 0) && selectedPreview.getForward()
                    .name()
                    .equals(keep ? "EAST" : "SOUTH")
                    && selectedPreview.getUp()
                        .name()
                        .equals(keep ? "NORTH" : "UP")
                    && selectedPreview.getColor() == (keep ? AEColor.Red : AEColor.Transparent),
                "real selected Drive getters respect omitted inventory/orientation/paint rather than unfiltered draft");
            assertDetached(selectedPreview.getProxy());
            check(
                canonical.equals(get(cellClass, cell, "tileNbt")) && optionKeys.equals(selected)
                    && canonicalBefore.equals(decode.invoke(null, canonical))
                    && draftBefore.equals(draft)
                    && emptyCellBefore.equals(emptyCell.writeToNBT(new NBTTagCompound())),
                "ephemeral Service/native frame selection leaves source cell, draft and retained options immutable");
        }
    }

    private static void assertDetached(AENetworkProxy proxy) throws ReflectiveOperationException {
        check(
            !proxy.isReady() && get(AENetworkProxy.class, proxy, "node") == null
                && get(AENetworkProxy.class, proxy, "data") == null,
            "preview/capture never schedules readiness, creates node or loads grid identity");
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
        throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError("AE preview: " + message);
    }
}

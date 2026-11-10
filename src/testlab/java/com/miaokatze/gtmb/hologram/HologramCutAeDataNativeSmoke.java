package com.miaokatze.gtmb.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.util.ProbeChunkTickets;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.core.worlddata.WorldData;
import appeng.helpers.DualityInterface;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.misc.PartInterface;
import appeng.tile.crafting.TileMolecularAssembler;
import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileCableBus;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;

/** Explicitly opted-in real AE/GT roundtrip; its oracle is the source, never the post-load snapshot. */
public final class HologramCutAeDataNativeSmoke {

    private static final UUID OWNER = UUID.fromString("00bcf030-4cc8-4c41-ad82-f66038010d08");
    private static final int SOURCE_X = 900, TARGET_X = 932, Y = 200, Z = 900;
    private static final int[] OFFSETS = { 0, 2, 4, 6, 8 };
    private static final String PREFIX = "[GTMB-CUT-AE-DATA] ";
    private static final String ORACLE = "gtmb-ae-cut-data-oracle.nbt";
    private static final String[] COMMON = { "id", "customName", "orientation_forward", "orientation_up" };
    private static final String[] INTERFACE = { "config", "patterns", "storage", "upgrades", "priority", "BLOCK",
        "SMART_BLOCK", "INTERFACE_TERMINAL", "INSERTION_MODE", "ADVANCED_BLOCKING_MODE", "LOCK_CRAFTING_MODE",
        "PATTERN_OPTIMIZATION", "FUZZY_MODE", "waitingToSend", "pointAt", "paintedColor" };
    private static boolean installed;
    private int ticks;
    private int due = -1;
    private boolean done;
    private NBTTagCompound oracle;
    private ForgeChunkManager.Ticket chunkTicket;
    private final Map<ChunkCoordIntPair, Chunk> nativeChunks = new LinkedHashMap<>();
    private final Map<Chunk, ChunkCoordIntPair> chunkIdentities = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    static synchronized void install() {
        if (installed || !Boolean.getBoolean("gtmb.hologram.cutAeDataNativeSmoke")) return;
        installed = true;
        // Only the explicit native reader-order negative control uses this recipe in the isolated fixture.
        CraftingManager.getInstance()
            .getRecipeList()
            .add(0, new WorldRecipe());
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramCutAeDataNativeSmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || ++ticks < 60) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            WorldServer world = server.worldServerForDimension(0);
            Path root = HologramCutService.saveRoot();
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("gtmb-ae-cut-data-"),
                "dedicated fixture world name");
            require(Files.isRegularFile(root.resolve("gtmb-ae-cut-data.fixture")), "explicit owned fixture marker");
            if (chunkTicket == null) acquireChunks(world);
            else retainChunks(world);
            String phase = System.getProperty("gtmb.hologram.cutAeDataPhase", "prepare");
            require("prepare".equals(phase) || "restart".equals(phase), "explicit prepare/restart phase");
            if ("restart".equals(phase)) {
                if (due < 0) {
                    oracle = HologramSnapshotCodec.decode(Files.readAllBytes(root.resolve(ORACLE)));
                    require(
                        oracle.getBoolean("naturalTicksPassed"),
                        "prepare reached actual natural tick verification");
                    // The far chunks were loaded during this callback. Preserve and verify their original region
                    // bytes before native GT's first tick; do not manufacture readiness in the observer.
                    verifyDisk(world, "restart-before-native-ticks-disk");
                    for (int dx : OFFSETS) {
                        TileEntity tile = world.getTileEntity(TARGET_X + dx, Y, Z);
                        oracle.setTag("restart-loaded-live" + dx, snapshot(tile));
                        if (tile instanceof IGridProxyable) oracle.setBoolean(
                            "restart-loaded-proxy-ready" + dx,
                            ((IGridProxyable) tile).getProxy()
                                .isReady());
                    }
                    oracle.setLong("restartLoadedWorldTick", world.getTotalWorldTime());
                    due = ticks + 30;
                    receipt(root, "restart-loaded", oracle);
                    System.out
                        .println(PREFIX + "CORE PASS phase=restart originalDiskVerified=true waitingForNativeTicks=30");
                    return;
                }
                if (ticks < due) return;
                oracle.setLong("restartVerifiedWorldTick", world.getTotalWorldTime());
                require(
                    oracle.getLong("restartVerifiedWorldTick") - oracle.getLong("restartLoadedWorldTick") >= 30,
                    "restart actually advances thirty native world ticks after loading fixture chunks");
                verify(world, "restart-live");
                verifyDisk(world, "restart-disk");
                receipt(root, "restart", oracle);
                finish(server, "FINAL PASS phase=restart sourceOracle=true actualNativeReload=true");
            } else if (due < 0) {
                require(!Files.exists(root.resolve(ORACLE)), "fresh fixture oracle");
                prepare(world, root);
                due = ticks + 30;
            } else if (ticks >= due) {
                verify(world, "natural-ticks");
                // Keep a receipt of the actual before-tick commit disk, then independently save the later live state.
                HologramCutWorld.saveAndVerify(world, oracle.getCompoundTag("entry"), TARGET_X, Y, Z, false);
                verifyDisk(world, "natural-ticks-disk");
                oracle.setBoolean("naturalTicksPassed", true);
                Files.write(root.resolve(ORACLE), HologramSnapshotCodec.compress(oracle));
                receipt(root, "prepare", oracle);
                finish(server, "FINAL PASS phase=prepare immediateDisk=true naturalTicks=true sourceOracle=true");
            }
        } catch (Throwable failure) {
            if (done) return;
            done = true;
            System.out.println(PREFIX + "FINAL FAIL " + failure);
            failure.printStackTrace();
            try {
                releaseChunks();
            } catch (Throwable cleanup) {
                cleanup.printStackTrace();
            } finally {
                server.initiateShutdown();
            }
        }
    }

    private void prepare(WorldServer world, Path root) throws Exception {
        oracle = new NBTTagCompound();
        recordChunks(world, "prepare-before-fixture");
        // Diagnose independent world-state restoration before any AE/GT fixture can fail to initialize.
        HologramCutLightingNativeSmoke.prepare(world, oracle);
        EntityPlayerMP actor = FakePlayerFactory.get(world, new GameProfile(OWNER, "GTMB_AE_Data"));
        actor.setPosition(SOURCE_X, Y, Z);
        WorldData.instance()
            .playerData()
            .getPlayerID(new GameProfile(UUID.fromString("00bcf030-4cc8-4c41-ad82-f66038010d09"), "GTMB_AE_Reserved"));
        int owner = WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
        require(owner > 0, "nonzero real registered AE owner");
        for (int x = SOURCE_X; x <= TARGET_X + 8; x++) require(world.isAirBlock(x, Y, Z), "fresh fixture region");
        ItemStack processing = pattern(false), crafting = pattern(true);
        require(
            ((ICraftingPatternItem) processing.getItem()).getPatternForItem(processing, world) != null,
            "real processing pattern valid");
        require(
            ((ICraftingPatternItem) crafting.getItem()).getPatternForItem(crafting, world) != null,
            "real vanilla crafting pattern valid");
        NBTTagCompound readerOrder = verifyWorldReader(world);

        BaseMetaTileEntity gt = gt(world, processing, crafting);
        TileInterface iface = (TileInterface) place(world, SOURCE_X + 2, find("appeng.block.misc.BlockInterface"));
        configure(iface.getInterfaceDuality(), processing, crafting);
        iface.setCustomName("GTMB native interface config");
        iface.setSide(ForgeDirection.EAST);
        TileCableBus bus = (TileCableBus) place(world, SOURCE_X + 4, find("appeng.block.networking.BlockCableBus"));
        require(
            bus.addPart(
                AEApi.instance()
                    .definitions()
                    .parts()
                    .iface()
                    .maybeStack(1)
                    .get(),
                ForgeDirection.NORTH,
                actor) != null,
            "real part interface added");
        PartInterface part = (PartInterface) bus.getPart(ForgeDirection.NORTH);
        configure(part.getInterfaceDuality(), processing, crafting);
        TileMolecularAssembler assembler = (TileMolecularAssembler) place(
            world,
            SOURCE_X + 6,
            find("appeng.block.crafting.BlockMolecularAssembler"));
        assembler.getInventoryByName("upgrades")
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .materials()
                    .cardSpeed()
                    .maybeStack(1)
                    .get());
        assembler.getInternalInventory()
            .setInventorySlotContents(10, crafting.copy());
        assembler.setCustomName("GTMB native assembler pattern");
        TileMolecularAssembler forced = (TileMolecularAssembler) place(
            world,
            SOURCE_X + 8,
            find("appeng.block.crafting.BlockMolecularAssembler"));
        InventoryCrafting table = new InventoryCrafting(new appeng.container.ContainerNull(), 3, 3);
        table.setInventorySlotContents(0, tagged(Item.getItemFromBlock(Blocks.log), 1, "crafting-input"));
        require(
            forced.pushPattern(
                ((ICraftingPatternItem) crafting.getItem()).getPatternForItem(crafting.copy(), world),
                table,
                ForgeDirection.EAST),
            "actual assembler accepts running forced plan");
        for (int dx : OFFSETS) {
            TileEntity tile = world.getTileEntity(SOURCE_X + dx, Y, Z);
            HologramCutTiles.ready(tile);
            if (tile instanceof IGridProxyable) ((IGridProxyable) tile).getProxy()
                .getNode()
                .setPlayerID(owner);
        }
        part.getProxy()
            .getNode()
            .setPlayerID(owner);
        require(gt.getMetaTileEntity() instanceof MTEHatchCraftingInputME, "real GT sample buffer class");
        oracle.setInteger("owner", owner);
        oracle.setTag("worldBeforeReadNativeControl", readerOrder);
        oracle.setTag("fixtureProcessingInput", processing.writeToNBT(new NBTTagCompound()));
        oracle.setTag("fixtureCraftingInput", crafting.writeToNBT(new NBTTagCompound()));
        for (int dx : OFFSETS) oracle.setTag("source" + dx, snapshot(world.getTileEntity(SOURCE_X + dx, Y, Z)));
        Files.write(root.resolve("gtmb-ae-cut-data-fixture-input.nbt"), HologramSnapshotCodec.compress(oracle));
        verifyFixtureInputs(processing, crafting);
        // Source must actually contain the seeded nested payloads; a degenerate empty fixture cannot pass.
        require(
            oracle.getCompoundTag("source0")
                .getTagList("internalInventory", 10)
                .tagCount() == 2,
            "two actual GT pattern buffers");
        require(
            oracle.getCompoundTag("source2")
                .getCompoundTag("patterns")
                .hasKey("#35", 10),
            "actual highest interface pattern slot");
        require(
            oracle.getCompoundTag("source6")
                .getCompoundTag("inv")
                .hasKey("item10", 10),
            "actual assembler physical pattern");
        require(
            oracle.getCompoundTag("source8")
                .hasKey("myPlan", 10),
            "actual running assembler plan persisted");
        NBTTagCompound cut = request("cut");
        cut.setInteger("x1", SOURCE_X);
        cut.setInteger("x2", SOURCE_X + 8);
        cut.setInteger("y1", Y);
        cut.setInteger("y2", Y);
        cut.setInteger("z1", Z);
        cut.setInteger("z2", Z);
        NBTTagCompound captured = HologramCutService.execute(actor, cut);
        success(captured, "real cut");
        for (int dx : OFFSETS) require(
            oracle.getCompoundTag("source" + dx)
                .equals(HologramCutNativeReceipt.storedTile(actor, captured.getString("id"), dx)),
            "source raw tile retained " + dx);
        NBTTagCompound entry = new HologramCutStorage(root, OWNER).load()
            .getTagList("entries", 10)
            .getCompoundTagAt(0);
        oracle.setTag("entry", entry.copy());
        NBTTagCompound paste = request("paste");
        paste.setString("id", captured.getString("id"));
        paste.setInteger("originX", TARGET_X);
        paste.setInteger("originY", Y);
        paste.setInteger("originZ", Z);
        success(HologramCutService.execute(actor, paste), "real paste");
        verify(world, "immediate-live");
        verifyDisk(world, "immediate-disk");
        Files.write(root.resolve(ORACLE), HologramSnapshotCodec.compress(oracle));
        System.out
            .println(PREFIX + "CORE PASS gtBuffer=true interface=true part=true assembler=true nonzeroOwner=true");
    }

    private static BaseMetaTileEntity gt(WorldServer world, ItemStack processing, ItemStack crafting) {
        int id = ItemList.Hatch_CraftingInput_Bus_ME.get(1)
            .getItemDamage();
        require(
            id > 0 && id < GregTechAPI.METATILEENTITIES.length
                && GregTechAPI.METATILEENTITIES[id] instanceof MTEHatchCraftingInputME,
            "registered native GT pattern buffer prototype");
        int metadata = GregTechAPI.METATILEENTITIES[id].getTileEntityBaseType();
        BaseMetaTileEntity base = (BaseMetaTileEntity) place(world, SOURCE_X, GregTechAPI.sBlockMachines, metadata);
        base.setInitialValuesAsNBT(null, (short) id);
        require(
            base.getMetaTileEntity() instanceof MTEHatchCraftingInputME && base.getMetaTileID() == id,
            "world contains the exact registered real GT pattern buffer");
        MTEHatchCraftingInputME hatch = (MTEHatchCraftingInputME) base.getMetaTileEntity();
        hatch.setInventorySlotContents(0, processing.copy());
        hatch.setInventorySlotContents(35, crafting.copy());
        hatch.setInventorySlotContents(36, gregtech.api.util.GTUtility.getIntegratedCircuit(24));
        hatch.setInventorySlotContents(37, tagged(Items.diamond, 17, "manual-slot"));
        hatch.setCustomName("GTMB native GT pattern buffer");
        hatch.setConnectsToAllSides(true);
        hatch.disablePatternOptimization = true;
        hatch.showPattern = false;
        NBTTagCompound seed = snapshot(base);
        NBTTagList buffers = new NBTTagList();
        buffers.appendTag(buffer(0, processing));
        buffers.appendTag(buffer(35, crafting));
        seed.setTag("internalInventory", buffers);
        base.readFromNBT(seed);
        return base;
    }

    private static NBTTagCompound buffer(int slot, ItemStack pattern) {
        NBTTagCompound row = new NBTTagCompound(), payload = new NBTTagCompound();
        row.setInteger("patternSlot", slot);
        payload.setTag("pattern", pattern.writeToNBT(new NBTTagCompound()));
        NBTTagList items = new NBTTagList();
        items.appendTag(gregtech.api.util.GTUtility.saveItem(tagged(Items.emerald, 513 + slot, "buffer-" + slot)));
        payload.setTag("inventory", items);
        FluidStack fluid = new FluidStack(FluidRegistry.WATER, 32000 + slot);
        fluid.tag = new NBTTagCompound();
        fluid.tag.setString("nestedFluidWitness", "fluid-" + slot);
        NBTTagList fluids = new NBTTagList();
        fluids.appendTag(fluid.writeToNBT(new NBTTagCompound()));
        payload.setTag("fluidInventory", fluids);
        row.setTag("patternSlotNBT", payload);
        return row;
    }

    private static NBTTagCompound verifyWorldReader(WorldServer world) throws Exception {
        ItemStack sensitive = pattern(true);
        NBTTagList in = new NBTTagList(), out = new NBTTagList();
        in.appendTag(tagged(Items.diamond, 1, "world-recipe").writeToNBT(new NBTTagCompound()));
        out.appendTag(tagged(Items.emerald, 1, "encoded-output").writeToNBT(new NBTTagCompound()));
        sensitive.getTagCompound()
            .setTag("in", in);
        sensitive.getTagCompound()
            .setTag("out", out);
        ICraftingPatternItem nativeItem = (ICraftingPatternItem) sensitive.getItem();
        require(nativeItem.getPatternForItem(sensitive.copy(), world) != null, "real world-dependent AE pattern valid");
        require(nativeItem.getPatternForItem(sensitive.copy(), null) == null, "null-world recipe negative control");
        Block block = find("appeng.block.crafting.BlockMolecularAssembler");
        NBTTagCompound proof = new NBTTagCompound();
        for (boolean forced : new boolean[] { false, true }) {
            TileMolecularAssembler source = (TileMolecularAssembler) block.createTileEntity(world, 0);
            source.setWorldObj(world);
            source.xCoord = 960;
            source.yCoord = Y;
            source.zCoord = Z;
            if (forced) {
                InventoryCrafting table = new InventoryCrafting(new appeng.container.ContainerNull(), 3, 3);
                table.setInventorySlotContents(0, tagged(Items.diamond, 1, "world-recipe"));
                require(
                    source
                        .pushPattern(nativeItem.getPatternForItem(sensitive.copy(), world), table, ForgeDirection.EAST),
                    "real transient forced-plan input");
            } else source.getInternalInventory()
                .setInventorySlotContents(10, sensitive.copy());
            NBTTagCompound input = snapshot(source);
            TileEntity old = TileEntity.createAndLoadEntity((NBTTagCompound) input.copy());
            require(old != null, "native legacy negative control tile");
            NBTTagCompound before = snapshot(old);
            NBTTagCompound after = snapshot(HologramCutTiles.create(world, block, 0, input));
            String key = forced ? "myPlan" : "inv";
            require(
                !HologramCutWorld.sameNbt(input.getTag(key), before.getTag(key)),
                "legacy real AE reader exposes missing-world data change " + key);
            require(
                HologramCutWorld.sameNbt(input.getTag(key), after.getTag(key)),
                "real AE reader preserves full native data with world-before-read " + key);
            proof.setTag("input-" + key, input);
            proof.setTag("legacy-" + key, before);
            proof.setTag("restored-" + key, after);
        }
        proof.setBoolean("passed", true);
        return proof;
    }

    private static void configure(DualityInterface duality, ItemStack processing, ItemStack crafting) {
        ItemStack card = AEApi.instance()
            .definitions()
            .materials()
            .cardPatternCapacity()
            .maybeStack(1)
            .get();
        card.stackSize = 3;
        duality.getUpgrades()
            .setInventorySlotContents(0, card);
        duality.getPatterns()
            .setInventorySlotContents(0, processing.copy());
        duality.getPatterns()
            .setInventorySlotContents(35, crafting.copy());
        duality.getStorage()
            .setInventorySlotContents(4, tagged(Items.diamond, 23, "interface-storage"));
        duality.getConfig()
            .setInventorySlotContents(4, tagged(Items.diamond, 23, "interface-storage"));
        duality.setPriority(73);
        duality.getConfigManager()
            .putSetting(Settings.BLOCK, YesNo.YES);
        duality.getConfigManager()
            .putSetting(Settings.INTERFACE_TERMINAL, YesNo.NO);
    }

    private void verify(WorldServer world, String stage) {
        retainChunks(world);
        recordChunks(world, stage);
        HologramCutLightingNativeSmoke.verify(world, oracle, stage);
        for (int dx : OFFSETS) {
            TileEntity actual = world.getTileEntity(TARGET_X + dx, Y, Z);
            require(actual != null && !actual.isInvalid(), stage + " actual native tile " + dx);
            equalProjection(dx, snapshot(actual), stage);
        }
        TileMolecularAssembler assembler = (TileMolecularAssembler) world.getTileEntity(TARGET_X + 6, Y, Z);
        ItemStack actualPattern = assembler.getInternalInventory()
            .getStackInSlot(10);
        require(
            ((ICraftingPatternItem) actualPattern.getItem()).getPatternForItem(actualPattern, world) != null,
            stage + " actual assembler recipe still parses");
        oracle.setBoolean(stage, true);
    }

    private void verifyFixtureInputs(ItemStack processing, ItemStack crafting) {
        NBTTagCompound gt = oracle.getCompoundTag("source0");
        NBTTagList buffers = gt.getTagList("internalInventory", 10);
        require(buffers.tagCount() == 2, "fixture actually stores both GT buffers");
        for (int i = 0; i < buffers.tagCount(); i++) {
            NBTTagCompound row = buffers.getCompoundTagAt(i);
            int slot = row.getInteger("patternSlot");
            require(slot == 0 || slot == 35, "fixture actual buffer slot");
            require(
                row.equals(buffer(slot, slot == 0 ? processing : crafting)),
                "fixture GT buffer exactly matches explicit pattern/items/fluids input " + slot);
        }
        expectInventory(gt.getTagList("Inventory", 10), 0, processing);
        expectInventory(gt.getTagList("Inventory", 10), 35, crafting);
        expectInventory(gt.getTagList("Inventory", 10), 36, gregtech.api.util.GTUtility.getIntegratedCircuit(24));
        expectInventory(gt.getTagList("Inventory", 10), 37, tagged(Items.diamond, 17, "manual-slot"));
        require(
            gt.getBoolean("additionalConnection") && gt.getBoolean("disablePatternOptimization")
                && !gt.getBoolean("showPattern"),
            "GT configuration matches explicit nondefault input");
        for (NBTTagCompound data : new NBTTagCompound[] { oracle.getCompoundTag("source2"),
            oracle.getCompoundTag("source4")
                .getCompoundTag("extra:2") }) {
            require(
                data.getCompoundTag("patterns")
                    .getCompoundTag("#0")
                    .equals(processing.writeToNBT(new NBTTagCompound())),
                "first pattern equals explicit encoded input");
            require(
                data.getCompoundTag("patterns")
                    .getCompoundTag("#35")
                    .equals(crafting.writeToNBT(new NBTTagCompound())),
                "last pattern equals explicit encoded input");
            require(
                data.getCompoundTag("storage")
                    .getCompoundTag("#4")
                    .equals(tagged(Items.diamond, 23, "interface-storage").writeToNBT(new NBTTagCompound())),
                "actual interface nested inventory equals fixture input");
            require(
                data.getInteger("priority") == 73 && "YES".equals(data.getString("BLOCK"))
                    && "NO".equals(data.getString("INTERFACE_TERMINAL")),
                "actual interface configuration input");
            NBTTagCompound configured = new NBTTagCompound();
            AEItemStack.create(tagged(Items.diamond, 23, "interface-storage"))
                .writeToNBT(configured);
            require(
                data.getCompoundTag("config")
                    .getCompoundTag("#4")
                    .equals(configured),
                "actual configured filter equals full explicit item NBT and quantity");
        }
        require(
            oracle.getCompoundTag("source6")
                .getCompoundTag("inv")
                .getCompoundTag("item10")
                .equals(crafting.writeToNBT(new NBTTagCompound())),
            "assembler physical pattern matches fixture input");
        require(
            oracle.getCompoundTag("source8")
                .getCompoundTag("myPlan")
                .equals(crafting.writeToNBT(new NBTTagCompound())),
            "assembler running pattern matches fixture input");
        require(
            oracle.getCompoundTag("source8")
                .getInteger("pushDirection") == ForgeDirection.EAST.ordinal(),
            "assembler actual running output direction equals fixture input");
        for (int dx : new int[] { 0, 2, 6, 8 }) require(
            oracle.getCompoundTag("source" + dx)
                .getCompoundTag("proxy")
                .getInteger("p") == oracle.getInteger("owner"),
            "fixture nonzero owner " + dx);
        require(
            oracle.getCompoundTag("source4")
                .getCompoundTag("extra:2")
                .getCompoundTag("part")
                .getInteger("p") == oracle.getInteger("owner"),
            "fixture actual interface part owner");
    }

    private static void expectInventory(NBTTagList inventory, int slot, ItemStack expected) {
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound row = inventory.getCompoundTagAt(i);
            if (row.getInteger("IntSlot") != slot) continue;
            require(
                ItemStack.areItemStacksEqual(expected, gregtech.api.util.GTUtility.loadItem(row)),
                "fixture actual GT inventory equals explicit input slot " + slot);
            return;
        }
        throw new AssertionError("fixture GT inventory slot missing " + slot);
    }

    private void verifyDisk(WorldServer world, String stage) throws Exception {
        retainChunks(world);
        HologramCutWorld.DiskReader reader = new HologramCutWorld.DiskReader(
            HologramCutService.saveRoot()
                .toFile());
        for (int dx : OFFSETS) {
            NBTTagList tiles = reader.read(world, (TARGET_X + dx) >> 4, Z >> 4).level.getTagList("TileEntities", 10);
            NBTTagCompound actual = null;
            for (int i = 0; i < tiles.tagCount(); i++) {
                NBTTagCompound tile = tiles.getCompoundTagAt(i);
                if (tile.getInteger("x") == TARGET_X + dx && tile.getInteger("y") == Y && tile.getInteger("z") == Z)
                    actual = tile;
            }
            require(actual != null, stage + " actual disk tile " + dx);
            equalProjection(dx, actual, stage);
            oracle.setTag(stage + dx, actual.copy());
        }
        oracle.setBoolean(stage, true);
    }

    private void equalProjection(int dx, NBTTagCompound actual, String stage) {
        NBTTagCompound expected = project(dx, oracle.getCompoundTag("source" + dx));
        NBTTagCompound received = project(dx, actual);
        require(
            HologramCutWorld.sameNbt(expected, received),
            stage + " source projection mismatch dx=" + dx + " expected=" + expected + " actual=" + received);
    }

    private static NBTTagCompound project(int dx, NBTTagCompound raw) {
        NBTTagCompound out = new NBTTagCompound();
        for (String key : COMMON) copy(raw, out, key);
        if (dx == 0) {
            for (String key : new String[] { "Inventory", "internalInventory", "mID", "mFacing", "mOwnerName",
                "mOwnerUuid", "additionalConnection", "disablePatternOptimization", "showPattern" })
                copy(raw, out, key);
        } else if (dx == 2) {
            for (String key : INTERFACE) copy(raw, out, key);
        } else if (dx == 4) {
            for (int side = 0; side < 7; side++) {
                copy(raw, out, "def:" + side);
                String key = "extra:" + side;
                if (raw.hasKey(key, 10)) {
                    NBTTagCompound extra = (NBTTagCompound) raw.getCompoundTag(key)
                        .copy();
                    if (extra.hasKey("part", 10)) extra.getCompoundTag("part")
                        .removeTag("g");
                    out.setTag(key, extra);
                }
            }
        } else {
            for (String key : new String[] { "upgrades", "inv", "REDSTONE_CONTROLLED", "myPlan", "pushDirection" })
                copy(raw, out, key);
        }
        if (raw.hasKey("proxy", 10)) {
            NBTTagCompound proxy = (NBTTagCompound) raw.getCompoundTag("proxy")
                .copy();
            proxy.removeTag("g");
            out.setTag("proxy", proxy);
        }
        return out;
    }

    private static void copy(NBTTagCompound raw, NBTTagCompound out, String key) {
        if (raw.hasKey(key)) out.setTag(
            key,
            raw.getTag(key)
                .copy());
    }

    private static ItemStack pattern(boolean crafting) {
        ItemStack item = AEApi.instance()
            .definitions()
            .items()
            .encodedPattern()
            .maybeStack(1)
            .get();
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList in = new NBTTagList(), out = new NBTTagList();
        in.appendTag(
            tagged(
                crafting ? Item.getItemFromBlock(Blocks.log) : Items.diamond,
                1,
                crafting ? "crafting-input" : "processing-input").writeToNBT(new NBTTagCompound()));
        out.appendTag(
            tagged(crafting ? Item.getItemFromBlock(Blocks.planks) : Items.emerald, crafting ? 4 : 1, "encoded-output")
                .writeToNBT(new NBTTagCompound()));
        tag.setTag("in", in);
        tag.setTag("out", out);
        tag.setBoolean("crafting", crafting);
        tag.setBoolean("substitute", true);
        tag.setBoolean("beSubstitute", false);
        tag.setString("author", "GTMB\u771f\u5b9e\u539f\u751f\u6837\u677f");
        tag.setLong("encodedAt", 1792108800000L);
        tag.setString("privateNestedWitness", "preserve-this-pattern-tag");
        item.setTagCompound(tag);
        return item;
    }

    private static ItemStack tagged(net.minecraft.item.Item item, int amount, String value) {
        ItemStack stack = new ItemStack(item, amount);
        NBTTagCompound tag = new NBTTagCompound(), nested = new NBTTagCompound();
        nested.setString("witness", value);
        nested.setString("unicode", "\u5d4c\u5957\u6570\u636e\u4fdd\u771f\u00e9");
        nested.setIntArray("numbers", new int[] { 3, 7, 31 });
        tag.setTag("aeCutNested", nested);
        stack.setTagCompound(tag);
        return stack;
    }

    private static TileEntity place(WorldServer world, int x, Block block) {
        return place(world, x, block, 0);
    }

    private static TileEntity place(WorldServer world, int x, Block block, int metadata) {
        require(world.setBlock(x, Y, Z, block, metadata, 3), "native fixture placement " + x);
        require(
            world.getBlock(x, Y, Z) == block && world.getBlockMetadata(x, Y, Z) == metadata,
            "actual registered native block and metadata " + x);
        TileEntity tile = world.getTileEntity(x, Y, Z);
        if (tile == null) {
            // As in the existing native energy fixture, the real block factory owns the tile implementation.
            TileEntity created = block.createTileEntity(world, metadata);
            if (created == null && block instanceof appeng.block.AEBaseTileBlock)
                created = ((appeng.block.AEBaseTileBlock) block).createNewTileEntity(world, metadata);
            require(
                created != null,
                "registered native fixture factory " + x
                    + " metadata="
                    + metadata
                    + " blockClass="
                    + block.getClass()
                        .getName());
            created.setWorldObj(world);
            created.xCoord = x;
            created.yCoord = Y;
            created.zCoord = Z;
            world.setTileEntity(x, Y, Z, created);
            tile = world.getTileEntity(x, Y, Z);
            require(tile == created, "world retained actual factory tile " + x);
        }
        require(tile != null && tile.getWorldObj() == world && !tile.isInvalid(), "native fixture tile " + x);
        System.out.println(
            PREFIX + "fixtureTile x="
                + x
                + " metadata="
                + metadata
                + " nativeClass="
                + tile.getClass()
                    .getName());
        return tile;
    }

    private static Block find(String name) {
        for (Object block : Block.blockRegistry) if (block != null && block.getClass()
            .getName()
            .equals(name)) return (Block) block;
        throw new AssertionError("required native block missing " + name);
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        return tag;
    }

    private static NBTTagCompound request(String action) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("action", action);
        tag.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return tag;
    }

    private static void success(NBTTagCompound reply, String stage) {
        require(reply.getBoolean("success"), stage + ": " + reply.getString("message"));
    }

    private static void receipt(Path root, String phase, NBTTagCompound data) throws Exception {
        Files.write(root.resolve("gtmb-ae-cut-data-" + phase + ".nbt"), HologramSnapshotCodec.compress(data));
    }

    private void finish(MinecraftServer server, String message) {
        // Cleanup must succeed before the single terminal PASS; later shutdown ticks cannot restart the fixture.
        releaseChunks();
        done = true;
        System.out.println(PREFIX + message);
        server.initiateShutdown();
    }

    private void acquireChunks(WorldServer world) {
        ProbeChunkTickets.ensureRegistered();
        chunkTicket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(chunkTicket != null, "owned native fixture chunk ticket");
        chunkTicket.getModData()
            .setBoolean("gtmbAeCutDataProbe", true);
        // Include the AE source/target neighbors and the transient world-reader control at x960.
        for (int cx = (SOURCE_X - 16) >> 4; cx <= (TARGET_X + 8 + 16) >> 4; cx++)
            for (int cz = (Z - 16) >> 4; cz <= (Z + 16) >> 4; cz++) loadChunk(world, cx, cz);
        loadChunk(world, 960 >> 4, Z >> 4);
        for (ChunkCoordIntPair coordinate : nativeChunks.keySet())
            if (modifiedChunk(coordinate)) ForgeChunkManager.forceChunk(chunkTicket, coordinate);
        retainChunks(world);
        System.out.println(
            PREFIX + "realChunks="
                + nativeChunks.size()
                + " loadChunkOnProvideRequest="
                + world.theChunkProviderServer.loadChunkOnProvideRequest);
    }

    private Chunk loadChunk(WorldServer world, int cx, int cz) {
        // provideChunk/getChunkFromChunkCoords can return one shared EmptyChunk while native loading is disabled.
        Chunk chunk = world.theChunkProviderServer.loadChunk(cx, cz);
        require(
            chunk != null && !(chunk instanceof EmptyChunk)
                && chunk.isChunkLoaded
                && chunk.xPosition == cx
                && chunk.zPosition == cz
                && world.theChunkProviderServer.chunkExists(cx, cz),
            "real native fixture chunk " + cx + "," + cz);
        ChunkCoordIntPair coordinate = new ChunkCoordIntPair(cx, cz);
        ChunkCoordIntPair alias = chunkIdentities.get(chunk);
        require(alias == null || alias.equals(coordinate), "different coordinates cannot share a native chunk object");
        Chunk previous = nativeChunks.get(coordinate);
        require(
            previous == null || previous == chunk || !modifiedChunk(coordinate),
            "modified native fixture chunk identity remains loaded");
        if (previous != null && previous != chunk) chunkIdentities.remove(previous);
        nativeChunks.put(coordinate, chunk);
        chunkIdentities.put(chunk, coordinate);
        require(world.getChunkFromChunkCoords(cx, cz) == chunk, "ordinary lookup returns the explicitly loaded chunk");
        return chunk;
    }

    private void retainChunks(WorldServer world) {
        require(chunkTicket != null, "owned fixture ticket exists before reads");
        for (ChunkCoordIntPair coordinate : nativeChunks.keySet()) {
            loadChunk(world, coordinate.chunkXPos, coordinate.chunkZPos);
            if (modifiedChunk(coordinate)) require(
                world.getPersistentChunks()
                    .containsEntry(coordinate, chunkTicket),
                "fixture retains its actual modified native chunk ticket");
        }
    }

    private void recordChunks(WorldServer world, String stage) {
        NBTTagCompound facts = new NBTTagCompound();
        NBTTagList chunks = new NBTTagList();
        for (Map.Entry<ChunkCoordIntPair, Chunk> item : nativeChunks.entrySet()) {
            Chunk chunk = item.getValue();
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", chunk.xPosition);
            row.setInteger("z", chunk.zPosition);
            row.setString(
                "nativeClass",
                chunk.getClass()
                    .getName());
            row.setBoolean("loaded", chunk.isChunkLoaded);
            row.setBoolean("emptyChunk", chunk instanceof EmptyChunk);
            row.setBoolean("forced", modifiedChunk(item.getKey()));
            chunks.appendTag(row);
        }
        facts.setTag("chunks", chunks);
        facts.setBoolean("loadChunkOnProvideRequest", world.theChunkProviderServer.loadChunkOnProvideRequest);
        facts.setBoolean("uniqueNativeIdentities", chunkIdentities.size() == nativeChunks.size());
        facts.setLong("worldTick", world.getTotalWorldTime());
        oracle.setTag("nativeChunks-" + stage, facts);
    }

    private void releaseChunks() {
        if (chunkTicket == null) return;
        ForgeChunkManager.releaseTicket(chunkTicket);
        chunkTicket = null;
    }

    private static boolean modifiedChunk(ChunkCoordIntPair coordinate) {
        return coordinate.chunkZPos == (Z >> 4)
            && (coordinate.chunkXPos == (SOURCE_X >> 4) || coordinate.chunkXPos == (TARGET_X >> 4));
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(PREFIX + message);
    }

    /** A real IRecipe exercising AE's native PatternHelper; no stand-in AE tile or inventory is used. */
    private static final class WorldRecipe implements IRecipe {

        public boolean matches(InventoryCrafting inventory, World world) {
            if (world == null) return false;
            int found = 0;
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                ItemStack item = inventory.getStackInSlot(i);
                if (item == null) continue;
                if (item.getItem() != Items.diamond || !item.hasTagCompound()
                    || !"world-recipe".equals(
                        item.getTagCompound()
                            .getCompoundTag("aeCutNested")
                            .getString("witness")))
                    return false;
                found++;
            }
            return found == 1;
        }

        public ItemStack getCraftingResult(InventoryCrafting inventory) {
            return getRecipeOutput().copy();
        }

        public int getRecipeSize() {
            return 1;
        }

        public ItemStack getRecipeOutput() {
            return tagged(Items.emerald, 1, "encoded-output");
        }
    }
}

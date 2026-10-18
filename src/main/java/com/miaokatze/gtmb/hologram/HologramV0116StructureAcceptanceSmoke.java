package com.miaokatze.gtmb.hologram;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IStructureElement.PlaceResult;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.gtnewhorizon.structurelib.util.ItemStackPredicate;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Installed GTSR parameter/declaration witness. No checkMachine call and no claim of actual formation. */
public final class HologramV0116StructureAcceptanceSmoke {

    private static final int X = 1100000, Y = 100, Z = 1100000;
    private static final String TYPE = "com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray";

    private HologramV0116StructureAcceptanceSmoke() {}

    public static NBTTagCompound run(WorldServer world, EntityPlayerMP actor) throws Exception {
        require(Boolean.getBoolean("gtmb.v0116.structureAcceptance"), "explicit structure acceptance flag");
        require(
            actor instanceof FakePlayer && actor.worldObj == world
                && !world.isRemote
                && world.playerEntities.isEmpty()
                && world.getWorldInfo()
                    .getWorldName()
                    .matches("hologram-smoke-v0115-[A-Za-z0-9_-]{1,20}"),
            "owned empty acceptance world and native Forge actor");
        boolean creative = actor.capabilities.isCreativeMode;
        double px = actor.posX, py = actor.posY, pz = actor.posZ;
        try {
            actor.capabilities.isCreativeMode = true;
            actor.setPosition(X + .5, Y, Z + .5);
            for (int cx = (X - 32) >> 4; cx <= (X + 32) >> 4; cx++)
                for (int cz = (Z - 32) >> 4; cz <= (Z + 32) >> 4; cz++) world.theChunkProviderServer.loadChunk(cx, cz);
            int id = machineId();
            require(id == 14850, "installed native turbine id agrees with the supplied blueprint");
            MTEEnhancedMultiBlockBase<?> prototype = (MTEEnhancedMultiBlockBase<?>) GregTechAPI.METATILEENTITIES[id];
            NBTTagCompound prototypeBefore = persistence(prototype);
            MTEEnhancedMultiBlockBase<?> detached = HologramDetachedController.create(id, ExtendedFacing.DEFAULT);
            TileEntity base = (TileEntity) detached.getBaseMetaTileEntity();
            base.xCoord = X;
            base.yCoord = Y;
            base.zCoord = Z;
            NBTTagList cases = new NBTTagList();
            HologramCapture[] captures = new HologramCapture[3];
            int[] heights = { 1, 9, 25 };
            for (int i = 0; i < heights.length; i++) {
                captures[i] = complete(detached, trigger(heights[i], 3));
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("rawHeight", heights[i]);
                row.setInteger("pieces", captures[i].pieces.size());
                row.setInteger("cells", captures[i].cells.size());
                cases.appendTag(row);
            }
            require(
                captures[0].pieces.size() == 2 && captures[1].pieces.size() == 4 && captures[2].pieces.size() == 6,
                "real turbine base/stack/cap piece counts at raw heights 1/9/25");
            require(
                captures[0].cells.size() < captures[1].cells.size()
                    && captures[1].cells.size() < captures[2].cells.size(),
                "actual native height changes the footprint");
            MTEEnhancedMultiBlockBase<?> live = placeScene(world, actor, id, captures[2]);
            NBTTagCompound sourceBefore = HologramNativeCapture.capture((TileEntity) live.getBaseMetaTileEntity());
            HologramAreaSignalInference inference = new HologramAreaSignalInference(live, trigger(1, 1));
            int polls = 0;
            while (!inference.step(System.nanoTime() + 5_000_000L) && ++polls < 4096) {}
            require(polls < 4096 && !inference.capture().incomplete, "resumable actual-world inference completes");
            require(
                HologramAreaSignalInference.geometry(captures[2])
                    .equals(HologramAreaSignalInference.geometry(inference.capture()))
                    && inference.capture().cells.size() == captures[2].cells.size(),
                "source scene infers all four additional native stacks instead of the default first stage");
            require(
                ChannelDataAccessor.getChannelData(inference.capture().trigger, "casing") == 3
                    && ChannelDataAccessor.getChannelData(inference.capture().trigger, "pipe") == 2
                    && ChannelDataAccessor.getChannelData(inference.capture().trigger, "gear") == 2,
                "native material channels are inferred from the actual source scene");
            require(
                sourceBefore.equals(HologramNativeCapture.capture((TileEntity) live.getBaseMetaTileEntity())),
                "inference leaves source persistence exact");

            HologramSession session = legacyMember(actor, id);
            HologramPresets.Structure selected = session.virtualPreset.structures.get(0);
            NBTTagList capabilities = HologramVirtualStructureEditing.capabilities(session, selected)
                .toNBT();
            for (String channel : new String[] { "height", "casing", "pipe", "gear" }) {
                boolean found = false;
                for (int i = 0; i < capabilities.tagCount(); i++) found |= channel.equals(
                    capabilities.getCompoundTagAt(i)
                        .getString("id"))
                    && capabilities.getCompoundTagAt(i)
                        .getBoolean("editable");
                require(found, "legacy empty-channel member discovers editable " + channel);
            }
            int[] editedCells = new int[3];
            for (int i = 2; i >= 0; i--) {
                edit(session, heights[i], 3);
                editedCells[i] = session.capture.cells.size();
                require(editedCells[i] == captures[i].cells.size(), "virtual selected member rebuild " + heights[i]);
            }
            edit(session, 25, 3);
            String tier3 = materials(session.capture);
            edit(session, 25, 1);
            require(!tier3.equals(materials(session.capture)), "same-height native casing tier remains editable");
            session.work.clear();
            for (int i = session.capture.cells.size() - 1; i >= 0; i--) session.work.add(i);
            long sortNanos = HologramPlacementJobs.spatialWorkOrder(session);
            int controller = -1;
            for (int i = 0; i < session.work.size(); i++) {
                HologramCapture.Cell cell = session.capture.cells.get(session.work.get(i));
                if ("templateController".equals(cell.role)) {
                    controller = i;
                    cell.status = "placed";
                }
                if (i > 0) require(
                    compare(session.capture.cells.get(session.work.get(i - 1)), cell) <= 0,
                    "integer spatial order");
            }
            require(
                controller >= 0 && controller < session.work.size() - 1,
                "old controller-last storage no longer forces controller-last construction");
            live.mUpdated = false;
            HologramPlacementJobs.notifyCompletedControllers(session);
            require(live.mUpdated, "completed controller receives native dirty/update flag");
            require(prototypeBefore.equals(persistence(prototype)), "registration prototype persistence remains exact");

            NBTTagCompound result = new NBTTagCompound();
            result.setString("status", "PASS");
            result.setInteger("controllerId", id);
            result.setInteger("heightCases", 3);
            result.setTag("nativeHeightCases", cases);
            result.setTag("inference", inference.diagnostics());
            result.setLong("spatialSortNanos", sortNanos);
            result.setInteger("spatialCells", session.work.size());
            for (String key : Arrays.asList(
                "inferredSceneParameters",
                "virtualHeightEditable",
                "capabilityChannelsComplete",
                "prototypeUnchanged",
                "sourceNbtUnchanged",
                "spatialWorkOrder",
                "controllerCompletionMarked")) result.setBoolean(key, true);
            result.setBoolean("directCheckMachineInvoked", false);
            result.setBoolean("actualFormationVerified", false);
            result.setString("prototypeSha256", digest(prototypeBefore));
            result.setString("sourceSha256", digest(sourceBefore));
            result.setString(
                "scope",
                "native registered GTSR declarations, physical casing/frame scene, actual-world inference, legacy member parameter rebuild; no hatch-complete formation fixture");
            return result;
        } finally {
            actor.capabilities.isCreativeMode = creative;
            actor.setPosition(px, py, pz);
        }
    }

    private static HologramSession legacyMember(EntityPlayerMP actor, int id) {
        HologramSession session = new HologramSession(actor, X, Y, Z, false, 2);
        session.virtual = true;
        session.main = 1;
        session.channels = new NBTTagCompound();
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.controllerFacing = ExtendedFacing.DEFAULT.ordinal();
        HologramPresets.Structure selected = new HologramPresets.Structure();
        selected.id = "native-legacy-turbine";
        selected.sourceClass = TYPE;
        selected.controllerId = id;
        session.virtualPreset.structures.add(selected);
        session.blueprintActiveStructure = selected.id;
        ItemStack shell = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
        HologramPresets.Cell saved = HologramPresets.cell(
            shell,
            0,
            0,
            0,
            ExtendedFacing.DEFAULT.getDirection()
                .ordinal(),
            true);
        saved.structureId = selected.id;
        session.virtualPreset.cells.add(saved);
        HologramCapture.Cell cell = new HologramCapture.Cell(null, X, Y, Z);
        cell.block = GregTechAPI.sBlockMachines;
        cell.role = "templateController";
        cell.userPosition = true;
        HologramBlueprintData.setMemberships(cell, java.util.Collections.singletonList(selected.id));
        session.capture = new HologramCapture(null, shell);
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
        session.pins.put(0, shell);
        return session;
    }

    private static void edit(HologramSession session, int height, int casing) throws Exception {
        NBTTagCompound channels = trigger(height, casing).getTagCompound()
            .getCompoundTag("channels");
        HologramPresets.Structure selected = session.virtualPreset.structures.get(0);
        HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
            session,
            selected,
            1,
            channels);
        session.main = 1;
        session.channels = (NBTTagCompound) channels.copy();
        int polls = 0;
        while (!task.step() && ++polls < 4096) {}
        require(polls < 4096, "virtual parameter rebuild completes");
        task.publish();
    }

    private static HologramCapture complete(MTEEnhancedMultiBlockBase<?> machine, ItemStack signal) {
        HologramCapture capture = null;
        int polls = 0;
        do {
            capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture);
        } while (capture.pending && ++polls < 4096);
        require(!capture.pending && !capture.incomplete, "native declaration capture complete");
        return capture;
    }

    private static MTEEnhancedMultiBlockBase<?> placeScene(WorldServer world, EntityPlayerMP actor, int id,
        HologramCapture source) {
        for (HologramCapture.Cell cell : source.cells)
            require(world.isAirBlock(cell.x, cell.y, cell.z), "fresh isolated source footprint");
        ItemStack shell = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
        require(
            ((ItemMachines) shell.getItem()).placeBlockAt(shell, actor, world, X, Y, Z, 1, .5F, .5F, .5F, 0),
            "native source controller placement");
        MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) world
            .getTileEntity(X, Y, Z)).getMetaTileEntity();
        machine.setExtendedFacing(ExtendedFacing.DEFAULT);
        for (HologramCapture.Cell cell : source.cells) {
            if (cell.anchor || cell.block == Blocks.air) continue;
            ItemStack material = HologramBlockIdentity.materialStack(cell.block, cell.meta);
            require(material != null && !cell.unknown, "native casing/frame construction material");
            PlaceResult placed = StructureUtility.survivalPlaceBlock(
                material,
                ItemStackPredicate.NBTMode.IGNORE,
                null,
                false,
                world,
                cell.x,
                cell.y,
                cell.z,
                new HologramMaterialSources.CreativeSource(java.util.Collections.singletonList(material)),
                actor);
            require(placed == PlaceResult.ACCEPT || placed == PlaceResult.ACCEPT_STOP, "native casing/frame placement");
            Block actual = world.getBlock(cell.x, cell.y, cell.z);
            if (actual instanceof ICustomBlockSetting) {
                actual.onBlockPlacedBy(world, cell.x, cell.y, cell.z, actor, material);
                actual.onPostBlockPlaced(world, cell.x, cell.y, cell.z, world.getBlockMetadata(cell.x, cell.y, cell.z));
            }
        }
        return machine;
    }

    private static ItemStack trigger(int height, int casing) {
        ItemStack signal = new ItemStack(Blocks.stone);
        ChannelDataAccessor.setChannelData(signal, "height", height);
        ChannelDataAccessor.setChannelData(signal, "casing", casing);
        ChannelDataAccessor.setChannelData(signal, "pipe", 2);
        ChannelDataAccessor.setChannelData(signal, "gear", 2);
        return signal;
    }

    private static int machineId() {
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++)
            if (GregTechAPI.METATILEENTITIES[id] != null && TYPE.equals(
                GregTechAPI.METATILEENTITIES[id].getClass()
                    .getName()))
                return id;
        throw new AssertionError("installed GTSR turbine registry entry missing");
    }

    private static NBTTagCompound persistence(MTEEnhancedMultiBlockBase<?> machine) {
        NBTTagCompound data = new NBTTagCompound();
        machine.saveNBTData(data);
        return data;
    }

    private static String materials(HologramCapture capture) {
        Map<String, Integer> counts = new TreeMap<>();
        for (HologramCapture.Cell cell : capture.cells)
            counts.merge(HologramBlockIdentity.key(cell.block, cell.meta), 1, Integer::sum);
        return counts.toString();
    }

    private static int compare(HologramCapture.Cell a, HologramCapture.Cell b) {
        int order = Integer.compare(a.y, b.y);
        if (order == 0) order = Integer.compare(a.z, b.z);
        return order == 0 ? Integer.compare(a.x, b.x) : order;
    }

    private static String digest(NBTTagCompound data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(CompressedStreamTools.compress(data));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("Native structure acceptance: " + message);
    }
}

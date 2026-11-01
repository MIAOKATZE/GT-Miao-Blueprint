package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testlab.TestLabWorldProvider;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Bounded real-server probe; only an active owned TestLab actor can construct or restore its three empty slots. */
public final class HologramGTNLNativeProbe {

    private HologramGTNLNativeProbe() {}

    public static Run begin(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        requireOwned(world, actor, x, y, z);
        for (int dx = 0; dx < 3; dx++) require(
            world.isAirBlock(x + dx, y, z) && world.getTileEntity(x + dx, y, z) == null,
            "GTNL fixture requires three original empty cells");
        return new Run(world, actor, x, y, z);
    }

    public static final class Run implements AutoCloseable {

        private final WorldServer world;
        private final FakePlayer actor;
        private final int x, y, z;
        private final NBTTagCompound report = new NBTTagCompound();
        private final NBTTagList rows = new NBTTagList();
        private final NBTTagList originalInventory;
        private final int originalSlot;
        private final String runId;
        private final List<Spec> specs = specs();
        private int index, pass, slices, failures;
        private long captureNanos, maxCaptureSliceNanos;
        private boolean finished, closed;
        private BaseMetaTileEntity tile;
        private MTEEnhancedMultiBlockBase<?> machine;
        private HologramCapture capture, first;
        private NBTTagCompound stateBefore, currentRow;
        private ItemStack trigger, debugItem, bwItem, fixedItem;
        private HologramGTNLElement debugElement, bwElement, fixedElement;
        private boolean debugPlacementUnsupported;
        private String coldFingerprint;

        private Run(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            originalInventory = actor.inventory.writeToNBT(new NBTTagList());
            originalSlot = actor.inventory.currentItem;
            runId = TestLab.status()
                .getString("runId");
            report.setString(
                "scope",
                "Real registered rc2.1 controllers, GTMB source/capture and native ItemBlock samples");
            report.setBoolean("actualWorld", true);
            report.setBoolean("inventoryUnchanged", true);
            report.setBoolean("actualNetty", false);
            report.setBoolean("guiVerified", false);
            report.setBoolean("fullMachineFormationVerified", false);
            report.setString(
                "coldMeaning",
                "First GTMB source graph on a newly placed controller; upstream startup caches are not reset");
            report.setString(
                "nativeCounterScope",
                "Actual GTMB direct construct/getStructureDefinition/spawnHint call sites, not unrelated server calls");
            report.setString(
                "nbtComparison",
                "Only AssemblerMatrix's freshly generated saveInvData storeUUID is normalized");
            report.setString(
                "activeStateFixture",
                "AEEP isRenderActive is set only on the owned isolated controller; no production recipe is claimed");
            report.setInteger("dimension", world.provider.dimensionId);
            report.setIntArray("origin", new int[] { x, y, z });
            report.setTag("cases", rows);
            Map<String, ModContainer> mods = Loader.instance()
                .getIndexedModList();
            require(
                mods.containsKey("sciencenotleisure") && mods.containsKey("gtnllib"),
                "GTNL and GTNLLib must actually be installed");
            require(
                "0.2.7-rc2.1".equals(
                    mods.get("sciencenotleisure")
                        .getVersion()),
                "GTNL runtime must be 0.2.7-rc2.1");
            require(
                "1.1.1".equals(
                    mods.get("gtnllib")
                        .getVersion()),
                "GTNLLib runtime must be 1.1.1");
            report.setString(
                "gtnlVersion",
                mods.get("sciencenotleisure")
                    .getVersion());
            report.setString(
                "gtnlJar",
                mods.get("sciencenotleisure")
                    .getSource()
                    .getName());
            report.setString(
                "gtnllibVersion",
                mods.get("gtnllib")
                    .getVersion());
            report.setString("sourceCommit", HologramGTNLSemantics.COMMIT);
        }

        /** Executes one placement or one existing bounded capture slice. The scheduler retains its tick budget. */
        public boolean step() throws Exception {
            require(!closed, "GTNL probe already closed");
            requireOwned(world, actor, x, y, z);
            if (index == specs.size()) {
                if (pass < 4) {
                    currentRow = new NBTTagCompound();
                    currentRow.setString(
                        "name",
                        pass == 0 ? "assembler_damage18"
                            : pass == 1 ? "bartworks_full_damage"
                                : pass == 2 ? "production_fixed_shell_payment" : "production_damage18_identity");
                    currentRow.setString("status", "RUNNING");
                    rows.appendTag(currentRow);
                    try {
                        if (pass < 2) placeMaterial(
                            pass == 0 ? debugItem : bwItem,
                            pass == 0 ? debugElement : bwElement,
                            x + 1 + pass);
                        else productionPlacement(
                            pass == 2 ? fixedItem : debugItem,
                            pass == 2 ? fixedElement : debugElement,
                            pass == 3 && debugPlacementUnsupported);
                        currentRow.setString("status", "PASS");
                    } catch (Exception | LinkageError failure) {
                        failCase(failure.toString());
                    } finally {
                        finishCase();
                    }
                    currentRow = null;
                    pass++;
                    return false;
                }
                finished = true;
                report.setBoolean("checksComplete", true);
                report.setInteger("failedCases", failures);
                return true;
            }
            Spec spec = specs.get(index);
            if (currentRow == null) {
                currentRow = new NBTTagCompound();
                currentRow.setString(
                    "name",
                    spec.family + "/" + spec.main + "/" + spec.unit + "/" + spec.intent + "/" + spec.active);
                currentRow.setString("status", "RUNNING");
                currentRow.setString("controllerClass", spec.type);
                currentRow.setTag("captures", new NBTTagList());
                rows.appendTag(currentRow);
            }
            boolean complete = false;
            try {
                complete = captureStep(spec);
            } catch (Exception | LinkageError failure) {
                failCase(failure.toString());
                complete = true;
            } finally {
                if (complete) finishCase();
            }
            if (complete) {
                machine = null;
                tile = null;
                first = capture = null;
                currentRow = null;
                index++;
                pass = slices = 0;
            }
            return false;
        }

        private boolean captureStep(Spec spec) throws Exception {
            if (machine == null) {
                install(spec);
                return false;
            }
            if (capture == null) {
                stateBefore = snapshot(tile);
                captureNanos = maxCaptureSliceNanos = 0;
            }
            long captureStarted = System.nanoTime();
            capture = HologramCapture.collect(machine, trigger, machine.getExtendedFacing(), spec.intent, capture);
            long elapsed = System.nanoTime() - captureStarted;
            captureNanos += elapsed;
            maxCaptureSliceNanos = Math.max(maxCaptureSliceNanos, elapsed);
            slices++;
            require(slices <= 64, "GTNL capture exceeded its finite slice bound");
            if (capture.pending) return false;
            require(!capture.incomplete, "GTNL capture refused " + spec.family + ": " + capture.failure);
            require(capture.sourceCurrent(), "GTNL source expired: " + capture.failure);
            require(
                capture.sourceResolution != null && capture.sourceResolution.status == StructureSources.Status.READY,
                "GTNL native probe must use the explicit source path");
            require(
                capture.nativeConstructCalls == 0 && capture.nativeDefinitionCalls == 0 && capture.nativeHintCalls == 0,
                "GTNL capture invoked a native callback");
            require(stateBefore.equals(snapshot(tile)), "GTNL capture changed native persisted controller state");
            require(
                originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "GTNL pure capture changed actor inventory");
            for (HologramCapture.Cell cell : capture.cells)
                require(cell.anchor || !cell.unknown, "Reviewed GTNL leaf remained unknown");
            validate(spec, capture);
            String fingerprint = fingerprint(capture);
            NBTTagCompound sample = new NBTTagCompound();
            sample.setString("phase", pass == 0 ? "cold" : "hot");
            sample.setInteger("cells", capture.cells.size());
            sample.setInteger("pieces", capture.pieces.size());
            sample.setInteger("slices", slices);
            sample.setLong("activeCaptureNanos", captureNanos);
            sample.setLong("maxCaptureSliceNanos", maxCaptureSliceNanos);
            sample.setLong("wallCaptureNanos", System.nanoTime() - capture.started);
            sample.setInteger("nativeConstructCalls", capture.nativeConstructCalls);
            sample.setInteger("nativeDefinitionCalls", capture.nativeDefinitionCalls);
            sample.setInteger("nativeHintCalls", capture.nativeHintCalls);
            sample.setBoolean("controllerNbtUnchanged", true);
            sample.setBoolean("inventoryUnchanged", true);
            sample.setString("fingerprint", fingerprint);
            sample.setTag("metadata", capture.sourceMetadata.copy());
            currentRow.getTagList("captures", 10)
                .appendTag(sample);
            if (pass == 0) {
                coldFingerprint = fingerprint;
                first = capture;
                capture = null;
                pass = 1;
                slices = 0;
            } else {
                require(
                    first.definition != capture.definition,
                    "New GTNL capture reused its previous definition object");
                require(coldFingerprint.equals(fingerprint), "GTNL cold/hot semantic fingerprints differ");
                currentRow.setString("status", "PASS");
                return true;
            }
            return false;
        }

        private void failCase(String error) {
            if (!"FAIL".equals(currentRow.getString("status"))) failures++;
            currentRow.setString("status", "FAIL");
            String previous = currentRow.getString("error");
            currentRow.setString("error", previous.isEmpty() ? error : previous + "; " + error);
            if (capture != null) {
                currentRow.setString("captureFailure", capture.failure);
                currentRow.setString("captureFailureOrigin", capture.failureOrigin);
                currentRow.setInteger("capturedCells", capture.cells.size());
                currentRow.setInteger("nativeConstructCalls", capture.nativeConstructCalls);
                currentRow.setInteger("nativeDefinitionCalls", capture.nativeDefinitionCalls);
                currentRow.setInteger("nativeHintCalls", capture.nativeHintCalls);
            }
            report.setInteger("failedCases", failures);
        }

        private void finishCase() throws IOException {
            try {
                if (capture != null) capture.cancelPending();
                boolean inventoryUnchanged = originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && actor.inventory.currentItem == originalSlot;
                currentRow.setBoolean("inventoryUnchanged", inventoryUnchanged);
                if (!inventoryUnchanged) {
                    report.setBoolean("inventoryUnchanged", false);
                    failCase("Native case changed actor inventory before restoration");
                }
                restoreInventory();
                for (int dx = 0; dx < 3; dx++) clearSlot(x + dx);
                for (int dx = 0; dx < 3; dx++) require(
                    world.isAirBlock(x + dx, y, z) && world.getTileEntity(x + dx, y, z) == null,
                    "GTNL case did not restore its original empty slot");
                currentRow.setBoolean("ownedSlotsRestored", true);
                currentRow.setBoolean("actorInventoryRestored", true);
            } catch (IOException | RuntimeException | LinkageError failure) {
                failCase("Case cleanup failed: " + failure);
                currentRow.setString("cleanupError", failure.toString());
                throw new IOException("GTNL case cleanup failed; independent work cannot safely continue", failure);
            }
        }

        private void restoreInventory() throws IOException {
            require(
                TestLab.isEnabled() && world.provider instanceof TestLabWorldProvider
                    && actor.worldObj == world
                    && runId.equals(
                        TestLab.status()
                            .getString("runId")),
                "GTNL actor restoration lost its run ownership");
            if (!originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                || actor.inventory.currentItem != originalSlot) {
                actor.inventory.readFromNBT((NBTTagList) originalInventory.copy());
                actor.inventory.currentItem = originalSlot;
                actor.inventory.markDirty();
            }
            require(
                originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "GTNL actor restoration failed");
        }

        private void install(Spec spec) throws Exception {
            int id = -1;
            for (int candidate = 0; candidate < GregTechAPI.METATILEENTITIES.length; candidate++) {
                IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[candidate];
                if (prototype != null && spec.type.equals(
                    prototype.getClass()
                        .getName())) {
                    id = candidate;
                    break;
                }
            }
            require(id >= 0, "Real GTNL controller registration absent: " + spec.type);
            ItemStack item = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
            require(item.getItem() instanceof ItemMachines, "GTNL controller has no native ItemMachines");
            require(
                ((ItemMachines) item.getItem())
                    .placeBlockAt(item, actor, world, x, y, z, ForgeDirection.NORTH.ordinal(), .5F, .5F, .5F, 0),
                "Native GTNL controller placement failed");
            require(world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity, "Native GT tile was not created");
            tile = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
            tile.setFrontFacing(ForgeDirection.NORTH);
            tile.disableWorking();
            tile.setOwnerName(actor.getCommandSenderName());
            tile.setOwnerUuid(actor.getUniqueID());
            require(
                tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase && spec.type.equals(
                    tile.getMetaTileEntity()
                        .getClass()
                        .getName()),
                "Native GTNL metatile differs");
            machine = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
            suppressNativeTick(world, tile);
            currentRow.setBoolean("nativeTickSuppressed", true);
            report.setString(
                "nativeTickScope",
                "Only the fixture controller is removed from native tick lists at END; its real chunk TE remains present");
            if ("aeep".equals(spec.family))
                field(machine.getClass(), "isRenderActive").setBoolean(machine, spec.active);
            trigger = new ItemStack(Blocks.stone, 1);
            if ("nuclear_reactor".equals(spec.family)) ChannelDataAccessor.setChannelData(trigger, "casing", spec.main);
            else if (!"assembler_matrix".equals(spec.family))
                ChannelDataAccessor.setChannelData(trigger, "machine_casing", spec.main);
            if ("precision_assembler".equals(spec.family))
                ChannelDataAccessor.setChannelData(trigger, "unit_casing", spec.unit);
            if ("aeep".equals(spec.family)) ChannelDataAccessor.setChannelData(trigger, "coil", spec.unit);
            int expected = "assembler_matrix".equals(spec.family) ? 1
                : "large_steam_mixer".equals(spec.family) ? 2
                    : "nuclear_reactor".equals(spec.family) ? 3
                        : "precision_assembler".equals(spec.family) ? 50 : 4 * (HeatingCoilLevel.getMaxTier() + 1);
            require(
                HologramGTNLSemantics.inferenceTriggers(machine, trigger)
                    .size() == expected,
                "GTNL finite parameter domain differs for " + spec.family);
            currentRow.setInteger("controllerId", id);
            currentRow.setInteger("parameterCombinations", expected);
            currentRow.setTag("trigger", trigger.writeToNBT(new NBTTagCompound()));
        }

        private void validate(Spec spec, HologramCapture value) throws IOException {
            int cores = 0;
            for (HologramCapture.Cell cell : value.cells) {
                if (!(cell.element instanceof HologramGTNLElement)) continue;
                HologramGTNLElement leaf = (HologramGTNLElement) cell.element;
                if ("assembler.core".equals(leaf.id)) {
                    cores++;
                    for (ItemStack option : leaf.candidates()) if (option.getItemDamage() == 18) {
                        debugItem = option;
                        debugElement = leaf;
                    }
                }
                if ("nuclear.core".equals(leaf.id)) cores++;
                if ("aeep.rebolted".equals(leaf.id)) {
                    bwItem = leaf.primary();
                    bwElement = leaf;
                }
                if ("mixer.industrial".equals(leaf.id)) {
                    fixedItem = leaf.primary();
                    fixedElement = leaf;
                }
            }
            if ("assembler_matrix".equals(spec.family))
                require(cores == 343 && debugItem != null, "Assembler core declaration differs");
            if ("nuclear_reactor".equals(spec.family)) {
                int expected = spec.main == 1 ? 9 : spec.main == 2 ? 21 : 37;
                require(cores == expected, "Nuclear core count differs: " + cores);
                currentRow.setInteger("coreCells", cores);
            }
            if ("aeep".equals(spec.family)) {
                boolean air = spec.active && spec.intent == StructureSources.CaptureIntent.LIVE_SNAPSHOT;
                String sphere = air ? "sphere_air" : "sphere";
                int count = 0;
                for (IStructureElement<?> element : value.definition.getStructureFor(sphere))
                    if (air ? element == StructureUtility.isAir()
                        : element instanceof HologramGTNLElement
                            && ((HologramGTNLElement) element).id.startsWith("aeep.sphere."))
                        count++;
                require(count == 187, "AEEP sphere must contain exactly 187 declared material/air cells");
                require(sphere.equals(value.pieces.get(1).name), "AEEP intent selected the wrong sphere");
                require(
                    value.sourceMetadata.getCompoundTag("capabilities")
                        .getBoolean("construct") == !air,
                    "AEEP active snapshot acquired template construction capability");
                currentRow.setString("sphere", sphere);
                currentRow.setInteger("sphereCells", count);
            }
        }

        private void placeMaterial(ItemStack original, HologramGTNLElement declaration, int px) throws IOException {
            String name = currentRow.getString("name");
            require(original != null && declaration != null, "GTNL special material was not captured: " + name);
            requireOwned(world, actor, x, y, z);
            ItemStack stack = original.copy();
            require(stack.getItem() instanceof ItemBlock, "GTNL material has no native ItemBlock");
            currentRow.setTag("item", original.writeToNBT(new NBTTagCompound()));
            currentRow.setString("placementSupport", "UNSUPPORTED");
            int metadata = stack.getItem()
                .getMetadata(stack.getItemDamage());
            boolean placed;
            try {
                placed = ((ItemBlock) stack.getItem())
                    .placeBlockAt(stack, actor, world, px, y, z, ForgeDirection.UP.ordinal(), .5F, .5F, .5F, metadata);
            } finally {
                currentRow
                    .setString("block", String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(px, y, z))));
                currentRow.setInteger("worldMeta", world.getBlockMetadata(px, y, z));
                TileEntity actual = world.getTileEntity(px, y, z);
                currentRow.setString(
                    "tileClass",
                    actual == null ? ""
                        : actual.getClass()
                            .getName());
                if (actual != null) currentRow.setTag("tile", snapshot(actual));
            }
            boolean exact = placed && declaration.matchesTarget(world, px, y, z, original)
                && (!"assembler_damage18".equals(name) || world.getBlockMetadata(px, y, z) == 18);
            currentRow.setBoolean("nativePlacementReturned", placed);
            currentRow.setBoolean("fullItemIdentityMatches", exact);
            currentRow.setString("placementSupport", exact ? "SUPPORTED" : "UNSUPPORTED");
            if ("assembler_damage18".equals(name)) debugPlacementUnsupported = !exact;
            require(
                exact,
                "Native ItemBlock cannot preserve the declared material identity: " + name
                    + " itemDamage="
                    + original.getItemDamage()
                    + " worldMeta="
                    + world.getBlockMetadata(px, y, z));
        }

        private void productionPlacement(ItemStack material, HologramGTNLElement declaration, boolean expectUnsupported)
            throws IOException {
            require(material != null && declaration != null, "A captured GTNL fixed declaration is required");
            requireOwned(world, actor, x, y, z);
            int px = x + 1;
            currentRow.setString(
                "scope",
                "Controlled single-cell production transaction; full-machine formation and workflow admission are not exercised");
            currentRow.setString(
                "entry",
                "HologramWorldTransactions.operate -> reviewedElement -> StructureUtility.survivalPlaceBlock");
            currentRow.setTag("item", material.writeToNBT(new NBTTagCompound()));
            currentRow.setBoolean("creative", actor.capabilities.isCreativeMode);
            try {
                HologramSession session = new HologramSession(actor, px, y, z, false, 1);
                session.mode = 0;
                session.materialSettings.main = true;
                session.materialSettings.containers = session.materialSettings.me = false;
                session.materialContext = new HologramMaterials.Context(actor, session.materialSettings, session.slot);
                session.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
                HologramCapture.Cell cell = new HologramCapture.Cell(
                    declaration,
                    px,
                    y,
                    z,
                    new ItemStack(Blocks.stone));
                declaration.describeCell(cell);
                cell.candidates = declaration.candidates();
                session.capture.cells.add(cell);
                session.capture.positions.put(HologramValues.position(cell), cell);
                session.pins.put(0, material.copy());
                NBTTagList empty = actor.inventory.writeToNBT(new NBTTagList());
                require(count(material) == 0, "Production payment fixture requires no existing target material");
                boolean missing = HologramWorldTransactions.operate(session, cell, 0);
                currentRow.setBoolean("missingMaterialRejected", !missing);
                currentRow.setString("missingStatus", cell.status);
                require(
                    !missing && world.isAirBlock(px, y, z)
                        && empty.equals(actor.inventory.writeToNBT(new NBTTagList())),
                    "Missing-material production transaction wrote a block or changed inventory");
                int supplySlot = -1;
                for (int slot = 1; slot < actor.inventory.mainInventory.length; slot++)
                    if (actor.inventory.mainInventory[slot] == null) {
                        supplySlot = slot;
                        break;
                    }
                require(supplySlot >= 0, "No free owned actor slot for one supplied casing");
                ItemStack supplied = material.copy();
                supplied.stackSize = 1;
                actor.inventory.setInventorySlotContents(supplySlot, supplied);
                actor.inventory.markDirty();
                NBTTagList funded = actor.inventory.writeToNBT(new NBTTagList());
                require(count(material) == 1, "Fixture must supply exactly one target item");
                boolean placed = HologramWorldTransactions.operate(session, cell, 0);
                currentRow.setBoolean("fundedOperationReturned", placed);
                currentRow.setString("fundedStatus", cell.status);
                currentRow.setString("fundedMessage", session.status);
                if (expectUnsupported) {
                    require(
                        !placed && world.isAirBlock(px, y, z)
                            && funded.equals(actor.inventory.writeToNBT(new NBTTagList())),
                        "Unrepresentable damage18 production path must reject and fully restore world/inventory");
                    currentRow.setBoolean("unsupportedIdentitySafelyRejected", true);
                    currentRow.setInteger("materialDebit", 0);
                } else {
                    require(
                        placed && declaration.matchesTarget(world, px, y, z, material)
                            && count(material) == 0
                            && empty.equals(actor.inventory.writeToNBT(new NBTTagList())),
                        "Funded production path did not place exact casing and debit exactly one item: "
                            + session.status);
                    NBTTagList paid = actor.inventory.writeToNBT(new NBTTagList());
                    boolean replay = HologramWorldTransactions.operate(session, cell, 0);
                    require(
                        replay && declaration.matchesTarget(world, px, y, z, material)
                            && paid.equals(actor.inventory.writeToNBT(new NBTTagList())),
                        "Production replay changed target or debited material again");
                    currentRow.setBoolean("replayNoDebit", true);
                    currentRow.setString("replayStatus", cell.status);
                    currentRow.setInteger("materialDebit", 1);
                }
            } finally {
                // Session construction assigns a tool ID, and the fixture supplies one item; both are scoped setup.
                restoreInventory();
            }
        }

        private int count(ItemStack material) {
            int result = 0;
            for (ItemStack stack : actor.inventory.mainInventory)
                if (stack != null && stack.isItemEqual(material) && ItemStack.areItemStackTagsEqual(stack, material))
                    result += stack.stackSize;
            return result;
        }

        public NBTTagCompound result() {
            return (NBTTagCompound) report.copy();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            if (currentRow != null && "RUNNING".equals(currentRow.getString("status")))
                failCase("Probe stopped before this case completed");
            restoreInventory();
            requireOwned(world, actor, x, y, z);
            if (capture != null) capture.cancelPending();
            for (int dx = 0; dx < 3; dx++) clearSlot(x + dx);
            for (int dx = 0; dx < 3; dx++) require(
                world.isAirBlock(x + dx, y, z) && world.getTileEntity(x + dx, y, z) == null,
                "GTNL fixture did not restore its original empty slot");
            require(
                originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "GTNL fixture inventory differed during cleanup");
            report.setBoolean("restoredOriginalEmptyCells", true);
            report.setBoolean("actorInventoryRestored", true);
            report.setInteger("failedCases", failures);
            report.setBoolean("success", finished && failures == 0);
            closed = true;
        }

        private void clearSlot(int px) throws IOException {
            requireOwned(world, actor, x, y, z);
            Chunk chunk = world.getChunkFromChunkCoords(px >> 4, z >> 4);
            TileEntity old = world.getTileEntity(px, y, z);
            chunk.removeTileEntity(px & 15, y, z & 15);
            if (old != null) world.loadedTileEntityList.remove(old);
            ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
            if (section != null) {
                section.func_150818_a(px & 15, y & 15, z & 15, Blocks.air);
                section.setExtBlockMetadata(px & 15, y & 15, z & 15, 0);
            }
            chunk.setChunkModified();
            world.markBlockForUpdate(px, y, z);
        }
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        if (tile instanceof IGregTechTileEntity && ((IGregTechTileEntity) tile).getMetaTileEntity() != null
            && ((IGregTechTileEntity) tile).getMetaTileEntity()
                .getClass()
                .getName()
                .equals("com.science.gtnl.common.machine.multiblock.AssemblerMatrix")
            && tag.hasKey("storeUUID", 8)) tag.setString("storeUUID", "native-save-nonce");
        return tag;
    }

    private static String fingerprint(HologramCapture capture) {
        StringBuilder text = new StringBuilder();
        for (HologramCapture.Cell cell : capture.cells) {
            text.append(cell.x)
                .append(',')
                .append(cell.y)
                .append(',')
                .append(cell.z)
                .append(':')
                .append(cell.blueprintItem)
                .append(':')
                .append(cell.blueprintDamage)
                .append(':')
                .append(cell.meta)
                .append(':')
                .append(
                    cell.element instanceof HologramGTNLElement ? ((HologramGTNLElement) cell.element).id : cell.role)
                .append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(
                    text.toString()
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hash.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }

    static void suppressNativeTick(WorldServer world, TileEntity tile)
        throws ReflectiveOperationException, IOException {
        Field processing;
        Field pending;
        try {
            processing = field(World.class, "field_147481_N");
        } catch (NoSuchFieldException mapped) {
            processing = field(World.class, "processingLoadedTiles");
        }
        try {
            pending = field(World.class, "field_147484_a");
        } catch (NoSuchFieldException mapped) {
            pending = field(World.class, "addedTileEntityList");
        }
        require(!processing.getBoolean(world), "Fixture controller tick suppression requires the END phase");
        require(
            world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile,
            "Fixture TE is not present in its real chunk");
        Object pendingValue = pending.get(world);
        require(pendingValue instanceof List, "Native pending tile list has an unexpected shape");
        world.loadedTileEntityList.remove(tile);
        ((List<?>) pendingValue).remove(tile);
        require(
            !world.loadedTileEntityList.contains(tile) && !((List<?>) pendingValue).contains(tile)
                && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile,
            "Fixture controller tick suppression changed chunk identity or left a scheduled tick");
    }

    private static void requireOwned(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        require(
            TestLab.isEnabled() && world != null
                && actor != null
                && !world.isRemote
                && world.provider instanceof TestLabWorldProvider
                && actor.worldObj == world
                && !actor.capabilities.isCreativeMode
                && !actor.isDead
                && actor.capabilities.allowEdit
                && ItemNekoHologramProjector.isProjector(actor.getHeldItem())
                && TestLab.isOwnedFixture(world, actor, x, y, z, 3, 1, 1),
            "GTNL probe requires active owned TestLab actor and three leased cells");
        for (Object user : world.playerEntities)
            require(user instanceof FakePlayer, "Human presence stops GTNL fixture mutation");
    }

    private static void require(boolean condition, String detail) throws IOException {
        if (!condition) throw new IOException(detail);
    }

    private static List<Spec> specs() {
        List<Spec> result = new ArrayList<>();
        result.add(
            new Spec("assembler_matrix", "AssemblerMatrix", 1, 1, false, StructureSources.CaptureIntent.NEW_TEMPLATE));
        for (int tier = 1; tier <= 2; tier++) result.add(
            new Spec(
                "large_steam_mixer",
                "steam.LargeSteamMixer",
                tier,
                1,
                false,
                StructureSources.CaptureIntent.NEW_TEMPLATE));
        result.add(
            new Spec("aeep", "AtomicEnergyExcitationPlant", 1, 1, false, StructureSources.CaptureIntent.NEW_TEMPLATE));
        result.add(
            new Spec("aeep", "AtomicEnergyExcitationPlant", 1, 1, true, StructureSources.CaptureIntent.LIVE_SNAPSHOT));
        result.add(
            new Spec("aeep", "AtomicEnergyExcitationPlant", 1, 1, true, StructureSources.CaptureIntent.NEW_TEMPLATE));
        for (int tier = 1; tier <= 3; tier++) result.add(
            new Spec("nuclear_reactor", "NuclearReactor", tier, 1, false, StructureSources.CaptureIntent.NEW_TEMPLATE));
        result.add(
            new Spec(
                "precision_assembler",
                "structuralReconstructionPlan.PrecisionAssembler",
                1,
                1,
                false,
                StructureSources.CaptureIntent.NEW_TEMPLATE));
        result.add(
            new Spec(
                "precision_assembler",
                "structuralReconstructionPlan.PrecisionAssembler",
                10,
                5,
                false,
                StructureSources.CaptureIntent.NEW_TEMPLATE));
        return result;
    }

    private static final class Spec {

        final String family, type;
        final int main, unit;
        final boolean active;
        final StructureSources.CaptureIntent intent;

        Spec(String family, String suffix, int main, int unit, boolean active, StructureSources.CaptureIntent intent) {
            this.family = family;
            this.type = "com.science.gtnl.common.machine.multiblock." + suffix;
            this.main = main;
            this.unit = unit;
            this.active = active;
            this.intent = intent;
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.common.blocks.BlockCasings12;
import gregtech.common.blocks.ItemCasings;
import gregtech.common.tileentities.machines.multi.MTECokeOven;

final class HologramStockCokeOvenProfileRegression {

    private HologramStockCokeOvenProfileRegression() {}

    static void run() {
        int id = HologramStockCokeOvenProfile.CONTROLLER, hatchId = HologramStockCokeOvenProfile.HATCH;
        Block previousCasing = GregTechAPI.sBlockCasings12, previousMachineBlock = GregTechAPI.sBlockMachines;
        var previousController = GregTechAPI.METATILEENTITIES[id];
        var previousHatch = GregTechAPI.METATILEENTITIES[hatchId];
        try {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            // Standalone harness lacks the GT mod preload; use its exact casing/block class without registry side effects.
            if (previousCasing == null) GregTechAPI.sBlockCasings12 = (BlockCasings12) unsafe
                .allocateInstance(BlockCasings12.class);
            if (previousMachineBlock == null) GregTechAPI.sBlockMachines = Blocks.iron_block;
            var controller = new MTECokeOven("gtmb_basic_coke_profile_fixture");
            var hatch = (MTEHatchCokeOven) unsafe.allocateInstance(MTEHatchCokeOven.class);
            GregTechAPI.METATILEENTITIES[id] = controller;
            GregTechAPI.METATILEENTITIES[hatchId] = hatch;
            var profile = HologramStockCokeOvenProfile.inspectRegistered();
            SimpleAssert.that(profile != null && profile.definition == controller.getStructureDefinition()
                && id == 236 && hatchId == 237, "exact #236 static native Coke Oven, not industrial or a synthetic shape");

            IStructureElement<?> shell = null;
            int shells = 0;
            for (IStructureElement<?> element : profile.definition.getStructureFor(HologramStockCokeOvenProfile.PIECE)) {
                IStructureElement<?> leaf = profile.casingLeaf(element);
                if (leaf != null) {
                    if (shell == null) shell = element;
                    SimpleAssert.that(shell == element && HologramCasingFallback.resolve(leaf).block
                        == GregTechAPI.sBlockCasings12 && HologramCasingFallback.resolve(leaf).meta == 0,
                        "every declared Coke shell comes from this exact native chain and brick metadata");
                    shells++;
                }
            }
            SimpleAssert.eq(25, shells, "25 actual native shell declarations, excluding center air and the controller");
            SimpleAssert.that(profile.acceptsSettings(1, new NBTTagCompound())
                && profile.acceptsSettings(3, new NBTTagCompound()), "main stack size does not change this native shape");
            var configured = new NBTTagCompound();
            configured.setInteger("gt_hatch", 2);
            SimpleAssert.that(!profile.acceptsSettings(1, configured), "never suppress an explicit hatch tier channel");

            var tile = (HatchTile) unsafe.allocateInstance(HatchTile.class);
            tile.setWorldObj(HologramPlacementFixtureWorld.create(unsafe));
            tile.setMetaTileID((short) hatchId);
            hatch.setBaseMetaTileEntity(tile);
            int west = ForgeDirection.WEST.ordinal(), east = ForgeDirection.EAST.ordinal();
            var ready = HologramStockCokeOvenProfile.Shell.READY_CASING;
            var matched = HologramStockCokeOvenProfile.Shell.MATCHED_HATCH;
            var unsatisfied = HologramStockCokeOvenProfile.Shell.EXPLICIT_HATCH_UNSATISFIED;
            SimpleAssert.eq(ready, profile.classify(shell, Blocks.air, 0, null, -1, -1, -1),
                "only the unedited empty shell may choose its original native casing fallback");
            SimpleAssert.eq(HologramStockCokeOvenProfile.Shell.MATCHED_CASING,
                profile.classify(shell, GregTechAPI.sBlockCasings12, 0, null, -1, -1, -1),
                "own exact installed casing is not deducted or overwritten");
            SimpleAssert.eq(HologramStockCokeOvenProfile.Shell.BLOCKED,
                profile.classify(shell, GregTechAPI.sBlockCasings12, 1, null, -1, -1, -1),
                "same block, wrong real metadata is not classified as the native Coke brick or replaced");
            SimpleAssert.eq(matched, profile.classify(shell, GregTechAPI.sBlockMachines, 0, tile, -1, -1, -1),
                "actual installed #237 Coke Oven hatch is recognized without mutating addHatch");
            SimpleAssert.eq(matched, profile.classify(shell, GregTechAPI.sBlockMachines, 0, tile, hatchId, 0, west),
                "only an exact chosen hatch ID, stock tier and requested facing is satisfied");
            SimpleAssert.eq(unsatisfied, profile.classify(shell, GregTechAPI.sBlockMachines, 0, tile, hatchId, 0, east),
                "installed hatch in a different required facing is not skipped as satisfied");
            SimpleAssert.eq(unsatisfied, profile.classify(shell, GregTechAPI.sBlockMachines, 0, tile, hatchId, 1, west),
                "unknown stock tier is refused rather than treating ULV as the requested tier");
            SimpleAssert.eq(unsatisfied, profile.classify(shell, Blocks.air, 0, null, hatchId, 0, west),
                "missing explicit hatch never causes an automatic casing or other hatch to be placed");
            SimpleAssert.eq(HologramStockCokeOvenProfile.Shell.UNPROVED,
                profile.classify(StructureUtility.ofBlock(Blocks.iron_block, 0), Blocks.air, 0, null, -1, -1, -1),
                "unrelated element cannot borrow the approved Coke Oven casing proof");
            GregTechAPI.METATILEENTITIES[hatchId] = null;
            SimpleAssert.eq(null, profile.casingLeaf(shell), "registry authority replacement invalidates the proof");
            GregTechAPI.METATILEENTITIES[hatchId] = hatch;
            scopedStockJob(unsafe, controller, hatch);
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("stock native Coke Oven profile", failure);
        } finally {
            GregTechAPI.sBlockCasings12 = previousCasing;
            GregTechAPI.sBlockMachines = previousMachineBlock;
            GregTechAPI.METATILEENTITIES[id] = previousController;
            GregTechAPI.METATILEENTITIES[hatchId] = previousHatch;
        }
    }

    private static void scopedStockJob(sun.misc.Unsafe unsafe, MTECokeOven machine, MTEHatchCokeOven hatchMeta)
        throws ReflectiveOperationException, IOException {
        var previousRoot = HologramConstructionJournal.STORAGE_ROOT.get();
        Path ledger = Files.createTempDirectory("gtmb-stock-coke-native-");
        EntityPlayerMP player = null;
        try {
            HologramConstructionJournal.STORAGE_ROOT.set(ledger);
            var world = HologramPlacementFixtureWorld.create(unsafe);
            player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "RealStockCokeEngine");
            var network = player.playerNetServerHandler.netManager;
            network.setNetHandler(player.playerNetServerHandler);
            player.capabilities.allowEdit = true;
            player.capabilities.isCreativeMode = false;
            player.posX = player.posZ = .5;
            player.posY = 64;
            var casing = GregTechAPI.sBlockCasings12;
            Item item = Item.getItemFromBlock(casing);
            if (item == null) {
                // The isolated functional harness does not preload GregTech's blocks. Install its exact classes;
                // both block mutation and material settlement still run the real ItemCasings/ItemBlock path.
                String fixtureName = "gtmb_test:stock_coke_real_item";
                // The common runner also installs dummy GT blocks. Allocate a genuinely free matching id;
                // never overwrite either registry, even if only one side of a slot is occupied.
                int slot = 3823;
                while (slot >= 3776 && (Block.getBlockById(slot) != Blocks.air || Item.getItemById(slot) != null))
                    slot--;
                Object existing = Block.blockRegistry.getObject(fixtureName);
                SimpleAssert.that(slot >= 3776 && Block.getBlockById(slot) == Blocks.air
                    && Item.getItemById(slot) == null && (existing == null || existing == Blocks.air)
                    && Item.itemRegistry.getObject(fixtureName) == null, "unused matching fixture id and name");
                // As in the existing native casing/coil fixtures: Unsafe skips Block's initializer.
                // Use the registry's real Forge delegate for this exact casing, never a substitute block.
                var delegate = Block.class.getDeclaredField("delegate");
                delegate.setAccessible(true);
                if (delegate.get(casing) == null) delegate.set(
                    casing, ((FMLControlledNamespacedRegistry) Block.blockRegistry).getDelegate(casing, Block.class));
                SimpleAssert.that(delegate.get(casing) != null, "stock fixture has its real Forge registry delegate");
                casing.stepSound = Blocks.iron_block.stepSound;
                Block.blockRegistry.addObject(slot, fixtureName, casing);
                item = new ItemCasings(casing);
                Item.itemRegistry.addObject(slot, fixtureName, item);
                SimpleAssert.that(Block.getBlockById(slot) == casing && Item.getItemById(slot) == item
                    && Item.getItemFromBlock(casing) == item, "registered exact stock fixture block and item pair");
            }
            var front = BaseMetaTileEntity.class.getDeclaredField("mFacing");
            front.setAccessible(true);
            var control = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
            control.setWorldObj(world);
            control.xCoord = 0;
            control.yCoord = 64;
            control.zCoord = 0;
            control.setMetaTileID((short) HologramStockCokeOvenProfile.CONTROLLER);
            front.set(control, ForgeDirection.NORTH);
            machine.setBaseMetaTileEntity(control);
            var liveHatch = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
            liveHatch.setWorldObj(world);
            liveHatch.setMetaTileID((short) HologramStockCokeOvenProfile.HATCH);
            front.set(liveHatch, ForgeDirection.WEST);
            hatchMeta.setBaseMetaTileEntity(liveHatch);
            ExtendedFacing extended = machine.getExtendedFacing();
            int[] chosen = new int[3], first = new int[3];
            extended.getWorldOffset(new int[] { 1, 1, 2 }, chosen);
            extended.getWorldOffset(new int[] { -1, -1, 0 }, first);
            liveHatch.xCoord = chosen[0];
            liveHatch.yCoord = 64 + chosen[1];
            liveHatch.zCoord = chosen[2];
            String hatchPos = HologramPlacementFixtureWorld.position(liveHatch.xCoord, liveHatch.yCoord, liveHatch.zCoord);
            String firstPos = HologramPlacementFixtureWorld.position(first[0], 64 + first[1], first[2]);
            world.occupants = new HashMap<String, TileEntity>();
            world.occupants.put("0,64,0", control);
            world.occupants.put(hatchPos, liveHatch);
            world.seed(0, 64, 0, GregTechAPI.sBlockMachines, 0);
            world.seed(liveHatch.xCoord, liveHatch.yCoord, liveHatch.zCoord, GregTechAPI.sBlockMachines, 0);
            player.inventory.mainInventory[1] = new ItemStack(item, 2, 0);

            var session = new HologramSession(player, 0, 64, 0);
            session.materialSettings.main = true;
            session.materialSettings.containers = session.materialSettings.me = false;
            HologramWorkspace.activate(player.getUniqueID(), session);
            HologramWorkspace.publishActionEndpoint(player, false);
            var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            SimpleAssert.that(gate != null && control.getMetaTileEntity() == machine
                && liveHatch.getMetaTileEntity() == hatchMeta, "real #236/237 native ownership and exact live play endpoint");
            var selected = new HologramStockNativeConstruction.HatchChoice(chosen[0], chosen[1], chosen[2],
                HologramStockCokeOvenProfile.HATCH, 0, ForgeDirection.WEST.ordinal());
            var wrongFacing = new HologramStockNativeConstruction.HatchChoice(chosen[0], chosen[1], chosen[2],
                HologramStockCokeOvenProfile.HATCH, 0, ForgeDirection.EAST.ordinal());
            var consent = HologramIncrementalNativePlacement.Consent.PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK;
            refuse(endpoint, session, request(1), HologramStockCokeOvenProfile.SOURCE_ID, consent, wrongFacing);
            refuse(endpoint, session, request(2), "gregtech:ebf", consent, selected);
            refuse(endpoint, session, request(3), HologramStockCokeOvenProfile.SOURCE_ID, null, selected);
            SimpleAssert.that(world.writes().isEmpty() && player.inventory.mainInventory[1].stackSize == 2,
                "wrong pose, unsupported source or absent explicit prefix consent have no effects");

            var initial = request(4);
            var job = HologramStockNativeConstruction.begin(
                endpoint, session, initial, HologramStockCokeOvenProfile.SOURCE_ID, 1, consent, selected);
            SimpleAssert.that(session.capture == null && job.visited() == 0 && world.writes().isEmpty()
                && HologramWorkspace.legacyConstructionRefusal(player) != null,
                "admission uses shared authority and walks no live shape or quote before first work");
            refuse(endpoint, session, initial, HologramStockCokeOvenProfile.SOURCE_ID, consent, selected);
            world.arm();
            SimpleAssert.eq(HologramStockNativeConstruction.State.ACTIVE, job.drain(Long.MAX_VALUE, 32, 1),
                "first stock checkpoint stays active after exactly one committed write");
            SimpleAssert.that(job.placed() == 1 && job.visited() == 1 && world.writes().size() == 1
                && firstPos.equals(world.writes().get(0)) && world.getBlock(first[0], 64 + first[1], first[2]) == casing
                && player.inventory.mainInventory[1].stackSize == 1 && session.capture == null
                && !HologramConstructionJournal.pending(player),
                "real stock first casing and one real debit before advancing any other declaration row");
            SimpleAssert.eq(HologramStockNativeConstruction.State.STOPPED, job.drain(Long.MAX_VALUE, 32, 32),
                "exhausted exact backpack stops with the already placed prefix, not completed");
            SimpleAssert.that(job.placed() == 2 && world.writes().size() == 2
                && player.inventory.mainInventory[1] == null && !job.reason().isEmpty()
                && gate.query(endpoint.constructionScope, initial).outcome.committedTransactions == 2,
                "two actual world writes and two exact original-slot debits, no duplicate work");
            refuse(endpoint, session, initial, HologramStockCokeOvenProfile.SOURCE_ID, consent, selected);

            player.inventory.mainInventory[1] = new ItemStack(item, 23, 0);
            var resume = request(5);
            job = HologramStockNativeConstruction.begin(
                endpoint, session, resume, HologramStockCokeOvenProfile.SOURCE_ID, 1, consent, selected);
            HologramStockNativeConstruction.State result = HologramStockNativeConstruction.State.ACTIVE;
            for (int tick = 0; tick < 8 && result == HologramStockNativeConstruction.State.ACTIVE; tick++)
                result = job.drain(Long.MAX_VALUE, 32, 8);
            SimpleAssert.eq(HologramStockNativeConstruction.State.COMPLETE, result, "3D native cursor reached completion");
            SimpleAssert.that(job.placed() == 22 && world.writes().size() == 24
                && player.inventory.mainInventory[1].stackSize == 1 && !world.writes().contains(hatchPos)
                && world.getTileEntity(liveHatch.xCoord, liveHatch.yCoord, liveHatch.zCoord) == liveHatch
                && liveHatch.getFrontFacing() == ForgeDirection.WEST && session.capture == null
                && !HologramConstructionJournal.pending(player),
                "native 25 shells minus one installed matching hatch: exact 24 casing debits across two confirmations");
            var stale = HologramStockNativeConstruction.begin(
                endpoint, session, request(6), HologramStockCokeOvenProfile.SOURCE_ID, 1, consent, selected);
            front.set(liveHatch, ForgeDirection.EAST);
            SimpleAssert.eq(HologramStockNativeConstruction.State.STOPPED, stale.drain(Long.MAX_VALUE, 32, 32),
                "installed hatch turned after authorization cannot complete, recapture or start placement");
            SimpleAssert.that(stale.placed() == 0 && stale.visited() == 0 && world.writes().size() == 24
                && player.inventory.mainInventory[1].stackSize == 1, "stale selected hatch has no world or stock effect");
            // Reopen one known casing cell; physically alter the native authority while Forge sees that casing
            // installed, without touching the old base object's cached links/valid bits or vetoing the event.
            front.set(liveHatch, ForgeDirection.WEST);
            world.setBlock(first[0], 64 + first[1], first[2], Blocks.air, 0, 3);
            verifyForgeRollback(unsafe, world, player, session, endpoint, selected, request(7), first,
                () -> world.occupants.remove(hatchPos));
            SimpleAssert.that(!liveHatch.isInvalid()
                && world.getTileEntity(liveHatch.xCoord, liveHatch.yCoord, liveHatch.zCoord) == null,
                "rollback does not restore the independently removed physical hatch");
            world.occupants.put(hatchPos, liveHatch);
            var replacement = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
            replacement.setWorldObj(world);
            replacement.xCoord = 0;
            replacement.yCoord = 64;
            replacement.zCoord = 0;
            replacement.setMetaTileID((short) HologramStockCokeOvenProfile.CONTROLLER);
            front.set(replacement, ForgeDirection.NORTH);
            var otherController = new MTECokeOven("gtmb_coke_forge_replacement");
            otherController.setBaseMetaTileEntity(replacement);
            verifyForgeRollback(unsafe, world, player, session, endpoint, selected, request(8), first,
                () -> world.occupants.put("0,64,0", replacement));
            SimpleAssert.that(!control.isInvalid() && world.getTileEntity(0, 64, 0) == replacement,
                "an identical model at the original coordinate does not inherit this job's controller authority");
            world.occupants.put("0,64,0", control);
            double originalX = player.posX;
            var movedActor = player;
            try {
                verifyForgeRollback(unsafe, world, player, session, endpoint, selected, request(9), first,
                    () -> movedActor.posX = 8.75);
                SimpleAssert.that(player.worldObj == world && player.playerNetServerHandler == endpoint.connection
                    && player.posX == 8.75, "same-world Forge movement survives the casing rollback");
            } finally {
                player.posX = originalX;
            }
            verifyConstructorFences(world, player, session, endpoint, selected);
            verifyEntryAdapter(world, player, session, endpoint, selected, item, first);
            HologramStockNativeServerRegression.run(unsafe, world, player, session, endpoint, selected, item, first);
            try (var files = Files.list(ledger)) {
                SimpleAssert.eq(0L, files.count(), "normal scoped writes do not force durability files per casing");
            }
            HologramWorkspace.revokeActionEndpoint(player);
        } finally {
            if (player != null && player.playerNetServerHandler != null)
                player.playerNetServerHandler.netManager.channel().close();
            HologramWorkspace.clear();
            if (previousRoot == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previousRoot);
            Files.deleteIfExists(ledger);
        }
    }

    private static void verifyConstructorFences(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession previous, HologramWorkspace.ActionEndpoint endpoint,
        HologramStockNativeConstruction.HatchChoice choice) {
        var baseInventory = player.inventory;
        var hooked = new HookedInventory(player);
        hooked.mainInventory = baseInventory.mainInventory;
        hooked.currentItem = baseInventory.currentItem;
        player.inventory = hooked;
        var projector = hooked.mainInventory[previous.slot];
        var tag = projector.getTagCompound();
        final String toolKey = HologramSessionKeys.TOOL_ID;
        String originalId = tag.getString(toolKey);
        int writes = world.writes().size();
        var partial = HologramStockNativeEntry.Funding.EXPLICIT_AVAILABLE_STOCK;
        try {
            var predecessorIntent = stockIntent(endpoint, previous, choice, request(10), partial);
            var captured = HologramStockNativeEntry.ingress(endpoint, predecessorIntent);
            SimpleAssert.that(captured != null, "predecessor test binds a real current projector at ingress");
            long epoch = previous.editorWorkspaceEpoch, plan = previous.planRevision;
            hooked.dirty = () -> {
                previous.editorWorkspaceEpoch++;
                previous.planRevision++;
            };
            refuseEntry(captured);
            SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous
                && previous.editorWorkspaceEpoch == epoch + 1 && previous.planRevision == plan + 1,
                "the real session-constructor callback revised the same predecessor; it was not replaced or re-pinned");

            var dirtyIntent = stockIntent(endpoint, previous, choice, request(11), partial);
            captured = HologramStockNativeEntry.ingress(endpoint, dirtyIntent);
            long cleanRevision = previous.planRevision, cleanEpoch = previous.editorWorkspaceEpoch;
            hooked.dirty = () -> previous.blueprintDirty = true;
            refuseEntry(captured);
            SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous && previous.blueprintDirty
                && previous.planRevision == cleanRevision && previous.editorWorkspaceEpoch == cleanEpoch,
                "constructor callback creates unsaved work without advancing revision; the old active view survives");
            previous.blueprintDirty = false;

            var toolIntent = stockIntent(endpoint, previous, choice, request(12), partial);
            captured = HologramStockNativeEntry.ingress(endpoint, toolIntent);
            SimpleAssert.that(captured != null, "tool reentry test first captures the canonical original id");
            String otherTool = UUID.randomUUID().toString();
            hooked.dirty = () -> tag.setString(toolKey, otherTool);
            refuseEntry(captured);
            SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous
                && tag.getString(toolKey).equals(otherTool),
                "constructor may not adopt a substituted canonical id on the same projector reference");

            tag.setString(toolKey, "");
            var blank = stockIntent(endpoint, previous, choice, request(13), partial);
            SimpleAssert.eq(null, HologramStockNativeEntry.ingress(endpoint, blank), "blank tools cannot mint on this path");
            SimpleAssert.that(tag.getString(toolKey).isEmpty() && HologramWorkspace.find(endpoint.actor) == previous,
                "refused blank tool has not mutated its identity or opened a temporary construction workspace");
            tag.setString(toolKey, originalId);

            var afterReserve = stockIntent(endpoint, previous, choice, request(14), partial);
            captured = HologramStockNativeEntry.ingress(endpoint, afterReserve);
            long oldEpoch = previous.editorWorkspaceEpoch, oldPlan = previous.planRevision;
            boolean[] fired = { false };
            hooked.heldRead = () -> {
                var active = HologramWorkspace.find(endpoint.actor);
                if (!fired[0] && active != null && active != previous) {
                    fired[0] = true;
                    SimpleAssert.that(active.stockNativePrivate && !active.open,
                        "private marker is installed during reservation before the driver is published");
                    HologramService.openSettings(player);
                    SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == active && !active.open,
                        "an attempted settings open cannot expose the reserved pre-publication scratch");
                    previous.editorWorkspaceEpoch++;
                    previous.planRevision++;
                }
            };
            refuseEntry(captured);
            SimpleAssert.that(fired[0] && HologramWorkspace.find(endpoint.actor) == null
                && previous.editorWorkspaceEpoch == oldEpoch + 1 && previous.planRevision == oldPlan + 1
                && world.writes().size() == writes && !HologramWorkspace.constructionGate(endpoint).busy(endpoint.actor),
                "failed post-reserve admission removes its own seat without restoring a revised predecessor or leaking lease");

            hooked.heldRead = null;
            HologramWorkspace.activate(endpoint.actor, previous);
            var newer = new HologramSession(player, previous.x, previous.y, previous.z, true, previous.side);
            var competingSeat = stockIntent(endpoint, previous, choice, request(15), partial);
            captured = HologramStockNativeEntry.ingress(endpoint, competingSeat);
            int[] armedReads = { -1 };
            hooked.dirty = () -> armedReads[0] = 0;
            hooked.heldRead = () -> {
                if (armedReads[0] >= 0 && ++armedReads[0] == 2) {
                    armedReads[0] = -1;
                    HologramWorkspace.activate(endpoint.actor, newer);
                }
            };
            refuseEntry(captured);
            SimpleAssert.that(armedReads[0] == -1 && HologramWorkspace.find(endpoint.actor) == newer
                && world.writes().size() == writes && !HologramWorkspace.constructionGate(endpoint).busy(endpoint.actor),
                "real held-slot getter cannot make an already superseded workspace disappear under stock reservation");
        } finally {
            player.inventory = baseInventory;
            tag.setString(toolKey, originalId);
        }
        HologramWorkspace.activate(endpoint.actor, previous);
    }

    private static final class HookedInventory extends InventoryPlayer {

        Runnable dirty, heldRead;

        HookedInventory(EntityPlayerMP player) {
            super(player);
        }

        @Override
        public void markDirty() {
            super.markDirty();
            Runnable once = dirty;
            dirty = null;
            if (once != null) once.run();
        }

        @Override
        public ItemStack getCurrentItem() {
            if (heldRead != null) heldRead.run();
            return super.getCurrentItem();
        }
    }

    private static void verifyEntryAdapter(HologramPlacementFixtureWorld world, EntityPlayerMP player,
        HologramSession previous, HologramWorkspace.ActionEndpoint endpoint,
        HologramStockNativeConstruction.HatchChoice choice, Item casingItem, int[] first) throws IOException {
        var full = HologramStockNativeEntry.Funding.REQUIRE_FULL_BOUND;
        var partial = HologramStockNativeEntry.Funding.EXPLICIT_AVAILABLE_STOCK;
        var stock = player.inventory.mainInventory;
        int writes = world.writes().size();
        stock[1] = new ItemStack(casingItem, 23, 0);
        stock[2] = new ItemStack(casingItem, 32, 1);
        var insufficient = stockIntent(endpoint, previous, choice, request(16), full);
        refuseEntry(HologramStockNativeEntry.ingress(endpoint, insufficient));
        SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous && world.writes().size() == writes
            && stock[1].stackSize == 23 && stock[2].stackSize == 32,
            "standard intent only accepts the exact 24-brick bound, never silently switches to partial");
        stock[1] = new ItemStack(casingItem, 24, 0);
        refuseEntry(HologramStockNativeEntry.ingress(endpoint, insufficient));
        SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous && world.writes().size() == writes,
            "a denied request cannot become a later start after restocking");

        var changedHeld = stockIntent(endpoint, previous, choice, request(17), partial);
        var queued = HologramStockNativeEntry.ingress(endpoint, changedHeld);
        SimpleAssert.that(queued != null, "capture held-tool identity before the scheduled entry");
        ItemStack original = stock[previous.slot];
        stock[previous.slot] = original.copy();
        refuseEntry(queued);
        stock[previous.slot] = original;
        SimpleAssert.that(HologramWorkspace.find(endpoint.actor) == previous && world.writes().size() == writes,
            "a queued short confirmation cannot transfer to a replacement held stack");

        stock[1] = new ItemStack(casingItem, 1, 0);
        var partialIntent = stockIntent(endpoint, previous, choice, request(18), partial);
        var entry = HologramStockNativeEntry.begin(HologramStockNativeEntry.ingress(endpoint, partialIntent));
        var reserved = HologramWorkspace.find(endpoint.actor);
        SimpleAssert.that(reserved != null && reserved != previous && reserved.capture == null
            && world.writes().size() == writes && stock[1].stackSize == 1,
            "confirmed entry reserves an empty exact temporary session without preparing a geometry plan");
        SimpleAssert.that(!entry.stop(endpoint, request(19)), "different correlated request cannot stop the native entry");
        entry.tick(Long.MAX_VALUE, 32, 1);
        SimpleAssert.that(entry.placed() == 1 && stock[1] == null && world.writes().size() == writes + 1,
            "explicit partial short intent reaches the same actual world and debit transaction");
        SimpleAssert.that(entry.stop(endpoint, partialIntent.request())
            && HologramWorkspace.find(endpoint.actor) == previous,
            "exact stop preserves committed cells and restores only the original workspace");
        refuseEntry(HologramStockNativeEntry.ingress(endpoint, partialIntent));

        HologramWorkspace.rollbackCompactNative(null, previous);
        SimpleAssert.eq(null, HologramWorkspace.find(endpoint.actor), "physical local opening has no server workspace");
        world.setBlock(first[0], 64 + first[1], first[2], Blocks.air, 0, 3);
        stock[1] = new ItemStack(casingItem, 24, 0);
        var exactFull = stockIntent(endpoint, previous, choice, request(20), full);
        entry = HologramStockNativeEntry.begin(HologramStockNativeEntry.ingress(endpoint, exactFull));
        SimpleAssert.that(HologramWorkspace.find(endpoint.actor) != null && stock[1].stackSize == 24,
            "fresh client-local opening needs no previous editor, quote or geometry capture");
        var result = HologramStockNativeConstruction.State.ACTIVE;
        for (int i = 0; i < 8 && result == HologramStockNativeConstruction.State.ACTIVE; i++)
            result = entry.tick(Long.MAX_VALUE, 32, 4);
        SimpleAssert.that(result == HologramStockNativeConstruction.State.COMPLETE && entry.placed() == 1
            && stock[1].stackSize == 23 && stock[2].stackSize == 32
            && HologramWorkspace.find(endpoint.actor) == null,
            "fully-funded exact short confirmation closed its transient scope and charged only the actual missing cell");
    }

    private static HologramStockNativeEntry.Intent stockIntent(HologramWorkspace.ActionEndpoint endpoint,
        HologramSession original, HologramStockNativeConstruction.HatchChoice choice,
        HologramConstructionTaskGate.Request request, HologramStockNativeEntry.Funding funding) {
        return new HologramStockNativeEntry.Intent(request, endpoint.constructionScope.generation,
            HologramStockCokeOvenProfile.SOURCE_ID, HologramStockCokeOvenProfile.CONTROLLER,
            original.x, original.y, original.z, original.side, original.slot, original.facing, 1, false, false, 0,
            funding, choice);
    }

    private static void refuseEntry(HologramStockNativeEntry.Submission submission) {
        try {
            HologramStockNativeEntry.begin(submission);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("short native intent must fail closed");
    }

    private static void verifyForgeRollback(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world,
        EntityPlayerMP player, HologramSession session, HologramWorkspace.ActionEndpoint endpoint,
        HologramStockNativeConstruction.HatchChoice selected, HologramConstructionTaskGate.Request request,
        int[] first, Runnable changeAuthority) throws IOException, ReflectiveOperationException {
        var job = HologramStockNativeConstruction.begin(endpoint, session, request,
            HologramStockCokeOvenProfile.SOURCE_ID, 1,
            HologramIncrementalNativePlacement.Consent.PLACE_SAFE_CELLS_WITH_AVAILABLE_STOCK, selected);
        Block casing = GregTechAPI.sBlockCasings12;
        int x = first[0], y = 64 + first[1], z = first[2], writes = world.writes().size();
        var oldStock = player.inventory.mainInventory[1];
        int[] events = { 0 };
        var eventField = MinecraftForge.class.getDeclaredField("EVENT_BUS");
        Object busBase = unsafe.staticFieldBase(eventField);
        long busOffset = unsafe.staticFieldOffset(eventField);
        EventBus previous = MinecraftForge.EVENT_BUS;
        unsafe.putObjectVolatile(busBase, busOffset, new EventBus() {
            @Override
            public boolean post(Event event) {
                if (events[0] == 0 && event instanceof BlockEvent.PlaceEvent place && place.player == player
                    && place.world == world && place.x == x && place.y == y && place.z == z
                    && world.getBlock(x, y, z) == casing) {
                    events[0]++;
                    SimpleAssert.that(player.inventory.mainInventory[1] == oldStock && oldStock.stackSize == 1,
                        "the real stock casing was installed in Forge while the original exact inventory is untouched");
                    changeAuthority.run();
                }
                return false;
            }
        });
        try {
            SimpleAssert.eq(HologramStockNativeConstruction.State.STOPPED, job.drain(Long.MAX_VALUE, 32, 1),
                "changed native authority cannot commit an otherwise valid casing");
        } finally {
            unsafe.putObjectVolatile(busBase, busOffset, previous);
        }
        SimpleAssert.that(events[0] == 1 && world.writes().size() == writes + 2 && world.getBlock(x, y, z) == Blocks.air
            && job.placed() == 0 && player.inventory.mainInventory[1] == oldStock && oldStock.stackSize == 1
            && !HologramConstructionJournal.pending(player) && session.capture == null
            && HologramWorkspace.constructionGate(endpoint).query(endpoint.constructionScope, request)
                .outcome.committedTransactions == 0,
            "real world setter then rollback, no debit, no settlement leakage and no false committed prefix");
    }

    private static HologramConstructionTaskGate.Request request(long sequence) {
        return new HologramConstructionTaskGate.Request(
            UUID.randomUUID(), sequence, HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE);
    }

    private static void refuse(HologramWorkspace.ActionEndpoint endpoint, HologramSession session,
        HologramConstructionTaskGate.Request request, String source, HologramIncrementalNativePlacement.Consent consent,
        HologramStockNativeConstruction.HatchChoice selected) {
        try {
            HologramStockNativeConstruction.begin(endpoint, session, request, source, 1, consent, selected);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unconfirmed/unmatched/replayed native stock intent was admitted");
    }

    private static final class HatchTile extends BaseMetaTileEntity {

        @Override
        public ForgeDirection getFrontFacing() {
            return ForgeDirection.WEST;
        }
    }
}

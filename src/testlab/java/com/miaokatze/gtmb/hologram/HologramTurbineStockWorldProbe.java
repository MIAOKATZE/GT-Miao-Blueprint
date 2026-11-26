// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Hatch;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Offset;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.register.ProjectorRegistry;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/**
 * Real loaded world and connected actor, production Action wire and Lifecycle stock scheduler. No physical click claim.
 */
public final class HologramTurbineStockWorldProbe {

    public static final String FLAG = "gtmb.testlab.ownedTurbineStockWorld";
    private static final String TYPE = "com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray";
    public static final boolean BASIC = Boolean.getBoolean(FLAG + "BasicPreset");
    private final UUID actorId, toolId = UUID.randomUUID();
    private final java.util.Set<Offset> nativeHatchSites = new java.util.HashSet<>();
    private final Map<Integer, ItemStack> basicStock = new LinkedHashMap<>();
    private volatile BasicSite basicSite;
    private volatile BasicInput basicInput;

    @Desugar
    public record BasicSite(int phase, UUID actor, UUID tool, int x, int y, int z, int lowChunkX, int lowChunkZ,
        int highChunkX, int highChunkZ, List<Offset> verifiedMissing) {}

    @Desugar
    private record BasicInput(BasicSite site, HologramControllerTarget.Projection projection,
        HologramNativePresetIndex.Prepared plan) {}

    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Offset> solid = new ArrayList<>();
    private final List<Hatch> hatches = new ArrayList<>();
    private final List<ItemStack> hatchStock = Arrays
        .asList(ItemList.Hatch_Input_LV.get(1), ItemList.Hatch_Output_LV.get(1), ItemList.Hatch_Dynamo_LV.get(1));
    private final java.util.concurrent.ConcurrentLinkedQueue<HologramStockNativeWire.Reply> incoming = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile Outbound outbound;

    @Desugar
    public record Outbound(Confirmation confirmation, boolean afterProbe) {}

    private HologramStockNativeWire.Reply last;
    private volatile HologramStockNativeWire.Key expected;
    private WorldServer world;
    private EntityPlayerMP actor;
    private MTEEnhancedMultiBlockBase<?> machine;
    private BaseMetaTileEntity base;
    private ExtendedFacing facing;
    private int x, y, z, stage, ticks;
    private volatile long submitted;
    private NBTTagList starterInventory, creativeInventory, repairedInventory;
    private ItemStack fixtureTool, fixtureHatch;
    private int originalSlot, toolSlot = -1, stockSlot = -1;
    private GameType originalGameType;
    private Object[] oldHatches;
    private volatile boolean done;

    public HologramTurbineStockWorldProbe(UUID actorId) {
        this.actorId = actorId;
    }

    public Outbound outbound() {
        return outbound;
    }

    public void beginSent(Outbound current) {
        require(current == outbound && current.afterProbe(), "Wrong production BEGIN");
        submitted = System.nanoTime();
    }

    /** Bound by the TestLab client to its exact live NetHandler before any fixture request is sent. */
    public void nativeReply(HologramStockNativeWire.Reply reply) {
        var key = expected;
        if (key != null && key.equals(reply.key())) incoming.add(reply);
    }

    public BasicSite basicSite() {
        return basicSite;
    }

    /** Called only by the same integrated client's owned fixture with the real published native target. */
    public void basicPrepared(BasicSite site, HologramControllerTarget.Projection projection,
        HologramNativePresetIndex.Prepared plan) {
        require(
            BASIC && site != null
                && site == basicSite
                && basicInput == null
                && plan != null
                && projection.metadata()
                    .getBoolean("projectionDataComplete"),
            "Wrong native Basic fixture handoff");
        basicInput = new BasicInput(site, projection, plan);
    }

    public boolean done() {
        return done;
    }

    public Map<String, Object> receipt() {
        require(done, "Cannot publish incomplete probe");
        return new LinkedHashMap<>(evidence);
    }

    /**
     * Caller has already matched the private, fresh save path, host lease and Core world ownership. Only server thread.
     */
    public void step(WorldServer owned) throws Exception {
        if (done) return;
        try {
            advance(owned);
        } catch (Exception | LinkageError failure) {
            try {
                releaseFixtureInventory();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void advance(WorldServer owned) throws Exception {
        if (world == null) {
            if (owned.playerEntities.isEmpty()) return;
            require(
                owned.playerEntities.size() == 1 && owned.playerEntities.get(0) instanceof EntityPlayerMP real
                    && !(real instanceof FakePlayer)
                    && real.getUniqueID()
                        .equals(actorId),
                "Wrong owned actor");
            actor = (EntityPlayerMP) owned.playerEntities.get(0);
            if (actor.ticksExisted < 60) return;
            require(
                Boolean.TRUE.equals(
                    Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                        .getMethod("owns", net.minecraft.world.World.class)
                        .invoke(null, owned)),
                "Not a TestLab-owned world");
            world = owned;
            setup();
            if (BASIC) requestBasic(1);
            else submit(1);
            return;
        }
        require(
            world == owned && actor.worldObj == world
                && actor.getUniqueID()
                    .equals(actorId)
                && world.playerEntities.size() == 1
                && world.playerEntities.get(0) == actor
                && !actor.isDead
                && ++ticks < 6000,
            "World/actor/timeout");
        if (basicSite != null) {
            if (basicInput != null) takeBasic();
            return;
        }
        drainProductionReplies();
        if (last == null) return;
        if (last.phase() == Phase.ACTIVE || last.phase() == Phase.ACCEPTED) {
            if (stage == 1 && last.placed() > 0 && !evidence.containsKey("firstObservedOccupiedTarget")) {
                for (Offset p : solid) if (!air(p)) {
                    evidence.put("firstObservedOccupiedTarget", Arrays.asList(p.x(), p.y(), p.z()));
                    evidence.put("firstObservedNanos", System.nanoTime() - submitted);
                    evidence.put("firstWorldObservationPhase", "ACTIVE");
                    break;
                }
            }
            return;
        }
        require(
            last.phase() == Phase.COMPLETE && last.reason()
                .isEmpty(),
            "stage=" + stage
                + " native="
                + last.phase()
                + " reason="
                + last.reason()
                + " placed="
                + last.placed()
                + " hatchEvidence="
                + terminalHatchEvidence());
        if (stage == 1) {
            long now = System.nanoTime() - submitted;
            evidence.putIfAbsent("creativeCompleteReplyNanos", now);
            // Observe the GT tile's normal mMachine update before scan/assertions or checkMachine.
            if (machine.mMachine) evidence.putIfAbsent("creativeNaturallyFormedNanos", now);
            long complete = (Long) evidence.get("creativeCompleteReplyNanos");
            if (!machine.mMachine && now - complete < 10_000_000_000L) return;
            evidence.put("creativeNaturallyFormed", machine.mMachine);
            require(last.placed() == solid.size(), "First build wrote " + last.placed() + ", expected " + solid.size());
            for (Offset p : solid) require(!air(p), "Missing required native solid " + p);
            // A very fast build can complete without an intermediate ACTIVE being observed.
            if (!evidence.containsKey("firstObservedOccupiedTarget"))
                evidence.put("firstWorldObservationPhase", "COMPLETE");
            if (!evidence.containsKey("firstObservedOccupiedTarget")) evidence.put(
                "firstObservedOccupiedTarget",
                Arrays.asList(
                    solid.get(0)
                        .x(),
                    solid.get(0)
                        .y(),
                    solid.get(0)
                        .z()));
            verifyHatches();
            require(creativeInventory.equals(inventorySnapshot()), "Creative debit");
            requireFormed();
            evidence.put("creativeActualPlaced", last.placed());
            if (BASIC) evidence.put("basicCreativeCommitted", realBasicWorld());
            evidence.put("creativePostAssertionNanos", System.nanoTime() - submitted);
            oldHatches = new Object[] { tile(0), tile(1), tile(2) };
            if (BASIC) {
                removeBasicHatchesAndStock();
                requestBasic(2);
                return;
            }
            var p = hatches.get(0)
                .position();
            require(
                world.setBlockToAir(x + p.x(), y + p.y(), z + p.z()) && air(p),
                "Could not remove exact missing hatch");
            enableSurvivalStock();
            require(actor.inventory.getStackInSlot(stockSlot) == null, "Reserved repair slot was occupied");
            fixtureHatch = hatchStock.get(0)
                .copy();
            fixtureHatch.stackSize = 2;
            actor.inventory.setInventorySlotContents(stockSlot, fixtureHatch);
            actor.inventoryContainer.detectAndSendChanges();
            submit(2);
        } else if (stage == 2 && BASIC) {
            require(last.placed() == 3, "Basic stock repair must place all three groups");
            for (var entry : basicStock.entrySet()) {
                ItemStack item = actor.inventory.getStackInSlot(entry.getKey());
                require(
                    sameBasicStock(item, entry.getValue()) && item.stackSize == 1,
                    "Basic stock repair must debit exactly one of each distinct quoted hatch");
            }
            verifyHatches();
            requireFormed();
            evidence.put("basicStockCommitted", realBasicWorld());
            evidence.put("survivalRepairActualPlaced", last.placed());
            repairedInventory = inventorySnapshot();
            submit(3);
        } else if (stage == 2) {
            ItemStack remaining = actor.inventory.getStackInSlot(stockSlot);
            require(
                last.placed() == 1 && sameFixtureHatch(remaining) && remaining.stackSize == 1,
                "Missing-only survival repair must debit one from the reserved stock slot");
            verifyHatches();
            require(
                tile(0) != oldHatches[0] && tile(1) == oldHatches[1] && tile(2) == oldHatches[2],
                "Repair replaced a satisfied hatch or failed to replace missing hatch");
            requireFormed();
            repairedInventory = inventorySnapshot();
            evidence.put("survivalRepairActualPlaced", last.placed());
            submit(3);
        } else {
            require(
                last.placed() == 0 && repairedInventory.equals(inventorySnapshot()),
                "Satisfied confirmation rewrote or debited");
            verifyHatches();
            requireFormed();
            evidence.put("satisfiedPlaced", 0);
            evidence.put("nativeFormationErrors", 0);
            evidence.put("noSecondDebit", true);
            releaseFixtureInventory();
            require(
                starterInventory.equals(inventorySnapshot()) && actor.inventory.currentItem == originalSlot,
                "Starter inventory or originally selected slot changed");
            evidence.put("starterInventoryPreserved", true);
            evidence.put("starterSlotRestored", true);
            done = true;
        }
    }

    private void setup() throws Exception {
        require(
            GregTechAPI.METATILEENTITIES[14850] != null && TYPE.equals(
                GregTechAPI.METATILEENTITIES[14850].getClass()
                    .getName()),
            "Installed GTSR turbine missing");
        require(actor.worldObj == world && actor.dimension == 0 && !actor.isDead, "Unexpected actor state");
        originalGameType = actor.theItemInWorldManager.getGameType();
        require(originalGameType == GameType.SURVIVAL, "Fresh actor must start in survival");
        originalSlot = actor.inventory.currentItem;
        require(originalSlot >= 0 && originalSlot < 9, "Not a normal hotbar selection");
        starterInventory = inventorySnapshot();
        // GTNH can grant starter manuals. Never replace them. Prefer an empty hotbar position and
        // reserve a different main-inventory slot for only the test-funded hatch.
        for (int i = 0; i < 9; i++) if (actor.inventory.getStackInSlot(i) == null) {
            toolSlot = i;
            break;
        }
        for (int i = 9; i < actor.inventory.mainInventory.length; i++) if (actor.inventory.getStackInSlot(i) == null) {
            stockSlot = i;
            break;
        }
        require(toolSlot >= 0 && stockSlot >= 0, "No verified-empty fixture hotbar and main inventory slots");
        evidence.put("starterOccupiedSlots", starterInventory.tagCount());
        evidence.put("fixtureProjectorSlot", toolSlot);
        evidence.put("fixtureStockSlot", stockSlot);
        x = (int) Math.floor(actor.posX);
        z = (int) Math.floor(actor.posZ);
        y = 160;
        actor.setGameType(GameType.CREATIVE);
        // Glass support is in front, outside the machine definition. No existing terrain is overwritten.
        for (int cx = (x - 72) >> 4; cx <= (x + 72) >> 4; cx++)
            for (int cz = (z - 72) >> 4; cz <= (z + 72) >> 4; cz++) world.theChunkProviderServer.loadChunk(cx, cz);
        require(world.isAirBlock(x, y, z) && world.isAirBlock(x, y - 1, z - 4), "Fixture site occupied");
        world.setBlock(x, y - 1, z - 4, net.minecraft.init.Blocks.glass);
        actor.playerNetServerHandler.setPlayerLocation(x + .5, y, z - 3.5, 0F, 0F);
        var controller = GregTechAPI.METATILEENTITIES[14850].getStackForm(1);
        require(
            controller.getItem() instanceof ItemMachines placer
                && placer.placeBlockAt(controller, actor, world, x, y, z, 2, .5F, .5F, .5F, 0),
            "Native anchor placement");
        base = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
        base.setFrontFacing(ForgeDirection.NORTH);
        machine = (MTEEnhancedMultiBlockBase<?>) base.getMetaTileEntity();
        facing = machine.getExtendedFacing();
        var tool = new ItemStack(ProjectorRegistry.projector);
        var tag = new NBTTagCompound();
        tag.setString(HologramSessionKeys.TOOL_ID, toolId.toString());
        tool.setTagCompound(tag);
        var sources = new HologramMaterials.Settings();
        sources.main = sources.containers = sources.me = false;
        HologramMaterials.write(tool, sources);
        require(
            actor.inventory.getStackInSlot(toolSlot) == null && actor.inventory.getStackInSlot(stockSlot) == null,
            "Reserved fixture inventory changed before setup");
        fixtureTool = tool;
        actor.inventory.setInventorySlotContents(toolSlot, tool);
        selectSlot(toolSlot);
        actor.inventoryContainer.detectAndSendChanges();
        creativeInventory = inventorySnapshot();
        // Independent oracle: read the exact packaged native shape; "~" must match its native anchor.
        Class<?> def = Class.forName("com.miaokatze.gtsr.common.machine.turbine.MegaSteamTurbineStructureDef");
        Object visual = def.getMethod("visual", int.class)
            .invoke(null, 0);
        String[][] rows = (String[][]) visual.getClass()
            .getMethod("shapeLayers")
            .invoke(visual);
        int ax = def.getField("HORIZONTAL_OFFSET")
            .getInt(null);
        int by = def.getField("VERTICAL_OFFSET")
            .getInt(null);
        int cz = def.getField("DEPTH_OFFSET")
            .getInt(null);
        require(rows[by][cz].charAt(ax) == '~', "Packaged visual anchor mismatch");
        for (int b = 0; b < rows.length; b++)
            for (int c = 0; c < rows[b].length; c++) for (int a = 0; a < rows[b][c].length(); a++) {
                char symbol = rows[b][c].charAt(a);
                if (symbol == ' ' || symbol == 'A' || symbol == '~') continue;
                int[] xyz = new int[3];
                facing.getWorldOffset(new int[] { a - ax, b - by, c - cz }, xyz);
                var p = new Offset(xyz[0], xyz[1], xyz[2]);
                require(air(p), "Preexisting solid at target " + p);
                solid.add(p);
                if (BASIC && symbol == 'U') nativeHatchSites.add(p);
                if (symbol == 'U' && hatches.size() < hatchStock.size()) {
                    ItemStack part = hatchStock.get(hatches.size());
                    require(
                        part != null && part.getItem() instanceof ItemMachines,
                        "Ordinary registered hatch missing");
                    hatches.add(new Hatch(p, part.getItemDamage(), ForgeDirection.NORTH.ordinal()));
                }
            }
        require(solid.size() > 1000 && hatches.size() == 3, "Incomplete GTSR native oracle");
        evidence.put("nativeMachine", TYPE);
        evidence.put(
            "scheduler",
            "HologramNetwork stockNativeServer via production Lifecycle; no test budget or test tick");
        evidence.put("installedControllerId", 14850);
        evidence.put("expectedSolidTargets", solid.size());
        evidence.put("ordinaryHatches", hatches.toString());
        evidence.put(
            "scope",
            "Real integrated WorldClient Action wire, authenticated stock queue and production Lifecycle budget. No physical GUI click.");
    }

    /** On a refusal only, identify the exact piece, model and absolute facing proofs outside performance assertions. */
    private String terminalHatchEvidence() {
        if (last.phase() == Phase.COMPLETE && last.reason()
            .isEmpty()) return "";
        List<String> rows = new ArrayList<>();
        try {
            var detached = HologramDetachedController.fromLive(machine);
            ItemStack trigger = fixtureTool.copy();
            trigger.stackSize = 1;
            var capture = HologramCapture.declarationPieces(
                (com.gtnewhorizon.structurelib.alignment.constructable.IConstructable) detached,
                trigger);
            var definition = capture.definition == null ? detached.getStructureDefinition() : capture.definition;
            for (var piece : capture.pieces) {
                var elements = definition.getStructureFor(piece.name);
                var cursor = new HologramStructureCursor(elements, piece.a, piece.b, piece.c);
                while (cursor.done() < cursor.total() && rows.size() < hatches.size()) {
                    int index = cursor.done();
                    Offset position = cursor.declarationOffset(facing);
                    if (position == null) continue;
                    for (var hatch : hatches) if (position.equals(hatch.position())) {
                        var element = elements[index];
                        var cell = new HologramCapture.Cell(
                            element,
                            x + position.x(),
                            y + position.y(),
                            z + position.z(),
                            piece.trigger);
                        int[] legal = HologramCellPolicy.previewHatchFacings(
                            cell,
                            new ItemStack(GregTechAPI.sBlockMachines, 1, hatch.id()),
                            detached,
                            facing);
                        rows.add(
                            piece.name + "/"
                                + index
                                + " at="
                                + position
                                + " id="
                                + hatch.id()
                                + " element="
                                + element.getClass()
                                    .getName()
                                + " requested="
                                + hatch.facing()
                                + " legal="
                                + Arrays.toString(legal));
                    }
                }
            }
        } catch (Exception | LinkageError diagnosticUnavailable) {
            rows.add(
                "declaration evidence unavailable: " + diagnosticUnavailable.getClass()
                    .getName());
        }
        return rows.isEmpty() ? hatches.toString() : rows.toString();
    }

    private boolean air(Offset p) {
        return world.isAirBlock(x + p.x(), y + p.y(), z + p.z());
    }

    private Object tile(int i) {
        Offset p = hatches.get(i)
            .position();
        return world.getTileEntity(x + p.x(), y + p.y(), z + p.z());
    }

    private void verifyHatches() {
        for (int i = 0; i < hatches.size(); i++) {
            var part = hatches.get(i);
            require(
                tile(i) instanceof BaseMetaTileEntity hatch && hatch.getMetaTileID() == part.id()
                    && hatch.getMetaTileEntity() != null
                    && (part.facing() < 0 || hatch.getFrontFacing()
                        .ordinal() == part.facing()),
                "Wrong real ordinary hatch identity or orientation at " + part.position());
        }
    }

    private void requireFormed() {
        var errors = new ArrayList<gregtech.api.structure.error.StructureError>();
        machine.checkMachine(base, null, errors);
        require(errors.isEmpty(), "Native GTSR checkMachine rejected build: " + errors);
    }

    private void requestBasic(int phase) {
        require(basicSite == null && basicInput == null && !nativeHatchSites.isEmpty(), "Missing native hatch oracle");
        int xmin = x, xmax = x, zmin = z - 4, zmax = z;
        // Every declared target must be observed. Unknown hatch slots must never pass as free.
        for (var point : solid) {
            xmin = Math.min(xmin, x + point.x());
            xmax = Math.max(xmax, x + point.x());
            zmin = Math.min(zmin, z + point.z());
            zmax = Math.max(zmax, z + point.z());
        }
        var missing = new ArrayList<Offset>();
        if (phase == 2) for (var hatch : hatches) missing.add(hatch.position());
        basicSite = new BasicSite(
            phase,
            actorId,
            toolId,
            x,
            y,
            z,
            xmin >> 4,
            zmin >> 4,
            xmax >> 4,
            zmax >> 4,
            Collections.unmodifiableList(missing));
    }

    private void takeBasic() {
        BasicInput input = basicInput;
        require(
            input.site() == basicSite && input.projection()
                .cells()
                .size() >= solid.size()
                && input.projection()
                    .metadata()
                    .getInteger("siteUnknown") == 0,
            "Basic source is incomplete");
        int phase = input.site()
            .phase();
        boolean creative = phase == 1;
        require(actor.capabilities.isCreativeMode == creative, "Basic game mode did not sync");
        int[] ids = input.plan()
            .stockIds(), counts = new int[ids.length];
        require(ids.length > 0, "No proved ordinary hatch models in real Basic plan");
        if (!creative) {
            var stock = new HologramMaterials.Context(actor, HologramMaterials.read(actor.getHeldItem()), toolSlot);
            for (int i = 0; i < ids.length; i++)
                counts[i] = stock.count(new ItemStack(GregTechAPI.sBlockMachines, 1, ids[i]), Integer.MAX_VALUE);
            var denial = input.plan()
                .resolve(new int[ids.length], false);
            require(
                !denial.complete() && denial.choices()
                    .isEmpty(),
                "Empty stock fabricated a partial Basic plan");
        }
        var outcome = input.plan()
            .resolve(counts, creative);
        require(
            outcome.complete() && outcome.choices()
                .size() == 3,
            "Real Basic must return all three groups: " + outcome.reason());
        var accepted = new ArrayList<Hatch>();
        var groups = new java.util.HashSet<String>();
        var sites = new java.util.HashSet<Offset>();
        for (var choice : outcome.choices()) {
            var cell = input.projection()
                .cells()
                .get(choice.index());
            var pos = new Offset(cell.dx(), cell.dy(), cell.dz());
            require(
                nativeHatchSites.contains(pos) && sites.add(pos) && air(pos),
                "Basic chose non-native, occupied or duplicate U");
            var sample = new ItemStack(GregTechAPI.sBlockMachines, 1, choice.itemId());
            require(groups.add(basicGroup(sample)), "Duplicate Basic requirement");
            if (!creative) {
                int stockAt = -1;
                for (int i = 0; i < ids.length; i++) if (ids[i] == choice.itemId()) {
                    stockAt = i;
                    break;
                }
                require(stockAt >= 0 && counts[stockAt] == 2, "Basic stock candidate has no bound two-item offer");
            }
            accepted.add(new Hatch(pos, choice.itemId(), choice.facing()));
        }
        require(
            groups.equals(new java.util.HashSet<>(Arrays.asList("Input", "Output", "Dynamo"))),
            "Real Basic did not cover independent Input, Output and Dynamo");
        hatches.clear();
        hatches.addAll(accepted);
        evidence.put(creative ? "basicCreativePlanned" : "basicStockPlanned", accepted.toString());
        evidence.put(creative ? "basicCreativeStockIds" : "basicSurvivalStockIds", ids.length);
        if (!creative) evidence.put("basicNoStockRefusedAtomically", true);
        basicInput = null;
        basicSite = null;
        submit(phase);
    }

    private void enableSurvivalStock() {
        var settings = HologramMaterials.read(actor.getHeldItem());
        settings.main = true;
        HologramMaterials.write(actor.getHeldItem(), settings);
        actor.setGameType(GameType.SURVIVAL);
    }

    private void removeBasicHatchesAndStock() {
        enableSurvivalStock();
        for (Hatch hatch : hatches) {
            var offset = hatch.position();
            require(
                world.setBlockToAir(x + offset.x(), y + offset.y(), z + offset.z()) && air(offset),
                "Could not prepare the one-per-group repair sites");
            int slot = -1;
            for (int i = 9; i < actor.inventory.mainInventory.length; i++)
                if (actor.inventory.getStackInSlot(i) == null) {
                    slot = i;
                    break;
                }
            require(slot >= 0, "Need three verified-empty fixture stock slots");
            ItemStack item = new ItemStack(GregTechAPI.sBlockMachines, 2, hatch.id());
            actor.inventory.setInventorySlotContents(slot, item);
            basicStock.put(slot, item);
        }
        actor.inventoryContainer.detectAndSendChanges();
    }

    private List<String> realBasicWorld() {
        var installed = new ArrayList<String>();
        for (int i = 0; i < hatches.size(); i++) {
            var tile = (BaseMetaTileEntity) tile(i);
            var hatch = hatches.get(i);
            var sample = new ItemStack(GregTechAPI.sBlockMachines, 1, tile.getMetaTileID());
            require(
                tile.getMetaTileEntity()
                    .isFacingValid(tile.getFrontFacing()),
                "Invalid actual Basic hatch orientation");
            installed.add(
                basicGroup(sample) + "="
                    + tile.getMetaTileID()
                    + "@"
                    + hatch.position()
                    + "/"
                    + tile.getFrontFacing()
                        .name());
        }
        return installed;
    }

    private static boolean sameBasicStock(ItemStack current, ItemStack expected) {
        return current != null && expected != null
            && current.isItemEqual(expected)
            && ItemStack.areItemStackTagsEqual(current, expected);
    }

    private static String basicGroup(ItemStack sample) {
        var tile = ItemMachines.getMetaTileEntity(sample);
        if (tile instanceof gregtech.api.metatileentity.implementations.MTEHatchInput) return "Input";
        if (tile instanceof gregtech.api.metatileentity.implementations.MTEHatchOutput) return "Output";
        if (tile instanceof gregtech.api.metatileentity.implementations.MTEHatchDynamo
            || tile instanceof tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti) return "Dynamo";
        throw new IllegalStateException("Not an ordinary GT Basic candidate " + sample.getItemDamage());
    }

    private void submit(int next) {
        stage = next;
        var endpoint = HologramWorkspace.captureActionEndpoint(actor.playerNetServerHandler);
        require(endpoint != null && endpoint.constructionScope != null, "Actual authenticated endpoint missing");
        long sequence = HologramNetwork.nextConstructionIntentSequence();
        require(sequence > 0, "Native client sequence exhausted");
        expected = new HologramStockNativeWire.Key(UUID.randomUUID(), sequence, 0);
        var selection = new HologramDeclaredNativeSelection(
            14850,
            Collections.emptyMap(),
            0,
            0,
            Collections.emptyList(),
            hatches);
        require(selection.valid(), "Unrepresentable declared options");
        var config = HologramMaterials.read(actor.getHeldItem());
        boolean creative = actor.capabilities.isCreativeMode;
        int sourceKey = HologramProjectedMainPolicy.structurePlacementKey(config, creative);
        require(sourceKey >= 0 && (sourceKey & 7) == (creative ? 0 : 1), "Incorrect real construction sources");
        evidence.put(creative ? "creativeNoSources" : "survivalInventoryOnly", true);
        Confirmation confirmed = Confirmation.declared(
            expected,
            actor.dimension,
            toolId,
            x,
            y,
            z,
            2,
            toolSlot,
            facing.ordinal(),
            1,
            false,
            false,
            sourceKey,
            selection);
        last = null;
        expected = confirmed.forGeneration(endpoint.constructionScope.generation)
            .key();
        outbound = new Outbound(confirmed, false);
    }

    private void drainProductionReplies() {
        for (HologramStockNativeWire.Reply reply; (reply = incoming.poll()) != null;) {
            if (!reply.key()
                .equals(expected)) continue;
            if (reply.phase() == Phase.CAPABLE) {
                var current = outbound;
                require(current != null && !current.afterProbe(), "Unexpected duplicate production CAPABLE");
                submitted = 0;
                outbound = new Outbound(
                    current.confirmation()
                        .forGeneration(
                            reply.key()
                                .generation()),
                    true);
                continue;
            }
            last = reply;
        }
    }

    private NBTTagList inventorySnapshot() {
        return (NBTTagList) actor.inventory.writeToNBT(new NBTTagList())
            .copy();
    }

    private void selectSlot(int slot) {
        actor.inventory.currentItem = slot;
        actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(slot));
    }

    private boolean sameFixtureHatch(ItemStack stack) {
        return stack != null && fixtureHatch != null
            && stack.isItemEqual(fixtureHatch)
            && ItemStack.areItemStackTagsEqual(stack, fixtureHatch);
    }

    private void releaseFixtureInventory() {
        if (actor == null || starterInventory == null) return;
        if (toolSlot >= 0 && fixtureTool != null) {
            ItemStack held = actor.inventory.getStackInSlot(toolSlot);
            if (held == fixtureTool || held != null && held.getItem() == fixtureTool.getItem()
                && held.hasTagCompound()
                && toolId.toString()
                    .equals(
                        held.getTagCompound()
                            .getString(HologramSessionKeys.TOOL_ID)))
                actor.inventory.setInventorySlotContents(toolSlot, null);
            fixtureTool = null;
        }
        if (stockSlot >= 0 && sameFixtureHatch(actor.inventory.getStackInSlot(stockSlot)))
            actor.inventory.setInventorySlotContents(stockSlot, null);
        fixtureHatch = null;
        for (var entry : basicStock.entrySet())
            if (sameBasicStock(actor.inventory.getStackInSlot(entry.getKey()), entry.getValue()))
                actor.inventory.setInventorySlotContents(entry.getKey(), null);
        basicStock.clear();
        actor.setGameType(originalGameType);
        selectSlot(originalSlot);
        actor.inventoryContainer.detectAndSendChanges();
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.util.ProbeChunkTickets;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.ItemMachines;

/** Owned-save fixture preparation, natural formation, then three explicit readonly center observations. */
public final class HologramCenterNeighborRuntimeProbe implements AutoCloseable {

    private static final int SECTORS = 5;

    private static final String CENTER = "com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter";
    private static final String[] READ_METRICS = { "siteNativeCalls", "siteNativeCaptureCalls",
        "siteGeometryNativeCalls", "siteUniqueCells", "siteCellReads", "siteTileNbtCaptures" };
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final int x, y, z;
    private final Path recoveryFile;
    private final ItemStack controllerItem;
    private final double originalX, originalY, originalZ, motionX, motionY, motionZ;
    private final float originalYaw, originalPitch, fallDistance;
    private final NBTTagCompound report = new NBTTagCompound();
    private final NBTTagList neighbors = new NBTTagList();
    private final NBTTagList formationTransitions = new NBTTagList();
    private final Set<HologramNativeTargetPlan.Offset> expected = new LinkedHashSet<>();
    private final Set<HologramNativeTargetPlan.Offset> owned = new LinkedHashSet<>();
    private final Set<Long> loadedChunks = new LinkedHashSet<>();
    private final Map<Long, Chunk> nativeChunks = new LinkedHashMap<>();
    private final Map<Chunk, Long> chunkIdentities = new IdentityHashMap<>();
    private final Map<Long, ForgeChunkManager.Ticket> chunkLeases = new LinkedHashMap<>();
    private final List<ForgeChunkManager.Ticket> chunkTickets = new ArrayList<>();
    private final List<HologramNativeTargetPlan.Offset> nativePorts = new ArrayList<>();
    private final List<HologramNativeTargetPlan.Offset> floor = new ArrayList<>();
    private HologramNativeTargetPlan.Offset neighborPosition;
    private HologramNativeStructurePreview geometry;
    private Iterator<HologramNativePreviewWorld.Placement> placements;
    private Iterator<HologramProjectorSimulation.Position> visited;
    private Iterator<HologramNativeTargetPlan.Offset> preflight;
    private BaseMetaTileEntity live;
    private HologramNativeSitePlan reader;
    private HologramNativeTargetPlan target;
    private HologramNativeCapture.Adapter forbidNeighborNbt;
    private TileEntity neighborTile;
    private NBTTagCompound neighborBefore, baselineMetrics;
    private NBTTagCompound neighborBusiness, previousNeighborNbt, neighborAudit;
    private Object neighborMachine;
    private Block neighborBlock;
    private int neighborMetadata;
    private long previousNeighborTick;
    private String baselineDigest, baselineCells;
    private int phase, observation, formationTicks, standingZ;
    private int lastFormationTier = Integer.MIN_VALUE, lastFormationMask = Integer.MIN_VALUE;
    private boolean lastFormationFormed, lastFormationChecking;
    private long readStart, sourceElapsedNanos, targetStart;
    private volatile boolean done;
    private boolean closed, mutationStarted, actorMoved;

    public static HologramCenterNeighborRuntimeProbe begin(WorldServer world, EntityPlayerMP actor, int x, int y, int z)
        throws Exception {
        return begin(
            world,
            actor,
            x,
            y,
            z,
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("gtmb-center-neighbor-owned-recovery.txt"));
    }

    public static HologramCenterNeighborRuntimeProbe begin(WorldServer world, EntityPlayerMP actor, int x, int y, int z,
        Path recoveryFile) throws Exception {
        return new HologramCenterNeighborRuntimeProbe(world, actor, x, y, z, recoveryFile);
    }

    private HologramCenterNeighborRuntimeProbe(WorldServer world, EntityPlayerMP actor, int x, int y, int z,
        Path recoveryFile) throws Exception {
        require(world != null && actor != null && actor.worldObj == world, "Actual center actor/world required");
        require(
            actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL && !actor.capabilities.isCreativeMode
                && !actor.canCommandSenderUseCommand(2, "miaotest"),
            "Actual non-OP survival actor required");
        require(
            Boolean.TRUE.equals(
                Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                    .getMethod("owns", World.class)
                    .invoke(null, world)),
            "Exact owned save required");
        require(y >= 64 && y <= 160, "Center fixture anchor height must leave native vertical clearance");
        this.world = world;
        this.actor = actor;
        this.x = x;
        this.y = y;
        this.z = z;
        this.recoveryFile = recoveryFile;
        originalX = actor.posX;
        originalY = actor.posY;
        originalZ = actor.posZ;
        originalYaw = actor.rotationYaw;
        originalPitch = actor.rotationPitch;
        motionX = actor.motionX;
        motionY = actor.motionY;
        motionZ = actor.motionZ;
        fallDistance = actor.fallDistance;
        Class<?> items = Class.forName("com.miaokatze.gtsr.common.api.enums.GTSRItemList");
        Object entry = items.getField("OreIntegrationCenter")
            .get(null);
        controllerItem = ((ItemStack) items.getMethod("get", int.class)
            .invoke(entry, 1)).copy();
        require(controllerItem.getItem() instanceof ItemMachines, "Actual registered center item unavailable");
        require(
            CENTER.equals(
                GregTechAPI.METATILEENTITIES[controllerItem.getItemDamage()].getClass()
                    .getName()),
            "Actual center registry identity differs");
        report.setString("schema", "gtmb-center-native-neighbor-v1");
        report.setInteger("anchorX", x);
        report.setInteger("anchorY", y);
        report.setInteger("anchorZ", z);
        report.setInteger("controllerId", controllerItem.getItemDamage());
        report.setString("fixturePreparation", "Native construct and NEI completion; no GTMB business cost claim");
        report.setBoolean("forcedFormedFlag", false);
        report.setBoolean("cleanupPending", false);
        report.setTag("neighbors", neighbors);
        report.setTag("formationTransitions", formationTransitions);
        report.setString(
            "recoveryFile",
            recoveryFile.toAbsolutePath()
                .toString());
        Class<?> center = GregTechAPI.METATILEENTITIES[controllerItem.getItemDamage()].getClass();
        var location = center.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        if (location.openConnection() instanceof java.net.JarURLConnection connection) {
            connection.setUseCaches(false);
            location = connection.getJarFileURL();
        }
        Path jar = java.nio.file.Paths.get(location.toURI());
        report.setString("loadedJar", jar.toString());
        if (Files.isRegularFile(jar)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(jar)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) >= 0;) hash.update(buffer, 0, count);
            }
            report.setString("loadedJarSHA256", hex(hash.digest()));
        }
    }

    /** Invoked by the existing testlab END server tick. It never drives machine ticks itself. */
    public void tick(long deadline) {
        if (done || closed) return;
        try {
            require(actor.worldObj == world, "Center actor left the owned world");
            report.setInteger("phase", phase);
            if (phase == 0) {
                if (geometry == null) geometry = openGeometry(SECTORS);
                if (!geometry.advance(deadline)) return;
                placements = geometry.placements();
                visited = geometry.visitedPositions();
                phase = 1;
            }
            if (phase == 1) {
                while (placements.hasNext() && System.nanoTime() < deadline) {
                    var p = placements.next();
                    expected.add(relative(p.x(), p.y(), p.z()));
                    if (p.tile() instanceof BaseMetaTileEntity && (p.x() != x || p.y() != y || p.z() != z))
                        nativePorts.add(relative(p.x(), p.y(), p.z()));
                }
                if (placements.hasNext()) return;
                while (visited.hasNext() && System.nanoTime() < deadline) {
                    var p = visited.next();
                    expected.add(relative(p.x(), p.y(), p.z()));
                }
                if (visited.hasNext()) return;
                expected.remove(new HologramNativeTargetPlan.Offset(0, 0, 0));
                geometry.close();
                geometry = null;
                owned.addAll(expected);
                owned.add(new HologramNativeTargetPlan.Offset(0, 0, 0));
                // Select a small explicit standing platform against the already generated range.
                for (int distance : new int[] { 4, 8, 16, 24, 32, 48 }) {
                    boolean overlaps = false;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        overlaps |= owned.contains(new HologramNativeTargetPlan.Offset(dx, -1, -distance + dz))
                            || owned.contains(new HologramNativeTargetPlan.Offset(dx, 0, -distance + dz))
                            || owned.contains(new HologramNativeTargetPlan.Offset(dx, 1, -distance + dz));
                    if (!overlaps) {
                        standingZ = -distance;
                        break;
                    }
                }
                require(standingZ != 0, "No nonoverlapping fixture actor platform available");
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var p = new HologramNativeTargetPlan.Offset(dx, -1, standingZ + dz);
                    floor.add(p);
                    owned.add(p);
                    owned.add(new HologramNativeTargetPlan.Offset(dx, 0, standingZ + dz));
                    owned.add(new HologramNativeTargetPlan.Offset(dx, 1, standingZ + dz));
                    owned.add(new HologramNativeTargetPlan.Offset(dx, 2, standingZ + dz));
                }
                geometry = openGeometry(SECTORS + 1);
                phase = 2;
            }
            if (phase == 2) {
                if (!geometry.advance(deadline)) return;
                placements = geometry.placements();
                phase = 3;
            }
            if (phase == 3) {
                while (placements.hasNext() && System.nanoTime() < deadline) {
                    var p = placements.next();
                    var at = relative(p.x(), p.y(), p.z());
                    if (!owned.contains(at) && p.y() > 1 && p.y() < 254) {
                        neighborPosition = at;
                        break;
                    }
                }
                if (neighborPosition == null && placements.hasNext()) return;
                require(neighborPosition != null, "No outside member of the former sector-2 candidate available");
                geometry.close();
                geometry = null;
                owned.add(neighborPosition);
                preflight = owned.iterator();
                phase = 4;
            }
            if (phase == 4) {
                while (preflight.hasNext() && System.nanoTime() < deadline) {
                    var p = preflight.next();
                    int px = x + p.x(), py = y + p.y(), pz = z + p.z();
                    require(py > 0 && py < 255, "Native fixture exceeds world height");
                    long chunk = ((long) (px >> 4) << 32) ^ ((pz >> 4) & 0xffffffffL);
                    if (loadedChunks.add(chunk)) acquireNativeChunk(px >> 4, pz >> 4);
                    require(
                        world.isAirBlock(px, py, pz) && world.getTileEntity(px, py, pz) == null,
                        "Owned preparation refuses preexisting block/tile at " + px + "," + py + "," + pz);
                }
                if (preflight.hasNext()) return;
                writeRecoveryReceipt();
                prepareNativeCenter();
                phase = 5;
                return;
            }
            if (phase == 5) {
                require(++formationTicks <= 12000, "Natural native center formation timed out");
                NBTTagCompound status = (NBTTagCompound) live.getMetaTileEntity()
                    .getClass()
                    .getMethod("operationStatus")
                    .invoke(live.getMetaTileEntity());
                report.setTag("lastFormationStatus", status.copy());
                recordFormationTransition(status);
                if (!((MTEMultiBlockBase) live.getMetaTileEntity()).mMachine || !status.getBoolean("Formed")
                    || status.getBoolean("CheckingFormation")
                    || status.getInteger("Tier") != 0
                    || status.getInteger("Sectors") != (1 << SECTORS) - 1) return;
                var formed = HologramOreCenterSiteAccess.read(live);
                require(
                    formed.tier() == 0 && formed.mask() == (1 << SECTORS) - 1,
                    "Natural native center formed an unexpected layout");
                report.setInteger("naturalFormationTicks", formationTicks);
                startObservation();
                phase = 6;
            }
            if (phase == 6) {
                if (!advanceWithNeighborAudit(true, deadline)) return;
                require(
                    reader.settings()
                        .getBoolean("siteKnown"),
                    "Native source rejected: " + reader.settings());
                require(
                    reader.footprint()
                        .equals(expected),
                    "Native source did not return the complete actual footprint");
                if (neighborPosition != null)
                    require(!readNeighbor(reader), "Unrelated neighbor entered the source readset");
                sourceElapsedNanos = System.nanoTime() - readStart;
                String cellHash = cellSignature(reader.siteCells());
                if (observation == 0) {
                    baselineCells = cellHash;
                    baselineMetrics = metrics(reader.settings());
                    report.setInteger(
                        "actualCellCount",
                        reader.siteCells()
                            .size());
                    report.setTag("metrics", baselineMetrics.copy());
                    var timings = new NBTTagCompound();
                    for (String name : new String[] { "siteGeometryNanos", "siteAcceptanceNanos",
                        "siteNativeCaptureNanos" })
                        timings.setLong(
                            name,
                            reader.settings()
                                .getLong(name));
                    report.setTag("sourceTimings", timings);
                    report.setDouble("sourceElapsedMs", sourceElapsedNanos / 1_000_000.0);
                    report.setString("actualCellsDigest", cellHash);
                    report.setTag("expectedRows", expectedRows(reader.siteCells()));
                } else {
                    require(
                        baselineCells.equals(cellHash),
                        "Unrelated neighbor changed source block/item/facing cells");
                    require(
                        baselineMetrics.equals(metrics(reader.settings())),
                        "Unrelated neighbor changed source read counts");
                }
                target = HologramNativeTargetPlan
                    .fromSite(live.getMetaTileID(), ExtendedFacing.DEFAULT, reader.siteCells(), reader.settings());
                targetStart = System.nanoTime();
                phase = 7;
            }
            if (phase == 7) {
                if (!advanceWithNeighborAudit(false, deadline)) return;
                String digest = target.targetDigest();
                if (observation == 0) {
                    baselineDigest = digest;
                    report.setString("targetDigest", digest);
                    report.setDouble("targetFreezeDigestMs", (System.nanoTime() - targetStart) / 1_000_000.0);
                } else {
                    require(baselineDigest.equals(digest), "Unrelated neighbor changed native target digest");
                    var row = new NBTTagCompound();
                    row.setString("kind", observation == 1 ? "ordinary-block" : "foreign-controller");
                    row.setInteger("x", x + neighborPosition.x());
                    row.setInteger("y", y + neighborPosition.y());
                    row.setInteger("z", z + neighborPosition.z());
                    row.setBoolean("sourceRead", false);
                    row.setBoolean("sourceNbtCaptured", false);
                    row.setBoolean("sameCells", true);
                    row.setBoolean("sameDigest", true);
                    row.setBoolean("sameReadMetrics", true);
                    row.setTag("synchronousNeighborAudit", neighborAudit);
                    row.setTag("metrics", metrics(reader.settings()));
                    row.setDouble("sourceElapsedMs", sourceElapsedNanos / 1_000_000.0);
                    row.setDouble("targetFreezeDigestMs", (System.nanoTime() - targetStart) / 1_000_000.0);
                    neighbors.appendTag(row);
                }
                closeObservation();
                if (observation < 2) removeNeighbor();
                else validateNeighbor();
                if (++observation <= 2) {
                    stageNeighbor();
                    startObservation();
                    phase = 6;
                } else {
                    report.setBoolean("passed", true);
                    report.setBoolean("centerRetainedForClientUi", true);
                    report.setBoolean("foreignControllerRetainedForClientUi", true);
                    report.setString(
                        "coverage",
                        "Real center tier 1 / sectors 5; ordinary block and unformed foreign controller outside actual extent");
                    done = true;
                }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            report.setString("failure", failure.toString());
            java.io.StringWriter stack = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(stack));
            report.setString("failureStackTrace", stack.toString());
            try {
                recordFailureCells();
            } catch (Throwable diagnostic) {
                report.setString("failureDiagnosticError", diagnostic.toString());
                diagnostic.printStackTrace();
            }
            report.setBoolean("passed", false);
            try {
                close();
            } catch (Throwable cleanup) {
                report.setString("cleanupFailure", cleanup.toString());
                cleanup.printStackTrace();
            }
            done = true;
        }
    }

    public boolean done() {
        return done;
    }

    public NBTTagCompound result() {
        return (NBTTagCompound) report.copy();
    }

    private HologramNativeStructurePreview openGeometry(int sectors) throws Exception {
        BaseMetaTileEntity fresh = detached();
        var preview = new HologramNativeStructurePreview(
            fresh,
            fresh.getMetaTileEntity(),
            (IConstructable) fresh.getMetaTileEntity(),
            false,
            trigger(sectors),
            this::detached,
            ExtendedFacing.DEFAULT,
            false);
        var config = new NBTTagCompound();
        config.setBoolean("rangeOnly", true);
        config.setBoolean("footprintOnly", true);
        preview.configure(config);
        return preview;
    }

    private BaseMetaTileEntity detached() throws IOException {
        var base = HologramNativeTargetPlan
            .freshController(controllerItem.getItemDamage(), ExtendedFacing.DEFAULT, new NBTTagCompound());
        base.xCoord = x;
        base.yCoord = y;
        base.zCoord = z;
        var declarations = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        declarations.tile = base;
        base.setWorldObj(declarations);
        return base;
    }

    private static ItemStack trigger(int sectors) {
        var channels = new NBTTagCompound();
        channels.setInteger("ore_center_sectors", sectors);
        return HologramNativeTargetPlan.canonicalTrigger(1, channels);
    }

    private void prepareNativeCenter() throws Exception {
        long start = System.nanoTime();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        net.minecraft.world.IWorldAccess writes = (net.minecraft.world.IWorldAccess) java.lang.reflect.Proxy
            .newProxyInstance(
                net.minecraft.world.IWorldAccess.class.getClassLoader(),
                new Class<?>[] { net.minecraft.world.IWorldAccess.class },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "GTMB center native preparation notifications";
                    };
                    // Same MCP/SRG-independent coordinate callback shape as FourMachineWorldWrites.
                    if (arguments != null && arguments.length == 3
                        && arguments[0] instanceof Integer
                        && arguments[1] instanceof Integer
                        && arguments[2] instanceof Integer) {
                        report.setInteger("lastNativeWriteX", (Integer) arguments[0]);
                        report.setInteger("lastNativeWriteY", (Integer) arguments[1]);
                        report.setInteger("lastNativeWriteZ", (Integer) arguments[2]);
                        report.setString("lastNativeWriteStage", report.getString("preparationStage"));
                    }
                    return null;
                });
        world.addWorldAccess(writes);
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            assertNativeChunks("before-native-preparation");
            mutationStarted = true;
            report.setBoolean("cleanupPending", true);
            for (var p : floor) {
                checkpoint("actor-platform", x + p.x(), y + p.y(), z + p.z());
                require(
                    world.setBlock(x + p.x(), y + p.y(), z + p.z(), Blocks.stone, 0, 3),
                    "Owned actor platform placement failed");
            }
            actor.setPositionAndUpdate(x + .5, y, z + standingZ + .5);
            actor.motionX = actor.motionY = actor.motionZ = 0;
            actor.fallDistance = 0;
            actorMoved = true;
            live = placeController(x, y, z);
            ItemStack signal = trigger(SECTORS);
            require(
                signal.stackSize == 1 && ChannelDataAccessor.hasSubChannel(signal, "ore_center_sectors")
                    && ChannelDataAccessor.getChannelData(signal, "ore_center_sectors") == SECTORS,
                "Native center fixture trigger lost its explicit main/sector channel");
            report.setTag("nativePreparationTrigger", signal.writeToNBT(new NBTTagCompound()));
            report.setInteger("nativePreparationMain", signal.stackSize);
            report.setInteger(
                "nativePreparationSectorCount",
                ChannelDataAccessor.getChannelData(signal, "ore_center_sectors"));
            report.setInteger("nativePreparationExpectedMask", (1 << SECTORS) - 1);
            checkpoint("native-center-construct", x, y, z);
            ((IConstructable) live.getMetaTileEntity()).construct(signal.copy(), false);
            checkpoint("native-nei-completion-and-ports", x, y, z);
            live.getMetaTileEntity()
                .getClass()
                .getMethod("onPreviewStructureComplete", ItemStack.class)
                .invoke(live.getMetaTileEntity(), signal.copy());
            long bodyAudit = System.nanoTime();
            int actualNonAir = 0;
            for (var p : expected) if (!world.isAirBlock(x + p.x(), y + p.y(), z + p.z())) actualNonAir++;
            report.setInteger("nativePreparedMemberPositions", expected.size());
            report.setInteger("nativePreparedNonAirMembers", actualNonAir);
            report.setDouble("nativePreparedBodyCountMs", (System.nanoTime() - bodyAudit) / 1_000_000.0);
            require(
                actualNonAir == expected.size(),
                "Native fixture construction left missing expected solid center members");
            require(
                !((MTEMultiBlockBase) live.getMetaTileEntity()).mMachine,
                "Fixture preparation must not predeclare native formation");
            report.setDouble("nativeFixturePreparationMs", (System.nanoTime() - start) / 1_000_000.0);
        } finally {
            world.removeWorldAccess(writes);
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
    }

    private BaseMetaTileEntity placeController(int px, int py, int pz) {
        checkpoint("native-controller-item-placement", px, py, pz);
        require(
            world.isAirBlock(px, py, pz) && world.getTileEntity(px, py, pz) == null,
            "Owned controller position ceased to be empty");
        ItemStack item = controllerItem.copy();
        require(
            ((ItemMachines) item.getItem()).placeBlockAt(
                item,
                actor,
                world,
                px,
                py,
                pz,
                ExtendedFacing.DEFAULT.getDirection()
                    .ordinal(),
                .5F,
                .5F,
                .5F,
                0),
            "Native center item placement failed");
        require(world.getTileEntity(px, py, pz) instanceof BaseMetaTileEntity, "Native center tile missing");
        var base = (BaseMetaTileEntity) world.getTileEntity(px, py, pz);
        require(
            base.getMetaTileID() == controllerItem.getItemDamage() && CENTER.equals(
                base.getMetaTileEntity()
                    .getClass()
                    .getName()),
            "Native center tile registry differs");
        base.setOwnerName(actor.getCommandSenderName());
        base.setOwnerUuid(actor.getUniqueID());
        base.setFrontFacing(ExtendedFacing.DEFAULT.getDirection());
        ((IAlignment) base.getMetaTileEntity()).setExtendedFacing(ExtendedFacing.DEFAULT);
        base.disableWorking();
        world.markBlockForUpdate(px, py, pz);
        return base;
    }

    private void stageNeighbor() throws Exception {
        int px = x + neighborPosition.x(), py = y + neighborPosition.y(), pz = z + neighborPosition.z();
        require(
            world.isAirBlock(px, py, pz) && world.getTileEntity(px, py, pz) == null,
            "Neighbor site ceased to be empty");
        if (observation == 1)
            require(world.setBlock(px, py, pz, Blocks.diamond_block, 0, 3), "Neighbor block placement failed");
        else placeController(px, py, pz);
        neighborTile = world.getTileEntity(px, py, pz);
        neighborBlock = world.getBlock(px, py, pz);
        neighborMetadata = world.getBlockMetadata(px, py, pz);
        neighborMachine = neighborTile instanceof BaseMetaTileEntity base ? base.getMetaTileEntity() : null;
        neighborAudit = new NBTTagCompound();
        neighborAudit.setString(
            "scope",
            "Full native NBT and coordinate notifications around each synchronous advance; native ticks between calls remain enabled");
        neighborAudit.setString(
            "nativeCounterSource",
            "GT5U .277 MTEMultiBlockBase.onPostTick increments mTotalRunTime even while unformed/disabled; saveNBTData serializes it");
        neighborAudit.setTag("betweenCallNbtChanges", new NBTTagList());
        report.setTag("currentNeighborAudit", neighborAudit);
        // Fixture-only native serialization is distinct from the prohibited source-reader capture boundary.
        neighborBefore = nativeNeighborNbt();
        previousNeighborNbt = (NBTTagCompound) neighborBefore.copy();
        previousNeighborTick = world.getTotalWorldTime();
        neighborBusiness = neighborBusinessState(neighborBefore);
        forbidNeighborNbt = new HologramNativeCapture.Adapter() {

            public boolean supports(TileEntity tile) {
                return tile == neighborTile && tile != null;
            }

            public NBTTagCompound capture(TileEntity tile) {
                throw new AssertionError("Unrelated neighbor serialized");
            }
        };
        HologramNativeCapture.register(forbidNeighborNbt);
    }

    private void startObservation() throws IOException {
        assertNativeChunks("before-read-" + observation);
        var requested = new NBTTagCompound();
        requested.setInteger("main", 1);
        requested.setTag("channels", new NBTTagCompound());
        reader = HologramNativeSitePlan.begin(live, requested);
        readStart = System.nanoTime();
    }

    private void closeObservation() {
        if (target != null) {
            target.close();
            target = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (forbidNeighborNbt != null) {
            HologramNativeCapture.unregister(forbidNeighborNbt);
            forbidNeighborNbt = null;
        }
    }

    private void removeNeighbor() throws IOException {
        if (observation == 0 || neighborPosition == null) return;
        validateNeighbor();
        world.setBlockToAir(x + neighborPosition.x(), y + neighborPosition.y(), z + neighborPosition.z());
        neighborTile = null;
        neighborBefore = null;
        previousNeighborNbt = null;
        neighborBusiness = null;
    }

    private void validateNeighbor() throws IOException {
        NBTTagCompound current = nativeNeighborNbt();
        validateNeighborIdentityAndBusiness(current);
        recordBetweenCallChanges(current, "end-observation");
        neighborAudit.setTag("fullObservationSpanChanges", nbtChanges(neighborBefore, current, ""));
        neighborAudit.setBoolean("businessInvariantsUnchanged", true);
        neighborAudit.setBoolean("allSynchronousNativeNbtUnchanged", true);
        require(
            neighborAudit.getInteger("synchronousCalls") > 0
                && neighborAudit.getInteger("coordinateNotifications") == 0,
            "Neighbor call isolation audit was incomplete or observed source writes");
    }

    private boolean advanceWithNeighborAudit(boolean reading, long deadline) throws IOException {
        if (observation == 0) return reading ? reader.advance(deadline) : target.advance(deadline);
        long auditStart = System.nanoTime();
        NBTTagCompound before = nativeNeighborNbt();
        validateNeighborIdentityAndBusiness(before);
        recordBetweenCallChanges(before, reading ? "before-reader-advance" : "before-target-advance");
        final int[] notifications = { 0 };
        Thread owner = Thread.currentThread();
        net.minecraft.world.IWorldAccess listener = (net.minecraft.world.IWorldAccess) java.lang.reflect.Proxy
            .newProxyInstance(
                net.minecraft.world.IWorldAccess.class.getClassLoader(),
                new Class<?>[] { net.minecraft.world.IWorldAccess.class },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "GTMB synchronous neighbor coordinate audit";
                    };
                    if (Thread.currentThread() == owner && arguments != null
                        && arguments.length == 3
                        && arguments[0] instanceof Integer px
                        && arguments[1] instanceof Integer py
                        && arguments[2] instanceof Integer pz
                        && px == x + neighborPosition.x()
                        && py == y + neighborPosition.y()
                        && pz == z + neighborPosition.z()) notifications[0]++;
                    return null;
                });
        world.addWorldAccess(listener);
        long stepStart = System.nanoTime();
        Throwable nativeFailure = null;
        try {
            return reading ? reader.advance(deadline) : target.advance(deadline);
        } catch (IOException | RuntimeException | Error failure) {
            nativeFailure = failure;
            throw failure;
        } finally {
            long stepNanos = System.nanoTime() - stepStart;
            world.removeWorldAccess(listener);
            try {
                NBTTagCompound after = nativeNeighborNbt();
                validateNeighborIdentityAndBusiness(after);
                NBTTagList changed = nbtChanges(before, after, "");
                neighborAudit.setInteger("synchronousCalls", neighborAudit.getInteger("synchronousCalls") + 1);
                neighborAudit.setInteger(
                    reading ? "readerAdvanceCalls" : "targetAdvanceCalls",
                    neighborAudit.getInteger(reading ? "readerAdvanceCalls" : "targetAdvanceCalls") + 1);
                neighborAudit.setInteger(
                    "coordinateNotifications",
                    neighborAudit.getInteger("coordinateNotifications") + notifications[0]);
                neighborAudit.setLong(
                    "fixtureAuditNanos",
                    neighborAudit.getLong("fixtureAuditNanos") + System.nanoTime() - auditStart - stepNanos);
                if (changed.tagCount() != 0) neighborAudit.setTag("synchronousFailureNbtChanges", changed);
                require(
                    changed.tagCount() == 0 && notifications[0] == 0,
                    "Synchronous native source call changed unrelated neighbor NBT or notified its coordinate: "
                        + changed);
                previousNeighborNbt = after;
                previousNeighborTick = world.getTotalWorldTime();
            } catch (IOException | RuntimeException | Error auditFailure) {
                if (nativeFailure != null) nativeFailure.addSuppressed(auditFailure);
                else throw auditFailure;
            }
        }
    }

    private NBTTagCompound nativeNeighborNbt() {
        var result = new NBTTagCompound();
        if (neighborTile != null) neighborTile.writeToNBT(result);
        if (neighborAudit != null) neighborAudit
            .setInteger("fixtureOnlyNativeNbtSnapshots", neighborAudit.getInteger("fixtureOnlyNativeNbtSnapshots") + 1);
        return (NBTTagCompound) result.copy();
    }

    private NBTTagCompound neighborBusinessState(NBTTagCompound nativeNbt) throws IOException {
        var business = new NBTTagCompound();
        if (neighborTile instanceof BaseMetaTileEntity base) {
            business.setInteger("machineId", base.getMetaTileID());
            business.setInteger(
                "facing",
                base.getFrontFacing()
                    .ordinal());
            business.setString("owner", base.getOwnerName());
            business.setString("ownerUuid", String.valueOf(base.getOwnerUuid()));
            business.setBoolean("allowedToWork", base.isAllowedToWork());
            business.setLong("energy", base.getStoredEU());
            business.setLong("steam", base.getStoredSteam());
            business.setTag(
                "registeredItem",
                base.getMetaTileEntity()
                    .getStackForm(1)
                    .writeToNBT(new NBTTagCompound()));
            HologramNativeConfiguration.capture(base, null, business);
            var inventory = new NBTTagList();
            for (int slot = 0; slot < base.getSizeInventory(); slot++) {
                ItemStack item = base.getStackInSlot(slot);
                if (item != null) {
                    var row = item.writeToNBT(new NBTTagCompound());
                    row.setInteger("slot", slot);
                    inventory.appendTag(row);
                }
            }
            business.setTag("inventory", inventory);
            // Exact 1e05 center saveNBTData identifies Center* as its complete batch/configuration journal.
            // The separate oreCenterStructure cache follows native formation; every change is still logged,
            // and ALL keys (including that cache and GT run-time counters) remain strict within each call.
            for (Object key : nativeNbt.func_150296_c()) if (((String) key).startsWith("Center")) business.setTag(
                (String) key,
                nativeNbt.getTag((String) key)
                    .copy());
        }
        return (NBTTagCompound) business.copy();
    }

    private void validateNeighborIdentityAndBusiness(NBTTagCompound nativeNbt) throws IOException {
        int px = x + neighborPosition.x(), py = y + neighborPosition.y(), pz = z + neighborPosition.z();
        require(
            world.getBlock(px, py, pz) == neighborBlock && world.getBlockMetadata(px, py, pz) == neighborMetadata
                && world.getTileEntity(px, py, pz) == neighborTile
                && (neighborTile == null || !neighborTile.isInvalid())
                && (neighborTile == null || neighborTile.getWorldObj() == world && neighborTile.xCoord == px
                    && neighborTile.yCoord == py
                    && neighborTile.zCoord == pz)
                && (!(neighborTile instanceof BaseMetaTileEntity base) || base.getMetaTileEntity() == neighborMachine),
            "Unrelated neighbor block/tile/controller identity changed");
        NBTTagCompound current = neighborBusinessState(nativeNbt);
        if (!neighborBusiness.equals(current))
            neighborAudit.setTag("businessInvariantChanges", nbtChanges(neighborBusiness, current, ""));
        require(
            neighborBusiness.equals(current),
            "Unrelated neighbor item/facing/owner/configuration/inventory/business changed");
    }

    private void recordBetweenCallChanges(NBTTagCompound current, String stage) {
        NBTTagList changed = nbtChanges(previousNeighborNbt, current, "");
        if (changed.tagCount() == 0) return;
        var row = new NBTTagCompound();
        row.setString("stage", stage);
        row.setLong("previousWorldTick", previousNeighborTick);
        row.setLong("currentWorldTick", world.getTotalWorldTime());
        row.setTag("changes", changed);
        NBTTagList log = neighborAudit.getTagList("betweenCallNbtChanges", 10);
        if (log.tagCount() < 128) log.appendTag(row);
        else neighborAudit.setBoolean("betweenCallLogTruncated", true);
        require(
            world.getTotalWorldTime() > previousNeighborTick,
            "Neighbor NBT changed between audited calls without an intervening native tick: " + changed);
    }

    private static NBTTagList nbtChanges(NBTTagCompound before, NBTTagCompound after, String prefix) {
        var changes = new NBTTagList();
        var keys = new java.util.TreeSet<String>();
        for (Object key : before.func_150296_c()) keys.add((String) key);
        for (Object key : after.func_150296_c()) keys.add((String) key);
        for (String key : keys) {
            var oldValue = before.getTag(key);
            var newValue = after.getTag(key);
            if (java.util.Objects.equals(oldValue, newValue)) continue;
            if (oldValue instanceof NBTTagCompound oldCompound && newValue instanceof NBTTagCompound newCompound) {
                NBTTagList nested = nbtChanges(oldCompound, newCompound, prefix + key + ".");
                for (int i = 0; i < nested.tagCount(); i++) changes.appendTag(nested.getCompoundTagAt(i));
            } else {
                var row = new NBTTagCompound();
                row.setString("key", prefix + key);
                row.setBoolean("beforePresent", oldValue != null);
                row.setBoolean("afterPresent", newValue != null);
                if (oldValue != null) row.setTag("before", oldValue.copy());
                if (newValue != null) row.setTag("after", newValue.copy());
                changes.appendTag(row);
            }
        }
        return changes;
    }

    private boolean readNeighbor(HologramNativeSitePlan site) throws ReflectiveOperationException {
        var field = HologramNativeSitePlan.class.getDeclaredField("scene");
        field.setAccessible(true);
        Map<?, ?> scene = (Map<?, ?>) field.get(site);
        return scene.containsKey(
            CoordinatePacker.pack(x + neighborPosition.x(), y + neighborPosition.y(), z + neighborPosition.z()));
    }

    private void writeRecoveryReceipt() throws IOException {
        StringBuilder ledger = new StringBuilder("GTMB owned center native preparation v1\n");
        ledger.append("world=")
            .append(
                world.getSaveHandler()
                    .getWorldDirectory()
                    .getAbsolutePath())
            .append('\n');
        ledger.append("actor=")
            .append(actor.getUniqueID())
            .append('\n');
        ledger.append("originalPose=")
            .append(originalX)
            .append(',')
            .append(originalY)
            .append(',')
            .append(originalZ)
            .append(',')
            .append(originalYaw)
            .append(',')
            .append(originalPitch)
            .append('\n');
        ledger.append("originalMotion=")
            .append(motionX)
            .append(',')
            .append(motionY)
            .append(',')
            .append(motionZ)
            .append(" fallDistance=")
            .append(fallDistance)
            .append('\n');
        ledger.append("nativeChunks=")
            .append(nativeChunks.size())
            .append(" ownTickets=")
            .append(chunkTickets.size())
            .append(" providerLoadChunkOnProvideRequest=")
            .append(world.theChunkProviderServer.loadChunkOnProvideRequest)
            .append('\n');
        ledger.append("Every following coordinate was preflight air/noTE; only these may be removed.\n");
        for (var p : owned) ledger.append(x + p.x())
            .append(',')
            .append(y + p.y())
            .append(',')
            .append(z + p.z())
            .append('\n');
        Files.write(
            recoveryFile,
            ledger.toString()
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
        report.setInteger("ownedOriginallyAirPositions", owned.size());
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closeObservation();
        if (geometry != null) {
            geometry.close();
            geometry = null;
        }
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            RunnableMachineUpdate.setCurrentThreadEnabled(false);
            if (mutationStarted) {
                assertNativeChunks("before-cleanup");
                // Disable empty fixture machines before removal; no real business inventory is seeded by this probe.
                for (var p : owned) {
                    TileEntity tile = world.getTileEntity(x + p.x(), y + p.y(), z + p.z());
                    if (tile instanceof BaseMetaTileEntity base) base.disableWorking();
                    if (tile instanceof net.minecraft.inventory.IInventory inventory)
                        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) require(
                            inventory.getStackInSlot(slot) == null,
                            "Cleanup retains unexpected real inventory at " + p);
                }
                for (var p : owned) {
                    if (p.x() == 0 && p.y() == 0 && p.z() == 0 || floor.contains(p)) continue;
                    world.setBlockToAir(x + p.x(), y + p.y(), z + p.z());
                }
                world.setBlockToAir(x, y, z);
            }
            if (actorMoved) {
                actor.setPositionAndUpdate(originalX, originalY, originalZ);
                actor.rotationYaw = originalYaw;
                actor.rotationPitch = originalPitch;
                actor.motionX = motionX;
                actor.motionY = motionY;
                actor.motionZ = motionZ;
                actor.fallDistance = fallDistance;
            }
            if (mutationStarted) for (var p : floor) world.setBlockToAir(x + p.x(), y + p.y(), z + p.z());
            if (mutationStarted) for (var p : owned) require(
                world.isAirBlock(x + p.x(), y + p.y(), z + p.z())
                    && world.getTileEntity(x + p.x(), y + p.y(), z + p.z()) == null,
                "Owned native fixture restoration incomplete");
            releaseNativeChunks();
            closed = true;
            report.setBoolean("cleanupPending", false);
            report.setBoolean("closed", true);
            report.setBoolean("centerRetainedForClientUi", false);
            report.setBoolean("foreignControllerRetainedForClientUi", false);
            done = true;
        } finally {
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
        }
    }

    private void acquireNativeChunk(int cx, int cz) {
        require(nativeChunks.size() < 256, "Owned center exceeds the fixed fixture chunk bound");
        ProbeChunkTickets.ensureRegistered();
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        // Same native load/identity contract as existing CutAeDataNativeSmoke/EnergyNetworkSmoke.
        // provideChunk/getChunkFromBlockCoords alone can return a shared EmptyChunk.
        Chunk loaded = world.theChunkProviderServer.loadChunk(cx, cz);
        require(
            loaded != null && !(loaded instanceof EmptyChunk)
                && loaded.isChunkLoaded
                && loaded.xPosition == cx
                && loaded.zPosition == cz
                && world.theChunkProviderServer.chunkExists(cx, cz),
            "Native center fixture chunk failed to load at " + cx + "," + cz);
        Long alias = chunkIdentities.get(loaded);
        require(alias == null || alias.longValue() == key, "Owned center coordinates alias one native chunk");
        ForgeChunkManager.Ticket ticket = chunkTickets.isEmpty() ? null : chunkTickets.get(chunkTickets.size() - 1);
        if (ticket == null || ticket.getChunkListDepth() > 0 && ticket.getChunkList()
            .size() >= ticket.getChunkListDepth()) {
            ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
            require(ticket != null, "Forge refused the center fixture's bounded chunk lease");
            chunkTickets.add(ticket);
            // Existing opt-in callback releases these tickets on reload; no new callback or limit override.
            ticket.getModData()
                .setBoolean("gtmbTestLab", true);
            ticket.getModData()
                .setBoolean("gtmbCenterNeighborProbe", true);
            ticket.getModData()
                .setString(
                    "recoveryFile",
                    recoveryFile.toAbsolutePath()
                        .toString());
        }
        ChunkCoordIntPair coordinate = new ChunkCoordIntPair(cx, cz);
        ForgeChunkManager.forceChunk(ticket, coordinate);
        require(
            world.getPersistentChunks()
                .containsEntry(coordinate, ticket),
            "Center fixture did not retain its own chunk ticket");
        require(
            world.getChunkFromChunkCoords(cx, cz) == loaded,
            "Ordinary lookup does not return the explicitly loaded native chunk");
        nativeChunks.put(key, loaded);
        chunkIdentities.put(loaded, key);
        chunkLeases.put(key, ticket);
        report.setInteger("ownedNativeChunks", nativeChunks.size());
        report.setInteger("ownNativeChunkTickets", chunkTickets.size());
        report.setBoolean("loadChunkOnProvideRequest", world.theChunkProviderServer.loadChunkOnProvideRequest);
        report.setBoolean("nativeChunkTicketsReleased", false);
    }

    private void assertNativeChunks(String stage) {
        for (var entry : nativeChunks.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32), cz = (int) key;
            Chunk chunk = entry.getValue();
            require(
                chunk.isChunkLoaded && !(chunk instanceof EmptyChunk)
                    && world.theChunkProviderServer.chunkExists(cx, cz)
                    && world.getChunkFromChunkCoords(cx, cz) == chunk
                    && world.getPersistentChunks()
                        .containsEntry(new ChunkCoordIntPair(cx, cz), chunkLeases.get(key)),
                "Owned native chunk identity/lease lost at " + stage + ": " + cx + "," + cz);
        }
        report.setString("lastNativeChunkProof", stage);
    }

    private void releaseNativeChunks() {
        for (var ticket : chunkTickets) ForgeChunkManager.releaseTicket(ticket);
        for (var entry : chunkLeases.entrySet()) {
            long key = entry.getKey();
            require(
                !world.getPersistentChunks()
                    .containsEntry(new ChunkCoordIntPair((int) (key >> 32), (int) key), entry.getValue()),
                "Center fixture retained an own ticket after release");
        }
        chunkTickets.clear();
        chunkLeases.clear();
        nativeChunks.clear();
        chunkIdentities.clear();
        loadedChunks.clear();
        report.setBoolean("nativeChunkTicketsReleased", true);
    }

    private void checkpoint(String stage, int px, int py, int pz) {
        report.setString("preparationStage", stage);
        report.setInteger("lastTargetX", px);
        report.setInteger("lastTargetY", py);
        report.setInteger("lastTargetZ", pz);
    }

    private NBTTagCompound nativeCellState(int px, int py, int pz) {
        var result = new NBTTagCompound();
        result.setInteger("x", px);
        result.setInteger("y", py);
        result.setInteger("z", pz);
        int cx = px >> 4, cz = pz >> 4;
        boolean exists = world.theChunkProviderServer.chunkExists(cx, cz);
        result.setBoolean("providerChunkExists", exists);
        result.setBoolean("loadChunkOnProvideRequest", world.theChunkProviderServer.loadChunkOnProvideRequest);
        if (exists) {
            Chunk lookup = world.getChunkFromChunkCoords(cx, cz);
            result.setString(
                "nativeChunkClass",
                lookup.getClass()
                    .getName());
            result.setBoolean("emptyChunk", lookup instanceof EmptyChunk);
            result.setInteger("nativeChunkX", lookup.xPosition);
            result.setInteger("nativeChunkZ", lookup.zPosition);
            result.setBoolean("loaded", lookup.isChunkLoaded);
            result.setString("block", String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(px, py, pz))));
            result.setInteger("meta", world.getBlockMetadata(px, py, pz));
            TileEntity tile = world.getTileEntity(px, py, pz);
            if (tile != null) result.setString(
                "tileClass",
                tile.getClass()
                    .getName());
            if (tile instanceof BaseMetaTileEntity base) result.setInteger("machineId", base.getMetaTileID());
        }
        return result;
    }

    private void recordFailureCells() {
        report.setInteger("failurePhase", phase);
        report.setTag("failureAnchor", nativeCellState(x, y, z));
        if (report.hasKey("lastTargetX", 3)) report.setTag(
            "failureLastTarget",
            nativeCellState(
                report.getInteger("lastTargetX"),
                report.getInteger("lastTargetY"),
                report.getInteger("lastTargetZ")));
        if (report.hasKey("lastNativeWriteX", 3)) report.setTag(
            "failureLastNativeWrite",
            nativeCellState(
                report.getInteger("lastNativeWriteX"),
                report.getInteger("lastNativeWriteY"),
                report.getInteger("lastNativeWriteZ")));
        var ports = new NBTTagList();
        for (var p : nativePorts) ports.appendTag(nativeCellState(x + p.x(), y + p.y(), z + p.z()));
        report.setTag("failureNativePortTargets", ports);
    }

    private void recordFormationTransition(NBTTagCompound status) {
        int tier = status.getInteger("Tier"), mask = status.getInteger("Sectors");
        boolean formed = status.getBoolean("Formed"), checking = status.getBoolean("CheckingFormation");
        if (tier == lastFormationTier && mask == lastFormationMask
            && formed == lastFormationFormed
            && checking == lastFormationChecking) return;
        if (formationTransitions.tagCount() < 128) {
            var row = new NBTTagCompound();
            row.setInteger("naturalTicksSincePreparation", formationTicks);
            row.setLong("worldTick", world.getTotalWorldTime());
            row.setInteger("tier", tier);
            row.setInteger("mask", mask);
            row.setBoolean("formed", formed);
            row.setBoolean("checkingFormation", checking);
            formationTransitions.appendTag(row);
        } else report.setBoolean("formationTransitionLogTruncated", true);
        lastFormationTier = tier;
        lastFormationMask = mask;
        lastFormationFormed = formed;
        lastFormationChecking = checking;
    }

    private HologramNativeTargetPlan.Offset relative(int px, int py, int pz) {
        return new HologramNativeTargetPlan.Offset(px - x, py - y, pz - z);
    }

    private static NBTTagCompound metrics(NBTTagCompound settings) {
        var result = new NBTTagCompound();
        for (String name : READ_METRICS) result.setLong(name, settings.getLong(name));
        return result;
    }

    private static String cellSignature(List<HologramNativeTargetPlan.Cell> cells) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var cell : cells) {
            String row = cell.dx() + ","
                + cell.dy()
                + ","
                + cell.dz()
                + ":"
                + net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block())
                + ":"
                + cell.metadata()
                + ":"
                + cell.facing()
                + ":"
                + (cell.material() == null ? ""
                    : cell.material()
                        .writeToNBT(new NBTTagCompound()))
                + "\n";
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private static NBTTagList expectedRows(List<HologramNativeTargetPlan.Cell> cells) {
        var result = new NBTTagList();
        for (var cell : cells) {
            if (result.tagCount() >= 32 && cell.block() != GregTechAPI.sBlockMachines) continue;
            if (result.tagCount() >= 64) break;
            var row = new NBTTagCompound();
            row.setInteger("x", cell.dx());
            row.setInteger("y", cell.dy());
            row.setInteger("z", cell.dz());
            row.setString(
                "block",
                String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block())));
            row.setInteger("meta", cell.metadata());
            row.setInteger("facing", cell.facing());
            if (cell.material() != null) row.setTag(
                "item",
                cell.material()
                    .writeToNBT(new NBTTagCompound()));
            result.appendTag(row);
        }
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}

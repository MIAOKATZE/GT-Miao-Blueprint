package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testlab.TestLabWorldProvider;

import appeng.api.AEApi;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileDenseEnergyCell;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * Enabled-laboratory fixture support and independent world/payment witness. No packet handler calls this class.
 * Native Save is observed from the actual session commit; this bridge never invents a PNG or owner acknowledgement.
 */
public final class HologramCompositeLabBridge {

    private static HologramCompositeLabBridge current;
    private static final int SIDE = 40, CELLS = 64000, OBSERVE_PER_TICK = 256;
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final NBTTagCompound manifest;
    private final NBTTagCompound result = new NBTTagCompound();
    private final NBTTagList checks = new NBTTagList();
    private final List<NBTTagCompound> original = new ArrayList<>(CELLS);
    private final int[] source, bankOrigin;
    private HologramCompositeLabBank.Bank bank, sourceBank, shareBank;
    private ItemStack sourceTerminal, shareTerminal;
    private boolean initialized, bound, baselineComplete, finished;
    private int baselineCursor, checkCursor;
    private int[] checkTarget;
    private String checkKind = "", suppliedKind = "", savedSession = "";
    private String cutId = "";
    private int checked, tileChecked, omittedVolatileFields;
    private final List<String> mismatchSamples = new ArrayList<>();
    private BigInteger euBefore = BigInteger.ZERO, euSupplied = BigInteger.ZERO;
    private BigInteger fixtureEuCredits = BigInteger.ZERO;
    private BigInteger wirelessStoredBefore = BigInteger.ZERO;
    private long operationStarted, totalStarted = System.nanoTime();
    private NBTTagCompound bankBefore;

    public HologramCompositeLabBridge(WorldServer world, EntityPlayerMP actor, NBTTagCompound manifest)
        throws Exception {
        this.world = world;
        this.actor = actor;
        this.manifest = (NBTTagCompound) manifest.copy();
        source = origin("source");
        bankOrigin = origin("bank");
        require(
            Arrays.equals(this.manifest.getIntArray("size"), new int[] { SIDE, SIDE, SIDE }),
            "exact 40-cubed manifest required");
        requireOwned(bankOrigin, 32);
        result.setTag("manifest", this.manifest.copy());
        result.setTag("checks", checks);
        result.setBoolean("actualNetty", !(actor instanceof FakePlayer) && actor.playerNetServerHandler != null);
        result.setString("nativeSave", "NOT_RUN");
        result.setString("status", "INITIALIZING");
        result.setString(
            "oracle",
            "direct World block/meta and TileEntity.writeToNBT; immutable pre-import source; no product processing output used as expected");
        result.setString(
            "volatilePolicy",
            "only explicitly listed native runtime/AE grid identity fields are omitted; inventory, recipes, patterns, config and other payload remain exact");
        current = this;
    }

    /** Only one opted-in run exists; returned object still checks ownership before every mutation. */
    public static HologramCompositeLabBridge current() {
        return current;
    }

    public void initialize() throws Exception {
        requireOwned(bankOrigin, 32);
        require(!initialized, "bank initializes once");
        int x = bankOrigin[0] + 3, y = bankOrigin[1] + 3, z = bankOrigin[2] + 3;
        Block controller = AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeBlock()
            .get();
        for (int row = 0; row < 2; row++) for (int n = 0; n < 7; n++) {
            int zz = z + row * 14;
            require(world.isAirBlock(x + n, y, zz), "fresh bank controller footprint");
            require(world.setBlock(x + n, y, zz, controller, 0, 3), "registered AE controller placement");
            TileEntity tile = world.getTileEntity(x + n, y, zz);
            require(tile instanceof IGridProxyable, "native controller factory");
            ((IGridProxyable) tile).getProxy()
                .setOwner(actor);
        }
        sourceBank = HologramCompositeLabBank.create(world, actor, x, y, z);
        shareBank = HologramCompositeLabBank.create(world, actor, x, y, z + 14);
        int paymentX = bankOrigin[0] + 18, paymentY = bankOrigin[1] + 3, paymentZ = bankOrigin[2] + 12;
        require(
            world.isAirBlock(paymentX, paymentY, paymentZ)
                && world.setBlock(paymentX, paymentY, paymentZ, controller, 0, 3),
            "separate native payment controller placement");
        TileEntity paymentController = world.getTileEntity(paymentX, paymentY, paymentZ);
        require(paymentController instanceof IGridProxyable, "native payment controller factory");
        ((IGridProxyable) paymentController).getProxy()
            .setOwner(actor);
        bank = HologramCompositeLabBank.create(world, actor, paymentX, paymentY, paymentZ);
        Block dense = AEApi.instance()
            .definitions()
            .blocks()
            .energyCellDense()
            .maybeBlock()
            .get();
        NBTTagList networkPower = new NBTTagList();
        double suppliedAE = 0;
        int powerCellCount = 0;
        for (int row = 0; row < 2; row++) {
            NBTTagList cells = new NBTTagList();
            for (int n = 0; n < 7; n++) for (int side : new int[] { -1, 1 })
                cells.appendTag(finiteReserveCell(dense, x + n, y + side, z + row * 14));
            // Each real 7-cubed reserve touches the existing y+1 cells. Both networks stay physically separate.
            for (int n = 0; n < 7; n++)
                for (int height = 2; height <= 8; height++) for (int offset = 0; offset < 7; offset++)
                    cells.appendTag(finiteReserveCell(dense, x + n, y + height, z + row * 14 + offset));
            require(cells.tagCount() == 357, "each native network has exactly 357 reserve cells");
            NBTTagCompound bankPower = (row == 0 ? sourceBank : shareBank).fixturePowerReceipt();
            double initialAE = bankPower.getDouble("nativeCurrentAE");
            double capacityAE = bankPower.getDouble("nativeMaxAE");
            for (int index = 0; index < cells.tagCount(); index++) {
                initialAE += cells.getCompoundTagAt(index)
                    .getDouble("nativeCurrentAE");
                capacityAE += cells.getCompoundTagAt(index)
                    .getDouble("nativeMaxAE");
            }
            NBTTagCompound network = new NBTTagCompound();
            network.setString("network", row == 0 ? "source" : "share");
            network.setInteger("reserveCells", cells.tagCount());
            network.setInteger("allFinitePowerCells", cells.tagCount() + 1);
            network.setDouble("nativeInitialAE", initialAE);
            network.setDouble("nativeCapacityAE", capacityAE);
            network.setBoolean("anyInfinite", false);
            network.setBoolean("automaticRefill", false);
            network.setString(
                "observation",
                "one-time native initialization before run ticks; per-cell current/max read at initial credit");
            network.setTag("bankPowerCell", bankPower);
            network.setTag("reservePowerCells", cells);
            networkPower.appendTag(network);
            require(initialAE == 358_000_000, "native finite network supply must total exactly 358M AE");
            suppliedAE += initialAE;
            powerCellCount += cells.tagCount() + 1;
        }
        require(powerCellCount == 716 && suppliedAE == 716_000_000, "actual source/share finite power accounting");
        NBTTagList paymentCells = new NBTTagList();
        for (int dx = 18; dx <= 21; dx++) for (int dy = 4; dy <= 7; dy++) for (int dz = 12; dz <= 15; dz++) paymentCells
            .appendTag(finiteReserveCell(dense, bankOrigin[0] + dx, bankOrigin[1] + dy, bankOrigin[2] + dz));
        NBTTagCompound paymentPower = bank.fixturePowerReceipt();
        double paymentInitialAE = paymentPower.getDouble("nativeCurrentAE");
        double paymentCapacityAE = paymentPower.getDouble("nativeMaxAE");
        for (int index = 0; index < paymentCells.tagCount(); index++) {
            paymentInitialAE += paymentCells.getCompoundTagAt(index)
                .getDouble("nativeCurrentAE");
            paymentCapacityAE += paymentCells.getCompoundTagAt(index)
                .getDouble("nativeMaxAE");
        }
        require(
            paymentCells.tagCount() == 64 && paymentInitialAE == 65_000_000,
            "separate native payment supply is exactly 64 reserves plus its finite bank cell");
        NBTTagCompound paymentNetwork = new NBTTagCompound();
        paymentNetwork.setString("network", "payment");
        paymentNetwork.setInteger("reserveCells", 64);
        paymentNetwork.setInteger("allFinitePowerCells", 65);
        paymentNetwork.setDouble("nativeInitialAE", paymentInitialAE);
        paymentNetwork.setDouble("nativeCapacityAE", paymentCapacityAE);
        paymentNetwork.setBoolean("anyInfinite", false);
        paymentNetwork.setBoolean("automaticRefill", false);
        paymentNetwork.setTag("bankPowerCell", paymentPower);
        paymentNetwork.setTag("reservePowerCells", paymentCells);
        networkPower.appendTag(paymentNetwork);
        powerCellCount += 65;
        suppliedAE += paymentInitialAE;
        require(powerCellCount == 781 && suppliedAE == 781_000_000, "actual three-network finite power accounting");
        result.setInteger("finiteAEPowerCells", powerCellCount);
        result.setDouble("finiteAEFixtureSupply", suppliedAE);
        result.setTag("finiteAENetworks", networkPower);
        sourceTerminal = quantumTerminal();
        shareTerminal = quantumTerminal();
        initialized = true;
        result.setString("status", "WAIT_NATIVE_BANK");
    }

    private NBTTagCompound finiteReserveCell(Block dense, int x, int y, int z) throws IOException {
        requireOwned(bankOrigin, 32);
        require(
            world.isAirBlock(x, y, z) && world.setBlock(x, y, z, dense, 0, 3),
            "finite native AE reserve cell placement");
        TileEntity tile = world.getTileEntity(x, y, z);
        require(tile instanceof TileDenseEnergyCell, "native dense reserve cell factory");
        TileDenseEnergyCell cell = (TileDenseEnergyCell) tile;
        require(!cell.isInfinite() && cell.getAEMaxPower() >= 1_000_000, "finite real reserve capacity");
        NBTTagCompound charge = new NBTTagCompound();
        charge.setDouble("internalCurrentPower", 1_000_000);
        cell.readFromNBT_TileEnergyCell(charge);
        cell.getProxy()
            .setOwner(actor);
        cell.markDirty();
        require(
            cell.getAECurrentPower() == 1_000_000 && !cell.isInfinite(),
            "native reserve accepted exactly one finite initial AE credit");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setIntArray("position", new int[] { x, y, z });
        receipt.setDouble("nativeCurrentAE", cell.getAECurrentPower());
        receipt.setDouble("nativeMaxAE", cell.getAEMaxPower());
        receipt.setBoolean("infinite", cell.isInfinite());
        return receipt;
    }

    public boolean bankReady() throws Exception {
        requireOwned(bankOrigin, 32);
        if (!initialized || !bank.ready() || !sourceBank.ready() || !shareBank.ready()) return false;
        require(
            bank.nativeGrid() != sourceBank.nativeGrid() && bank.nativeGrid() != shareBank.nativeGrid()
                && sourceBank.nativeGrid() != shareBank.nativeGrid(),
            "source/share/payment must be three distinct real AE grids");
        result.setBoolean("threeDistinctNativeGrids", true);
        if (!bound) {
            int x = bankOrigin[0] + 3, y = bankOrigin[1] + 3, z = bankOrigin[2] + 3;
            bind(sourceTerminal, x, y, z);
            bind(shareTerminal, x, y, z + 14);
            actor.inventory.setInventorySlotContents(32, sourceTerminal);
            actor.inventory.setInventorySlotContents(33, shareTerminal);
            bank.bindTerminal();
            result.setTag("paymentReceipt", bank.receipt());
            actor.inventory.markDirty();
            bound = true;
            result.setString(
                "quantumBinding",
                "real native terminal onItemUseFirst; native isBound/getAnchor checked; no terminal NBT fabricated");
            result.setTag("sourceTerminal", sourceTerminal.writeToNBT(new NBTTagCompound()));
            result.setTag("shareTerminal", shareTerminal.writeToNBT(new NBTTagCompound()));
            result.setString("status", "WAIT_FIXTURE");
        }
        return true;
    }

    public ItemStack sourceTerminal() {
        return sourceTerminal;
    }

    public ItemStack shareTerminal() {
        return shareTerminal;
    }

    public BigInteger fixtureEuCredits() {
        return fixtureEuCredits;
    }

    public void close() {
        if (current == this) current = null;
        original.clear();
        finished = true;
    }

    /** Called after the fixture's native idle/formed gate, before any product import or cut. */
    public void startBaseline() throws Exception {
        requireOwned(source, SIDE);
        require(bound && !baselineComplete && baselineCursor == 0, "one baseline after native bank readiness");
        result.setString("status", "BASELINE");
        operationStarted = System.nanoTime();
    }

    /** No product task is manually ticked: natural workspace/server ticks own import, transport and placement. */
    public void tick() throws Exception {
        if (finished) return;
        if ("BASELINE".equals(result.getString("status"))) {
            requireOwned(source, SIDE);
            for (int n = 0; n < OBSERVE_PER_TICK && baselineCursor < CELLS; n++, baselineCursor++)
                original.add(read(source, baselineCursor));
            if (baselineCursor == CELLS) {
                baselineComplete = true;
                result.setString("sourceSha256", hash(original));
                result.setLong("baselineNanos", System.nanoTime() - operationStarted);
                result.setInteger("sourceCells", CELLS);
                result.setString("status", "READY");
            }
        } else if (!checkKind.isEmpty()) {
            requireOwned(checkTarget, SIDE);
            for (int n = 0; n < OBSERVE_PER_TICK && checkCursor < CELLS; n++, checkCursor++) {
                NBTTagCompound expected = normalized(original.get(checkCursor), checkKind, true);
                NBTTagCompound actual = normalized(read(checkTarget, checkCursor), checkKind, false);
                checked++;
                if (expected.hasKey("tile", 10)) tileChecked++;
                if (!expected.equals(actual) && mismatchSamples.size() < 16) mismatchSamples
                    .add(checkCursor + ": expected=" + abbreviate(expected) + " actual=" + abbreviate(actual));
            }
            if (checkCursor == CELLS) finishCheck();
        }
        observeSave();
    }

    public boolean ready() {
        return baselineComplete && checkKind.isEmpty() && !finished;
    }

    /** Common-side convenience only. Connected acceptance uses the client's real production selection commands. */
    public void beginCommonImport() throws Exception {
        require(actor instanceof FakePlayer, "connected import must originate through the actual client");
        require(ready(), "baseline ready");
        requireOwned(source, SIDE);
        HologramAreaWorkflow.beginAreaSelection(actor);
        HologramAreaWorkflow.areaPoint(actor, 1, source[0], source[1], source[2], false);
        HologramAreaWorkflow.areaPoint(actor, 2, source[0] + 39, source[1] + 39, source[2] + 39, false);
        HologramAreaWorkflow.finishAreaSelection(actor, request(session()));
    }

    /** Real processing entry; never a substitute for six native UI stage choices. */
    public void processCommon(String tendency) throws Exception {
        require(actor instanceof FakePlayer, "connected processing must originate through the actual UI");
        HologramSession s = session();
        require(
            s.capture != null && !s.capture.incomplete && s.capture.cells.size() == CELLS,
            "complete actual source import");
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults(tendency, 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        NBTTagCompound command = request(s);
        command.setTag("policy", policy.write());
        HologramAuthoringWorkflow.processingAction(s, command, "processBlueprint");
        require(s.processingReviewed && s.processingPresetOperationSuccess, s.status);
        checkEncoded(tendency);
    }

    /** Executes bounded production JSON encode/decode and reports the real limit, before Save starts. */
    public NBTTagCompound checkEncoded(String tendency) throws Exception {
        HologramSession s = session();
        require(s.processingReviewed && s.virtualPreset != null, "actual reviewed processed session required");
        long started = System.nanoTime();
        byte[] bytes = HologramBlueprintSharing
            .encodeBounded(new GsonBuilder().create(), s.virtualPreset, HologramBlueprintSharing.MAX_BYTES);
        HologramPresets.Preset readback = HologramBlueprintSharing.decode(new String(bytes, StandardCharsets.UTF_8));
        require(readback.cells.size() == CELLS, "64k encode/readback lost cells");
        NBTTagCompound row = new NBTTagCompound();
        row.setString("kind", tendency);
        row.setInteger("jsonBytes", bytes.length);
        row.setInteger("jsonLimit", HologramBlueprintSharing.MAX_BYTES);
        row.setInteger("artifactLimit", HologramBlueprintArtifact.MAX_BYTES);
        row.setInteger("cells", readback.cells.size());
        row.setLong("encodeReadbackNanos", System.nanoTime() - started);
        row.setString("save", "NOT_RUN: encode/readback is not JSON+PNG/owner ACK");
        result.setTag(tendency + "Encoding", row);
        return (NBTTagCompound) row.copy();
    }

    /** Supply once, from the actual quote, to real finite ME cells; subsequent product actions alone pay. */
    public void fundPlacement(String tendency) throws Exception {
        require(ready() && suppliedKind.isEmpty(), "no outstanding supplied placement");
        requireOwned(bankOrigin, 32);
        HologramSession s = session();
        require(
            s.virtualPlacement && s.virtualLocked && s.capture.cells.size() == CELLS,
            "locked actual 64k placement required");
        int[] target = origin(tendency);
        requireOwned(target, SIDE);
        require(s.x == target[0] && s.y == target[1] && s.z == target[2], "manifest target anchor differs");
        HologramResourcePlanner.refreshPlan(s);
        require(s.blueprintTotalResources != null, "actual total material quote required");
        bankBefore = bank.receipt();
        euBefore = WirelessNetworkManager.getUserEU(actor.getUniqueID());
        wirelessStoredBefore = wirelessStored();
        euSupplied = s.blueprintTotalResources.energy;
        if (euSupplied.signum() > 0) {
            require(
                WirelessNetworkManager.addEUToGlobalEnergyMap(actor.getUniqueID(), euSupplied),
                "finite quoted wireless EU fixture supply");
            fixtureEuCredits = fixtureEuCredits.add(euSupplied);
        }
        bank.supply(s.blueprintTotalResources);
        suppliedKind = tendency;
        s.materialSettings.main = s.materialSettings.containers = false;
        s.materialSettings.me = true;
        HologramMaterials.write(actor.getHeldItem(), s.materialSettings);
        s.materialContext = new HologramMaterials.Context(actor, s.materialSettings);
        HologramResourcePlanner.refreshPlan(s);
        result.setTag(tendency + "BankSupplied", bank.receipt());
        result.setString(tendency + "QuotedEU", euSupplied.toString());
        result.setInteger(tendency + "PlanMissing", s.planMissing);
        require(s.planMissing == 0, "finite native bank must make actual plan fully payable: " + s.status);
        HologramSnapshots.send(s);
    }

    /** Start an independent, bounded 64k comparison only after actual product terminal completion. */
    public void checkPlacement(String tendency) throws Exception {
        HologramSession s = session();
        require(tendency.equals(suppliedKind), "placement has the exact outstanding funded cycle");
        require(
            s.job == 3 && s.cursor == s.work.size(),
            "actual production placement must finish before postcheck: " + s.status);
        bank.verifyPaid();
        BigInteger after = WirelessNetworkManager.getUserEU(actor.getUniqueID());
        BigInteger nativeUptake = wirelessStored().subtract(wirelessStoredBefore);
        require(
            nativeUptake.signum() >= 0 && after.equals(euBefore.subtract(nativeUptake)),
            "actual wireless EU net debit differs from quote plus observed native hatch uptake");
        NBTTagCompound ledger = bank.receipt();
        ledger.setTag("beforeSupply", bankBefore);
        ledger.setString("euBefore", euBefore.toString());
        ledger.setString("euSupplied", euSupplied.toString());
        ledger.setString("euAfter", after.toString());
        ledger.setString("nativeWirelessHatchUptake", nativeUptake.toString());
        ledger.setBoolean("exactStockExhausted", true);
        ledger.setInteger("actualJobCompleted", s.completed);
        result.setTag(tendency + "Paid", ledger);
        bank.nextCycle();
        suppliedKind = "";
        startCheck(tendency, origin(tendency));
    }

    /** Common raw backend entry; native acceptance submits these same coordinate/ID-only requests over Netty. */
    public NBTTagCompound rawCommon(String action, String id) throws Exception {
        require(actor instanceof FakePlayer, "connected raw actions must originate through actual client Netty");
        NBTTagCompound command = rawRequest(action, id);
        NBTTagCompound reply = HologramCutService.execute(actor, command);
        require(reply.getBoolean("success"), reply.getString("message"));
        return reply;
    }

    public NBTTagCompound rawRequest(String action, String id) throws Exception {
        require(ready(), "baseline ready before raw operation");
        requireOwned(source, SIDE);
        int[] target = origin("raw");
        requireOwned(target, SIDE);
        NBTTagCompound command = new NBTTagCompound();
        command.setString("action", action);
        command.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        if ("cut".equals(action)) {
            cutId = command.getString("requestId");
            command.setString("name", "Composite real survival 64k");
            command.setInteger("x1", source[0]);
            command.setInteger("y1", source[1]);
            command.setInteger("z1", source[2]);
            command.setInteger("x2", source[0] + 39);
            command.setInteger("y2", source[1] + 39);
            command.setInteger("z2", source[2] + 39);
        } else {
            require("paste".equals(action), "only fixture cut/paste admitted");
            command.setString("id", id == null || id.isEmpty() ? cutId : id);
            command.setInteger("originX", target[0]);
            command.setInteger("originY", target[1]);
            command.setInteger("originZ", target[2]);
        }
        return command;
    }

    public void checkRaw() throws Exception {
        startCheck("raw", origin("raw"));
    }

    public void complete() throws Exception {
        require(ready(), "postchecks must complete");
        require(
            result.getBoolean("selfPassed") && result.getBoolean("sharePassed") && result.getBoolean("rawPassed"),
            "all three independent postchecks required");
        if (!(actor instanceof FakePlayer)) require(
            result.getBoolean("selfNativeSave") && result.getBoolean("shareNativeSave"),
            "both exact production owner Save commits required");
        result.setString("status", actor instanceof FakePlayer ? "COMMON_BACKEND_COMPLETE" : "PASS");
        result.setLong("elapsedNanos", System.nanoTime() - totalStarted);
        finished = true;
    }

    public NBTTagCompound report() {
        NBTTagCompound detached = (NBTTagCompound) result.copy();
        detached.setInteger("baselineCells", baselineCursor);
        detached.setInteger("postcheckCells", checkCursor);
        detached.setString("postcheckKind", checkKind);
        return detached;
    }

    private void startCheck(String kind, int[] target) throws Exception {
        require(ready(), "one independent check at a time");
        requireOwned(target, SIDE);
        checkKind = kind;
        checkTarget = target;
        checkCursor = checked = tileChecked = omittedVolatileFields = 0;
        mismatchSamples.clear();
        operationStarted = System.nanoTime();
        result.setString("status", "CHECK_" + kind);
    }

    private void finishCheck() throws Exception {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("kind", checkKind);
        row.setInteger("cells", checked);
        row.setInteger("tiles", tileChecked);
        row.setInteger("explicitlyOmittedVolatileFields", omittedVolatileFields);
        row.setLong("elapsedNanos", System.nanoTime() - operationStarted);
        row.setString("mismatches", mismatchSamples.toString());
        row.setBoolean("passed", mismatchSamples.isEmpty());
        checks.appendTag(row);
        result.setBoolean(checkKind + "Passed", mismatchSamples.isEmpty());
        String kind = checkKind;
        checkKind = "";
        result.setString("status", "READY");
        require(mismatchSamples.isEmpty(), "Independent " + kind + " world data mismatch " + mismatchSamples);
    }

    private void observeSave() {
        HologramSession s = HologramWorkspace.find(actor.getUniqueID());
        if (s == null || s.player != actor
            || !s.blueprintOperationSuccess
            || s.blueprintDirty
            || !s.blueprintSaveReceipt.isEmpty()
            || s.blueprintSaveJsonSha256.isEmpty()
            || s.blueprintSavePreviewSha256.isEmpty()
            || s.processingPolicy == null) return;
        String identity = s.id + ":" + s.blueprintSaveJsonSha256;
        if (identity.equals(savedSession)) return;
        savedSession = identity;
        String kind = s.processingPolicy.tendency;
        result.setBoolean(kind + "NativeSave", !(actor instanceof FakePlayer) && actor.playerNetServerHandler != null);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("session", s.id);
        row.setString("localId", s.blueprintSaveLocalId);
        row.setString("jsonSha256", s.blueprintSaveJsonSha256);
        row.setString("pngSha256", s.blueprintSavePreviewSha256);
        row.setBoolean("ownerCommit", s.blueprintOperationSuccess && !s.blueprintDirty);
        result.setTag(kind + "SaveCommit", row);
        result.setString(
            "nativeSave",
            "OBSERVED_OWNER_COMMIT; addon must independently verify exact client files and PNG source hash");
    }

    private NBTTagCompound read(int[] origin, int index) {
        int x = origin[0] + index % SIDE, y = origin[1] + index / (SIDE * SIDE), z = origin[2] + index / SIDE % SIDE;
        NBTTagCompound row = new NBTTagCompound();
        row.setString("block", String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))));
        row.setInteger("meta", world.getBlockMetadata(x, y, z));
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null) {
            NBTTagCompound nativeData = new NBTTagCompound();
            tile.writeToNBT(nativeData);
            row.setTag("tile", nativeData);
        }
        return row;
    }

    private NBTTagCompound normalized(NBTTagCompound input, String kind, boolean expected) throws Exception {
        NBTTagCompound out = (NBTTagCompound) input.copy();
        if (!out.hasKey("tile", 10)) return out;
        NBTTagCompound tile = out.getCompoundTag("tile");
        // Native save location is independently checked by the direct read coordinate. No product transform is called.
        for (String key : new String[] { "x", "y", "z", "mTickTimer", "mLastUpdate", "mLastUpdateTime", "mMachine",
            "mUpdated", "mUpdate", "mNeedsUpdate", "mStartUpCheck", "mRuntime", "mRunTime", "mProgresstime",
            "mMaxProgresstime", "lastTime", "lastTick", "lastUpdate", "updateCount", "lastMetadata", "channelCount",
            "usedChannels" }) {
            if (tile.hasKey(key)) {
                tile.removeTag(key);
                omittedVolatileFields++;
            }
        }
        normalizeAeGrid(tile);
        if ("share".equals(kind)) {
            // This schema is deliberately explicit. Unknown identity/config differences fail the independent oracle.
            for (String key : new String[] { "Owner", "OwnerUUID", "owner", "ownerUUID", "mOwner", "mOwnerName",
                "mOwnerUuid", "mOwnerUUID", "UUID", "uuid", "SecurityKey", "securityKey", "lastOutput", "customName",
                "CustomName" }) tile.removeTag(key);
        }
        // GTSWN native gateways: own source network for raw/self, explicit destination network for share.
        if (tile.hasKey("anchorDim", 3) && tile.hasKey("anchorX", 3)) {
            int z = bankOrigin[2] + 3 + ("share".equals(kind) ? 14 : 0);
            if (expected) {
                tile.setInteger("anchorDim", world.provider.dimensionId);
                tile.setInteger("anchorX", bankOrigin[0] + 3);
                tile.setInteger("anchorY", bankOrigin[1] + 3);
                tile.setInteger("anchorZ", z);
            }
            // Native generation is a runtime capability, separately checked by actual native binding and active node.
            tile.removeTag("anchorGeneration");
            tile.removeTag("anchorSchema");
        }
        return out;
    }

    private void normalizeAeGrid(NBTTagCompound tile) {
        for (Object raw : new HashSet<Object>(tile.func_150296_c())) {
            String key = String.valueOf(raw);
            NBTBase value = tile.getTag(key);
            if (value instanceof NBTTagCompound
                && (key.equals("proxy") || key.equals("node") || key.equals("grid") || key.equals("GridNode"))) {
                NBTTagCompound node = (NBTTagCompound) value;
                for (String runtime : new String[] { "g", "k", "p" }) if (node.hasKey(runtime)) {
                    node.removeTag(runtime);
                    omittedVolatileFields++;
                }
            }
        }
    }

    private HologramSession session() throws IOException {
        HologramSession s = HologramWorkspace.find(actor.getUniqueID());
        require(
            s != null && s.player == actor && HologramSessionPolicy.valid(s),
            "live owned production session required");
        return s;
    }

    private BigInteger wirelessStored() {
        BigInteger sum = BigInteger.ZERO;
        // Iterate loaded native tile objects once at payment boundaries, never call native recipe/check methods.
        for (Object candidate : new ArrayList<Object>(world.loadedTileEntityList))
            if (candidate instanceof BaseMetaTileEntity) {
                BaseMetaTileEntity base = (BaseMetaTileEntity) candidate;
                Object meta = base.getMetaTileEntity();
                if (meta != null && meta.getClass()
                    .getSimpleName()
                    .contains("Wireless")
                    && meta.getClass()
                        .getSimpleName()
                        .contains("Energy"))
                    sum = sum.add(BigInteger.valueOf(base.getStoredEU()));
            }
        return sum;
    }

    private static NBTTagCompound request(HologramSession s) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", s.id);
        request.setLong("planRevision", s.planRevision);
        return request;
    }

    private int[] origin(String kind) throws IOException {
        int[] position = manifest.getIntArray(kind);
        require(
            position.length == 3 && Arrays.asList("source", "bank", "self", "share", "raw")
                .contains(kind),
            "fixed manifest origin required");
        return position;
    }

    private void requireOwned(int[] position, int size) throws IOException {
        require(
            TestLab.isEnabled() && world != null
                && !world.isRemote
                && world.provider instanceof TestLabWorldProvider
                && actor != null
                && actor.worldObj == world
                && !actor.isDead
                && !actor.capabilities.isCreativeMode
                && actor.capabilities.allowEdit
                && ItemNekoHologramProjector.isProjector(actor.getHeldItem())
                && TestLab.isOwnedFixture(
                    world,
                    actor,
                    position[0],
                    position[1],
                    position[2],
                    size,
                    size == 32 ? 16 : size,
                    size),
            "active exact owned composite laboratory actor and leased facility required");
    }

    private ItemStack quantumTerminal() throws IOException {
        for (Object candidate : Item.itemRegistry) if (candidate instanceof Item && candidate.getClass()
            .getName()
            .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal"))
            return new ItemStack((Item) candidate);
        throw new IOException("Actual registered GTSWN quantum terminal is required");
    }

    private void bind(ItemStack terminal, int x, int y, int z) throws Exception {
        terminal.getItem()
            .onItemUseFirst(terminal, actor, world, x, y, z, 1, .5f, .5f, .5f);
        Class<?> type = terminal.getItem()
            .getClass();
        require(
            Boolean.TRUE.equals(
                type.getMethod("isBound", ItemStack.class)
                    .invoke(null, terminal)),
            "native quantum terminal binding");
        int[] anchor = (int[]) type.getMethod("getAnchor", ItemStack.class)
            .invoke(null, terminal);
        require(
            Arrays.equals(anchor, new int[] { world.provider.dimensionId, x, y, z }),
            "native terminal exact anchor");
    }

    private static String abbreviate(NBTTagCompound row) {
        String value = row.toString();
        return value.length() > 350 ? value.substring(0, 350) : value;
    }

    private static String hash(List<NBTTagCompound> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (NBTTagCompound row : rows) digest.update(
            row.toString()
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException("Composite lab: " + message);
    }
}

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/** Explicit, read-only private-input acceptance. Never registered or invoked by ordinary product startup. */
public final class HologramV0116MaterialAcceptanceProbe implements AutoCloseable {

    public static final String INPUT_SHA256 = "6753399d9805fb6d32ab57520894e1ac79808f30f6fc8b73dc655d3cc3e36bd5";
    private static final int X = 1000000, Y = 64, Z = 1000000;
    private static HologramV0116MaterialAcceptanceProbe observer;
    private final EntityPlayerMP actor;
    private final WorldServer world;
    private final HologramSession session;
    private final NBTTagCompound receipt = new NBTTagCompound();
    private final Object preparation;
    private final Method step;
    private final boolean metricsEnabled, creative, allowEdit;
    private final double actorX, actorY, actorZ;
    private final ItemStack originalVoucherSlot;
    private final Path input;
    private final long startedTick;
    private boolean complete, closed;
    private long lastTick = Long.MIN_VALUE;
    private NBTTagCompound observedEnvelope;
    private int observedEnvelopes;

    /** Product calls this only at the material envelope boundary; ordinary startup retains no observer or data. */
    static void observeMaterialEnvelope(EntityPlayerMP player, NBTTagCompound envelope) {
        if (!Boolean.getBoolean("gtmb.v0116.materialProbe")) return;
        HologramV0116MaterialAcceptanceProbe current = observer;
        if (current == null || current.actor != player) return;
        current.observedEnvelope = (NBTTagCompound) envelope.copy();
        current.observedEnvelopes++;
    }

    public static HologramV0116MaterialAcceptanceProbe create(WorldServer world, EntityPlayerMP actor)
        throws Exception {
        require(Boolean.getBoolean("gtmb.v0116.materialProbe"), "explicit v0116 material probe opt-in required");
        require(
            world != null && actor instanceof FakePlayer
                && actor.worldObj == world
                && !world.isRemote
                && world.getWorldInfo()
                    .getWorldName()
                    .matches("hologram-smoke-v0115-[A-Za-z0-9_-]{1,20}"),
            "owned legacy acceptance world and native FakePlayer required");
        require(world.playerEntities.isEmpty(), "no logged-in players admitted");
        return new HologramV0116MaterialAcceptanceProbe(world, actor);
    }

    private HologramV0116MaterialAcceptanceProbe(WorldServer world, EntityPlayerMP actor) throws Exception {
        this.world = world;
        this.actor = actor;
        startedTick = world.getTotalWorldTime();
        metricsEnabled = HologramRuntimeMetrics.isEnabled();
        creative = actor.capabilities.isCreativeMode;
        allowEdit = actor.capabilities.allowEdit;
        actorX = actor.posX;
        actorY = actor.posY;
        actorZ = actor.posZ;
        originalVoucherSlot = actor.inventory.mainInventory[1];
        input = Paths.get(System.getProperty("gtmb.v0116.blueprintInput", ""))
            .toRealPath();
        require(Files.isRegularFile(input) && !Files.isSymbolicLink(input), "regular local blueprint input required");
        byte[] bytes = Files.readAllBytes(input); // One parsing read; a final streaming hash detects external changes.
        require(
            INPUT_SHA256.equals(
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(bytes))),
            "private input SHA-256 mismatch");
        HologramPresets.Preset preset = HologramBlueprintSharing.decode(new String(bytes, StandardCharsets.UTF_8));
        require(preset.cells.size() == 48003, "native decoded input must contain all 48003 cells");
        require(
            count(preset, "chisel:") == 31000 && count(preset, "gregtech:") == 16999 && count(preset, "gtswn:") == 4,
            "private input category totals mismatch");
        for (HologramPresets.Cell cell : preset.cells) require(
            !cell.excluded && cell.x >= 0
                && cell.x <= 159
                && cell.y >= 0
                && cell.y <= 31
                && cell.z >= 0
                && cell.z <= 47,
            "admitted input geometry/exclusions changed");
        receipt.setString("status", "PREPARING");
        receipt.setString("inputSha256", INPUT_SHA256);
        receipt.setInteger("inputCells", 48003);
        receipt.setInteger("privateParsingReads", 1);
        receipt.setInteger("privateFinalHashReads", 0);
        receipt.setBoolean("privateInputCopied", false);
        receipt.setBoolean("privateNbtPersisted", false);
        receipt.setBoolean("actualNetty", false);
        receipt.setBoolean("actualClientUi", false);
        receipt.setBoolean("survivalConstructionClaim", false);
        receipt.setString(
            "entry",
            "native decode -> Projection.populateVirtual/ResourcePlanner -> real SnapshotPreparation.Task.step/finish -> MaterialWorkflow.action");
        receipt.setString(
            "transportBoundary",
            "retired native SnapshotTicket skips disconnected FakePlayer full delivery; real preparation and quote finalization retained");
        receipt.setString(
            "readObservationBoundary",
            "full-cell visits/builds, cellRow/plan activities, cache identity/revision and inventory snapshot tick; individual World getters are not instrumented");
        try {
            actor.capabilities.isCreativeMode = true;
            actor.capabilities.allowEdit = true;
            actor.setPosition(X + .5, Y, Z + .5);
            loadFixtureChunks();
            session = new HologramSession(actor, X, Y, Z, false, 1);
            session.virtual = session.virtualPlacement = session.virtualLocked = true;
            session.blueprintAwaitingConfirmation = session.placementReviewIssued = true;
            session.materialSettings.main = true;
            session.materialSettings.containers = session.materialSettings.me = false;
            session.virtualPreset = preset;
            HologramBlueprintProjection.populateVirtual(session);
            require(
                !session.capture.incomplete && session.capture.cells.size() == 48003,
                "real native projection incomplete: " + session.capture.failure);
            require(HologramSessionPolicy.valid(session), "native creative locked placement session admission failed");
            for (HologramCapture.Cell cell : session.capture.cells)
                require("pending".equals(cell.status), "native classification rejected cell status: " + cell.status);
            actor.inventory.mainInventory[1] = HologramDecorationVoucher.stack();
            require(HologramDecorationVoucher.available(actor) > 0, "actual registered voucher required");
            HologramWorkspace.activate(actor.getUniqueID(), session);
            HologramNetwork.SnapshotTicket ticket = HologramNetwork.reserveOwnedState(session);
            require(ticket != null, "native snapshot ticket allocation failed");
            HologramNetwork.cancelState(ticket); // Preparation remains real; no FakePlayer delivery is claimed.
            Class<?> task = Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation$Task");
            Constructor<?> constructor = task
                .getDeclaredConstructor(HologramSession.class, HologramNetwork.SnapshotTicket.class);
            constructor.setAccessible(true);
            preparation = constructor.newInstance(session, ticket);
            step = task.getDeclaredMethod("step", long.class, java.util.function.LongSupplier.class);
            step.setAccessible(true);
            HologramRuntimeMetrics.setEnabled(true);
        } catch (Exception | LinkageError failure) {
            HologramWorkspace.clear();
            actor.inventory.mainInventory[1] = originalVoucherSlot;
            actor.capabilities.isCreativeMode = creative;
            actor.capabilities.allowEdit = allowEdit;
            actor.setPosition(actorX, actorY, actorZ);
            HologramRuntimeMetrics.setEnabled(metricsEnabled);
            throw failure;
        }
    }

    /** Once per natural server END tick; background metadata reports keep their actual worker lifecycle. */
    public NBTTagCompound poll() throws Exception {
        require(!closed, "material probe already closed");
        if (complete) return (NBTTagCompound) receipt.copy();
        long tick = world.getTotalWorldTime();
        require(tick != lastTick, "material probe must be polled on natural ticks");
        lastTick = tick;
        require(tick - startedTick < 3600, "real material preparation deadline expired");
        loadFixtureChunks();
        step.invoke(preparation, System.nanoTime() + 8_000_000L, (java.util.function.LongSupplier) System::nanoTime);
        receipt.setInteger("preparationStage", ((Number) get(preparation, "stage")).intValue());
        if (!((Boolean) get(preparation, "encoding"))) return (NBTTagCompound) receipt.copy();
        NBTTagCompound state = (NBTTagCompound) get(preparation, "state");
        require(
            state.getTagList("cells", 10)
                .tagCount() == 48003 && !state.getBoolean("quotesIncomplete")
                && !state.getBoolean("planIncomplete"),
            "real complete snapshot preparation required");
        require(
            HologramMaterialQuote.cached(session) && session.materialQuote.getBoolean("complete"),
            "creative quote cache incomplete");
        verifyNativeDemands();
        NBTTagCompound ordinary = summarize(session.materialQuote);
        require(
            ordinary.getLong("chiselRequired") == 31000 && ordinary.getLong("gregtechRequired") == 16999,
            "creative native quote omitted Chisel or GT shells/machines");
        require(
            ordinary.getInteger("fluidRows") > 0 && ordinary.getLong("fluidRequired") > 0,
            "native fluid resource rows missing");
        receipt.setTag("ordinary", ordinary);
        receipt.setString("fullPreparedNbtSha256", digest(state));
        NBTTagCompound before = HologramRuntimeMetrics.snapshot();
        long revision = session.planRevision, inventoryTick = session.materialQuote.getLong("inventorySnapshotTick");
        long nonce = session.placementReviewNonce;
        HologramMaterialQuote.Cache cache = session.materialQuoteCache;
        NBTTagList initialInventory = actor.inventory.writeToNBT(new NBTTagList());
        long metadataBytes = 0, maxMetadataBytes = 0;
        require(observer == null, "one isolated metadata observer at a time");
        observer = this;
        HologramWorkspace.activate(actor.getUniqueID(), session);
        for (int i = 0; i < 10; i++) {
            observedEnvelope = null;
            NBTTagCompound action = new NBTTagCompound();
            action.setString("session", session.id);
            action.setLong("planRevision", revision);
            action.setLong("placementReviewNonce", session.placementReviewNonce);
            action.setLong("uiSequence", 1000L + i);
            boolean enabled = i % 2 == 0;
            action.setBoolean("enabled", enabled);
            require(
                HologramMaterialWorkflow.action(session, action, "setDecorativeVoucher"),
                "native voucher operation not handled");
            require(
                session.jobVoucherRequested == enabled && session.placementReviewNonce == nonce + i + 1,
                "native voucher action rejected or nonce not advanced");
            require(
                session.planRevision == revision && session.materialQuoteCache == cache
                    && session.materialQuote.getLong("inventorySnapshotTick") == inventoryTick,
                "voucher action rebuilt plan/cache or reobserved inventory");
            require(
                observedEnvelope != null && observedEnvelopes == i + 1,
                "one actual material envelope per native action required");
            require(observedEnvelope.getBoolean("progressOnly"), "independent material progress envelope required");
            NBTTagCompound metadata = observedEnvelope.getCompoundTag("progress");
            require(
                metadata.getBoolean("materialQuoteOnly") && !metadata.hasKey("cells")
                    && !metadata.hasKey("blueprint")
                    && !metadata.hasKey("regions")
                    && !metadata.getBoolean("workspaceUiOnly")
                    && metadata.getLong("uiSequence") == 1000L + i,
                "voucher acknowledgement contains full geometry/UI intent or wrong request sequence");
            byte[] encoded = HologramSnapshotCodec.compress(observedEnvelope);
            metadataBytes += encoded.length;
            maxMetadataBytes = Math.max(maxMetadataBytes, encoded.length);
            require(encoded.length < 65536, "voucher acknowledgement is not small metadata");
            if (enabled) {
                NBTTagCompound voucher = summarize(session.materialQuote);
                require(
                    voucher.getLong("chiselRequired") == 0 && voucher.getLong("gregtechRequired") == 16999,
                    "voucher must waive Chisel while retaining all GT payment");
                receipt.setTag("voucher", voucher);
            } else require(
                ordinary.equals(summarize(session.materialQuote)),
                "ordinary demand changed after voucher round trip");
        }
        observer = null;
        observedEnvelope = null;
        require(
            initialInventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
            "review toggles consumed or changed stock");
        NBTTagCompound after = HologramRuntimeMetrics.snapshot();
        for (String key : new String[] { "fullCellsSnapshotBuilds", "fullCellsSnapshotCells",
            "fullCellsSnapshotCompressedBytes" })
            require(
                before.getCompoundTag("queues")
                    .getLong(key)
                    == after.getCompoundTag("queues")
                        .getLong(key),
                "voucher action increased " + key);
        for (String key : new String[] { "planStep", "planClassify", "planResources", "cellRow", "snapshotCompress" })
            require(
                before.getCompoundTag("activity")
                    .getCompoundTag(key)
                    .getLong("calls")
                    == after.getCompoundTag("activity")
                        .getCompoundTag(key)
                        .getLong("calls"),
                "voucher action repeated " + key);
        receipt.setTag("metricsBeforeToggles", before);
        receipt.setTag("metricsAfterToggles", after);
        receipt.setInteger("voucherToggles", 10);
        receipt.setLong("metadataCompressedBytesTotal", metadataBytes);
        receipt.setLong("metadataCompressedBytesMax", maxMetadataBytes);
        receipt.setBoolean("fullPreparationCountersUnchanged", true);
        receipt.setBoolean("planRevisionUnchanged", true);
        receipt.setBoolean("inventoryObservationTickUnchanged", true);
        receipt.setBoolean("voucherInventoryUnchanged", true);
        receipt.setBoolean("privateInputSha256Unchanged", INPUT_SHA256.equals(hashFile(input)));
        receipt.setInteger("privateFinalHashReads", 1);
        require(receipt.getBoolean("privateInputSha256Unchanged"), "read-only source changed during acceptance");
        receipt.setString("status", "PASS");
        complete = true;
        return (NBTTagCompound) receipt.copy();
    }

    private void loadFixtureChunks() {
        for (int cx = X >> 4; cx <= (X + 159) >> 4; cx++)
            for (int cz = Z >> 4; cz <= (Z + 47) >> 4; cz++) world.theChunkProviderServer.loadChunk(cx, cz);
    }

    /** Compare every native shell/fluid identity, not just totals that could hide one omitted variant. */
    private void verifyNativeDemands() {
        Map<HologramBlueprintResources.ExactKey, Long> shells = new LinkedHashMap<>(),
            quotedShells = new LinkedHashMap<>();
        Map<HologramBlueprintResources.ExactKey, Long> fluids = new LinkedHashMap<>(),
            quotedFluids = new LinkedHashMap<>();
        for (int i = 0; i < session.capture.cells.size(); i++) {
            HologramCapture.Cell cell = session.capture.cells.get(i);
            ItemStack target = HologramCellPolicy.targetFor(session, cell, i);
            require(target != null, "native projected shell identity missing");
            if (!HologramGtswnPlacementMaterials.freeShell(cell.block, target)) {
                ItemStack shell = HologramEssentia.shellMaterial(target);
                shells.merge(new HologramBlueprintResources.ExactKey(shell, null), 1L, Math::addExact);
            }
        }
        if (session.blueprintResources != null)
            for (HologramBlueprintResources.FluidCost fluid : session.blueprintResources.fluids)
                fluids.merge(new HologramBlueprintResources.ExactKey(null, fluid.stack), fluid.amount, Math::addExact);
        NBTTagList rows = session.materialQuote.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if ("item".equals(row.getString("kind")) && row.getLong("shellRequired") > 0) quotedShells.merge(
                new HologramBlueprintResources.ExactKey(HologramMaterialQuote.readItem(row), null),
                row.getLong("shellRequired"),
                Math::addExact);
            else if ("fluid".equals(row.getString("kind"))) quotedFluids.merge(
                new HologramBlueprintResources.ExactKey(null, HologramMaterialQuote.readFluid(row)),
                row.getLong("required"),
                Math::addExact);
        }
        require(shells.equals(quotedShells), "complete native shell identity/count comparison failed");
        require(fluids.equals(quotedFluids), "complete native fluid identity/count comparison failed");
        receipt.setInteger("nativeShellIdentities", shells.size());
        receipt.setInteger("nativeFluidIdentities", fluids.size());
        receipt.setBoolean("allNativeShellAndFluidDemandsQuoted", true);
    }

    private static NBTTagCompound summarize(NBTTagCompound quote) throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = quote.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            String kind = row.getString("kind");
            result.setInteger(kind + "Rows", result.getInteger(kind + "Rows") + 1);
            result
                .setLong(kind + "Required", Math.addExact(result.getLong(kind + "Required"), row.getLong("required")));
            if ("item".equals(kind))
                for (String mod : new String[] { "chisel", "gregtech", "gtswn" }) if (row.getString("itemRegistry")
                    .startsWith(mod + ":"))
                    result.setLong(
                        mod + "Required",
                        Math.addExact(result.getLong(mod + "Required"), row.getLong("required")));
        }
        // Hash only the detached rows: never write identities, coordinates or original machine payloads.
        NBTTagCompound demand = new NBTTagCompound();
        demand.setTag("rows", rows.copy());
        result.setString("rowsSha256", digest(demand));
        return result;
    }

    private static int count(HologramPresets.Preset preset, String prefix) {
        int result = 0;
        for (HologramPresets.Cell cell : preset.cells)
            if (cell.block != null && cell.block.startsWith(prefix)) result++;
        return result;
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static String digest(NBTTagCompound tag) throws Exception {
        return hex(
            MessageDigest.getInstance("SHA-256")
                .digest(HologramSnapshotCodec.compress(tag)));
    }

    private static String hashFile(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536];
            for (int n; (n = stream.read(bytes)) >= 0;) if (n > 0) digest.update(bytes, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (observer == this) observer = null;
        observedEnvelope = null;
        Object metadata;
        try {
            metadata = get(preparation, "metadata");
            if (metadata instanceof HologramSnapshotMetadataTask) ((HologramSnapshotMetadataTask) metadata).cancel();
        } catch (Exception failure) {
            throw new IllegalStateException("material probe cleanup failed", failure);
        } finally {
            HologramWorkspace.clear();
            actor.inventory.mainInventory[1] = originalVoucherSlot;
            actor.capabilities.isCreativeMode = creative;
            actor.capabilities.allowEdit = allowEdit;
            actor.setPosition(actorX, actorY, actorZ);
            HologramRuntimeMetrics.setEnabled(metricsEnabled);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}

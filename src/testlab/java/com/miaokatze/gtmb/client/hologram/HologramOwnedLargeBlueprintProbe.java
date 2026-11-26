// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.util.FakePlayer;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage;
import com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction;
import com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction.Attempt;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCli;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintSpatialView;
import com.miaokatze.gtmb.register.ProjectorRegistry;

/** Uses the real saved-native catalog, normal lock/material confirmation and real integrated server. */
final class HologramOwnedLargeBlueprintProbe {

    static final String FLAG = "gtmb.testlab.ownedLargeBlueprint";
    static final boolean ENABLED = Boolean.getBoolean(FLAG);
    private static final String TOOL_KEY = "gtitHologramToolId";
    private static final int SYNTHETIC_MAX = 2_823_168, SAMPLE_LIMIT = 64;
    private final Path save;
    private final UUID actorId, toolId = UUID.randomUUID();
    private final String id = "local_" + UUID.randomUUID();
    private final int prefix = Integer.getInteger(FLAG + "Prefix", 0);
    private final long started = System.nanoTime();
    private final Map<String, Object> evidence = new LinkedHashMap<>();

    private WorldServer owned;
    private EntityPlayerMP actor;
    private ItemStack projector;
    private NBTTagList startingInventory;
    private int startingSlot, toolSlot;
    private int ax, ay, az;
    private volatile Catalog catalog;
    private volatile int worldStage;
    private volatile boolean ready, cleared, witnessedFirst, witnessedTerminal, restored;
    private volatile HologramNativeLocalConstruction.Result terminal;
    private volatile int worldMatches;
    private volatile long firstWorldNanos;
    private CompletableFuture<Catalog> file;
    private Object originalStore, temporaryStore;
    private Attempt attempt;
    private int clientStage;
    private boolean cancellationSent;
    private long confirming;

    @Desugar
    private record Witness(int x, int y, int z, String block, int metadata) {}

    @Desugar
    private record Catalog(NativeBlueprintPairStore.Receipt receipt, int elements, int expected,
        NativeBlueprintDefinition.Frame frame, List<Witness> witnesses, String inputSha, String inputKind) {}

    HologramOwnedLargeBlueprintProbe(Path save, UUID actorId) {
        this.save = save;
        this.actorId = actorId;
        require(prefix >= 0 && prefix <= SYNTHETIC_MAX, "Invalid prefix");
    }

    void server(WorldServer world) throws Exception {
        require(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath()
                .equals(save.toRealPath()),
            "Native blueprint probe escaped exact isolated save");
        if (owned == null) {
            if (world.playerEntities.isEmpty()) return;
            require(
                world.playerEntities.size() == 1 && world.playerEntities.get(0) instanceof EntityPlayerMP real
                    && !(real instanceof FakePlayer)
                    && actorId.equals(real.getUniqueID()),
                "Probe has no owned actor");
            if (((EntityPlayerMP) world.playerEntities.get(0)).ticksExisted < 60) return;
            actor = (EntityPlayerMP) world.playerEntities.get(0);
            require(
                actor.worldObj == world && actor.dimension == 0
                    && actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL,
                "Expected fresh survival actor");
            owned = world;
        }
        require(
            owned == world && actor.worldObj == world
                && world.playerEntities.size() == 1
                && world.playerEntities.get(0) == actor,
            "Owned actor changed");
        if (catalog == null) return;
        if (!ready) {
            stageWorld(catalog);
            ready = true;
            return;
        }
        if (worldStage == 1 && !cleared) {
            require(
                world.getBlock(ax, ay - 1, az) == Blocks.glass && world.setBlockToAir(ax, ay - 1, az),
                "Owned temporary lock support did not clear");
            for (var w : catalog.witnesses) require(air(w), "Pre-confirmation sample already occupied " + w);
            cleared = true;
        } else if (worldStage >= 2 && !witnessedTerminal) {
            int matches = 0;
            for (var w : catalog.witnesses) if (matches(w)) matches++;
            if (matches > 0 && !witnessedFirst) {
                firstWorldNanos = System.nanoTime();
                witnessedFirst = true;
            }
            if (terminal == null) return;
            worldMatches = matches;
            witnessedTerminal = true;
            restoreFixtureActor();
            restored = true;
        }
    }

    boolean client(Minecraft mc, Map<String, Object> out) throws Exception {
        require(System.nanoTime() - started < 435_000_000_000L, "Large native timeout at stage " + clientStage);
        require(
            mc.thePlayer.getUniqueID()
                .equals(actorId) && mc.isIntegratedServerRunning(),
            "Not the owned client");
        if (file == null) {
            Path isolated = save.resolve("gtmb-owned-blueprint-catalog");
            Files.createDirectory(isolated);
            temporaryStore = new HologramClientLibrary(isolated);
            var actorName = mc.thePlayer.getCommandSenderName();
            file = CompletableFuture.supplyAsync(() -> {
                try {
                    return prepare(((HologramClientLibrary) temporaryStore).directory(), actorName);
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            return false;
        }
        if (catalog == null) {
            if (!file.isDone()) return false;
            try {
                catalog = file.get();
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                var stages = new ArrayList<String>();
                for (int i = 0; i < 4 && cause != null; i++) {
                    var stack = cause.getStackTrace();
                    stages.add(
                        cause.getClass()
                            .getName() + (stack.length == 0 ? "" : " at " + stack[0]));
                    if (cause.getCause() == cause) break;
                    cause = cause.getCause();
                }
                evidence.put("stagingFailure", stages);
                out.put("largeBlueprint", evidence);
                throw new IllegalStateException(
                    "Native test file could not be staged: " + String.join(" <- ", stages),
                    failure.getCause());
            }
            evidence.put("input", catalog.inputKind);
            evidence.put("sourceSha256", catalog.inputSha);
            evidence.put("publishedNativeSha256", catalog.receipt.physicalHash);
            evidence.put("nativeFileElements", catalog.elements);
            evidence.put("expectedConstructionTargets", catalog.expected);
            out.put("largeBlueprint", evidence);
        }
        if (!ready) return false;
        var placement = HologramLocalPlacement.INSTANCE;
        switch (clientStage) {
            case 0 -> {
                ItemStack held = mc.thePlayer.getHeldItem();
                if (!ours(held)) return false;
                var library = HologramLibraryClient.INSTANCE;
                originalStore = library.privateStore();
                field(HologramLibraryClient.class, "store").set(library, temporaryStore);
                require(
                    library.previewLocal(id, true, catalog.receipt),
                    "Saved native catalog placement click refused");
                clientStage++;
            }
            case 1 -> {
                if (!placement.active() || mc.theWorld.getBlock(ax, ay - 1, az) != Blocks.glass) return false;
                require(placement.display().nativeViewport != null, "Saved source degraded to legacy display");
                var hit = mc.objectMouseOver;
                try {
                    mc.objectMouseOver = new MovingObjectPosition(
                        ax,
                        ay - 1,
                        az,
                        1,
                        Vec3.createVectorHelper(ax + .5, ay, az + .5));
                    placement.use(false);
                } finally {
                    mc.objectMouseOver = hit;
                }
                require(
                    placement.locked() && Arrays.equals(placement.anchor(), new int[] { ax, ay, az }),
                    "Normal ghost lock failed or used a different anchor");
                placement.use(false);
                require(mc.currentScreen instanceof HologramLibraryMaterialReviewScreen, "No normal material review");
                worldStage = 1;
                clientStage++;
            }
            case 2 -> {
                require(mc.currentScreen instanceof HologramLibraryMaterialReviewScreen, "Normal review retired early");
                var review = (HologramLibraryMaterialReviewScreen) mc.currentScreen;
                if (!cleared || !review.localSubmitAvailable()) return false;
                confirming = System.nanoTime();
                worldStage = 2;
                review.localAction(LocalAction.SUBMIT);
                evidence.put("ordinaryMaterialConfirm", 1);
                clientStage++;
            }
            case 3 -> {
                if (attempt == null) attempt = attempt();
                if (attempt == null) return false;
                if (!Boolean.TRUE.equals(evidence.get("manuallyClosedDuringConstruction"))) {
                    require(
                        mc.currentScreen instanceof HologramLibraryMaterialReviewScreen,
                        "Construction automatically closed the normal confirmation UI");
                    require(
                        placement.worldDisplay(System.nanoTime()) != null,
                        "Active construction did not retain its world projection");
                    mc.displayGuiScreen(null);
                    evidence.put("manuallyClosedDuringConstruction", true);
                }
                var result = attempt.result();
                if (prefix > 0 && !cancellationSent
                    && result.placed >= prefix
                    && result.phase == HologramNativeLocalConstruction.Phase.EXECUTING) {
                    var ticket = HologramBackgroundTasks.ownedPlacementTicket(attempt.id);
                    require(
                        ticket != null && HologramBackgroundTasks.cancelOwnedPlacement(ticket),
                        "Normal owned-HUD cancel refused");
                    cancellationSent = true;
                }
                if (result.phase == HologramNativeLocalConstruction.Phase.PREPARING
                    || result.phase == HologramNativeLocalConstruction.Phase.EXECUTING) return false;
                evidence.put("receiptPhase", result.phase.name());
                evidence.put("receiptDetail", result.detail);
                evidence.put("receiptPlaced", result.placed);
                evidence.put("receiptSatisfied", result.satisfied);
                evidence.put("receiptTotal", result.total);
                terminal = result;
                clientStage++;
            }
            case 4 -> {
                if (!witnessedTerminal || !restored) return false;
                require(
                    placement.worldDisplay(System.nanoTime()) == null,
                    "World projection survived the terminal placement receipt");
                evidence.put("projectionClearedAtTerminal", true);
                evidence.put("worldMatches", worldMatches);
                evidence.put("worldSamples", catalog.witnesses.size());
                evidence.put("firstObservedWriteNanos", witnessedFirst ? firstWorldNanos - confirming : null);
                evidence.put("verifyCreativeNoDebit", true);
                require(
                    terminal.total == catalog.expected && terminal.satisfied == 0 && witnessedFirst,
                    "No real world write or incorrect exact construction receipt: " + terminal.detail);
                if (prefix == 0) require(
                    terminal.phase == HologramNativeLocalConstruction.Phase.COMPLETE
                        && terminal.placed == catalog.expected
                        && worldMatches == catalog.witnesses.size(),
                    "Actual full construction or matching world sample failed: " + terminal.detail);
                else require(
                    terminal.phase == HologramNativeLocalConstruction.Phase.CANCELLED && cancellationSent
                        && terminal.placed >= prefix
                        && terminal.placed < catalog.expected
                        && worldMatches > 0,
                    "Bounded prefix/cancel did not prove committed world writes and clean stop: " + terminal.detail);
                evidence.put("passed", true);
                return true;
            }
            default -> throw new IllegalStateException("Invalid native test stage");
        }
        evidence.put("stage", clientStage);
        return false;
    }

    void clientCleanup(Minecraft mc) throws Exception {
        HologramLocalPlacement.INSTANCE.clear();
        if (temporaryStore != null && originalStore != null)
            field(HologramLibraryClient.class, "store").set(HologramLibraryClient.INSTANCE, originalStore);
        if (file != null && !file.isDone()) file.cancel(true);
        if (mc.theWorld != null) mc.displayGuiScreen(null);
    }

    private void stageWorld(Catalog data) {
        ax = (int) Math.floor(actor.posX);
        az = (int) Math.floor(actor.posZ);
        var f = data.frame;
        ay = Math.toIntExact(48L + f.anchorY - f.minY);
        long lowX = (long) ax + f.minX - f.anchorX, lowZ = (long) az + f.minZ - f.anchorZ;
        require(
            ay >= 42 && ay < 250 && f.sizeY <= 200 && f.sizeX <= 160 && f.sizeZ <= 160,
            "Archive footprint does not fit the owned compact test world");
        int cx0 = Math.toIntExact(Math.min(ax, lowX)) >> 4;
        int cz0 = Math.toIntExact(Math.min(az - 3L, lowZ)) >> 4;
        int cx1 = Math.toIntExact(Math.max(ax, lowX + f.sizeX - 1)) >> 4;
        int cz1 = Math.toIntExact(Math.max(az, lowZ + f.sizeZ - 1)) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) owned.theChunkProviderServer.loadChunk(cx, cz);
        require(
            owned.isAirBlock(ax, ay - 1, az) && owned.setBlock(ax, ay - 1, az, Blocks.glass),
            "Cannot stage temporary support in empty test site");
        startingInventory = inventory();
        startingSlot = actor.inventory.currentItem;
        toolSlot = -1;
        for (int i = 0; i < 9; i++) if (actor.inventory.getStackInSlot(i) == null) {
            toolSlot = i;
            break;
        }
        require(toolSlot >= 0 && actor.inventory.getStackInSlot(toolSlot) == null, "No empty fixture hotbar slot");
        var tag = new NBTTagCompound();
        tag.setString(TOOL_KEY, toolId.toString());
        projector = new ItemStack(ProjectorRegistry.projector);
        projector.setTagCompound(tag);
        actor.inventory.setInventorySlotContents(toolSlot, projector);
        actor.inventory.currentItem = toolSlot;
        actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(toolSlot));
        actor.setGameType(GameType.CREATIVE);
        actor.capabilities.isFlying = true;
        actor.sendPlayerAbilities();
        actor.playerNetServerHandler.setPlayerLocation(ax + .5, ay + 1., az - 2.5, 0F, 0F);
        actor.inventoryContainer.detectAndSendChanges();
    }

    private void restoreFixtureActor() {
        require(ours(actor.inventory.getStackInSlot(toolSlot)), "Fixture projector identity changed");
        actor.inventory.setInventorySlotContents(toolSlot, null);
        actor.inventory.currentItem = startingSlot;
        actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(startingSlot));
        require(startingInventory.equals(inventory()), "Creative construction changed start inventory");
        actor.setGameType(GameType.SURVIVAL);
        actor.sendPlayerAbilities();
        actor.inventoryContainer.detectAndSendChanges();
    }

    private boolean ours(ItemStack item) {
        return item != null && item.getItem() == ProjectorRegistry.projector
            && item.hasTagCompound()
            && toolId.toString()
                .equals(
                    item.getTagCompound()
                        .getString(TOOL_KEY));
    }

    private NBTTagList inventory() {
        return (NBTTagList) actor.inventory.writeToNBT(new NBTTagList())
            .copy();
    }

    private boolean matches(Witness w) {
        return !air(w)
            && w.block.equals(Block.blockRegistry.getNameForObject(owned.getBlock(ax + w.x, ay + w.y, az + w.z)))
            && owned.getBlockMetadata(ax + w.x, ay + w.y, az + w.z) == w.metadata;
    }

    private boolean air(Witness w) {
        return owned.isAirBlock(ax + w.x, ay + w.y, az + w.z);
    }

    private Catalog prepare(Path directory, String author) throws Exception {
        String source = System.getProperty(FLAG + "File", "");
        final byte[] bytes;
        final String sha;
        String kind;
        if (source.isEmpty()) {
            int cells = Integer.getInteger(FLAG + "Cells", 32_768);
            require(cells >= 4096 && cells <= SYNTHETIC_MAX, "Invalid synthetic cell count");
            bytes = synthetic(author, cells);
            sha = HologramBlueprintArtifact.hash(bytes);
            kind = "generated three-material native pressure fixture (not original Nxer/DOT)";
        } else {
            byte[] input = pinned(Paths.get(source), FLAG + "Sha256", 128 * 1024 * 1024);
            sha = HologramBlueprintArtifact.hash(input);
            byte[] converted = source.endsWith(".gtnb") ? input
                : NativeBlueprintCli.migratePair(
                    input,
                    pinned(
                        Paths.get(System.getProperty(FLAG + "LegacyPng")),
                        FLAG + "LegacyPngSha256",
                        4 * 1024 * 1024),
                    -1,
                    () -> true).bytes;
            Path raw = save.resolve("gtmb-owned-blueprint-import.gtnb");
            Path staged = save.resolve("gtmb-owned-blueprint-authored.gtnb");
            Files.write(raw, converted, java.nio.file.StandardOpenOption.CREATE_NEW);
            try (var definition = NativeBlueprintDefinition.open(raw)) {
                // The native edit writer truncates an existing destination and refuses symlinks.
                // Claim only this exact path in the owned fixture save, never an existing artifact.
                Files.createFile(staged);
                new com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits(definition)
                    .author(id, "Owned archive construction fixture", author, actorId.toString(), 1L)
                    .write(staged);
            }
            bytes = Files.readAllBytes(staged);
            kind = "user-authorized SHA-pinned archive; only copy's editor/owner header rebound";
        }
        var prepared = HologramNativeBlueprintStorage
            .prepareAuthoredSave(directory, id, bytes, HologramBlueprintArtifact.hash(bytes), previewPng(), () -> true);
        var receipt = HologramNativeBlueprintStorage.activate(directory, prepared, prepared.revision, () -> true);
        try (var definition = NativeBlueprintDefinition.open(receipt.source)) {
            var space = definition.spatial();
            var frame = definition.frame();
            var locations = new ArrayList<Witness>();
            var descriptors = new HashSet<Long>();
            int expected = 0;
            for (var entry : space.counts()
                .entrySet()) {
                JsonObject row = witnessFields(definition, space, entry.getKey());
                if (included(row)) {
                    expected = Math.addExact(expected, entry.getValue());
                    descriptors.add(entry.getKey());
                }
            }
            require(expected >= 4096 && (prefix == 0 || prefix < expected), "Fixture size/prefix not meaningful");
            int samples = prefix == 0 ? SAMPLE_LIMIT : Math.min(space.size(), SAMPLE_LIMIT * 2);
            for (int i = 0; i < samples; i++) {
                // Dense early witnesses cover bounded prefix; distributed witnesses cover a full completion.
                int ordinal = prefix == 0 ? (int) ((long) i * (space.size() - 1) / (samples - 1)) : i;
                var point = space.atOrdinal(ordinal);
                if (!descriptors.contains(point.descriptor)) continue;
                JsonObject row = witnessFields(definition, space, point.descriptor);
                locations.add(
                    new Witness(
                        point.x - frame.anchorX,
                        point.y - frame.anchorY,
                        point.z - frame.anchorZ,
                        row.get("block")
                            .getAsString(),
                        row.has("metadata") ? row.get("metadata")
                            .getAsInt() : 0));
            }
            require(!locations.isEmpty(), "No independent block/metadata witnesses");
            return new Catalog(
                receipt,
                space.size(),
                expected,
                frame,
                Collections.unmodifiableList(locations),
                sha,
                kind);
        }
    }

    private static JsonObject witnessFields(NativeBlueprintDefinition definition, NativeBlueprintSpatialView space,
        long descriptor) throws Exception {
        var fields = definition.element(NativeBlueprintSpatialView.element(descriptor));
        var extra = definition
            .restoredFields(space.wrapper(descriptor), key -> !"/tileNbt".equals(key) && !"/itemNbt".equals(key));
        for (var entry : extra.entrySet()) fields.add(entry.getKey(), entry.getValue());
        return fields;
    }

    private static boolean included(JsonObject row) {
        return row.has("block") && !row.get("block")
            .isJsonNull()
            && !"minecraft:air".equals(
                row.get("block")
                    .getAsString())
            && (!row.has("excluded") || row.get("excluded")
                .isJsonNull()
                || !row.get("excluded")
                    .getAsBoolean());
    }

    private static byte[] pinned(Path path, String property, int max) throws Exception {
        String pin = System.getProperty(property, "");
        require(
            pin.matches("[0-9a-f]{64}") && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) <= max,
            "Archive requires an explicit SHA-256 pin and bounded regular file");
        byte[] bytes = Files.readAllBytes(path);
        require(pin.equals(HologramBlueprintArtifact.hash(bytes)), "Archive SHA-256 disagrees with supplied pin");
        return bytes;
    }

    private byte[] synthetic(String author, int count) throws Exception {
        var header = new JsonObject();
        header.addProperty("id", id);
        header.addProperty("name", "Owned three-material large placement fixture");
        header.addProperty("schema", 2);
        header.addProperty("controllerType", "area");
        header.addProperty("controllerId", -1);
        header.addProperty("controllerFacing", -1);
        header.addProperty("authorName", author);
        header.addProperty("authorUuid", actorId.toString());
        header.addProperty("createdAt", 1L);
        int span = Math.max(32, (int) Math.ceil(Math.sqrt(count / 128.0)));
        String[] blocks = { "minecraft:stonebrick", "minecraft:quartz_block", "minecraft:glowstone" };
        return NativeBlueprintMigration
            .author(header, new JsonObject(), count, new NativeBlueprintMigration.IndexedAuthoredCell() {

                public int coordinate(int n, int axis) {
                    return axis == 0 ? n % span - span / 2 : axis == 1 ? n / (span * span) : n / span % span - span / 2;
                }

                public int descriptor(int n) {
                    return (n / 16) % blocks.length;
                }

                public JsonObject at(int n) {
                    var row = new JsonObject();
                    row.addProperty("x", coordinate(n, 0));
                    row.addProperty("y", coordinate(n, 1));
                    row.addProperty("z", coordinate(n, 2));
                    row.addProperty("block", blocks[descriptor(n)]);
                    row.addProperty("item", blocks[descriptor(n)]);
                    row.addProperty("metadata", 0);
                    row.addProperty("damage", 0);
                    return row;
                }
            },
                () -> !Thread.currentThread()
                    .isInterrupted()).bytes;
    }

    private static byte[] previewPng() throws Exception {
        var png = new BufferedImage(640, 640, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(0, 0, 0xffaaccdd);
        var out = new ByteArrayOutputStream();
        require(ImageIO.write(png, "png", out), "PNG encoder unavailable");
        return out.toByteArray();
    }

    private static Attempt attempt() throws ReflectiveOperationException {
        Object active = field(HologramClientNativePlacement.class, "active").get(null);
        return active == null ? null : (Attempt) field(active.getClass(), "attempt").get(active);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        var f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}

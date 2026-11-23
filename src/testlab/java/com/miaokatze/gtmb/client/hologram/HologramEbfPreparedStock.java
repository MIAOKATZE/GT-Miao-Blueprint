// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.FakePlayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;

/** Fixed-profile, pre-login vanilla fixture; never writes a live player, packet, or prior world. */
final class HologramEbfPreparedStock {

    static final String FLAG = "gtmb.testlab.localPreviewStagesEbfPreparedStock";
    static final String SHA_FLAG = "gtmb.testlab.localPreviewStagesEbfPreparedStockSha256";
    static final String SCHEMA = "gtmb-owned-ebf-prestock-v1";
    static final String FILE = "ebf-prestock-playerdata.dat";
    static final String REGISTRY = "gtmb:neko_hologram_projector";
    static final int SLOT = 1;
    private static final int MAX_COMPRESSED = 4096, MAX_ADMITTED = 8192;

    private final String run, digest;
    private final UUID actor;
    private final byte[] bytes;
    private final Item projector;
    private final int registeredId;
    private final NBTTagCompound itemTag;
    private boolean installed;
    private volatile boolean nativeServerActorVerified;

    private HologramEbfPreparedStock(String run, UUID actor, String digest, byte[] bytes, Item projector,
        int registeredId, NBTTagCompound itemTag) {
        this.run = run;
        this.actor = actor;
        this.digest = digest;
        this.bytes = bytes;
        this.projector = projector;
        this.registeredId = registeredId;
        this.itemTag = itemTag;
    }

    static HologramEbfPreparedStock read(Path game, Path output, Path hostOutput, String run, String expectedActor,
        String titleProfileId, String shaPin, Item projector) throws IOException {
        UUID actor = loginActor(expectedActor);
        Path receiptRoot = exactDirectory(output);
        require(receiptRoot.equals(exactDirectory(hostOutput)), "EBF stock receipt roots differ");
        Path ownedGame = ownedGame(game, receiptRoot);
        var lease = readJson(exactDirectory(ownedGame.resolve(".testlab-host")).resolve("lease.json"));
        require(REGISTRY.equals(Item.itemRegistry.getNameForObject(projector)), "EBF stock projector registry mismatch");
        int registeredId = Item.getIdFromItem(projector);
        require(Item.getItemById(registeredId) == projector, "EBF stock projector ID remap mismatch");
        verifyLease(lease, run, actor, loginActor(titleProfileId), shaPin, receiptRoot);
        byte[] bytes = readBounded(receiptRoot.resolve(FILE));
        NBTTagCompound tag = validate(bytes, shaPin, registeredId);
        return new HologramEbfPreparedStock(run, actor, shaPin, bytes, projector, registeredId,
            (NBTTagCompound) tag.getTagList("Inventory", 10).getCompoundTagAt(0).getCompoundTag("tag").copy());
    }

    void install(Path save, Path savesParent, Map<String, Object> receipt) throws IOException {
        require(!installed, "EBF pre-stock fixture already installed");
        Path data = destination(save, savesParent, run, actor);
        Files.createDirectory(data.getParent());
        Files.write(data, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        require(java.util.Arrays.equals(bytes, readBounded(data)), "EBF stock playerdata write readback differed");
        installed = true;
        receipt.put("schema", SCHEMA);
        receipt.put("fixtureSha256", digest);
        receipt.put("registry", REGISTRY);
        receipt.put("selectedSlot", SLOT);
        receipt.put("vanillaPreloginFileCreatedNew", true);
        receipt.put("noDonorWorldPlayerIdentityCapabilitiesOrDraft", true);
    }

    boolean verifyServer(WorldServer world) throws IOException {
        if (nativeServerActorVerified) return true;
        if (world.playerEntities.isEmpty()) return false;
        require(installed && world.playerEntities.size() == 1
            && world.playerEntities.get(0) instanceof EntityPlayerMP real && !(real instanceof FakePlayer)
            && real.worldObj == world && real.theItemInWorldManager.getGameType() == WorldSettings.GameType.SURVIVAL
            && !world.getWorldInfo().areCommandsAllowed() && isOwnedStock(real), "Native server did not load exact ordinary stock actor");
        nativeServerActorVerified = true;
        return true;
    }

    boolean verifyClient(EntityPlayer real, Map<String, Object> receipt) throws IOException {
        if (!nativeServerActorVerified) return false;
        require(isOwnedStock(real), "Native client did not load exact ordinary stock actor");
        receipt.put("bothNativePeersHeldExactOwnedStockBeforePreview", true);
        receipt.put("liveInventoryFixtureWrites", 0);
        return true;
    }

    private boolean isOwnedStock(EntityPlayer real) {
        if (real == null || Item.getIdFromItem(projector) != registeredId || Item.getItemById(registeredId) != projector
            || !actor.equals(real.getUniqueID()) || real.dimension != 0 || real.isDead
            || real.capabilities.isCreativeMode || real.capabilities.allowFlying || real.capabilities.isFlying
            || real.inventory.currentItem != SLOT) return false;
        var held = real.getHeldItem();
        return held != null && held.getItem() == projector && held.stackSize == 1 && held.getItemDamage() == 0
            && held.hasTagCompound() && itemTag.equals(held.getTagCompound());
    }

    static UUID loginActor(String id) throws IOException {
        require(id != null && (id.matches("[0-9a-fA-F]{32}")
            || id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")),
            "EBF stock requires an explicit normal launcher profile ID");
        String canonical = id.replace("-", "").replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        return UUID.fromString(canonical);
    }

    static void verifyLease(JsonObject lease, String run, UUID actor, UUID launchActor, String shaPin, Path receiptRoot)
        throws IOException {
        require(uuid(run) && run.equals(string(lease, "runId")) && actor != null && actor.equals(launchActor)
            && shaPin != null && shaPin.matches("[0-9a-f]{64}") && lease.has("ebfPreparedStock")
            && lease.get("ebfPreparedStock").isJsonObject(), "Exact prepared-stock run/profile/seal required");
        var pin = lease.getAsJsonObject("ebfPreparedStock");
        String actorSha256 = HologramBlueprintArtifact.hash(actor.toString().getBytes(StandardCharsets.US_ASCII));
        require(pin.entrySet().size() == 5 && SCHEMA.equals(string(pin, "schema"))
            && actor.toString().equals(string(pin, "actorUuid")) && shaPin.equals(string(pin, "sha256"))
            && actorSha256.equals(string(pin, "nativeDonorActorSha256"))
            && receiptRoot.toString().equals(string(pin, "receiptRoot")), "EBF stock seal is unbound to the exact lease");
    }

    static Path ownedGame(Path game, Path receipt) throws IOException {
        Path root = receipt.getParent();
        require(root != null && root.getParent() != null && root.getFileName().toString().equals("receipts"),
            "Expected the same-run isolated QA receipt root");
        Path expected = root.getParent().resolve("launcher-data").resolve("instances")
            .resolve(receipt.getFileName()).resolve(".minecraft");
        Path actual = exactDirectory(game);
        require(expected.equals(actual), "EBF stock receipt does not belong to this isolated game instance");
        return actual;
    }

    static Path destination(Path save, Path savesParent, String run, UUID actor) throws IOException {
        Path parent = exactDirectory(savesParent);
        Path exact = exactDirectory(save);
        require(uuid(run) && exact.getParent().equals(parent) && exact.getFileName().toString().equals("MiaoHost-" + run),
            "Refusing stock for a world outside the exact run-owned root");
        try (var entries = Files.newDirectoryStream(exact)) {
            var iterator = entries.iterator();
            require(iterator.hasNext(), "Owned EBF save marker missing");
            Path owned = iterator.next();
            require(owned.getFileName().toString().equals(".miaotest-host-owner.json")
                && Files.isRegularFile(owned, LinkOption.NOFOLLOW_LINKS) && !iterator.hasNext(),
                "Refusing a nonempty/preexisting stock world");
            var marker = readJson(owned);
            require(run.equals(string(marker, "runId")) && parent.toString().equals(string(marker, "parent")),
                "Expected the same canonical EBF world marker");
        }
        require(actor != null, "No exact actor for vanilla playerdata");
        return exact.resolve("playerdata").resolve(actor + ".dat");
    }

    static Path exactDirectory(Path candidate) throws IOException {
        Path normal = candidate.toAbsolutePath().normalize();
        require(Files.isDirectory(normal, LinkOption.NOFOLLOW_LINKS) && normal.equals(normal.toRealPath()),
            "Expected exact canonical owned directory");
        return normal;
    }

    private static byte[] readBounded(Path file) throws IOException {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Expected ordinary prepared stock file");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            var input = Channels.newInputStream(channel)) {
            return boundedBytes(input, MAX_COMPRESSED);
        }
    }

    /** Both the compressed seed and its expanded contents are bounded before Minecraft parses any tags. */
    private static byte[] boundedBytes(java.io.InputStream stream, int max) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] block = new byte[1024];
        for (int n; (n = stream.read(block)) != -1;) {
            require(n <= max - out.size(), "EBF stock seed exceeds its size bound");
            out.write(block, 0, n);
        }
        require(out.size() > 0, "Empty EBF stock seed");
        return out.toByteArray();
    }

    static NBTTagCompound validate(byte[] bytes, String digest, int registeredId) throws IOException {
        require(bytes.length > 0 && bytes.length <= MAX_COMPRESSED && HologramBlueprintArtifact.hash(bytes).equals(digest),
            "EBF stock fixture digest mismatch");
        NBTTagCompound data;
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(bytes));
            var input = new DataInputStream(new ByteArrayInputStream(boundedBytes(zip, MAX_ADMITTED)))) {
            data = CompressedStreamTools.func_152456_a(input, new NBTSizeTracker(MAX_ADMITTED));
            require(input.read() == -1, "EBF stock has trailing payload");
        }
        var inventory = data.getTagList("Inventory", 10);
        var projected = inventory.getCompoundTagAt(0);
        String id = projected.getCompoundTag("tag").getString("gtitHologramToolId");
        require(uuid(id) && registeredId > 0 && registeredId <= Short.MAX_VALUE
            && expectedData(id, registeredId).equals(data), "Unexpected field, item identity or default in EBF stock seed");
        return data;
    }

    static NBTTagCompound expectedData(String toolId, int registeredId) {
        var data = new NBTTagCompound();
        data.setInteger("Dimension", 0);
        data.setTag("Pos", triples(.5, 5, .5));
        data.setTag("Motion", triples(0, 0, 0));
        var rotation = new NBTTagList();
        rotation.appendTag(new NBTTagFloat(0));
        rotation.appendTag(new NBTTagFloat(0));
        data.setTag("Rotation", rotation);
        data.setFloat("HealF", 20);
        data.setShort("Air", (short) 300);
        data.setShort("Fire", (short) -20);
        data.setInteger("foodLevel", 20);
        data.setInteger("foodTickTimer", 0);
        data.setFloat("foodSaturationLevel", 5);
        data.setFloat("foodExhaustionLevel", 0);
        var stack = new NBTTagCompound();
        stack.setByte("Slot", (byte) SLOT);
        stack.setShort("id", (short) registeredId);
        stack.setByte("Count", (byte) 1);
        stack.setShort("Damage", (short) 0);
        var itemTag = new NBTTagCompound();
        itemTag.setString("gtitHologramToolId", toolId);
        itemTag.setInteger("gtitHologramMain", 1);
        itemTag.setBoolean("gtitHologramMainExplicit", false);
        itemTag.setBoolean("gtitHologramDowngrade", false);
        var materials = new NBTTagCompound();
        materials.setBoolean("main", true);
        materials.setBoolean("containers", true);
        materials.setBoolean("me", true);
        materials.setInteger("priority", 0);
        materials.setInteger("recoveryDestination", 0);
        itemTag.setTag("gtitHologramMaterials", materials);
        stack.setTag("tag", itemTag);
        var inventory = new NBTTagList();
        inventory.appendTag(stack);
        data.setTag("Inventory", inventory);
        data.setInteger("SelectedItemSlot", SLOT);
        return data;
    }

    private static NBTTagList triples(double a, double b, double c) {
        var values = new NBTTagList();
        values.appendTag(new NBTTagDouble(a));
        values.appendTag(new NBTTagDouble(b));
        values.appendTag(new NBTTagDouble(c));
        return values;
    }

    private static JsonObject readJson(Path file) throws IOException {
        var parsed = new JsonParser().parse(new String(readBounded(file), StandardCharsets.UTF_8));
        require(parsed.isJsonObject(), "Expected strict lease/marker object");
        return parsed.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static boolean uuid(String input) {
        try {
            return input != null && UUID.fromString(input).toString().equals(input);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}

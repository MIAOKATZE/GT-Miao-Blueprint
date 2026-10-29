package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Bounded binary NBT transport and detached ownership rewriting for shared blueprints. */
final class HologramBlueprintNbt {

    static final int MAX_COMPRESSED = 8 * 1024 * 1024;
    static final int MAX_EXPANDED = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 512;
    static final String ITEM_REGISTRY = "gtitBlueprintItemRegistry";
    static final String MISSING_ITEM = "gtitBlueprintMissingItem";
    static final String COVER_REGISTRY = "gtitBlueprintCoverRegistry";
    static final String COVER_DAMAGE = "gtitBlueprintCoverDamage";
    static final String MISSING_COVER = "gtitBlueprintMissingCover";
    private static final Field LIST_ENTRIES = listEntriesField();

    private HologramBlueprintNbt() {}

    static String encodeCaptured(NBTTagCompound tag) throws IOException {
        return encode(tag, false);
    }

    static String encodeCaptured(Block block, NBTTagCompound tag) throws IOException {
        return encode(tag, false, true, block);
    }

    /** Capture already knows the exact expanded size while writing the bounded transport. */
    static Encoded encodeCapturedWithSize(Block block, NBTTagCompound tag) throws IOException {
        return encodeWithSize(tag, false, true, block);
    }

    static Encoded encodeCanonicalWithSize(Block block, NBTTagCompound tag) throws IOException {
        return encodeWithSize(
            tag == null ? null : canonicalActorPayload(block, tag, null, "", false),
            false,
            true,
            null);
    }

    static final class Encoded {

        final String value;
        final int expandedBytes;

        private Encoded(String value, int expandedBytes) {
            this.value = value;
            this.expandedBytes = expandedBytes;
        }
    }

    /** AE schemas already handled identities; never recursively rewrite encoded pattern recipe data. */
    static String encodeCanonical(NBTTagCompound tag) throws IOException {
        return encode(tag, false);
    }

    /** Mixed FMP keeps AE recipes exact while non-AE parts retain the established ownership policy. */
    static String encodeCanonical(Block block, NBTTagCompound tag) throws IOException {
        if (tag == null) return null;
        return encodeCanonical(canonicalActorPayload(block, tag, null, "", false));
    }

    /** Pure detached transport projection; root target coordinates are bound after schema analysis. */
    static NBTTagCompound canonicalActorPayload(Block block, NBTTagCompound source, UUID owner, String ownerName,
        boolean stripCovers) throws IOException {
        if (source == null) return null;
        if (block == null || !block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) return (NBTTagCompound) source.copy();
        return canonicalMultipartPayload(source, owner, ownerName, stripCovers);
    }

    /** Shared pure implementation; only top-level AE parts are exempt from the legacy recursive rule. */
    static NBTTagCompound canonicalMultipartPayload(NBTTagCompound source, UUID owner, String ownerName,
        boolean stripCovers) throws IOException {
        NBTTagCompound copy = (NBTTagCompound) source.copy();
        NBTBase originalParts = copy.getTag("parts");
        if (originalParts != null && !(originalParts instanceof NBTTagList))
            throw new IOException("Invalid FMP part list");
        NBTTagList parts = originalParts == null ? new NBTTagList() : (NBTTagList) originalParts;
        if (parts.tagCount() > 0 && parts.func_150303_d() != 10) throw new IOException("Invalid FMP part type");
        NBTTagList legacy = new NBTTagList();
        for (int index = 0; index < parts.tagCount(); index++) {
            NBTTagCompound part = parts.getCompoundTagAt(index);
            // These shared references belong only to the deep copy; source parts are never mutated.
            legacy.appendTag(HologramAeBlueprint.isAeMultipartPart(part) ? new NBTTagCompound() : part);
        }
        copy.setTag("parts", legacy);
        NBTBase report = copy.getTag(HologramAeBlueprint.UNRESOLVED);
        copy.removeTag(HologramAeBlueprint.UNRESOLVED);
        rewrite(copy, owner, ownerName == null ? "" : ownerName, stripCovers, false);
        for (int index = 0; index < legacy.tagCount(); index++)
            rewrite(legacy.getCompoundTagAt(index), owner, ownerName == null ? "" : ownerName, stripCovers, false);
        copy.setTag("parts", parts);
        if (report != null) copy.setTag(HologramAeBlueprint.UNRESOLVED, report);
        return copy;
    }

    static void bindTarget(NBTTagCompound tag, int x, int y, int z) {
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
    }

    private static String encode(NBTTagCompound tag, boolean rewriteOwnership) throws IOException {
        return encode(tag, rewriteOwnership, true);
    }

    static String encodeData(NBTTagCompound tag) throws IOException {
        return encode(tag, false, false);
    }

    private static String encode(NBTTagCompound tag, boolean rewriteOwnership, boolean resolveItems)
        throws IOException {
        return encode(tag, rewriteOwnership, resolveItems, null);
    }

    private static String encode(NBTTagCompound tag, boolean rewriteOwnership, boolean resolveItems, Block block)
        throws IOException {
        return encodeWithSize(tag, rewriteOwnership, resolveItems, block).value;
    }

    private static Encoded encodeWithSize(NBTTagCompound tag, boolean rewriteOwnership, boolean resolveItems,
        Block block) throws IOException {
        if (tag == null) return new Encoded(null, 0);
        checkDepth(tag, 0);
        NBTTagCompound copy = (NBTTagCompound) tag.copy();
        if (block != null) HologramCarpentersCompatibility.stripOwner(block, copy);
        if (rewriteOwnership) rewrite(copy, null, "", false, false);
        if (resolveItems) stableItems(copy, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LimitedOutput expanded = new LimitedOutput(
            new GZIPOutputStream(new LimitedOutput(bytes, MAX_COMPRESSED)),
            MAX_EXPANDED);
        try (DataOutputStream output = new DataOutputStream(expanded)) {
            output.writeByte(10);
            output.writeUTF("");
            writeCanonicalPayload(copy, output, 0);
        }
        return new Encoded(
            Base64.getEncoder()
                .encodeToString(bytes.toByteArray()),
            expanded.written);
    }

    /** Validate before native copy can recurse, including when a caller supplies a cyclic compound. */
    private static void checkDepth(NBTBase tag, int depth) throws IOException {
        if (tag == null || depth > MAX_DEPTH) throw new IOException("蓝图NBT嵌套超出上限");
        if (tag.getId() < 1 || tag.getId() > 11) throw new IOException("蓝图NBT类型无效");
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (Object key : compound.func_150296_c()) checkDepth(compound.getTag((String) key), depth + 1);
        } else if (tag instanceof NBTTagList) {
            for (NBTBase child : listEntries((NBTTagList) tag)) checkDepth(child, depth + 1);
        }
    }

    /** Standard 1.7 NBT wire format, independent of a vanilla or modded compound map's iteration order. */
    private static void writeCanonicalPayload(NBTBase tag, DataOutput output, int depth) throws IOException {
        if (tag == null || depth > MAX_DEPTH) throw new IOException("蓝图NBT嵌套超出上限");
        switch (tag.getId()) {
            case 1:
                output.writeByte(((NBTBase.NBTPrimitive) tag).func_150290_f());
                break;
            case 2:
                output.writeShort(((NBTBase.NBTPrimitive) tag).func_150289_e());
                break;
            case 3:
                output.writeInt(((NBTBase.NBTPrimitive) tag).func_150287_d());
                break;
            case 4:
                output.writeLong(((NBTBase.NBTPrimitive) tag).func_150291_c());
                break;
            case 5:
                output.writeFloat(((NBTBase.NBTPrimitive) tag).func_150288_h());
                break;
            case 6:
                output.writeDouble(((NBTBase.NBTPrimitive) tag).func_150286_g());
                break;
            case 7:
                byte[] bytes = ((NBTTagByteArray) tag).func_150292_c();
                output.writeInt(bytes.length);
                output.write(bytes);
                break;
            case 8:
                output.writeUTF(((NBTTagString) tag).func_150285_a_());
                break;
            case 9:
                NBTTagList list = (NBTTagList) tag;
                int type = list.func_150303_d();
                if (type < 0 || type > 11 || type == 0 && list.tagCount() != 0) throw new IOException("蓝图NBT列表类型无效");
                output.writeByte(type);
                output.writeInt(list.tagCount());
                for (NBTBase child : listEntries(list)) {
                    if (child.getId() != type) throw new IOException("蓝图NBT列表类型不一致");
                    writeCanonicalPayload(child, output, depth + 1);
                }
                break;
            case 10:
                NBTTagCompound compound = (NBTTagCompound) tag;
                List<String> keys = new ArrayList<>();
                for (Object key : compound.func_150296_c()) keys.add((String) key);
                java.util.Collections.sort(keys);
                for (String key : keys) {
                    NBTBase child = compound.getTag(key);
                    if (child.getId() < 1 || child.getId() > 11) throw new IOException("蓝图NBT类型无效");
                    output.writeByte(child.getId());
                    output.writeUTF(key);
                    writeCanonicalPayload(child, output, depth + 1);
                }
                output.writeByte(0);
                break;
            case 11:
                int[] integers = ((NBTTagIntArray) tag).func_150302_c();
                output.writeInt(integers.length);
                for (int value : integers) output.writeInt(value);
                break;
            default:
                throw new IOException("蓝图NBT类型无效");
        }
    }

    static NBTTagCompound decode(String value) throws IOException {
        if (value == null || value.isEmpty()) return null;
        if (HologramSnapshotDataContext.active()) return HologramSnapshotDataContext.decode(value);
        NBTTagCompound tag = decodeData(value);
        try {
            stableItems(tag, false);
            return tag;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图NBT编码无效", invalid);
        }
    }

    /** First admission resolves identities on the owner; later reads use only that task's frozen numeric IDs. */
    static NBTTagCompound decodeSnapshot(String value, Map<String, Integer> itemIds, boolean freeze)
        throws IOException {
        NBTTagCompound tag = decodeData(value);
        try {
            stableItems(tag, false, itemIds, freeze);
            return tag;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图NBT编码无效", invalid);
        }
    }

    /** In-memory equivalent of canonical transport's item/cover identity normalization. */
    static void normalizeTransportItems(NBTTagCompound payload) throws IOException {
        if (payload == null) return;
        try {
            stableItems(payload, true);
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图NBT编码无效", invalid);
        }
    }

    /** Strict read of offline observations: process-local numeric IDs have no fallback authority. */
    static NBTTagCompound restorePortableObservation(NBTTagCompound portable) throws IOException {
        checkDepth(portable, 0);
        requireItemBindings(portable);
        NBTTagCompound copy = (NBTTagCompound) portable.copy();
        stableItems(copy, false);
        if (hasMissingItems(copy)) throw new IOException("The observation requires an unavailable registered item");
        return copy;
    }

    private static void requireItemBindings(NBTBase tag) throws IOException {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            if (itemCompound(compound) && !compound.hasKey(ITEM_REGISTRY, 8))
                throw new IOException("An offline observation has an unbound numeric ItemStack ID");
            for (Object key : compound.func_150296_c()) requireItemBindings(compound.getTag((String) key));
        } else if (tag instanceof NBTTagList) {
            for (NBTBase entry : listEntries((NBTTagList) tag)) requireItemBindings(entry);
        }
    }

    static NBTTagCompound decodeData(String value) throws IOException {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > ((MAX_COMPRESSED + 2) / 3) * 4) throw new IOException("蓝图压缩NBT超出上限");
        try {
            byte[] bytes = Base64.getDecoder()
                .decode(value);
            if (bytes.length > MAX_COMPRESSED) throw new IOException("蓝图压缩NBT超出上限");
            NBTTagCompound tag = HologramSnapshotCodec.decode(bytes, MAX_EXPANDED, 16L * 1024 * 1024);
            return tag;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图NBT编码无效", invalid);
        }
    }

    /** Counts expanded bytes without constructing tags, for the blueprint-wide device payload budget. */
    static int expandedBytes(String value) throws IOException {
        if (value == null || value.isEmpty()) return 0;
        if (value.length() > ((MAX_COMPRESSED + 2) / 3) * 4) throw new IOException("蓝图压缩NBT超出上限");
        try {
            byte[] compressed = Base64.getDecoder()
                .decode(value);
            if (compressed.length > MAX_COMPRESSED) throw new IOException("蓝图压缩NBT超出上限");
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > MAX_EXPANDED - total) throw new IOException("蓝图展开NBT超出上限");
                    total += count;
                }
                return total;
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("蓝图NBT编码无效", invalid);
        }
    }

    static String sanitize(String value) throws IOException {
        return value == null ? null : encodeCaptured(decode(value));
    }

    static NBTTagCompound forPlacement(String value, UUID ownerUuid, String ownerName, int x, int y, int z,
        boolean retainNbt, boolean retainCovers) throws IOException {
        return forPlacement(value, ownerUuid, ownerName, x, y, z, retainNbt, retainCovers, null);
    }

    static NBTTagCompound forPlacement(String value, UUID ownerUuid, String ownerName, int x, int y, int z,
        boolean retainNbt, boolean retainCovers, List<String> keys) throws IOException {
        return forPlacement(decode(value), ownerUuid, ownerName, x, y, z, retainNbt, retainCovers, keys);
    }

    private static NBTTagCompound forPlacement(NBTTagCompound result, UUID ownerUuid, String ownerName, int x, int y,
        int z, boolean retainNbt, boolean retainCovers, List<String> keys) {
        if (result == null) return null;
        if (!retainNbt && !retainCovers && !result.hasKey(HologramNetworkBindings.GROUP)) return null;
        result = filter(result, retainNbt ? keys : java.util.Collections.emptyList(), retainCovers);
        rewrite(result, ownerUuid, ownerName == null ? "" : ownerName, !retainCovers, false);
        bindTarget(result, x, y, z);
        return result;
    }

    static NBTTagCompound itemForPlacement(String value, UUID ownerUuid, String ownerName, boolean retainNbt,
        boolean retainCovers) throws IOException {
        return itemForPlacement(value, ownerUuid, ownerName, retainNbt, retainCovers, null);
    }

    static NBTTagCompound itemForPlacement(String value, UUID ownerUuid, String ownerName, boolean retainNbt,
        boolean retainCovers, List<String> keys) throws IOException {
        if (!retainNbt && !retainCovers) return null;
        NBTTagCompound result = decode(value);
        if (result != null) {
            result = filter(result, retainNbt ? keys : java.util.Collections.emptyList(), retainCovers);
            rewrite(result, ownerUuid, ownerName == null ? "" : ownerName, !retainCovers, false);
        }
        return result;
    }

    static String filterCaptured(String value, List<String> keys, boolean retainCovers) throws IOException {
        NBTTagCompound source = decode(value);
        if (source == null) return null;
        NBTTagCompound filtered = filter(source, keys, retainCovers);
        return encodeCaptured(filtered);
    }

    static String filterCanonical(Block block, String value, List<String> keys, boolean retainCovers)
        throws IOException {
        NBTTagCompound source = decode(value);
        return source == null ? null : encodeCanonical(block, filter(source, keys, retainCovers));
    }

    /** Reuse the just-decoded owned payload for payment validation, AE routing and selection. */
    static String filterDecoded(Block block, NBTTagCompound source, List<String> keys, boolean retainCovers)
        throws IOException {
        if (source == null) return null;
        boolean canonical = HologramAeBlueprint.supports(block, source);
        NBTTagCompound selected = filter(source, keys, retainCovers);
        return canonical ? encodeCanonical(block, selected) : encodeCaptured(selected);
    }

    /**
     * Last canonical assertion before costing/installation; target coordinates are the only actor-independent binding.
     */
    static NBTTagCompound forPlacement(Block block, int metadata, String value, UUID ownerUuid, String ownerName, int x,
        int y, int z, boolean retainNbt, boolean retainCovers, List<String> keys) throws IOException {
        NBTTagCompound source = decode(value);
        if (!HologramAeBlueprint.supports(block, source))
            return forPlacement(source, ownerUuid, ownerName, x, y, z, retainNbt, retainCovers, keys);
        if (source == null) return null;
        NBTTagCompound selected = filter(source, retainNbt ? keys : java.util.Collections.emptyList(), retainCovers);
        selected = canonicalActorPayload(block, selected, ownerUuid, ownerName, !retainCovers);
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, metadata, selected);
        if (analysis.blocking) throw new IOException("AE blueprint has unresolved schema/slot issues");
        NBTTagCompound canonical = nativePayload(analysis.payload());
        bindTarget(canonical, x, y, z);
        return canonical;
    }

    static NBTTagCompound filterSelection(NBTTagCompound source, List<String> keys, boolean retainCovers) {
        return filter((NBTTagCompound) source.copy(), keys, retainCovers);
    }

    static NBTTagCompound filterSelection(Block block, NBTTagCompound source, List<String> keys, boolean retainCovers) {
        return filter((NBTTagCompound) source.copy(), keys, retainCovers, block);
    }

    static boolean protectedKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return essentialKey(normalized) || HologramNetworkBindings.descriptorKey(key)
            || HologramCoordinatePolicies.RELATIONS.equals(key)
            || HologramRecovery.TAG.equals(key)
            || HologramFullPreservation.TAG.equals(key)
            || ITEM_REGISTRY.equals(key)
            || MISSING_ITEM.equals(key)
            || COVER_REGISTRY.equals(key)
            || COVER_DAMAGE.equals(key)
            || MISSING_COVER.equals(key);
    }

    static boolean protectedKey(NBTTagCompound source, String key) {
        return protectedKey(key) || "cbOwner".equals(key) && HologramDeidentification.carpenterOwner(source);
    }

    static boolean protectedKey(Block block, NBTTagCompound source, String key) {
        return protectedKey(key) || "cbOwner".equals(key) && HologramDeidentification.carpenterOwner(block, source);
    }

    /** Exact audited paired asset schema. An omitted type or quantity cannot leave a malformed phantom payload. */
    static void normalizeResourceSelection(NBTTagCompound tag) {
        if (tag == null) return;
        String id = tag.getString("id");
        if (("TileJar".equals(id) || "TileJarVoid".equals(id))
            && (!tag.hasKey("Aspect", 8) || !tag.hasKey("Amount", 2))) {
            tag.removeTag("Aspect");
            tag.setShort("Amount", (short) 0);
        }
    }

    /** Root-isolated reviews still need the complete declared native quantity/type pair. */
    static void includeResourceCompanions(NBTTagCompound source, NBTTagCompound selected, String root) {
        if (!HologramNativeHubData.fluidKey(root)) return;
        for (String key : new String[] { "mSteamStored", "mWaterStored", HologramNativeHubData.TYPE })
            if (source.hasKey(key)) selected.setTag(
                key,
                source.getTag(key)
                    .copy());
    }

    private static NBTTagCompound filter(NBTTagCompound source, List<String> keys, boolean retainCovers) {
        return filter(source, keys, retainCovers, null);
    }

    private static NBTTagCompound filter(NBTTagCompound source, List<String> keys, boolean retainCovers, Block block) {
        if (keys == null && retainCovers) return source;
        Set<String> selected = keys == null ? null : new HashSet<>(keys);
        NBTTagCompound result = new NBTTagCompound();
        for (Object raw : source.func_150296_c()) {
            String key = (String) raw;
            String normalized = key.toLowerCase(Locale.ROOT);
            boolean cover = coverKey(normalized);
            if ((cover && retainCovers)
                || (!cover && (selected == null || selected.contains(key) || protectedKey(block, source, key))))
                result.setTag(
                    key,
                    source.getTag(key)
                        .copy());
        }
        normalizeResourceSelection(result);
        return result;
    }

    private static boolean essentialKey(String key) {
        return key.equals("gtmbaeunresolved") || key.equals("id")
            || key.equals("mid")
            || key.equals("texturepage")
            || key.equals("textureindex")
            || key.equals("x")
            || key.equals("y")
            || key.equals("z")
            || uuidKey(key)
            || nameKey(key);
    }

    /** ItemStack.writeToNBT in local MCP writes numeric id, Count and Damage; tile/cover ids do not have this shape. */
    private static boolean itemCompound(NBTTagCompound tag) {
        return tag.hasKey("id", 99) && tag.hasKey("Count", 99) && tag.hasKey("Damage", 99);
    }

    static Item stableItem(NBTTagCompound stack) {
        if (stack.hasKey(ITEM_REGISTRY, 8)) {
            Object found = Item.itemRegistry.getObject(stack.getString(ITEM_REGISTRY));
            return found instanceof Item ? (Item) found : null;
        }
        return stack.hasKey("id", 99) ? Item.getItemById(stack.getInteger("id")) : null;
    }

    static boolean hasMissingItems(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            if (compound.getBoolean(MISSING_COVER)) return true;
            if (itemCompound(compound) && compound.hasKey(ITEM_REGISTRY, 8) && stableItem(compound) == null)
                return true;
            for (Object raw : compound.func_150296_c()) if (hasMissingItems(compound.getTag((String) raw))) return true;
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            for (NBTBase entry : listEntries(list)) if (hasMissingItems(entry)) return true;
        }
        return false;
    }

    /** Payment comparisons and native tile readers must never receive blueprint-only transport markers. */
    static NBTTagCompound nativePayload(NBTTagCompound tag) throws IOException {
        if (tag == null) return null;
        if (hasMissingItems(tag)) throw new IOException("蓝图NBT包含缺失物品，不能恢复或扣除资源");
        NBTTagCompound copy = (NBTTagCompound) tag.copy();
        removeQuantumVisual(copy);
        removeMarkers(copy);
        return copy;
    }

    /** Resolve only the registered root tile ID; nested items/recipes and unknown families keep their own data. */
    static boolean registeredQuantumTile(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey("id", 8)) return false;
        try {
            Field names = ReflectionHelper.findField(TileEntity.class, "nameToClassMap", "field_145855_i");
            Object type = ((Map<?, ?>) names.get(null)).get(tag.getString("id"));
            return type instanceof Class<?> && HologramNetworkBindings.NODE_TILE.equals(((Class<?>) type).getName());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Cannot verify registered quantum visual payload", failure);
        }
    }

    private static void removeQuantumVisual(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey(HologramDisplaySnapshot.QUANTUM_SIDES) || !registeredQuantumTile(tag)) return;
        if (!HologramDisplaySnapshot.validQuantumSides(tag))
            throw new IOException("Malformed quantum preview side mask cannot enter a native payload");
        tag.removeTag(HologramDisplaySnapshot.QUANTUM_SIDES);
    }

    private static void removeMarkers(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            compound.removeTag(ITEM_REGISTRY);
            compound.removeTag(MISSING_ITEM);
            compound.removeTag(COVER_REGISTRY);
            compound.removeTag(COVER_DAMAGE);
            compound.removeTag(MISSING_COVER);
            compound.removeTag(HologramCoordinatePolicies.RELATIONS);
            compound.removeTag(HologramFullPreservation.TAG);
            for (Object raw : compound.func_150296_c()) removeMarkers(compound.getTag((String) raw));
        } else if (tag instanceof NBTTagList) {
            for (NBTBase entry : listEntries((NBTTagList) tag)) removeMarkers(entry);
        }
    }

    private static void stableItems(NBTBase tag, boolean capture) {
        stableItems(tag, capture, null, false);
    }

    private static void stableItems(NBTBase tag, boolean capture, Map<String, Integer> itemIds, boolean freeze) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            if (compound.hasKey("gt.covers", 9)) {
                NBTTagList covers = (NBTTagList) compound.getTag("gt.covers");
                if (covers.func_150303_d() == 10) {
                    for (int i = 0; i < covers.tagCount(); i++)
                        stableCover(covers.getCompoundTagAt(i), capture, itemIds, freeze);
                }
            }
            if (itemCompound(compound)) {
                if (capture && !compound.hasKey(ITEM_REGISTRY, 8)) {
                    Item item = Item.getItemById(compound.getInteger("id"));
                    String registry = item == null ? "unresolved:" + compound.getInteger("id")
                        : String.valueOf(Item.itemRegistry.getNameForObject(item));
                    compound.setString(ITEM_REGISTRY, registry);
                }
                if (compound.hasKey(ITEM_REGISTRY, 8)) {
                    Integer id = stableItemId(compound.getString(ITEM_REGISTRY), itemIds, freeze);
                    compound.setShort("id", (short) (id == null ? -1 : id));
                    if (id == null) compound.setBoolean(MISSING_ITEM, true);
                    else compound.removeTag(MISSING_ITEM);
                }
            }
            for (Object raw : new ArrayList<>(compound.func_150296_c()))
                stableItems(compound.getTag((String) raw), capture, itemIds, freeze);
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            for (NBTBase entry : listEntries(list)) stableItems(entry, capture, itemIds, freeze);
        }
    }

    private static void stableCover(NBTTagCompound row, boolean capture, Map<String, Integer> itemIds, boolean freeze) {
        if (!row.hasKey("id", 3) || row.getInteger("id") == 0 && !row.hasKey(COVER_REGISTRY, 8)) return;
        // GT CoverRegistry NBT_ID uses GTUtility.stackToInt: low 16 bits item ID, upper 16 bits damage.
        if (capture && !row.hasKey(COVER_REGISTRY, 8)) {
            int packed = row.getInteger("id");
            Item item = Item.getItemById(packed & 0xFFFF);
            row.setString(
                COVER_REGISTRY,
                item == null ? "unresolved:" + (packed & 0xFFFF)
                    : String.valueOf(Item.itemRegistry.getNameForObject(item)));
            row.setInteger(COVER_DAMAGE, packed >>> 16);
        }
        if (row.hasKey(COVER_REGISTRY, 8)) {
            Integer id = stableItemId(row.getString(COVER_REGISTRY), itemIds, freeze);
            if (id == null) {
                row.setInteger("id", 0);
                row.setBoolean(MISSING_COVER, true);
            } else {
                row.setInteger("id", id | row.getInteger(COVER_DAMAGE) << 16);
                row.removeTag(MISSING_COVER);
            }
        }
    }

    private static Integer stableItemId(String name, Map<String, Integer> itemIds, boolean freeze) {
        if (itemIds != null && itemIds.containsKey(name)) return itemIds.get(name);
        if (itemIds != null && !freeze) throw new IllegalStateException("Snapshot item identity was not frozen");
        Object found = Item.itemRegistry.getObject(name);
        Integer id = found instanceof Item ? Item.getIdFromItem((Item) found) : null;
        if (itemIds != null) itemIds.put(name, id);
        return id;
    }

    static Set<String> itemRegistries(NBTBase tag) {
        return resourceRegistries(tag);
    }

    /** Registry identities plus Forge's registration-time provider identity for each selected fluid. */
    static Set<String> resourceRegistries(NBTBase tag) {
        Set<String> result = new HashSet<>();
        collectRegistries(tag, result);
        return result;
    }

    private static void collectRegistries(NBTBase tag, Set<String> result) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            if (compound.hasKey(ITEM_REGISTRY, 8)) result.add(compound.getString(ITEM_REGISTRY));
            if (compound.hasKey(COVER_REGISTRY, 8)) result.add(compound.getString(COVER_REGISTRY));
            if (compound.hasKey("FluidName", 8)) {
                Fluid fluid = FluidRegistry.getFluid(compound.getString("FluidName"));
                if (fluid != null) result.add(FluidRegistry.getDefaultFluidName(fluid));
            }
            if (compound.hasKey("mWaterStored", 4) && compound.hasKey(HologramNativeHubData.TYPE, 8)) {
                Fluid fluid = FluidRegistry.getFluid(compound.getString(HologramNativeHubData.TYPE));
                if (fluid != null) result.add(FluidRegistry.getDefaultFluidName(fluid));
            }
            for (Object raw : compound.func_150296_c()) collectRegistries(compound.getTag((String) raw), result);
        } else if (tag instanceof NBTTagList) {
            for (NBTBase entry : listEntries((NBTTagList) tag)) collectRegistries(entry, result);
        }
    }

    private static void rewrite(NBTBase tag, UUID uuid, String name, boolean stripCovers, boolean ownerContext) {
        rewrite(tag, uuid, name, stripCovers, ownerContext, false);
    }

    private static void rewrite(NBTBase tag, UUID uuid, String name, boolean stripCovers, boolean ownerContext,
        boolean coverContext) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            if (itemCompound(compound)) return;
            for (Object raw : new ArrayList<>(compound.func_150296_c())) {
                String key = (String) raw;
                if (HologramFullPreservation.TAG.equals(key) || HologramFullPreservation.preserves(compound, key))
                    continue;
                String normalized = key.toLowerCase(Locale.ROOT);
                NBTBase original = compound.getTag(key);
                if (stripCovers && coverKey(normalized)) {
                    compound.removeTag(key);
                } else if (original instanceof NBTTagCompound || original instanceof NBTTagList) {
                    boolean actor = ownerContext || nameKey(normalized) || uuidKey(normalized);
                    boolean cover = coverKey(normalized) || coverContext
                        && (normalized.equals("d") || normalized.equals("data") || normalized.equals("coverdata"));
                    if (actor || cover) rewrite(original, uuid, name, stripCovers, actor, cover);
                } else if (nameKey(normalized) || (ownerContext && normalized.equals("name"))) {
                    compound.setString(key, name);
                } else if (uuidKey(normalized) || (ownerContext && (normalized.equals("id") || normalized.equals("uuid")
                    || normalized.equals("uuidmost")
                    || normalized.equals("uuidleast")))) {
                        if (normalized.endsWith("most"))
                            compound.setLong(key, uuid == null ? 0 : uuid.getMostSignificantBits());
                        else if (normalized.endsWith("least"))
                            compound.setLong(key, uuid == null ? 0 : uuid.getLeastSignificantBits());
                        else if (original.getId() == 11) compound.setIntArray(
                            key,
                            uuid == null ? new int[4]
                                : new int[] { (int) (uuid.getMostSignificantBits() >>> 32),
                                    (int) uuid.getMostSignificantBits(), (int) (uuid.getLeastSignificantBits() >>> 32),
                                    (int) uuid.getLeastSignificantBits() });
                        else compound.setString(key, uuid == null ? "" : uuid.toString());
                    }
            }
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            for (NBTBase entry : listEntries(list)) rewrite(entry, uuid, name, stripCovers, ownerContext, coverContext);
        }
    }

    /** Local MCP has no generic list getter; the sole List field is stable across its mapped/SRG names. */
    private static Field listEntriesField() {
        Field selected = null;
        for (Field field : NBTTagList.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            if (selected != null) throw new IllegalStateException("NBTTagList has multiple List fields");
            selected = field;
        }
        if (selected == null) throw new IllegalStateException("NBTTagList List field unavailable");
        selected.setAccessible(true);
        return selected;
    }

    @SuppressWarnings("unchecked")
    private static List<NBTBase> listEntries(NBTTagList list) {
        try {
            return (List<NBTBase>) LIST_ENTRIES.get(list);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("NBTTagList entries inaccessible", failure);
        }
    }

    private static boolean uuidKey(String key) {
        return key.equals("ownerid") || key.equals("owneruuid")
            || key.equals("mowneruuid")
            || key.equals("owneruuidmost")
            || key.equals("owneruuidleast")
            || key.equals("mowneruuidmost")
            || key.equals("mowneruuidleast")
            || key.equals("owner_id")
            || key.equals("owner_uuid");
    }

    private static boolean nameKey(String key) {
        return key.equals("owner") || key.equals("ownername") || key.equals("mownername") || key.equals("owner_name");
    }

    private static boolean coverKey(String key) {
        return key.equals("gt.covers") || key.equals("covers")
            || key.equals("cover")
            || key.equals("mcoverlist")
            || key.equals("mcoverids")
            || key.equals("mcoverdata")
            || key.matches("mcoverdata[0-5]");
    }

    private static final class LimitedOutput extends FilterOutputStream {

        private final int limit;
        private int written;

        LimitedOutput(OutputStream output, int limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(int count) throws IOException {
            if (count < 0 || count > limit - written) throw new IOException("蓝图NBT超出字节上限");
            written += count;
        }
    }
}

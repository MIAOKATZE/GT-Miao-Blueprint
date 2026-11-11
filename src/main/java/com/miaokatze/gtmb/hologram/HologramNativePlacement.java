// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Audited native persistence and material callbacks on the main thread. No harvesting, ticking or extraction. */
public final class HologramNativePlacement {

    public interface Adapter {

        boolean supports(Block block);

        /** New optional families keep their former ordinary ItemBlock path when no tile payload was retained. */
        default boolean requiresPayload() {
            return false;
        }

        /** Must return a new tile, preserve every selected component, and never touch a real world. */
        TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) throws Exception;

        /** Engine-owned original-state recovery is distinct from restoring a reusable, resource-checked template. */
        default TileEntity createRollback(Block block, int metadata, NBTTagCompound tag, World detachedWorld)
            throws Exception {
            return create(block, metadata, tag, detachedWorld);
        }

        /** Complete physical part/cover costs, including exact material tags; never remove live contents. */
        List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws Exception;

        /** True only when materials also pays every physical inventory item in the selected payload. */
        default boolean includesInventoryDrops(Block block) {
            return false;
        }

        /** Internal installation policy; only the newly decoded, detached tile may be changed. */
        default void prepareActor(Block block, TileEntity detachedTile, EntityPlayerMP actor) throws Exception {
            FamilyPolicy policy = builtinPolicy(block);
            if (policy != null && policy != this) policy.prepareActor(block, detachedTile, actor);
        }

        /** Exact physical identity unless an audited native item has equivalent shell variants. */
        default boolean matchesMaterial(Block block, ItemStack cost, ItemStack sample) {
            if (cost.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(cost, sample)) return true;
            FamilyPolicy policy = builtinPolicy(block);
            return policy != null && policy != this && policy.matchesMaterial(block, cost, sample);
        }

        /** Some native writers allocate global identities; reviewed modules provide a pure detached projection. */
        default NBTTagCompound captureExpected(TileEntity detachedTile) throws Exception {
            NBTTagCompound tag = new NBTTagCompound();
            detachedTile.writeToNBT(tag);
            return tag;
        }

        /** Normalize only documented runtime identities after both complete persistence views have been captured. */
        default void normalizeComparison(Block block, NBTTagCompound tag) {}
    }

    /** Explicit built-in family policy also retained when a legacy adapter overrides its factory or bill. */
    public interface FamilyPolicy extends Adapter {

        @Override
        void prepareActor(Block block, TileEntity detachedTile, EntityPlayerMP actor) throws Exception;

        @Override
        boolean matchesMaterial(Block block, ItemStack cost, ItemStack sample);
    }

    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();

    static {
        register("gtmb:native", new Builtin());
        register("gtmb:ae", new HologramAeBlueprint.NativeAdapter());
    }

    private HologramNativePlacement() {}

    /** Registration is explicit: unknown persistence formats never acquire support by field guessing. */
    public static synchronized void register(String id, Adapter adapter) {
        if (id == null || id.isEmpty() || adapter == null || ADAPTERS.containsKey(id))
            throw new IllegalArgumentException("native placement adapter");
        ADAPTERS.put(id, adapter);
    }

    /** Internal built-ins precede every community registration, including registrations made before mod post-init. */
    public static synchronized void registerBuiltin(String id, Adapter adapter) {
        if (id == null || id.isEmpty() || adapter == null || ADAPTERS.containsKey(id))
            throw new IllegalArgumentException("native placement adapter");
        Map<String, Adapter> previous = new LinkedHashMap<>(ADAPTERS);
        ADAPTERS.clear();
        for (Map.Entry<String, Adapter> entry : previous.entrySet()) {
            ADAPTERS.put(entry.getKey(), entry.getValue());
            if (entry.getKey()
                .equals("gtmb:native")) ADAPTERS.put(id, adapter);
        }
    }

    /** Remove only the expected registration; legacy replacement/other adapters remain untouched. */
    public static synchronized boolean unregister(String id, Adapter expected) {
        return ADAPTERS.remove(id, expected);
    }

    public static synchronized Adapter adapter(Block block) {
        if (block == null) return null;
        List<Adapter> snapshot = new ArrayList<>(ADAPTERS.values());
        for (int i = snapshot.size() - 1; i >= 0; i--) if (snapshot.get(i)
            .supports(block)) return snapshot.get(i);
        return null;
    }

    private static synchronized FamilyPolicy builtinPolicy(Block block) {
        for (Map.Entry<String, Adapter> entry : ADAPTERS.entrySet()) {
            if (entry.getValue() instanceof FamilyPolicy && entry.getValue()
                .supports(block)) return (FamilyPolicy) entry.getValue();
            if (entry.getKey()
                .equals("gtmb:ae")) break;
        }
        return null;
    }

    public static boolean supported(Block block) {
        return adapter(block) != null;
    }

    /** Provider selection only; this does not grant permissions, resource payment or source authority. */
    public static boolean supported(Block block, NBTTagCompound retainedPayload) {
        Adapter adapter = adapter(block);
        return adapter != null && (!adapter.requiresPayload() || retainedPayload != null);
    }

    /** Presence check for not-yet-decoded blueprint rows; malformed nonempty data still reaches native validation. */
    static boolean supportedBlueprint(Block block, String retainedTileNbt) {
        Adapter adapter = adapter(block);
        return adapter != null && (!adapter.requiresPayload() || retainedTileNbt != null && !retainedTileNbt.isEmpty());
    }

    public static ItemStack sample(Block block, int metadata, TileEntity tile) throws IOException {
        if (HologramNetworkBindings.node(block)) return HologramAeBlueprint.blockSample(block, metadata);
        if (tile instanceof appeng.tile.AEBaseTile && HologramAeBlueprint.supports(block)
            && !block.getClass()
                .getName()
                .equals("appeng.block.networking.BlockCableBus"))
            return HologramAeBlueprint.blockSample(block, metadata);
        if (tile == null || !supported(block)) return null;
        NBTTagCompound tag = HologramNativeCapture.capture(tile);
        return sample(block, metadata, tag);
    }

    /** A native placement identity is distinct from its bill, which may legitimately be empty. */
    public static ItemStack sample(Block block, int metadata, NBTTagCompound payload) throws IOException {
        if (HologramNetworkBindings.node(block)) return HologramAeBlueprint.blockSample(block, metadata);
        List<ItemStack> costs = materials(block, metadata, payload);
        if (costs.isEmpty()) {
            Item item = Item.getItemFromBlock(block);
            return item == null ? null : new ItemStack(item, 1, block.damageDropped(metadata));
        }
        ItemStack first = costs.get(0)
            .copy();
        first.stackSize = 1;
        return first;
    }

    public static List<ItemStack> materials(Block block, int metadata, NBTTagCompound payload) throws IOException {
        Adapter adapter = adapter(block);
        if (adapter == null) return new ArrayList<>();
        if (HologramAeBlueprint.supports(block, payload)) {
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, metadata, payload);
            if (analysis.blocking) throw unsupported("AE resource policy has unresolved issues");
            HologramAeNativeResources
                .validateSelectedFinalPayload(HologramBlueprintNbt.nativePayload(analysis.payload()));
            List<ItemStack> costs = HologramAeBlueprint.nativeShellMaterials(block, metadata, analysis.payload());
            if (block.getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart")) {
                NBTTagCompound nonAe = HologramAeBlueprint.withoutAeMultipart(analysis.payload());
                if (nonAe.getTagList("parts", 10)
                    .tagCount() > 0)
                    for (ItemStack component : materials(block, metadata, nonAe)) costs.add(component.copy());
            }
            for (ItemStack component : analysis.materials) costs.add(component.copy());
            return costs;
        }
        if (payload == null || payload.hasNoTags()) throw unsupported("native tile payload was not retained");
        try {
            NBTTagCompound tag = HologramEnergyPayload
                .prepare(block, metadata, HologramBlueprintNbt.nativePayload(payload), null).tileEmpty;
            HologramAeNativeResources.validateSelectedFinalPayload(tag);
            HologramResourceWorld world = HologramResourceWorld.create(block, metadata);
            tag.setInteger("x", 0);
            tag.setInteger("y", 64);
            tag.setInteger("z", 0);
            TileEntity tile = adapter.create(block, metadata, tag, world);
            if (tile == null) throw unsupported("native tile factory rejected payload");
            world.tile = tile;
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack stack : adapter.materials(block, metadata, tile)) {
                if (stack == null || stack.getItem() == null || stack.stackSize <= 0)
                    throw unsupported("native component has no payable material");
                result.add(stack.copy());
            }
            return result;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException(
                "Unsupported native blueprint materials: " + block.getClass()
                    .getName(),
                failure);
        }
    }

    /** Main sample is charged by the normal transaction; every remaining native component is extra. */
    public static List<ItemStack> additionalMaterials(Block block, int metadata, NBTTagCompound payload, ItemStack main)
        throws IOException {
        return additionalMaterials(block, materials(block, metadata, payload), main);
    }

    /** Reuse a bill already obtained for the exact payload in this operation, retaining the caller's native credits. */
    static List<ItemStack> additionalMaterials(Block block, List<ItemStack> materials, ItemStack main)
        throws IOException {
        List<ItemStack> costs = new ArrayList<>();
        for (ItemStack material : materials) costs.add(material.copy());
        if (HologramGtswnPlacementMaterials.freeShell(block, main)) return costs;
        if (costs.isEmpty()) return costs;
        for (int i = 0; i < costs.size(); i++) {
            ItemStack cost = costs.get(i);
            if (main != null && matchesMaterial(block, cost, main)) {
                if (--cost.stackSize == 0) costs.remove(i);
                return costs;
            }
        }
        throw unsupported("main material does not match native components");
    }

    public static boolean matchesSample(Block block, int metadata, NBTTagCompound payload, ItemStack sample) {
        try {
            if (HologramNetworkBindings.node(block)) {
                if (HologramAeBlueprint.analyze(block, metadata, payload).blocking) return false;
                ItemStack identity = HologramAeBlueprint.blockSample(block, metadata);
                return sample != null && identity != null
                    && identity.isItemEqual(sample)
                    && ItemStack.areItemStackTagsEqual(identity, sample);
            }
            List<ItemStack> costs = materials(block, metadata, payload);
            if (costs.isEmpty()) {
                ItemStack identity = sample(block, metadata, payload);
                return sample != null && identity != null
                    && identity.isItemEqual(sample)
                    && ItemStack.areItemStackTagsEqual(identity, sample);
            }
            for (ItemStack cost : costs) if (sample != null && matchesMaterial(block, cost, sample)) return true;
        } catch (IOException failure) {
            return false;
        }
        return false;
    }

    private static boolean matchesMaterial(Block block, ItemStack cost, ItemStack sample) {
        Adapter adapter = adapter(block);
        return adapter == null ? cost.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(cost, sample)
            : adapter.matchesMaterial(block, cost, sample);
    }

    public static boolean matchesPlaced(Block block, int metadata, TileEntity actual, NBTTagCompound payload) {
        return matchesPlaced(block, metadata, actual, payload, null);
    }

    /** Compare the selected persistence after applying the provider's detached actor policy. */
    public static boolean matchesPlaced(Block block, int metadata, TileEntity actual, NBTTagCompound payload,
        net.minecraft.entity.player.EntityPlayerMP actor) {
        if (actual == null || payload == null) return false;
        if (HologramAeBlueprint.supports(block, payload))
            return HologramAeBlueprint.matchesPlaced(block, metadata, actual, payload);
        try {
            HologramResourceWorld detached = HologramResourceWorld.create(block, metadata);
            Adapter adapter = adapter(block);
            TileEntity expected = adapter.create(
                block,
                metadata,
                HologramEnergyPayload.withoutBuffers(HologramBlueprintNbt.nativePayload(payload)),
                detached);
            if (expected == null || !actual.getClass()
                .equals(expected.getClass())) return false;
            adapter.prepareActor(block, expected, actor);
            NBTTagCompound actualTag = HologramNativeCapture.capture(actual),
                expectedTag = adapter.captureExpected(expected);
            actualTag = HologramEnergyPayload.withoutBuffers(actualTag);
            expectedTag = HologramEnergyPayload.withoutBuffers(expectedTag);
            // Capture adds this project-owned display hint, while native writeToNBT deliberately does not.
            // Compare native persistence symmetrically; never omit arbitrary data using this field name.
            omitPreviewFlag(actualTag);
            omitPreviewFlag(expectedTag);
            adapter.normalizeComparison(block, actualTag);
            adapter.normalizeComparison(block, expectedTag);
            for (String key : new String[] { "x", "y", "z", HologramNetworkBindings.GROUP, HologramNetworkBindings.ROLE,
                HologramNetworkBindings.HINT, HologramNetworkBindings.CAPTURE_ANCHOR, "GTSWN_IncorporationIdentity" }) {
                actualTag.removeTag(key);
                expectedTag.removeTag(key);
            }
            return actualTag.equals(expectedTag);
        } catch (Exception | LinkageError invalid) {
            return false;
        }
    }

    private static void omitPreviewFlag(NBTTagCompound tag) {
        if (tag.hasKey("gtmb.nativePreview", 1)
            && (tag.getByte("gtmb.nativePreview") == 0 || tag.getByte("gtmb.nativePreview") == 1))
            tag.removeTag("gtmb.nativePreview");
    }

    /** Payment and Forge event rollback remain owned by HologramService. */
    public static TileEntity install(World world, int x, int y, int z, Block block, int metadata,
        NBTTagCompound payload) throws IOException {
        return install(world, x, y, z, block, metadata, payload, null);
    }

    public static TileEntity install(World world, int x, int y, int z, Block block, int metadata,
        NBTTagCompound payload, net.minecraft.entity.player.EntityPlayerMP actor) throws IOException {
        return install(world, x, y, z, block, metadata, payload, actor, null);
    }

    static TileEntity install(World world, int x, int y, int z, Block block, int metadata, NBTTagCompound payload,
        net.minecraft.entity.player.EntityPlayerMP actor, HologramNetworkBindings.State bindings) throws IOException {
        return install(world, x, y, z, block, metadata, payload, actor, bindings, false);
    }

    /** The server Service alone derives this permission from its validated, completed six-stage processing policy. */
    static TileEntity install(World world, int x, int y, int z, Block block, int metadata, NBTTagCompound payload,
        net.minecraft.entity.player.EntityPlayerMP actor, HologramNetworkBindings.State bindings,
        boolean verifiedQuantumClear) throws IOException {
        Adapter adapter = adapter(block);
        if (adapter == null) throw unsupported("unregistered native placement provider");
        if (verifiedQuantumClear && (!HologramNetworkBindings.node(block) || actor == null))
            throw unsupported("explicit quantum clear requires the registered node and a verified actor");
        try {
            NBTTagCompound selected = payload;
            if (HologramAeBlueprint.supports(block, payload)) {
                HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, metadata, payload);
                if (analysis.blocking || !analysis.supported) throw unsupported("AE blueprint has unresolved issues");
                selected = analysis.payload();
            }
            NBTTagCompound tag = HologramBlueprintNbt.nativePayload(selected);
            if (tag == null) throw unsupported("native tile payload was not retained");
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            // Deserialize with a read-only world before joining any real network or native lifecycle.
            HologramResourceWorld detached = HologramResourceWorld.create(block, metadata);
            TileEntity tile = adapter.create(block, metadata, tag, detached);
            if (tile == null || tile.getWorldObj() != null && tile.getWorldObj() != detached)
                throw unsupported("native factory did not create a detached tile");
            if (HologramAeBlueprint.supports(block, tag)) {
                HologramAeBlueprint.prepareActor(tile, actor);
                if (actor != null && HologramNetworkBindings.node(block)) {
                    if (verifiedQuantumClear) prepareClearedQuantum(tile, tag, actor);
                    else if (!tag.hasKey(HologramNetworkBindings.GROUP))
                        throw unsupported("Quantum blueprint node requires a server-owned local group descriptor");
                }
                if (tag.hasKey(HologramNetworkBindings.GROUP) && HologramNetworkBindings.node(block)) {
                    if (actor == null || bindings == null) throw unsupported("Quantum network binding is required");
                    HologramNetworkBindings.bindNode(tile, tag, actor, bindings);
                }
            } else if (actor != null) {
                boolean changed = false;
                if (block.getClass()
                    .getName()
                    .equals("appeng.block.networking.BlockCableBus"))
                    changed = rewriteCableOwners(tile, tag, aePlayerId(actor));
                if (block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")) {
                    List<?> parts = (List<?>) call(tile, "jPartList", new Class<?>[0]);
                    Integer playerId = null;
                    for (int i = 0; i < parts.size(); i++) {
                        Object part = parts.get(i);
                        NBTTagCompound row = tag.getTagList("parts", 10)
                            .getCompoundTagAt(i);
                        if (row.getString("id")
                            .equals("ae2_cablebus")) {
                            if (playerId == null) playerId = aePlayerId(actor);
                            changed |= rewriteCableOwners(call(part, "getCableBus", new Class<?>[0]), row, playerId);
                        }
                    }
                }
                if (changed) tile = adapter.create(block, metadata, tag, detached);
            }
            adapter.prepareActor(block, tile, actor);
            world.removeTileEntity(x, y, z);
            tile.setWorldObj(world);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            world.setTileEntity(x, y, z, tile);
            if (verifiedQuantumClear) requireUnboundQuantum(tile);
            finishJoin(world, block, tile);
            tile.markDirty();
            world.markBlockForUpdate(x, y, z);
            if (block.getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart"))
                Class.forName("codechicken.multipart.MultipartHelper")
                    .getMethod("sendDescPacket", World.class, TileEntity.class)
                    .invoke(null, world, tile);
            return tile;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException("Native blueprint placement rejected", failure);
        }
    }

    private static void prepareClearedQuantum(TileEntity tile, NBTTagCompound canonical,
        net.minecraft.entity.player.EntityPlayerMP actor) throws Exception {
        if (!HologramNetworkBindings.NODE_TILE.equals(
            tile.getClass()
                .getName()))
            throw unsupported("cleared quantum node factory/schema changed");
        for (String key : new String[] { HologramNetworkBindings.GROUP, HologramNetworkBindings.ROLE,
            HologramNetworkBindings.HINT, HologramNetworkBindings.CAPTURE_ANCHOR, "anchorDim", "anchorX", "anchorY",
            "anchorZ", "proxy", "linked" })
            if (canonical.hasKey(key)) throw unsupported("cleared quantum payload retained a network relation: " + key);
        requireUnboundQuantum(tile);
        // GTSWN's original setPlacer only assigns the local AE proxy owner. It never supplies an anchor.
        call(tile, "setPlacer", new Class<?>[] { net.minecraft.entity.player.EntityPlayer.class }, actor);
        requireUnboundQuantum(tile);
    }

    private static void requireUnboundQuantum(TileEntity tile) throws Exception {
        if (!hasUnboundNativeNode(tile))
            throw unsupported("cleared quantum node acquired an unverified external anchor");
    }

    /** Read-only exact GTSWN query shared by installation and Service target matching. */
    static boolean hasUnboundNativeNode(TileEntity tile) {
        if (tile == null || !HologramNetworkBindings.NODE_TILE.equals(
            tile.getClass()
                .getName()))
            return false;
        try {
            return !(Boolean) call(tile, "hasAnchor", new Class<?>[0])
                && (Integer) call(tile, "getAnchorDim", new Class<?>[0]) == Integer.MIN_VALUE
                && !(Boolean) call(tile, "isLinked", new Class<?>[0]);
        } catch (Exception | LinkageError invalidNativeSchema) {
            return false;
        }
    }

    static TileEntity rollbackTile(World world, Block block, int metadata, NBTTagCompound tag) {
        if (!supported(block)) return TileEntity.createAndLoadEntity(tag);
        try {
            if (adapter(block) instanceof HologramAeBlueprint.NativeAdapter) return HologramAeBlueprint
                .createRollbackNative(block, metadata, tag, HologramResourceWorld.create(block, metadata));
            return adapter(block).createRollback(block, metadata, tag, HologramResourceWorld.create(block, metadata));
        } catch (Exception | LinkageError failure) {
            throw new IllegalStateException("Native recovery tile failed", failure);
        }
    }

    private static int aePlayerId(EntityPlayerMP actor) {
        return appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(actor.getGameProfile());
    }

    /** Pure base persistence for reviewed modules whose native writer mutates global state. */
    public static NBTTagCompound captureBase(TileEntity tile) throws IOException {
        try {
            java.lang.reflect.Field names = ReflectionHelper
                .findField(TileEntity.class, "classToNameMap", "field_145853_j");
            @SuppressWarnings("unchecked")
            Map<Class<?>, String> registered = (Map<Class<?>, String>) names.get(null);
            String id = registered.get(tile.getClass());
            if (id == null) throw unsupported("unregistered native tile identity");
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", id);
            tag.setInteger("x", tile.xCoord);
            tag.setInteger("y", tile.yCoord);
            tag.setInteger("z", tile.zCoord);
            try {
                java.lang.reflect.Field data = TileEntity.class.getDeclaredField("customTileData");
                data.setAccessible(true);
                NBTTagCompound custom = (NBTTagCompound) data.get(tile);
                if (custom != null) tag.setTag("ForgeData", custom.copy());
            } catch (NoSuchFieldException absentInThisMinecraftVersion) {}
            return tag;
        } catch (IllegalAccessException failure) {
            throw new IOException("Cannot capture native base persistence", failure);
        }
    }

    /** Bind only an isolated factory result, before reviewed local decoding; never installs into a server world. */
    public static void bindDetached(TileEntity tile, World world, NBTTagCompound tag) throws IOException {
        if (!(world instanceof HologramResourceWorld)) throw unsupported("native factory requires a detached world");
        tile.setWorldObj(world);
        tile.xCoord = tag.getInteger("x");
        tile.yCoord = tag.getInteger("y");
        tile.zCoord = tag.getInteger("z");
        ((HologramResourceWorld) world).tile = tile;
    }

    /** Internal audited factory helper; supplied NBT never selects an arbitrary tile class. */
    public static TileEntity createDetached(Block block, int metadata, NBTTagCompound tag, World world)
        throws IOException {
        if (!(world instanceof HologramResourceWorld)) throw unsupported("native factory requires a detached world");
        TileEntity tile = block.createTileEntity(world, metadata);
        if (tile == null) throw unsupported("missing native tile factory");
        validateFactoryId(tag, tile);
        tile.setWorldObj(world);
        tile.xCoord = tag.getInteger("x");
        tile.yCoord = tag.getInteger("y");
        tile.zCoord = tag.getInteger("z");
        ((HologramResourceWorld) world).tile = tile;
        tile.readFromNBT((NBTTagCompound) tag.copy());
        return tile;
    }

    private static boolean rewriteCableOwners(Object host, NBTTagCompound rootTag, int playerId) throws Exception {
        boolean changed = false;
        for (int side = 0; side < 7; side++) {
            Object part = call(
                host,
                "getPart",
                new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
            if (!(part instanceof appeng.me.helpers.IGridProxyable)) continue;
            appeng.me.helpers.AENetworkProxy proxy = ((appeng.me.helpers.IGridProxyable) part).getProxy();
            NBTTagCompound extra = rootTag.getCompoundTag("extra:" + side);
            String name = HologramBlueprintOwnership.nodeKey(proxy);
            NBTTagCompound node = extra.getCompoundTag(name);
            node.setInteger("p", playerId);
            extra.setTag(name, node);
            rootTag.setTag("extra:" + side, extra);
            changed = true;
        }
        return changed;
    }

    /** Chunk insertion only validates a tile; native multipart parts must also join their real world once. */
    static void finishJoin(World world, Block block, TileEntity tile) {
        if (world.isRemote || !block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) return;
        try {
            call(tile, "onChunkLoad", new Class<?>[0]);
        } catch (Exception | LinkageError failure) {
            throw new IllegalStateException("Native multipart world join rejected", failure);
        }
    }

    private static IOException unsupported(String detail) {
        return new IOException("Unsupported native blueprint: " + detail);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = target.getClass()
            .getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /** The known block factory owns the tile class; a supplied ID must resolve to that exact registered class. */
    static void validateFactoryId(NBTTagCompound payload, TileEntity prototype) throws IOException {
        // Existing detached compatibility seeds are partial native schemas. Real captured tiles always retain id.
        // A missing id never selects another factory: the audited, registry-bound block already selected prototype.
        if (!payload.hasKey("id")) return;
        if (!payload.hasKey("id", 8) || payload.getString("id")
            .isEmpty()) throw unsupported("native tile id must be a nonempty registered string");
        try {
            NBTTagCompound factory = new NBTTagCompound();
            prototype.writeToNBT(factory);
            if (!factory.hasKey("id", 8) || factory.getString("id")
                .isEmpty()) throw unsupported("native block factory has no registered tile identity");
            if (factory.getString("id")
                .equals(payload.getString("id"))) return;
            java.lang.reflect.Field names = ReflectionHelper
                .findField(TileEntity.class, "nameToClassMap", "field_145855_i");
            @SuppressWarnings("unchecked")
            Map<String, Class<?>> registered = (Map<String, Class<?>>) names.get(null);
            // CarpentersBlocks registers official compatibility aliases for the same native TE class.
            // Accept only real runtime aliases, without instantiating an untrusted payload-selected class.
            if (registered.get(payload.getString("id")) != prototype.getClass())
                throw unsupported("native tile id does not match the registered block factory");
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Unsupported native block factory identity", failure);
        }
    }

    private static final class Builtin implements Adapter {

        public boolean includesInventoryDrops(Block block) {
            return block.getClass()
                .getName()
                .equals("appeng.block.networking.BlockCableBus");
        }

        public boolean supports(Block block) {
            String type = block.getClass()
                .getName();
            return HologramBlockIdentity.vanillaSign(block) || type.equals("codechicken.multipart.BlockMultipart")
                || type.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                || type.equals("gcewing.architecture.common.block.BlockShape")
                || type.equals("crazypants.enderio.conduit.BlockConduitBundle")
                || type.equals("appeng.block.networking.BlockCableBus");
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) throws Exception {
            TileEntity tile;
            String type = block.getClass()
                .getName();
            if (HologramBlockIdentity.vanillaSign(block)) {
                if (metadata < 0 || metadata > 15
                    || block == net.minecraft.init.Blocks.wall_sign && (metadata < 2 || metadata > 5))
                    throw unsupported("invalid vanilla sign orientation");
                tile = block.createTileEntity(world, metadata);
                validateFactoryId(tag, tile);
                tile.setWorldObj(world);
                tile.readFromNBT((NBTTagCompound) tag.copy());
                return tile;
            }
            if (type.equals("codechicken.multipart.BlockMultipart")) {
                // MultipartSaveLoad uses savedMultipart -> TileNBTContainer at load time, then creates a dynamic tile.
                // Its writer maps every dynamic tile class back to this exact native persistence carrier.
                if (tag.hasKey("id") && (!tag.hasKey("id", 8) || !"savedMultipart".equals(tag.getString("id"))))
                    throw unsupported("multipart root id does not match the native persistence carrier");
                for (int i = 0; i < tag.getTagList("parts", 10)
                    .tagCount(); i++) {
                    NBTTagCompound part = tag.getTagList("parts", 10)
                        .getCompoundTagAt(i);
                    if (part.getString("id")
                        .startsWith("mcr_")
                        && Class.forName("codechicken.microblock.MicroMaterialRegistry")
                            .getMethod("getMaterial", String.class)
                            .invoke(null, part.getString("material")) == null)
                        throw unsupported("unregistered multipart material");
                }
                Object loading = Class.forName("codechicken.multipart.handler.MultipartSaveLoad$")
                    .getField("MODULE$")
                    .get(null);
                Object previousWorld = call(loading, "loadingWorld", new Class<?>[0]);
                try {
                    tile = (TileEntity) Class.forName("codechicken.multipart.MultipartHelper")
                        .getMethod("createTileFromNBT", World.class, NBTTagCompound.class)
                        .invoke(null, null, tag.copy());
                } finally {
                    call(loading, "loadingWorld_$eq", new Class<?>[] { World.class }, previousWorld);
                }
                if (tile == null) throw unsupported("multipart factory rejected all parts");
                Object parts = call(tile, "partList", new Class<?>[0]);
                if ((Integer) call(parts, "size", new Class<?>[0]) != tag.getTagList("parts", 10)
                    .tagCount()) throw unsupported("unregistered multipart component");
            } else {
                if (type.equals("gcewing.architecture.common.block.BlockShape")) {
                    Object shape = Class.forName("gcewing.architecture.common.shape.Shape")
                        .getMethod("forId", int.class)
                        .invoke(null, tag.getInteger("Shape"));
                    if (!tag.hasKey("Shape", 99) || shape.getClass()
                        .getField("id")
                        .getInt(shape) != tag.getInteger("Shape"))
                        throw unsupported("unregistered ArchitectureCraft shape");
                    for (String material : new String[] { "BaseName", "Name2" }) {
                        if (material.equals("Name2") && !tag.hasKey(material)) continue;
                        Block nativeBlock = Block.getBlockFromName(tag.getString(material));
                        if (nativeBlock == null || nativeBlock == net.minecraft.init.Blocks.air)
                            throw unsupported("unregistered ArchitectureCraft material");
                    }
                }
                tile = block.createTileEntity(world, metadata);
                if (tile == null) throw unsupported("missing native tile factory");
                if (type.equals("gcewing.architecture.common.block.BlockShape")
                    || type.equals("com.creativemd.littletiles.common.blocks.BlockTile")) validateFactoryId(tag, tile);
                tile.setWorldObj(world);
                tile.xCoord = tag.getInteger("x");
                tile.yCoord = tag.getInteger("y");
                tile.zCoord = tag.getInteger("z");
                if (world instanceof HologramResourceWorld) ((HologramResourceWorld) world).tile = tile;
                tile.readFromNBT((NBTTagCompound) tag.copy());
                if (type.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                    && ((List<?>) call(tile, "getTiles", new Class<?>[0])).size() != tag.getInteger("tilesCount"))
                    throw unsupported("unregistered LittleTiles component");
                if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")
                    && ((java.util.Collection<?>) call(tile, "getConduits", new Class<?>[0])).size()
                        != tag.getTagList("conduits", 10)
                            .tagCount())
                    throw unsupported("unregistered EnderIO conduit");
                if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")) {
                    String facade = tag.getString("facadeId");
                    if (!facade.isEmpty() && !facade.equals("null")
                        && !(Boolean) call(tile, "hasFacade", new Class<?>[0]))
                        throw unsupported("unregistered EnderIO facade material");
                }
                if (type.equals("appeng.block.networking.BlockCableBus")) for (int side = 0; side < 7; side++) {
                    if (tag.hasKey("def:" + side, 10)) {
                        Object part = call(
                            tile,
                            "getPart",
                            new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                            net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
                        if (part == null) throw unsupported("unregistered AE2 part");
                    }
                    if (side < 6 && tag.hasKey("facade:" + side, 10)) {
                        Object facade = call(
                            call(tile, "getFacadeContainer", new Class<?>[0]),
                            "getFacade",
                            new Class<?>[] { net.minecraftforge.common.util.ForgeDirection.class },
                            net.minecraftforge.common.util.ForgeDirection.getOrientation(side));
                        if (facade == null) throw unsupported("unregistered AE2 facade");
                    }
                }
            }
            tile.setWorldObj(world);
            tile.xCoord = tag.getInteger("x");
            tile.yCoord = tag.getInteger("y");
            tile.zCoord = tag.getInteger("z");
            return tile;
        }

        @SuppressWarnings("unchecked")
        public List<ItemStack> materials(Block block, int metadata, TileEntity tile) throws Exception {
            List<ItemStack> costs = new ArrayList<>();
            if (HologramBlockIdentity.vanillaSign(block)) {
                costs.add(new ItemStack(Items.sign, 1, 0));
                return costs;
            }
            String type = block.getClass()
                .getName();
            if (type.equals("codechicken.multipart.BlockMultipart")) {
                Object iterator = call(call(tile, "partList", new Class<?>[0]), "iterator", new Class<?>[0]);
                while ((Boolean) call(iterator, "hasNext", new Class<?>[0])) {
                    Object part = call(iterator, "next", new Class<?>[0]);
                    Object drops = call(call(part, "getDrops", new Class<?>[0]), "iterator", new Class<?>[0]);
                    int count = costs.size();
                    while ((Boolean) call(drops, "hasNext", new Class<?>[0]))
                        costs.add((ItemStack) call(drops, "next", new Class<?>[0]));
                    if (costs.size() == count) throw unsupported("multipart part has no material");
                }
            } else if (type.equals("appeng.block.networking.BlockCableBus")) {
                Object container = call(tile, "getCableBus", new Class<?>[0]);
                call(container, "getDrops", new Class<?>[] { List.class }, costs);
            } else if (type.equals("crazypants.enderio.conduit.BlockConduitBundle")) {
                for (Object conduit : (java.util.Collection<?>) call(tile, "getConduits", new Class<?>[0])) {
                    List<ItemStack> drops = (List<ItemStack>) call(conduit, "getDrops", new Class<?>[0]);
                    if (drops.isEmpty()) throw unsupported("conduit has no material");
                    costs.addAll(drops);
                }
                if ((Boolean) call(tile, "hasFacade", new Class<?>[0])) {
                    Item item = (Item) Class.forName("crazypants.enderio.EnderIO")
                        .getField("itemConduitFacade")
                        .get(null);
                    Enum<?> facade = (Enum<?>) call(tile, "getFacadeType", new Class<?>[0]);
                    ItemStack stack = new ItemStack(item, 1, facade.ordinal());
                    Class.forName("crazypants.enderio.machine.painter.PainterUtil")
                        .getMethod("setSourceBlock", ItemStack.class, Block.class, int.class)
                        .invoke(
                            null,
                            stack,
                            call(tile, "getFacadeId", new Class<?>[0]),
                            call(tile, "getFacadeMetadata", new Class<?>[0]));
                    costs.add(stack);
                }
                // Legacy FMP covers have independent persistence/materials; no free unpriced restoration.
                NBTTagCompound tag = new NBTTagCompound();
                tile.writeToNBT(tag);
                if (tag.hasKey("microblocks"))
                    throw unsupported("legacy conduit microblock covers require a registered provider");
            } else if (type.equals("com.creativemd.littletiles.common.blocks.BlockTile")) {
                for (Object little : (List<?>) call(tile, "getTiles", new Class<?>[0])) {
                    List<ItemStack> drops = (List<ItemStack>) call(little, "getDrops", new Class<?>[0]);
                    if (drops.isEmpty()) throw unsupported("LittleTiles child has no material");
                    costs.addAll(drops);
                }
            } else {
                costs.addAll(block.getDrops(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, metadata, 0));
            }
            return costs;
        }
    }
}

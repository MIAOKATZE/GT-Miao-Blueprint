package com.miaokatze.gtmb.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.implementations.items.ISpatialStorageCell;
import appeng.api.implementations.items.IUpgradeModule;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.block.AEBaseTileBlock;
import appeng.helpers.IInterfaceHost;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.AEBaseInvTile;
import appeng.tile.AEBaseTile;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.inventory.IAEAppEngInventory;
import appeng.tile.inventory.IAEStackInventory;
import appeng.util.Platform;

/** AE2 rv3-beta-1080 schema analysis. Every public input and output is a disposable copy. */
public final class HologramAeBlueprint {

    public static final int POLICY_VERSION = 1;
    public static final int MAX_MARK_BYTES = 65536;
    private static final int MAX_SLOTS = 512;
    private static final int MAX_UNRESOLVED = 512;
    public static final String UNRESOLVED = "gtmbAeUnresolved";
    private static final Set<String> LOST_BLOCKERS = new HashSet<>(
        Arrays.asList(
            "static_block_data",
            "cpu_runtime_omitted",
            "unreviewed_tile_field",
            "missing_physical_item",
            "unknown_inventory_slot",
            "unsupported_ghost",
            "cell_partition_invalid",
            "interface_storage_alias_conflict",
            "unreviewed_part_field",
            "unreviewed_interface_field",
            "unreviewed_cable_field",
            "unsupported_p2p_energy",
            "waiting_stack_invalid",
            "waiting_stack_unsupported",
            "unsupported_host",
            "unsupported_part",
            "unsupported_part_item",
            "unsupported_cell"));
    private static final Set<String> PERMANENT_BLOCKERS = new HashSet<>(
        Arrays.asList("unsupported_host", "unsupported_part", "unsupported_part_item", "unsupported_cell"));
    private static final String TILE = "appeng.tile.";
    private static final Map<String, Schema> SCHEMAS = new HashMap<>();
    // AE2FluidCraft 1.5.113: these finite cells inherit AEBaseCell's stack-backed FLUID schema.
    // Creative, void, infinity-water and portable cells have different native behavior and remain unreviewed.
    private static final Set<String> AE2FC_CELL_TYPES = new HashSet<>(
        Arrays.asList(
            "com.glodblock.github.common.item.ItemBasicFluidStorageCell",
            "com.glodblock.github.common.item.ItemMultiFluidStorageCell",
            "com.glodblock.github.common.item.ItemFluidExtremeStorageCell"));
    private static final Set<String> CELL_TYPES = new HashSet<>(
        Arrays.asList(
            "appeng.items.storage.ItemBasicStorageCell",
            "appeng.items.storage.ItemExtremeStorageCell",
            "appeng.items.storage.ItemAdvancedStorageCell",
            "appeng.items.storage.ItemVoidStorageCell",
            "com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageCell",
            "com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageFluidCell",
            "com.miaokatze.gtit.common.items.infinitycell.ItemNekoInfinityStorageUnit"));
    private static final Set<String> CELL_CONFIG_KEYS = new HashSet<>(
        Arrays.asList(
            "list",
            "upgrades",
            "FuzzyMode",
            "OreFilter",
            "cellRestrictionTypes",
            "cellRestrictionAmount",
            "display"));
    private static Field listContents;

    static {
        CELL_TYPES.addAll(AE2FC_CELL_TYPES);
        schema("storage.TileDrive", inv("inv", "item", 10, "emptyCell", 1, "cell"));
        schema("storage.TileChest", inv("inv", "item", 2, "physical", 64, "any"));
        schema("storage.TileIOPort", inv("cells", "#", 12, "emptyCell", 1, "cell"), upgrade(3));
        schema("storage.TileSkyChest", inv("inv", "item", 36, "physical", 64, "any"));
        schema(
            "misc.TileInterface",
            inv("config", "#", 9, "ghost", 64, "any"),
            inv("patterns", "#", 36, "physical", 1, "pattern"),
            inv("storage", "#", 9, "physical", 64, "any"),
            upgrade(4));
        schema("crafting.TileMolecularAssembler", inv("inv", "#", 11, "physical", 64, "assembler"), upgrade(5));
        schema("misc.TileInscriber", inv("inscriberInv", "#", 4, "physical", 1, "inscriber"), upgrade(3));
        schema("misc.TileAdvancedInscriber", inv("inv", "#", 4, "physical", 64, "advancedInscriber"), upgrade(5));
        schema("misc.TileCrystalGrowthChamber", inv("inv", "item", 27, "physical", 64, "any"), upgrade(3));
        schema(
            "misc.TileCellWorkbench",
            inv("cell", "#", 1, "emptyCell", 1, "workbenchCell"),
            inv("config", "#", 63, "ghost", 64, "any"));
        schema("misc.TileCharger", inv("inv", "item", 1, "physical", 1, "chargeable"));
        schema("misc.TileCondenser", inv("inv", "item", 3, "physical", 64, "any"));
        schema("misc.TileVibrationChamber", inv("inv", "item", 1, "physical", 64, "fuel"));
        schema("grindstone.TileGrinder", inv("inv", "item", 7, "physical", 64, "any"));
        schema("qnb.TileQuantumBridge", inv("inv", "item", 1, "physical", 1, "singularity"));
        schema("networking.TileWireless", inv("inv", "item", 1, "physical", 64, "rangeBooster"));
        schema("networking.TileWirelessHub");
        schema("networking.TileWirelessConnector");
        schema("spatial.TileSpatialIOPort", inv("inv", "item", 2, "emptyCell", 1, "spatialCell"));
        schema("spatial.TileSpatialLinkChamber", inv("inv", "item", 1, "emptyCell", 1, "spatialCell"));
        for (String name : Arrays.asList(
            "networking.TileController",
            "networking.TileEnergyAcceptor",
            "networking.TileEnergyCell",
            "networking.TileDenseEnergyCell",
            "networking.TileCreativeEnergyCell",
            "networking.TileCreativeEnergyController",
            "spatial.TileSpatialPylon",
            "misc.TileQuartzGrowthAccelerator",
            "misc.TileSkyCompass",
            "misc.TileLightDetector",
            "grindstone.TileCrank")) schema(name);
        // CPU runtime may contain physical inventory. Shell recognition is allowed; data is a blocker.
        for (String name : Arrays.asList(
            "crafting.TileCraftingTile",
            "crafting.TileCraftingStorageTile",
            "crafting.TileCraftingMonitorTile")) schema(name);
    }

    private HologramAeBlueprint() {}

    /** Exact AE2FC class/API gate; a generic storage-cell implementation cannot acquire this schema. */
    static boolean nativeFluidCellSchema(ItemStack source) {
        return source != null && source.getItem() instanceof appeng.items.AEBaseCell
            && AE2FC_CELL_TYPES.contains(
                source.getItem()
                    .getClass()
                    .getName())
            && ((appeng.items.AEBaseCell) source.getItem()).getStackType()
                == appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;
    }

    /** Only the native root storage records are contents; nested recipes and other metadata are inert here. */
    static NBTTagCompound projectNativeFluidCellMetadata(String type, NBTTagCompound source) throws IOException {
        if (!AE2FC_CELL_TYPES.contains(type)) throw new IOException("Unreviewed AE2FluidCraft cell schema");
        NBTTagCompound empty = source == null ? new NBTTagCompound() : (NBTTagCompound) source.copy();
        for (Object raw : new HashSet<Object>(empty.func_150296_c())) {
            String key = (String) raw;
            if (key.equals("ft") || key.equals("fc") || key.matches("[#@][0-9]+")) empty.removeTag(key);
        }
        return empty;
    }

    public static final class Issue {

        public final String code, severity, path, message;

        public Issue(String code, String severity, String path, String message) {
            this.code = code;
            this.severity = severity;
            this.path = path;
            this.message = message;
        }
    }

    public static final class Slot {

        public final String id, kind, label;
        public final int limit;
        public final List<String> allowed;
        public final boolean enabled;
        /** Only populated for a server-issued review tombstone; not an arbitrary mutation path. */
        public final String issueCode, issuePath;
        private final ItemStack stack;
        private final String[] path;
        private final String validator;
        private final int nativeIndex;
        private final Object host;

        private Slot(String id, String kind, int limit, String validator, ItemStack stack, String[] path,
            int nativeIndex, Object host) {
            this(id, kind, limit, validator, stack, path, nativeIndex, host, !kind.equals("readonly"));
        }

        private Slot(String id, String kind, int limit, String validator, ItemStack stack, String[] path,
            int nativeIndex, Object host, boolean enabled) {
            this(id, kind, limit, validator, stack, path, nativeIndex, host, enabled, "", "");
        }

        private Slot(String id, String kind, int limit, String validator, ItemStack stack, String[] path,
            int nativeIndex, Object host, boolean enabled, String issueCode, String issuePath) {
            this.id = id;
            this.kind = kind;
            this.label = issueCode.isEmpty() ? id : "Confirm discard: " + issuePath;
            this.limit = limit;
            this.allowed = Collections.singletonList(validator);
            this.enabled = enabled;
            this.issueCode = issueCode;
            this.issuePath = issuePath;
            this.stack = stack == null ? null : stack.copy();
            this.path = path.clone();
            this.validator = validator;
            this.nativeIndex = nativeIndex;
            this.host = host;
        }

        public ItemStack stack() {
            return stack == null ? null : stack.copy();
        }
    }

    public static final class Analysis {

        public final boolean supported, blocking;
        public final List<Slot> slots;
        public final List<Issue> issues;
        /** Physical component costs only; the main block/part shell is paid by the existing transaction. */
        public final List<ItemStack> materials;
        /** Top-level NBT roots owned by this adapter; do not scan them again with generic resource heuristics. */
        public final Set<String> handledRoots;
        private final NBTTagCompound data;

        private Analysis(Work work) throws IOException {
            writeUnresolved(work);
            this.supported = work.supported;
            boolean denied = false;
            for (Issue issue : work.issues) denied |= issue.severity.equals("blocker");
            this.blocking = denied;
            this.data = (NBTTagCompound) work.data.copy();
            this.slots = Collections.unmodifiableList(new ArrayList<>(work.slots));
            this.issues = Collections.unmodifiableList(new ArrayList<>(work.issues));
            List<ItemStack> copied = new ArrayList<>();
            for (ItemStack stack : work.materials) copied.add(stack.copy());
            this.materials = Collections.unmodifiableList(copied);
            this.handledRoots = Collections.unmodifiableSet(new LinkedHashSet<>(work.roots));
        }

        public NBTTagCompound payload() {
            return (NBTTagCompound) data.copy();
        }
    }

    static final class EmptyCellProjection {

        final ItemStack stack;
        final List<ItemStack> materials;

        private EmptyCellProjection(ItemStack stack, List<ItemStack> materials) {
            this.stack = stack.copy();
            this.materials = new ArrayList<>();
            for (ItemStack material : materials) this.materials.add(material.copy());
        }
    }

    static EmptyCellProjection emptyCellProjection(ItemStack source) throws IOException {
        if (source == null || source.getItem() == null || source.stackSize != 1)
            throw new IOException("ME 仓室元件本体无效，不能投影配置");
        Work work = new Work(new NBTTagCompound());
        ItemStack projected = HologramAeBlueprintSchemas.emptyCell(work, source.copy(), new String[] { "Inventory" });
        if (projected == null) throw new IOException("ME 仓室元件不属于已审阅的空元件配置格式");
        for (Issue issue : work.issues)
            if ("blocker".equals(issue.severity)) throw new IOException("ME 仓室元件配置无效：" + issue.message);
        return new EmptyCellProjection(projected, work.materials);
    }

    private static final class Work {

        NBTTagCompound data;
        boolean supported = true;
        final List<Slot> slots = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        final List<ItemStack> materials = new ArrayList<>();
        final Set<String> roots = new LinkedHashSet<>();

        Work(NBTTagCompound source) throws IOException {
            data = source == null ? new NBTTagCompound() : (NBTTagCompound) source.copy();
            for (Issue old : readUnresolved(data)) issues.add(
                new Issue(
                    old.code,
                    automaticallyOmitted(old.code) ? "warning" : old.severity,
                    old.path,
                    automaticallyOmitted(old.code)
                        ? "Unavailable component was omitted; replace its marker to restore it."
                        : old.message));
            data.removeTag(UNRESOLVED);
            roots.add(UNRESOLVED);
        }

        void issue(String code, String severity, String path, String message) {
            for (Issue issue : issues) if (issue.code.equals(code) && issue.path.equals(path)) return;
            issues.add(new Issue(code, severity, path, message));
        }
    }

    static boolean automaticallyOmitted(String code) {
        return "unsupported_cell".equals(code) || "unsupported_part".equals(code)
            || "unsupported_part_item".equals(code);
    }

    private static final class Inventory {

        final String root, prefix, kind, validator;
        final int size, limit;

        Inventory(String root, String prefix, int size, String kind, int limit, String validator) {
            this.root = root;
            this.prefix = prefix;
            this.size = size;
            this.kind = kind;
            this.limit = limit;
            this.validator = validator;
        }
    }

    private static final class Schema {

        final List<Inventory> inventories;

        Schema(Inventory... inventories) {
            this.inventories = Arrays.asList(inventories);
        }
    }

    private static Inventory inv(String root, String prefix, int size, String kind, int limit, String validator) {
        return new Inventory(root, prefix, size, kind, limit, validator);
    }

    private static Inventory upgrade(int size) {
        return inv("upgrades", "#", size, "physical", 1, "upgrade");
    }

    private static void schema(String name, Inventory... inventories) {
        SCHEMAS.put(TILE + name, new Schema(inventories));
    }

    /** Recognition is broader than restoration. Unknown AE hosts receive a safe shell and an explicit blocker. */
    public static boolean supports(Block block) {
        return HologramNetworkBindings.node(block) || block != null && block.getClass()
            .getName()
            .startsWith("appeng.block.");
    }

    public static boolean supports(Block block, NBTTagCompound payload) {
        if (supports(block)) return true;
        if (block == null || payload == null
            || !block.getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart"))
            return false;
        NBTTagList parts = payload.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) if (isAeMultipartPart(parts.getCompoundTagAt(i))) return true;
        return false;
    }

    /** Exact FMP serialized part discriminator; callers must establish the BlockMultipart host first. */
    public static boolean isAeMultipartPart(NBTTagCompound part) {
        return part != null && part.getString("id")
            .equals("ae2_cablebus");
    }

    /** Ordinary reviewed machines can install their filtered empty shell; multipart structure still requires NBT. */
    public static boolean supportsEmptyNativeShell(Block block) {
        if (HologramNetworkBindings.node(block)) return true;
        return block instanceof AEBaseTileBlock && !block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")
            && new NativeAdapter().supports(block)
            && HologramNativePlacement.adapter(block) instanceof NativeAdapter;
    }

    /** Register after the legacy Builtin so this provider owns reviewed ordinary AE blocks and CableBus. */
    public static final class NativeAdapter implements HologramNativePlacement.Adapter {

        @Override
        public boolean supports(Block block) {
            if (!HologramAeBlueprint.supports(block)) return false;
            if (HologramNetworkBindings.node(block)) return true;
            if (block.getClass()
                .getName()
                .equals("appeng.block.networking.BlockCableBus")) return true;
            if (!(block instanceof AEBaseTileBlock)) return false;
            Class<? extends TileEntity> type = ((AEBaseTileBlock) block).getTileEntityClass();
            return type != null && SCHEMAS.containsKey(type.getName());
        }

        @Override
        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detached) throws IOException {
            return createNative(block, metadata, tag, detached);
        }

        @Override
        public List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws IOException {
            NBTTagCompound tag = new NBTTagCompound();
            detachedTile.writeToNBT(tag);
            Analysis analysis = analyze(block, metadata, tag);
            if (analysis.blocking) throw new IOException("AE components are outside the audited material schema");
            List<ItemStack> costs = nativeShellMaterials(block, metadata, analysis.payload());
            for (ItemStack stack : analysis.materials) costs.add(stack.copy());
            return costs;
        }

        @Override
        public boolean includesInventoryDrops(Block block) {
            return true;
        }
    }

    public static ItemStack blockSample(Block block, int metadata) throws IOException {
        if (!supports(block)) return null;
        if (block instanceof AEBaseTileBlock) {
            try {
                Field mapping = AEBaseTile.class.getDeclaredField("ITEM_STACKS");
                mapping.setAccessible(true);
                Object source = ((Map<?, ?>) mapping.get(null)).get(((AEBaseTileBlock) block).getTileEntityClass());
                if (source instanceof appeng.core.features.IStackSrc) {
                    ItemStack sample = ((appeng.core.features.IStackSrc) source).stack(1);
                    if (sample != null) {
                        sample = sample.copy();
                        if (sample.getItem() == Item.getItemFromBlock(block))
                            sample.setItemDamage(block.damageDropped(metadata));
                        return sample;
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new IOException("AE tile item registry could not be read", failure);
            }
        }
        Item item = Item.getItemFromBlock(block);
        if (item == null) throw new IOException("AE block has no registered material");
        return new ItemStack(item, 1, block.damageDropped(metadata));
    }

    /** Editable draft identity only. This never treats unresolved contents as payable or installable. */
    public static ItemStack sampleIdentity(Block block, int metadata, NBTTagCompound rawPayload) throws IOException {
        if (block == null) return null;
        if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) return cableIdentity(rawPayload);
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart") && rawPayload != null) {
            NBTTagList parts = rawPayload.getTagList("parts", 10);
            for (int i = 0; i < parts.tagCount(); i++) {
                NBTTagCompound part = parts.getCompoundTagAt(i);
                if (part.getString("id")
                    .equals("ae2_cablebus")) {
                    ItemStack sample = cableIdentity(part);
                    if (sample != null) return sample;
                }
            }
            return null;
        }
        return blockSample(block, metadata);
    }

    private static ItemStack cableIdentity(NBTTagCompound payload) throws IOException {
        if (payload == null) return null;
        for (int side = 0; side < 7; side++) {
            ItemStack sample = HologramAeBlueprintSchemas.readStack(payload.getCompoundTag("def:" + side));
            if (sample != null && sample.getItem() instanceof IPartItem)
                return new ItemStack(sample.getItem(), 1, sample.getItemDamage());
        }
        return null;
    }

    /** CableBus shells only. FMP callers must also pay non-AE components with their existing native provider. */
    public static List<ItemStack> nativeShellMaterials(Block block, int metadata, NBTTagCompound payload)
        throws IOException {
        Analysis analysis = analyze(block, metadata, payload);
        if (analysis.blocking) throw new IOException("Unresolved AE shell material schema");
        List<ItemStack> result = new ArrayList<>();
        NBTTagCompound data = analysis.payload();
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) {
            NBTTagList parts = data.getTagList("parts", 10);
            for (int i = 0; i < parts.tagCount(); i++) {
                NBTTagCompound part = parts.getCompoundTagAt(i);
                if (part.getString("id")
                    .equals("ae2_cablebus")) shellMaterials(part, result);
            }
        } else if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) shellMaterials(data, result);
        else {
            ItemStack sample = blockSample(block, metadata);
            if (sample != null && !HologramGtswnPlacementMaterials.freeShell(block, sample)) result.add(sample);
            if (HologramNetworkBindings.node(block)) shellMaterials(data.getCompoundTag("partContainer"), result);
        }
        return result;
    }

    /** Disposable FMP remainder for the existing provider; do not overwrite the original blueprint. */
    public static NBTTagCompound withoutAeMultipart(NBTTagCompound payload) {
        NBTTagCompound result = (NBTTagCompound) payload.copy();
        NBTTagList remaining = new NBTTagList();
        NBTTagList source = result.getTagList("parts", 10);
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound part = source.getCompoundTagAt(i);
            if (!part.getString("id")
                .equals("ae2_cablebus")) remaining.appendTag(part.copy());
        }
        result.setTag("parts", remaining);
        return result;
    }

    /** One projection for review and real reservation. A non-null main shell is paid by the service. */
    public static HologramBlueprintResources.Plan resourcePlan(Block block, int metadata, NBTTagCompound payload,
        ItemStack main) throws IOException {
        Analysis analysis = analyze(block, metadata, payload);
        if (!analysis.supported || analysis.blocking) throw new IOException("AE resource policy has unresolved issues");
        NBTTagCompound canonical = HologramBlueprintNbt.nativePayload(analysis.payload());
        HologramAeNativeResources.validateSelectedFinalPayload(canonical);
        List<ItemStack> shells = nativeShellMaterials(block, metadata, analysis.payload());
        HologramBlueprintResources.Plan remainder;
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) {
            NBTTagCompound nonAe = withoutAeMultipart(canonical);
            remainder = HologramBlueprintResources.plan(nonAe);
            if (nonAe.getTagList("parts", 10)
                .tagCount() > 0) {
                // Keep the established provider and inventory-drop policy for every non-AE part.
                List<ItemStack> nativeCosts = HologramNativePlacement.materials(block, metadata, nonAe);
                if (HologramNativePlacement.adapter(block)
                    .includesInventoryDrops(block)) remainder.removeItemsIncludedByNativeDrops(nativeCosts);
                for (ItemStack stack : nativeCosts) shells.add(stack.copy());
            }
        } else {
            for (String root : analysis.handledRoots) canonical.removeTag(root);
            remainder = HologramBlueprintResources.plan(canonical);
        }
        if (main != null && !HologramGtswnPlacementMaterials.freeShell(block, main)) {
            boolean matched = false;
            for (int i = 0; i < shells.size(); i++) {
                ItemStack shell = shells.get(i);
                if (!shell.isItemEqual(main) || !ItemStack.areItemStackTagsEqual(shell, main)) continue;
                if (--shell.stackSize == 0) shells.remove(i);
                matched = true;
                break;
            }
            if (!matched) throw new IOException("Main material does not match the AE/native shell identity");
        }
        return remainder.addNativeItems(shells)
            .addNativeItems(analysis.materials);
    }

    /** Compare persistent canonical configuration; fresh network identities and target coordinates are excluded. */
    public static boolean matchesPlaced(Block block, int metadata, TileEntity actual, NBTTagCompound expected) {
        if (actual == null || expected == null || !supports(block, expected)) return false;
        try {
            NBTTagCompound actualTag = new NBTTagCompound();
            actual.writeToNBT(actualTag);
            Analysis left = analyze(block, metadata, actualTag), right = analyze(block, metadata, expected);
            if (left.blocking || right.blocking || !left.supported || !right.supported) return false;
            // Portable item identities are transport metadata; compare the complete native configuration on both sides.
            NBTTagCompound canonicalActual = HologramEnergyPayload
                .withoutBuffers(HologramBlueprintNbt.nativePayload(left.payload()));
            NBTTagCompound canonicalExpected = HologramEnergyPayload
                .withoutBuffers(HologramBlueprintNbt.nativePayload(right.payload()));
            for (String key : Arrays.asList("x", "y", "z")) {
                canonicalActual.removeTag(key);
                canonicalExpected.removeTag(key);
            }
            for (String key : Arrays.asList(
                HologramNetworkBindings.GROUP,
                HologramNetworkBindings.ROLE,
                HologramNetworkBindings.HINT,
                HologramNetworkBindings.ORIGINAL,
                HologramNetworkBindings.MODE)) {
                canonicalActual.removeTag(key);
                canonicalExpected.removeTag(key);
            }
            return canonicalActual.equals(canonicalExpected);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static void shellMaterials(NBTTagCompound data, List<ItemStack> result) throws IOException {
        for (int side = 0; side < 7; side++) {
            if (data.hasKey("def:" + side, 10)) {
                ItemStack stack = HologramAeBlueprintSchemas.readStack(data.getCompoundTag("def:" + side));
                if (stack == null || !(stack.getItem() instanceof IPartItem))
                    throw new IOException("Missing AE part shell");
                result.add(new ItemStack(stack.getItem(), 1, stack.getItemDamage()));
            }
            if (side < 6 && data.hasKey("facade:" + side, 10)) {
                ItemStack stack = HologramAeBlueprintSchemas.readStack(data.getCompoundTag("facade:" + side));
                if (stack == null) throw new IOException("Missing AE facade shell");
                result.add(HologramAeBlueprintSchemas.nativeStack(stack));
            }
        }
    }

    private static void quantumNode(Work work, Block block, int metadata) throws IOException {
        NBTTagCompound data = work.data;
        TileEntity host = block.createTileEntity(null, metadata);
        if (host == null || !HologramNetworkBindings.NODE_TILE.equals(
            host.getClass()
                .getName()))
            throw new IOException("GTSWN quantum node factory/schema changed");
        cleanHost(work, data, host, new String[0]);
        for (String key : Arrays.asList("placerUUID", "placerName")) {
            if (!data.hasKey(key)) continue;
            work.roots.add(key);
            if (!HologramNetworkBindings.validOwnerField(data, key, "placerUUID".equals(key)))
                work.issue("unreviewed_tile_field", "blocker", key, "量子节点放置者信息的类型或 UUID 无效，请核验后再处理");
            else work.issue("network_identity", "warning", key, "已移除来源放置者信息，放置时由 GTSWN 记录当前玩家");
            data.removeTag(key);
        }
        for (String key : Arrays.asList("anchorDim", "anchorX", "anchorY", "anchorZ", "linked"))
            HologramAeBlueprintSchemas.remove(
                work,
                data,
                key,
                new String[0],
                "quantum_rebind",
                "Original quantum coordinates are removed; bind the local group before placement");
        if (data.hasKey(HologramNetworkBindings.GROUP)) HologramNetworkBindings.group(data);
        HologramNetworkBindings.validateOriginal(data);
        if (data.hasKey("color")
            && (!data.hasKey("color", 3) || data.getInteger("color") < 0 || data.getInteger("color") > 15))
            throw new IOException("Invalid GTSWN quantum color");
        // This palette belongs to the selected network; setAnchor rebuilds it from the new controller.
        data.removeTag("color");
        work.roots.add("color");
        if (data.hasKey("lastAppliedMigration")) {
            work.roots.add("lastAppliedMigration");
            // GTSWN 1.8.66 persists the cursor of its world's QuantumMigrationJournal on every node.
            // It is neither configuration nor portable identity: setAnchor starts at the target world's
            // current sequence. Validate before omission so a lookalike field cannot hide arbitrary data.
            if (!validQuantumMigrationCursor(data)) {
                work.issue(
                    "unreviewed_tile_field",
                    "blocker",
                    "lastAppliedMigration",
                    "GTSWN migration cursor must be a nonnegative long; malformed data requires explicit review");
            } else {
                work.issue(
                    "quantum_migration_reset",
                    "warning",
                    "lastAppliedMigration",
                    "Source-world migration cursor is omitted; the selected target network starts its own cursor");
            }
            data.removeTag("lastAppliedMigration");
        }
        if (data.hasKey("partContainer")) {
            if (!data.hasKey("partContainer", 10)) throw new IOException("Malformed GTSWN part container");
            cable(work, data.getCompoundTag("partContainer"), new String[] { "partContainer" });
        }
        // Keep this exact scalar in saved/preview data. nativePayload removes it before native readers or payment.
        if (data.hasKey(HologramDisplaySnapshot.QUANTUM_SIDES)) {
            work.roots.add(HologramDisplaySnapshot.QUANTUM_SIDES);
            if (!HologramBlueprintNbt.registeredQuantumTile(data) || !HologramDisplaySnapshot.validQuantumSides(data)) {
                work.issue(
                    "unreviewed_tile_field",
                    "blocker",
                    HologramDisplaySnapshot.QUANTUM_SIDES,
                    "Quantum display mask requires the registered node identity and an integer from 0 to 63");
                data.removeTag(HologramDisplaySnapshot.QUANTUM_SIDES);
            }
        }
        Set<String> known = new HashSet<>(
            Arrays.asList(
                "id",
                "x",
                "y",
                "z",
                "color",
                "partContainer",
                HologramDisplaySnapshot.QUANTUM_SIDES,
                HologramNetworkBindings.GROUP,
                HologramNetworkBindings.ROLE,
                HologramNetworkBindings.HINT,
                HologramNetworkBindings.ORIGINAL,
                HologramNetworkBindings.MODE,
                UNRESOLVED));
        for (Object raw : new HashSet<Object>(data.func_150296_c())) {
            String key = (String) raw;
            work.roots.add(key);
            if (!known.contains(key)) {
                work.issue(
                    "unreviewed_tile_field",
                    "blocker",
                    key,
                    "GTSWN node field is outside its registered persistence schema and is omitted");
                data.removeTag(key);
            }
        }
    }

    /** GTSWN 1.8.66 QuantumMigrationJournal sequences start at zero and are stored as native longs. */
    static boolean validQuantumMigrationCursor(NBTTagCompound data) {
        return data != null && data.hasKey("lastAppliedMigration", 4) && data.getLong("lastAppliedMigration") >= 0L;
    }

    public static Analysis analyze(Block block, int metadata, NBTTagCompound payload) throws IOException {
        Work work = new Work(payload);
        if (block == null) throw new IOException("Missing AE block identity");
        if (block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) {
            if (supports(block, work.data))
                HologramAeBlueprintSchemas.previewFields(work, work.data, new String[0], false);
            multipart(work);
            return new Analysis(work);
        }
        if (!supports(block)) {
            work.supported = false;
            return new Analysis(work);
        }
        HologramAeBlueprintSchemas.previewFields(work, work.data, new String[0], false);
        if (HologramNetworkBindings.node(block)) {
            quantumNode(work, block, metadata);
            return new Analysis(work);
        }
        if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) {
            cable(work, work.data, new String[0]);
            return new Analysis(work);
        }
        Class<? extends TileEntity> tileClass = block instanceof AEBaseTileBlock
            ? ((AEBaseTileBlock) block).getTileEntityClass()
            : null;
        if (tileClass != null && !SCHEMAS.containsKey(tileClass.getName())) {
            unsupported(work, tileClass.getName());
            return new Analysis(work);
        }
        TileEntity fresh = tileClass == null ? null : block.createTileEntity(null, metadata);
        if (fresh == null) {
            // Static AE blocks have no configuration or inventories.
            NBTTagCompound safe = shell(work.data);
            for (Object key : work.data.func_150296_c()) {
                if (!safe.hasKey((String) key)) work.issue(
                    "static_block_data",
                    "blocker",
                    (String) key,
                    "Static AE block cannot restore this device data; only its block shell is retained");
            }
            work.data = shell(work.data);
            return new Analysis(work);
        }
        Schema schema = SCHEMAS.get(
            fresh.getClass()
                .getName());
        if (schema == null) {
            unsupported(
                work,
                fresh.getClass()
                    .getName());
            return new Analysis(work);
        }
        cleanHost(work, work.data, fresh, new String[0]);
        boolean interfaceTile = fresh.getClass()
            .getName()
            .equals(TILE + "misc.TileInterface");
        if (interfaceTile) HologramAeBlueprintSchemas.importInterfaceStorage(work);
        for (Inventory inventory : schema.inventories) inventory(work, work.data, inventory, new String[0], fresh);
        if (interfaceTile) HologramAeBlueprintSchemas.mirrorInterfaceStorage(work.data);
        if (fresh.getClass()
            .getName()
            .equals(TILE + "misc.TileAdvancedInscriber"))
            HologramAeBlueprintSchemas.single(work, work.data, "output", "readonly", 64, "any", new String[0], fresh);
        HologramAeBlueprintSchemas.guardTileRoots(work, fresh, schema);
        return new Analysis(work);
    }

    public static Analysis updateSlot(Block block, int metadata, NBTTagCompound payload, String slotId,
        ItemStack markedOrNull) throws IOException {
        Analysis before = analyze(block, metadata, payload);
        Slot selected = null;
        for (Slot slot : before.slots) if (slot.id.equals(slotId)) {
            selected = slot;
            break;
        }
        if (selected == null) throw new IOException("Unknown AE blueprint slot");
        if (!selected.issueCode.isEmpty()) {
            if (markedOrNull != null) throw new IOException("Review tombstones only accept explicit CLEAR");
            return analyze(
                block,
                metadata,
                resolveDiscardedIssue(before.payload(), selected.issueCode, selected.issuePath));
        }
        ItemStack marker = markedOrNull == null ? null : markedOrNull.copy();
        if (marker != null) {
            if (!selected.enabled || marker.getItem() == null
                || marker.stackSize < 1
                || marker.stackSize > selected.limit) throw new IOException("AE marker exceeds slot constraints");
            bounded(marker.hasTagCompound() ? marker.getTagCompound() : new NBTTagCompound(), 0, new int[] { 0 });
            final int[] written = { 0 };
            OutputStream limited = new OutputStream() {

                @Override
                public void write(int value) throws IOException {
                    if (++written[0] > MAX_MARK_BYTES) throw new IOException("AE marker expanded NBT exceeds budget");
                }

                @Override
                public void write(byte[] values, int offset, int count) throws IOException {
                    if (count > MAX_MARK_BYTES - written[0])
                        throw new IOException("AE marker expanded NBT exceeds budget");
                    written[0] += count;
                }
            };
            NBTTagCompound boundedStack = new NBTTagCompound();
            marker.writeToNBT(boundedStack);
            CompressedStreamTools.write(boundedStack, new DataOutputStream(limited));
            validate(selected, marker, before);
        }
        NBTTagCompound changed = before.payload();
        removeResolvedSlotMarkers(changed, selected.id);
        NBTTagCompound parent = changed;
        for (int i = 0; i < selected.path.length - 1; i++) {
            String segment = selected.path[i];
            if (segment.startsWith("@part=")) {
                parent = parent.getTagList("parts", 10)
                    .getCompoundTagAt(Integer.parseInt(segment.substring(6)));
            } else {
                if (!parent.hasKey(segment, 10)) parent.setTag(segment, new NBTTagCompound());
                parent = parent.getCompoundTag(segment);
            }
        }
        String key = selected.path[selected.path.length - 1];
        if (marker == null) {
            parent.removeTag(key);
            if (key.matches("def:[0-6]")) {
                parent.removeTag("extra:" + key.substring(4));
                clearPartDefinitionMarkers(changed, selected);
            }
        } else {
            NBTTagCompound tag = new NBTTagCompound();
            if (selected.kind.equals("ghost")) Platform.writeStackNBT(Platform.convertStackPacket(marker), tag, true);
            else marker.writeToNBT(tag);
            parent.setTag(key, tag);
        }
        if (block instanceof AEBaseTileBlock && ((AEBaseTileBlock) block).getTileEntityClass() != null
            && ((AEBaseTileBlock) block).getTileEntityClass()
                .getName()
                .equals(TILE + "misc.TileInterface")
            && selected.path.length >= 2
            && selected.path[0].equals("storage")
            && selected.path[1].matches("#[0-8]")) HologramAeBlueprintSchemas.mirrorInterfaceStorage(changed);
        return analyze(block, metadata, changed);
    }

    /** Only called with the project's read-only detached World, before any native ready/validate callback. */
    public static TileEntity createNative(Block block, int metadata, NBTTagCompound payload, World detached)
        throws IOException {
        if (!(detached instanceof HologramResourceWorld)) throw new IOException("AE factory requires detached world");
        Analysis analysis = analyze(block, metadata, payload);
        if (!analysis.supported || analysis.blocking) throw new IOException("AE blueprint has unresolved problems");
        return loadDetached(block, metadata, HologramBlueprintNbt.nativePayload(analysis.payload()), detached, true);
    }

    /** Trusted world recovery journal only. Preserve original contents/identity; this is never a blueprint load. */
    static TileEntity createRollbackNative(Block block, int metadata, NBTTagCompound journal, World detached)
        throws IOException {
        if (!(detached instanceof HologramResourceWorld) || !new NativeAdapter().supports(block))
            throw new IOException("Unreviewed AE recovery factory");
        return loadDetached(block, metadata, HologramBlueprintNbt.nativePayload(journal), detached, false);
    }

    private static TileEntity loadDetached(Block block, int metadata, NBTTagCompound tag, World detached,
        boolean resetIdentity) throws IOException {
        TileEntity tile = block.createTileEntity(detached, metadata);
        if (!(tile instanceof AEBaseTile) && !HologramNetworkBindings.node(block))
            throw new IOException("AE native tile factory is unavailable");
        tile.setWorldObj(detached);
        tile.xCoord = tag.getInteger("x");
        tile.yCoord = tag.getInteger("y");
        tile.zCoord = tag.getInteger("z");
        ((HologramResourceWorld) detached).tile = tile;
        // AEBaseInvTile restores with setters. Suppress native inventory change callbacks on this disposable
        // inventory so Condenser inputs are not consumed and Chest buffers are not injected while loading.
        AppEngInternalInventory loadingInventory = null;
        if (tile instanceof AEBaseInvTile
            && ((AEBaseInvTile) tile).getInternalInventory() instanceof AppEngInternalInventory) {
            loadingInventory = (AppEngInternalInventory) ((AEBaseInvTile) tile).getInternalInventory();
            loadingInventory.setTileEntity(null);
        }
        try {
            // Private-blueprint provenance is consumed only by the verified binding adapter, never GTSWN's reader.
            tag.removeTag(HologramNetworkBindings.ORIGINAL);
            tag.removeTag(HologramNetworkBindings.MODE);
            tile.readFromNBT(tag);
        } finally {
            if (loadingInventory != null) loadingInventory.setTileEntity((IAEAppEngInventory) tile);
        }
        if (block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus")) {
            try {
                for (int side = 0; side < 7; side++) {
                    ForgeDirection direction = ForgeDirection.getOrientation(side);
                    if (tag.hasKey("def:" + side, 10) && tile.getClass()
                        .getMethod("getPart", ForgeDirection.class)
                        .invoke(tile, direction) == null)
                        throw new IOException("Native AE host rejected a retained part");
                    if (side < 6 && tag.hasKey("facade:" + side, 10)) {
                        Object facades = tile.getClass()
                            .getMethod("getFacadeContainer")
                            .invoke(tile);
                        if (facades.getClass()
                            .getMethod("getFacade", ForgeDirection.class)
                            .invoke(facades, direction) == null)
                            throw new IOException("Native AE host rejected a retained facade");
                    }
                }
            } catch (ReflectiveOperationException invalid) {
                throw new IOException("AE CableBus persistence contract changed", invalid);
            }
        }
        if (resetIdentity) prepareActor(tile, null);
        return tile;
    }

    /** readFromNBT(null) prevents missing g from resolving to the world's real GridStorage ID 0. */
    public static void prepareActor(TileEntity tile, EntityPlayerMP actor) throws IOException {
        if (tile instanceof IGridProxyable) resetProxy(((IGridProxyable) tile).getProxy(), actor);
        // BlockCableBus.setupTile also produces official interface-layer subclasses (TESR inherits this base).
        if (tile instanceof appeng.tile.networking.TileCableBus || HologramNetworkBindings.NODE_TILE.equals(
            tile.getClass()
                .getName())) {
            for (ForgeDirection side : ForgeDirection.values()) {
                try {
                    Object part = tile.getClass()
                        .getMethod("getPart", ForgeDirection.class)
                        .invoke(tile, side);
                    if (part != null) preparePart(part, actor);
                } catch (ReflectiveOperationException failure) {
                    throw new IOException("AE part owner reset failed", failure);
                }
            }
        }
        Class<?> multipartTile;
        try {
            multipartTile = Class
                .forName("codechicken.multipart.TileMultipart", false, HologramAeBlueprint.class.getClassLoader());
        } catch (ClassNotFoundException absentOptionalMultipart) {
            return;
        }
        // MultipartGenerator also creates tiles outside the base class's package.
        if (multipartTile.isInstance(tile)) {
            try {
                List<?> parts = (List<?>) tile.getClass()
                    .getMethod("jPartList")
                    .invoke(tile);
                Class<?> cableBusPart = Class.forName("appeng.fmp.CableBusPart");
                for (Object part : parts) {
                    // AE's official PartRegistry factory produces interface-layer subclasses of this base.
                    if (cableBusPart.isInstance(part) && "ae2_cablebus".equals(
                        part.getClass()
                            .getMethod("getType")
                            .invoke(part))) {
                        Object bus = part.getClass()
                            .getMethod("getCableBus")
                            .invoke(part);
                        for (ForgeDirection side : ForgeDirection.values()) {
                            Object nativePart = bus.getClass()
                                .getMethod("getPart", ForgeDirection.class)
                                .invoke(bus, side);
                            if (nativePart != null) preparePart(nativePart, actor);
                        }
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new IOException("AE multipart owner reset failed", failure);
            }
        }
    }

    private static void resetProxy(AENetworkProxy proxy, EntityPlayerMP actor) throws IOException {
        if (proxy == null) throw new IOException("Missing AE network proxy");
        try {
            Field ready = AENetworkProxy.class.getDeclaredField("isReady");
            ready.setAccessible(true);
            Field node = AENetworkProxy.class.getDeclaredField("node");
            node.setAccessible(true);
            if (ready.getBoolean(proxy) || node.get(proxy) != null)
                throw new IOException("AE proxy became ready before owner reset");
            proxy.readFromNBT(null);
            proxy.setOwner(actor);
        } catch (ReflectiveOperationException failure) {
            throw new IOException("AE proxy lifecycle schema changed", failure);
        }
    }

    private static void preparePart(Object part, EntityPlayerMP actor) throws IOException {
        if (part instanceof IGridProxyable) resetProxy(((IGridProxyable) part).getProxy(), actor);
        String outerType = outerProxyClass(part);
        if (outerType != null) {
            try {
                Class<?> type = Class.forName(outerType);
                Field outer = type.getDeclaredField("outerProxy");
                outer.setAccessible(true);
                if (outer.getType() != AENetworkProxy.class) throw new IOException("AE outer proxy type changed");
                resetProxy((AENetworkProxy) outer.get(part), actor);
            } catch (ReflectiveOperationException failure) {
                throw new IOException("AE secondary proxy reset failed: " + outerType, failure);
            }
        }
    }

    /** Exact classes/declaring fields in AE2 1080. No generic proxy-field scan. */
    private static String outerProxyClass(Object part) {
        String type = part.getClass()
            .getName();
        if (type.equals("appeng.parts.misc.PartToggleBus") || type.equals("appeng.parts.misc.PartInvertedToggleBus"))
            return "appeng.parts.misc.PartToggleBus";
        if (type.equals("appeng.parts.p2p.PartP2PTunnelME")) return type;
        if (type.equals("appeng.parts.networking.PartQuartzFiber")) return type;
        return null;
    }

    private static NBTTagCompound shell(NBTTagCompound source) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : Arrays
            .asList("id", "x", "y", "z", "orientation_forward", "orientation_up", "customName", "paintedColor"))
            if (source.hasKey(key)) result.setTag(
                key,
                source.getTag(key)
                    .copy());
        return result;
    }

    private static void unsupported(Work work, String type) {
        work.supported = false;
        work.data = shell(work.data);
        work.issue(
            "unsupported_host",
            "blocker",
            "",
            "Unreviewed AE host " + type + ": only the block shell is retained; resolve before saving");
    }

    private static String[] path(String[] prefix, String... suffix) {
        String[] result = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private static String displayPath(String[] path) {
        return String.join("/", path);
    }

    private static List<Issue> readUnresolved(NBTTagCompound payload) throws IOException {
        List<Issue> result = new ArrayList<>();
        if (!payload.hasKey(UNRESOLVED)) return result;
        if (!payload.hasKey(UNRESOLVED, 9)) throw new IOException("Invalid AE unresolved issue marker");
        NBTTagList markers = (NBTTagList) payload.getTag(UNRESOLVED);
        if (markers.tagCount() > MAX_UNRESOLVED || markers.tagCount() > 0 && markers.func_150303_d() != 10)
            throw new IOException("AE unresolved issue budget/type exceeded");
        for (int i = 0; i < markers.tagCount(); i++) {
            NBTTagCompound entry = markers.getCompoundTagAt(i);
            if (!entry.hasKey("code", 8) || !entry.hasKey("path", 8) || !entry.hasKey("message", 8))
                throw new IOException("Invalid AE unresolved issue entry");
            String code = entry.getString("code"), path = entry.getString("path"), message = entry.getString("message");
            if (!LOST_BLOCKERS.contains(code) || path.length() > 512
                || message.length() > 256
                || path.indexOf('\u0000') >= 0
                || message.indexOf('\u0000') >= 0) throw new IOException("Unrecognized AE unresolved issue entry");
            result.add(new Issue(code, "blocker", path, message));
        }
        return result;
    }

    private static void writeUnresolved(Work work) throws IOException {
        NBTTagList markers = new NBTTagList();
        Set<String> seen = new HashSet<>();
        for (Issue issue : work.issues) {
            if (!issue.severity.equals("blocker") || !LOST_BLOCKERS.contains(issue.code)) continue;
            if (!seen.add(issue.code + "\u0000" + issue.path)) continue;
            if (markers.tagCount() >= MAX_UNRESOLVED || issue.path.length() > 512 || issue.message.length() > 256)
                throw new IOException("Complete AE unresolved issue report exceeds budget");
            NBTTagCompound marker = new NBTTagCompound();
            marker.setString("code", issue.code);
            marker.setString("path", issue.path);
            marker.setString("message", issue.message);
            markers.appendTag(marker);
            if (!PERMANENT_BLOCKERS.contains(issue.code)) {
                boolean directSlot = false;
                for (Slot slot : work.slots) if (issue.path.equals(slot.id) || issue.path.startsWith(slot.id + "/")) {
                    directSlot = true;
                    break;
                }
                if (!directSlot) {
                    if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE review slot budget exceeded");
                    String id = reviewId(issue.code, issue.path);
                    work.slots.add(
                        new Slot(
                            id,
                            "readonly",
                            0,
                            "discardOmitted",
                            null,
                            new String[0],
                            -1,
                            null,
                            false,
                            issue.code,
                            issue.path));
                }
            }
        }
        if (markers.tagCount() == 0) work.data.removeTag(UNRESOLVED);
        else work.data.setTag(UNRESOLVED, markers);
    }

    private static String reviewId(String code, String path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((code + "\u0000" + path).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder("review/");
            for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hash.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    /** Only acknowledge an already omitted, audited loss entry; this never mutates arbitrary source NBT paths. */
    public static NBTTagCompound resolveDiscardedIssue(NBTTagCompound payload, String code, String issuePath)
        throws IOException {
        if (!LOST_BLOCKERS.contains(code) || PERMANENT_BLOCKERS.contains(code))
            throw new IOException("This AE problem requires replacing/deleting the real blueprint slot or cell");
        NBTTagCompound result = (NBTTagCompound) payload.copy();
        NBTTagList kept = new NBTTagList();
        boolean found = false;
        for (Issue issue : readUnresolved(result)) {
            if (issue.code.equals(code) && issue.path.equals(issuePath)) {
                found = true;
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("code", issue.code);
            entry.setString("path", issue.path);
            entry.setString("message", issue.message);
            kept.appendTag(entry);
        }
        if (!found) throw new IOException("AE discarded-issue review is stale");
        if (kept.tagCount() == 0) result.removeTag(UNRESOLVED);
        else result.setTag(UNRESOLVED, kept);
        return result;
    }

    private static void removeResolvedSlotMarkers(NBTTagCompound payload, String slotId) throws IOException {
        NBTTagList kept = new NBTTagList();
        for (Issue issue : readUnresolved(payload)) {
            if (issue.path.equals(slotId) || issue.path.startsWith(slotId + "/")) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("code", issue.code);
            entry.setString("path", issue.path);
            entry.setString("message", issue.message);
            kept.appendTag(entry);
        }
        if (kept.tagCount() == 0) payload.removeTag(UNRESOLVED);
        else payload.setTag(UNRESOLVED, kept);
    }

    /** Invoked only after CLEAR of a server-issued native definition slot; no caller-supplied NBT path. */
    private static void clearPartDefinitionMarkers(NBTTagCompound payload, Slot selected) throws IOException {
        String extra = partExtraPath(selected);
        if (extra != null) removeResolvedSlotMarkers(payload, extra);
    }

    private static String partExtraPath(Slot selected) {
        if (!selected.kind.equals("readonly") || !selected.validator.equals("part")
            || selected.path.length < 1
            || selected.path.length > 2) return null;
        if (selected.path.length == 2 && !selected.path[0].matches("@part=[0-9]+")) return null;
        String key = selected.path[selected.path.length - 1];
        if (!key.matches("def:[0-6]")) return null;
        String[] extra = selected.path.clone();
        extra[extra.length - 1] = "extra:" + key.substring(4);
        return displayPath(extra);
    }

    public static Set<String> resolvedIssuePaths(String slotId, Analysis before, Analysis after) {
        Set<String> paths = new LinkedHashSet<>();
        Slot selected = null;
        for (Slot slot : before.slots) if (slot.id.equals(slotId)) {
            selected = slot;
            break;
        }
        if (selected == null) return Collections.emptySet();
        if (!selected.issueCode.isEmpty()) {
            paths.add(selected.issuePath);
            return Collections.unmodifiableSet(paths);
        }
        String upgradeHost = null;
        int upgrades = slotId.lastIndexOf("upgrades/");
        if (selected.validator.equals("upgrade") && upgrades >= 0) upgradeHost = slotId.substring(0, upgrades);
        String deletedPartExtra = partExtraPath(selected);
        if (deletedPartExtra != null) for (Slot current : after.slots) if (current.id.equals(slotId)) {
            deletedPartExtra = null;
            break;
        }
        for (Issue issue : before.issues) {
            boolean direct = issue.path.equals(slotId) || issue.path.startsWith(slotId + "/");
            boolean deletedPart = deletedPartExtra != null
                && (issue.path.equals(deletedPartExtra) || issue.path.startsWith(deletedPartExtra + "/"));
            boolean capacity = upgradeHost != null && issue.code.equals("hidden_pattern")
                && issue.path.startsWith(upgradeHost + "patterns/");
            if (!direct && !deletedPart && !capacity) continue;
            boolean remains = false;
            for (Issue current : after.issues) if (current.code.equals(issue.code) && current.path.equals(issue.path)) {
                remains = true;
                break;
            }
            if (!remains) paths.add(issue.path);
        }
        return Collections.unmodifiableSet(paths);
    }

    /** Rotate only documented side segments in report paths; no pattern/item NBT is traversed. */
    public static String rotateIssuePath(String path, boolean vertical) {
        String[] segments = path.split("/", -1);
        int index = segments.length > 0 && segments[0].startsWith("@part=") ? 1 : 0;
        if (index < segments.length && segments[index].matches("(?:def|extra|facade):[0-5]")) {
            String segment = segments[index];
            int side = segment.charAt(segment.length() - 1) - '0';
            segments[index] = segment.substring(0, segment.length() - 1)
                + HologramVirtualTransform.front(side, vertical);
        }
        return String.join("/", segments);
    }

    static void rotateUnresolved(NBTTagCompound payload, boolean vertical) throws IOException {
        if (payload == null || !payload.hasKey(UNRESOLVED)) return;
        List<Issue> issues = readUnresolved(payload);
        NBTTagList rotated = new NBTTagList();
        for (Issue issue : issues) {
            NBTTagCompound marker = new NBTTagCompound();
            marker.setString("code", issue.code);
            marker.setString("path", rotateIssuePath(issue.path, vertical));
            marker.setString("message", issue.message);
            rotated.appendTag(marker);
        }
        payload.setTag(UNRESOLVED, rotated);
    }

    private static void cleanHost(Work work, NBTTagCompound data, Object host, String[] prefix) throws IOException {
        // Implemented below using exact host persistence paths; never traverse item recipe NBT.
        HologramAeBlueprintSchemas.cleanHost(work, data, host, prefix);
    }

    private static void inventory(Work work, NBTTagCompound data, Inventory inventory, String[] prefix, Object host)
        throws IOException {
        HologramAeBlueprintSchemas.inventory(work, data, inventory, prefix, host);
    }

    private static void cable(Work work, NBTTagCompound data, String[] prefix) throws IOException {
        HologramAeBlueprintSchemas.cable(work, data, prefix);
    }

    private static void multipart(Work work) throws IOException {
        HologramAeBlueprintSchemas.multipart(work);
    }

    private static void validate(Slot slot, ItemStack marker, Analysis before) throws IOException {
        HologramAeBlueprintSchemas.validate(slot, marker, before);
    }

    private static void bounded(NBTBase tag, int depth, int[] elements) throws IOException {
        if (depth > 16 || ++elements[0] > 2048) throw new IOException("AE marker NBT exceeds preview budget");
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (Object key : compound.func_150296_c()) bounded(compound.getTag((String) key), depth + 1, elements);
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            try {
                if (listContents == null) {
                    Field selected = null;
                    for (Field field : NBTTagList.class.getDeclaredFields()) {
                        if (!List.class.isAssignableFrom(field.getType())) continue;
                        if (selected != null) throw new IOException("Ambiguous native NBT list schema");
                        selected = field;
                    }
                    if (selected == null) throw new IOException("Native NBT list schema is unavailable");
                    selected.setAccessible(true);
                    listContents = selected;
                }
                for (Object value : (List<?>) listContents.get(list)) bounded((NBTBase) value, depth + 1, elements);
            } catch (IllegalAccessException invalid) {
                throw new IOException("Native NBT list schema is inaccessible", invalid);
            }
        }
    }

    /* Exact persistence schemas are nested to keep mutation authority and slot paths private. */
    private static final class HologramAeBlueprintSchemas {

        static void guardTileRoots(Work work, Object host, Schema schema) {
            Set<String> known = new HashSet<>(
                Arrays.asList(
                    "id",
                    "x",
                    "y",
                    "z",
                    "customName",
                    "orientation_forward",
                    "orientation_up",
                    "paintedColor",
                    "proxy",
                    HologramNetworkBindings.GROUP,
                    HologramNetworkBindings.ROLE,
                    HologramNetworkBindings.HINT,
                    HologramNetworkBindings.CAPTURE_ANCHOR,
                    "internalCurrentPower"));
            for (Settings setting : Settings.values()) known.add(setting.name());
            for (Inventory inventory : schema.inventories) known.add(inventory.root);
            String type = host.getClass()
                .getName();
            if (type.equals(TILE + "misc.TileInterface"))
                known.addAll(Arrays.asList("priority", "pointAt", "waitingToSend", "inv"));
            if (type.equals(TILE + "storage.TileDrive") || type.equals(TILE + "storage.TileChest"))
                known.add("priority");
            if (type.equals(TILE + "storage.TileChest")) known.add("terminalSettings");
            if (type.equals(TILE + "misc.TileAdvancedInscriber"))
                known.addAll(Arrays.asList("topLocked", "bottomLocked", "output"));
            if (type.equals(TILE + "networking.TileWirelessHub")
                || type.equals(TILE + "networking.TileWirelessConnector")) known.add("Color");
            if (type.equals(TILE + "networking.TileController")) known.add("controllerAnimation");
            if (type.startsWith(TILE + "crafting.TileCrafting")) known.add("core");
            for (Object raw : new HashSet<Object>(work.data.func_150296_c())) {
                String key = (String) raw;
                work.roots.add(key);
                if (!known.contains(key)) {
                    work.issue(
                        "unreviewed_tile_field",
                        "blocker",
                        key,
                        "AE field is outside this host's audited persistence schema and is omitted; resolve before saving");
                    work.data.removeTag(key);
                }
            }
        }

        /** TileInterface's inherited inv.itemN and Duality storage.#N are the same nine physical slots. */
        static void importInterfaceStorage(Work work) throws IOException {
            work.roots.add("inv");
            NBTTagCompound data = work.data;
            if (!data.hasKey("inv")) return;
            if (!data.hasKey("inv", 10)) {
                work.issue(
                    "unreviewed_tile_field",
                    "blocker",
                    "inv",
                    "Malformed inherited interface storage alias is omitted; explicit review is required");
                data.removeTag("inv");
                return;
            }
            NBTTagCompound alias = data.getCompoundTag("inv");
            if (data.hasKey("storage") && !data.hasKey("storage", 10)) {
                work.issue(
                    "unknown_inventory_slot",
                    "blocker",
                    "storage",
                    "Malformed canonical interface storage is omitted; its valid inherited slots are retained only after explicit review");
                data.removeTag("storage");
            }
            boolean canonicalPresent = data.hasKey("storage", 10);
            NBTTagCompound storage = data.getCompoundTag("storage");
            for (Object raw : alias.func_150296_c()) {
                String key = (String) raw;
                if (!key.matches("item[0-8]") || !alias.hasKey(key, 10)) work.issue(
                    "unknown_inventory_slot",
                    "blocker",
                    "inv/" + key,
                    "Unrecognized inherited interface storage entry is omitted; explicitly review this physical loss");
            }
            for (int index = 0; index < 9; index++) {
                String aliasKey = "item" + index, key = "#" + index;
                NBTTagCompound inherited = alias.getCompoundTag(aliasKey);
                if (!canonicalPresent) {
                    if (!inherited.hasNoTags()) storage.setTag(key, inherited.copy());
                } else {
                    boolean same;
                    try {
                        same = HologramBlueprintNbt.nativePayload(inherited)
                            .equals(HologramBlueprintNbt.nativePayload(storage.getCompoundTag(key)));
                    } catch (IOException unresolved) {
                        same = false;
                    }
                    if (!same) work.issue(
                        "interface_storage_alias_conflict",
                        "blocker",
                        "storage/" + key,
                        "Inherited inv/item" + index
                            + " disagrees with the same storage slot; its alternate physical marker is omitted. Clear or replace this storage marker explicitly");
                }
            }
            if (!canonicalPresent && !storage.hasNoTags()) data.setTag("storage", storage);
            // Never retain a second unsafe/unpaid inventory shadow. Canonical sanitized slots are mirrored later.
            data.removeTag("inv");
        }

        static void mirrorInterfaceStorage(NBTTagCompound data) {
            NBTTagCompound storage = data.getCompoundTag("storage"), alias = new NBTTagCompound();
            for (int index = 0; index < 9; index++) alias.setTag(
                "item" + index,
                storage.getCompoundTag("#" + index)
                    .copy());
            // Both native read-event orders now restore exactly the same sanitized/paid physical inventory.
            data.setTag("inv", alias);
        }

        static void cleanHost(Work work, NBTTagCompound data, Object host, String[] prefix) throws IOException {
            String type = host.getClass()
                .getName();
            if (prefix.length == 0) data.removeTag("GTSWN_IncorporationIdentity");
            if (prefix.length == 0 && type.equals("appeng.tile.networking.TileController")
                && data.hasKey("GTSWN_ControllerIdentity")) {
                root(work, prefix, "GTSWN_ControllerIdentity");
                if (!HologramNetworkBindings.validOwnerField(data, "GTSWN_ControllerIdentity", true)) work.issue(
                    "unreviewed_tile_field",
                    "blocker",
                    "GTSWN_ControllerIdentity",
                    "量子控制器世代标识必须是原生 UUID 字符串，请核验来源数据");
                else work
                    .issue("network_identity", "warning", "GTSWN_ControllerIdentity", "已移除来源控制器世代标识，新控制器将生成自己的原生身份");
                data.removeTag("GTSWN_ControllerIdentity");
            }
            if (host instanceof IGridProxyable) {
                // GTSWN's exact persistence adapter writes "proxy". Its lazy getProxy() requires
                // a live world, so schema inspection must remove that key without constructing
                // a proxy or making this disposable factory tile ready on any network.
                String proxyKey = HologramNetworkBindings.NODE_TILE.equals(type) ? "proxy"
                    : HologramBlueprintOwnership.nodeKey(((IGridProxyable) host).getProxy());
                remove(
                    work,
                    data,
                    proxyKey,
                    prefix,
                    "network_identity",
                    "AE owner/security/grid identity is reset for the placing player");
            }
            if (outerProxyClass(host) != null)
                remove(work, data, "outer", prefix, "network_identity", "AE secondary network identity is reset");
            if (prefix.length == 0) {
                // Target coordinates are rebound by installation; never inherit the captured world position.
                data.setInteger("x", 0);
                data.setInteger("y", 64);
                data.setInteger("z", 0);
                work.roots.addAll(Arrays.asList("x", "y", "z"));
            }
            if (data.hasKey("internalCurrentPower")) {
                double power = data.getDouble("internalCurrentPower");
                if (!data.hasKey("internalCurrentPower", 6) || !Double.isFinite(power) || power < 0)
                    throw new IOException("Invalid native AE power cache");
                root(work, prefix, "internalCurrentPower");
            }
            if (type.equals(TILE + "misc.TileCondenser"))
                zero(work, data, "storedPower", prefix, "energy_reset", "Condenser stored power is omitted");
            if (type.equals(TILE + "misc.TileVibrationChamber")) {
                zero(work, data, "burnTime", prefix, "burn_reset", "Remaining fuel burn state is omitted");
                zero(work, data, "maxBurnTime", prefix, "burn_reset", "Fuel burn cache is reset");
                remove(work, data, "burnSpeed", prefix, "burn_reset", "Fuel burn cache is reset");
            }
            if (type.equals(TILE + "networking.TileWireless"))
                remove(work, data, "serial", prefix, "wireless_identity", "Wireless terminal serial is regenerated");
            if (type.equals(TILE + "storage.TileChest")
                || host instanceof appeng.parts.reporting.AbstractPartTerminal) {
                remove(
                    work,
                    data,
                    "terminalSettings",
                    prefix,
                    "player_settings",
                    "Per-player terminal settings and UUIDs are omitted");
                remove(work, data, "pins", prefix, "player_pins", "Per-player terminal pins and UUIDs are omitted");
            }
            if (type.equals("appeng.parts.misc.PartStorageBus")) remove(
                work,
                data,
                "filterCache",
                prefix,
                "runtime_reset",
                "Storage bus runtime filter cache is regenerated");
            if (type.equals("appeng.parts.automation.PartExportBus")) {
                if (data.hasKey("nextSlot")) {
                    if (!data.hasKey("nextSlot", 3) || data.getInteger("nextSlot") < 0
                        || data.getInteger("nextSlot") >= 9)
                        work.issue(
                            "unreviewed_part_field",
                            "blocker",
                            displayPath(path(prefix, "nextSlot")),
                            "Malformed native Export Bus scheduling cursor is omitted; explicit review is required");
                    remove(
                        work,
                        data,
                        "nextSlot",
                        prefix,
                        "runtime_reset",
                        "Export Bus scheduling cursor is regenerated");
                }
                for (int slot = 0; slot < 9; slot++) {
                    String key = "links-" + slot;
                    if (!data.hasKey(key)) continue;
                    if (!data.hasKey(key, 10)) work.issue(
                        "unreviewed_part_field",
                        "blocker",
                        displayPath(path(prefix, key)),
                        "Malformed native Export Bus crafting link is omitted; explicit review is required");
                    remove(work, data, key, prefix, "crafting_link", "Export Bus active crafting link is omitted");
                }
            }
            if (reviewedReporting(type)) {
                if (data.hasKey("spin")
                    && (!data.hasKey("spin", 1) || data.getByte("spin") < 0 || data.getByte("spin") > 3)) {
                    work.issue(
                        "unreviewed_part_field",
                        "blocker",
                        displayPath(path(prefix, "spin")),
                        "Native reporting part rotation must be a byte from 0 through 3; malformed rotation is omitted");
                    data.removeTag("spin");
                }
                if (data.hasKey("opacity")) {
                    float opacity = data.getFloat("opacity");
                    if (!data.hasKey("opacity", 5) || Float.isNaN(opacity) || Float.isInfinite(opacity)) work.issue(
                        "unreviewed_part_field",
                        "blocker",
                        displayPath(path(prefix, "opacity")),
                        "Native reporting part light cache must be a finite float; malformed cache is omitted");
                    remove(
                        work,
                        data,
                        "opacity",
                        prefix,
                        "runtime_reset",
                        "Reporting part neighboring light cache is regenerated");
                }
            }
            if (type.equals("appeng.parts.p2p.PartP2PLight"))
                remove(work, data, "lastValue", prefix, "runtime_reset", "P2P last light signal is reset");
            if (type.equals("appeng.parts.automation.PartLevelEmitter")) {
                remove(
                    work,
                    data,
                    "lastReportedValue",
                    prefix,
                    "runtime_reset",
                    "Level emitter cached network value is reset");
                remove(
                    work,
                    data,
                    "prevState",
                    prefix,
                    "runtime_reset",
                    "Level emitter cached redstone output is reset");
            }
            if (type.equals(TILE + "networking.TileWirelessHub")
                || type.equals(TILE + "networking.TileWirelessConnector")) {
                remove(
                    work,
                    data,
                    "connectedTargets",
                    prefix,
                    "wireless_connections",
                    "Wireless target coordinates are omitted; reconnect the new devices");
                if (data.hasKey("Color") && (data.getInteger("Color") < 0
                    || data.getInteger("Color") >= appeng.api.util.AEColor.values().length))
                    throw new IOException("Invalid AE wireless color");
            }
            if (type.equals(TILE + "spatial.TileSpatialIOPort"))
                remove(work, data, "lastRedstoneState", prefix, "runtime_reset", "Spatial IO redstone cache is reset");
            if (type.equals(TILE + "misc.TileAdvancedInscriber")) remove(
                work,
                data,
                "progress",
                prefix,
                "runtime_reset",
                "Advanced inscriber progress is reset; paid pending output is retained");
            if (type.equals(TILE + "storage.TileIOPort")) {
                remove(work, data, "moveQueue", prefix, "runtime_reset", "IO transfer queue is reset");
                remove(work, data, "lastRedstoneState", prefix, "runtime_reset", "IO redstone cache is reset");
                remove(work, data, "pendingRedstonePulse", prefix, "runtime_reset", "IO pending pulse is reset");
            }
            if (type.equals(TILE + "crafting.TileMolecularAssembler")) {
                remove(
                    work,
                    data,
                    "myPlan",
                    prefix,
                    "runtime_reset",
                    "Pushed crafting plan is reset; physical pattern slot is retained");
                remove(work, data, "pushDirection", prefix, "runtime_reset", "Pushed plan direction is reset");
            }
            if (host instanceof IInterfaceHost) {
                for (String key : new ArrayList<String>(data.func_150296_c())) {
                    if (key.matches("links-[0-9]+"))
                        remove(work, data, key, prefix, "crafting_link", "Active crafting link is omitted");
                }
                remove(work, data, "unlockEvent", prefix, "runtime_reset", "Interface crafting lock runtime is reset");
                remove(work, data, "unlockStacks", prefix, "runtime_reset", "Interface result lock samples are reset");
                waiting(work, data, prefix);
            }
            if (type.startsWith("appeng.parts.p2p.")) {
                if (data.hasKey("freq") && data.getLong("freq") != 0) {
                    work.issue(
                        "p2p_rebind",
                        "warning",
                        displayPath(path(prefix, "freq")),
                        "P2P pairing is cleared; bind the new tunnels again");
                }
                data.setLong("freq", 0);
                root(work, prefix, "freq");
            }
            if (type.equals("appeng.parts.p2p.PartP2PIC2Power")) {
                // Retained-resource precision policy stays identical to v004; no free energy restoration.
                for (String key : Arrays.asList("OutputPacket", "OutputPacket2")) {
                    if (data.hasKey(key) && (!data.hasKey(key, 6) || data.getDouble(key) != 0)) {
                        work.issue(
                            "unsupported_p2p_energy",
                            "blocker",
                            displayPath(path(prefix, key)),
                            "IC2 P2P buffered energy cannot be restored; empty the source buffer or delete this part");
                        data.setDouble(key, 0);
                    }
                    root(work, prefix, key);
                }
            }
            if (type.startsWith(TILE + "crafting.TileCrafting")) {
                NBTTagCompound safe = shell(data);
                for (Object raw : data.func_150296_c()) {
                    String key = (String) raw;
                    if (key.equals("core")) {
                        if (data.getBoolean(key))
                            work.issue("cpu_core_reset", "warning", key, "Crafting CPU core runtime is regenerated");
                    } else if (!safe.hasKey(key) && !key.equals("proxy")) {
                        work.issue(
                            "cpu_runtime_omitted",
                            "blocker",
                            displayPath(path(prefix, key)),
                            "Crafting CPU runtime may hold physical resources; only its clean shell is retained");
                    }
                    root(work, prefix, key);
                }
                for (Object raw : new HashSet<Object>(data.func_150296_c())) data.removeTag((String) raw);
                for (Object raw : safe.func_150296_c()) data.setTag(
                    (String) raw,
                    safe.getTag((String) raw)
                        .copy());
                // False core prevents the native class from copying a cluster's previous state.
                data.setBoolean("core", false);
            }
        }

        static void inventory(Work work, NBTTagCompound data, Inventory inventory, String[] prefix, Object host)
            throws IOException {
            root(work, prefix, inventory.root);
            if (data.hasKey(inventory.root) && !data.hasKey(inventory.root, 10)) {
                work.issue(
                    "unknown_inventory_slot",
                    "blocker",
                    displayPath(path(prefix, inventory.root)),
                    "Malformed native inventory data is omitted; explicitly acknowledge the loss or replace this machine");
                data.removeTag(inventory.root);
            }
            NBTTagCompound stored = data.getCompoundTag(inventory.root);
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < inventory.size; i++) {
                String key = inventory.prefix + i;
                expected.add(key);
                String[] itemPath = path(prefix, inventory.root, key);
                String kind = inventory.kind, validator = inventory.validator;
                int limit = inventory.limit;
                if (host.getClass()
                    .getName()
                    .equals(TILE + "storage.TileChest") && i == 1) {
                    kind = "emptyCell";
                    validator = "cell";
                    limit = 1;
                }
                if (validator.equals("assembler")) {
                    if (i == 10) {
                        validator = "pattern";
                        limit = 1;
                    } else {
                        kind = "readonly";
                        validator = "any";
                        limit = 64;
                    }
                }
                if ((validator.equals("inscriber") || validator.equals("advancedInscriber")) && i == 3) {
                    kind = "readonly";
                    validator = "any";
                }
                if (inventory.root.equals("pattern") && i == 1) {
                    kind = "readonly";
                    validator = "pattern";
                }
                ItemStack stack = null;
                if (stored.hasKey(key)) {
                    if (!stored.hasKey(key, 10)) {
                        work.issue(
                            kind.equals("ghost") ? "unsupported_ghost" : "missing_physical_item",
                            "blocker",
                            displayPath(itemPath),
                            "Malformed slot record is omitted; clear or replace this marker explicitly");
                        stored.removeTag(key);
                    }
                    NBTTagCompound raw = stored.getCompoundTag(key);
                    if (!raw.hasNoTags()) {
                        if (kind.equals("ghost")) stack = ghost(work, raw, itemPath);
                        else {
                            stack = readStack(raw);
                            if (stack == null) {
                                boolean unavailable = raw.hasKey(HologramBlueprintNbt.ITEM_REGISTRY, 8)
                                    && HologramBlueprintNbt.stableItem(raw) == null;
                                work.issue(
                                    "missing_physical_item",
                                    unavailable ? "warning" : "blocker",
                                    displayPath(itemPath),
                                    "Physical item is unavailable or invalid; it is not silently retained for free");
                                stored.removeTag(key);
                            } else if (kind.equals("emptyCell") || isCell(stack)) {
                                kind = "emptyCell";
                                stack = emptyCell(work, stack, itemPath);
                                NBTTagCompound clean = new NBTTagCompound();
                                if (stack == null) stored.removeTag(key);
                                else {
                                    stack.writeToNBT(clean);
                                    stored.setTag(key, clean);
                                }
                            } else {
                                try {
                                    work.materials.add(nativeStack(stack));
                                    if (stack.stackSize > limit) work.issue(
                                        "slot_overflow",
                                        "blocker",
                                        displayPath(itemPath),
                                        "Physical marker exceeds the native slot limit; remove it or split the marker");
                                } catch (IOException unresolved) {
                                    work.issue(
                                        "missing_physical_item",
                                        HologramBlueprintNbt.hasMissingItems(raw) ? "warning" : "blocker",
                                        displayPath(itemPath),
                                        "Physical marker contains unavailable item data and is omitted; clear or replace this slot explicitly");
                                    stored.removeTag(key);
                                    stack = null;
                                }
                            }
                        }
                    } else stored.removeTag(key);
                    if (raw.hasNoTags()) stored.removeTag(key);
                }
                if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE preview slot budget exceeded");
                boolean enabled = !kind.equals("readonly");
                if (host instanceof IInterfaceHost && inventory.root.equals("patterns")) {
                    int rows = 1 + upgradeCount(data, Upgrades.PATTERN_CAPACITY);
                    enabled = i / 9 < rows;
                    if (!enabled && stack != null) work.issue(
                        "hidden_pattern",
                        "blocker",
                        displayPath(itemPath),
                        "Physical pattern is outside the installed capacity; add a capacity card or delete its marker");
                }
                work.slots
                    .add(new Slot(displayPath(itemPath), kind, limit, validator, stack, itemPath, i, host, enabled));
            }
            // Native loaders silently ignore extra slots: report and remove those exact inventory entries.
            for (Object raw : new HashSet<Object>(stored.func_150296_c())) {
                String key = (String) raw;
                if (!expected.contains(key)) {
                    work.issue(
                        "unknown_inventory_slot",
                        "blocker",
                        displayPath(path(prefix, inventory.root, key)),
                        "Unrecognized physical/ghost inventory entry is omitted; resolve before saving");
                    stored.removeTag(key);
                }
            }
            if (!stored.hasNoTags()) data.setTag(inventory.root, stored);
            else data.removeTag(inventory.root);
            if (inventory.validator.equals("upgrade")) validateUpgradeGroup(work, data, inventory, prefix, host);
        }

        static void cable(Work work, NBTTagCompound data, String[] prefix) throws IOException {
            previewFields(work, data, prefix, true);
            if (prefix.length == 0) {
                data.setInteger("x", 0);
                data.setInteger("y", 64);
                data.setInteger("z", 0);
                work.roots.addAll(Arrays.asList("x", "y", "z"));
            }
            remove(work, data, "hasRedstone", prefix, "runtime_reset", "CableBus redstone cache is regenerated");
            for (int side = 0; side < 7; side++) {
                String definition = "def:" + side, extraKey = "extra:" + side;
                root(work, prefix, definition);
                root(work, prefix, extraKey);
                if (side < 6) {
                    String facade = "facade:" + side;
                    root(work, prefix, facade);
                    if (data.hasKey(facade, 10)) {
                        ItemStack facadeItem = readStack(data.getCompoundTag(facade));
                        if (facadeItem == null) throw new IOException("Missing facade material");
                        if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE preview slot budget exceeded");
                        work.slots.add(
                            new Slot(
                                displayPath(path(prefix, facade)),
                                "readonly",
                                1,
                                "facade",
                                facadeItem,
                                path(prefix, facade),
                                side,
                                null));
                    }
                }
                if (!data.hasKey(definition)) {
                    if (data.hasKey(extraKey)) remove(
                        work,
                        data,
                        extraKey,
                        prefix,
                        "orphan_part_data",
                        "Orphan part inventory/configuration is omitted");
                    continue;
                }
                if (!data.hasKey(definition, 10)) throw new IOException("Invalid AE part definition");
                ItemStack definitionStack = readStack(data.getCompoundTag(definition));
                if (definitionStack == null || !(definitionStack.getItem() instanceof IPartItem))
                    throw new IOException("Missing AE native part item");
                ItemStack cleanDefinition = new ItemStack(
                    definitionStack.getItem(),
                    1,
                    definitionStack.getItemDamage());
                if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE preview slot budget exceeded");
                work.slots.add(
                    new Slot(
                        displayPath(path(prefix, definition)),
                        "readonly",
                        1,
                        "part",
                        cleanDefinition,
                        path(prefix, definition),
                        side,
                        null));
                if (definitionStack.hasTagCompound()) {
                    work.issue(
                        "part_item_data_reset",
                        "warning",
                        displayPath(path(prefix, definition, "tag")),
                        "Part item identity/configuration tags are omitted; audited part state is retained in its own schema");
                    NBTTagCompound written = new NBTTagCompound();
                    cleanDefinition.writeToNBT(written);
                    data.setTag(definition, written);
                }
                if (!definitionStack.getItem()
                    .getClass()
                    .getName()
                    .equals("appeng.items.parts.ItemMultiPart")) {
                    data.removeTag(definition);
                    data.removeTag(extraKey);
                    work.slots.remove(work.slots.size() - 1);
                    work.issue(
                        "unsupported_part_item",
                        "warning",
                        displayPath(path(prefix, extraKey)),
                        "Unreviewed addon part is omitted, including its contents and network references; replace it to restore this component");
                    continue;
                }
                IPart part = ((IPartItem) definitionStack.getItem()).createPartFromItemStack(cleanDefinition);
                if (part == null || !part.getClass()
                    .getName()
                    .startsWith("appeng.parts.")) {
                    data.removeTag(definition);
                    data.removeTag(extraKey);
                    work.slots.remove(work.slots.size() - 1);
                    work.issue(
                        "unsupported_part",
                        "warning",
                        displayPath(path(prefix, extraKey)),
                        "Unreviewed addon part is omitted; remaining registered components retain their material costs");
                    continue;
                }
                NBTTagCompound extra = data.getCompoundTag(extraKey);
                String[] extraPath = path(prefix, extraKey);
                cleanHost(work, extra, part, extraPath);
                if (part instanceof IInterfaceHost) {
                    for (Inventory inventory : SCHEMAS.get(TILE + "misc.TileInterface").inventories)
                        inventory(work, extra, inventory, extraPath, part);
                    guardInterfaceRoots(work, extra, extraPath, part);
                } else {
                    if (part instanceof appeng.parts.automation.PartUpgradeable) {
                        IInventory upgrades = nativeInventory(part, "upgrades");
                        if (upgrades == null) throw new IOException("AE part upgrades are unavailable");
                        inventory(work, extra, upgrade(upgrades.getSizeInventory()), extraPath, part);
                    }
                    int configSize = nativeGhostSize(part);
                    if (configSize > 0)
                        inventory(work, extra, inv("config", "#", configSize, "ghost", 64, "any"), extraPath, part);
                    terminal(work, extra, part, extraPath);
                    if (part instanceof appeng.parts.reporting.AbstractPartMonitor)
                        single(work, extra, "configuredItem", "ghost", 64, "any", extraPath, part);
                    guardPartRoots(work, extra, part, extraPath, configSize);
                }
                data.setTag(extraKey, extra);
            }
            Set<String> known = new HashSet<>(
                Arrays.asList("id", "x", "y", "z", "customName", "orientation_forward", "orientation_up"));
            for (int side = 0; side < 7; side++) {
                known.add("def:" + side);
                known.add("extra:" + side);
                if (side < 6) known.add("facade:" + side);
            }
            for (Object raw : new HashSet<Object>(data.func_150296_c())) {
                String key = (String) raw;
                root(work, prefix, key);
                if (!known.contains(key)) {
                    work.issue(
                        "unreviewed_cable_field",
                        "blocker",
                        displayPath(path(prefix, key)),
                        "CableBus field is outside its audited schema and is omitted");
                    data.removeTag(key);
                }
            }
        }

        /** Project-owned display transport fields, never native inventory or network persistence. */
        static void previewFields(Work work, NBTTagCompound data, String[] prefix, boolean cable) {
            if (data.hasKey("gtmb.nativePreview")) {
                root(work, prefix, "gtmb.nativePreview");
                boolean valid = data.hasKey("gtmb.nativePreview", 1)
                    && (data.getByte("gtmb.nativePreview") == 0 || data.getByte("gtmb.nativePreview") == 1);
                if (!valid) work.issue(
                    cable ? "unreviewed_cable_field" : "unreviewed_tile_field",
                    "blocker",
                    displayPath(path(prefix, "gtmb.nativePreview")),
                    "Malformed preview marker is omitted; review the unrecognized data explicitly");
                data.removeTag("gtmb.nativePreview");
            }
            if (!cable || !data.hasKey("gtmb.aeCableVisual")) return;
            root(work, prefix, "gtmb.aeCableVisual");
            NBTTagCompound visual = data.getCompoundTag("gtmb.aeCableVisual");
            boolean valid = data.hasKey("gtmb.aeCableVisual", 10) && visual.func_150296_c()
                .size() == 2
                && visual.hasKey("connections", 3)
                && visual.hasKey("channels", 3)
                && visual.getInteger("connections") >= 0
                && visual.getInteger("connections") <= 255;
            if (!valid) work.issue(
                "unreviewed_cable_field",
                "blocker",
                displayPath(path(prefix, "gtmb.aeCableVisual")),
                "Cable display cache contains unrecognized data and is omitted; explicit review is required");
            data.removeTag("gtmb.aeCableVisual");
        }

        static void multipart(Work work) throws IOException {
            work.data.setInteger("x", 0);
            work.data.setInteger("y", 64);
            work.data.setInteger("z", 0);
            work.roots.addAll(Arrays.asList("x", "y", "z"));
            NBTTagList parts = work.data.getTagList("parts", 10);
            if (parts.tagCount() > 64) throw new IOException("Multipart component budget exceeded");
            boolean found = false;
            for (int i = 0; i < parts.tagCount(); i++) {
                NBTTagCompound part = parts.getCompoundTagAt(i);
                if (!part.getString("id")
                    .equals("ae2_cablebus")) continue;
                found = true;
                cable(work, part, new String[] { "@part=" + i });
            }
            work.supported = found;
            // 'parts' contains non-AE components too: the existing FMP resource provider retains ownership of them.
        }

        static void validate(Slot slot, ItemStack marker, Analysis before) throws IOException {
            String rule = slot.validator;
            if (rule.equals("any") && !slot.kind.equals("ghost")
                && slot.nativeIndex >= 0
                && slot.host instanceof IInventory
                && !((IInventory) slot.host).isItemValidForSlot(slot.nativeIndex, marker))
                throw new IOException("AE native inventory rejects this marker in this slot");
            if (slot.kind.equals("ghost") && slot.host instanceof IInterfaceHost) {
                IAEStack<?> ghost = Platform.convertStackPacket(marker);
                boolean supported = false;
                for (appeng.api.storage.data.IAEStackType<?> type : ((IInterfaceHost) slot.host)
                    .getSupportedStackTypes()) supported |= ghost != null && ghost.getStackType() == type;
                if (!supported) throw new IOException("AE Interface does not support this ghost stack type");
            }
            if (rule.equals("pattern")) {
                if (!(marker.getItem() instanceof ICraftingPatternItem) || !marker.hasTagCompound())
                    throw new IOException("AE pattern slot requires an encoded physical pattern");
            } else if (rule.equals("cell") || rule.equals("workbenchCell")) {
                boolean accepted = rule.equals("workbenchCell")
                    ? marker.getItem() instanceof ICellWorkbenchItem
                        && ((ICellWorkbenchItem) marker.getItem()).isEditable(marker)
                    : AEApi.instance()
                        .registries()
                        .cell()
                        .isCellHandled(marker);
                if (!accepted) throw new IOException("AE slot requires a recognized storage cell");
            } else if (rule.equals("spatialCell")) {
                if (!(marker.getItem() instanceof ISpatialStorageCell)
                    || !((ISpatialStorageCell) marker.getItem()).isSpatialStorage(marker))
                    throw new IOException("AE slot requires a spatial cell");
            } else if (rule.equals("viewCell")) {
                if (!AEApi.instance()
                    .definitions()
                    .items()
                    .viewCell()
                    .isSameAs(marker)) throw new IOException("AE terminal slot requires a view cell");
            } else if (rule.equals("patternAny")) {
                if (!(marker.getItem() instanceof ICraftingPatternItem) && !AEApi.instance()
                    .definitions()
                    .materials()
                    .blankPattern()
                    .isSameAs(marker)) throw new IOException("AE pattern terminal requires a blank or encoded pattern");
            } else if (rule.equals("upgrade")) {
                if (!(marker.getItem() instanceof IUpgradeModule))
                    throw new IOException("AE slot requires an upgrade card");
                Upgrades type = ((IUpgradeModule) marker.getItem()).getType(marker);
                IInventory upgrades = nativeInventory(slot.host, "upgrades");
                if (type == null || !(upgrades instanceof UpgradeInventory))
                    throw new IOException("AE upgrade schema unavailable");
                int count = 0;
                String group = slot.id.substring(0, slot.id.lastIndexOf('/'));
                for (Slot other : before.slots) {
                    if (other.id.equals(slot.id) || !other.id.startsWith(group + "/")) continue;
                    ItemStack stack = other.stack();
                    if (stack != null && stack.getItem() instanceof IUpgradeModule
                        && ((IUpgradeModule) stack.getItem()).getType(stack) == type) count += stack.stackSize;
                }
                if (count + marker.stackSize > ((UpgradeInventory) upgrades).getMaxInstalled(type))
                    throw new IOException("AE machine does not support this upgrade count");
            } else if (rule.equals("fuel")) {
                if (net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime(marker) <= 0)
                    throw new IOException("AE vibration chamber requires fuel");
            } else if (rule.equals("chargeable")) {
                if (!Platform.isChargeable(marker)) throw new IOException("AE charger requires a chargeable item");
            } else if (rule.equals("rangeBooster")) {
                if (!AEApi.instance()
                    .definitions()
                    .materials()
                    .wirelessBooster()
                    .isSameAs(marker)) throw new IOException("AE wireless slot requires a range booster");
            } else if (rule.equals("singularity")) {
                if (!AEApi.instance()
                    .definitions()
                    .materials()
                    .qESingularity()
                    .isSameAs(marker)) throw new IOException("Quantum bridge requires a quantum singularity");
            } else if (rule.equals("inscriber")) {
                if (!(slot.host instanceof IInventory)
                    || !((IInventory) slot.host).isItemValidForSlot(slot.nativeIndex, marker))
                    throw new IOException("Invalid inscriber material/press for this native slot");
            } else if (rule.equals("advancedInscriber")) {
                if (!(slot.host instanceof IInventory)
                    || !((IInventory) slot.host).isItemValidForSlot(slot.nativeIndex, marker))
                    throw new IOException("Invalid advanced inscriber material/press for this native slot");
            }
        }

        private static void terminal(Work work, NBTTagCompound data, IPart part, String[] prefix) throws IOException {
            if (!(part instanceof appeng.parts.reporting.AbstractPartTerminal)) return;
            inventory(work, data, inv("viewCell", "#", 5, "physical", 1, "viewCell"), prefix, part);
            String type = part.getClass()
                .getName();
            if (type.equals("appeng.parts.reporting.PartCraftingTerminal"))
                inventory(work, data, inv("craftingGrid", "#", 9, "physical", 64, "any"), prefix, part);
            else if (type.equals("appeng.parts.reporting.PartPatternTerminal")
                || type.equals("appeng.parts.reporting.PartPatternTerminalEx")) {
                    inventory(work, data, inv("pattern", "#", 2, "physical", 64, "patternAny"), prefix, part);
                    int crafting = type.endsWith("Ex") ? 32 : 9, output = type.endsWith("Ex") ? 32 : 3;
                    inventory(work, data, inv("craftingGrid", "#", crafting, "ghost", 64, "any"), prefix, part);
                    inventory(work, data, inv("outputList", "#", output, "ghost", 64, "any"), prefix, part);
                }
        }

        private static void single(Work work, NBTTagCompound data, String key, String kind, int limit, String validator,
            String[] prefix, Object host) throws IOException {
            root(work, prefix, key);
            String[] location = path(prefix, key);
            ItemStack stack = null;
            if (data.hasKey(key)) {
                if (!data.hasKey(key, 10)) throw new IOException("Invalid AE singleton slot " + displayPath(location));
                NBTTagCompound raw = data.getCompoundTag(key);
                stack = kind.equals("ghost") ? ghost(work, raw, location) : readStack(raw);
                if (!kind.equals("ghost")) {
                    if (stack == null) {
                        work.issue(
                            "missing_physical_item",
                            raw.hasKey(HologramBlueprintNbt.ITEM_REGISTRY, 8)
                                && HologramBlueprintNbt.stableItem(raw) == null ? "warning" : "blocker",
                            displayPath(location),
                            "Physical buffered item is invalid and omitted");
                        data.removeTag(key);
                    } else {
                        if (isCell(stack)) {
                            stack = emptyCell(work, stack, location);
                            NBTTagCompound clean = new NBTTagCompound();
                            if (stack == null) data.removeTag(key);
                            else {
                                stack.writeToNBT(clean);
                                data.setTag(key, clean);
                            }
                        } else work.materials.add(nativeStack(stack));
                    }
                }
            }
            if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE preview slot budget exceeded");
            work.slots.add(new Slot(displayPath(location), kind, limit, validator, stack, location, -1, host));
        }

        private static void root(Work work, String[] prefix, String key) {
            if (prefix.length == 0) work.roots.add(key);
            else if (!prefix[0].startsWith("@part=")) work.roots.add(prefix[0]);
        }

        private static void remove(Work work, NBTTagCompound data, String key, String[] prefix, String code,
            String message) {
            root(work, prefix, key);
            if (data.hasKey(key)) {
                work.issue(code, "warning", displayPath(path(prefix, key)), message);
                data.removeTag(key);
            }
        }

        private static void zero(Work work, NBTTagCompound data, String key, String[] prefix, String code,
            String message) {
            root(work, prefix, key);
            if (data.hasKey(key)) {
                if (!data.hasKey(key, 99) || data.getDouble(key) != 0)
                    work.issue(code, "warning", displayPath(path(prefix, key)), message);
                data.setDouble(key, 0);
            }
        }

        private static ItemStack readStack(NBTTagCompound raw) throws IOException {
            if (raw.hasNoTags()) return null;
            Item item = HologramBlueprintNbt.stableItem(raw);
            if (item == null || !raw.hasKey("Damage", 99) || !raw.hasKey("Count", 99)) return null;
            int count = raw.getInteger("Count");
            if (count <= 0) return null;
            ItemStack stack = new ItemStack(item, count, raw.getInteger("Damage"));
            if (raw.hasKey("tag", 10)) stack.setTagCompound(
                (NBTTagCompound) raw.getCompoundTag("tag")
                    .copy());
            return stack;
        }

        private static ItemStack nativeStack(ItemStack stack) throws IOException {
            ItemStack result = stack.copy();
            if (result.hasTagCompound())
                result.setTagCompound(HologramBlueprintNbt.nativePayload(result.getTagCompound()));
            return result;
        }

        private static ItemStack ghost(Work work, NBTTagCompound raw, String[] location) throws IOException {
            try {
                IAEStack<?> ghost = Platform.readStackNBT(HologramBlueprintNbt.nativePayload(raw), false);
                if (ghost == null || ghost.getStackSize() < 0) throw new IOException("Invalid ghost stack");
                NBTTagCompound canonical = new NBTTagCompound();
                Platform.writeStackNBT(ghost, canonical, true);
                for (Object key : new HashSet<Object>(raw.func_150296_c())) raw.removeTag((String) key);
                for (Object key : canonical.func_150296_c()) raw.setTag(
                    (String) key,
                    canonical.getTag((String) key)
                        .copy());
                return ghost.getItemStackForNEI();
            } catch (RuntimeException | IOException invalid) {
                work.issue(
                    "unsupported_ghost",
                    HologramBlueprintNbt.hasMissingItems(raw) ? "warning" : "blocker",
                    displayPath(location),
                    "Ghost stack type is unavailable; resolve this sample");
                // Keep only a blocker; not an unrecognized typed record that could acquire meaning later.
                for (Object key : new HashSet<Object>(raw.func_150296_c())) raw.removeTag((String) key);
                return null;
            }
        }

        private static ItemStack emptyCell(Work work, ItemStack source, String[] location) throws IOException {
            ItemStack empty = new ItemStack(source.getItem(), 1, source.getItemDamage());
            String type = source.getItem()
                .getClass()
                .getName();
            String cellPath = displayPath(location);
            if (CELL_TYPES.contains(type)) {
                NBTTagCompound original = source.hasTagCompound() ? source.getTagCompound() : new NBTTagCompound();
                boolean nativeFluid = AE2FC_CELL_TYPES.contains(type);
                if (nativeFluid && !nativeFluidCellSchema(source)) {
                    work.issue(
                        "unsupported_cell",
                        "warning",
                        cellPath,
                        "Reviewed fluid cell no longer supplies the native stack-backed fluid schema; replace this marker");
                    return null;
                }
                NBTTagCompound kept = nativeFluid ? projectNativeFluidCellMetadata(type, original)
                    : new NBTTagCompound();
                boolean hadContents = false;
                for (Object raw : original.func_150296_c()) {
                    String key = (String) raw;
                    if (nativeFluid) {
                        if (!kept.hasKey(key)) hadContents = true;
                    } else if (CELL_CONFIG_KEYS.contains(key)) kept.setTag(
                        key,
                        original.getTag(key)
                            .copy());
                    else if (key.matches("[#@][0-9]+") || Arrays
                        .asList(
                            "it",
                            "ic",
                            "ft",
                            "fc",
                            "diskuuid",
                            "diskdata",
                            "disklist",
                            "fluid_disklist",
                            "disklist_v2",
                            "linked")
                        .contains(key)) hadContents = true;
                    else work.issue(
                        "cell_unknown_data",
                        "warning",
                        cellPath + "/tag/" + key,
                        "Unreviewed cell metadata is omitted from the empty cell");
                }
                if (hadContents) work.issue(
                    "cell_contents_cleared",
                    "warning",
                    cellPath + "/tag",
                    "Storage cell contents are omitted; same cell type, partition and supported cards are retained");
                if (kept.hasKey("list") && !kept.hasKey("list", 10)) {
                    work.issue(
                        "cell_partition_invalid",
                        "blocker",
                        cellPath + "/tag/list",
                        "Malformed cell partition is omitted; replace or clear this cell marker explicitly");
                    kept.removeTag("list");
                }
                NBTTagCompound partition = kept.getCompoundTag("list");
                for (Object raw : new HashSet<Object>(partition.func_150296_c())) {
                    String key = (String) raw;
                    if (!key.matches("#[0-9]{1,2}") || Integer.parseInt(key.substring(1)) >= 63
                        || !partition.hasKey(key, 10)) {
                        work.issue(
                            "cell_partition_invalid",
                            "blocker",
                            cellPath + "/tag/list/" + key,
                            "Cell partition record is outside its native schema and is omitted");
                        partition.removeTag(key);
                    } else ghost(work, partition.getCompoundTag(key), path(location, "tag", "list", key));
                }
                // Resolve stable imported item identities before the native CellUpgrades loader reads numeric ids.
                // Only the exact captured upgrade inventory is visited; recipe/config trees are not rewritten.
                NBTTagCompound persistedCards = kept.getCompoundTag("upgrades");
                for (Object raw : new HashSet<Object>(persistedCards.func_150296_c())) {
                    String key = (String) raw;
                    if (!persistedCards.hasKey(key, 10) || persistedCards.getCompoundTag(key)
                        .hasNoTags()) continue;
                    ItemStack card = readStack(persistedCards.getCompoundTag(key));
                    try {
                        if (card == null) throw new IOException("Unavailable embedded cell card");
                        NBTTagCompound nativeCard = new NBTTagCompound();
                        nativeStack(card).writeToNBT(nativeCard);
                        persistedCards.setTag(key, nativeCard);
                    } catch (IOException unresolved) {
                        work.issue(
                            "missing_physical_item",
                            HologramBlueprintNbt.hasMissingItems(persistedCards.getCompoundTag(key)) ? "warning"
                                : "blocker",
                            cellPath + "/tag/upgrades/" + key,
                            "Embedded physical card is unavailable and omitted; clear or replace this marker explicitly");
                        persistedCards.removeTag(key);
                    }
                }
                if (!kept.hasNoTags()) empty.setTagCompound(kept);
                // Native AEBaseCell supplies standalone, stack-backed config/upgrades; source is never touched.
                IInventory cards = cellUpgrades(empty);
                Map<Upgrades, Integer> counts = new HashMap<>();
                for (int i = 0; i < cards.getSizeInventory(); i++) {
                    ItemStack card = cards.getStackInSlot(i);
                    if (card == null) continue;
                    if (!(card.getItem() instanceof IUpgradeModule)) {
                        work.issue(
                            "cell_upgrade_invalid",
                            "blocker",
                            cellPath + "/tag/upgrades/#" + i,
                            "Embedded cell slot contains a physical item that is not an upgrade card; replace or clear the cell marker");
                        work.materials.add(nativeStack(card));
                        continue;
                    }
                    Upgrades upgrade = ((IUpgradeModule) card.getItem()).getType(card);
                    int count = counts.containsKey(upgrade) ? counts.get(upgrade) : 0;
                    counts.put(upgrade, count + card.stackSize);
                    if (upgrade == null || card.stackSize != 1
                        || !(cards instanceof UpgradeInventory)
                        || count + card.stackSize > ((UpgradeInventory) cards).getMaxInstalled(upgrade))
                        work.issue(
                            "cell_upgrade_invalid",
                            "blocker",
                            cellPath + "/tag/upgrades/#" + i,
                            "Embedded cell card exceeds native type/capacity constraints");
                    work.materials.add(nativeStack(card));
                }
                // Inspect all persisted slots, including extras silently ignored by native loaders.
                inventoryWithoutCosts(work, kept, upgrade(cards.getSizeInventory()), path(location, "tag"), empty);
            } else {
                work.issue(
                    "unsupported_cell",
                    "warning",
                    cellPath,
                    "Unreviewed cell is omitted so its default factory cannot access external storage; replace this marker with a reviewed empty cell");
                return null;
            }
            work.materials.add(new ItemStack(source.getItem(), 1, source.getItemDamage()));
            if (source.stackSize != 1)
                work.issue("cell_stack_count", "blocker", cellPath, "Cell marker must have count 1");
            return nativeStack(empty);
        }

        /** GTIT's fluid cell uses these exact native stack-backed inventories without ICellWorkbenchItem. */
        private static IInventory cellUpgrades(ItemStack empty) throws IOException {
            if (empty.getItem() instanceof ICellWorkbenchItem)
                return ((ICellWorkbenchItem) empty.getItem()).getUpgradesInventory(empty);
            if (!empty.getItem()
                .getClass()
                .getName()
                .equals("com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageFluidCell"))
                throw new IOException("Reviewed cell workbench schema changed");
            try {
                Object cards = empty.getItem()
                    .getClass()
                    .getMethod("getUpgradesInventory", ItemStack.class)
                    .invoke(empty.getItem(), empty);
                if (!(cards instanceof appeng.items.contents.CellUpgrades))
                    throw new IOException("GTIT fluid cell inventory is outside its reviewed schema");
                return (IInventory) cards;
            } catch (ReflectiveOperationException failure) {
                throw new IOException("GTIT fluid cell workbench API changed", failure);
            }
        }

        private static boolean isCell(ItemStack stack) {
            return stack.getItem() instanceof ISpatialStorageCell || AEApi.instance()
                .registries()
                .cell()
                .isCellHandled(stack);
        }

        private static int upgradeCount(NBTTagCompound data, Upgrades wanted) throws IOException {
            int count = 0;
            NBTTagCompound cards = data.getCompoundTag("upgrades");
            for (Object raw : cards.func_150296_c()) {
                ItemStack card = readStack(cards.getCompoundTag((String) raw));
                if (card != null && card.getItem() instanceof IUpgradeModule
                    && ((IUpgradeModule) card.getItem()).getType(card) == wanted) count += card.stackSize;
            }
            return Math.min(count, 3);
        }

        private static IInventory nativeInventory(Object host, String name) throws IOException {
            if (host instanceof IInterfaceHost) return ((IInterfaceHost) host).getInterfaceDuality()
                .getInventoryByName(name);
            if (host instanceof ItemStack) {
                ItemStack stack = (ItemStack) host;
                if (name.equals("upgrades") && stack.getItem() instanceof ICellWorkbenchItem)
                    return ((ICellWorkbenchItem) stack.getItem()).getUpgradesInventory(stack);
                return null;
            }
            try {
                Method method = host.getClass()
                    .getMethod("getInventoryByName", String.class);
                return (IInventory) method.invoke(host, name);
            } catch (NoSuchMethodException absent) {
                return null;
            } catch (ReflectiveOperationException failure) {
                throw new IOException("AE native inventory schema changed", failure);
            }
        }

        private static int nativeGhostSize(Object host) throws IOException {
            try {
                Method method = host.getClass()
                    .getMethod("getAEInventoryByName", StorageName.class);
                Object config = method.invoke(host, StorageName.CONFIG);
                return config instanceof IAEStackInventory ? ((IAEStackInventory) config).getSizeInventory() : 0;
            } catch (NoSuchMethodException absent) {
                return 0;
            } catch (ReflectiveOperationException failure) {
                throw new IOException("AE ghost inventory schema changed", failure);
            }
        }

        private static void guardPartRoots(Work work, NBTTagCompound data, IPart host, String[] prefix,
            int configSize) {
            Set<String> known = new HashSet<>(
                Arrays.asList("part", "display", "priority", "filter", "customName", "orientation", "paintedColor"));
            for (Settings setting : Settings.values()) known.add(setting.name());
            if (host instanceof appeng.parts.automation.PartUpgradeable) known.add("upgrades");
            if (configSize > 0) known.add("config");
            if (reviewedReporting(
                host.getClass()
                    .getName()))
                known.add("spin");
            if (host.getClass()
                .getName()
                .startsWith("appeng.parts.p2p.")) known.addAll(Arrays.asList("freq", "output"));
            if (host instanceof appeng.parts.reporting.AbstractPartMonitor)
                known.addAll(Arrays.asList("configuredItem", "isLocked"));
            if (host instanceof appeng.parts.reporting.AbstractPartTerminal) known.add("viewCell");
            if (host.getClass()
                .getName()
                .equals("appeng.parts.reporting.PartCraftingTerminal")) known.add("craftingGrid");
            if (host.getClass()
                .getName()
                .equals("appeng.parts.reporting.PartPatternTerminal")
                || host.getClass()
                    .getName()
                    .equals("appeng.parts.reporting.PartPatternTerminalEx"))
                known.addAll(
                    Arrays.asList(
                        "craftingGrid",
                        "outputList",
                        "pattern",
                        "craftingMode",
                        "substitute",
                        "beSubstitute",
                        "inverted",
                        "activePage"));
            if (host.getClass()
                .getName()
                .equals("appeng.parts.p2p.PartP2PIC2Power"))
                known.addAll(Arrays.asList("OutputPacket", "OutputPacket2", "OutputVoltageA", "OutputVoltageB"));
            if (host.getClass()
                .getName()
                .equals("appeng.parts.automation.PartLevelEmitter"))
                known.addAll(Arrays.asList("reportingValue", "typeFilters", "TYPE_FILTER"));
            for (Object raw : new HashSet<Object>(data.func_150296_c())) {
                String key = (String) raw;
                if (!known.contains(key)) {
                    work.issue(
                        "unreviewed_part_field",
                        "blocker",
                        displayPath(path(prefix, key)),
                        "AE part field is outside the audited schema and is omitted; remove the part or resolve its state");
                    data.removeTag(key);
                }
            }
        }

        private static boolean reviewedReporting(String type) {
            return Arrays
                .asList(
                    "appeng.parts.reporting.PartTerminal",
                    "appeng.parts.reporting.PartInterfaceTerminal",
                    "appeng.parts.reporting.PartCraftingTerminal",
                    "appeng.parts.reporting.PartPatternTerminal",
                    "appeng.parts.reporting.PartPatternTerminalEx",
                    "appeng.parts.reporting.PartStorageMonitor",
                    "appeng.parts.reporting.PartConversionMonitor")
                .contains(type);
        }

        private static void guardInterfaceRoots(Work work, NBTTagCompound data, String[] prefix, IPart host) {
            Set<String> known = new HashSet<>(
                Arrays.asList(
                    "part",
                    "customName",
                    "display",
                    "config",
                    "patterns",
                    "storage",
                    "upgrades",
                    "priority",
                    "waitingToSend"));
            for (Settings setting : Settings.values()) known.add(setting.name());
            if (host.getClass()
                .getName()
                .equals("appeng.parts.p2p.PartP2PInterface")) known.addAll(Arrays.asList("freq", "output"));
            for (Object raw : new HashSet<Object>(data.func_150296_c())) {
                String key = (String) raw;
                if (!known.contains(key)) {
                    work.issue(
                        "unreviewed_interface_field",
                        "blocker",
                        displayPath(path(prefix, key)),
                        "Interface field is outside its audited schema and is omitted");
                    data.removeTag(key);
                }
            }
        }

        private static void validateUpgradeGroup(Work work, NBTTagCompound data, Inventory inventory, String[] prefix,
            Object host) throws IOException {
            IInventory nativeInv = nativeInventory(host, "upgrades");
            if (!(nativeInv instanceof UpgradeInventory)) throw new IOException("AE upgrade inventory unavailable");
            Map<Upgrades, Integer> counts = new HashMap<>();
            for (int i = 0; i < inventory.size; i++) {
                String key = inventory.prefix + i;
                ItemStack card = readStack(
                    data.getCompoundTag(inventory.root)
                        .getCompoundTag(key));
                if (card == null) continue;
                Upgrades type = card.getItem() instanceof IUpgradeModule
                    ? ((IUpgradeModule) card.getItem()).getType(card)
                    : null;
                int count = counts.containsKey(type) ? counts.get(type) : 0;
                counts.put(type, count + card.stackSize);
                if (type == null || card.stackSize != 1
                    || count + card.stackSize > ((UpgradeInventory) nativeInv).getMaxInstalled(type))
                    work.issue(
                        "invalid_upgrade",
                        "blocker",
                        displayPath(path(prefix, inventory.root, key)),
                        "Native AE upgrade type or count is invalid for this machine");
            }
        }

        private static void inventoryWithoutCosts(Work work, NBTTagCompound data, Inventory inventory, String[] prefix,
            Object host) throws IOException {
            int first = work.materials.size();
            inventory(work, data, inventory, prefix, host);
            while (work.materials.size() > first) work.materials.remove(work.materials.size() - 1);
        }

        private static void waiting(Work work, NBTTagCompound data, String[] prefix) throws IOException {
            root(work, prefix, "waitingToSend");
            if (work.slots.size() >= MAX_SLOTS) throw new IOException("AE preview slot budget exceeded");
            work.slots.add(
                new Slot(
                    displayPath(path(prefix, "waitingToSend")),
                    "readonly",
                    0,
                    "clearQueue",
                    null,
                    path(prefix, "waitingToSend"),
                    -1,
                    null));
            if (data.hasKey("waitingToSend") && !data.hasKey("waitingToSend", 9))
                throw new IOException("Invalid Interface waiting buffer");
            // A typed getter silently substitutes an empty list when a retained nonempty list has another element
            // type. Preserve the actual native tag so malformed queued cargo cannot become an empty, payable host.
            NBTTagList queue = data.hasKey("waitingToSend") ? (NBTTagList) data.getTag("waitingToSend")
                : new NBTTagList();
            if (queue.tagCount() > 0 && queue.func_150303_d() != 10)
                throw new IOException("Invalid AE2 Interface waiting queue entry type");
            if (queue.tagCount() > 512) throw new IOException("AE waiting queue budget exceeded");
            NBTTagList safe = new NBTTagList();
            for (int i = 0; i < queue.tagCount(); i++) {
                NBTTagCompound raw = queue.getCompoundTagAt(i);
                IAEStack<?> queued;
                try {
                    queued = Platform.readStackNBT(HologramBlueprintNbt.nativePayload(raw), true);
                } catch (RuntimeException | IOException invalid) {
                    work.issue(
                        "waiting_stack_invalid",
                        "blocker",
                        displayPath(path(prefix, "waitingToSend", "#" + i)),
                        "Unrecognized physical waiting buffer entry is omitted; clear this blueprint queue before saving");
                    continue;
                }
                if (!(queued instanceof IAEItemStack) || queued.getStackSize() < 1
                    || queued.getStackSize() > Integer.MAX_VALUE
                    || !raw.hasKey("Cnt", 4)) {
                    work.issue(
                        "waiting_stack_unsupported",
                        "blocker",
                        displayPath(path(prefix, "waitingToSend", "#" + i)),
                        "Waiting buffer type/count cannot be paid exactly and is omitted; clear this blueprint queue before saving");
                    continue;
                }
                ItemStack item = ((IAEItemStack) queued).getItemStack();
                item.stackSize = (int) queued.getStackSize();
                work.materials.add(item.copy());
                safe.appendTag(raw.copy());
            }
            if (safe.tagCount() == 0) data.removeTag("waitingToSend");
            else data.setTag("waitingToSend", safe);
        }
    }
}

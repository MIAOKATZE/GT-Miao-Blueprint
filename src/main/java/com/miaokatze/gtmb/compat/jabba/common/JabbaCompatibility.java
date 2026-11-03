package com.miaokatze.gtmb.compat.jabba.common;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativeCapture;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.hologram.HologramNativePlacement;
import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

/** JABBA 1.5.24: local finite barrels, with pure capture also preserving shared/raw identities. */
public final class JabbaCompatibility {

    private static final String PREFIX = "mcp.mobius.betterbarrels.";
    private static final String TILE = PREFIX + "common.blocks.TileEntityBarrel";
    private static final String STORAGE = PREFIX + "common.blocks.StorageLocal";
    private static final String[] RESOURCES = { "version", "storage", "coreUpgrades", "structural", "redstone",
        "hopper", "ender", "void", "creative", "nStorageUpg", "sideUpgrades", "sideMeta", "linked" };

    private JabbaCompatibility() {}

    public static List<GTMBExtensions.Registration> install() {
        List<GTMBExtensions.Registration> installed = new ArrayList<>();
        try {
            installed.add(NativeCapture.register("gtmb:jabba_capture", new NativeCapture.Adapter() {

                public boolean supports(TileEntity tile) {
                    return tile.getClass()
                        .getName()
                        .equals(TILE);
                }

                public NBTTagCompound capture(TileEntity tile) throws Exception {
                    return capturePure(tile);
                }
            }));
            installed.add(ResourceContainers.registerRoot("gtmb:jabba_resources", context -> {
                if (!context.nativeTileId.equals("TileEntityBarrel") || !context.selectedGroup(RESOURCES)) return null;
                return new ResourceContainers.RootClaim(
                    Arrays.asList(RESOURCES),
                    bill(context.root()),
                    Collections.emptyList(),
                    BigInteger.ZERO);
            }));
            HologramNativePlacement.Adapter placement = new Placement();
            installed.add(GTMBExtensions.install("gtmb:jabba_placement", "native-placement", () -> {
                HologramNativePlacement.registerBuiltin("gtmb:jabba_placement", placement);
                return () -> HologramNativePlacement.unregister("gtmb:jabba_placement", placement);
            }));
            return Collections.unmodifiableList(installed);
        } catch (RuntimeException | Error failure) {
            for (int i = installed.size() - 1; i >= 0; i--) installed.get(i)
                .close();
            throw failure;
        }
    }

    /** Never invokes TileEntityBarrel.writeToNBT/getStorage, which can allocate or repair BSpace identities. */
    public static NBTTagCompound capturePure(TileEntity tile) throws Exception {
        NBTTagCompound tag = HologramNativePlacement.captureBase(tile);
        Object upgrades = get(tile, "coreUpgrades");
        if (!upgrades.getClass()
            .getName()
            .equals(PREFIX + "common.blocks.BarrelCoreUpgrades")) throw unsupported("unknown core-upgrade serializer");
        Object storage = get(tile, "storage");
        if ((Boolean) get(upgrades, "hasEnder") && tile.getWorldObj() != null && !tile.getWorldObj().isRemote) {
            Object handler = Class.forName(PREFIX + "bspace.BSpaceStorageHandler")
                .getField("_instance")
                .get(null);
            if (handler == null) throw unsupported("shared storage is not available for read-only capture");
            storage = ((Map<?, ?>) get(handler, "storageMap")).get(get(tile, "id"));
        }
        if (storage == null || !storage.getClass()
            .getName()
            .equals(STORAGE)) throw unsupported("unknown or missing storage serializer");
        tag.setInteger("version", 5);
        tag.setInteger("orientation", ((Enum<?>) get(tile, "orientation")).ordinal());
        tag.setInteger("rotation", ((Enum<?>) get(tile, "rotation")).ordinal());
        tag.setIntArray("sideUpgrades", ((int[]) get(tile, "sideUpgrades")).clone());
        tag.setIntArray("sideMeta", ((int[]) get(tile, "sideMetadata")).clone());
        call(upgrades, "writeToNBT", new Class<?>[] { NBTTagCompound.class }, tag);
        tag.setBoolean("ticking", (Boolean) get(tile, "isTicking"));
        tag.setBoolean("linked", (Boolean) get(tile, "isLinked"));
        tag.setByte("nticks", (Byte) get(tile, "nTicks"));
        NBTTagCompound stored = (NBTTagCompound) ((NBTTagCompound) call(storage, "writeTagCompound", new Class<?>[0]))
            .copy();
        ItemStack prototype = (ItemStack) get(storage, "itemTemplate");
        if (prototype != null)
            NativeItemIds.write(stored.getCompoundTag("current_item"), Item.getIdFromItem(prototype.getItem()));
        tag.setTag("storage", stored);
        tag.setInteger("bspaceid", (Integer) get(tile, "id"));
        return tag;
    }

    private static final class Placement implements HologramNativePlacement.Adapter {

        public boolean supports(Block block) {
            return block.getClass()
                .getName()
                .equals(PREFIX + "common.blocks.BlockBarrel");
        }

        public boolean requiresPayload() {
            return true;
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) throws Exception {
            if (!tag.getString("id")
                .equals("TileEntityBarrel")) throw unsupported("wrong tile identity");
            TileEntity tile = block.createTileEntity(world, metadata);
            if (tile == null || !tile.getClass()
                .getName()
                .equals(TILE)) throw unsupported("wrong block factory");
            boolean retainedResources = false;
            for (String key : RESOURCES) retainedResources |= tag.hasKey(key);
            if (!retainedResources) {
                NBTTagCompound defaults = capturePure(tile);
                for (Object key : tag.func_150296_c()) defaults.setTag(
                    (String) key,
                    tag.getTag((String) key)
                        .copy());
                tag = defaults;
            }
            bill(tag);
            HologramNativePlacement.bindDetached(tile, world, tag);
            Object storage = get(tile, "storage");
            readStorage(storage, tag.getCompoundTag("storage"));
            Object upgrades = get(tile, "coreUpgrades");
            // Only this local upgrade helper is used; the barrel reader resolves global BSpace and schedules ticks.
            call(upgrades, "readFromNBT", new Class<?>[] { NBTTagCompound.class, int.class }, tag, 5);
            set(tile, "orientation", ForgeDirection.getOrientation(tag.getInteger("orientation")));
            set(tile, "rotation", ForgeDirection.getOrientation(tag.getInteger("rotation")));
            set(
                tile,
                "sideUpgrades",
                tag.getIntArray("sideUpgrades")
                    .clone());
            set(
                tile,
                "sideMetadata",
                tag.getIntArray("sideMeta")
                    .clone());
            set(tile, "isTicking", tag.getBoolean("ticking"));
            set(tile, "isLinked", tag.getBoolean("linked"));
            set(tile, "nTicks", tag.getByte("nticks"));
            set(tile, "id", tag.getInteger("bspaceid"));
            return tile;
        }

        public List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws Exception {
            List<ItemStack> result = new ArrayList<>();
            result.add(new ItemStack(block, 1, block.damageDropped(metadata)));
            result.addAll(bill(capturePure(detachedTile)));
            return result;
        }

        public TileEntity createRollback(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
            // These blocks previously used vanilla native recovery; do not apply safe-clone schema gates to originals.
            return TileEntity.createAndLoadEntity((NBTTagCompound) tag.copy());
        }

        public boolean includesInventoryDrops(Block block) {
            return true;
        }

        public void prepareActor(Block block, TileEntity tile, EntityPlayerMP actor) throws Exception {
            bill(capturePure(tile));
            // A reusable template creates a new local barrel. Rollback/raw decoding never enters this hook.
            set(tile, "id", -1);
        }

        public NBTTagCompound captureExpected(TileEntity tile) throws Exception {
            return capturePure(tile);
        }

        public void normalizeComparison(Block block, NBTTagCompound tag) {
            tag.removeTag("bspaceid");
        }
    }

    private static List<ItemStack> bill(NBTTagCompound root) throws IOException {
        try {
            keys(
                root,
                "id",
                "x",
                "y",
                "z",
                "version",
                "orientation",
                "rotation",
                "sideUpgrades",
                "sideMeta",
                "coreUpgrades",
                "structural",
                "redstone",
                "hopper",
                "ender",
                "void",
                "creative",
                "nStorageUpg",
                "ticking",
                "linked",
                "nticks",
                "storage",
                "bspaceid",
                "gtmb.nativePreview");
            if (root.hasKey("gtmb.nativePreview")
                && (!root.hasKey("gtmb.nativePreview", 1) || root.getByte("gtmb.nativePreview") < 0
                    || root.getByte("gtmb.nativePreview") > 1))
                throw unsupported("invalid preview marker");
            if (!root.hasKey("version", 3) || root.getInteger("version") != 5)
                throw unsupported("only the reviewed v5 persistence schema can be restored");
            require(root, "storage", 10);
            require(root, "coreUpgrades", 11);
            require(root, "sideUpgrades", 11);
            require(root, "sideMeta", 11);
            for (String name : new String[] { "structural", "nStorageUpg", "orientation", "rotation", "bspaceid" })
                require(root, name, 3);
            for (String name : new String[] { "redstone", "hopper", "ender", "void", "creative", "linked", "ticking" })
                require(root, name, 1);
            require(root, "nticks", 1);
            if (root.getBoolean("ender") || root.getBoolean("linked") || root.getBoolean("creative"))
                throw unsupported("shared, linked or creative storage is not a reusable local template");
            int orientation = root.getInteger("orientation"), rotation = root.getInteger("rotation");
            if (orientation < 0 || orientation > 6 || rotation < 0 || rotation > 6)
                throw unsupported("invalid barrel orientation");
            Object[] coreTypes = (Object[]) Class.forName(PREFIX + "common.items.upgrades.UpgradeCore")
                .getField("VALUES")
                .get(null);
            Object[] levels = (Object[]) Class.forName(PREFIX + "common.StructuralLevel")
                .getField("LEVELS")
                .get(null);
            int structural = root.getInteger("structural"), storageUpgrades = 0, used = 0;
            if (structural < 0 || structural >= levels.length) throw unsupported("unregistered structural level");
            Set<String> flags = new HashSet<>();
            List<ItemStack> result = new ArrayList<>();
            for (int level = 1; level <= structural; level++) result.add(item("itemUpgradeStructural", level - 1));
            for (int code : root.getIntArray("coreUpgrades")) {
                if (code < 0 || code >= coreTypes.length) throw unsupported("unregistered core upgrade");
                Object upgrade = coreTypes[code];
                String type = ((Enum<?>) get(upgrade, "type")).name();
                int slots = (Integer) get(upgrade, "slotsUsed");
                used = Math.addExact(used, slots);
                if (type.equals("STORAGE")) storageUpgrades = Math.addExact(storageUpgrades, slots);
                else flags.add(type.toLowerCase(java.util.Locale.ROOT));
                result.add(item("itemUpgradeCore", code));
            }
            if (used > (Integer) call(levels[structural], "getMaxCoreSlots", new Class<?>[0])
                || storageUpgrades != root.getInteger("nStorageUpg"))
                throw unsupported("inconsistent upgrade capacity");
            for (String flag : new String[] { "redstone", "hopper", "ender", "void", "creative" })
                if (root.getBoolean(flag) != flags.contains(flag)) throw unsupported("inconsistent core flag " + flag);
            int[] sides = root.getIntArray("sideUpgrades"), sideMeta = root.getIntArray("sideMeta");
            if (sides.length != 6 || sideMeta.length != 6) throw unsupported("invalid side upgrade array");
            for (int i = 0; i < sides.length; i++) {
                int side = sides[i];
                if (side < 0 || side > 4) throw unsupported("unregistered side upgrade");
                if (side >= 2) result.add(item("itemUpgradeSide", side - 2));
                if (side == 3 && !root.getBoolean("hopper") || side == 4 && !root.getBoolean("redstone"))
                    throw unsupported("side upgrade lacks its core upgrade");
                int amount = sideMeta[i] >> 1;
                if (side == 3 && amount != 1 && amount != 2 && amount != 4 && amount != 8 && amount != 16)
                    throw unsupported("invalid native hopper transfer setting");
                if (side == 4 && (sideMeta[i] < 0 || sideMeta[i] > 2))
                    throw unsupported("invalid native redstone setting");
            }
            NBTTagCompound storage = root.getCompoundTag("storage");
            keys(
                storage,
                "amount",
                "maxstacks",
                "upgCapacity",
                "current_item",
                "keepLastItem",
                "deleteExcess",
                "alwaysProvide");
            require(storage, "amount", 3);
            require(storage, "maxstacks", 3);
            require(storage, "upgCapacity", 3);
            for (String flag : new String[] { "keepLastItem", "deleteExcess", "alwaysProvide" })
                if (storage.hasKey(flag)) require(storage, flag, 1);
            if (storage.getBoolean("deleteExcess") != root.getBoolean("void")
                || storage.getBoolean("alwaysProvide") != root.getBoolean("creative"))
                throw unsupported("inconsistent storage policy");
            int baseStacks = Class.forName(PREFIX + "BetterBarrels")
                .getField("stacksSize")
                .getInt(null);
            long stacks = (long) baseStacks * (storageUpgrades + 1L);
            if (stacks <= 0 || stacks > Integer.MAX_VALUE
                || storage.getInteger("maxstacks") != stacks
                || storage.getInteger("upgCapacity") != storageUpgrades) throw unsupported("inconsistent storage size");
            int amount = storage.getInteger("amount");
            if (amount < 0) throw unsupported("negative stored amount");
            if (storage.hasKey("current_item")) {
                require(storage, "current_item", 10);
                ItemStack identity = template(storage.getCompoundTag("current_item"));
                if ((long) amount > stacks * identity.getMaxStackSize())
                    throw unsupported("stored amount exceeds capacity");
                if (amount > 0) {
                    identity.stackSize = amount;
                    result.add(identity);
                }
            } else if (amount != 0) throw unsupported("stored amount has no physical identity");
            return result;
        } catch (ReflectiveOperationException | ArithmeticException failure) {
            throw new IOException("JABBA reviewed schema is unavailable", failure);
        }
    }

    private static ItemStack item(String field, int damage) throws ReflectiveOperationException, IOException {
        Item item = (Item) Class.forName(PREFIX + "BetterBarrels")
            .getField(field)
            .get(null);
        if (item == null || Item.getItemById(Item.getIdFromItem(item)) != item)
            throw unsupported("unregistered upgrade item " + field);
        return new ItemStack(item, 1, damage);
    }

    private static ItemStack template(NBTTagCompound tag) throws IOException {
        keys(tag, "id", "idExt", "Count", "Damage", "tag");
        if (tag.hasKey("id") && !tag.hasKey("id", 2) && !tag.hasKey("id", 3)
            || tag.hasKey("idExt") && !tag.hasKey("idExt", 3)
            || !tag.hasKey("Count", 1)
            || !tag.hasKey("Damage", 2)
            || tag.hasKey("tag") && !tag.hasKey("tag", 10)) throw unsupported("invalid item encoding");
        int id = NativeItemIds.read(tag);
        Item identity = Item.getItemById(id);
        int damage = tag.getShort("Damage");
        if (id <= 0 || identity == null || damage < 0 || tag.getByte("Count") != 0)
            throw unsupported("unregistered item identity");
        ItemStack stack = new ItemStack(identity, 0, damage);
        if (tag.hasKey("tag")) stack.setTagCompound(
            (NBTTagCompound) tag.getCompoundTag("tag")
                .copy());
        return stack;
    }

    /** The reviewed local reader, with native item headers decoded through Core rather than an absent test mixin. */
    private static void readStorage(Object storage, NBTTagCompound tag)
        throws ReflectiveOperationException, IOException {
        set(storage, "totalAmount", tag.getInteger("amount"));
        set(storage, "maxstacks", tag.getInteger("maxstacks"));
        set(storage, "upgCapacity", tag.getInteger("upgCapacity"));
        set(storage, "keepLastItem", tag.getBoolean("keepLastItem"));
        set(storage, "deleteExcess", tag.getBoolean("deleteExcess"));
        set(storage, "alwaysProvide", tag.getBoolean("alwaysProvide"));
        ItemStack prototype = tag.hasKey("current_item") ? template(tag.getCompoundTag("current_item")) : null;
        call(storage, "setItem", new Class<?>[] { ItemStack.class }, prototype);
        int stackSize = (Integer) get(storage, "stackAmount");
        int remaining = (Integer) get(storage, "totalCapacity") - tag.getInteger("amount");
        if (!tag.getBoolean("deleteExcess") && remaining < stackSize) {
            ItemStack input = prototype.copy();
            input.stackSize = stackSize - remaining;
            set(storage, "inputStack", input);
            set(storage, "prevInputStack", input.copy());
        }
    }

    private static void keys(NBTTagCompound tag, String... allowed) throws IOException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (Object key : tag.func_150296_c()) if (!names.contains(key)) throw unsupported("unknown field " + key);
    }

    private static void require(NBTTagCompound tag, String key, int type) throws IOException {
        if (!tag.hasKey(key, type)) throw unsupported("invalid field type " + key);
    }

    private static Object get(Object source, String name) throws ReflectiveOperationException {
        return field(source.getClass(), name).get(source);
    }

    private static void set(Object source, String name, Object value) throws ReflectiveOperationException {
        field(source.getClass(), name).set(source, value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object source, String name, Class<?>[] parameters, Object... arguments)
        throws ReflectiveOperationException {
        Method method = source.getClass()
            .getMethod(name, parameters);
        return method.invoke(source, arguments);
    }

    private static IOException unsupported(String detail) {
        return new IOException("Unsupported JABBA blueprint: " + detail);
    }
}

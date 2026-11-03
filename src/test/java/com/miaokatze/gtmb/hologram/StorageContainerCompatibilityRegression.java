package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.compat.jabba.common.JabbaCompatibility;
import com.miaokatze.gtmb.compat.storagedrawers.common.StorageDrawersCompatibility;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

/** Exact optional native schemas in a small JVM; no real world, global network join or gameplay claim. */
public final class StorageContainerCompatibilityRegression {

    private StorageContainerCompatibilityRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(StorageContainerCompatibilityRegression.class, args)) return;
        jabba();
        drawers();
        System.out
            .println("GTMB_STORAGE_COMPAT PASS local counts/upgrades/pure capture/identity; no world or GL claim");
    }

    private static void jabba() throws Exception {
        String prefix = "mcp.mobius.betterbarrels.";
        Class<?> mod = Class.forName(prefix + "BetterBarrels");
        for (String name : new String[] { "itemUpgradeStructural", "itemUpgradeCore", "itemUpgradeSide" }) {
            Item item = new Item() {};
            registerItem("gtmb_test:jabba_" + name, item);
            mod.getField(name)
                .set(null, item);
        }
        mod.getField("stacksSize")
            .setInt(null, 64);
        Class<?> levelClass = Class.forName(prefix + "common.StructuralLevel");
        Object levels = java.lang.reflect.Array.newInstance(levelClass, 2);
        for (int i = 0; i < 2; i++) {
            Object level = allocate(levelClass);
            field(levelClass, "maxCoreSlots").setInt(level, i);
            java.lang.reflect.Array.set(levels, i, level);
        }
        levelClass.getField("LEVELS")
            .set(null, levels);
        Class<?> tileClass = Class.forName(prefix + "common.blocks.TileEntityBarrel");
        mapping(tileClass, "TileEntityBarrel");
        Block block = (Block) Class.forName(prefix + "common.blocks.BlockBarrel")
            .getConstructor()
            .newInstance();
        registerBlock("gtmb_test:jabba_barrel", block, new ItemBlock(block));
        HologramResourceWorld world = HologramResourceWorld.create(block, 0);
        TileEntity source = block.createTileEntity(world, 0);
        NBTTagCompound seed = barrelSeed();
        Object storage = field(tileClass, "storage").get(source);
        storage.getClass()
            .getMethod("readTagCompound", NBTTagCompound.class)
            .invoke(storage, seed.getCompoundTag("storage"));
        Object core = field(tileClass, "coreUpgrades").get(source);
        core.getClass()
            .getMethod("readFromNBT", NBTTagCompound.class, int.class)
            .invoke(core, seed, 5);
        field(tileClass, "id").setInt(source, 73);
        source.setWorldObj(world);
        Class<?> handlerClass = Class.forName(prefix + "bspace.BSpaceStorageHandler");
        Field singleton = handlerClass.getField("_instance");
        Object previousHandler = singleton.get(null);
        singleton.set(null, null);
        List<GTMBExtensions.Registration> handles = JabbaCompatibility.install();
        try {
            ordinaryShell(block);
            NBTTagCompound captured = HologramNativeCapture.capture(source);
            SimpleAssert.that(
                !HologramNativePlacement.supported(block, null),
                "a bare barrel retains the ordinary ItemBlock path");
            SimpleAssert.that(
                HologramNativePlacement.supported(block, new NBTTagCompound()),
                "present payload remains distinct from an absent payload");
            SimpleAssert.eq(73, field(tileClass, "id").getInt(source), "read-only capture preserves source BSpace id");
            SimpleAssert.that(singleton.get(null) == null, "capture does not initialize or update BSpace");
            SimpleAssert.eq(
                5000,
                captured.getCompoundTag("storage")
                    .getInteger("amount"),
                "physical amount is independent of template Count=0");
            List<ResourceContainers.RootClaim> claims = ResourceContainers.decodeRoots(captured);
            SimpleAssert.eq(
                5000,
                itemCount(
                    claims.get(0)
                        .items(),
                    Items.apple),
                "root codec prices the full physical amount");
            NBTTagCompound extended = (NBTTagCompound) captured.copy();
            NBTTagCompound template = extended.getCompoundTag("storage")
                .getCompoundTag("current_item");
            template.setShort("id", (short) Item.getIdFromItem(Items.stick));
            template.setInteger("idExt", Item.getIdFromItem(Items.apple));
            SimpleAssert.eq(
                5000,
                itemCount(
                    ResourceContainers.decodeRoots(extended)
                        .get(0)
                        .items(),
                    Items.apple),
                "JABBA native extended identity takes precedence without changing the separate quantity");
            List<ItemStack> full = HologramNativePlacement.materials(block, 0, captured);
            SimpleAssert.eq(5000, itemCount(full, Items.apple), "native invoice includes full inventory exactly once");
            SimpleAssert.that(
                HologramBlueprintResources.plan(captured, full).items.isEmpty(),
                "root claims credit the paid native invoice without duplication");
            HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(block);
            NBTTagCompound configurationOnly = new NBTTagCompound();
            configurationOnly.setString("id", "TileEntityBarrel");
            configurationOnly.setInteger("orientation", 2);
            TileEntity empty = adapter.create(block, 0, configurationOnly, world);
            SimpleAssert.eq(
                0,
                JabbaCompatibility.capturePure(empty)
                    .getCompoundTag("storage")
                    .getInteger("amount"),
                "omitting the complete resource group creates an empty local barrel without losing orientation");
            TileEntity clone = adapter.create(block, 0, captured, world);
            TileEntity extendedClone = adapter.create(block, 0, extended, world);
            SimpleAssert.eq(
                Item.getIdFromItem(Items.apple),
                NativeItemIds.read(
                    JabbaCompatibility.capturePure(extendedClone)
                        .getCompoundTag("storage")
                        .getCompoundTag("current_item")),
                "local hydration decodes extended item headers without relying on an instance mixin");
            template.setFloat("idExt", 1);
            rejected(extended, "JABBA rejects unknown floating extended headers");
            adapter.prepareActor(block, clone, null);
            SimpleAssert.eq(-1, field(tileClass, "id").getInt(clone), "safe clone has a new local identity");
            SimpleAssert.eq(73, field(tileClass, "id").getInt(source), "clone preparation preserves source identity");
            TileEntity recovered = HologramNativePlacement.rollbackTile(world, block, 0, captured);
            SimpleAssert.eq(
                73,
                field(tileClass, "id").getInt(recovered),
                "trusted native rollback preserves the original local identity");
            field(tileClass, "id").setInt(clone, 99);
            SimpleAssert.that(
                HologramNativePlacement.matchesPlaced(block, 0, clone, captured),
                "native-assigned local id does not invalidate equivalent paid contents");
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.getCompoundTag("storage")
                .setInteger("amount", -1);
            rejected(malformed, "negative quantities are rejected");
            malformed = (NBTTagCompound) captured.copy();
            malformed.setBoolean("ender", true);
            rejected(malformed, "shared storage does not become a reusable blueprint");
            Object handler = allocate(handlerClass);
            HashMap<Integer, Object> shared = new HashMap<>();
            shared.put(73, storage);
            field(handlerClass, "storageMap").set(handler, shared);
            field(handlerClass, "maxBarrelID").setInt(handler, 121);
            singleton.set(null, handler);
            field(core.getClass(), "hasEnder").setBoolean(core, true);
            NBTTagCompound raw = HologramNativeCapture.capture(source);
            SimpleAssert.that(
                raw.getBoolean("ender") && raw.getInteger("bspaceid") == 73,
                "shared capture preserves raw native identity and policy");
            SimpleAssert.eq(
                5000,
                raw.getCompoundTag("storage")
                    .getInteger("amount"),
                "shared capture reads its existing storage without repair");
            SimpleAssert.eq(
                121,
                field(handlerClass, "maxBarrelID").getInt(handler),
                "shared capture never allocates an identity");
            SimpleAssert.eq(1, shared.size(), "shared capture never changes global storage membership");
        } finally {
            close(handles);
            singleton.set(null, previousHandler);
        }
    }

    private static NBTTagCompound barrelSeed() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "TileEntityBarrel");
        tag.setInteger("version", 5);
        tag.setInteger("orientation", 2);
        tag.setInteger("rotation", 2);
        tag.setIntArray("sideUpgrades", new int[6]);
        tag.setIntArray("sideMeta", new int[6]);
        tag.setIntArray("coreUpgrades", new int[] { 0 });
        tag.setInteger("structural", 1);
        tag.setInteger("nStorageUpg", 1);
        for (String flag : new String[] { "redstone", "hopper", "ender", "void", "creative", "ticking", "linked" })
            tag.setBoolean(flag, false);
        tag.setByte("nticks", (byte) 0);
        tag.setInteger("bspaceid", 73);
        NBTTagCompound storage = new NBTTagCompound();
        storage.setInteger("amount", 5000);
        storage.setInteger("maxstacks", 128);
        storage.setInteger("upgCapacity", 1);
        storage.setTag("current_item", new ItemStack(Items.apple, 0).writeToNBT(new NBTTagCompound()));
        tag.setTag("storage", storage);
        return tag;
    }

    private static void drawers() throws Exception {
        String prefix = "com.jaquadro.minecraft.storagedrawers.";
        Class<?> mod = Class.forName(prefix + "StorageDrawers");
        Object config = Class.forName(prefix + "config.ConfigManager")
            .getConstructor(java.io.File.class)
            .newInstance(
                Files.createTempDirectory("gtmb-drawers-fixture-")
                    .resolve("drawers.cfg")
                    .toFile());
        config.getClass()
            .getMethod("syncConfig")
            .invoke(config);
        mod.getField("config")
            .set(null, config);
        Class<?> items = Class.forName(prefix + "core.ModItems");
        for (String name : new String[] { "upgrade", "upgradeStatus", "upgradeLock", "upgradeVoid", "upgradeCreative",
            "upgradeRedstone", "upgradeDowngrade" }) {
            Object item = items.getField(name)
                .getType()
                .getConstructor(String.class)
                .newInstance("gtmb_test_" + name);
            registerItem("gtmb_test:drawers_" + name, (Item) item);
            items.getField(name)
                .set(null, item);
            SimpleAssert.that(
                new ItemStack((Item) item).getItem() == item,
                "real upgrade constructors initialize the Forge registry delegate");
        }
        Class<?> tileClass = Class.forName(prefix + "block.tile.TileEntityDrawersStandard");
        mapping(tileClass, "StorageDrawers:tileDrawersStandard");
        Block block = (Block) Class.forName(prefix + "block.BlockDrawers")
            .getConstructor(String.class, int.class, boolean.class)
            .newInstance("fullDrawers1", 1, false);
        Item item = (Item) Class.forName(prefix + "item.ItemBasicDrawers")
            .getConstructor(Block.class)
            .newInstance(block);
        registerBlock("gtmb_test:drawers", block, item);
        HologramResourceWorld world = HologramResourceWorld.create(block, 0);
        TileEntity source = block.createTileEntity(world, 0);
        Object drawer = ((Object[]) field(tileClass, "drawers").get(source))[0];
        field(drawer.getClass(), "protoStack").set(drawer, new ItemStack(Items.apple));
        field(drawer.getClass(), "count").setInt(drawer, 500);
        field(tileClass, "drawerCapacity").setInt(source, 8);
        UUID owner = new UUID(5, 7);
        field(tileClass, "owner").set(source, owner);
        ItemStack upgrade = new ItemStack(
            (Item) items.getField("upgradeStatus")
                .get(null),
            1,
            1);
        ((ItemStack[]) field(tileClass, "upgrades").get(source))[0] = upgrade;
        List<GTMBExtensions.Registration> handles = StorageDrawersCompatibility.install();
        try {
            ordinaryShell(block);
            NBTTagCompound captured = HologramNativeCapture.capture(source);
            SimpleAssert.that(
                !HologramNativePlacement.supported(block, null),
                "bare drawers retain the ordinary ItemBlock path");
            SimpleAssert.eq(
                500,
                captured.getTagList("Slots", 10)
                    .getCompoundTagAt(0)
                    .getInteger("Count"),
                "capture retains the separate integer drawer count");
            List<ResourceContainers.RootClaim> claims = ResourceContainers.decodeRoots(captured);
            SimpleAssert.eq(
                500,
                itemCount(
                    claims.get(0)
                        .items(),
                    Items.apple),
                "drawer prototype Count=1 is not the physical quantity");
            SimpleAssert.eq(
                1,
                claims.get(1)
                    .items()
                    .size(),
                "real upgrade is billed independently of inventory");
            NBTTagCompound extended = (NBTTagCompound) captured.copy();
            NBTTagCompound prototype = extended.getTagList("Slots", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("ItemStack");
            prototype.setShort("id", (short) Item.getIdFromItem(Items.stick));
            prototype.setInteger("idExt", Item.getIdFromItem(Items.apple));
            NBTTagCompound upgradeRow = extended.getTagList("Upgrades", 10)
                .getCompoundTagAt(0);
            upgradeRow.setShort("id", (short) 0);
            upgradeRow.setInteger("idExt", Item.getIdFromItem(upgrade.getItem()));
            List<ResourceContainers.RootClaim> extendedClaims = ResourceContainers.decodeRoots(extended);
            SimpleAssert.eq(
                500,
                itemCount(
                    extendedClaims.get(0)
                        .items(),
                    Items.apple),
                "drawer extended prototype identity retains its independent integer quantity");
            SimpleAssert.eq(
                upgrade.getItem(),
                extendedClaims.get(1)
                    .items()
                    .get(0)
                    .getItem(),
                "extended upgrade identities resolve through Core native headers");
            SimpleAssert.eq(
                500,
                field(drawer.getClass(), "count").getInt(drawer),
                "capture and quoting leave the live count unchanged");
            SimpleAssert.eq(owner, field(tileClass, "owner").get(source), "capture preserves source owner");
            NBTTagCompound restorable = (NBTTagCompound) extended.copy();
            restorable.getTagList("Slots", 10)
                .getCompoundTagAt(0)
                .setInteger("Count", 7);
            HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(block);
            TileEntity clone = adapter.create(block, 0, restorable, world);
            Actor actor = allocate(Actor.class);
            actor.identity = new UUID(11, 13);
            adapter.prepareActor(block, clone, actor);
            SimpleAssert
                .eq(actor.identity, field(tileClass, "owner").get(clone), "safe owned clone binds the current actor");
            SimpleAssert.eq(owner, field(tileClass, "owner").get(source), "rebinding never changes source owner");
            SimpleAssert.that(
                HologramNativePlacement.matchesPlaced(block, 0, clone, restorable, actor),
                "pure readback matches physical contents and actor ownership");
            NBTTagCompound bad = (NBTTagCompound) captured.copy();
            bad.setString("Sec", "unreviewed-provider");
            rejected(bad, "unknown security providers are rejected without rewriting the source");
            TileEntity recovered = HologramNativePlacement.rollbackTile(world, block, 0, bad);
            SimpleAssert.eq(
                "unreviewed-provider",
                StorageDrawersCompatibility.capturePure(recovered)
                    .getString("Sec"),
                "safe-clone rejection does not block trusted native rollback of original security state");
            bad = (NBTTagCompound) captured.copy();
            NativeItemIds.write(
                bad.getTagList("Upgrades", 10)
                    .getCompoundTagAt(0),
                Item.getIdFromItem(
                    (Item) items.getField("upgradeCreative")
                        .get(null)));
            rejected(bad, "unlimited drawers are not finite inventory");
            upgradeRow.setFloat("idExt", 1);
            rejected(extended, "drawers reject floating extended upgrade headers");
            SimpleAssert.eq(
                500,
                field(drawer.getClass(), "count").getInt(drawer),
                "rejected templates preserve all source contents");
        } finally {
            close(handles);
        }
    }

    private static void rejected(NBTTagCompound tag, String message) throws Exception {
        try {
            ResourceContainers.decodeRoots(tag);
            throw new AssertionError(message);
        } catch (java.io.IOException expected) {}
    }

    private static void ordinaryShell(Block block) throws Exception {
        ItemStack shell = new ItemStack(block);
        HologramStaticMaterials.Builder theoretical = new HologramStaticMaterials.Builder();
        theoretical.add(block, 0, shell, null, null);
        SimpleAssert.that(
            theoretical.snapshot()
                .getBoolean("complete"),
            "unconfigured ItemBlock shell remains fully quoteable");
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.cells.add(HologramPresets.cell(shell, 0, 0, 0, -1, false));
        SimpleAssert.eq(
            1,
            itemCount(HologramAePolicy.materials(preset).items, shell.getItem()),
            "saved empty shell review charges exactly its original native ItemBlock");
        SimpleAssert
            .that(preset.aeIssues.isEmpty(), "registering a payload adapter does not mark empty shells unsupported");
        HologramSession session = allocate(HologramSession.class);
        session.virtual = true;
        session.virtualPreset = preset;
        HologramCapture.Cell cell = new HologramCapture.Cell(null, 0, 64, 0);
        cell.block = block;
        SimpleAssert.that(
            HologramCellPolicy.placeableSample(session, cell, shell),
            "empty-shell candidates retain the existing ItemBlock admission path");
    }

    private static int itemCount(List<ItemStack> items, Item type) {
        int count = 0;
        for (ItemStack item : items) if (item.getItem() == type) count += item.stackSize;
        return count;
    }

    private static void close(List<GTMBExtensions.Registration> handles) {
        for (int i = handles.size() - 1; i >= 0; i--) handles.get(i)
            .close();
    }

    private static void mapping(Class<?> type, String id) throws Exception {
        Method method = TileEntity.class.getDeclaredMethod("addMapping", Class.class, String.class);
        method.setAccessible(true);
        method.invoke(null, type, id);
    }

    private static void registerBlock(String name, Block block, Item item) throws Exception {
        int slot = freeSlot();
        add(Block.blockRegistry, slot, name, block);
        add(Item.itemRegistry, slot, name, item);
    }

    private static void registerItem(String name, Item item) throws Exception {
        add(Item.itemRegistry, freeSlot(), name, item);
    }

    private static int freeSlot() {
        int slot = 3500;
        while (Item.getItemById(slot) != null || Block.getBlockById(slot) != Blocks.air) slot--;
        return slot;
    }

    private static void add(Object registry, int slot, String name, Object value) throws Exception {
        Method method = cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry.class
            .getDeclaredMethod("add", int.class, String.class, Object.class, BitSet.class);
        method.setAccessible(true);
        method.invoke(registry, slot, name, value, new BitSet());
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
            Field field = current.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException absent) {}
        throw new NoSuchFieldException(name);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type
            .cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }

    private static final class Actor extends EntityPlayerMP {

        private UUID identity;

        private Actor() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return identity;
        }
    }
}

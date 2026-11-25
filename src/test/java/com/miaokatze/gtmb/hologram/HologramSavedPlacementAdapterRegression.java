// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;

/** Existing codecs, registry factories, authorization, source selection and ledger; only live transport and world storage are absent. */
final class HologramSavedPlacementAdapterRegression {

    private static final String ID = "local_41e99281-89fa-41fa-b61a-d7892cb3cffd";
    private static final String SIGN_TEXT = "原生施工验证";
    private static final int X = 0, Y = 64, Z = 0;

    private HologramSavedPlacementAdapterRegression() {}

    static void run() throws Exception {
        sign(false);
        sign(true);
        wireless(true);
        wireless(false);
    }

    private static void sign(boolean veto) throws Exception {
        HologramNativeSavedWorldRegression.withConnectedWorld(0, connected -> {
            EntityPlayerMP player = connected.nativeActor();
            sun.misc.Unsafe unsafe = unsafe();
            TestWorld world = TestWorld.create(unsafe);
            player.worldObj = world;
            HologramWorkspace.publishActionEndpoint(player, false);
            player.inventory.mainInventory[1] = new ItemStack(Items.sign, 1);
            var tag = new NBTTagCompound();
            tag.setString("id", "Sign");
            tag.setString("Text1", SIGN_TEXT);
            tag.setString("Text2", "second row");
            tag.setString("Text3", "");
            tag.setString("Text4", "");
            JsonObject sign = cell("minecraft:standing_sign", "minecraft:sign");
            sign.addProperty("metadata", 4);
            sign.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(Blocks.standing_sign, tag));

            Field eventBus = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object base = unsafe.staticFieldBase(eventBus);
            long offset = unsafe.staticFieldOffset(eventBus);
            EventBus prior = MinecraftForge.EVENT_BUS;
            if (veto) unsafe.putObjectVolatile(base, offset, new EventBus() {
                @Override
                public boolean post(Event event) {
                    if (event instanceof BlockEvent.PlaceEvent place && place.player == player) {
                        world.vetoed++;
                        return true;
                    }
                    return prior.post(event);
                }
            });
            try {
                var result = place(connected, player, sign);
                if (!veto) {
                    var actual = world.getTileEntity(X, Y, Z);
                    check(result.phase == HologramNativeLocalConstruction.Phase.COMPLETE && result.placed == 1
                        && result.satisfied == 0 && world.getBlock(X, Y, Z) == Blocks.standing_sign
                        && world.getBlockMetadata(X, Y, Z) == 4 && actual instanceof TileEntitySign
                        && SIGN_TEXT.equals(((TileEntitySign) actual).signText[0])
                        && "second row".equals(((TileEntitySign) actual).signText[1])
                        && actual.getWorldObj() == world && actual.xCoord == X && actual.yCoord == Y && actual.zCoord == Z
                        && stock(player, Items.sign) == 0 && !connected.pendingPayment(),
                        "native built-in sign restores exact variant/typed tile text and debits one sign: " + describe(result));
                } else {
                    check(result.phase == HologramNativeLocalConstruction.Phase.FAILED && result.placed == 0
                        && world.vetoed > 0 && world.getBlock(X, Y, Z) == Blocks.air
                        && world.getTileEntity(X, Y, Z) == null && stock(player, Items.sign) == 1
                        && !connected.pendingPayment(),
                        "Forge-rejected native tile restores air, removes the tile, refunds exact bill: " + describe(result));
                }
                System.out.println("[GTMB-NATIVE-SAVED-ADAPTER] sign " + (veto ? "veto+rollback" : "world+text+debit") + " PASS");
            } finally {
                if (veto) unsafe.putObjectVolatile(base, offset, prior);
            }
        });
    }

    static void realZtonesUsersSource(Path mixed) throws Exception {
        byte[] original=Files.readAllBytes(mixed);
        check(HologramBlueprintArtifact.hash(original).equals("26cdd671942dbc5b1bfb4f15a84f892403d07900a0a789116d0f11bb2fca9108"),
            "exact authorized mixed blueprint, not generated sample");
        Path converted=Files.createTempFile("gtmb-mixed-ztones-",".gtnb");
        try {
            Files.write(converted,NativeBlueprintMigration.convert(original).bytes);
            try(var definition=com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition.open(converted)){
                check(definition.cells()==123369,"original full file decoded");
                HologramNativeSavedWorldRegression.withConnectedWorld(0,connected->{
                    var draft=new HologramNativeBlueprintDraft(new NativeBlueprintEdits(definition));
                    EntityPlayerMP player=connected.nativeActor();
                    // Original class / original ItemDecoBlocks2 factory / original canonical names, registered into FML.
                    // No Ztones registry stand-ins, and no claim that this loads its complete FML lifecycle.
                    boolean extra=Boolean.getBoolean("gtmb.mixedExtraUtilities");
                    String[] names={"Ztones:tile.laveBlock","Ztones:tile.glaxx","ExtraUtilities:greenscreen"};
                    String[] classes={"com.riciJak.Ztones.block.MBlocks.BlockLave","com.riciJak.Ztones.block.VBlocks.BlockGlaxx","com.rwtema.extrautils.block.BlockGreenScreen"};
                    int covered=0;
                    int variants=0;
                    for(int k=0;k<(extra?3:2);k++){
                        Block block=(Block)Class.forName(classes[k]).getConstructor().newInstance();
                        Item item=(Item)Class.forName(k==2?"com.rwtema.extrautils.item.ItemBlockMetadata":"com.riciJak.Ztones.item.block.ItemDecoBlocks2").getConstructor(Block.class).newInstance(block);
                        check(k==2?"tile.extrautils:greenscreen".equals(block.getUnlocalizedName()):names[k].equals("Ztones:"+block.getUnlocalizedName()),"canonical name from original constructor");
                        int free=3500;while(free<4095&&(Block.getBlockById(free)!=null&&Block.getBlockById(free)!=Blocks.air||Item.getItemById(free)!=null))free++;
                        check(free<4095,"dedicated registry slot");
                        var cls=cpw.mods.fml.common.registry.GameData.class;
                        var main=cls.getDeclaredMethod("getMain");var b=cls.getDeclaredMethod("registerBlock",Block.class,String.class,int.class);
                        var i=cls.getDeclaredMethod("registerItem",Item.class,String.class,int.class);
                        main.setAccessible(true);b.setAccessible(true);i.setAccessible(true);
                        b.invoke(main.invoke(null),block,names[k],free);i.invoke(main.invoke(null),item,names[k],free);
                        int paid=0;
                        for(var e:draft.space().counts().entrySet()) {
                            var entry=draft.placementShape(e.getKey(),draft.space().wrapper(e.getKey()));
                            if(!names[k].equals(entry.block))continue;
                            check(entry.block()==block&&Item.getItemFromBlock(block)==item,"real registry binding");
                            var effective=draft.executionCell(e.getKey(),draft.space().wrapper(e.getKey()));
                            check(effective.tileNbt.isEmpty()&&effective.itemNbt.isEmpty()&&effective.facing==-1,"Ztones descriptor has no ignored state");
                            var row=cell(effective.block,effective.item);row.addProperty("damage",effective.damage);row.addProperty("metadata",effective.metadata);
                            TestWorld world=TestWorld.create(unsafe());player.worldObj=world;
                            HologramWorkspace.publishActionEndpoint(player,false);player.inventory.mainInventory[1]=new ItemStack(item,2,effective.damage);
                            var result=place(connected,player,row);
                            check(result.phase==HologramNativeLocalConstruction.Phase.COMPLETE&&result.placed==1&&world.getBlock(X,Y,Z)==block
                                &&world.getBlockMetadata(X,Y,Z)==effective.metadata&&stock(player,item)==1,
                                "original registered block placed and paid: "+names[k]+" metadata "+effective.metadata+" "+describe(result));
                            paid+=e.getValue();variants++;
                        }
                        check(paid==(k==0?112043:k==1?6190:2147),"actual mixed source descriptor total "+names[k]+": "+paid);
                        covered+=paid;
                    }
                    check(variants==(extra?4:2),"sample every represented literal metadata");
                    System.out.println("GTMB_MIXED_ZTONES 123369 original cells decoded; "+covered+" represented by "+variants+" actual source variants, tested one write and subtype debit for each; not full mixed-world placement");
                });
            }
        }finally{Files.deleteIfExists(converted);}
    }

    private static void wireless(boolean allowed) throws Exception {
        HologramNativeSavedWorldRegression.withConnectedWorld(0, connected -> {
            EntityPlayerMP player = connected.nativeActor();
            try (var me = new HologramProjectedMeFixture(player, allowed)) {
                player.inventory.mainInventory[1] = null;
                HologramMaterials.Settings sources = new HologramMaterials.Settings();
                sources.main = sources.containers = false;
                sources.priority = 2;
                HologramMaterials.write(player.getHeldItem(), sources);
                connected.arm();
                var result = place(connected, player, cell("minecraft:stone", "minecraft:stone"));
                check(result.phase == (allowed ? HologramNativeLocalConstruction.Phase.COMPLETE
                            : HologramNativeLocalConstruction.Phase.FAILED)
                    && result.placed == (allowed ? 1 : 0)
                    && me.stock == (allowed ? 2 : 3) && me.modulates == (allowed ? 1 : 0)
                    && connected.at(X, Y, Z, allowed ? Blocks.stone : Blocks.air)
                    && !connected.pendingPayment(), "saved-native wireless terminal permission and actual ME MODULATE: "
                        + describe(result) + " stock=" + me.stock + " modulates=" + me.modulates);
                System.out.println("[GTMB-NATIVE-SAVED-ADAPTER] wireless " + (allowed ? "world+MODULATE" : "denied+no-debit") + " PASS");
            }
        });
    }

    private static HologramNativeLocalConstruction.Result place(
        HologramNativeSavedWorldRegression.ConnectedWorld connected, EntityPlayerMP player, JsonObject cell)
        throws Exception {
        Path root = Files.createTempDirectory("gtmb-saved-adapter-fixture-");
        try {
            JsonObject header = new JsonObject();
            header.addProperty("schema", 2);
            header.addProperty("controllerType", "area");
            header.addProperty("id", ID);
            header.addProperty("name", "Saved adapter fixture");
            byte[] bytes = NativeBlueprintMigration.author(header, new JsonObject(), 1, i -> cell, () -> true).bytes;
            var stage = HologramNativeBlueprintStorage.prepareAuthoredSave(root, ID, bytes,
                HologramBlueprintArtifact.hash(bytes), NativeBlueprintCodecRegression.png(), () -> true);
            var receipt = HologramNativeBlueprintStorage.activate(root, stage, stage.revision, () -> true);
            try (var source = NativeBlueprintPairStore.open(root, ID)) {
                var draft = new HologramNativeBlueprintDraft(new NativeBlueprintEdits(source));
                String toolId = new UUID(4, 389).toString();
                if (!player.getHeldItem().hasTagCompound()) player.getHeldItem().setTagCompound(new NBTTagCompound());
                player.getHeldItem().getTagCompound().setString(HologramSessionKeys.TOOL_ID, toolId);
                var attempt = new HologramNativeLocalConstruction.Attempt(1);
                var plan = HologramNativeLocalConstruction.prepare(draft, receipt, () -> true);
                HologramNativeLocalConstruction.submit(attempt, plan, () -> player, toolId, player.dimension,
                    new int[] { X, Y, Z }, new int[0],
                    HologramProjectedMainPolicy.settingsKey(HologramMaterials.read(player.getHeldItem())), false);
                connected.stepNative();
                return attempt.result();
            }
        } finally {
            HologramNativeLocalConstruction.stopServer();
            try (var paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static JsonObject cell(String block, String item) {
        JsonObject cell = new JsonObject();
        cell.addProperty("x", 0);
        cell.addProperty("y", 0);
        cell.addProperty("z", 0);
        cell.addProperty("block", block);
        cell.addProperty("item", item);
        return cell;
    }

    private static int stock(EntityPlayerMP player, Item item) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static String describe(HologramNativeLocalConstruction.Result result) {
        return result.phase + " placed=" + result.placed + ", satisfied=" + result.satisfied + ", " + result.detail;
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** Minimal storage fake: creation and deserialization still use the production registry's actual TileEntity. */
    private static final class TestWorld extends HologramServiceEditRegression.ReadOnlyWorld {

        private static final class State {
            final Block block;
            final int metadata;

            State(Block block, int metadata) {
                this.block = block;
                this.metadata = metadata;
            }
        }
        private Map<String, State> placed;
        int vetoed;

        static TestWorld create(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
            TestWorld world = (TestWorld) unsafe.allocateInstance(TestWorld.class);
            world.placed = new HashMap<>();
            world.occupants = new HashMap<>();
            world.placed.put(key(X, Y - 1, Z), new State(Blocks.stone, 0));
            set(world, "provider", new WorldProviderSurface());
            set(world, "worldAccesses", new ArrayList<>());
            set(world, "perWorldStorage", new MapStorage(null));
            world.capturedBlockSnapshots = new ArrayList<>();
            return world;
        }

        private static void set(World world, String field, Object value) throws ReflectiveOperationException {
            Field declared = World.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(world, value);
        }

        private static String key(int x, int y, int z) {
            return HologramPlacementFixtureWorld.position(x, y, z);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            var state = placed.get(key(x, y, z));
            return state == null ? Blocks.air : state.block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            var state = placed.get(key(x, y, z));
            return state == null ? 0 : state.metadata;
        }

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            return getBlock(x, y, z) == Blocks.air;
        }

        @Override
        public boolean canPlaceEntityOnSide(Block block, int x, int y, int z, boolean replace,
            int side, Entity entity, ItemStack stack) {
            return block != null && block != Blocks.air && blockExists(x, y, z) && isAirBlock(x, y, z)
                && getBlock(x, y - 1, z) == Blocks.stone && getTileEntity(x, y, z) == null;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
            String at = key(x, y, z);
            if (block == Blocks.air) {
                placed.remove(at);
                removeTileEntity(x, y, z);
            } else {
                if (getBlock(x, y, z) != block) removeTileEntity(x, y, z);
                placed.put(at, new State(block, metadata));
                if (block.hasTileEntity(metadata) && getTileEntity(x, y, z) == null) {
                    TileEntity tile = block.createTileEntity(this, metadata);
                    if (tile != null) setTileEntity(x, y, z, tile);
                }
            }
            return true;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
            Block block = getBlock(x, y, z);
            if (block == Blocks.air) return false;
            placed.put(key(x, y, z), new State(block, metadata));
            return true;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            tile.setWorldObj(this);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            tile.validate();
            occupants.put(key(x, y, z), tile);
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            TileEntity last = occupants.remove(key(x, y, z));
            if (last != null) last.invalidate();
        }

        @Override
        public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}

        @Override
        public void func_147453_f(int x, int y, int z, Block block) {}

        @Override
        public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}
    }
}

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;

/**
 * Separate JVM: the real LCR definition captures registry identities at class initialization. No game process or GL.
 */
public final class HologramIndustrialConstraintsRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramIndustrialConstraintsRegression.class, args)) return;
        String originalThreadName = Thread.currentThread()
            .getName();
        // Forge 1.7.10 effective-side analysis uses this exact thread name; the bootstrap's log side is insufficient.
        Thread.currentThread()
            .setName("Server thread");
        SimpleAssert.that(
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .getEffectiveSide()
                .isServer(),
            "native GT static initialization runs on the effective server side");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Block oldCoils = GregTechAPI.sBlockCasings5, oldCasings = GregTechAPI.sBlockCasings8;
        try {
            GregTechAPI.sBlockCasings5 = registered(new CoilBlock(), 4051, "constraint_coils");
            GregTechAPI.sBlockCasings8 = registered(
                new Block(net.minecraft.block.material.Material.iron) {},
                4052,
                "constraint_casings");
            MTELargeChemicalReactor controller = new MTELargeChemicalReactor("constraint_lcr");
            IStructureElement<?> slot = null, leaf = null;
            int slotCount = 0;
            for (IStructureElement<?> element : controller.getStructureDefinition()
                .getStructureFor("main")) {
                if (element.isNavigating() || !HologramIndustrialConstraints.coilSlot(element)) continue;
                slotCount++;
                slot = element;
                for (IStructureElement<?> part : HologramElementCatalog.parts(element))
                    if (HologramIndustrialConstraints.lcrCoil(part)) leaf = part;
            }
            SimpleAssert.eq(5, slotCount, "actual LCR declaration offers five coil positions, not five required coils");
            net.minecraft.nbt.NBTTagList capabilities = HologramCapabilities
                .describe(controller, new ItemStack(Blocks.stone), null)
                .toNBT();
            boolean hasCoilOptions = false;
            for (int row = 0; row < capabilities.tagCount(); row++) {
                NBTTagCompound capability = capabilities.getCompoundTagAt(row);
                if ("coil".equals(capability.getString("id")))
                    hasCoilOptions = "coil".equals(capability.getString("kind")) && capability.getTagList("options", 10)
                        .tagCount() == 15;
            }
            SimpleAssert.that(hasCoilOptions, "actual LCR receives its required named coil grade control");
            FixtureWorld world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            world.blocks = new HashMap<>();
            world.metas = new HashMap<>();
            world.tiles = new HashMap<>();
            for (int raw : new int[] { 1, 2, 3, 14, 3, 1 }) {
                ItemStack trigger = new ItemStack(Blocks.stone);
                ChannelDataAccessor.setChannelData(trigger, "coil", raw);
                HologramCapture capture = capture(controller, slot, trigger);
                HologramIndustrialConstraints.bind(capture, world, Collections.emptyMap(), false);
                SimpleAssert.that(!capture.incomplete, "bounded coil grade capture is complete");
                SimpleAssert.eq(
                    1L,
                    capture.cells.stream()
                        .filter(cell -> "coil".equals(cell.family))
                        .count(),
                    "every upgrade/downgrade retains exactly one coil");
                HologramCapture.Cell selected = capture.cells.stream()
                    .filter(cell -> "coil".equals(cell.family))
                    .findFirst()
                    .get();
                ItemStack nativeHint = leaf
                    .getBlocksToPlace(null, world, 0, 64, 0, ChannelDataAccessor.withChannel(trigger, "coil"), null)
                    .getStacks()
                    .iterator()
                    .next();
                SimpleAssert.eq(
                    nativeHint.getItemDamage(),
                    selected.meta,
                    "default target equals the real LCR enum's native coil signal mapping");
                SimpleAssert.eq("block", selected.role, "required coil cannot become an automatic hatch slot");
                nativeCoilCheck(controller, leaf, selected, world);
                HologramDeclarationCandidates alternatives = new HologramDeclarationCandidates();
                alternatives.collect(slot, controller, world, 0, 64, 0, trigger, null, 0);
                SimpleAssert.eq(
                    14L,
                    alternatives.stacks.stream()
                        .filter(HologramIndustrialConstraints::heatingCoil)
                        .count(),
                    "reviewed enum exposes all real coil grades");
            }
            world.tiles.put(0, new net.minecraft.tileentity.TileEntity());
            HologramCapture occupied = capture(controller, slot, new ItemStack(Blocks.stone));
            HologramIndustrialConstraints.bind(occupied, world, Collections.emptyMap(), false);
            SimpleAssert
                .eq("coil", occupied.cells.get(1).family, "required coil does not take an occupied hatch/device slot");
            world.tiles.clear();
            world.blocks.put(3, GregTechAPI.sBlockCasings5);
            world.metas.put(3, 7);
            SimpleAssert.eq(
                7,
                GregTechAPI.sBlockCasings5.getDamageValue(world, 3, 64, 0),
                "fixture mirrors native GT coil pick-block metadata");
            SimpleAssert.eq(
                7,
                Item.getItemFromBlock(GregTechAPI.sBlockCasings5)
                    .getMetadata(7),
                "fixture mirrors native GT casing item subtype metadata");
            HologramCapture existing = capture(controller, slot, new ItemStack(Blocks.stone));
            HologramIndustrialConstraints.bind(existing, world, Collections.emptyMap(), false);
            SimpleAssert.eq("coil", existing.cells.get(3).family, "default keeps the real installed coil position");
            SimpleAssert.eq(7, existing.cells.get(3).meta, "default preserves the installed coil material and grade");
            world.blocks.put(2, GregTechAPI.sBlockCasings5);
            HologramCapture duplicate = capture(controller, slot, new ItemStack(Blocks.stone));
            HologramIndustrialConstraints.bind(duplicate, world, Collections.emptyMap(), false);
            SimpleAssert.that(duplicate.incomplete, "two real coils cannot be advertised as a valid LCR preset");
            world.blocks.clear();
            world.metas.clear();
            Map<String, ItemStack> conflictingPins = new HashMap<>();
            conflictingPins.put("1,64,0", new ItemStack(GregTechAPI.sBlockCasings5));
            conflictingPins.put("2,64,0", new ItemStack(GregTechAPI.sBlockCasings5));
            HologramCapture pinned = capture(controller, slot, new ItemStack(Blocks.stone));
            HologramIndustrialConstraints.bind(pinned, world, conflictingPins, false);
            SimpleAssert
                .that(pinned.incomplete, "two explicit planned coils cannot be advertised as a valid LCR preset");
            SimpleAssert.eq(
                0,
                counter(controller, "mCoilAmount"),
                "read-only constraint binding never runs the live coil check");
            SimpleAssert.eq(
                8,
                HologramIndustrialConstraints.minimumCasings(controller),
                "source check requires at least eight inert casings");
            SimpleAssert.eq(
                0,
                HologramIndustrialConstraints.minimumCasings(new Object()),
                "unreviewed machine names confer no constraints");
            SimpleAssert.eq(
                0,
                HologramHatchPreset.choose(new int[] { 1, 2, 3 }, new int[] { 0, 0, 0 }, 0, false),
                "ULV request rises to the declared hatch minimum");
            SimpleAssert.eq(
                2,
                HologramHatchPreset.choose(new int[] { 1, 2, 3 }, new int[] { 0, 0, 0 }, 14, false),
                "high request falls to the declared hatch maximum");
            materialRoundTrip(slot, unsafe);
            System.out.println(
                "[GTMB-INDUSTRIAL-CONSTRAINTS] FINAL PASS real LCR enum/default/grades + native casing snapshot roundtrip");
        } finally {
            GregTechAPI.sBlockCasings5 = oldCoils;
            GregTechAPI.sBlockCasings8 = oldCasings;
            Thread.currentThread()
                .setName(originalThreadName);
        }
    }

    private static void materialRoundTrip(IStructureElement<?> slot, sun.misc.Unsafe unsafe) throws Exception {
        int id = 248;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[id];
        ITexture[] originalPage = Textures.BlockIcons.casingTexturePages[1];
        try {
            GregTechAPI.METATILEENTITIES[id] = (ProbeHatch) unsafe.allocateInstance(ProbeHatch.class);
            ItemStack target = new ItemStack(new ItemMachines(Blocks.stone), 1, id);
            String captured = HologramDisplaySnapshot.retainCasing(target, slot, "");
            NBTTagCompound saved = HologramBlueprintNbt.decode(captured);
            SimpleAssert.eq(id, (int) saved.getShort("mID"), "new native hatch persistence retains its actual type");
            saved.setString("functionalCache", "must be filtered");
            String filtered = HologramBlueprintNbt
                .filterCaptured(HologramBlueprintNbt.encodeCaptured(saved), Collections.emptyList(), false);
            SimpleAssert.that(
                !HologramBlueprintNbt.decode(filtered)
                    .hasKey("functionalCache"),
                "visual persistence does not bypass device NBT selection");
            NBTTagCompound display = HologramDisplaySnapshot.target(target, 2, null, filtered, false);
            SimpleAssert.eq(1, display.getInteger("texturePage"), "blueprint retains LCR native casing page");
            SimpleAssert.eq(48, display.getInteger("textureIndex"), "blueprint retains chemically inert casing index");
            ITexture texture = (ITexture) Proxy.newProxyInstance(
                ITexture.class.getClassLoader(),
                new Class<?>[] { ITexture.class },
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
            Textures.BlockIcons.casingTexturePages[1] = new ITexture[128];
            Textures.BlockIcons.casingTexturePages[1][48] = texture;
            Method resolve = Class.forName("com.miaokatze.gtmb.client.hologram.HologramMachineTextures")
                .getDeclaredMethod("resolve", IMetaTileEntity.class, int.class, int[].class, NBTTagCompound.class);
            resolve.setAccessible(true);
            ITexture[][] actual = (ITexture[][]) resolve
                .invoke(null, GregTechAPI.METATILEENTITIES[id], 2, new int[] { 0, 1, 2, 3, 4, 5 }, display);
            for (int side = 0; side < 6; side++) SimpleAssert.that(
                actual[side][0] == texture,
                "saved snapshot reaches the hatch's native casing material on every face");
            NBTTagCompound stale = new NBTTagCompound();
            stale.setInteger("texturePage", 0);
            stale.setInteger("textureIndex", 0);
            NBTTagCompound corrected = HologramDisplaySnapshot
                .target(target, 2, slot, HologramBlueprintNbt.encodeCaptured(stale), false);
            SimpleAssert.eq(
                48,
                corrected.getInteger("textureIndex"),
                "declared target material survives an unformed source's default skin");
        } finally {
            GregTechAPI.METATILEENTITIES[id] = previous;
            Textures.BlockIcons.casingTexturePages[1] = originalPage;
        }
    }

    private static int counter(Object controller, String name) throws Exception {
        Field field = controller.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(controller);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void nativeCoilCheck(MTELargeChemicalReactor controller, IStructureElement leaf,
        HologramCapture.Cell selected, FixtureWorld world) throws Exception {
        Field amount = MTELargeChemicalReactor.class.getDeclaredField("mCoilAmount");
        Field level = MTELargeChemicalReactor.class.getDeclaredField("mHeatingCoilLevel");
        amount.setAccessible(true);
        level.setAccessible(true);
        amount.setInt(controller, 0);
        level.set(controller, HeatingCoilLevel.None);
        world.blocks.put(selected.x, selected.block);
        world.metas.put(selected.x, selected.meta);
        SimpleAssert.that(
            leaf.check(controller, world, selected.x, selected.y, selected.z),
            "default coil passes the real detached LCR enum check");
        SimpleAssert.that(
            !leaf.check(controller, world, selected.x, selected.y, selected.z),
            "real detached LCR enum rejects a second coil");
        amount.setInt(controller, 0);
        level.set(controller, HeatingCoilLevel.None);
        world.blocks.clear();
        world.metas.clear();
    }

    private static HologramCapture capture(Object controller, IStructureElement<?> slot, ItemStack trigger) {
        HologramCapture capture = new HologramCapture(controller, trigger);
        for (int x = 0; x < 5; x++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(slot, x, 64, 0, trigger);
            cell.block = GregTechAPI.sBlockCasings8;
            cell.meta = 0;
            cell.role = "hatch";
            capture.cells.add(cell);
        }
        return capture;
    }

    private static Block registered(Block block, int id, String name) {
        Block.blockRegistry.addObject(id, "gtmb:" + name, block);
        Item.itemRegistry.addObject(id, "gtmb:" + name, new ItemBlock(block) {

            @Override
            public int getMetadata(int damage) {
                return damage;
            }
        });
        return block;
    }

    private static final class CoilBlock extends Block implements IHeatingCoil {

        CoilBlock() {
            super(net.minecraft.block.material.Material.iron);
        }

        @Override
        public int damageDropped(int metadata) {
            return metadata;
        }

        public HeatingCoilLevel getCoilHeat(int meta) {
            for (int tier = 0; tier <= HeatingCoilLevel.getMaxTier(); tier++) {
                HeatingCoilLevel level = HeatingCoilLevel.getFromTier((byte) tier);
                if (BlockCasings5.getMetaFromCoilHeat(level) == meta) return level;
            }
            return HeatingCoilLevel.None;
        }
    }

    private static final class ProbeHatch extends MTEHatchInput {

        private ProbeHatch() {
            super("constraint_hatch", 0, new String[0], new ITexture[0][][]);
        }

        public ITexture[] getTexturesInactive(ITexture background) {
            return new ITexture[] { background };
        }

        public ITexture[] getTexturesActive(ITexture background) {
            return new ITexture[] { background };
        }
    }

    private static final class FixtureWorld extends WorldServer {

        Map<Integer, Block> blocks;
        Map<Integer, Integer> metas;
        Map<Integer, net.minecraft.tileentity.TileEntity> tiles;

        private FixtureWorld() {
            super(null, null, "constraint fixture", 0, null, null);
        }

        public Block getBlock(int x, int y, int z) {
            return blocks.getOrDefault(x, Blocks.air);
        }

        public int getBlockMetadata(int x, int y, int z) {
            return metas.getOrDefault(x, 0);
        }

        public net.minecraft.tileentity.TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(x);
        }

        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("constraint preview must not modify world");
        }
    }
}

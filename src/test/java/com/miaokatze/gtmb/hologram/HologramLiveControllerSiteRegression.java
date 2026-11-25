// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the actual right-click local projection capture against loaded chunks, not pre-written display rows. */
public final class HologramLiveControllerSiteRegression {
    public static void main(String[] args) {
        int rc = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramLiveControllerSiteRegression.class, args)) return;
            verify();
            System.out.println("LiveControllerSite PASS: actual client-world capture; installed/empty/partial/unknown, channel evidence and preview default");
        } catch (Throwable failure) {
            failure.printStackTrace();
            rc = 1;
        }
        System.exit(rc);
    }

    public static final class Controller extends TileEntity implements IConstructable {
        @Override public void construct(ItemStack stack, boolean hints) {
            int[] offsets = {1, 2, 3, 20};
            for (int dx : offsets) worldObj.setBlock(xCoord + dx, yCoord, zCoord, Blocks.wool, 3, 3);
        }
        @Override public String[] getStructureDescription(ItemStack stack) { return new String[0]; }
    }

    private static final class SiteWorld extends HologramResourceWorld {
        private boolean populated;
        private TileEntity installedHatch;
        SiteWorld() { super(Blocks.stone, 0); isRemote = true; }
        @Override boolean previewChunkExists(int x, int z) { return x == 0 && z == 0; }
        @Override public Chunk getChunkFromChunkCoords(int x, int z) {
            if (!previewChunkExists(x,z)) throw new AssertionError("forced unavailable chunk");
            return new Chunk(this, x, z);
        }
        @Override public boolean blockExists(int x,int y,int z) { return previewChunkExists(x>>4,z>>4); }
        @Override public TileEntity getTileEntity(int x,int y,int z) {
            return x == 2 && y == 64 && z == 4 ? tile : x == 4 && y == 64 && z == 4 ? installedHatch : null;
        }
        @Override public Block getBlock(int x,int y,int z) {
            if (!blockExists(x,y,z)) throw new AssertionError("unloaded world read");
            return y != 64 || z != 4 ? Blocks.air : x == 2 ? Blocks.stone
                : x == 4 && installedHatch != null ? Blocks.furnace
                : populated && (x == 3 || x == 4) ? Blocks.wool : Blocks.air;
        }
        @Override public int getBlockMetadata(int x,int y,int z) { return x == 3 ? 3 : x == 4 ? 4 : 0; }
    }

    private static void verify() throws Exception {
        SiteWorld world = HologramResourceWorld.create(SiteWorld::new);
        Controller tile = new Controller();
        tile.setWorldObj(world); tile.xCoord = 2; tile.yCoord = 64; tile.zCoord = 4; world.tile = tile;
        SimpleAssert.that(HologramLocalControllerCapture.supports(tile), "formal controller route accepts constructable");
        for (boolean populated : new boolean[] {true,false}) {
            world.populated = populated;
            ItemStack tool = new ItemStack(Items.stick, 1);
            NBTTagCompound settings = HologramLocalControllerCapture.toolSettings(tool);
            try (HologramLocalControllerCapture capture = new HologramLocalControllerCapture(tile,0,2,tool,settings,"")) {
                HologramControllerTarget.Projection target = null;
                for (int i = 0; i < 100 && target == null; i++) target = capture.stepTarget(128, 1000000000L);
                SimpleAssert.that(target != null, "formal target phase publishes");
                NBTTagCompound metadata = target.metadata();
                SimpleAssert.eq(populated ? 2 : 0, metadata.getInteger("siteOccupied"), "occupied cells");
                SimpleAssert.eq(populated ? 1 : 0, metadata.getInteger("siteMatching"), "matches");
                SimpleAssert.eq(populated ? 1 : 0, metadata.getInteger("siteDifferent"), "wrong installed metadata");
                SimpleAssert.eq(populated ? 1 : 3, metadata.getInteger("siteMissing"), "loaded empty sites");
                SimpleAssert.eq(1, metadata.getInteger("siteUnknown"), "unloaded site, never marked empty");
                SimpleAssert.eq(populated ? 1 : 0, view(metadata, false), "first view of right-clicked site");
                SimpleAssert.eq(2, view(metadata,true), "never clobber an already selected difference view");
                NBTTagCompound done = null;
                for (int i = 0; i < 100 && done == null; i++) done = capture.stepDetails(128, 1000000000L);
                SimpleAssert.that(done != null && done.getInteger("siteOccupied") == metadata.getInteger("siteOccupied"),
                    "detail publication does not overwrite live evidence");
            }
        }
        // Real GT meta tile with a live attached energy hatch. Vanilla furnace is the offline TE-capable host block.
        var uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        var unsafe = (sun.misc.Unsafe)uf.get(null);
        var installed = (gregtech.api.metatileentity.implementations.MTEHatchEnergy)
            unsafe.allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchEnergy.class);
        var tierField = gregtech.api.metatileentity.implementations.MTETieredMachineBlock.class.getDeclaredField("mTier");
        unsafe.putByte(installed, unsafe.objectFieldOffset(tierField), (byte)4);
        var base = new gregtech.api.metatileentity.BaseMetaTileEntity();
        base.xCoord = 4; base.yCoord = 64; base.zCoord = 4; base.setWorldObj(world);
        base.setMetaTileID((short)44);
        var faceField = gregtech.api.metatileentity.BaseMetaTileEntity.class.getDeclaredField("mFacing");
        faceField.setAccessible(true); faceField.set(base,net.minecraftforge.common.util.ForgeDirection.WEST);
        installed.setBaseMetaTileEntity(base);
        world.installedHatch = base;
        ItemStack projector = new ItemStack(Items.stick);
        try (HologramLocalControllerCapture capture = new HologramLocalControllerCapture(tile,0,2,projector,
            HologramLocalControllerCapture.toolSettings(projector),"")) {
            HologramControllerTarget.Projection projection = null;
            for (int i=0; i<100 && projection==null; i++) projection=capture.stepTarget(128,1000000000L);
            SimpleAssert.that(projection!=null, "live hatch capture completed");
            SimpleAssert.eq(1,projection.metadata().getInteger("siteHatches"), "installed MTE hatch counted, not the target");
            var row = projection.cells().stream().filter(c -> c.dx()==2).findFirst().get();
            SimpleAssert.eq(44,row.actualStack().getItemDamage(), "actual MTE ID");
            SimpleAssert.eq(4,row.actualDisplay().getInteger("siteHatchTier"), "actual MTE tier");
            SimpleAssert.eq(net.minecraftforge.common.util.ForgeDirection.WEST.ordinal(),row.actualFacing(), "actual MTE facing");
            SimpleAssert.that(row.targetBlock()==Blocks.wool, "the different plan remains independently editable");
        }
        var tiers = StructureUtility.<Object,Integer>ofBlocksTiered((block, meta) -> meta,
            Arrays.asList(Pair.of(Blocks.wool, 3), Pair.of(Blocks.wool, 4)), 0, (obj, value) -> {}, obj -> 0);
        IStructureElement<?> signal = StructureUtility.withChannel("sample_channel",tiers);
        HologramLocalControllerSite ambiguous = new HologramLocalControllerSite();
        ambiguous.observe(signal, false, Blocks.wool, 4, new ItemStack(Blocks.wool,1,4), -1,
            Blocks.wool,3,new ItemStack(Blocks.wool,1,3),-1,false,false);
        var branched = StructureUtility.withChannel("sample_channel", StructureUtility.ofChain(tiers, tiers));
        ambiguous.observe(branched, false, Blocks.wool, 4, new ItemStack(Blocks.wool,1,4), -1,
            Blocks.wool,3,new ItemStack(Blocks.wool,1,3),-1,false,false);
        NBTTagCompound ambiguity = new NBTTagCompound(); ambiguous.publish(ambiguity);
        SimpleAssert.that(!ambiguity.getCompoundTag("siteChannels").hasKey("sample_channel"),
            "a branching declaration never borrows its first tier table or a neighbouring value");
        HologramLocalControllerSite site = new HologramLocalControllerSite();
        for (int meta : new int[] {4,4,3}) {
            site.observe(signal, false, Blocks.wool, meta, new ItemStack(Blocks.wool,1,meta), -1,
                Blocks.wool,3,new ItemStack(Blocks.wool,1,3),-1,false,false);
            NBTTagCompound observed = new NBTTagCompound(); site.publish(observed);
            SimpleAssert.eq(meta == 4 ? 2 : 0, observed.getCompoundTag("siteChannels").getInteger("sample_channel"),
                "real StructureLib channel tier; inconsistent physical observations never report a value");
        }
    }

    private static int view(NBTTagCompound snapshot, boolean manual) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.client.hologram.HologramUiPreview");
        var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Object ui = ctor.newInstance((Object)null);
        var choose = type.getDeclaredMethod("chooseControllerSiteView", NBTTagCompound.class); choose.setAccessible(true);
        if (manual) { var set = type.getDeclaredMethod("view", int.class); set.setAccessible(true); set.invoke(ui,2); }
        choose.invoke(ui,snapshot);
        var value = type.getDeclaredField("view"); value.setAccessible(true); return value.getInt(ui);
    }
}

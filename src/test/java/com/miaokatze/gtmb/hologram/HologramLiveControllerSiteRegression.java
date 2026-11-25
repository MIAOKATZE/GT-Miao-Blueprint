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
        verifyLarge();
        verifyInstalledVariant();
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

    private static void verifyInstalledVariant() throws Exception {
        SimpleAssert.eq(0, HologramInstalledControllerShape.installedGroups(0, 0), "unknown client state");
        SimpleAssert.eq(0, HologramInstalledControllerShape.installedGroups(0x43, 64), "unformed client");
        SimpleAssert.eq(0, HologramInstalledControllerShape.installedGroups(0x53, 0), "invalid extended variant");
        SimpleAssert.eq(5, HologramInstalledControllerShape.installedGroups(0x43, 0), "synchronized extended variant");
        SimpleAssert.eq(2, HologramInstalledControllerShape.selectedGroups(2), "target stays at selected variant");

        // Actual native array and its navigation transforms, not a bounding box or an invented prefix.
        String[][] structure = { {"~XXX A", "XX X  ", "X  XX "} };
        var definition = com.gtnewhorizon.structurelib.structure.StructureDefinition.<Object>builder()
            .addShape("installed", structure)
            .addElement('X', StructureUtility.ofBlock(Blocks.wool, 3))
            .addElement('A', StructureUtility.isAir()).build();
        var facing = com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT;
        var nativeShape = new HologramInstalledControllerShape(definition.getStructureFor("installed"), 0, 0, 0, 5);
        java.util.List<HologramControllerTarget.Cell> installed = new java.util.ArrayList<>();
        installed.add(variantFixtureCell(0, 0, 0, true, true));
        while (nativeShape.hasNext()) {
            var p = nativeShape.next(facing);
            if (p != null) installed.add(variantFixtureCell(p.x(), p.y(), p.z(), true, false));
        }
        SimpleAssert.eq(10, installed.size(), "air/padding excluded; all native solids included once");
        var first = installed.get(1);
        var selected = new java.util.ArrayList<HologramControllerTarget.Cell>();
        selected.add(variantFixtureCell(first.x(), first.y(), first.z(), true, false));
        selected.add(variantFixtureCell(20, 20, 20, false, false));
        selected.add(installed.get(0));
        var header = new NBTTagCompound();
        header.setBoolean("localStandardController", true);
        header.setInteger("siteOccupied", 1);
        header.setInteger("siteNativeOccupied", 9);
        var counts = new HologramControllerMaterialCounts.Builder();
        selected.forEach(row -> counts.add(row, 0, 0, 0));
        var marker = new HologramControllerTarget.Projection(header, selected, counts.seal(), installed);
        int[] firstView = com.miaokatze.gtmb.client.hologram.HologramLargeControllerViewportChecks.verify(marker);
        SimpleAssert.eq(3, firstView[0], "selected target with conflicting coordinate intact");
        SimpleAssert.eq(10, firstView[1], "site draws native cell count, not target overlap");
        // Change the target footprint/ordering again. No need to recolor or recompute the native current shape.
        var second = new HologramControllerTarget.Projection(header, selected.subList(1, 3), null, installed);
        int[] secondView = com.miaokatze.gtmb.client.hologram.HologramLargeControllerViewportChecks.verify(second);
        SimpleAssert.eq(2, secondView[0], "new target shape");
        SimpleAssert.eq(firstView[1], secondView[1], "SITE is independent of target shape");
        SimpleAssert.eq(2, marker.materialCounts().total(), "SITE-only cells never enter target materials");
    }

    private static HologramControllerTarget.Cell variantFixtureCell(int x, int y, int z, boolean exists, boolean anchor) {
        Block shown = anchor ? Blocks.stone : Blocks.wool;
        Block actual = exists ? shown : Blocks.air;
        int meta = anchor ? 0 : 3;
        return new HologramControllerTarget.Cell(x, y, z, x, y, z, Block.blockRegistry.getNameForObject(actual),
            Block.blockRegistry.getNameForObject(shown), exists ? meta : 0, meta, actual, shown,
            exists ? new ItemStack(shown, 1, meta) : null, new ItemStack(shown, 1, meta), null, null, anchor, false,
            false, exists ? "satisfied" : "missing", "", "", "", "", -1, -1, null, null, -1, false,
            HologramControllerTarget.TargetKind.MATERIAL);
    }

    /** A survival element rejects further work halfway through a 100 x 10 x 20 declaration. */
    public static final class LargeController extends TileEntity
        implements com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable {
        static boolean unsupported, emptyNative;
        @Override public int survivalConstruct(ItemStack signal, int budget,
            com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment env) {
            if (unsupported) return -2;
            if (worldObj.getBlock(xCoord - 50, yCoord + 1, zCoord - 10) != Blocks.air) return 0;
            for (int i = 0; i < 10000; i++)
                worldObj.setBlock(xCoord + i % 100 - 50, yCoord + i / 2000 + 1, zCoord + (i / 100) % 20 - 10,
                    i == 0 ? Blocks.furnace : Blocks.wool, i == 0 ? 4 : 3, 3);
            return 10000;
        }
        @Override public void construct(ItemStack signal, boolean hints) {
            if (!(worldObj instanceof HologramNativePreviewWorld)) throw new AssertionError("live creative write");
            if (emptyNative) return;
            for (int i = 0; i < 20000; i++)
                worldObj.setBlock(xCoord + i % 100 - 50, yCoord + i / 2000 + 1, zCoord + (i / 100) % 20 - 10,
                    Blocks.brick_block, 0, 3);
        }
        @Override public String[] getStructureDescription(ItemStack signal) { return new String[0]; }
    }

    private static final class LargeSite extends HologramResourceWorld {
        boolean installed;
        final java.util.Map<Long, Chunk> chunks = new java.util.HashMap<>();
        LargeSite() { super(Blocks.stone, 0); isRemote = true; }
        @Override boolean previewChunkExists(int x, int z) { return true; }
        @Override public Chunk getChunkFromChunkCoords(int x, int z) {
            return chunks.computeIfAbsent(((long)x << 32) ^ (z & 0xffffffffL), k -> new Chunk(this,x,z));
        }
        @Override public boolean blockExists(int x, int y, int z) { return y >= 0 && y < 256; }
        private int index(int x, int y, int z) {
            int ix=x-(tile.xCoord-50), iy=y-tile.yCoord-1, iz=z-(tile.zCoord-10);
            return ix>=0 && ix<100 && iy>=0 && iy<10 && iz>=0 && iz<20 ? ix+100*iz+2000*iy : -1;
        }
        @Override public Block getBlock(int x, int y, int z) {
            if (x == tile.xCoord && y == tile.yCoord && z == tile.zCoord) return Blocks.stone;
            int index=index(x,y,z);
            return index<0 ? Blocks.air : installed ? index==0 ? Blocks.furnace
                : index<10000 ? Blocks.wool : Blocks.brick_block : index<8 ? Blocks.dirt : Blocks.air;
        }
        @Override public int getBlockMetadata(int x,int y,int z) {
            int index=index(x,y,z);
            return !installed || index<0 ? 0 : index==0 ? 4 : index<10000 ? 3 : 0;
        }
        @Override public TileEntity getTileEntity(int x,int y,int z) {
            return x==tile.xCoord && y==tile.yCoord && z==tile.zCoord ? tile : null;
        }
        @Override public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("preview wrote a live site");
        }
    }

    private static void verifyLarge() throws Exception {
        LargeSite world=HologramResourceWorld.create(LargeSite::new);
        LargeController tile = new LargeController(); tile.xCoord=37; tile.yCoord=64; tile.zCoord=-33;
        tile.setWorldObj(world); world.tile=tile;
        ItemStack tool=new ItemStack(Items.stick);
        for (int scenario=0;scenario<4;scenario++) {
            long start=System.nanoTime();
            world.installed=scenario==1; LargeController.unsupported=scenario==2;
            LargeController.emptyNative=scenario==3;
            try (HologramLocalControllerCapture capture=new HologramLocalControllerCapture(tile,0,2,tool,
                HologramLocalControllerCapture.toolSettings(tool),"")) {
                if (LargeController.emptyNative) {
                    try {
                        for (int i=0;i<100;i++) if (capture.stepTarget(607,Long.MAX_VALUE / 2)!=null)
                            throw new AssertionError("no native completion, but a prefix was published");
                        throw new AssertionError("stalled machine did not report failure");
                    } catch (java.io.IOException expected) {
                        SimpleAssert.that("LOCAL_NATIVE_PREVIEW_INCOMPLETE".equals(expected.getMessage()),
                            "incomplete declaration is surfaced explicitly");
                    }
                    continue;
                }
                HologramControllerTarget.Projection model=null;
                for (int i=0;i<250 && model==null;i++) model=capture.stepTarget(607,Long.MAX_VALUE / 2);
                SimpleAssert.that(model!=null, "partial survival prefix must not be published as final");
                SimpleAssert.eq(20001,model.cells().size(),"20k + independent protected anchor");
                SimpleAssert.eq(world.installed ? 20000 : 8,model.metadata().getInteger("siteOccupied"),"real non-air sites");
                SimpleAssert.eq(world.installed ? 1 : 0,view(model.metadata(),false),"terrain does not select LIVE");
                int[] visibility=com.miaokatze.gtmb.client.hologram.HologramLargeControllerViewportChecks.verify(model);
                SimpleAssert.eq(20001,visibility[0],"target all regions including far edge");
                SimpleAssert.eq(world.installed ? 20001 : 9,visibility[1],"current view independent of target");
                boolean[] occupied=new boolean[20000]; int displayOnly=0;
                for (var cell:model.cells()) {
                    if (cell.anchor()) continue;
                    int ordinal=cell.dx()+50 + 100*(cell.dz()+10) + 2000*(cell.dy()-1);
                    SimpleAssert.that(ordinal>=0 && ordinal<occupied.length && !occupied[ordinal],"unique declared coordinate");
                    occupied[ordinal]=true;
                    boolean extra=LargeController.unsupported || ordinal>=10000;
                    if (extra) displayOnly++;
                    SimpleAssert.that(cell.targetBlock()==(extra ? Blocks.brick_block : ordinal==0 ? Blocks.furnace : Blocks.wool),
                        "creative display may fill holes; never substitute for a survival-selected TE/casing");
                    SimpleAssert.that(cell.missingBlock()==extra,"creative-only rows cannot prove a payable target");
                }
                SimpleAssert.eq(LargeController.unsupported ? 20000 : 10000,displayOnly,"display-only complement");
                System.out.println("LargeController actual-capture→candidate: scenario="+scenario+" source=20000+anchor"
                    +" target="+visibility[0]+" site="+visibility[1]+" regions="+visibility[2]
                    +" displayOnly="+displayOnly+" elapsedMs="+(System.nanoTime()-start)/1_000_000L);
            }
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

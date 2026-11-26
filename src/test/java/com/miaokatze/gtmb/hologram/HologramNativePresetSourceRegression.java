// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.CommonProxy;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import gregtech.api.util.HatchElementBuilder;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Offline native builder and real local source-entry contracts. No candidate lists are injected into the index. */
public final class HologramNativePresetSourceRegression {

    private static final int CONTROLLER_ID = 32670, FIRST_HATCH_ID = 32671;

    public static void main(String[] args) {
        int status = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramNativePresetSourceRegression.class, args)) return;
            run();
            System.out.println(
                "NativePresetSource PASS: stock atLeast/chain/OR, source entry, per-slot offers and invalidation");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }

    static void run() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        Block savedBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity[] saved = GregTechAPI.METATILEENTITIES.clone();
        Field proxy = StructureLib.class.getDeclaredField("proxy"),
            marker = StructureLib.class.getDeclaredField("blockHint");
        proxy.setAccessible(true);
        marker.setAccessible(true);
        Object oldProxy = proxy.get(null), oldMarker = marker.get(null);
        String previousThread = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            proxy.set(null, new Hints());
            marker.set(null, Blocks.glass);
            Block machines = new Block(net.minecraft.block.material.Material.iron) {};
            Block.blockRegistry.addObject(4072, "gtmb:preset_source_machines", machines);
            Item.itemRegistry
                .addObject(4072, "gtmb:preset_source_machines", new gregtech.common.blocks.ItemMachines(machines));
            GregTechAPI.sBlockMachines = machines;
            Arrays.fill(GregTechAPI.METATILEENTITIES, null);
            Class<?>[] hatchClasses = { MTEHatchInputBus.class, MTEHatchInput.class, MTEHatchOutputBus.class,
                MTEHatchOutput.class, MTEHatchMaintenance.class, MTEHatchMuffler.class, MTEHatchEnergy.class };
            List<ItemStack> catalog = new ArrayList<>();
            for (int n = 0; n < hatchClasses.length; n++) {
                int id = FIRST_HATCH_ID + n;
                IMetaTileEntity hatch = (IMetaTileEntity) unsafe.allocateInstance(hatchClasses[n]);
                Field tier = MTETieredMachineBlock.class.getDeclaredField("mTier");
                tier.setAccessible(true);
                tier.set(hatch, (byte) (hatchClasses[n] == MTEHatchMaintenance.class ? 1 : 2));
                BaseMetaTileEntity host = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
                host.setMetaTileID((short) id);
                hatch.setBaseMetaTileEntity(host);
                GregTechAPI.METATILEENTITIES[id] = hatch;
                catalog.add(new ItemStack(machines, 1, id));
            }
            Map<IHatchElement<? super MTEMultiBlockBase>, Integer> requirements = new LinkedHashMap<>();
            for (HatchElement role : new HatchElement[] { HatchElement.InputBus, HatchElement.InputHatch,
                HatchElement.OutputBus, HatchElement.OutputHatch, HatchElement.Maintenance, HatchElement.Muffler })
                requirements.put(role, 1);
            requirements.put(HatchElement.ExoticEnergy.or(HatchElement.Energy), 1);
            var builder = HatchElementBuilder.<MTEMultiBlockBase>builder()
                .atLeast(requirements)
                .casingIndex(1)
                .hint(1);
            var leaf = builder.build();
            witness(builder, requirements, leaf);
            var chain = StructureUtility.ofChain(leaf, StructureUtility.ofBlock(Blocks.stone, 0));
            var source = new HologramNativeStructurePreview.PresetCandidates(() -> catalog);
            ItemStack trigger = new ItemStack(Blocks.stone);
            SimpleAssert.that(
                HologramHatchPolicy.nativeBrowseAtLeast(leaf),
                "reviewed standard atLeast retains its native browse token");
            SimpleAssert.eq(
                7,
                source.offers(leaf, trigger)
                    .size(),
                "direct leaf enumerates all seven precision-assembler roles");
            SimpleAssert.eq(
                7,
                source.offers(chain, trigger)
                    .size(),
                "casing chain preserves the same automatic offers");
            nativeIndex(chain, source, trigger, catalog);
            pureIdentityAndPlacement(source, trigger, catalog.get(0), catalog.get(6));
            heterogeneousPositions(leaf, catalog.get(0), catalog.get(6));
            sourceEntry(unsafe, chain);
            var extraFilter = builder.hatchItemFilterAnd(
                context -> stack -> { throw new AssertionError("unknown additional filter executed"); });
            HologramHatchPolicy.andFunction(extraFilter, context -> null);
            SimpleAssert.that(
                source.offers(chain, trigger)
                    .isEmpty(),
                "old cached atLeast offers expire after filter mutation");
            var custom = HatchElement.Energy
                .withCount(context -> { throw new AssertionError("custom count executed"); });
            var customNeeds = Collections.<IHatchElement<? super MTEMultiBlockBase>, Integer>singletonMap(custom, 1);
            var customBuilder = HatchElementBuilder.<MTEMultiBlockBase>builder()
                .atLeast(customNeeds)
                .casingIndex(1)
                .hint(1);
            var customLeaf = customBuilder.build();
            witness(customBuilder, customNeeds, customLeaf);
            SimpleAssert.that(
                source.offers(customLeaf, trigger)
                    .isEmpty(),
                "custom counters grant no automatic offers");
            var customIndex = new HologramNativePresetIndex();
            customIndex.record(customLeaf, 1, null, true, false);
            SimpleAssert.that(
                !customIndex.prepare(settings(), Collections.emptyMap(), index -> source.offers(customLeaf, trigger))
                    .resolve(null, true)
                    .complete(),
                "layer/custom counters cannot certify whole-machine quotas");
        } finally {
            Thread.currentThread()
                .setName(previousThread);
            GregTechAPI.sBlockMachines = savedBlock;
            System.arraycopy(saved, 0, GregTechAPI.METATILEENTITIES, 0, saved.length);
            proxy.set(null, oldProxy);
            marker.set(null, oldMarker);
        }
    }

    private static void witness(HatchElementBuilder<?> builder, Map<? extends IHatchElement<?>, Integer> requirements,
        IStructureElement<?> leaf) {
        // Standalone LaunchClassLoader does not run Sponge Mixin. Replay its exact atLeast RETURN/build hooks
        // around the actual .265 builder, rather than supplying a preselected candidate list to the allocator.
        HologramHatchPolicy.declare(builder, requirements.keySet(), true);
        HologramHatchPolicy.requirements(builder, requirements);
        HologramHatchPolicy.built(builder, leaf);
    }

    private static NBTTagCompound settings() {
        NBTTagCompound settings = new NBTTagCompound();
        settings.setInteger("hatchTier", 2);
        settings.setBoolean("hatchDowngrade", true);
        return settings;
    }

    private static void nativeIndex(IStructureElement<?> element,
        HologramNativeStructurePreview.PresetCandidates source, ItemStack trigger, List<ItemStack> catalog)
        throws Exception {
        HologramNativePresetIndex index = new HologramNativePresetIndex();
        for (int n = 0; n < 10; n++) index.record(element, n, null, true, false);
        int[] reads = { 0 };
        var prepared = index.prepare(settings(), Collections.emptyMap(), new HologramNativePresetIndex.Candidates() {

            public List<ItemStack> at(int position) {
                reads[0]++;
                return source.offers(element, trigger);
            }

            public Object sharedKey(int position) {
                return source.sharedKey(element, trigger);
            }
        });
        SimpleAssert.eq(1, reads[0], "explicit context-free key shares one bounded automatic query");
        NBTTagCompound strict = settings();
        strict.setBoolean("hatchDowngrade", false);
        SimpleAssert.that(
            !index.prepare(strict, Collections.emptyMap(), position -> source.offers(element, trigger))
                .resolve(null, true)
                .complete(),
            "strict MV preserves the missing-MV-maintenance boundary; functional tiers are not rewritten");
        int[] counts = new int[prepared.stockIds().length];
        Arrays.fill(counts, 1);
        var result = prepared.resolve(counts, false);
        SimpleAssert.that(
            result.complete() && result.choices()
                .size() == 7,
            "MV with downgrade includes native LV maintenance and satisfies the seven-role chain");
        for (ItemStack stack : catalog) SimpleAssert.that(
            result.choices()
                .stream()
                .anyMatch(choice -> choice.itemId() == stack.getItemDamage()),
            "each required native role has a physical choice");
        Arrays.fill(counts, 0);
        SimpleAssert.that(
            !prepared.resolve(counts, false)
                .complete(),
            "empty stock returns no partial successful preset");
    }

    private static void heterogeneousPositions(IStructureElement<?> declaration, ItemStack input, ItemStack energy)
        throws Exception {
        var index = new HologramNativePresetIndex();
        for (int n = 0; n < 8; n++) index.record(declaration, n, null, true, false);
        var prepared = index.prepare(
            settings(),
            Collections.emptyMap(),
            position -> Collections.singletonList(position == 7 ? energy : input));
        var outcome = prepared.resolve(null, true);
        SimpleAssert.that(
            !outcome.complete(),
            "different per-position offers cannot be copied from the first slot to claim missing roles");
        // A narrow two-role declaration proves that a later slot remains reachable by the allocator.
        var needs = new LinkedHashMap<IHatchElement<? super MTEMultiBlockBase>, Integer>();
        needs.put(HatchElement.InputBus, 1);
        needs.put(HatchElement.Energy, 1);
        var builder = HatchElementBuilder.<MTEMultiBlockBase>builder()
            .atLeast(needs)
            .casingIndex(1)
            .hint(1);
        var leaf = builder.build();
        witness(builder, needs, leaf);
        var narrow = new HologramNativePresetIndex();
        narrow.record(leaf, 0, null, true, false);
        narrow.record(leaf, 1, null, true, false);
        var solved = narrow
            .prepare(
                settings(),
                Collections.emptyMap(),
                position -> Collections.singletonList(position == 0 ? input : energy))
            .resolve(null, true);
        SimpleAssert.that(
            solved.complete() && solved.choices()
                .stream()
                .anyMatch(choice -> choice.index() == 1 && choice.itemId() == energy.getItemDamage()),
            "heterogeneous later position is independently evaluated in prepare and resolve");
    }

    private static void sourceEntry(sun.misc.Unsafe unsafe, IStructureElement<?> declaration) throws Exception {
        Controller controller = new Controller();
        GregTechAPI.METATILEENTITIES[CONTROLLER_ID] = controller;
        BaseMetaTileEntity live = new BaseMetaTileEntity();
        live.setMetaTileID((short) CONTROLLER_ID);
        live.setMetaTileEntity(controller);
        controller.setBaseMetaTileEntity(live);
        SiteWorld world = HologramResourceWorld.create(SiteWorld::new);
        world.tile = live;
        live.setWorldObj(world);
        live.yCoord = 64;
        controller.setExtendedFacing(com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT);
        @SuppressWarnings("unchecked")
        IStructureElement<Controller> element = (IStructureElement<Controller>) declaration;
        IStructureDefinition<Controller> definition = StructureDefinition.<Controller>builder()
            .addShape("main", new String[][] { { "AAAAAAAAAA" } })
            .addElement('A', element)
            .build();
        boolean[] current = { true };
        try (
            GTMBExtensions.Registration registration = StructureSources.registerContextual(
                "gtmb_test:preset-source",
                Controller.class,
                (fresh, trigger, intent) -> StructureSources.Resolution.ready(
                    new StructureSources.Plan(
                        definition,
                        Collections.singletonList(new StructureSources.Piece("main", -1, 0, 0, trigger))),
                    () -> current[0] ? null : "FIXTURE_SOURCE_CHANGED"));
            var capture = new HologramLocalControllerCapture(
                live,
                0,
                ForgeDirection.NORTH.ordinal(),
                new ItemStack(Blocks.stone),
                HologramLocalControllerCapture.toolSettings(new ItemStack(Blocks.stone)),
                "")) {
            HologramControllerTarget.Projection projection = null;
            for (int step = 0; projection == null && step < 100; step++)
                projection = capture.stepTarget(128, 100_000_000L);
            SimpleAssert.that(projection != null, "real local source geometry publishes target");
            Field preview = HologramLocalControllerCapture.class.getDeclaredField("nativePreview");
            preview.setAccessible(true);
            SimpleAssert.that(preview.get(capture) == null, "source geometry requires no native preview object");
            Field captured = HologramLocalControllerCapture.class.getDeclaredField("capture");
            captured.setAccessible(true);
            HologramCapture cells = (HologramCapture) captured.get(capture);
            SimpleAssert.that(
                cells.cells.stream()
                    .allMatch(cell -> cell.candidates == null),
                "source target capture has no prefilled candidate lists");
            int selected = 0;
            while (projection.cells()
                .get(selected)
                .anchor()) selected++;
            SimpleAssert.that(
                capture.selectedOffers(selected)
                    .candidates()
                    .size() >= 7,
                "production source manual entry lazily exposes the declared native hatch models");
            var prepared = capture.prepareNativePreset(settings(), Collections.emptyMap());
            SimpleAssert.that(
                prepared.resolve(null, true)
                    .complete(),
                "production source entry explicitly queries the automatic backend");
            current[0] = false;
            SimpleAssert.that(
                !prepared.resolve(null, true)
                    .complete(),
                "source changes while waiting for stock expire the old Prepared");
            current[0] = true;
            capture.close();
            SimpleAssert.that(
                !prepared.resolve(null, true)
                    .complete(),
                "closed captures cannot apply old stock receipts");
        }
    }

    private static void pureIdentityAndPlacement(HologramNativeStructurePreview.PresetCandidates source,
        ItemStack trigger, ItemStack first, ItemStack second) throws Exception {
        var a = new EqualPure(first, true);
        var b = new EqualPure(second, true);
        SimpleAssert.that(a.equals(b), "fixture objects deliberately compare equal");
        SimpleAssert.that(
            HologramValues.same(
                first,
                source.offers(a, trigger)
                    .get(0))
                && HologramValues.same(
                    second,
                    source.offers(b, trigger)
                        .get(0)),
            "different element identities never share candidates through equals");
        var manualOnly = new EqualPure(first, false);
        SimpleAssert.that(
            source.offers(manualOnly, trigger)
                .isEmpty()
                && source.manualOffers(manualOnly, trigger)
                    .size() == 1,
            "pure accepts keeps manual choices while placementAllows rejects automatic candidates");
        var index = new HologramNativePresetIndex();
        index.record(a, 0, null, true, false);
        var result = index.prepare(settings(), Collections.emptyMap(), position -> source.offers(a, trigger))
            .resolve(null, true);
        SimpleAssert.that(
            !result.complete() && result.reason()
                .contains("配额"),
            "a complete pure cell candidate list does not invent whole-machine quotas");
    }

    private static final class EqualPure implements Declarations.PureElement<Object> {

        private final ItemStack sample;
        private final boolean automatic;

        EqualPure(ItemStack sample, boolean automatic) {
            this.sample = sample;
            this.automatic = automatic;
        }

        @Override
        public ItemStack primary() {
            return sample.copy();
        }

        @Override
        public List<ItemStack> candidates() {
            return Collections.singletonList(sample.copy());
        }

        @Override
        public boolean complete() {
            return true;
        }

        @Override
        public String role() {
            return "hatch";
        }

        @Override
        public boolean accepts(ItemStack target) {
            return HologramValues.same(sample, target);
        }

        @Override
        public boolean placementAllows(ItemStack target) {
            return automatic && accepts(target);
        }

        @Override
        public int targetMeta(ItemStack target) {
            return 0;
        }

        @Override
        public boolean matches(Declarations.WorldState state) {
            return false;
        }

        @Override
        public boolean matchesTarget(Declarations.WorldState state, ItemStack target) {
            return false;
        }

        @Override
        public boolean check(Object context, World world, int x, int y, int z) {
            throw new AssertionError("pure check executed");
        }

        @Override
        public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("pure hint executed");
        }

        @Override
        public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
            throw new AssertionError("pure placement executed");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualPure;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static final class SiteWorld extends HologramResourceWorld {

        SiteWorld() {
            super(GregTechAPI.sBlockMachines, 0);
            isRemote = true;
        }

        @Override
        boolean previewChunkExists(int x, int z) {
            return true;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return x == 0 && y == 64 && z == 0 ? GregTechAPI.sBlockMachines : Blocks.air;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return x == 0 && y == 64 && z == 0 ? tile : null;
        }

        @Override
        public Chunk getChunkFromChunkCoords(int x, int z) {
            return new Chunk(this, x, z);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("source preset wrote live world");
        }
    }

    private static final class Hints extends CommonProxy {

        @Override
        public void hintParticle(World world, int x, int y, int z, Block block, int metadata) {
            SimpleAssert
                .that(HologramCapture.hint(world, x, y, z, block, metadata), "active source consumes the native hint");
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller> {

        Controller() {
            super("preset_source_fixture");
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            throw new AssertionError("source invoked native definition");
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            throw new AssertionError("source invoked native construct");
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            throw new AssertionError("source invoked tooltip");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}

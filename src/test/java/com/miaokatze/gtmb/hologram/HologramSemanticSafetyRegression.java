package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.blocks.ItemMachines;

/** Negative contracts at the common read boundary, independent of an installed GTNL. */
final class HologramSemanticSafetyRegression {

    static void run() {
        unknownFiltersAreNeverInvoked();
        metadataRemainsProvenance();
        beaconRequiresItsRealTile();
        savedGtnlSourcesRequireAdmission();
    }

    private static void unknownFiltersAreNeverInvoked() {
        Object builder = new Object();
        IStructureElement<Object> element = StructureUtility.isAir();
        AtomicInteger callbacks = new AtomicInteger();
        HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), true);
        HologramHatchPolicy.andFunction(builder, context -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown Function invalidates the recoverable declaration");
        HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), true);
        HologramHatchPolicy.andBiFunction(builder, (context, trigger) -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown BiFunction invalidates the recoverable declaration");
        SimpleAssert.that(callbacks.get() == 0, "display and planning never evaluate unknown filters");
        HologramHatchPolicy.reset(builder);
    }

    private static void metadataRemainsProvenance() {
        try {
            NBTTagCompound semantic = new NBTTagCompound();
            semantic.setString("purpose", "LIVE_SNAPSHOT");
            semantic.setInteger("gtnlSchema", 1);
            HologramPresets.Preset original = new HologramPresets.Preset();
            original.semanticNbt = HologramBlueprintNbt.encodeCanonical(semantic);
            HologramPresets.Structure source = new HologramPresets.Structure();
            source.id = "machine";
            source.semanticNbt = original.semanticNbt;
            original.structures.add(source);
            HologramPresets.Preset copied = HologramSnapshotMetadataSource.copyMetadata(original);
            SimpleAssert.that(original.semanticNbt.equals(copied.semanticNbt), "preset semantic receipt survives copy");
            SimpleAssert.that(
                source.semanticNbt.equals(copied.structures.get(0).semanticNbt),
                "member semantic receipt survives copy");
            String raw = "{\"blueprint\":{\"semanticNbt\":\"" + original.semanticNbt
                + "\",\"cells\":[],\"palette\":[]}}";
            byte[] compact = HologramBlueprintCompactCodec.recompress(raw.getBytes(StandardCharsets.UTF_8), 1024);
            SimpleAssert.that(
                HologramBlueprintCompactCodec.parse(compact)
                    .getAsJsonObject("blueprint")
                    .get("semanticNbt")
                    .getAsString()
                    .equals(original.semanticNbt),
                "dictionary roundtrip preserves semantic receipt exactly");
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void beaconRequiresItsRealTile() {
        HologramGTNLElement element = HologramGTNLSemantics.commonElement("BlockBeacon");
        HologramResourceWorld world = HologramResourceWorld.create(net.minecraft.init.Blocks.beacon, 0);
        SimpleAssert.that(!element.matchesWorld(world, 0, 64, 0), "beacon block alone does not satisfy the TE rule");
        world.tile = new net.minecraft.tileentity.TileEntityChest();
        world.tile.yCoord = 64;
        SimpleAssert.that(!element.matchesWorld(world, 0, 64, 0), "wrong TE cannot masquerade as a beacon");
        world.tile = new net.minecraft.tileentity.TileEntityBeacon();
        world.tile.yCoord = 64;
        SimpleAssert.that(element.matchesWorld(world, 0, 64, 0), "beacon with its real TE is recognized");
    }

    private static void savedGtnlSourcesRequireAdmission() {
        try {
            HologramSession session = savedSession();
            HologramPresets.Preset preset = session.virtualPreset;
            String unknown = "com.science.gtnl.common.machine.multiblock.UnreviewedFixture";
            preset.sourceClass = unknown;
            HologramPresets.validate(preset);
            HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(session);
            SimpleAssert.that(population.step(Integer.MAX_VALUE, Long.MAX_VALUE), "old blueprint restores completely");
            SimpleAssert.that(!session.capture.incomplete, "unknown provenance remains displayable and preservable");
            SimpleAssert.eq(1, session.pins.size(), "the real saved-blueprint path restores an explicit material pin");
            SimpleAssert.eq(
                "pending",
                HologramPlanPolicy.classify(session, session.capture.cells.get(0), 0),
                "the ordinary per-cell replay path alone admits the source block at a new position");
            SimpleAssert.that(
                !HologramPlanPolicy.constructionAllowed(session),
                "saved unknown GTNL provenance must stop construction before a restored pin can write a new position");
            failure(session, "GTNL_UNREVIEWED_MACHINE", "top-level unknown GTNL is explicitly rejected");
            SimpleAssert.eq(unknown, preset.sourceClass, "rejected admission preserves the original source metadata");
            HologramPresets.validate(preset);

            preset.sourceClass = AdmissionController.class.getName();
            SimpleAssert.that(
                HologramGTNLSemantics.constructionFailure(session) == null,
                "ordinary non-GTNL blueprint replay retains its existing admission");
            SimpleAssert.that(
                HologramPlanPolicy.constructionAllowed(session),
                "the full construction gate still admits the same ordinary restored block");
            HologramPresets.Structure ordinary = source("ordinary", preset.sourceClass, -1);
            HologramPresets.Structure unreviewed = source("unreviewed", unknown, -1);
            preset.structures.add(ordinary);
            preset.structures.add(unreviewed);
            failure(
                session,
                "GTNL_UNREVIEWED_MACHINE",
                "an enabled unknown member cannot hide behind an ordinary member");
            unreviewed.enabled = false;
            preset.sourceClass = unknown;
            SimpleAssert.that(
                HologramGTNLSemantics.constructionFailure(session) == null,
                "disabled GTNL members and region-level provenance do not veto ordinary enabled members");
            unreviewed.enabled = true;
            unreviewed.sourceClass = null;
            failure(
                session,
                "GTNL_UNREVIEWED_MACHINE",
                "a member's missing source tag uses its saved top-level source");
            preset.structures.clear();
            preset.sourceClass = null;

            savedControllerIdentities(session, unknown);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("saved GTNL construction admission", failure);
        }
    }

    private static void savedControllerIdentities(HologramSession session, String unknown)
        throws ReflectiveOperationException, java.io.IOException {
        String prefix = "com.science.gtnl.common.machine.multiblock.";
        String[] classes = { unknown, prefix + "AssemblerMatrix", prefix + "steam.LargeSteamMixer",
            prefix + "AtomicEnergyExcitationPlant", prefix + "NuclearReactor",
            prefix + "structuralReconstructionPlan.PrecisionAssembler" };
        String[] families = { "", "assembler_matrix", "large_steam_mixer", "aeep", "nuclear_reactor",
            "precision_assembler" };
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        int firstId = 31970;
        IMetaTileEntity[] previous = Arrays
            .copyOfRange(GregTechAPI.METATILEENTITIES, firstId, firstId + classes.length + 1);
        try {
            ControllerClasses loader = new ControllerClasses();
            for (int i = 0; i < classes.length; i++) GregTechAPI.METATILEENTITIES[firstId
                + i] = (IMetaTileEntity) unsafe.allocateInstance(loader.define(classes[i]));
            IMetaTileEntity ordinary = (IMetaTileEntity) unsafe.allocateInstance(AdmissionController.class);
            GregTechAPI.METATILEENTITIES[firstId + classes.length] = ordinary;
            HologramPresets.Preset preset = session.virtualPreset;
            preset.sourceClass = null;
            preset.controllerId = firstId;
            preset.controllerType = unknown + ":" + firstId;
            SimpleAssert.eq(
                unknown,
                HologramBlueprintData.sourceClass(preset, null),
                "legacy controllerType fallback requires its saved nonnegative controller ID");
            failure(session, "GTNL_UNREVIEWED_MACHINE", "legacy controllerType fallback is also a GTNL source");
            legacySourceMigration(unknown, firstId);
            preset.controllerType = null;
            preset.sourceClass = unknown;
            preset.controllerId = firstId;
            failure(
                session,
                "GTNL_UNREVIEWED_MACHINE",
                "a real registry GTNL identity stays unsupported without review");
            preset.sourceClass = ordinary.getClass()
                .getName();
            failure(
                session,
                "GTNL_SAVED_SOURCE_MISMATCH",
                "a forged ordinary source tag cannot hide a GTNL controller ID");
            preset.sourceClass = null;
            failure(session, "GTNL_SAVED_SOURCE_MISMATCH", "removing the source tag cannot hide a GTNL controller ID");
            preset.sourceClass = classes[1];
            preset.controllerId = firstId + classes.length;
            failure(session, "GTNL_SAVED_SOURCE_MISMATCH", "a GTNL source cannot borrow an ordinary controller ID");
            preset.controllerId = firstId + 2;
            failure(
                session,
                "GTNL_SAVED_SOURCE_MISMATCH",
                "two reviewed GTNL controller identities cannot be exchanged");

            for (int i = 1; i < classes.length; i++) {
                preset.sourceClass = classes[i];
                preset.controllerId = firstId + i;
                preset.semanticNbt = null;
                failure(
                    session,
                    "GTNL_SAVED_RESCAN_REQUIRED",
                    "each reviewed family still needs a saved template proof");
                preset.semanticNbt = proof(families[i], "LIVE_SNAPSHOT", HologramGTNLSemantics.COMMIT);
                failure(session, "GTNL_SAVED_RESCAN_REQUIRED", "a live snapshot does not authorize a new template");
                preset.semanticNbt = proof(families[i], "NEW_TEMPLATE", "old-commit");
                failure(session, "GTNL_SAVED_RESCAN_REQUIRED", "a stale source proof does not authorize construction");
                preset.semanticNbt = proof("wrong_family", "NEW_TEMPLATE", HologramGTNLSemantics.COMMIT);
                failure(
                    session,
                    "GTNL_SAVED_SOURCE_MISMATCH",
                    "proof family must match the registered controller family");
                HologramPresets.Structure member = source("reviewed", classes[i], firstId + i);
                preset.structures.add(member);
                failure(
                    session,
                    "GTNL_SAVED_RESCAN_REQUIRED",
                    "top-level proof cannot replace a member's missing proof");
                member.semanticNbt = preset.semanticNbt;
                failure(session, "GTNL_SAVED_SOURCE_MISMATCH", "member proofs also bind their own machine family");
                member.enabled = false;
                SimpleAssert.that(
                    HologramGTNLSemantics.constructionFailure(session) == null,
                    "disabled reviewed members retain their saved data without gaining construction authority");
                preset.structures.clear();
            }

            preset.sourceClass = ordinary.getClass()
                .getName();
            preset.controllerId = firstId + classes.length;
            preset.semanticNbt = null;
            ItemMachines machines = new ItemMachines(new Block(Material.iron) {});
            session.pins.put(0, new ItemStack(machines, 1, firstId));
            failure(
                session,
                "GTNL_SAVED_SOURCE_MISMATCH",
                "the actual controller material defeats forged source and ID fields");
            HologramPresets.Structure disabled = source("disabled", unknown, firstId);
            disabled.enabled = false;
            preset.structures.add(disabled);
            HologramBlueprintData.setMemberships(session.capture.cells.get(0), Collections.singletonList(disabled.id));
            SimpleAssert.that(
                HologramGTNLSemantics.constructionFailure(session) == null,
                "an actual GTNL controller in a disabled member does not block unrelated placement");
            HologramPresets.Structure enabled = source(
                "enabled",
                ordinary.getClass()
                    .getName(),
                preset.controllerId);
            preset.structures.add(enabled);
            HologramBlueprintData.setMemberships(session.capture.cells.get(0), Arrays.asList(disabled.id, enabled.id));
            failure(
                session,
                "GTNL_SAVED_SOURCE_MISMATCH",
                "a shared enabled cell cannot borrow a disabled GTNL source");
        } finally {
            System.arraycopy(previous, 0, GregTechAPI.METATILEENTITIES, firstId, previous.length);
        }
    }

    private static void legacySourceMigration(String sourceClass, int controllerId)
        throws ReflectiveOperationException, java.io.IOException {
        HologramPresets.Preset legacy = new HologramPresets.Preset();
        legacy.id = "semantic_legacy_source";
        legacy.name = "Legacy source";
        legacy.controllerType = sourceClass + ":" + controllerId;
        HologramPresets.Cell controller = new HologramPresets.Cell();
        // Missing dependency identities are intentionally preserved by the actual legacy migration contract.
        controller.item = "gtmb_semantic_qa:missing_legacy_controller";
        controller.damage = controllerId;
        controller.controller = true;
        controller.facing = 2;
        legacy.cells.add(controller);
        legacy.palette.add(controller);
        boolean rejected = false;
        try {
            HologramPresets.upgradeLegacy(legacy);
        } catch (java.io.IOException missingId) {
            rejected = "旧模板格式无效".equals(missingId.getMessage());
        }
        SimpleAssert.that(rejected, "a region's controllerId=-1 is not a legal old controller template");
        legacy.controllerId = controllerId;
        HologramPresets.Preset upgraded = HologramPresets.upgradeLegacy(legacy);
        HologramPresets.validate(upgraded);
        SimpleAssert.eq(2, upgraded.schema, "the real old-template upgrade emits schema 2");
        SimpleAssert.eq(sourceClass, upgraded.sourceClass, "the real upgrade materializes the old controller source");
        SimpleAssert.eq(controllerId, upgraded.controllerId, "the real upgrade retains the old controller ID");
        HologramSession migrated = savedSession();
        migrated.virtualPreset = upgraded;
        migrated.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        failure(
            migrated,
            "GTNL_UNREVIEWED_MACHINE",
            "a migrated missing-dependency source still cannot gain construction authority");
    }

    private static HologramSession savedSession() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        UUID identity = UUID.fromString("fdbb9168-166f-42c0-9e81-6c2a03d7a63d");
        for (Field candidate : EntityPlayer.class.getDeclaredFields()) if (candidate.getType() == GameProfile.class) {
            candidate.setAccessible(true);
            candidate.set(player, new GameProfile(identity, "SemanticFixture"));
        }
        for (Field candidate : Entity.class.getDeclaredFields()) if (candidate.getType() == UUID.class) {
            candidate.setAccessible(true);
            candidate.set(player, identity);
        }
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.capabilities.allowEdit = true;
        HologramSession session = new HologramSession(player, 20, 64, 0, false, 2);
        session.virtual = session.virtualPlacement = session.virtualLocked = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.id = "saved_semantic_boundary";
        session.virtualPreset.name = "Saved semantic boundary";
        session.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
        return session;
    }

    private static HologramPresets.Structure source(String id, String type, int controllerId) {
        HologramPresets.Structure source = new HologramPresets.Structure();
        source.id = id;
        source.sourceClass = type;
        source.controllerId = controllerId;
        return source;
    }

    private static String proof(String family, String purpose, String commit) throws java.io.IOException {
        NBTTagCompound proof = new NBTTagCompound();
        proof.setInteger("gtnlSchema", 1);
        proof.setString("sourceCommit", commit);
        proof.setString("purpose", purpose);
        proof.setString("family", family);
        return HologramBlueprintNbt.encodeCanonical(proof);
    }

    private static void failure(HologramSession session, String code, String label) {
        String reason = HologramGTNLSemantics.constructionFailure(session);
        SimpleAssert.that(reason != null && reason.startsWith(code), label + ": " + reason);
    }

    /** No static graph or registered casing dependencies; identity checks must never invoke machine callbacks. */
    public static class AdmissionController extends MTEEnhancedMultiBlockBase<AdmissionController> {

        public AdmissionController() {
            super("semantic_identity_fixture");
        }

        @Override
        public IStructureDefinition<AdmissionController> getStructureDefinition() {
            throw new AssertionError("identity admission must not read a native structure definition");
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            throw new AssertionError("identity admission must not construct a machine");
        }

        @Override
        public ItemStack getStackForm(long count) {
            throw new AssertionError("identity admission must not request a native machine item");
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            throw new AssertionError("identity admission must not read a native tooltip");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            throw new AssertionError("identity admission must not create a native machine");
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            throw new AssertionError("identity admission must not request native textures");
        }
    }

    /** Synthetic names exercise registry identity checks without loading or executing optional GTNL classes. */
    private static final class ControllerClasses extends ClassLoader {

        ControllerClasses() {
            super(HologramSemanticSafetyRegression.class.getClassLoader());
        }

        Class<?> define(String name) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name.replace('.', '/'),
                null,
                AdmissionController.class.getName()
                    .replace('.', '/'),
                null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Real server placement entry, attachment geometry and read-only world tripwires; no GUI or live-payment claims. */
public final class HologramUnsavedPlacementRegression {

    private HologramUnsavedPlacementRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramUnsavedPlacementRegression.class, args)) return;
        try {
            attachmentDraft(false);
            attachmentDraft(true);
            admissionFences();
            savedSourceIsUnchanged();
            retiredPreparationCannotPublish();
            nativeGeneratedAttachments();
            System.out.println("HologramUnsavedPlacementRegression passed");
        } finally {
            HologramWorkspace.clear();
        }
    }

    private static void nativeGeneratedAttachments() throws Exception {
        Class<?> blockType = Class.forName("com.creativemd.littletiles.common.blocks.BlockTile");
        Block host = (Block) blockType.getConstructor(net.minecraft.block.material.Material.class)
            .newInstance(net.minecraft.block.material.Material.rock);
        host = DecorationVoucherRegression.registeredFixture("littletiles:BlockLittleTiles", host);
        Class.forName("com.creativemd.littletiles.LittleTiles")
            .getField("blockTile")
            .set(null, host);
        Class<?> little = Class.forName("com.creativemd.littletiles.common.utils.LittleTile");
        java.lang.reflect.Method register = little.getMethod("registerLittleTile", Class.class, String.class);
        register
            .invoke(null, Class.forName("com.creativemd.littletiles.common.utils.LittleTileBlock"), "BlockTileBlock");
        register.invoke(
            null,
            Class.forName("com.creativemd.littletiles.common.utils.LittleTileBlockColored"),
            "BlockTileColored");
        java.lang.reflect.Method map = net.minecraft.tileentity.TileEntity.class
            .getDeclaredMethod("addMapping", Class.class, String.class);
        map.setAccessible(true);
        map.invoke(
            null,
            Class.forName("com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles"),
            "LittleTilesTileEntity");
        for (boolean picture : new boolean[] { false, true }) {
            try (Fixture fixture = new Fixture()) {
                fixture.attachThroughService(picture);
                HologramSession s = fixture.session;
                HologramCapture.Cell cell = s.capture.cells.get(0);
                SimpleAssert.that(
                    cell.block == host && !cell.tileNbt.isEmpty() && cell.attachmentPlacementProof != null,
                    "real server attachment owns native partial geometry and exact provenance");
                NBTTagCompound original = HologramBlueprintNbt.decode(cell.tileNbt);
                SimpleAssert.eq(
                    picture ? 2 : 1,
                    original.getInteger("tilesCount"),
                    "native fixture retains the partial glyph or mixed image components");
                SimpleAssert.that(
                    !HologramNativePlacement.materials(cell.block, cell.meta, original)
                        .isEmpty(),
                    "the real LittleTiles adapter validates generated geometry and its materials");
                String payload = cell.tileNbt;
                if (picture) fixture.exportAcceptedAttachment();
                HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, fixture.request());
                fixture.finishPreparation();
                SimpleAssert
                    .eq(payload, s.capture.cells.get(0).tileNbt, "placement retains complete native source payload");
                SimpleAssert.that(
                    !s.processingReviewed && s.blueprintSaveReceipt.isEmpty() && s.blueprintDirty,
                    "generated native geometry needs neither a fabricated processing receipt nor a saved blueprint");
                SimpleAssert
                    .that(s.planMissing > 0, "partial and mixed geometry still require actual material payment");
            }
        }
        for (String alteration : new String[] { "missingProof", "changedPayload", "unrelatedMachine" }) {
            try (Fixture fixture = new Fixture()) {
                fixture.attachThroughService(false);
                HologramSession s = fixture.session;
                HologramCapture.Cell cell = s.capture.cells.get(0);
                if ("missingProof".equals(alteration)) cell.attachmentPlacementProof = null;
                else if ("changedPayload".equals(alteration)) {
                    NBTTagCompound changed = HologramBlueprintNbt.decode(cell.tileNbt);
                    changed.setString("Owner", "external identity");
                    cell.tileNbt = HologramBlueprintNbt.encodeCaptured(changed);
                } else {
                    HologramCapture.Cell other = new HologramCapture.Cell(null, 3, 64, 0);
                    other.userPosition = true;
                    other.block = Blocks.wall_sign;
                    other.meta = 2;
                    other.actualStack = new ItemStack(net.minecraft.init.Items.sign);
                    other.blueprintBlock = "minecraft:wall_sign";
                    other.blueprintItem = "minecraft:sign";
                    NBTTagCompound sign = new NBTTagCompound();
                    sign.setString("id", "Sign");
                    sign.setString("Text1", "unreviewed machine data");
                    other.tileNbt = HologramBlueprintNbt.encodeCaptured(sign);
                    // A forged attachment membership never gives another cell the original geometry's proof.
                    HologramBlueprintData.setMemberships(other, cell.memberships());
                    HologramTextWorkflow.stageAttachment(
                        s,
                        s.virtualPreset,
                        Collections.singletonList(other),
                        Collections.emptySet(),
                        null);
                }
                HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, fixture.request());
                fixture.drainPreparation();
                SimpleAssert.that(
                    !s.virtualPlacement && s.blueprintDirty,
                    "unreviewed arbitrary data cannot inherit attachment authority: " + alteration);
            }
        }
    }

    private static void attachmentDraft(boolean picture) throws Exception {
        try (Fixture fixture = new Fixture()) {
            HologramSession s = fixture.session;
            fixture.attach(picture, 0);
            SimpleAssert.that(s.virtualPreset.cells.isEmpty(), "authoring metadata need not contain fresh geometry");
            HologramPresets.Preset editor = s.virtualPreset;
            long revision = s.planRevision;
            NBTTagCompound forged = fixture.request();
            forged.setString("presetId", "unrelated_saved_preset");
            forged.setBoolean("retainNbt", false);
            forged.setBoolean("creative", true);
            forged.setInteger("cost", 0);
            HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, forged);
            fixture.finishPreparation();
            SimpleAssert.that(s.virtualPlacement && !s.virtualLocked, "draft enters moving world preview");
            SimpleAssert.that(s.virtualPreset != editor, "placement projects a detached authoritative blueprint");
            SimpleAssert.eq(1, s.virtualPreset.cells.size(), "new attachment cells come from the current capture");
            SimpleAssert.eq(1, s.virtualPreset.structures.size(), "attachment definition is retained");
            SimpleAssert.that(s.virtualPreset.id == null, "placement never mints a persistent blueprint identity");
            SimpleAssert.that(s.blueprintDirty, "unsaved attachment remains dirty");
            SimpleAssert.eq("draft", s.blueprintSource, "draft provenance is retained");
            SimpleAssert.eq("", s.blueprintSavedId, "preview does not claim a successful save");
            SimpleAssert.eq("", s.blueprintSaveReceipt, "preview creates no save receipt");
            SimpleAssert.that(s.planRevision > revision, "placement receives a fresh material plan revision");
            SimpleAssert.eq(1, s.planMissing, "empty inventory still owes the attachment material");
            SimpleAssert.eq(0, s.job, "preview never starts construction");
            SimpleAssert.that(!s.placementReviewIssued && !s.forcePlacementReady, "preview grants no build consent");
            SimpleAssert.eq(
                "",
                fixture.player.getHeldItem()
                    .getTagCompound()
                    .getString(HologramSessionKeys.SELECTED_PRESET),
                "draft placement does not replace the last saved blueprint selection");
            SimpleAssert.that(
                !fixture.player.getHeldItem()
                    .getTagCompound()
                    .hasKey(HologramSessionKeys.DRAFT),
                "placing a draft does not implicitly persist it to the tool");
            NBTTagCompound old = fixture.request();
            old.setLong("planRevision", revision);
            HologramAuthoringWorkflow.blueprintAction(s, old, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, s.job, "old confirmation cannot build the new draft preview");
            HologramWorldPlacementWorkflow.cancelWorldPlacement(fixture.player, fixture.request());
            SimpleAssert.that(!s.virtualPlacement && s.blueprintDirty, "cancel keeps the editable unsaved attachment");
            SimpleAssert.eq(1, s.capture.cells.size(), "cancel retains attachment geometry");
        }
    }

    private static void admissionFences() throws Exception {
        for (String gate : new String[] { "session", "revision", "epoch", "missingEpoch", "incomplete", "pending",
            "capturePending", "localPlan", "attachment", "processing", "save", "job", "tool", "world", "unloaded" }) {
            try (Fixture fixture = new Fixture()) {
                HologramSession s = fixture.session;
                fixture.attach(false, 0);
                NBTTagCompound action = fixture.request();
                if ("session".equals(gate)) action.setString(
                    "session",
                    UUID.randomUUID()
                        .toString());
                else if ("revision".equals(gate)) action.setLong("planRevision", s.planRevision - 1);
                else if ("epoch".equals(gate)) action.setLong("editorWorkspaceEpoch", s.editorWorkspaceEpoch - 1);
                else if ("missingEpoch".equals(gate)) action.removeTag("editorWorkspaceEpoch");
                else if ("incomplete".equals(gate)) s.capture.incomplete = true;
                else if ("pending".equals(gate)) s.capture.pending = true;
                else if ("capturePending".equals(gate)) s.capturePending = true;
                else if ("localPlan".equals(gate)) s.localPlanPending = true;
                else if ("attachment".equals(gate)) s.attachmentTask = (HologramTextWorkflow.Task) fixture.unsafe
                    .allocateInstance(HologramTextWorkflow.Task.class);
                else if ("processing".equals(gate)) s.processingWorkflowStarted = true;
                else if ("save".equals(gate)) s.blueprintSaveReceipt = "pending-write";
                else if ("job".equals(gate)) s.job = 2;
                else if ("tool".equals(gate)) fixture.player.inventory.currentItem = 1;
                else if ("world".equals(gate)) fixture.player.dimension++;
                else fixture.world.unloaded = true;
                HologramCapture before = s.capture;
                HologramPresets.Preset preset = s.virtualPreset;
                try {
                    HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, action);
                    throw new AssertionError("placement should reject " + gate);
                } catch (IOException expected) {}
                SimpleAssert.that(!s.virtualPlacement && !s.virtualLocked, "gate grants no placement: " + gate);
                SimpleAssert.that(s.capture == before && s.virtualPreset == preset, "gate preserves draft: " + gate);
                SimpleAssert.that(s.blueprintDirty, "rejection keeps unsaved state: " + gate);
                s.attachmentTask = null;
                s.capturePending = false;
            }
        }
    }

    private static void savedSourceIsUnchanged() throws Exception {
        Field gameDirectory = HologramLibraryFacade.class.getDeclaredField("clientGameDirectory");
        gameDirectory.setAccessible(true);
        Path previous = (Path) gameDirectory.get(null);
        Path root = Files.createTempDirectory("gtmb-unsaved-placement-");
        try (Fixture fixture = new Fixture()) {
            HologramLibraryFacade.configureClientGameDirectory(root);
            HologramSession s = fixture.session;
            HologramPresets.Preset base = new HologramPresets.Preset();
            base.schema = 2;
            base.id = "baseline";
            base.name = "original saved blueprint";
            base.controllerId = -1;
            base.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 3, 0, 0, -1, false));
            HologramPresets.save(base);
            byte[] stored = Files.readAllBytes(HologramPresets.libraryPath());
            s.virtualPreset = HologramPresets.get(base.id);
            s.blueprintSavedId = base.id;
            s.blueprintSource = "preset";
            HologramBlueprintProjection.populateVirtual(s);
            fixture.attach(true, 0);
            String source = s.blueprintSource;
            HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, fixture.request());
            fixture.finishPreparation();
            SimpleAssert.eq(2, s.capture.cells.size(), "edited saved copy includes original and new attachment");
            SimpleAssert.eq(base.id, s.virtualPreset.id, "placement retains the logical source without saving it");
            SimpleAssert.eq(base.id, s.blueprintSavedId, "saved source identity remains unchanged");
            SimpleAssert.eq(source, s.blueprintSource, "source provenance survives placement");
            SimpleAssert.that(s.blueprintDirty, "edited saved source still has unsaved changes");
            SimpleAssert.that(
                Arrays.equals(stored, Files.readAllBytes(HologramPresets.libraryPath())),
                "original saved blueprint bytes are neither updated nor consumed");
            SimpleAssert.eq(1, HologramPresets.get(base.id).cells.size(), "saved source still has no attachment");
        } finally {
            gameDirectory.set(null, previous);
            HologramPresets.clearCache();
        }
    }

    private static void retiredPreparationCannotPublish() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.attach(false, 0);
            HologramSession s = fixture.session;
            HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, fixture.request());
            HologramCurrentPlacementPreparation task = s.currentPlacementTask;
            HologramCapture capture = s.capture;
            task.tick(1, Long.MAX_VALUE, false);
            SimpleAssert.that(
                s.currentPlacementTask == task && !s.virtualPlacement,
                "one owner unit cannot project and validate the full draft synchronously");
            s.planRevision++;
            task.tick(1, Long.MAX_VALUE, false);
            SimpleAssert.that(
                s.currentPlacementTask == null && !s.virtualPlacement,
                "a retired revision cannot install asynchronous placement authority");
            SimpleAssert
                .that(s.capture == capture && s.blueprintDirty, "retiring preparation preserves unsaved geometry");
            HologramWorldPlacementWorkflow.placeCurrentBlueprint(s, fixture.request());
            s.currentPlacementTask.cancel();
            SimpleAssert.that(
                s.currentPlacementTask == null && s.capture == capture && s.blueprintDirty,
                "explicit preparation cancellation keeps the current draft");
        }
    }

    private static final class Fixture implements AutoCloseable {

        final sun.misc.Unsafe unsafe;
        final EntityPlayerMP player;
        final HologramServiceEditRegression.ReadOnlyWorld world;
        final HologramSession session;

        Fixture() throws ReflectiveOperationException {
            HologramWorkspace.clear();
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            unsafe = (sun.misc.Unsafe) singleton.get(null);
            world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            UUID id = UUID.randomUUID();
            for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
                field.setAccessible(true);
                field.set(player, new GameProfile(id, "UnsavedPlacementFixture"));
            }
            for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
                field.setAccessible(true);
                field.set(player, id);
            }
            player.worldObj = world;
            player.posY = 64;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
            session = new HologramSession(player, 0, 64, 0, false, 1);
            session.virtual = true;
            session.virtualPreset = new HologramPresets.Preset();
            session.virtualPreset.schema = 2;
            session.virtualPreset.name = "unsaved attachment";
            session.virtualPreset.controllerId = -1;
            session.capture = new HologramCapture(null, player.getHeldItem());
            session.materialSettings.containers = session.materialSettings.me = false;
            session.materialContext = new HologramMaterials.Context(player, session.materialSettings);
            HologramWorkspace.activate(id, session);
        }

        NBTTagCompound request() {
            NBTTagCompound action = new NBTTagCompound();
            action.setString("session", session.id);
            action.setLong("planRevision", session.planRevision);
            action.setLong("editorWorkspaceEpoch", session.editorWorkspaceEpoch);
            return action;
        }

        void finishPreparation() throws Exception {
            drainPreparation();
            SimpleAssert
                .that(session.virtualPlacement, "asynchronous placement preparation succeeded: " + session.status);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (session.localPlanPending && System.nanoTime() < deadline)
                HologramDeferredPlanRefresh.tick(session, 2, 100_000_000L, ignored -> {});
            SimpleAssert.that(!session.localPlanPending, "deferred material plan completed");
        }

        void drainPreparation() throws Exception {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (session.currentPlacementTask != null && System.nanoTime() < deadline) {
                session.currentPlacementTask.tick(2, Long.MAX_VALUE, false);
                if (session.currentPlacementTask != null && !session.currentPlacementTask.ready()) Thread.sleep(1);
            }
            SimpleAssert.that(session.currentPlacementTask == null, "bounded preparation reached a terminal result");
        }

        void attachThroughService(boolean picture) throws Exception {
            HologramTextBox text = new HologramTextBox();
            text.id = (picture ? HologramPictureBox.PREFIX : HologramTextBox.PREFIX) + UUID.randomUUID();
            text.material = "minecraft:wool";
            text.width = 2;
            text.height = 1;
            text.bitmap = new byte[] { 1 };
            if (picture) {
                text.picture = new HologramPictureBox();
                text.picture.filename = "native-mixed.png";
                text.picture.width = text.picture.originalWidth = text.picture.sourceWidth = 2;
                text.picture.height = text.picture.originalHeight = text.picture.sourceHeight = text.picture.shortSide = 1;
                text.picture.palette = 1;
                text.picture.source = new int[] { 0xffdddddd, 0xff963430 };
            }
            NBTTagCompound action = request();
            action.setLong("textRequest", 321);
            action.setTag("textBox", text.write());
            HologramTextWorkflow.apply(session, action);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (session.attachmentTask != null && System.nanoTime() < deadline) {
                session.attachmentTask.tick();
                if (session.attachmentTask != null && !session.attachmentTask.ready()) Thread.sleep(1);
            }
            SimpleAssert.that(
                session.textPlacementAck.getBoolean("accepted"),
                "actual generated attachment was accepted: " + session.status);
        }

        void exportAcceptedAttachment() throws Exception {
            session.openPurpose = "blueprintEdit";
            session.workspaceTab = "blueprint";
            NBTTagCompound state = HologramSnapshots.basicState(session);
            HologramSnapshots.writeBlueprintScalars(session, state);
            net.minecraft.nbt.NBTTagList cells = new net.minecraft.nbt.NBTTagList();
            for (int i = 0; i < session.capture.cells.size(); i++)
                cells.appendTag(HologramSnapshots.cellRow(session, i, true));
            state.setTag("cells", cells);
            HologramSnapshotMetadataTask metadata = new HologramSnapshotMetadataTask(session, state);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!metadata.step(32, Long.MAX_VALUE)) {
                if (System.nanoTime() > deadline) throw new AssertionError("accepted attachment metadata timed out");
                Thread.sleep(1);
            }
            java.util.List<Runnable> queue = new java.util.ArrayList<>();
            HologramSnapshotEncoding.Job encoded = HologramSnapshotEncoding.submit(state, queue::add);
            Thread encoder = new Thread(queue.remove(0), "unsaved-placement-snapshot-encoder");
            encoder.start();
            encoder.join(10000);
            SimpleAssert.that(
                encoded.done && encoded.failure == null && encoded.bytes != null,
                "production snapshot encoder completed accepted attachment wire data");
            NBTTagCompound wire = HologramSnapshotCodec.decode(encoded.bytes);
            SimpleAssert.that(
                wire.getTagList("structures", 10)
                    .getCompoundTagAt(0)
                    .hasKey("textBox", 10),
                "the exported wire fixture contains the expanded attachment editor definition");
            com.miaokatze.gtmb.client.hologram.HologramUnsavedPlacementUiRegression.run(wire);
            Files.write(java.nio.file.Paths.get("accepted-attachment-draft.nbt"), encoded.bytes);
        }

        void attach(boolean picture, int x) throws IOException {
            HologramTextBox text = new HologramTextBox();
            text.id = (picture ? HologramPictureBox.PREFIX : HologramTextBox.PREFIX) + UUID.randomUUID();
            text.width = text.height = 1;
            text.pixelX = text.pixelY = text.thickness = 16;
            text.bitmap = new byte[] { 1 };
            text.material = "minecraft:wool";
            text.x = x;
            if (picture) {
                text.picture = new HologramPictureBox();
                text.picture.filename = "fixture.png";
                text.picture.width = text.picture.height = text.picture.originalWidth = text.picture.originalHeight = 1;
                text.picture.sourceWidth = text.picture.sourceHeight = text.picture.shortSide = 1;
                text.picture.palette = 1;
                text.picture.source = new int[] { 0xffdddddd };
            }
            HologramTextBox.Cell geometry = text.generate()
                .get(0);
            SimpleAssert.that(geometry.fullBlock, "fixture uses generated full-block attachment geometry");
            HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
            cell.userPosition = true;
            cell.block = Block.getBlockFromName(geometry.materials[0]);
            cell.meta = geometry.metadata[0];
            cell.actualStack = new ItemStack(cell.block, 1, cell.meta);
            cell.blueprintBlock = geometry.materials[0];
            cell.blueprintItem = net.minecraft.item.Item.itemRegistry.getNameForObject(cell.actualStack.getItem());
            cell.blueprintDamage = cell.actualStack.getItemDamage();
            HologramBlueprintData.setMemberships(cell, Collections.singletonList(text.id));
            HologramTextWorkflow.stageAttachment(
                session,
                session.virtualPreset,
                Collections.singletonList(cell),
                Collections.emptySet(),
                Collections.emptyMap());
            HologramPresets.Structure group = new HologramPresets.Structure();
            group.id = text.id;
            group.sourceMod = "gtmb";
            group.textBoxNbt = HologramBlueprintNbt.encodeCaptured(text.write());
            group.x = x;
            session.virtualPreset.structures.add(group);
            session.blueprintSource = "draft";
            HologramAuthoringWorkflow.blueprintEdited(session, "geometry");
        }

        @Override
        public void close() {
            session.attachmentTask = null;
            session.capturePending = false;
            HologramWorkspace.clear();
        }
    }
}

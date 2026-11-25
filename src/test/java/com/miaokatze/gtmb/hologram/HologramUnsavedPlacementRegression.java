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
        try(Fixture fixture=new Fixture()){
            for(boolean attached:new boolean[]{false,true}){
                if(attached)fixture.attach(false,0);
                HologramSession source=fixture.session;
                long before=source.planRevision;
                var request=fixture.request();
                request.setString("op","placeCurrentBlueprint");
                HologramActionDispatcher.handle(fixture.player,request);
                SimpleAssert.that(HologramWorkspace.find(fixture.player.getUniqueID())==source
                    && !source.virtualPlacement && source.job==0&&source.planRevision==before
                    && source.status.contains("保存为原生"),"retired formal draft button does not create a job");
            }
        }finally{HologramWorkspace.clear();}
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

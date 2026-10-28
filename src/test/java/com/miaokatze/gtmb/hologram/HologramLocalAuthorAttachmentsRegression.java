package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Attachment source fidelity and local lifecycle without a world, player, Session or wire ACK. */
public final class HologramLocalAuthorAttachmentsRegression {

    private HologramLocalAuthorAttachmentsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorAttachmentsRegression.class, args)) return;
        run();
        System.out.println("Local author attachment regression passed");
    }

    public static void run() throws Exception {
        HologramTextBox text = new HologramTextBox();
        text.id = HologramTextBox.PREFIX + "11111111-1111-1111-1111-111111111111";
        text.width = text.height = 1;
        text.bitmap = new byte[] { 1 };
        NBTTagCompound original = text.write();
        original.setString("vendorExtension", "keep");
        HologramTextBox edited = HologramTextBox.read(original);
        edited.turn(false);
        edited.y = 1024;
        edited.validate();
        SimpleAssert.eq(
            "keep",
            edited.write()
                .getString("vendorExtension"),
            "unknown definition tags survive edits");
        SimpleAssert.eq(0, original.getIntArray("turns").length, "editing never mutates caller-owned NBT");

        JsonObject group = new JsonObject();
        group.addProperty("id", text.id);
        group.addProperty("x", 3);
        group.addProperty("y", 1024);
        group.addProperty("z", -7);
        group.addProperty("textBoxNbt", HologramBlueprintNbt.encodeCaptured(original));
        group.addProperty("vendorGroup", "keep");
        JsonObject cell = new JsonObject();
        cell.addProperty("x", 3);
        cell.addProperty("y", 1024);
        cell.addProperty("z", -7);
        cell.addProperty("block", "minecraft:stone");
        cell.addProperty("structureId", text.id);
        JsonArray groups = new JsonArray();
        groups.add(group);
        JsonArray cells = new JsonArray();
        cells.add(cell);
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.add("structures", groups);
        root.add("cells", cells);
        AuthorDocument document = AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
        long selected = document.cells()
            .iterator()
            .next()
            .id();
        NBTTagCompound selectedDefinition = HologramLocalAuthorAttachments
            .selectedDefinition(document, Collections.singleton(selected));
        SimpleAssert
            .eq(1024, selectedDefinition.getInteger("y"), "author structure position overrides stale encoded origin");
        SimpleAssert
            .eq("keep", selectedDefinition.getString("vendorExtension"), "selection reads full source definition");
        NBTTagCompound rebuilt = text.write();
        rebuilt.setString("text", "new text");
        NBTTagCompound merged = HologramLocalAuthorAttachments.mergeDefinition(document, rebuilt);
        SimpleAssert.eq("new text", merged.getString("text"), "edited known fields override original");
        SimpleAssert
            .eq("keep", merged.getString("vendorExtension"), "rebuilding widgets cannot drop source extension fields");

        AuthorEditor editor = new AuthorEditor(document, 4);
        HologramLocalAuthorAttachments owner = new HologramLocalAuthorAttachments();
        SimpleAssert.eq(null, owner.tick(), "idle tick has no fabricated result");
        SimpleAssert.eq(null, owner.cancel("cancel"), "idle cancel has no fabricated acknowledgement");
        NBTTagCompound stale = new NBTTagCompound();
        stale.setLong("planRevision", document.revision() + 1);
        stale.setTag("textBox", text.write());
        boolean refused = false;
        try {
            owner.start(editor, stale);
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && !owner.busy(), "stale admission fails before any native or worker task");
        SimpleAssert
            .that(editor.current() == document && !editor.canUndo(), "failed admission preserves source and history");
        owner.close();

        HologramPictureBox picture = new HologramPictureBox();
        picture.filename = "pixel.png";
        picture.originalWidth = picture.originalHeight = picture.sourceWidth = picture.sourceHeight = 1;
        picture.width = picture.height = picture.shortSide = 1;
        picture.source = new int[] { 0xff123456 };
        NBTTagCompound pictureTag = picture.write();
        pictureTag.setString("vendorPicture", "keep nested");
        HologramPictureBox restored = HologramPictureBox.read(pictureTag);
        restored.brightness = 10;
        SimpleAssert.eq(
            "keep nested",
            restored.write()
                .getString("vendorPicture"),
            "picture extensions survive settings edits");
    }
}

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Iterator;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;

/** Reuses the attachment transaction on a private editor; never creates a Session, world or asset authority. */
public final class HologramAuthorAttachmentReview implements HologramAuthorServerReview.NativeTask {

    private final HologramLocalAuthorAttachments attachments = new HologramLocalAuthorAttachments();
    private Iterator<JsonObject> remaining;
    private JsonArray cells;
    private HologramAuthorServerReview.Result result;
    private boolean closed, complete;

    private HologramAuthorAttachmentReview(AuthorDocument frozen, NBTTagCompound request) throws IOException {
        if (frozen == null || !frozen.editable() || request == null || !request.hasKey("textBox", 10))
            throw new IOException("Missing editable attachment review source/definition");
        // The uploaded draft may be reopened with a different local revision. Transport already binds the
        // original revision/hash; this private transaction is fenced to the actual frozen server document.
        NBTTagCompound action = (NBTTagCompound) request.copy();
        action.setLong("planRevision", frozen.revision());
        attachments.start(new AuthorEditor(frozen, 1), action);
    }

    public static HologramAuthorAttachmentReview start(AuthorDocument frozen, NBTTagCompound request)
        throws IOException {
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            return new HologramAuthorAttachmentReview(frozen, request);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot start server attachment review: " + failure.getMessage(), failure);
        }
    }

    /** Existing bounded geometry worker and native slices, followed by cooperative delta assembly. */
    @Override
    public boolean step() throws IOException {
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            if (closed) throw new IOException("Attachment review was cancelled");
            if (complete) return true;
            if (result == null) {
                HologramLocalAuthorAttachments.Result prepared = attachments.tick();
                if (prepared == null) return false;
                if (!prepared.accepted) throw new IOException(prepared.message);
                NBTTagCompound report = new NBTTagCompound();
                report.setLong("textRequest", prepared.request);
                report.setString("message", prepared.message);
                JsonObject delta = new JsonObject();
                delta.add("group", prepared.preparedGroup);
                cells = new JsonArray();
                delta.add("cells", cells);
                remaining = prepared.preparedCells.iterator();
                result = new HologramAuthorServerReview.Result(report, delta, null);
            }
            long deadline = System.nanoTime() + 2_000_000L;
            for (int work = 0; work < 256 && remaining.hasNext() && System.nanoTime() < deadline; work++)
                cells.add(remaining.next());
            complete = !remaining.hasNext();
            return complete;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Server attachment review failed: " + failure.getMessage(), failure);
        }
    }

    @Override
    public HologramAuthorServerReview.Result result() throws IOException {
        if (!complete || closed) throw new IOException("Attachment review is not complete");
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        attachments.close();
        remaining = null;
        cells = null;
        result = null;
    }
}

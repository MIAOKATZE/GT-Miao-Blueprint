// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.client.miaovisonlite.MiaoVisonLiteMaterialBatch.OutlineFrame;
import com.miaokatze.miaokatzecore.mixin.render.TessellatorAccessor;

/** Resident wide-line packets for the append-only prefix of one projected outline. */
public final class MiaoVisonLiteOutline implements AutoCloseable {

    private static final long MAX_RESIDENT_BYTES = 224L * 1024 * 1024;
    private static final long MAX_RECORD_BYTES_PER_FRAME = 16L * 1024 * 1024;
    private final List<Packet> packets = new ArrayList<>();
    private Object source, pose, outlines, selection;
    private OutlineFrame frame;
    private int selected, selectionVersion, density, seenOutlines, coveredOutlines;
    private long depth;
    private long retainedBytes, recordedBytes;
    private boolean recording;
    private int replayedVertices, recordings;

    /** Draws the unchanged leading batches before the caller emits its remaining outlines in original order. */
    public int drawPrefix(Tessellator tess, Object source, Object pose, Object outlines, int count, Object selection,
        int selectionVersion, int selected, float density, double depth, boolean allowRecording) {
        replayedVertices = recordings = 0;
        recordedBytes = 0;
        OutlineFrame next = MiaoVisonLiteMaterialBatch.outlineFrame(tess);
        if (next == null) {
            recording = false;
            return 0;
        }
        int densityBits = Float.floatToRawIntBits(density);
        long depthBits = Double.doubleToRawLongBits(depth);
        boolean same = frame != null && frame.matches(next)
            && this.source == source
            && this.pose == pose
            && this.outlines == outlines
            && this.selection == selection
            && this.selectionVersion == selectionVersion
            && this.selected == selected
            && this.density == densityBits
            && this.depth == depthBits
            && count >= seenOutlines;
        if (!same) {
            close();
            frame = next;
            this.source = source;
            this.pose = pose;
            this.outlines = outlines;
            this.selection = selection;
            this.selectionVersion = selectionVersion;
            this.selected = selected;
            this.density = densityBits;
            this.depth = depthBits;
        }
        seenOutlines = count;
        recording = same && allowRecording;
        TessellatorAccessor access = (TessellatorAccessor) tess;
        for (Packet packet : packets) {
            tess.startDrawing(GL11.GL_LINES);
            access.miaokatze$setColor(packet.color);
            access.miaokatze$setHasColor(true);
            tess.draw();
            packet.geometry.draw();
            replayedVertices += packet.vertices;
        }
        return coveredOutlines;
    }

    /**
     * Records and draws one complete original batch. The caller submits the unchanged live batch on refusal.
     * Never record a later batch if any earlier one had to stay live.
     */
    public boolean record(Tessellator tess, int vertices, int nextOutline) {
        if (!recording) return false;
        long bytes = (long) vertices * frame.bytesPerVertex;
        if (retainedBytes + bytes > MAX_RESIDENT_BYTES || recordedBytes + bytes > MAX_RECORD_BYTES_PER_FRAME) {
            recording = false;
            return false;
        }
        TessellatorAccessor access = (TessellatorAccessor) tess;
        if (!access.miaokatze$getIsDrawing() || access.miaokatze$getDrawMode() != GL11.GL_LINES
            || access.miaokatze$getRawBufferIndex() != vertices * 8
            || access.miaokatze$getVertexCount() != vertices
            || access.miaokatze$getAddedVertices() != vertices
            || !access.miaokatze$getHasColor()
            || access.miaokatze$getHasTexture()
            || access.miaokatze$getHasBrightness()
            || access.miaokatze$getHasNormals()) {
            recording = false;
            return false;
        }
        MiaoVisonLiteDisplayList geometry = MiaoVisonLiteDisplayList.allocate();
        if (geometry == null) {
            recording = false;
            return false;
        }
        int color = access.miaokatze$getColor();
        try {
            // The live drawer supplies the same default channels before expanding each line as triangles.
            MiaoVisonLiteMaterialBatch.expandOutline(tess, frame.frame);
            geometry.begin();
            tess.draw();
            geometry.finish();
            geometry.draw();
        } catch (RuntimeException | Error failure) {
            try {
                geometry.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            access.miaokatze$setColor(color);
            access.miaokatze$setHasColor(true);
            access.miaokatze$setHasTexture(false);
            access.miaokatze$setHasBrightness(false);
            access.miaokatze$setHasNormals(false);
        }
        packets.add(new Packet(geometry, color, vertices));
        coveredOutlines = nextOutline;
        retainedBytes += bytes;
        recordedBytes += bytes;
        recordings++;
        return true;
    }

    public int replayedVertices() {
        return replayedVertices;
    }

    public int recordings() {
        return recordings;
    }

    public void restoreEmptyTail(Tessellator tess) {
        if (replayedVertices == 0) return;
        TessellatorAccessor access = (TessellatorAccessor) tess;
        access.miaokatze$setColor(packets.get(packets.size() - 1).color);
        access.miaokatze$setHasColor(true);
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    @Override
    public void close() {
        if (frame != null && frame.currentContext()) for (Packet packet : packets) packet.geometry.close();
        packets.clear();
        source = pose = outlines = selection = null;
        frame = null;
        seenOutlines = coveredOutlines = 0;
        retainedBytes = 0;
        recording = false;
    }

    private static final class Packet {

        final MiaoVisonLiteDisplayList geometry;
        final int color, vertices;

        Packet(MiaoVisonLiteDisplayList geometry, int color, int vertices) {
            this.geometry = geometry;
            this.color = color;
            this.vertices = vertices;
        }
    }
}

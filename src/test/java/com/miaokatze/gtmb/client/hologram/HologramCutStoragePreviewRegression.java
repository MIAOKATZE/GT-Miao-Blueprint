// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.hologram.HologramCutProtocol;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Browser reads cannot revive a previous selection or a closed/save-switched browser. */
public final class HologramCutStoragePreviewRegression {

    private HologramCutStoragePreviewRegression() {}

    public static void run() {
        HologramCutStoragePreviewState browser = new HologramCutStoragePreviewState();
        SimpleAssert.that(!browser.needsRequest(), "an empty directory never requests raw structures");
        browser.select("A");
        SimpleAssert.that(browser.needsRequest(), "only selecting a structure arms its raw request");
        browser.begin("A-first", 0);
        for (int frame = 0; frame < 1000; frame++)
            SimpleAssert.that(!browser.needsRequest(), "redrawing never repeats a pending raw request");
        browser.select("B");
        browser.begin("B", 1);
        SimpleAssert.that(!browser.accept("A-first"), "late A cannot replace selected B");
        browser.select("A");
        browser.begin("A-second", 2);
        SimpleAssert.that(!browser.accept("A-first"), "A/B/A still rejects the first A request");
        SimpleAssert.that(!browser.accept("B"), "late B cannot replace selected A");
        SimpleAssert.that(browser.accept("A-second"), "only the exact current request is accepted");
        SimpleAssert.that(!browser.accept("A-second"), "duplicate chunks/ACK cannot install a second renderer");
        SimpleAssert.that(!browser.needsRequest(), "a completed raw preview is reused without frame polling");
        browser.select("B");
        browser.begin("B-timeout", 10);
        SimpleAssert.that(!browser.expire(HologramCutRequests.READ_TIMEOUT_NS + 9), "read has its full budget");
        SimpleAssert.that(browser.expire(HologramCutRequests.READ_TIMEOUT_NS + 10), "raw request times out once");
        SimpleAssert.that(!browser.accept("B-timeout"), "timed-out raw reply remains retired");
        SimpleAssert.that(!browser.needsRequest(), "timeout never creates an automatic request loop");
        browser.retry();
        SimpleAssert.that(browser.needsRequest(), "the explicit retry control re-arms one request");
        browser.begin("B-retry", 20);
        browser.select("");
        SimpleAssert.that(!browser.accept("B-retry"), "consuming/removing a directory entry retires its preview");
        SimpleAssert.that(!browser.needsRequest(), "a consumed directory entry is not fetched again");

        browser.select("A");
        browser.begin("closed-save", 30);
        browser.close();
        SimpleAssert.that(!browser.accept("closed-save"), "close or world/connection invalidation retires every read");
        SimpleAssert.that(!browser.needsRequest(), "closing cannot arm a background request");
        HologramCutStoragePreviewState reopened = new HologramCutStoragePreviewState();
        reopened.select("A");
        reopened.begin("new-save", 40);
        SimpleAssert.that(!reopened.accept("closed-save"), "a same-ID structure in another save has a new identity");
        SimpleAssert.that(reopened.accept("new-save"), "the new save only accepts its own request");

        String protocolId = "47544d42-6d75-4567-8123-123456789abc";
        SimpleAssert.eq(
            protocolId,
            UUID.fromString(protocolId)
                .toString(),
            "browser namespace is a canonical UUID");
        SimpleAssert.that(
            protocolId.startsWith(HologramCutStoragePreviewState.PREFIX),
            "retired browser replies remain distinguishable from placement requests");

        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setString("requestId", protocolId);
        legacy.setTag("preview", new NBTTagCompound());
        SimpleAssert.that(
            HologramCutStoragePreview.receive(legacy),
            "late legacy browser replies are consumed, not passed to ordinary placement accounting");
        legacy.setString("requestId", "09544d42-6d75-4567-8123-123456789abc");
        SimpleAssert.that(!HologramCutStoragePreview.receive(legacy), "real action acknowledgements are not claimed");

        NBTTagCompound passiveRequest = new NBTTagCompound();
        passiveRequest.setString("action", "list");
        passiveRequest.setString("id", "");
        SimpleAssert.that(
            HologramCutClient.prepareStorageControl(passiveRequest) && HologramCutProtocol.passiveList(passiveRequest)
                && passiveRequest.hasKey("cutMetadataOnly", 1),
            "the real client sender stamps the ordinary passive LIST so the old strict parser refuses it");
        NBTTagCompound retry = new NBTTagCompound();
        retry.setString("action", "list");
        retry.setString("id", protocolId);
        SimpleAssert.that(
            HologramCutClient.prepareStorageControl(retry) && !retry.hasKey("cutMetadataOnly")
                && protocolId.equals(retry.getString("id")),
            "manual exact Retry keeps its original targeted status/recovery shape");
        NBTTagCompound oldControls = new NBTTagCompound();
        HologramCutProtocol.writeBoundsProof(oldControls, UUID.fromString(protocolId));
        oldControls.setString("action", "openWorkspace");
        SimpleAssert.that(
            !HologramCutClient.prepareStorageControl(oldControls),
            "the stock sender cannot open a remote Cut manager even with a typed peer proof");
        oldControls.setString("action", "preview");
        SimpleAssert.that(
            !HologramCutClient.prepareStorageControl(oldControls),
            "the local pane has no legacy raw preview wire fallback");

        UUID endpoint = UUID.fromString(protocolId);
        NBTTagCompound actualList = new NBTTagCompound();
        actualList.setBoolean("success", true);
        actualList.setTag("entries", new NBTTagList());
        SimpleAssert.that(
            HologramCutClient.acceptedBoundsProof("list", true, actualList) == null,
            "a successful old-peer list without typed proof does not arm mutations");
        HologramCutProtocol.writeBoundsProof(actualList, endpoint);
        SimpleAssert.eq(
            endpoint,
            HologramCutClient.acceptedBoundsProof("list", true, actualList),
            "exact typed version and endpoint capability return only on the accepted LIST path");
        SimpleAssert.that(
            HologramCutClient.acceptedBoundsProof("cut", true, actualList) == null
                && HologramCutClient.acceptedBoundsProof("preview", true, actualList) == null
                && HologramCutClient.acceptedBoundsProof("list", false, actualList) == null,
            "no unsolicited/non-directory ACK can grant the write capability");
        actualList.setLong("cutBoundsProtocol", 1L);
        SimpleAssert.that(
            HologramCutClient.acceptedBoundsProof("list", true, actualList) == null,
            "coercible numeric versions are not typed proof");
        HologramCutProtocol.writeBoundsProof(actualList, endpoint);
        actualList.setString("cutBoundsToken", "1-1-1-1-1");
        SimpleAssert.that(
            HologramCutClient.acceptedBoundsProof("list", true, actualList) == null,
            "noncanonical or missing endpoint values cannot arm stock clipboard writes");

        Object world = new Object(), handler = new Object(), actor = new Object();
        Object stack = new Object(), item = new Object();
        HologramCutClient.ToolProof bound = new HologramCutClient.ToolProof(
            world,
            handler,
            actor,
            stack,
            item,
            endpoint,
            2,
            0);
        SimpleAssert.that(
            bound.matches(world, handler, actor, stack, item, endpoint, 2, 0),
            "the unchanged final clipboard tool proof still matches");
        SimpleAssert.that(
            !bound.matches(world, handler, actor, new Object(), item, endpoint, 2, 0)
                && !bound.matches(world, handler, actor, stack, new Object(), endpoint, 2, 0)
                && !bound.matches(world, handler, actor, stack, item, endpoint, 3, 0)
                && !bound.matches(world, handler, actor, stack, item, UUID.randomUUID(), 2, 0)
                && !bound.matches(new Object(), handler, actor, stack, item, endpoint, 2, 0)
                && !bound.matches(world, new Object(), actor, stack, item, endpoint, 2, 0)
                && !bound.matches(world, handler, new Object(), stack, item, endpoint, 2, 0)
                && !bound.matches(world, handler, actor, stack, item, endpoint, 2, 1),
            "same-class replacement stack, slot, token, world, handler or actor cannot inherit a previous confirmation");

        NBTTagCompound received = new NBTTagCompound();
        received.setString("id", protocolId);
        for (String key : new String[] { "x", "y", "z", "dimension" }) received.setInteger(key, 0);
        received.setInteger("sizeX", 2);
        received.setInteger("sizeY", 3);
        received.setInteger("sizeZ", 5);
        received.setLong("sequence", 71);
        received.setTag("cells", new NBTTagList());
        received.setTag("preview", new NBTTagCompound());
        NBTTagCompound safe = HologramCutClient.directoryRow(received);
        SimpleAssert.that(!safe.hasKey("cells") && !safe.hasKey("preview"), "older directory payload is not retained");
        HologramCutClient.ClipBounds bounds = HologramCutClient.ClipBounds.read(safe);
        SimpleAssert.that(
            bounds != null && Arrays.equals(
                new int[] { 12, 20, 31, 16, 22, 32 },
                bounds.boxAt(new Transform(1, 0, 0, 0), new int[] { 12, 20, 31 })),
            "local rotation renders exact transformed inclusive bounds");
        SimpleAssert.that(
            bounds.boxAt(new Transform(1, 0, 0, 0), new int[] { 12, 254, 31 }) == null,
            "world-height overflow cannot yield confirmable bounds");
        safe.setLong("sequence", 72);
        SimpleAssert.that(
            !bounds.sameEntry(HologramCutClient.ClipBounds.read(safe)),
            "same ID at a newer revision is not the selected confirmed entry");
        safe.setInteger("sequence", 71);
        SimpleAssert.that(
            HologramCutClient.ClipBounds.read(safe) == null,
            "wrongly typed sequence cannot arm bounds-only placement");
    }
}

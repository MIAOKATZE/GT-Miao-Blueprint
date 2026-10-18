package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

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
    }
}

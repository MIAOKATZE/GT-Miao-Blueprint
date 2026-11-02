package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramDemolitionProtocolRegression {

    private HologramDemolitionProtocolRegression() {}

    public static void run() {
        try {
            verify();
        } catch (IOException failure) {
            throw new AssertionError("Demolition request protocol", failure);
        }
    }

    private static void verify() throws IOException {
        NBTTagCompound start = request("demolish");
        HologramAreaSelection selection = new HologramAreaSelection();
        selection.point(1, 1, 60, 2);
        selection.point(2, 3, 62, 4);
        start.setTag("areaSelection", selection.snapshot());
        SimpleAssert
            .eq(start.getString("requestId"), HologramDemolitionProtocol.validate(start), "canonical request admitted");
        HologramAreaSelection decoded = HologramDemolitionProtocol.selection(start);
        SimpleAssert.that(decoded.contains(2, 61, 3) && !decoded.contains(9, 61, 3), "request keeps the chosen region");
        for (String foreign : new String[] { "name", "preview", "entries", "skipNamePrompt", "originX" }) {
            NBTTagCompound invalid = (NBTTagCompound) start.copy();
            invalid.setString(foreign, "not accepted");
            rejects(
                () -> HologramDemolitionProtocol.validate(invalid),
                "demolition rejects cut/storage field " + foreign);
        }
        NBTTagCompound wrongType = (NBTTagCompound) start.copy();
        wrongType.setString("areaSelection", "invalid");
        rejects(() -> HologramDemolitionProtocol.validate(wrongType), "selection must be a compound");
        NBTTagCompound unknown = (NBTTagCompound) start.copy();
        unknown.setString("action", "demolishUnknown");
        rejects(() -> HologramDemolitionProtocol.validate(unknown), "unknown action never enters demolition");
        NBTTagCompound noId = (NBTTagCompound) start.copy();
        noId.setString("requestId", "bad request");
        rejects(() -> HologramDemolitionProtocol.validate(noId), "invalid request nonce rejected");
        NBTTagCompound status = request("demolitionStatus");
        status.setString("id", start.getString("requestId"));
        HologramDemolitionProtocol.validate(status);
        status.setTag("areaSelection", selection.snapshot());
        rejects(() -> HologramDemolitionProtocol.validate(status), "status is read-only and carries no selection");
        HologramDemolitionProtocol.validate(request("demolitionCancel"));
        replay();
    }

    private static void replay() throws IOException {
        HologramDemolitionProtocol.History history = new HologramDemolitionProtocol.History();
        for (String action : new String[] { "demolish", "demolitionCancel" }) {
            String id = UUID.randomUUID()
                .toString();
            HologramDemolitionProtocol.Receipt first = history.begin(id, action);
            SimpleAssert.that(!first.accepted, "reservation already consumes a rejected mutation nonce");
            rejects(() -> history.begin(id, action), "failed mutation is never retried by duplicate packet");
            first.accepted = true;
            rejects(() -> history.begin(id, action), "successful mutation is never replayed");
            SimpleAssert.that(
                history.find(id) == first && history.find(id).accepted,
                "status retains the same accepted receipt");
            rejects(() -> history.begin(id, "other"), "nonce cannot be repurposed for a later operation");
        }
        SimpleAssert.that(history.find("unknown") == null, "unknown status request has no accepted mutation");
    }

    private static NBTTagCompound request(String action) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("action", action);
        n.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        n.setString("id", "");
        return n;
    }

    private static void rejects(Checked operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IOException expected) {}
    }

    private interface Checked {

        void run() throws IOException;
    }
}

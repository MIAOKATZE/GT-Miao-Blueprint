package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutContextRegression {

    private HologramCutContextRegression() {}

    public static void run() {
        for (boolean sameWorld : new boolean[] { false, true }) for (boolean held : new boolean[] { false, true }) {
            SimpleAssert.that(
                HologramCutService.acceptsContext("list", sameWorld, held),
                "owner directory read survives inventory/world sync races");
            for (String operation : new String[] { "cut", "paste", "undo", "preview" }) SimpleAssert.eq(
                sameWorld && held,
                HologramCutService.acceptsContext(operation, sameWorld, held),
                "write/raw-preview permissions remain bound to world and held projector: " + operation);
        }
        String completed = UUID.randomUUID()
            .toString(),
            absent = UUID.randomUUID()
                .toString();
        NBTTagCompound durable = new NBTTagCompound(), record = new NBTTagCompound();
        record.setString("id", completed);
        NBTTagList records = new NBTTagList();
        records.appendTag(record);
        durable.setTag("requests", records);
        NBTTagCompound before = (NBTTagCompound) durable.copy();
        try {
            NBTTagCompound response = new NBTTagCompound();
            HologramCutService.reportRequestResult(response, durable, completed);
            SimpleAssert.eq(completed, response.getString("reconciledRequestId"), "report the exact original request");
            SimpleAssert.that(response.getBoolean("requestCompleted"), "durable completion resolves a lost write ACK");
            HologramCutService.reportRequestResult(response, durable, absent);
            SimpleAssert.that(!response.getBoolean("requestCompleted"), "a dropped request is not reported complete");
            SimpleAssert.that(before.equals(durable), "checking outcomes never changes durable requests or entries");
        } catch (IOException failure) {
            throw new AssertionError("canonical request result lookup", failure);
        }
    }
}

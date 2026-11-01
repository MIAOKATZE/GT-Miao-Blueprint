package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Direct world gestures must carry the displayed source epoch without upgrading an older plan. */
public final class HologramSemanticRequestRegression {

    public static void run() {
        HologramState previous = HologramClient.state;
        try {
            NBTTagCompound state = new NBTTagCompound();
            state.setString("session", "semantic-request");
            state.setLong("sourceRevision", 9);
            state.setLong("planRevision", 12);
            HologramClient.state = new HologramState(state);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", "semantic-request");
            request.setLong("planRevision", 7);
            HologramClient.stampSemanticRevision(request);
            SimpleAssert.that(request.getLong("sourceRevision") == 9, "world gesture carries source revision");
            SimpleAssert.that(request.getLong("semanticPlanRevision") == 7, "stale plan is never upgraded");
            request.setLong("sourceRevision", 3);
            request.setLong("semanticPlanRevision", 4);
            HologramClient.stampSemanticRevision(request);
            SimpleAssert.that(
                request.getLong("sourceRevision") == 3 && request.getLong("semanticPlanRevision") == 4,
                "captured window request retains its original revisions");
            NBTTagCompound foreign = new NBTTagCompound();
            foreign.setString("session", "another-session");
            HologramClient.stampSemanticRevision(foreign);
            SimpleAssert.that(!foreign.hasKey("sourceRevision"), "another session cannot borrow the active epoch");
            state.removeTag("sourceRevision");
            HologramClient.state = new HologramState(state);
            NBTTagCompound legacy = new NBTTagCompound();
            legacy.setString("session", "semantic-request");
            HologramClient.stampSemanticRevision(legacy);
            SimpleAssert.that(!legacy.hasKey("sourceRevision"), "legacy requests retain their existing format");
        } finally {
            HologramClient.state = previous;
        }
    }
}

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** A quote receipt cannot replace geometry or acknowledge an unrelated workstation action. */
final class HologramMaterialQuoteUpdates {

    private static final String[] MATERIAL_KEYS = { "placementReviewNonce", "materialQuote", "decorativeVoucher",
        "materialOrder", "materialOrderingAvailable", "status", "missing", "blueprintResourceMissing",
        "blueprintAwaitingConfirmation", "forcePlacementReady", "forcePlacementNonce" };

    private HologramMaterialQuoteUpdates() {}

    static HologramState apply(HologramState base, NBTTagCompound receipt) {
        if (base == null || !receipt.getBoolean("materialQuoteOnly")
            || !base.session.equals(receipt.getString("session"))
            || base.data.getLong("planRevision") != receipt.getLong("planRevision")
            || receipt.getLong("uiSequence") < base.data.getLong("uiSequence")
            || receipt.getLong("uiSequence") < base.data.getLong("materialQuoteSequence")
            || receipt.getLong("placementReviewNonce") < base.data.getLong("placementReviewNonce")) return null;
        NBTTagCompound changes = new NBTTagCompound();
        changes.setLong("materialQuoteSequence", receipt.getLong("uiSequence"));
        for (String key : MATERIAL_KEYS) if (receipt.hasKey(key)) changes.setTag(key, receipt.getTag(key));
        return base.withMetadata(changes);
    }

    /** A full geometry transfer started before a voucher click must not roll its later receipt back. */
    static HologramState retain(HologramState next, HologramState previous) {
        if (previous == null || !next.session.equals(previous.session)
            || previous.data.getLong("materialQuoteSequence")
                <= Math.max(next.data.getLong("uiSequence"), next.data.getLong("materialQuoteSequence")))
            return next;
        if (next.data.getLong("planRevision") != previous.data.getLong("planRevision")
            || previous.data.getLong("placementReviewNonce") < next.data.getLong("placementReviewNonce")) {
            // Keep only the command watermark when a genuinely newer plan/review supersedes the quote.
            NBTTagCompound watermark = new NBTTagCompound();
            watermark.setLong("materialQuoteSequence", previous.data.getLong("materialQuoteSequence"));
            return next.withMetadata(watermark);
        }
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("materialQuoteOnly", true);
        receipt.setString("session", previous.session);
        receipt.setLong("planRevision", previous.data.getLong("planRevision"));
        receipt.setLong("uiSequence", previous.data.getLong("materialQuoteSequence"));
        for (String key : MATERIAL_KEYS) if (previous.data.hasKey(key)) receipt.setTag(key, previous.data.getTag(key));
        HologramState retained = apply(next, receipt);
        return retained == null ? next : retained;
    }
}

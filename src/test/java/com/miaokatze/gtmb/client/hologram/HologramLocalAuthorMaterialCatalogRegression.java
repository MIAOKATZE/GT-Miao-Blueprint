package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Pure request/result identity checks; does not create a player, world or author server session. */
public final class HologramLocalAuthorMaterialCatalogRegression {

    private HologramLocalAuthorMaterialCatalogRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorMaterialCatalogRegression.class, args)) return;
        run();
        System.out.println("Local author material catalog correlation regression passed");
    }

    public static void run() {
        NBTTagCompound request = new NBTTagCompound();
        for (String key : new String[] { "token", "document", "player", "toolFingerprint" })
            request.setString(key, key);
        request.setLong("revision", 3L);
        request.setInteger("toolSlot", 2);
        SimpleAssert.that(
            HologramLocalAuthorMaterialCatalog.matches(request, (NBTTagCompound) request.copy()),
            "exact result matches");
        for (String key : new String[] { "token", "document", "player", "toolFingerprint" }) {
            NBTTagCompound stale = (NBTTagCompound) request.copy();
            stale.setString(key, "other");
            SimpleAssert.that(!HologramLocalAuthorMaterialCatalog.matches(request, stale), "reject stale " + key);
        }
        NBTTagCompound stale = (NBTTagCompound) request.copy();
        stale.setLong("revision", 4L);
        SimpleAssert
            .that(!HologramLocalAuthorMaterialCatalog.matches(request, stale), "reject changed document revision");
        stale = (NBTTagCompound) request.copy();
        stale.setInteger("toolSlot", 1);
        SimpleAssert.that(!HologramLocalAuthorMaterialCatalog.matches(request, stale), "reject changed held tool slot");
    }
}

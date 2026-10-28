package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Detached tests of public sample projection and the only permitted real-tool settings fields. */
public final class HologramAuthorMaterialCatalogRegression {

    private HologramAuthorMaterialCatalogRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorMaterialCatalogRegression.class, args)) return;
        run();
        System.out.println("Author material catalog regression passed");
    }

    public static void run() throws Exception {
        ItemStack source = new ItemStack(Blocks.stone, 23);
        NBTTagCompound privateTag = new NBTTagCompound();
        privateTag.setString("encryptionKey", "never transmit");
        privateTag.setString("ownerUUID", "private owner");
        privateTag.setString("Inventory", "not a marker payload");
        source.setTagCompound(privateTag);
        ItemStack sealed = new ItemStack(Blocks.dirt);
        NBTTagCompound seal = new NBTTagCompound();
        seal.setTag(HologramRecovery.TAG, new NBTTagCompound());
        sealed.setTagCompound(seal);
        NBTTagList rows = HologramAuthorMaterialCatalog.publicSamples(Arrays.asList(source, source.copy(), sealed));
        SimpleAssert.eq(1, rows.tagCount(), "public samples deduplicate type and reject asset seals");
        NBTTagCompound row = rows.getCompoundTagAt(0);
        SimpleAssert.eq(
            2,
            row.func_150296_c()
                .size(),
            "only registry item and damage may cross boundary");
        SimpleAssert.eq("minecraft:stone", row.getString("item"), "public shell identity retained");
        SimpleAssert.eq(23, source.stackSize, "catalog projection never moves real stock");
        SimpleAssert.eq(
            "never transmit",
            source.getTagCompound()
                .getString("encryptionKey"),
            "source NBT unchanged");
        String first = HologramAuthorMaterialCatalog.toolFingerprint(source);
        SimpleAssert.eq(
            first,
            HologramAuthorMaterialCatalog.toolFingerprint(source.copy()),
            "tool fingerprint stable across copies");
        source.getTagCompound()
            .setBoolean("changedSettings", true);
        SimpleAssert.that(
            !first.equals(HologramAuthorMaterialCatalog.toolFingerprint(source)),
            "tool configuration changes invalidate correlation");

        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.recoveryDestination = 1;
        NBTTagCompound change = new NBTTagCompound();
        change.setBoolean("materialMain", false);
        change.setBoolean("materialContainers", true);
        change.setBoolean("materialMe", false);
        change.setInteger("materialPriority", 2);
        change.setInteger("recoveryDestination", 0);
        HologramAuthorMaterialCatalog.applySettings(settings, change);
        SimpleAssert.that(
            !settings.main && settings.containers && !settings.me,
            "only selected actual-tool source toggles change");
        SimpleAssert.eq(2, settings.priority, "valid source order applied");
        SimpleAssert.eq(1, settings.recoveryDestination, "request cannot change recovery destination");
        change.setInteger("materialPriority", 3);
        boolean rejected = false;
        try {
            HologramAuthorMaterialCatalog.applySettings(settings, change);
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "invalid real-tool settings rejected before mutation");
        SimpleAssert.eq(2, settings.priority, "failed settings mutation preserves previous state");

        NBTTagCompound request = new NBTTagCompound();
        request.setString("token", "00000000-0000-0000-0000-000000000001");
        request.setString("document", "local-document");
        request.setLong("revision", 4L);
        request.setString("player", "00000000-0000-0000-0000-000000000002");
        request.setInteger("toolSlot", 2);
        request.setString("toolFingerprint", first);
        HologramAuthorMaterialCatalog.validateRequest(request);
        NBTTagCompound reply = HologramAuthorMaterialCatalog.correlation(request);
        SimpleAssert.eq(request, reply, "reply correlation carries exact document revision, token, player and tool");
        request.removeTag("revision");
        rejected = false;
        try {
            HologramAuthorMaterialCatalog.validateRequest(request);
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "unversioned sample request rejected");
    }
}

package com.miaokatze.gtmb.hologram;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

/** One explicit local build intent; the authoritative quote and ordinary transaction still own payment. */
final class HologramLibraryExecution {

    private HologramLibraryExecution() {}

    static void submitOrReview(HologramSession s, NBTTagCompound request) {
        if (!request.getBoolean("executeIntent")) {
            if (request.getBoolean("openReview")) HologramProjectorWorkflow.openPlacementReview(s, s.player);
            return;
        }
        NBTTagCompound accepted = request.getCompoundTag("acknowledgedMaterials");
        boolean ready = !s.localPlanPending && s.planMissing == 0
            && s.planRecoveryFits
            && s.materialQuote != null
            && s.materialQuote.getBoolean("complete")
            && s.id.equals(s.materialQuote.getString("session"))
            && s.planRevision == s.materialQuote.getLong("planRevision")
            && accepted.getInteger("version") == 1
            && accepted.getBoolean("complete")
            && !accepted.getBoolean("cacheReviewRequired")
            && withinAcknowledged(accepted, s.materialQuote, s.player.capabilities.isCreativeMode);
        if (!ready) {
            HologramProjectorWorkflow.openPlacementReview(s, s.player);
            return;
        }
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = true;
        s.forcePlacementReady = false;
        s.placementReviewNonce++;
        NBTTagCompound build = new NBTTagCompound();
        build.setString("op", "build");
        build.setString("session", s.id);
        build.setLong("planRevision", s.planRevision);
        build.setLong("placementReviewNonce", s.placementReviewNonce);
        build.setBoolean("useDecorationVoucher", false);
        HologramBuildPreparation.reviewedBuild(s, build);
        if (s.job != 1 && s.job != 3) HologramProjectorWorkflow.openPlacementReview(s, s.player);
        else HologramSnapshots.send(s);
    }

    static boolean withinAcknowledged(NBTTagCompound accepted, NBTTagCompound quote, boolean creative) {
        try {
            if (accepted.getInteger("version") != 1 || !accepted.getBoolean("complete")
                || accepted.getBoolean("cacheReviewRequired")) return false;
            Map<Object, BigInteger> budgets = new LinkedHashMap<>(), required = new LinkedHashMap<>();
            NBTTagList acknowledged = accepted.getTagList("rows", 10), actual = quote.getTagList("rows", 10);
            if (acknowledged.tagCount() > 4096 || actual.tagCount() > 4096) return false;
            for (int index = 0; index < acknowledged.tagCount(); index++) {
                NBTTagCompound row = acknowledged.getCompoundTagAt(index);
                if (row.getBoolean("optionalCache")) return false;
                Object identity = identity(row);
                BigInteger amount = amount(row);
                if (identity == null || amount.signum() < 0) return false;
                budgets.merge(identity, amount, BigInteger::add);
            }
            for (int index = 0; index < actual.tagCount(); index++) {
                NBTTagCompound row = actual.getCompoundTagAt(index);
                BigInteger amount = amount(row);
                if (row.getBoolean("optionalCache")) {
                    // Internal energy/cache conversion was not priced by the local theoretical bill.
                    if (amount.signum() > 0) return false;
                    continue;
                }
                if ((!creative || "essentia".equals(row.getString("kind"))) && (row.getLong("missing") > 0
                    || row.hasKey("missingExact", 8) && decimal(row.getString("missingExact")).signum() > 0))
                    return false;
                Object identity = identity(row);
                if (identity == null || amount.signum() < 0) return false;
                required.merge(identity, amount, BigInteger::add);
            }
            for (Map.Entry<Object, BigInteger> row : required.entrySet()) if (row.getValue()
                .compareTo(budgets.getOrDefault(row.getKey(), BigInteger.ZERO)) > 0) return false;
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static Object identity(NBTTagCompound row) {
        String kind = row.getString("kind");
        if ("item".equals(kind)) {
            ItemStack item = HologramMaterialQuote.readItem(row);
            return item == null ? null : new HologramBlueprintResources.ExactKey(item, null);
        }
        if ("fluid".equals(kind)) {
            FluidStack fluid = HologramMaterialQuote.readFluid(row);
            return fluid == null ? null : new HologramBlueprintResources.ExactKey(null, fluid);
        }
        if ("essentia".equals(kind) || "energy".equals(kind)) return kind + ":" + row.getString("aspect");
        return null;
    }

    private static BigInteger amount(NBTTagCompound row) {
        return row.hasKey("requiredExact", 8) ? decimal(row.getString("requiredExact"))
            : BigInteger.valueOf(row.getLong("required"));
    }

    private static BigInteger decimal(String value) {
        if (value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("Invalid material quantity");
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) < '0' || value.charAt(index) > '9')
            throw new IllegalArgumentException("Invalid material quantity");
        return new BigInteger(value);
    }
}

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;

/**
 * Detached buffer projections. Unknown cache schemas empty safely; capacity/configuration is never a payment amount.
 */
public final class HologramEnergyPayload {

    private HologramEnergyPayload() {}

    public static final class Prepared {

        public final NBTTagCompound tilePaid;
        public final NBTTagCompound tileEmpty;
        public final NBTTagCompound itemPaid;
        public final NBTTagCompound itemEmpty;
        public final HologramEnergy.Demand demand = new HologramEnergy.Demand();
        public final List<String> warnings = new ArrayList<>();
        public final String intrinsicSource;

        private Prepared(NBTTagCompound tile, NBTTagCompound item, CapBankSchema capBank) throws IOException {
            this(tile, item, capBank, "");
        }

        private Prepared(NBTTagCompound tile, NBTTagCompound item, CapBankSchema capBank, String creativeAe)
            throws IOException {
            tilePaid = copy(tile);
            tileEmpty = copy(tile);
            itemPaid = copy(item);
            itemEmpty = copy(item);
            intrinsicSource = capBank != null && capBank.creative ? "EnderIO native creative capacitor bank"
                : creativeAe;
            if (!creativeAe.isEmpty()) {
                nativeAeDefaults(tilePaid);
                nativeAeDefaults(tileEmpty);
                nativeAeDefaults(itemPaid);
                nativeAeDefaults(itemEmpty);
            }
            if (capBank != null) {
                int tileRf = capBank.project(tilePaid, tileEmpty, warnings, "tile");
                int itemRf = capBank.project(itemPaid, itemEmpty, warnings, "item");
                // The block item and tile serialize the same bank; either placement path must be funded once.
                demand.rf = BigDecimal.valueOf(Math.max(tileRf, itemRf));
            }
            scan(tilePaid, tileEmpty, demand, warnings, "tile", false, capBank != null, 0);
            scan(itemPaid, itemEmpty, demand, warnings, "item", false, capBank != null, 0);
        }
    }

    public static Prepared prepare(NBTTagCompound tile, NBTTagCompound item) throws IOException {
        return new Prepared(tile, item, null);
    }

    /** The actual chosen block and metadata, never copied NBT identities, authorize intrinsic defaults. */
    public static Prepared prepare(Block targetBlock, int targetMetadata, NBTTagCompound tile, NBTTagCompound item)
        throws IOException {
        return new Prepared(
            tile,
            item,
            CapBankSchema.target(targetBlock, targetMetadata),
            creativeAeTarget(targetBlock));
    }

    /** Resource inspection uses this projection; buffer payment is an optional, separate server transaction. */
    public static NBTTagCompound withoutBuffers(NBTTagCompound payload) throws IOException {
        return prepare(payload, null).tileEmpty;
    }

    /** These native AE variants encode their current charge level, rather than physical type, in world metadata. */
    public static boolean volatileEnergyMetadata(Block block) {
        if (block == null) return false;
        String type = block.getClass()
            .getName();
        return type.equals("appeng.block.networking.BlockEnergyCell")
            || type.equals("appeng.block.networking.BlockDenseEnergyCell")
            || type.equals("appeng.block.networking.BlockController")
            || type.equals("appeng.block.networking.BlockCreativeEnergyController");
    }

    private static NBTTagCompound copy(NBTTagCompound value) {
        return value == null ? null : (NBTTagCompound) value.copy();
    }

    /** Exact native block + its registered tile class. Display names and copied source id fields have no authority. */
    private static String creativeAeTarget(Block block) throws IOException {
        if (block == null) return "";
        String targetClass = block.getClass()
            .getName();
        String tileClass;
        if (targetClass.equals("appeng.block.networking.BlockCreativeEnergyCell"))
            tileClass = "appeng.tile.networking.TileCreativeEnergyCell";
        else if (targetClass.equals("appeng.block.networking.BlockCreativeEnergyController"))
            tileClass = "appeng.tile.networking.TileCreativeEnergyController";
        else return "";
        try {
            Object nativeTile = block.getClass()
                .getMethod("getTileEntityClass")
                .invoke(block);
            if (!(nativeTile instanceof Class<?>) || !((Class<?>) nativeTile).getName()
                .equals(tileClass))
                throw new IOException("Creative AE target does not have its native infinite tile class");
            return "AE native creative energy provider";
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            throw new IOException("Creative AE native target cannot be verified", unsupported);
        }
    }

    private static void nativeAeDefaults(NBTTagCompound payload) {
        if (payload == null) return;
        // Native creative tile getters supply their own constant/infinite power. Do not buy a copied base cache.
        if (payload.hasKey("internalCurrentPower")) payload.setDouble("internalCurrentPower", 0);
        payload.removeTag("internalMaxPower");
    }

    private static void scan(NBTTagCompound paid, NBTTagCompound empty, HologramEnergy.Demand demand,
        List<String> warnings, String path, boolean conduit, boolean capBank, int depth) throws IOException {
        if (paid == null) return;
        if (depth > 64) throw new IOException("Energy buffer NBT nesting exceeds limit");
        // The exact tagged inventory item is already a material cost. Its nested charge is never an energy bank.
        if (paid.hasKey("id") && paid.hasKey("Count") && paid.hasKey("Damage")) return;
        boolean powerConduit = conduit;
        if (paid.hasKey("conduitType", 8)) {
            String type = paid.getString("conduitType");
            try {
                ClassLoader loader = HologramEnergyPayload.class.getClassLoader();
                powerConduit = Class.forName("crazypants.enderio.conduit.power.PowerConduit", false, loader)
                    .isAssignableFrom(Class.forName(type, false, loader));
            } catch (ClassNotFoundException | LinkageError unsupported) {
                powerConduit = false;
            }
        }
        for (Object raw : new HashSet<Object>(paid.func_150296_c())) {
            String key = (String) raw;
            String lower = key.toLowerCase(Locale.ROOT);
            NBTBase tag = paid.getTag(key);
            String field = path + "." + key;
            if (capBank && key.equals("storedEnergyRF")) continue;
            if (configuration(lower)) continue;
            if (cache(lower) && (tag instanceof NBTTagCompound || tag instanceof NBTTagList)) {
                paid.removeTag(key);
                empty.removeTag(key);
                warnings.add(field + ": unsupported energy container omitted");
                continue;
            }
            if (tag instanceof NBTTagCompound) {
                scan(
                    (NBTTagCompound) tag,
                    empty.getCompoundTag(key),
                    demand,
                    warnings,
                    field,
                    powerConduit && key.equals("conduit"),
                    false,
                    depth + 1);
                continue;
            }
            if (tag instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) tag;
                if (list.func_150303_d() == 10) {
                    NBTTagList emptyList = empty.getTagList(key, 10);
                    for (int index = 0; index < list.tagCount(); index++) scan(
                        list.getCompoundTagAt(index),
                        emptyList.getCompoundTagAt(index),
                        demand,
                        warnings,
                        field + "[" + index + "]",
                        false,
                        false,
                        depth + 1);
                }
                continue;
            }
            boolean eu = key.equals("mStoredEnergy");
            boolean ae = key.equals("internalCurrentPower");
            boolean rf = powerConduit && key.equals("energyStoredRF");
            boolean legacyRf = powerConduit && key.equals("energyStored");
            if (!eu && !ae && !rf && !legacyRf && !cache(lower)) continue;
            zero(empty, key, tag);
            BigDecimal amount = numeric(tag);
            if (amount == null || amount.signum() < 0) {
                zero(paid, key, tag);
                warnings.add(field + ": invalid or unknown energy buffer omitted");
                continue;
            }
            if (amount.signum() == 0) continue;
            if (eu && tag.getId() <= 4) {
                demand.eu = demand.eu.add(amount.toBigIntegerExact());
            } else if (ae && tag.getId() == 6) {
                demand.ae = demand.ae.add(amount);
            } else if (rf && tag.getId() == 3) {
                // Legacy reader overwrites energyStoredRF when energyStored exists.
                if (!paid.hasKey("energyStored")) demand.rf = demand.rf.add(amount);
            } else if (legacyRf && tag.getId() == 5) {
                float source = paid.getFloat(key);
                float scaled = source * 10;
                if (!Float.isFinite(scaled) || scaled > Integer.MAX_VALUE) {
                    zero(paid, key, tag);
                    warnings.add(field + ": unsupported legacy RF amount omitted");
                } else demand.rf = demand.rf.add(new BigDecimal((int) scaled));
            } else {
                zero(paid, key, tag);
                warnings.add(field + ": unsupported cached resource omitted");
            }
        }
    }

    private static final class CapBankSchema {

        final String uid;
        final int capacity;
        final boolean creative;

        CapBankSchema(String uid, int capacity, boolean creative) {
            this.uid = uid;
            this.capacity = capacity;
            this.creative = creative;
        }

        static CapBankSchema target(Block block, int metadata) throws IOException {
            if (block == null) return null;
            try {
                ClassLoader loader = HologramEnergyPayload.class.getClassLoader();
                Class<?> blockType = Class.forName("crazypants.enderio.machine.capbank.BlockCapBank", false, loader);
                if (!blockType.isInstance(block)) return null;
                Class<?> type = Class.forName("crazypants.enderio.machine.capbank.CapBankType", false, loader);
                Object nativeType = type.getMethod("getTypeFromMeta", int.class)
                    .invoke(null, metadata);
                int nativeMetadata = ((Number) type.getMethod("getMetaFromType", type)
                    .invoke(null, nativeType)).intValue();
                if (nativeMetadata != metadata) throw new IOException("Invalid native capacitor bank target metadata");
                String uid = (String) type.getMethod("getUid")
                    .invoke(nativeType);
                int capacity = ((Number) type.getMethod("getMaxEnergyStored")
                    .invoke(nativeType)).intValue();
                boolean creative = (Boolean) type.getMethod("isCreative")
                    .invoke(nativeType);
                if (uid == null || uid.isEmpty() || capacity <= 0)
                    throw new IOException("Invalid native capacitor bank type/capacity");
                return new CapBankSchema(uid, capacity, creative);
            } catch (ClassNotFoundException unavailable) {
                return null;
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                throw new IOException("Native capacitor bank schema cannot be verified", unavailable);
            }
        }

        int project(NBTTagCompound paid, NBTTagCompound empty, List<String> warnings, String path) {
            if (paid == null) return 0;
            paid.setString("type", uid);
            empty.setString("type", uid);
            if (creative) {
                // BlockCapBank.getSubBlocks creates the native creative variant at half its runtime capacity.
                // Never copy the source balance or a source-selected capacitor-bank type.
                paid.setInteger("storedEnergyRF", capacity / 2);
                empty.setInteger("storedEnergyRF", capacity / 2);
                return 0;
            }
            int amount = 0;
            if (paid.hasKey("storedEnergyRF")) {
                if (paid.hasKey("storedEnergyRF", 3) && paid.getInteger("storedEnergyRF") >= 0)
                    amount = Math.min(capacity, paid.getInteger("storedEnergyRF"));
                else warnings.add(path + ".storedEnergyRF: invalid native RF buffer omitted");
            }
            paid.setInteger("storedEnergyRF", amount);
            empty.setInteger("storedEnergyRF", 0);
            return amount;
        }
    }

    private static boolean configuration(String lower) {
        return lower.contains("capacity") || lower.contains("maxpower")
            || lower.contains("maxenergy")
            || lower.contains("recipe")
            || lower.contains("filter")
            || lower.contains("pattern")
            || lower.contains("whitelist")
            || lower.contains("blacklist")
            || lower.startsWith("ghost");
    }

    private static boolean cache(String lower) {
        return lower.equals("energy") || lower.equals("menergy")
            || lower.equals("eu")
            || lower.equals("meu")
            || lower.equals("charge")
            || lower.equals("mcharge")
            || lower.equals("energybuffer")
            || lower.equals("storedpower")
            || lower.contains("storedenergy")
            || lower.contains("storedeu")
            || lower.equals("energystored")
            || lower.equals("energystoredrf")
            || lower.equals("rfenergy")
            || lower.equals("energyl")
            || lower.equals("rf")
            || lower.equals("storedrf")
            || lower.equals("energycache")
            || lower.equals("eubuffer")
            || lower.equals("rfbuffer");
    }

    private static BigDecimal numeric(NBTBase tag) {
        if (!(tag instanceof NBTBase.NBTPrimitive)) return null;
        NBTBase.NBTPrimitive number = (NBTBase.NBTPrimitive) tag;
        if (tag.getId() <= 4) return new BigDecimal(BigInteger.valueOf(number.func_150291_c()));
        double value = number.func_150286_g();
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private static void zero(NBTTagCompound target, String key, NBTBase tag) {
        switch (tag.getId()) {
            case 1:
                target.setTag(key, new NBTTagByte((byte) 0));
                break;
            case 2:
                target.setTag(key, new NBTTagShort((short) 0));
                break;
            case 3:
                target.setTag(key, new NBTTagInt(0));
                break;
            case 4:
                target.setTag(key, new NBTTagLong(0));
                break;
            case 5:
                target.setTag(key, new NBTTagFloat(0));
                break;
            case 6:
                target.setTag(key, new NBTTagDouble(0));
                break;
            default:
                target.removeTag(key);
        }
    }
}

package com.miaokatze.gtmb.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Pure codecs for complete retained fluid-container subtrees. No codec receives a world, inventory or session. */
public final class ResourceContainers {

    private static final int MAX_SLOTS = 512;
    private static final Map<String, FluidCodec> CODECS = new LinkedHashMap<>();
    private static long generation;

    private ResourceContainers() {}

    /** Selected native payload identity and location within it; all NBT access returns independent copies. */
    public static final class Context {

        public final String nativeTileId, rootKey, path, name;
        public final int machineId;
        public final boolean fluidContext;
        private final NBTTagCompound container;

        public Context(String nativeTileId, int machineId, String rootKey, String path, String name,
            boolean fluidContext, NBTTagCompound container) {
            if (container == null || nativeTileId == null || rootKey == null || path == null || name == null)
                throw new IllegalArgumentException("complete resource context required");
            this.nativeTileId = nativeTileId;
            this.machineId = machineId;
            this.rootKey = rootKey;
            this.path = path;
            this.name = name;
            this.fluidContext = fluidContext;
            this.container = (NBTTagCompound) container.copy();
        }

        public NBTTagCompound container() {
            return (NBTTagCompound) container.copy();
        }
    }

    /** Physical quantity is a long, independently of FluidStack's integer display amount. */
    public static final class FluidAmount {

        public final long amount;
        private final FluidStack identity;

        public FluidAmount(FluidStack identity, long amount) {
            if (identity == null || identity.getFluid() == null
                || amount < 0
                || FluidRegistry.getFluid(
                    identity.getFluid()
                        .getName())
                    != identity.getFluid())
                throw new IllegalArgumentException("fluid identity and nonnegative quantity required");
            this.identity = identity.copy();
            this.identity.amount = 1;
            this.amount = amount;
        }

        public FluidStack identity() {
            return identity.copy();
        }
    }

    public interface FluidCodec {

        /**
         * Return null when this schema does not apply. A non-null list claims the complete subtree, including empty
         * slots: validate every key/type and reject unknown or unpriced data with IOException. Inputs are disposable;
         * callbacks must not access worlds, mutate live storage, tick devices, perform I/O or authorize placement.
         */
        List<FluidAmount> decode(Context context) throws IOException;
    }

    /** Register a trusted codec in declaration order. Closing its handle invalidates cached material plans. */
    public static GTMBExtensions.Registration register(String id, FluidCodec codec) {
        if (codec == null) throw new IllegalArgumentException("codec");
        return GTMBExtensions.install(id, "resource-fluid-container", () -> {
            synchronized (CODECS) {
                CODECS.put(id, codec);
                generation++;
            }
            return () -> {
                synchronized (CODECS) {
                    if (CODECS.remove(id, codec)) generation++;
                }
            };
        });
    }

    /** Include in any cache key whose result depends on registered resource codecs. */
    public static long generation() {
        synchronized (CODECS) {
            return generation;
        }
    }

    /** Avoid allocating callback context for unrelated ordinary NBT when no extension is installed. */
    public static boolean hasCodecs() {
        synchronized (CODECS) {
            return !CODECS.isEmpty();
        }
    }

    /** Production inspection entry point; a null result leaves the ordinary strict resource inspector in charge. */
    public static List<FluidAmount> decode(Context context) throws IOException {
        Map<String, FluidCodec> entries;
        synchronized (CODECS) {
            entries = new LinkedHashMap<>(CODECS);
        }
        for (Map.Entry<String, FluidCodec> entry : entries.entrySet()) {
            try {
                List<FluidAmount> result = entry.getValue()
                    .decode(context);
                if (result != null) return checked(result);
            } catch (IOException | RuntimeException | LinkageError failure) {
                throw new IOException(
                    "Resource container codec rejected " + context.path + ": " + entry.getKey(),
                    failure);
            }
        }
        if (context.nativeTileId.equals("blockReservoirTileEntity") && context.path.equals(context.rootKey)
            && (context.name.equals("tank") || context.name.equals("regenTank"))) {
            NBTTagCompound row = context.container;
            if (!row.hasKey("FluidName", 8) || !row.getString("FluidName")
                .equals("water")
                || !row.hasKey("Amount", 3)
                || row.getInteger("Amount") > 2000
                || context.name.equals("regenTank") && row.hasKey("Tag"))
                throw invalid("malformed native EnderIO reservoir water tank");
            FluidAmount water = fluidRecord(row);
            return checked(Collections.singletonList(water));
        }
        String name = context.name.toLowerCase(java.util.Locale.ROOT);
        if (context.fluidContext || name.contains("fluid") || name.contains("tank")) {
            for (Object key : context.container.func_150296_c())
                if (((String) key).startsWith("#")) return numberedFluidSlots(context.container);
        }
        return null;
    }

    /**
     * Exact native numbered-slot codec, reusable by registered adapters regardless of their container's name.
     * Accepts contiguous #0..#N slots containing empty compounds, Forge FluidStack records, or AEFluidStack records
     * written with Count=0 and the real long Cnt. Unknown fields, mixed quantity formats and malformed slots fail.
     * This method reads only; it never rewrites the retained payload or treats a configuration sample as free fluid.
     */
    public static List<FluidAmount> numberedFluidSlots(NBTTagCompound container) throws IOException {
        if (container == null) throw invalid("missing numbered fluid container");
        int count = container.func_150296_c()
            .size();
        if (count > MAX_SLOTS) throw invalid("too many numbered fluid slots");
        for (Object raw : container.func_150296_c()) {
            String key = (String) raw;
            if (!key.matches("#(?:0|[1-9][0-9]{0,2})")) throw invalid("unknown numbered fluid slot " + key);
            int index = Integer.parseInt(key.substring(1));
            if (index >= count || !container.hasKey(key, 10)) throw invalid("noncontiguous or malformed slot " + key);
        }
        List<FluidAmount> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            NBTTagCompound row = container.getCompoundTag("#" + index);
            if (row.hasNoTags()) continue;
            result.add(fluidRecord(row));
        }
        return checked(result);
    }

    private static FluidAmount fluidRecord(NBTTagCompound row) throws IOException {
        if (!row.hasKey("FluidName", 8)) throw invalid("fluid name must be a registered string");
        Fluid fluid = FluidRegistry.getFluid(row.getString("FluidName"));
        if (fluid == null) throw invalid("fluid is not registered");
        boolean ae = row.hasKey("Cnt");
        for (Object raw : row.func_150296_c()) {
            String key = (String) raw;
            boolean known = "FluidName".equals(key)
                || (ae
                    ? "Count".equals(key) || "Cnt".equals(key)
                        || "Req".equals(key)
                        || "Craft".equals(key)
                        || "ReqMade".equals(key)
                        || "UsedPercent".equals(key)
                        || "tag".equals(key)
                    : "Amount".equals(key) || "Tag".equals(key));
            if (!known) throw invalid("unknown fluid record field " + key);
        }
        long amount;
        String tagKey;
        if (ae) {
            if (!row.hasKey("Count", 1) || row.getByte("Count") != 0
                || !row.hasKey("Cnt", 4)
                || !row.hasKey("Req", 4)
                || row.getLong("Req") < 0
                || !row.hasKey("Craft", 1)
                || row.getByte("Craft") < 0
                || row.getByte("Craft") > 1) throw invalid("malformed AE long fluid quantity or request metadata");
            if (row.hasKey("ReqMade") && (!row.hasKey("ReqMade", 4) || row.getLong("ReqMade") < 0))
                throw invalid("malformed AE requested-craft quantity");
            if (row.hasKey("UsedPercent")
                && (!row.hasKey("UsedPercent", 5) || !Float.isFinite(row.getFloat("UsedPercent"))
                    || row.getFloat("UsedPercent") < 0))
                throw invalid("malformed AE fluid usage metadata");
            amount = row.getLong("Cnt");
            tagKey = "tag";
        } else {
            if (!row.hasKey("Amount", 3)) throw invalid("Forge fluid amount must be an integer");
            amount = row.getInteger("Amount");
            tagKey = "Tag";
        }
        if (amount < 0) throw invalid("negative physical fluid quantity");
        if (row.hasKey(tagKey) && !row.hasKey(tagKey, 10)) throw invalid("fluid tag must be a compound");
        FluidStack identity = new FluidStack(fluid, 1);
        if (row.hasKey(tagKey, 10)) identity.tag = (NBTTagCompound) row.getCompoundTag(tagKey)
            .copy();
        return new FluidAmount(identity, amount);
    }

    private static List<FluidAmount> checked(List<FluidAmount> result) throws IOException {
        if (result.size() > MAX_SLOTS) throw invalid("too many decoded fluid slots");
        for (FluidAmount fluid : result) if (fluid == null) throw invalid("codec returned a null fluid record");
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static IOException invalid(String reason) {
        return new IOException("Invalid native fluid container: " + reason);
    }
}

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;

/** TC jar contents and ThE's native essentia channel; no fluid or item substitute represents an aspect point. */
public final class HologramEssentia {

    private static final String ASPECT_CLASS = "thaumcraft.api.aspects.Aspect";
    private static final String JAR_ITEM_CLASS = "thaumcraft.common.blocks.ItemJarFilled";
    private static final String STACK_TYPE_CLASS = "thaumicenergistics.common.storage.AEEssentiaStackType";

    private HologramEssentia() {}

    public static final class Cost {

        public final String aspect;
        public long amount;

        Cost(String aspect, long amount) {
            this.aspect = aspect;
            this.amount = amount;
        }
    }

    public static boolean isJar(NBTTagCompound payload) {
        if (payload == null) return false;
        String id = payload.getString("id");
        return id.equals("TileJar") || id.equals("TileJarVoid");
    }

    /** Imported or custom NBT cannot hide real jar contents behind an unrelated tile id during pricing. */
    public static void validatePlacement(Block block, int metadata, NBTTagCompound payload) throws IOException {
        if (block == null || payload == null) return;
        String type = block.getClass()
            .getName(), expected = null;
        if (type.equals("thaumcraft.common.blocks.BlockJar") && (metadata == 0 || metadata == 3))
            expected = metadata == 3 ? "TileJarVoid" : "TileJar";
        else if (type.equals("thaumcraft.common.blocks.BlockMetalDevice") && metadata == 1) expected = "TileSiphon";
        else if (type.equals("thaumcraft.common.blocks.BlockMetalDevice") && metadata == 0) expected = "TileCrucible";
        else if (type.equals("thaumcraft.common.blocks.BlockStoneDevice") && metadata == 0)
            expected = "TileAlchemyFurnace";
        else if (type.equals("thaumcraft.common.blocks.BlockAlchemyFurnace") && metadata == 0)
            expected = "TileAlchemyFurnaceAdvanced";
        else if (type.equals("thaumcraft.common.blocks.BlockTube") && metadata >= 0 && metadata <= 7)
            expected = new String[] { "TileTube", "TileTubeValve", "TileCentrifuge", "TileTubeFilter", "TileTubeBuffer",
                "TileTubeRestrict", "TileTubeOneway", "TileEssentiaCrystalizer" }[metadata];
        if (expected == null) return;
        if (!expected.equals(payload.getString("id"))) throw invalid("目标源质容器与内容载荷的类型不一致");
        jarCost(payload);
        validateNonJarContents(payload);
    }

    /** Audited native assets that do not yet have a complete placement/payment adapter fail closed when nonempty. */
    public static void validateNonJarContents(NBTTagCompound payload) throws IOException {
        if (payload == null) return;
        String id = payload.getString("id"), field = null;
        if (id.equals("TileSiphon") || id.equals("TileTube")
            || id.equals("TileTubeValve")
            || id.equals("TileCentrifuge")
            || id.equals("TileTubeFilter")
            || id.equals("TileTubeRestrict")
            || id.equals("TileTubeOneway")) {
            if (payload.hasKey("amount")
                && (!(payload.getTag("amount") instanceof NBTBase.NBTPrimitive) || payload.getTag("amount")
                    .getId() > 4 || payload.getLong("amount") != 0))
                field = "amount";
        } else if (id.equals("TileAlchemyFurnace") || id.equals("TileAlchemyFurnaceAdvanced")
            || id.equals("TileTubeBuffer")
            || id.equals("TileCrucible")) {
                if (payload.hasKey("Aspects") && (!payload.hasKey("Aspects", 9)
                    || ((net.minecraft.nbt.NBTTagList) payload.getTag("Aspects")).tagCount() > 0)) field = "Aspects";
                if (id.equals("TileCrucible") && !payload.getBoolean("Empty")
                    && payload.hasKey("FluidName", 8)
                    && payload.getLong("Amount") != 0) field = "Aspects / FluidName / Amount";
            } else if (id.equals("TileEssentiaCrystalizer") && !payload.getString("Aspect")
                .isEmpty()) field = "Aspect";
        if (field != null) throw new IOException("暂不支持保留该原生源质容器的非空内容 " + id + "：请忽略内容字段 " + field + " 后重试；源模板和来源资源不变");
    }

    /** Read the final selected tile payload. An empty or explicitly omitted content pair costs nothing. */
    public static Cost jarCost(NBTTagCompound payload) throws IOException {
        return jarCost(payload, HologramEssentia::knownAspect);
    }

    static Cost jarCost(NBTTagCompound payload, Predicate<String> registered) throws IOException {
        if (!isJar(payload)) return null;
        if (!payload.hasKey("Amount")) {
            if (!payload.getString("Aspect")
                .isEmpty()) throw invalid("缺少源质数量");
            return null;
        }
        NBTBase amountTag = payload.getTag("Amount");
        if (!(amountTag instanceof NBTBase.NBTPrimitive) || amountTag.getId() > 4) throw invalid("源质数量类型无效");
        long amount = ((NBTBase.NBTPrimitive) amountTag).func_150291_c();
        if (amount < 0 || amount > 64) throw invalid("源质数量必须在 0 到 64 点之间");
        if (amount == 0) return null;
        if (!payload.hasKey("Aspect", 8) || !registered.test(payload.getString("Aspect")))
            throw invalid("源质类型未注册，不能保留非空罐内容");
        return new Cost(payload.getString("Aspect"), amount);
    }

    private static IOException invalid(String reason) {
        return new IOException("源质罐内容无效：" + reason + "；请取消罐内容后重试");
    }

    private static Object aspect(String tag) throws ReflectiveOperationException {
        return Class.forName(ASPECT_CLASS)
            .getMethod("getAspect", String.class)
            .invoke(null, tag);
    }

    public static boolean knownAspect(String tag) {
        if (tag == null || tag.isEmpty()) return false;
        try {
            return aspect(tag) != null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    public static String displayName(String tag) {
        try {
            Object aspect = aspect(tag);
            return aspect == null ? tag
                : (String) aspect.getClass()
                    .getMethod("getName")
                    .invoke(aspect);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return tag;
        }
    }

    /** TC returns a different item for a filled jar; construction always pays the real empty block shell. */
    public static ItemStack shellMaterial(ItemStack sample) {
        if (!nativeJarItem(sample)) return sample;
        Object registered = Block.blockRegistry.getObject("Thaumcraft:blockJar");
        if (!(registered instanceof Block)) return sample;
        Item shell = Item.getItemFromBlock((Block) registered);
        return shell == null ? sample : new ItemStack(shell, sample.stackSize, sample.getItemDamage());
    }

    private static boolean nativeJarItem(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && stack.stackSize == 1
            && (stack.getItemDamage() == 0 || stack.getItemDamage() == 3)
            && stack.getItem()
                .getClass()
                .getName()
                .equals(JAR_ITEM_CLASS);
    }

    /** Fuzzy shell payment must never discard a native jar's physical contents, even if its item identity matches. */
    public static boolean allowsFuzzyShell(ItemStack candidate) {
        if (candidate == null || candidate.getItem() == null) return false;
        if (!candidate.getItem()
            .getClass()
            .getName()
            .equals(JAR_ITEM_CLASS)) return true;
        if (!candidate.hasTagCompound()) return true;
        if (!candidate.getTagCompound()
            .hasKey("Aspects")) return true;
        if (!candidate.getTagCompound()
            .hasKey("Aspects", 9)) return false;
        net.minecraft.nbt.NBTTagList list = candidate.getTagCompound()
            .getTagList("Aspects", 10);
        if (list.tagCount() == 0) return true;
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound aspect = list.getCompoundTagAt(i);
            if (!aspect.hasKey("amount", 99) || aspect.getInteger("amount") != 0) return false;
        }
        return true;
    }

    /** Local payments use only TC's audited partial-fill jar, not phials, crystals, or unknown addon containers. */
    public static int jarAmount(ItemStack source, String tag) {
        if (!nativeJarItem(source)) return 0;
        try {
            Object list = source.getItem()
                .getClass()
                .getMethod("getAspects", ItemStack.class)
                .invoke(source.getItem(), source.copy());
            if (list == null) return 0;
            Object wanted = aspect(tag);
            if (wanted == null) return 0;
            Object[] aspects = (Object[]) list.getClass()
                .getMethod("getAspects")
                .invoke(list);
            if (aspects.length != 1 || aspects[0] != wanted) return 0;
            int amount = (Integer) list.getClass()
                .getMethod("getAmount", Class.forName(ASPECT_CLASS))
                .invoke(list, wanted);
            return amount > 0 && amount <= 64 ? amount : 0;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return 0;
        }
    }

    /** Create and verify the post-payment item through the native API before touching the live inventory. */
    public static ItemStack drainJar(ItemStack source, String tag, int requested) {
        int before = jarAmount(source, tag);
        if (requested <= 0 || before < requested) return null;
        try {
            ItemStack after = source.copy();
            Object list = after.getItem()
                .getClass()
                .getMethod("getAspects", ItemStack.class)
                .invoke(after.getItem(), after);
            list.getClass()
                .getMethod("remove", Class.forName(ASPECT_CLASS), int.class)
                .invoke(list, aspect(tag), requested);
            Method setter = after.getItem()
                .getClass()
                .getMethod("setAspects", ItemStack.class, list.getClass());
            setter.invoke(after.getItem(), after, list);
            if (jarAmount(after, tag) != before - requested) return null;
            // TC's native unlabeled empty drop is the block item, allowing its shell to be used next.
            if (before == requested && (!after.hasTagCompound() || after.getTagCompound()
                .getString("AspectFilter")
                .isEmpty())) after = shellMaterial(after);
            return after;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    /** Optional addon discovery is restricted to ThE's published native channel singleton. */
    public static IAEStackType<?> stackType() {
        try {
            Object type = Class.forName(STACK_TYPE_CLASS)
                .getField("ESSENTIA_STACK_TYPE")
                .get(null);
            return type instanceof IAEStackType<?> && ((IAEStackType<?>) type).getId()
                .equals("essentia") ? (IAEStackType<?>) type : null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    public static IAEStack<?> request(String aspect, long amount) {
        IAEStackType<?> type = stackType();
        if (type == null || amount <= 0 || !knownAspect(aspect)) return null;
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("AspectTag", aspect);
        nbt.setLong("Cnt", amount);
        return type.loadStackFromNBT(nbt);
    }

    public interface Access {

        boolean available();

        IAEStack<?> request(String aspect, long amount);

        long poweredCount(IAEStack<?> request);

        IAEStack<?> extract(IAEStack<?> request, Actionable mode);

        IAEStack<?> inject(IAEStack<?> request);

        void charge(long amount, int amountPerUnit);
    }

    public static long quote(Access access, String aspect, long limit) {
        if (limit <= 0 || !access.available()) return 0;
        IAEStack<?> request = access.request(aspect, limit);
        if (request == null) return 0;
        long offered = Math.min(limit, Math.max(0, access.poweredCount(request)));
        if (offered == 0) return 0;
        request.setStackSize(offered);
        IAEStack<?> simulated = access.extract(request, Actionable.SIMULATE);
        return same(request, simulated) ? Math.min(offered, Math.max(0, simulated.getStackSize())) : 0;
    }

    /** Extraction is the reservation. Journal actual native stacks before charging or checking a short extraction. */
    public static boolean reserve(Access access, String aspect, long amount, Consumer<IAEStack<?>> journal) {
        if (amount == 0) return true;
        if (amount < 0 || quote(access, aspect, amount) < amount) return false;
        IAEStack<?> request = access.request(aspect, amount);
        if (request == null || !access.available()) return false;
        IAEStack<?> extracted = access.extract(request, Actionable.MODULATE);
        if (extracted == null || extracted.getStackSize() <= 0) return false;
        journal.accept(extracted.copy());
        if (!same(request, extracted) || extracted.getStackSize() > amount)
            throw new IllegalStateException("Native essentia monitor returned a different aspect or excess amount");
        access.charge(extracted.getStackSize(), extracted.getAmountPerUnit());
        return extracted.getStackSize() == amount;
    }

    /** Keep the exact unaccepted amount in the receipt; repeated rollback cannot refund accepted points twice. */
    public static void refund(Access access, IAEStack<?> receipt) {
        if (receipt == null || receipt.getStackSize() <= 0 || !access.available()) return;
        long offered = receipt.getStackSize();
        IAEStack<?> remainder = access.inject(receipt.copy());
        if (remainder != null
            && (!same(receipt, remainder) || remainder.getStackSize() < 0 || remainder.getStackSize() > offered))
            throw new IllegalStateException("Native essentia monitor returned an invalid insertion remainder");
        receipt.setStackSize(remainder == null ? 0 : remainder.getStackSize());
    }

    private static boolean same(IAEStack<?> wanted, IAEStack<?> actual) {
        return actual != null && wanted.getStackType() == actual.getStackType() && wanted.isSameType(actual);
    }
}

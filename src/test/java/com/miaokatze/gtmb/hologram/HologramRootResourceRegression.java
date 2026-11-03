package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Atomic selected root fields, complete physical bills, isolation and registration lifecycle. */
public final class HologramRootResourceRegression {

    private static final List<String> FLUID_ROOTS = Arrays.asList("FluidName", "Amount", "Tag");

    private HologramRootResourceRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramRootResourceRegression.class, args)) return;
        run();
        System.out.println("Root resource regressions passed");
    }

    static void run() {
        try {
            fluidGroup();
            selectionAndCompanions();
            physicalItemAndEnergyBills();
            overlapsAndRejection();
        } catch (Exception failure) {
            throw new AssertionError("root resources", failure);
        }
    }

    private static void fluidGroup() throws Exception {
        NBTTagCompound source = fluid();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("variant", "exact physical fluid tag");
        source.setTag("Tag", tag);
        NBTTagCompound original = (NBTTagCompound) source.copy();
        long generation = ResourceContainers.generation();
        try (GTMBExtensions.Registration registration = ResourceContainers
            .registerRoot("test:root-fluid", HologramRootResourceRegression::fluidClaim)) {
            SimpleAssert
                .that(ResourceContainers.generation() > generation, "root codec changes the material generation");
            ResourceContainers.validateSelection(source, new HashSet<>(FLUID_ROOTS));
            expectFailure(
                () -> ResourceContainers
                    .validateSelection(source, new HashSet<>(Arrays.asList("FluidName", "Amount"))));
            SimpleAssert.that(
                ResourceContainers.decodeRoots(source, Collections.emptySet())
                    .isEmpty(),
                "a wholly unselected group has no bill");
            NBTTagCompound plain = (NBTTagCompound) source.copy();
            plain.removeTag("Tag");
            HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(plain);
            SimpleAssert.eq(1, plan.fluids.size(), "top-level fluid has one complete bill");
            SimpleAssert.eq(1250L, plan.fluids.get(0).amount, "physical root quantity is charged once");
            SimpleAssert.eq(original, source, "decoding never mutates the caller's native payload");
            NBTTagCompound malformed = (NBTTagCompound) source.copy();
            malformed.setLong("Amount", 1250);
            expectFailure(() -> HologramBlueprintResources.plan(malformed));
            malformed.setInteger("Amount", -1);
            expectFailure(() -> HologramBlueprintResources.plan(malformed));
            NBTTagCompound missing = (NBTTagCompound) source.copy();
            missing.removeTag("FluidName");
            expectFailure(() -> HologramBlueprintResources.plan(missing));
            ResourceContainers.RootClaim claim = ResourceContainers.decodeRoots(source)
                .get(0);
            FluidStack detached = claim.fluids()
                .get(0)
                .identity();
            detached.tag.setString("variant", "changed outside the claim");
            SimpleAssert.eq(
                tag,
                claim.fluids()
                    .get(0)
                    .identity().tag,
                "returned fluid identities are detached");
        }
        SimpleAssert.that(ResourceContainers.generation() > generation + 1, "closing retires root material caches");
        SimpleAssert
            .eq(0, HologramBlueprintResources.plan(source).fluids.size(), "retirement restores legacy inspection");
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setLong("Credits", 9000);
        SimpleAssert.eq(
            BigInteger.ZERO,
            HologramBlueprintResources.plan(unknown).energy,
            "unrelated unknown scalars do not become assets or newly rejected legacy data");
    }

    private static ResourceContainers.RootClaim fluidClaim(ResourceContainers.RootContext context) throws IOException {
        if (!"test:root-resource".equals(context.nativeTileId)) return null;
        if (!context.selectedGroup(FLUID_ROOTS.toArray(new String[0]))) return null;
        NBTTagCompound root = context.root();
        if (!root.hasKey("FluidName", 8) || !root.hasKey("Amount", 3)
            || root.getInteger("Amount") < 0
            || root.hasKey("Tag") && !root.hasKey("Tag", 10))
            throw new IOException("malformed exact Forge fluid fields");
        FluidStack identity = FluidStack.loadFluidStackFromNBT(root);
        if (identity == null) throw new IOException("unregistered physical fluid");
        root.setInteger("Amount", 1); // Disposable codec data cannot change the source or a later callback.
        return new ResourceContainers.RootClaim(
            FLUID_ROOTS,
            Collections.emptyList(),
            Collections.singletonList(new ResourceContainers.FluidAmount(identity, identity.amount)),
            BigInteger.ZERO);
    }

    private static void selectionAndCompanions() throws Exception {
        try (GTMBExtensions.Registration registration = ResourceContainers
            .registerRoot("test:root-selection", HologramRootResourceRegression::fluidClaim)) {
            NBTTagCompound source = fluid();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("variant", "selected sibling identity");
            source.setTag("Tag", tag);
            NBTTagCompound original = (NBTTagCompound) source.copy();
            String encoded = HologramBlueprintNbt.encodeCaptured(source);
            expectFailure(
                () -> HologramBlueprintNbt.filterCaptured(encoded, Arrays.asList("FluidName", "Amount"), false));
            SimpleAssert.eq(original, source, "partial native selection preserves every source field");
            SimpleAssert.eq(
                original,
                HologramBlueprintNbt.filterSelection(source, null, true),
                "nullable keys still mean complete selection");
            NBTTagCompound omitted = HologramBlueprintNbt.filterSelection(source, Collections.emptyList(), true);
            SimpleAssert.that(
                !omitted.hasKey("FluidName") && !omitted.hasKey("Amount") && !omitted.hasKey("Tag"),
                "the complete physical field group may be omitted without losing its source");
            NBTTagCompound noNbt = HologramBlueprintNbt.forPlacement(
                encoded,
                java.util.UUID.randomUUID(),
                "installer",
                1,
                64,
                2,
                false,
                true,
                Collections.singletonList("Amount"));
            SimpleAssert.that(
                !noNbt.hasKey("FluidName") && !noNbt.hasKey("Amount") && !noNbt.hasKey("Tag"),
                "retainNbt=false validates the actual retained fields instead of stale configured keys");
            NBTTagCompound malformed = (NBTTagCompound) source.copy();
            malformed.setLong("Amount", 1250);
            NBTTagCompound retained = HologramBlueprintNbt.filterSelection(malformed, null, true);
            SimpleAssert.eq(malformed, retained, "selection closure does not block reviewing malformed resource data");
            expectFailure(() -> HologramBlueprintResources.plan(retained));
            NBTTagCompound isolated = new NBTTagCompound();
            isolated.setString("id", malformed.getString("id"));
            isolated.setTag(
                "Amount",
                malformed.getTag("Amount")
                    .copy());
            HologramBlueprintNbt.includeResourceCompanions(malformed, isolated, "Amount");
            SimpleAssert.eq(
                malformed,
                isolated,
                "root-isolated review retains the complete atomic group even when its quantity is malformed");
            NBTTagCompound unrelated = new NBTTagCompound();
            HologramBlueprintNbt.includeResourceCompanions(source, unrelated, "Color");
            SimpleAssert.that(unrelated.hasNoTags(), "unrelated isolated configuration gains no physical siblings");
        }
    }

    private static void physicalItemAndEnergyBills() throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("id", "test:root-items");
        root.setInteger("Quantity", 37);
        NBTTagList items = new NBTTagList();
        items.appendTag(new ItemStack(Items.diamond, 2).writeToNBT(new NBTTagCompound()));
        root.setTag("Items", items);
        root.setLong("Credits", 11);
        try (GTMBExtensions.Registration itemsCodec = ResourceContainers.registerRoot("test:root-items", context -> {
            if (!"test:root-items".equals(context.nativeTileId) || !context.selectedGroup("Items", "Quantity"))
                return null;
            NBTTagCompound data = context.root();
            if (!data.hasKey("Quantity", 3) || data.getInteger("Quantity") <= 0) throw new IOException("quantity");
            ItemStack item = ItemStack.loadItemStackFromNBT(
                data.getTagList("Items", 10)
                    .getCompoundTagAt(0));
            item.stackSize = data.getInteger("Quantity");
            return new ResourceContainers.RootClaim(
                Arrays.asList("Items", "Quantity"),
                Collections.singletonList(item),
                Collections.emptyList(),
                BigInteger.ZERO);
        }); GTMBExtensions.Registration energyCodec = ResourceContainers.registerRoot("test:root-energy", context -> {
            if (!"test:root-items".equals(context.nativeTileId) || !context.selectedGroup("Credits")) return null;
            NBTTagCompound data = context.root();
            if (!data.hasKey("Credits", 4) || data.getLong("Credits") < 0) throw new IOException("credits");
            return new ResourceContainers.RootClaim(
                Collections.singletonList("Credits"),
                Collections.emptyList(),
                Collections.emptyList(),
                BigInteger.valueOf(data.getLong("Credits")));
        })) {
            HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(root);
            SimpleAssert.eq(1, plan.items.size(), "claimed Items is not scanned again by the legacy inspector");
            SimpleAssert.eq(37, plan.items.get(0).stackSize, "physical quantity replaces the display template count");
            SimpleAssert.eq(BigInteger.valueOf(11), plan.energy, "a precise profile can declare a physical EU field");
            ItemStack nativeInventory = new ItemStack(Items.diamond, 37);
            HologramBlueprintResources.Plan credited = HologramBlueprintResources
                .plan(root, Collections.singletonList(nativeInventory), true);
            SimpleAssert.that(credited.items.isEmpty(), "complete native inventory bill pays claimed items once");
            credited.removeItemsIncludedByNativeDrops(Collections.singletonList(nativeInventory));
            SimpleAssert
                .that(credited.items.isEmpty(), "existing native-drop reconciliation keeps zero duplicate cost");
            SimpleAssert.eq(37, nativeInventory.stackSize, "native inventory payment credits are detached");
            HologramBlueprintResources.Plan partial = HologramBlueprintResources
                .plan(root, Collections.singletonList(new ItemStack(Items.diamond, 20)), true);
            SimpleAssert
                .eq(17, partial.items.get(0).stackSize, "partial native inventory credit leaves the exact remainder");
            partial.removeItemsIncludedByNativeDrops(Collections.singletonList(new ItemStack(Items.diamond, 20)));
            SimpleAssert
                .eq(17, partial.items.get(0).stackSize, "legacy reconciliation cannot spend root credits twice");
            NBTTagCompound sameShell = (NBTTagCompound) root.copy();
            sameShell.setInteger("Quantity", 1);
            HologramBlueprintResources.Plan separate = HologramBlueprintResources
                .plan(sameShell, Collections.singletonList(new ItemStack(Items.diamond)), false);
            SimpleAssert.eq(
                1,
                separate.items.get(0).stackSize,
                "a native shell does not pay one identical retained inventory item when the adapter excludes contents");
            try (GTMBExtensions.Registration repeated = ResourceContainers.registerRoot("test:root-repeat", context -> {
                if (!"test:root-items".equals(context.nativeTileId) || !context.selectedGroup("ExtraQuantity"))
                    return null;
                return new ResourceContainers.RootClaim(
                    Collections.singletonList("ExtraQuantity"),
                    Collections.singletonList(
                        new ItemStack(
                            Items.diamond,
                            context.root()
                                .getInteger("ExtraQuantity"))),
                    Collections.emptyList(),
                    BigInteger.ZERO);
            })) {
                NBTTagCompound extra = (NBTTagCompound) root.copy();
                extra.setInteger("ExtraQuantity", 3);
                HologramBlueprintResources.Plan twoGroups = HologramBlueprintResources
                    .plan(extra, Collections.singletonList(nativeInventory), true);
                twoGroups = new HologramBlueprintResources.Plan().merge(twoGroups);
                twoGroups.removeItemsIncludedByNativeDrops(Collections.singletonList(nativeInventory));
                SimpleAssert.eq(
                    3,
                    twoGroups.items.get(0).stackSize,
                    "one native inventory bill cannot cover a second root group containing the same material");
            }
            ResourceContainers.RootClaim claim = ResourceContainers.decodeRoots(root)
                .get(0);
            claim.items()
                .get(0).stackSize = 1;
            SimpleAssert.eq(
                37,
                claim.items()
                    .get(0).stackSize,
                "returned item stacks cannot mutate a claim");
        }
        try {
            new ResourceContainers.RootClaim(
                Arrays.asList("same", "same"),
                Collections.emptyList(),
                Collections.emptyList(),
                BigInteger.ZERO);
            throw new AssertionError("duplicate group fields accepted");
        } catch (IllegalArgumentException expected) {}
        try {
            new ResourceContainers.RootClaim(
                Collections.singletonList("Items"),
                Collections.singletonList(new ItemStack(Items.diamond, 0)),
                Collections.emptyList(),
                BigInteger.ZERO);
            throw new AssertionError("zero-count physical item accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static void overlapsAndRejection() throws Exception {
        try (
            GTMBExtensions.Registration first = ResourceContainers
                .registerRoot("test:root-overlap-first", HologramRootResourceRegression::fluidClaim);
            GTMBExtensions.Registration second = ResourceContainers
                .registerRoot("test:root-overlap-second", context -> {
                    if (!"test:root-resource".equals(context.nativeTileId)) return null;
                    return new ResourceContainers.RootClaim(
                        Collections.singletonList("Amount"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        BigInteger.ZERO);
                })) {
            expectFailure(() -> HologramBlueprintResources.plan(fluid()));
        }
        try (
            GTMBExtensions.Registration first = ResourceContainers
                .registerRoot("test:root-optional-first", HologramRootResourceRegression::fluidClaim);
            GTMBExtensions.Registration second = ResourceContainers
                .registerRoot("test:root-optional-second", context -> {
                    if (!"test:root-resource".equals(context.nativeTileId)) return null;
                    return new ResourceContainers.RootClaim(
                        Arrays.asList("Other", "Tag"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        BigInteger.ZERO);
                })) {
            NBTTagCompound source = fluid();
            source.setInteger("Other", 1);
            expectFailure(() -> HologramBlueprintResources.plan(source));
        }
        AtomicInteger fallbackCalls = new AtomicInteger();
        try (GTMBExtensions.Registration rejection = ResourceContainers.registerRoot("test:root-reject", context -> {
            if (!"test:root-resource".equals(context.nativeTileId)) return null;
            throw new IOException("explicit native schema rejection");
        }); GTMBExtensions.Registration fallback = ResourceContainers.registerRoot("test:root-fallback", context -> {
            fallbackCalls.incrementAndGet();
            return fluidClaim(context);
        })) {
            expectFailure(() -> HologramBlueprintResources.plan(fluid()));
            SimpleAssert
                .eq(0, fallbackCalls.get(), "a matched rejection never enters another codec or legacy fallback");
        }
    }

    private static NBTTagCompound fluid() {
        NBTTagCompound root = new NBTTagCompound();
        new FluidStack(FluidRegistry.WATER, 1250).writeToNBT(root);
        root.setString("id", "test:root-resource");
        return root;
    }

    private static void expectFailure(Checked operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("invalid root resource claim accepted");
        } catch (IOException expected) {}
    }

    private interface Checked {

        void run() throws Exception;
    }
}

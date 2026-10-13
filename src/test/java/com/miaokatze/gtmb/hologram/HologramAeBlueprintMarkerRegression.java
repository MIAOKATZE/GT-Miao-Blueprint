package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Pure NBT loss-marker regression after the standard Minecraft bootstrap; no native AE world or registration. */
public final class HologramAeBlueprintMarkerRegression {

    private static int checks;

    private HologramAeBlueprintMarkerRegression() {}

    public static void main(String[] arguments) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAeBlueprintMarkerRegression.class, arguments)) return;
        run();
    }

    public static void run() {
        try {
            runChecked();
        } catch (IOException invalid) {
            throw new AssertionError("AE persistent loss-marker regression failed", invalid);
        }
    }

    private static void runChecked() throws IOException {
        checks = 0;
        filteredEmptyPayload();
        Block inert = new InertBlock();
        NBTTagCompound original = marker("missing_physical_item", "inv/item0");
        NBTTagCompound saved = (NBTTagCompound) original.copy();
        HologramAeBlueprint.Analysis first = HologramAeBlueprint.analyze(inert, 0, original);
        require(first.blocking, "loss marker blocks direct analysis");
        HologramAeBlueprint.Analysis second = HologramAeBlueprint.analyze(inert, 0, first.payload());
        require(second.blocking && second.issues.size() == 1, "reanalysis is idempotent without source shadow");
        require(original.equals(saved), "analysis never changes caller marker NBT");
        HologramAeBlueprint.Slot tombstone = second.slots.get(0);
        require(
            tombstone.kind.equals("readonly") && !tombstone.enabled && tombstone.issuePath.equals("inv/item0"),
            "loss receives exact readonly review descriptor");
        HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
            .updateSlot(inert, 0, second.payload(), tombstone.id, null);
        require(
            !cleared.blocking && !cleared.payload()
                .hasKey(HologramAeBlueprint.UNRESOLVED),
            "explicit review CLEAR resolves only its loss marker");
        require(
            HologramAeBlueprint.resolvedIssuePaths(tombstone.id, second, cleared)
                .contains("inv/item0"),
            "resolved metadata path matches descriptor");
        NBTTagCompound permanent = marker("unsupported_cell", "inv/item0");
        HologramAeBlueprint.Analysis denied = HologramAeBlueprint.analyze(inert, 0, permanent);
        require(denied.blocking && denied.slots.isEmpty(), "unknown cell cannot receive an approval tombstone");
        boolean refused = false;
        try {
            HologramAeBlueprint.resolveDiscardedIssue(permanent, "unsupported_cell", "inv/item0");
        } catch (IOException expected) {
            refused = true;
        }
        require(refused, "unknown cell cannot be acknowledged for installation");
        NBTTagCompound two = marker("missing_physical_item", "inv/item0");
        two.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
            .appendTag(entry("waiting_stack_invalid", "waitingToSend/#0"));
        NBTTagCompound one = HologramAeBlueprint.resolveDiscardedIssue(two, "missing_physical_item", "inv/item0");
        HologramAeBlueprint.Analysis left = HologramAeBlueprint.analyze(inert, 0, one);
        require(
            left.blocking && left.issues.size() == 1 && left.issues.get(0).path.equals("waitingToSend/#0"),
            "one review cannot retire another slot's omitted physical record");
        require(
            two.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
                .tagCount() == 2,
            "resolution input remains unchanged");
        definitionDeletionScopes(inert);
        recipeTransportMarkers();
        System.out.println("[GTMB-AE-MARKERS] PASS " + checks + " pure NBT assertions; not native/FML validation");
    }

    private static void recipeTransportMarkers() throws IOException {
        NBTTagCompound recipe = new NBTTagCompound(), item = new NBTTagCompound(),
            legalRecipeData = new NBTTagCompound();
        new ItemStack(Items.iron_ingot).writeToNBT(item);
        legalRecipeData.setString("ownerUUID", "recipe-owner-must-survive");
        legalRecipeData.setInteger("x", 19);
        legalRecipeData.setInteger("y", 28);
        legalRecipeData.setInteger("z", -31);
        item.setTag("tag", legalRecipeData);
        NBTTagList inputs = new NBTTagList();
        inputs.appendTag(item);
        recipe.setTag("in", inputs);
        NBTTagCompound original = (NBTTagCompound) recipe.copy();
        NBTTagCompound transported = HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCanonical(recipe));
        require(
            !transported.equals(recipe) && transported.getTagList("in", 10)
                .getCompoundTagAt(0)
                .hasKey(HologramBlueprintNbt.ITEM_REGISTRY, 8),
            "canonical transport adds registry-name metadata beyond the native recipe");
        require(
            HologramBlueprintNbt.nativePayload(transported)
                .equals(recipe),
            "stripping only defined transport markers preserves the entire native recipe including owner and coordinates");
        require(recipe.equals(original), "recipe transport projection preserves the original snapshot");
    }

    private static void definitionDeletionScopes(Block inert) throws IOException {
        try {
            // Native registration is covered separately; this fixture isolates typed issue-marker scope.
            Constructor<HologramAeBlueprint.Slot> nativeSlot = HologramAeBlueprint.Slot.class.getDeclaredConstructor(
                String.class,
                String.class,
                int.class,
                String.class,
                ItemStack.class,
                String[].class,
                int.class,
                Object.class);
            nativeSlot.setAccessible(true);
            Method clear = HologramAeBlueprint.class
                .getDeclaredMethod("clearPartDefinitionMarkers", NBTTagCompound.class, HologramAeBlueprint.Slot.class);
            clear.setAccessible(true);
            NBTTagCompound original = marker("unsupported_part_item", "extra:2");
            NBTTagList entries = original.getTagList(HologramAeBlueprint.UNRESOLVED, 10);
            entries.appendTag(entry("unsupported_part", "extra:2/hidden"));
            entries.appendTag(entry("unreviewed_part_field", "extra:3/hidden"));
            entries.appendTag(entry("unsupported_host", ""));
            NBTTagCompound changed = (NBTTagCompound) original.copy();
            HologramAeBlueprint.Slot selected = nativeSlot
                .newInstance("def:2", "readonly", 1, "part", null, new String[] { "def:2" }, 2, null);
            clear.invoke(null, changed, selected);
            HologramAeBlueprint.Analysis left = HologramAeBlueprint.analyze(inert, 0, changed);
            require(
                left.blocking && left.issues.size() == 2
                    && left.issues.stream()
                        .noneMatch(issue -> issue.path.startsWith("extra:2")),
                "definition CLEAR retires permanent and descendant issues only from its corresponding extra subtree");
            require(
                left.issues.stream()
                    .anyMatch(issue -> issue.code.equals("unsupported_host") && issue.path.isEmpty()),
                "definition deletion never acknowledges an unknown host");
            require(
                original.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
                    .tagCount() == 4,
                "typed deletion projection leaves the original marker snapshot unchanged");
            NBTTagCompound single = marker("unsupported_part", "extra:2");
            clear.invoke(null, single, selected);
            require(
                !HologramAeBlueprint.analyze(inert, 0, single).blocking,
                "deleted permanent part issue does not return on complete second analysis");
            NBTTagCompound mixed = marker("unsupported_part", "@part=0/extra:2");
            mixed.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
                .appendTag(entry("unsupported_part_item", "@part=1/extra:2"));
            mixed.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
                .appendTag(entry("unreviewed_part_field", "@part=0/extra:3/x"));
            HologramAeBlueprint.Slot mixedSlot = nativeSlot.newInstance(
                "@part=0/def:2",
                "readonly",
                1,
                "part",
                null,
                new String[] { "@part=0", "def:2" },
                2,
                null);
            clear.invoke(null, mixed, mixedSlot);
            HologramAeBlueprint.Analysis mixedLeft = HologramAeBlueprint.analyze(inert, 0, mixed);
            require(
                mixedLeft.blocking && mixedLeft.issues.size() == 2
                    && mixedLeft.issues.stream()
                        .anyMatch(issue -> issue.path.equals("@part=1/extra:2")),
                "FMP definition deletion preserves another part and another side's blockers");
            NBTTagCompound unrelated = marker("unsupported_part", "extra:2");
            HologramAeBlueprint.Slot ordinary = nativeSlot
                .newInstance("def:2", "physical", 1, "any", null, new String[] { "def:2" }, 2, null);
            clear.invoke(null, unrelated, ordinary);
            require(
                HologramAeBlueprint.analyze(inert, 0, unrelated).blocking,
                "an ordinary slot cannot invoke definition-specific marker retirement");
        } catch (ReflectiveOperationException invalid) {
            throw new IOException("Native slot marker fixture contract changed", invalid);
        }
    }

    private static final class InertBlock extends Block {

        InertBlock() {
            super(Material.rock);
        }
    }

    private static void filteredEmptyPayload() throws IOException {
        Block recognized = new appeng.block.GtmbAeStaticFixture();
        NBTTagCompound raw = new NBTTagCompound(), inv = new NBTTagCompound(), item = new NBTTagCompound();
        new ItemStack(Items.diamond, 37).writeToNBT(item);
        inv.setTag("item0", item);
        raw.setTag("inv", inv);
        raw.setTag("patterns", item.copy());
        raw.setTag("config", item.copy());
        raw.setString("orientation_forward", "WEST");
        raw.setByte("paintedColor", (byte) 3);
        NBTTagCompound proxy = new NBTTagCompound();
        proxy.setInteger("p", 999);
        proxy.setLong("g", 777);
        raw.setTag("proxy", proxy);
        NBTTagCompound before = (NBTTagCompound) raw.copy();
        NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
            recognized,
            0,
            HologramBlueprintNbt.encodeCanonical(recognized, raw),
            java.util.UUID.randomUUID(),
            "builder",
            13,
            64,
            -8,
            false,
            false,
            null);
        require(
            selected != null && !selected.hasKey("inv")
                && !selected.hasKey("patterns")
                && !selected.hasKey("config")
                && !selected.hasKey("orientation_forward")
                && !selected.hasKey("paintedColor")
                && !selected.hasKey("proxy"),
            "AE both-disabled filtering yields a canonical empty host without source configuration/identity");
        HologramBlueprintResources.Plan extras = HologramBlueprintResources.plan(selected);
        require(
            extras.items.isEmpty() && extras.fluids.isEmpty() && extras.energy.signum() == 0,
            "filtered empty canonical pays no omitted inventory/configuration resources");
        require(
            selected.getInteger("x") == 13 && selected.getInteger("y") == 64
                && selected.getInteger("z") == -8
                && raw.equals(before),
            "filtered shell binds target coordinates without mutating the original payload");
        require(
            !HologramAeBlueprint.supportsEmptyNativeShell(recognized)
                && !HologramAeBlueprint.supportsEmptyNativeShell(new InertBlock()),
            "namespace recognition or ordinary static blocks cannot opt into a native empty-shell installation");
        raw.setTag(
            HologramAeBlueprint.UNRESOLVED,
            marker("unsupported_cell", "inv/item0").getTag(HologramAeBlueprint.UNRESOLVED)
                .copy());
        boolean refused = false;
        try {
            HologramBlueprintNbt.forPlacement(
                recognized,
                0,
                HologramBlueprintNbt.encodeCanonical(recognized, raw),
                java.util.UUID.randomUUID(),
                "builder",
                13,
                64,
                -8,
                false,
                false,
                null);
        } catch (IOException expected) {
            refused = true;
        }
        require(refused, "disabling NBT/covers cannot acknowledge a persistent schema/slot blocker");
    }

    private static NBTTagCompound marker(String code, String path) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList entries = new NBTTagList();
        entries.appendTag(entry(code, path));
        result.setTag(HologramAeBlueprint.UNRESOLVED, entries);
        return result;
    }

    private static NBTTagCompound entry(String code, String path) {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("code", code);
        result.setString("path", path);
        result.setString("message", "fixture loss report");
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
        System.out.println("[GTMB-AE-MARKERS] PASS " + message);
    }
}

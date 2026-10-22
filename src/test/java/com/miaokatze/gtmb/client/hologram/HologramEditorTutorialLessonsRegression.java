package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Action;
import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Operation;
import com.miaokatze.gtmb.client.hologram.HologramEditorLessons.Step;
import com.miaokatze.gtmb.hologram.HologramObservedItemBindings;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Use the shipped lossless observations to distinguish a controller capture from declared author members. */
public final class HologramEditorTutorialLessonsRegression {

    private HologramEditorTutorialLessonsRegression() {}

    public static void run() {
        try {
            portableIdentityWinsOverNumericId();
            NBTTagCompound physicalData = HologramEditorTutorialSamples
                .portableObservation(HologramEditorTutorialSamples.Kind.PHYSICAL);
            NBTTagCompound physicalBefore = (NBTTagCompound) physicalData.copy();
            HologramState physical = new HologramState(physicalData);
            SimpleAssert.eq(36, physical.cells.size(), "the real physical EBF capture retains all 36 declared cells");
            SimpleAssert.eq(
                0,
                physical.data.getTagList("structures", 10)
                    .tagCount(),
                "the physical source has no area member declarations");
            SimpleAssert.eq(
                36,
                physical.structureMembers.get("")
                    .size(),
                "the original empty membership bucket remains compatible");
            SimpleAssert.that(
                !HologramEditorLessons.hasDeclaredStructure(physical),
                "an empty membership sentinel is not an identified source member");
            for (String topic : new String[] { "projection", "structure" }) {
                List<Step> direct = HologramEditorLessons.course(topic, null, physical);
                SimpleAssert.that(
                    !hasOperation(direct, Operation.SELECT_STRUCTURE),
                    "direct controller teaching does not request an area outline: " + topic);
                SimpleAssert.that(
                    !hasControl(direct, 163) && !hasControl(direct, 165),
                    "direct controller teaching has no enter/leave-area-member actions: " + topic);
                SimpleAssert.that(
                    hasControl(direct, 60),
                    "direct controller teaching keeps its real declared-parameter entry: " + topic);
                SimpleAssert.that(
                    hasTarget(direct, "launcher.3"),
                    "direct controller teaching visibly opens its actual source pane: " + topic);
            }
            HologramSelection physicalSelection = new HologramSelection();
            physicalSelection.click(0, false);
            SimpleAssert.eq(
                1,
                physicalSelection.size(),
                "direct source context does not replace the real single-cell selection with select-all");
            SimpleAssert.that(
                physicalBefore.equals(physicalData),
                "course adaptation leaves physical geometry and ownership data unchanged");

            NBTTagCompound malformed = (NBTTagCompound) physicalData.copy();
            malformed.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .setString("structureId", "undeclared-member");
            SimpleAssert.that(
                !HologramEditorLessons.hasDeclaredStructure(new HologramState(malformed)),
                "a nonempty cell membership without a matching source declaration is not sufficient");
            NBTTagCompound emptyDeclaration = new NBTTagCompound();
            emptyDeclaration.setString("id", "");
            NBTTagList declarations = new NBTTagList();
            declarations.appendTag(emptyDeclaration);
            malformed = (NBTTagCompound) physicalData.copy();
            malformed.setTag("structures", declarations);
            SimpleAssert.that(
                !HologramEditorLessons.hasDeclaredStructure(new HologramState(malformed)),
                "a blank declaration cannot promote the empty membership sentinel into an area member");

            HologramState author = new HologramState(
                HologramEditorTutorialSamples.portableObservation(HologramEditorTutorialSamples.Kind.AUTHOR));
            String primary = HologramEditorTutorialSamples.primaryStructure(HologramEditorTutorialSamples.Kind.AUTHOR);
            SimpleAssert
                .eq(39, author.cells.size(), "the recorded author fixture retains its EBF plus additional cells");
            SimpleAssert.eq(
                36,
                author.structureMembers.get(primary)
                    .size(),
                "the recorded author source has an exact 36-cell EBF footprint");
            SimpleAssert.that(
                HologramEditorLessons.hasDeclaredStructure(author),
                "the actual author source is a declared member with real cells");
            List<Step> structure = HologramEditorLessons.course("structure", null, author);
            SimpleAssert.that(
                hasOperation(structure, Operation.SELECT_STRUCTURE) && hasControl(structure, 163),
                "declared source teaching retains real member selection and entry");
            HologramEditorCommands commands = new HologramEditorCommands(null);
            // Headless course assertions need no physical keyboard/native library.
            for (int i = 0; i < commands.size(); i++) commands.get(i).key = 0;
            List<Step> blueprint = HologramEditorLessons.course("blueprint", commands, author);
            SimpleAssert.that(
                hasOperation(blueprint, Operation.SELECT_STRUCTURE) && hasControl(blueprint, 163),
                "the actual author blueprint course retains group selection and source editing");
            HologramSelection members = new HologramSelection();
            members.structure(author, primary, false);
            SimpleAssert.eq(
                36,
                members.size(),
                "native member selection includes only the observed EBF, excluding unrelated author cells");
        } catch (IOException failure) {
            throw new AssertionError("The shipped EBF tutorial observations must load and validate", failure);
        }
    }

    private static void portableIdentityWinsOverNumericId() throws IOException {
        NBTTagCompound stack = new ItemStack(Items.diamond).writeToNBT(new NBTTagCompound());
        stack.setString("gtitBlueprintItemRegistry", "minecraft:diamond");
        stack.setShort("id", (short) net.minecraft.item.Item.getIdFromItem(Items.iron_ingot));
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList nested = new NBTTagList();
        nested.appendTag(stack);
        root.setTag("nestedInventory", nested);
        NBTTagCompound restored = HologramObservedItemBindings.restore(root);
        SimpleAssert.that(
            ItemStack.loadItemStackFromNBT(
                restored.getTagList("nestedInventory", 10)
                    .getCompoundTagAt(0))
                .getItem() == Items.diamond,
            "nested portable ItemStacks bind by registry name even when the numeric ID resolves to another real item");
        SimpleAssert.eq(
            net.minecraft.item.Item.getIdFromItem(Items.iron_ingot),
            stack.getShort("id"),
            "restoring a portable observation leaves the source evidence immutable");
        stack.removeTag("gtitBlueprintItemRegistry");
        boolean rejected = false;
        try {
            HologramObservedItemBindings.restore(root);
        } catch (IOException missing) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "an offline numeric ItemStack without a registry binding is rejected");
    }

    private static boolean hasOperation(List<Step> steps, Operation operation) {
        for (Step step : steps) for (Action action : step.actions) if (action.operation == operation) return true;
        return false;
    }

    private static boolean hasControl(List<Step> steps, int control) {
        for (Step step : steps) for (Action action : step.actions) if (action.control == control) return true;
        return false;
    }

    private static boolean hasTarget(List<Step> steps, String target) {
        for (Step step : steps) for (Action action : step.actions) if (target.equals(action.target)) return true;
        return false;
    }
}

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
            completeCourseOrdering(commands, blueprint);
            attachmentCoursesUseTheCompletePlacementChain(commands);
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

    private static void completeCourseOrdering(HologramEditorCommands commands, List<Step> blueprint) {
        SimpleAssert
            .eq(14000, blueprint.get(1).actions.get(0).control, "the first load selects the first in-memory row");
        SimpleAssert.that(
            !hasControl(blueprint, 94) && hasAction(blueprint, Operation.HOVER_NATIVE, "control.94"),
            "author teaching observes the disabled whole-machine preset without dispatching its physical-only action");
        SimpleAssert.that(
            !hasControl(blueprint, 166) && hasAction(blueprint, Operation.HOVER_NATIVE, "control.166"),
            "author teaching only observes the NBT gate and does not invent unrecorded per-cell values");
        SimpleAssert.that(
            hasAction(blueprint, Operation.HOVER_NATIVE, "library.save.overwrite")
                && !hasAction(blueprint, Operation.HOVER_NATIVE, "control.222")
                && !hasTarget(blueprint, "library.save.asNew"),
            "overwrite teaching addresses its distinct target rather than the shared ID's first save-as button");
        SimpleAssert.that(
            hasOperation(blueprint, Operation.CANCEL_DISCARD) && hasOperation(blueprint, Operation.CONFIRM_DISCARD),
            "dirty source switching demonstrates both explicit modal choices");
        int axis = operationStep(blueprint, Operation.MOVE_X), plane = operationStep(blueprint, Operation.MOVE_XZ),
            copy = operationStep(blueprint, Operation.COPY_X),
            cancel = operationStep(blueprint, Operation.CANCEL_DISCARD),
            confirm = operationStep(blueprint, Operation.CONFIRM_DISCARD);
        SimpleAssert.that(
            axis < cancel && cancel < confirm && confirm < plane && plane < copy,
            "discard teaching follows a real move, then the final ground layout is moved and copied after reloading");
        SimpleAssert.eq(25, blueprint.size(), "the complete blueprint course retains 25 deliberate steps");
        SimpleAssert.that(
            blueprint.get(1).description.contains("两台") && blueprint.get(copy).description.contains("共三台"),
            "the course explains two initial machines and the third created by the actual copy");
        for (String topic : new String[] { "box-fps", "box-iso", "cut-fps", "cut-iso" }) {
            List<Step> world = HologramEditorLessons.course(topic, commands);
            SimpleAssert.that(
                !hasOperation(world, Operation.WORLD_MOVE_XZ),
                "world lessons do not invent an editor gizmo: " + topic);
            SimpleAssert.that(
                operationStep(world, Operation.FIRST_CORNER) < operationStep(world, Operation.SECOND_CORNER)
                    && operationStep(world, Operation.SECOND_CORNER)
                        < operationStep(world, Operation.WORLD_ADJUST_CORNER),
                "both corners exist before a second-corner correction: " + topic);
            if (topic.startsWith("cut-")) {
                SimpleAssert.that(
                    operationStep(world, Operation.WORLD_ADJUST_CORNER)
                        < operationStep(world, Operation.CUT_SOURCE_REVIEW)
                        && operationStep(world, Operation.CUT_SOURCE_REVIEW)
                            < operationStep(world, Operation.CUT_STORE),
                    "source naming remains visible for a separate save step: " + topic);
                SimpleAssert.that(
                    operationOrdinal(world, Operation.CUT_LOCK) < operationOrdinal(world, Operation.CUT_PLACE_REVIEW)
                        && operationOrdinal(world, Operation.CUT_PLACE_REVIEW)
                            < operationOrdinal(world, Operation.CUT_CANCEL)
                        && operationOrdinal(world, Operation.CUT_CANCEL) < operationOrdinal(world, Operation.CUT_PLACE),
                    "placement lock, modal review, cancellation and explicit confirmation have distinct boundaries: "
                        + topic);
                SimpleAssert.that(
                    operationStep(world, Operation.CUT_OPEN_STORAGE) < operationStep(world, Operation.CUT_TAKE)
                        && hasTarget(world, "cut.place"),
                    "the retained entry is taken only through the visible storage place button: " + topic);
                Step last = world.get(world.size() - 1);
                SimpleAssert.that(
                    last.actions.size() == 1 && last.actions.get(0).operation == Operation.CUT_PLACE
                        && "cut.lesson.placement.confirm".equals(last.actions.get(0).target),
                    "only a separate confirm-button action consumes the teaching entry: " + topic);
            } else SimpleAssert.that(
                operationStep(world, Operation.WORLD_ADJUST_CORNER) < operationStep(world, Operation.BOX_SUBMIT),
                "box submission follows an actual complete selection: " + topic);
        }
        List<Step> cutList = HologramEditorLessons.course("cut", commands);
        SimpleAssert.that(
            !hasOperation(cutList, Operation.CUT_STORE) && !hasOperation(cutList, Operation.CUT_PLACE),
            "the read-only cut-list lesson never pretends to place or consume an entry");
        SimpleAssert.that(
            cutList.get(1).description.contains("左侧"),
            "the cut preview description matches the retained left viewport");
    }

    private static void attachmentCoursesUseTheCompletePlacementChain(HologramEditorCommands commands)
        throws IOException {
        for (String topic : new String[] { "text", "picture" }) {
            List<Step> lesson = HologramEditorLessons.course(topic, commands);
            String expectedTool = topic.equals("picture") ? "image" : "text";
            int toolActions = 0;
            for (Step step : lesson) for (Action action : step.actions) {
                if (action.operation != Operation.OPEN_TOOL) continue;
                int index = commands.index(action.target);
                SimpleAssert.that(
                    index >= 0,
                    "attachment tool actions resolve in the production command catalogue: " + action.target);
                SimpleAssert.eq(
                    expectedTool,
                    commands.get(index).name,
                    "attachment opening and placement confirmation use the real command name");
                SimpleAssert.eq(
                    topic.equals("picture") ? 332 : 331,
                    commands.get(index).control,
                    "attachment commands retain the real router's text/image control identity");
                toolActions++;
            }
            SimpleAssert
                .eq(2, toolActions, "both opening and final placement confirmation resolve through real commands");
            int handles = commands.index("moveHandles");
            SimpleAssert.that(
                handles >= 0 && commands.get(handles).name.equals("moveHandles"),
                "attachment movement uses the production move-handles command");
            SimpleAssert.eq(
                topic.equals("text") ? 9 : 11,
                lesson.size(),
                "attachment lessons retain distinct review boundaries: " + topic);
            SimpleAssert.that(
                lesson.get(0).actions.isEmpty() && operationStep(lesson, Operation.OPEN_TOOL) > 0
                    && !hasOperation(lesson, Operation.SELECT_CELL)
                    && operationStep(lesson, Operation.TYPE_ATTACHMENT)
                        < operationStep(lesson, Operation.POSITION_ATTACHMENT)
                    && operationStep(lesson, Operation.POSITION_ATTACHMENT)
                        < operationStep(lesson, Operation.SELECT_ATTACHMENT)
                    && operationStep(lesson, Operation.SELECT_ATTACHMENT) < operationStep(lesson, Operation.MOVE_X),
                "attachment teaching opens without preselection, enters content, positions, then selects and moves the result: "
                    + topic);
            int generation = -1;
            for (int i = 0; i < lesson.size(); i++) for (Action action : lesson.get(i).actions)
                if (action.target.equals("attachment." + topic + ".generate")) generation = i;
            SimpleAssert.that(
                generation >= 0 && lesson.get(generation).actions.size() == 2
                    && lesson.get(generation).actions.get(1).target.equals("attachment.preset.skip"),
                "generate and explicit preset-skip share one step, keeping checkpoint boundaries free of save closures: "
                    + topic);
            SimpleAssert.that(
                !hasOperation(lesson, Operation.LIBRARY) && !hasControl(lesson, 222),
                "attachment lessons do not save or replace library documents: " + topic);
        }
        List<Step> picture = HologramEditorLessons.course("picture", commands);
        SimpleAssert.that(
            hasOperation(picture, Operation.SELECT_PICTURE) && hasTarget(picture, "attachment.picture.filters")
                && hasTarget(picture, "attachment.picture.filter"),
            "image teaching loads a local catalog row and uses the actual filter controls");
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("gtmb-tutorial-picture-regression-");
        java.nio.file.Path own = null;
        try {
            own = HologramEditorTutorialSamples.createPictureSample(directory);
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(own.toFile());
            SimpleAssert.eq(8, image.getWidth(), "the teaching image has a bounded eight-pixel width");
            SimpleAssert.eq(8, image.getHeight(), "the teaching image has a bounded eight-pixel height");
            SimpleAssert.that(
                own.getParent()
                    .equals(directory),
                "the helper owns a unique file in its supplied catalog");
        } finally {
            if (own != null) java.nio.file.Files.deleteIfExists(own);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    private static int operationStep(List<Step> steps, Operation operation) {
        for (int i = 0; i < steps.size(); i++)
            for (Action action : steps.get(i).actions) if (action.operation == operation) return i;
        throw new AssertionError("Missing course action " + operation);
    }

    private static int operationOrdinal(List<Step> steps, Operation operation) {
        int ordinal = 0;
        for (Step step : steps) for (Action action : step.actions) {
            if (action.operation == operation) return ordinal;
            ordinal++;
        }
        throw new AssertionError("Missing course action " + operation);
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
            (int) stack.getShort("id"),
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

    private static boolean hasAction(List<Step> steps, Operation operation, String target) {
        for (Step step : steps) for (Action action : step.actions)
            if (action.operation == operation && target.equals(action.target)) return true;
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

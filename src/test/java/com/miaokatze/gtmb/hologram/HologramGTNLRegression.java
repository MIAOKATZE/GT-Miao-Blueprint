package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.testutil.TestRunner;

/** Geometry and declaration contracts run without loading GTNL or invoking any upstream callback. */
public final class HologramGTNLRegression {

    private HologramGTNLRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramGTNLRegression.class, args)) return;
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("nuclear-geometry", HologramGTNLRegression::nuclearGeometry);
        tests.put("independent-channels", HologramGTNLRegression::channels);
        tests.put("fixed-leaf-purity-and-damage", HologramGTNLRegression::fixedLeaf);
        tests.put("bounded-mbs-decoder", HologramGTNLRegression::codec);
        tests.put("real-sphere-resource", HologramGTNLRegression::sphere);
        TestRunner.run(HologramGTNLRegression.class, tests);
    }

    static void run() {
        nuclearGeometry();
        channels();
        fixedLeaf();
        codec();
        sphere();
    }

    private static void nuclearGeometry() {
        int[] expected = { 9, 21, 37 };
        for (int tier = 1; tier <= 3; tier++) {
            String[][] shape = HologramGTNLShapes.nuclear(tier);
            int width = 2 * tier + 3;
            SimpleAssert.eq(width, shape.length, "reactor depth at tier " + tier);
            int cores = 0, anchors = 0;
            for (int depth = 0; depth < shape.length; depth++) {
                SimpleAssert.eq(5, shape[depth].length, "reactor has five height rows");
                for (int row = 0; row < 5; row++) {
                    SimpleAssert.eq(width, shape[depth][row].length(), "reactor row width");
                    for (int col = 0; col < width; col++) {
                        char symbol = shape[depth][row].charAt(col);
                        if (symbol == '~') anchors++;
                        if (symbol == 'X' || symbol == 'A'
                            || symbol == 'B'
                            || symbol == 'C'
                            || symbol == ' '
                            || symbol == '~') continue;
                        cores++;
                        SimpleAssert.eq(0, row, "core chambers only appear in the top layer");
                        SimpleAssert.that(
                            HologramGTNLShapes.coreCell(tier, depth - 1, col - 1),
                            "core chamber avoids cut corners");
                        SimpleAssert.eq(
                            HologramGTNLShapes.coreChar(depth - 1, col - 1),
                            symbol,
                            "core symbols preserve row and column identity");
                    }
                }
            }
            SimpleAssert.eq(expected[tier - 1], cores, "reactor 9/21/37 chamber oracle");
            SimpleAssert.eq(1, anchors, "one controller anchor");
            SimpleAssert.eq('~', shape[0][2].charAt(width / 2), "controller at depth zero with signed offsets");
        }
        SimpleAssert.that(!HologramGTNLShapes.coreCell(3, 0, 0), "large reactor corner is a seal, not a chamber");
        SimpleAssert.eq(1, HologramGTNLShapes.nuclearTier(-4), "low tier clamps to one");
        SimpleAssert.eq(3, HologramGTNLShapes.nuclearTier(64), "large tier clamps to three");
    }

    private static void channels() {
        ItemStack trigger = new ItemStack(Items.feather, 3);
        SimpleAssert.eq(3, HologramGTNLSemantics.channel(trigger, "casing", 3), "unselected channel uses stack");
        ChannelDataAccessor.setChannelData(trigger, "casing", 1);
        SimpleAssert.eq(1, HologramGTNLSemantics.channel(trigger, "casing", 3), "explicit nuclear channel beats stack");
        ChannelDataAccessor.setChannelData(trigger, "casing", 64);
        SimpleAssert.eq(3, HologramGTNLSemantics.channel(trigger, "casing", 3), "nuclear channel upper bound");
        for (int machine = 1; machine <= 10; machine++) for (int unit = 1; unit <= 5; unit++) {
            ChannelDataAccessor.setChannelData(trigger, "machine_casing", machine);
            ChannelDataAccessor.setChannelData(trigger, "unit_casing", unit);
            SimpleAssert.eq(
                machine,
                HologramGTNLSemantics.channel(trigger, "machine_casing", 10),
                "all ten machine levels retain their independent channel");
            SimpleAssert.eq(
                unit,
                HologramGTNLSemantics.channel(trigger, "unit_casing", 5),
                "all five unit levels retain their independent channel");
        }
        SimpleAssert.eq(3, trigger.stackSize, "queries never mutate the trigger");
        SimpleAssert.that(!HologramGTNLSemantics.supports(new Object()), "no class-name approximation for other mods");
    }

    private static void fixedLeaf() {
        ItemStack core = new ItemStack(Blocks.stone, 1, 18);
        ItemStack alternative = new ItemStack(Blocks.stone, 1, 9);
        HologramGTNLElement element = new HologramGTNLElement(
            "test.core",
            HologramGTNLElement.Kind.BLOCK,
            Arrays.asList(core, alternative),
            Arrays.asList(core),
            true,
            "",
            -1,
            -1,
            -1);
        core.setItemDamage(2);
        SimpleAssert.eq(
            18,
            element.primary()
                .getItemDamage(),
            "material damage remains eighteen after caller edit");
        element.candidates()
            .get(0)
            .setItemDamage(2);
        SimpleAssert.eq(
            18,
            element.primary()
                .getItemDamage(),
            "returned candidates do not expose mutable backing");
        SimpleAssert.that(element.accepts(alternative), "structurally accepted alternative");
        SimpleAssert.that(!element.placementAllows(alternative), "placement subset cannot enlarge structural rules");
        SimpleAssert.that(!element.accepts(core), "damage two cannot stand in for damage eighteen");
        ItemStack sealed = element.primary();
        sealed.setTagCompound(new NBTTagCompound());
        sealed.getTagCompound()
            .setString("unrelated-preserved-data", "retained");
        SimpleAssert.that(element.accepts(sealed), "material acceptance does not destroy preserved NBT");
        HologramCapture.Cell cell = new HologramCapture.Cell(element, 0, 0, 0);
        element.describeCell(cell);
        SimpleAssert.eq(18, cell.blueprintDamage, "saved item damage is independent of world metadata");
        SimpleAssert.eq("block", element.role(), "ordinary material is not a hatch");
        SimpleAssert.that(HologramDeclarationPurity.contextFree(element), "frozen pure leaf may share candidates");
        HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
        candidates.collect(element, new Object(), null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
        SimpleAssert.eq(2, candidates.stacks.size(), "production collector consumes fixed declarations directly");
        SimpleAssert.that(candidates.complete, "fixed candidate set is complete");
        boolean rejected = false;
        try {
            element.placeBlock(new Object(), null, 0, 0, 0, null);
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "a declaration never executes native world placement");
    }

    private static void codec() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x4d425331);
            output.writeInt(2);
            output.writeInt(3);
            output.writeBytes(" J ");
            output.writeInt(3);
            output.writeBytes("KKK");
            output.writeInt(1);
            output.writeInt(2);
            output.writeInt(0);
            output.writeInt(1);
            byte[] resource = bytes.toByteArray();
            String[][] decoded = HologramGTNLShapes.decode(resource, 1, 2, 3);
            SimpleAssert.eq(" J ", decoded[0][0], "spaces stay skip cells in the decoded shape");
            SimpleAssert.eq("KKK", decoded[0][1], "table references preserve resource letters");
            byte[] invalidIndex = resource.clone();
            invalidIndex[invalidIndex.length - 1] = 2;
            reject(invalidIndex, 1, 2, 3);
            byte[] invalidLength = resource.clone();
            invalidLength[11] = 4;
            reject(invalidLength, 1, 2, 3);
            reject(Arrays.copyOf(resource, resource.length - 1), 1, 2, 3);
            reject(Arrays.copyOf(resource, resource.length + 1), 1, 2, 3);
            reject(resource, 33, 2, 3);
            reject(new byte[8193], 1, 2, 3);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void reject(byte[] input, int height, int depth, int width) {
        try {
            HologramGTNLShapes.decode(input, height, depth, width);
            throw new AssertionError("unsafe resource was accepted");
        } catch (IOException expected) {}
    }

    private static void sphere() {
        try (InputStream input = HologramGTNLRegression.class.getResourceAsStream("/hologram/gtnl-rc21-sphere.mbs")) {
            if (input == null) throw new AssertionError("Missing pinned sphere fixture");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = input.read(buffer)) != -1;) bytes.write(buffer, 0, n);
            SimpleAssert.eq(632, bytes.size(), "pinned source resource byte count");
            String[][] shape = HologramGTNLShapes.decode(bytes.toByteArray(), 9, 9, 9);
            int j = 0, k = 0, skip = 0;
            for (String[] row : shape) for (String line : row) for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == 'J') j++;
                else if (line.charAt(i) == 'K') k++;
                else if (line.charAt(i) == ' ') skip++;
                else throw new AssertionError("Unexpected sphere symbol");
            }
            SimpleAssert.eq(52, j, "sphere J material count");
            SimpleAssert.eq(135, k, "sphere K material count");
            SimpleAssert.eq(542, skip, "empty resource cells remain skip, not air constraints");
            SimpleAssert.eq('J', shape[0][2].charAt(4), "fixed asymmetric sphere coordinate");
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}

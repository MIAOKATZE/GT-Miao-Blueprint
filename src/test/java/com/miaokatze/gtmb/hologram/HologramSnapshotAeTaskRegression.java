package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Complete old AuthoringQueries oracle; quantitative entry-point uses real test-only decode instrumentation. */
public final class HologramSnapshotAeTaskRegression {

    private HologramSnapshotAeTaskRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramSnapshotAeTaskRegression.class, args)) return;
        HologramDecodeProbe.install(HologramSnapshotAeTaskRegression.class);
        run();
        decodeCounts();
        System.out.println("HologramSnapshotAeTaskRegression P0a PASS (test-only actual decoder instrumentation)");
    }

    public static void run() {
        try {
            reportOracle();
            recognitionAndOwnership();
            errorPriority();
        } catch (Exception failure) {
            throw new AssertionError("snapshot AE same-cell decode/report contract", failure);
        }
    }

    private static void reportOracle() throws Exception {
        HologramSession session = session();
        Block ae = host("appeng.block.networking.BlockCableBus"), fmp = host("codechicken.multipart.BlockMultipart");
        add(session, ae, payload(), 2, 3, -1);
        add(session, fmp, multipart(true), -3, 2, 4);
        add(session, Blocks.stone, multipart(true), 4, -1, -2);
        add(session, fmp, multipart(false), 5, 0, 7);
        add(session, ae, new NBTTagCompound(), 6, 1, 3);
        add(session, ae, payload(), -7, 4, -5).excluded = true;
        session.virtualPreset.aeIssues.add(
            new HologramAePolicy.Issue("runtime_reset", "warning", "old/cache", "historical retained report", 6, 1, 3));
        session.virtualPreset.aeIssues.add(
            new HologramAePolicy.Issue(
                "missing_physical_item",
                "blocker",
                "inv/item0",
                "historical overwritten at its original position",
                2,
                3,
                -1));
        NBTTagList reference = HologramAuthoringQueries.aeCurrentIssues(session);
        SimpleAssert.that(reference.tagCount() >= 5, "fixture exercises a complete, ordered multi-host AE report");
        NBTTagCompound input = new NBTTagCompound();
        input.setLong("unknownTypedSentinel", Long.MAX_VALUE);
        session.ae.ack = new NBTTagCompound();
        session.ae.ack.setLong("sequence", 17);
        NBTTagCompound expected = (NBTTagCompound) input.copy();
        expected.setTag("aeIssues", reference);
        expected.setTag("aeEditAck", session.ae.ack.copy());
        expected.setBoolean("aePreviewIncomplete", true);
        for (int batch : new int[] { 1, 3, 100 }) {
            NBTTagCompound actual = (NBTTagCompound) input.copy();
            complete(new HologramSnapshotAeTask(session, actual), batch);
            SimpleAssert.eq(
                expected,
                actual,
                "full legacy oracle preserves every issue field/type, severity/path/order/coordinate and report field");
        }
    }

    private static void recognitionAndOwnership() throws Exception {
        NBTTagCompound input = multipart(true), saved = (NBTTagCompound) input.copy();
        Block fmp = host("codechicken.multipart.BlockMultipart");
        SimpleAssert
            .that(HologramAeBlueprint.supports(fmp, input), "real multipart host recognizes serialized AE part");
        SimpleAssert.that(
            !HologramAeBlueprint.supports(Blocks.stone, input),
            "the same payload cannot turn an ordinary host into AE");
        SimpleAssert.eq(saved, input, "supports is a read-only query");
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(fmp, 0, input);
        SimpleAssert.eq(saved, input, "Work owns a copy; analysis cannot mutate the retained first decoded tree");
        NBTTagCompound exported = analysis.payload();
        exported.setString("mutatedExport", "independent");
        SimpleAssert.that(
            !input.hasKey("mutatedExport") && !analysis.payload()
                .hasKey("mutatedExport"),
            "analysis/export trees remain independently mutable");
        SimpleAssert.eq(
            saved.getTagList("parts", 10)
                .getCompoundTagAt(1),
            analysis.payload()
                .getTagList("parts", 10)
                .getCompoundTagAt(1),
            "unknown non-AE multipart data stays exact");
    }

    private static void errorPriority() throws Exception {
        for (Block block : new Block[] { Blocks.stone, null, host("appeng.block.networking.BlockCableBus") }) {
            HologramCapture.Cell cell = cell(block, "%%%malformed%%%", 1, 2, 3);
            IOException old = rejected(() -> legacy(cell));
            IOException actual = rejected(
                () -> analyze(new HologramSnapshotAeTask(session(), new NBTTagCompound()), cell));
            SimpleAssert
                .eq(error(old), error(actual), "malformed data fails before host recognition at the old location");
        }
        HologramSession session = session();
        add(session, Blocks.stone, new NBTTagCompound(), 0, 0, 0).tileNbt = "%%%malformed%%%";
        HologramAePolicy.Issue prior = new HologramAePolicy.Issue("runtime_reset", "warning", "old", "old", 0, 0, 0);
        prior.path = "\u0000invalid";
        session.virtualPreset.aeIssues.add(prior);
        IOException first = rejected(() -> HologramAuthoringQueries.aeCurrentIssues(session));
        SimpleAssert.that(!error(first).contains("蓝图NBT"), "invalid historical issue precedes malformed cell decode");
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("aeSaveReview", new NBTTagCompound());
        complete(new HologramSnapshotAeTask(session, state), 1);
        SimpleAssert.that(
            state.getBoolean("aeReportBlocking") && state.getString("aeReportError")
                .equals("ae.report.rejected") && !state.hasKey("aeSaveReview") && !state.hasKey("aeIssues"),
            "owner task retains original rejection report");
    }

    private static void decodeCounts() throws Exception {
        Block ae = host("appeng.block.networking.BlockCableBus"), fmp = host("codechicken.multipart.BlockMultipart");
        count("ae", cell(ae, encoded(payload()), 0, 0, 0), new int[] { 2, 2, 2, 2 }, new int[] { 1, 1, 1, 1 });
        count(
            "multipart-ae",
            cell(fmp, encoded(multipart(true)), 0, 0, 0),
            new int[] { 2, 2, 2, 2 },
            new int[] { 1, 1, 1, 1 });
        count(
            "ordinary",
            cell(Blocks.stone, encoded(multipart(true)), 0, 0, 0),
            new int[] { 1, 1, 1, 1 },
            new int[] { 1, 1, 1, 1 });
        count(
            "multipart-other",
            cell(fmp, encoded(multipart(false)), 0, 0, 0),
            new int[] { 1, 1, 1, 1 },
            new int[] { 1, 1, 1, 1 });
        count(
            "empty-compound",
            cell(ae, encoded(new NBTTagCompound()), 0, 0, 0),
            new int[] { 2, 2, 2, 2 },
            new int[] { 1, 1, 1, 1 });
        for (String absent : new String[] { null, "" })
            count("absent", cell(ae, absent, 0, 0, 0), new int[] { 2, 0, 0, 0 }, new int[] { 1, 0, 0, 0 });
        count(
            "bad-base64",
            cell(Blocks.stone, "%%%malformed%%%", 0, 0, 0),
            new int[] { 1, 1, 0, 0 },
            new int[] { 1, 1, 0, 0 });
        count(
            "bad-gzip",
            cell(
                Blocks.stone,
                Base64.getEncoder()
                    .encodeToString(new byte[] { 1, 2, 3 }),
                0,
                0,
                0),
            new int[] { 1, 1, 1, 0 },
            new int[] { 1, 1, 1, 0 });
        NBTTagCompound invalid = payload();
        invalid.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
            .getCompoundTagAt(0)
            .setString("code", "not_recognized");
        count("bad-ae-schema", cell(ae, encoded(invalid), 0, 0, 0), new int[] { 2, 2, 2, 2 }, new int[] { 1, 1, 1, 1 });
    }

    private static void count(String label, HologramCapture.Cell cell, int[] expectedOld, int[] expectedNew)
        throws Exception {
        HologramSnapshotAeTask task = new HologramSnapshotAeTask(session(), new NBTTagCompound());
        String[] errors = new String[2];
        int[][] observed = new int[2][];
        for (int pass = 0; pass < 2; pass++) {
            HologramDecodeProbe.begin();
            try {
                if (pass == 0) legacy(cell);
                else analyze(task, cell);
            } catch (IOException expected) {
                errors[pass] = error(expected);
            } finally {
                observed[pass] = HologramDecodeProbe.end();
            }
        }
        SimpleAssert.that(
            Arrays.equals(expectedOld, observed[0]) && Arrays.equals(expectedNew, observed[1]),
            "actual decode/data/strict-entry/strict-success counts " + label + ": " + Arrays.deepToString(observed));
        SimpleAssert.eq(errors[0], errors[1], "decoder/schema error precedence " + label);
        System.out.println(
            "GTMB_P0A " + label + " old=" + Arrays.toString(observed[0]) + " new=" + Arrays.toString(observed[1]));
    }

    static void legacy(HologramCapture.Cell cell) throws IOException {
        if (HologramAePolicy.handles(cell.block, cell.tileNbt))
            HologramAeBlueprint.analyze(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
    }

    static void analyze(HologramSnapshotAeTask task, HologramCapture.Cell cell) throws Exception {
        Method method = HologramSnapshotAeTask.class.getDeclaredMethod("analyze", HologramCapture.Cell.class);
        method.setAccessible(true);
        try {
            method.invoke(task, cell);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw new AssertionError(failure.getCause());
        }
    }

    static void complete(HologramSnapshotAeTask task, int batch) {
        for (int step = 0; step < 1000; step++) if (task.step(batch, Long.MAX_VALUE)) return;
        throw new AssertionError("AE owner task did not complete");
    }

    static HologramSession session() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        EntityPlayerMP actor = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        actor.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        actor.inventory = new InventoryPlayer(actor);
        actor.capabilities = new PlayerCapabilities();
        HologramSession session = new HologramSession(actor, 10, 64, -3, false, 2);
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.deidentificationLevel = 1;
        session.localPlanPending = true;
        session.localPlanError = "isolated complete report";
        session.capture = new HologramCapture(null, null);
        session.blueprintActiveStructure = "assembly";
        return session;
    }

    static HologramCapture.Cell add(HologramSession session, Block block, NBTTagCompound payload, int x, int y, int z)
        throws IOException {
        HologramCapture.Cell cell = cell(block, encoded(payload), session.x + x, session.y + y, session.z + z);
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
        return cell;
    }

    static HologramCapture.Cell cell(Block block, String encoded, int x, int y, int z) {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, y, z);
        cell.block = block;
        cell.tileNbt = encoded;
        cell.structureId = "assembly";
        return cell;
    }

    static String encoded(NBTTagCompound payload) throws IOException {
        return Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(payload));
    }

    static Block host(String type) throws Exception {
        return (Block) unsafe().allocateInstance(Class.forName(type));
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    static NBTTagCompound payload() {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setBoolean("hasRedstone", true);
        payload.setLong("unknownTyped", Long.MAX_VALUE);
        NBTTagList markers = new NBTTagList();
        for (String code : new String[] { "missing_physical_item", "unsupported_part" }) {
            NBTTagCompound marker = new NBTTagCompound();
            marker.setString("code", code);
            marker.setString("path", "inv/item0");
            marker.setString("message", "retained loss fixture");
            markers.appendTag(marker);
        }
        payload.setTag(HologramAeBlueprint.UNRESOLVED, markers);
        return payload;
    }

    private static NBTTagCompound multipart(boolean ae) {
        NBTTagCompound payload = new NBTTagCompound(), part = payload();
        part.setString("id", ae ? "ae2_cablebus" : "other_mod:pipe");
        NBTTagList parts = new NBTTagList();
        parts.appendTag(part);
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setString("id", "other_mod:opaque");
        unknown.setLong("unknownLong", Long.MIN_VALUE);
        parts.appendTag(unknown);
        payload.setTag("parts", parts);
        return payload;
    }

    private interface Io {

        void run() throws Exception;
    }

    private static IOException rejected(Io action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return expected;
        }
        throw new AssertionError("malformed AE input accepted");
    }

    static String error(Throwable failure) {
        String result = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) result += cause.getClass()
            .getName() + ":"
            + cause.getMessage()
            + "\n";
        return result;
    }
}

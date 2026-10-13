package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Pure DTO/grouping regressions: no native container, player, world, library writer or GUI renderer. */
public final class HologramAeGuidanceRegression {

    private HologramAeGuidanceRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("AE guidance DTO regression passed; FML/GUI execution not covered");
    }

    public static void run() {
        reviewNavigation();
        NBTTagCompound dto = review(8192, false);
        HologramAeReviewModel model = new HologramAeReviewModel(dto, true);
        check(model.valid && model.canConfirm, "Complete warning-only report remains saveable");
        check(model.issues.size() == 8192 && model.groups.size() == 1, "All rows retained; same codes grouped");
        check(model.groups.get(0).count == 16384, "Grouped counts include each issue quantity");
        check(model.issues.get(8191).path.equals("inv/item8191"), "Tail warning remains accessible");
        check(
            model.materials.size() == 1 && model.materials.get(0).missing.equals("64"),
            "Short material row remains visible without becoming a blocker");
        dto.getTagList("issues", 10)
            .getCompoundTagAt(8191)
            .setString("path", "client mutation");
        check(model.issues.get(8191).path.equals("inv/item8191"), "Review data is detached");
        check(
            model.matches("session", 42) && !model.matches("foreign", 42) && !model.matches("session", 43),
            "Client confirmation presentation is exact-session/revision bound");
        NBTTagCompound action = model.action();
        check(
            action.getString("transactionId")
                .equals("nonce") && action.getLong("planRevision") == 42,
            "Confirmation carries exact server nonce and revision");
        action.setString("transactionId", "mutated");
        check(
            model.action()
                .getString("transactionId")
                .equals("nonce"),
            "Action copy cannot mutate model");
        HologramAeReviewModel.Gate gate = new HologramAeReviewModel.Gate();
        check(gate.first(model) && !gate.first(model), "One nonce opens once across repeated snapshots");
        dto = review(7, true);
        model = new HologramAeReviewModel(dto, true);
        check(
            model.blockerCount == 1 && !model.canConfirm,
            "Tail blocker disables confirmation even if server boolean claims true");
        check(
            model.issues.get(6)
                .guidance()
                .contains("明确清除"),
            "Tombstone clear explains explicit omission acknowledgement");
        dto.setInteger("issueCount", 6);
        check(!new HologramAeReviewModel(dto, true).valid, "Incomplete count report fails closed");
        dto = review(8193, false);
        check(
            !new HologramAeReviewModel(dto, true).valid,
            "Oversized report rejects whole display instead of hiding tail blockers");
        check(
            HologramAeReviewModel.plain("\u00a7cwarning\u0000", 128)
                .equals("warning"),
            "Untrusted fallback cannot inject formatting controls");
        NBTTagCompound stateTag = new NBTTagCompound();
        stateTag.setString("session", "session");
        stateTag.setLong("planRevision", 42);
        stateTag.setInteger("x", 100);
        stateTag.setInteger("y", 64);
        stateTag.setInteger("z", 200);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", 100 + i);
            cell.setInteger("y", 64);
            cell.setInteger("z", 200);
            cell.setInteger("dx", i);
            cell.setInteger("dy", 0);
            cell.setInteger("dz", 0);
            cell.setString("structureId", "ae-controller-structure");
            cells.appendTag(cell);
        }
        stateTag.setTag("cells", cells);
        NBTTagList issues = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound issue = issue(i, false);
            issue.setInteger("x", 100 + i);
            issue.setInteger("y", 64);
            issue.setInteger("z", 200);
            issue.setString("structureId", "ae-controller-structure");
            issues.appendTag(issue);
        }
        stateTag.setTag("aeIssues", issues);
        List<HologramAnnotationMarkers.Marker> markers = HologramAeWarningMarkers.collect(new HologramState(stateTag));
        check(
            markers.size() == 2 && markers.get(0).bounds == null,
            "Global full report plus one structural warning marker");
        check(
            markers.get(1).bounds[0] == 0 && markers.get(1).bounds[3] == 1,
            "Structure bounds derive current snapshot cells");
        issues.getCompoundTagAt(0)
            .setString("structureId", "");
        issues.getCompoundTagAt(1)
            .setString("structureId", "");
        issues.getCompoundTagAt(0)
            .setInteger("x", -999);
        issues.getCompoundTagAt(1)
            .setInteger("x", -998);
        markers = HologramAeWarningMarkers.collect(new HologramState(stateTag));
        check(
            markers.size() == 1,
            "Unknown/stale coordinates never guessed into a cell marker; full report remains reachable");
    }

    private static void reviewNavigation() {
        check(
            HologramAeReviewNavigation.needsSelection("A", "B"),
            "cross-structure issue requests structure selection first");
        check(
            HologramAeReviewNavigation.needsSelection("A", ""),
            "unstructured issue can return to whole blueprint before navigation");
        check(
            !HologramAeReviewNavigation.needsSelection("B", "B"),
            "current structure does not send a redundant selection packet");
        HologramAeReviewNavigation nav = navigation(false);
        check(
            nav.observe("session", 82, 33, "A", false, false) == HologramAeReviewNavigation.Result.WAITING,
            "cancel-review ACK cannot trigger the later structure navigation");
        check(
            nav.observe("session", 83, 34, "B", false, true) == HologramAeReviewNavigation.Result.READY,
            "exact structure ACK with a fresh revision releases navigation");
        check(
            nav.x == 10 && nav.y == 64 && nav.z == 20 && nav.structureId.equals("B") && !nav.replaceCell,
            "navigation retains only the server-issued issue coordinate and structure");
        check(
            nav.observe("session", 83, 34, "B", false, true) == HologramAeReviewNavigation.Result.COMPLETE,
            "repeated ACK never starts another preview request");
        nav = navigation(true);
        check(
            nav.replaceCell
                && nav.observe("session", 83, 34, "B", false, true) == HologramAeReviewNavigation.Result.READY,
            "replace-cell also waits for the owning structure before local target selection");
        nav = navigation(false);
        check(
            nav.observe("other-session", 0, 99, "B", false, true) == HologramAeReviewNavigation.Result.FAILED
                && !nav.failure()
                    .isEmpty(),
            "a new UUID cancels navigation with an explanation rather than guessing authority");
        nav = navigation(false);
        check(
            nav.observe("session", 83, 34, "A", false, true) == HologramAeReviewNavigation.Result.FAILED,
            "failed selection ACK does not open a container in another structure");
        nav = navigation(false);
        check(
            nav.observe("session", 84, 35, "B", false, true) == HologramAeReviewNavigation.Result.FAILED,
            "a newer user operation invalidates the exact pending selection request");
        nav = navigation(false);
        check(
            nav.observe("session", 83, 33, "B", false, true) == HologramAeReviewNavigation.Result.FAILED,
            "unchanged review revision cannot authorize the post-selection request");
        nav = navigation(false);
        check(
            nav.observe("session", 82, 34, "A", true, false) == HologramAeReviewNavigation.Result.FAILED,
            "starting import cancels navigation even before its selection ACK");
        nav = navigation(false);
        check(
            nav.observe("session", 83, 34, "B", false, false) == HologramAeReviewNavigation.Result.FAILED,
            "readonly/job/closed target ACK does not trigger a preview");
        nav = navigation(false);
        nav.cancel();
        check(
            nav.observe("session", 83, 34, "B", false, true) == HologramAeReviewNavigation.Result.COMPLETE,
            "user cancellation is permanent and does not retry on the eventual ACK");
    }

    private static HologramAeReviewNavigation navigation(boolean replace) {
        return new HologramAeReviewNavigation("session", 83, 33, "B", 10, 64, 20, replace);
    }

    private static NBTTagCompound review(int count, boolean blocker) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("session", "session");
        tag.setString("transactionId", "nonce");
        tag.setLong("planRevision", 42);
        tag.setBoolean("canConfirm", true);
        tag.setInteger("issueCount", count);
        tag.setInteger("warningCount", count - (blocker ? 1 : 0));
        tag.setInteger("blockerCount", blocker ? 1 : 0);
        NBTTagList issues = new NBTTagList();
        for (int i = 0; i < count; i++) issues.appendTag(issue(i, blocker && i == count - 1));
        tag.setTag("issues", issues);
        NBTTagList materials = new NBTTagList();
        NBTTagCompound row = new NBTTagCompound();
        row.setString("key", "pattern exact NBT");
        row.setString("type", "item");
        row.setString("name", "Encoded pattern");
        row.setString("required", "64");
        row.setString("available", "0");
        row.setString("missing", "64");
        materials.appendTag(row);
        tag.setTag("materials", materials);
        return tag;
    }

    private static NBTTagCompound issue(int index, boolean blocker) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("code", "ae.cell_contents_cleared");
        tag.setString("severity", blocker ? "blocker" : "warning");
        tag.setString("path", "inv/item" + index);
        tag.setString("message", "omitted contents");
        tag.setString("resolution", blocker ? "discardOmitted" : "none");
        tag.setLong("count", 2);
        tag.setInteger("x", index);
        tag.setInteger("y", 0);
        tag.setInteger("z", 0);
        return tag;
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}

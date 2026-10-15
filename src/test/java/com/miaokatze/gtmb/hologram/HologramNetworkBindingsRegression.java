package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Portable quantum group migration and the item-template accounting boundary, without a live network. */
public final class HologramNetworkBindingsRegression {

    private HologramNetworkBindingsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNetworkBindingsRegression.class, args)) return;
        run();
        System.out.println("Hologram network binding regressions passed");
    }

    public static void run() throws Exception {
        grouping();
        malformedGroup();
        physicalPatternsCost();
        bindingSurvivesDisabledPayload();
        completeNamedClassification();
        privateOriginsAndShareProjection();
        nativeSynchronizationContractAndTypedAttribution();
    }

    private static void nativeSynchronizationContractAndTypedAttribution() throws Exception {
        SimpleAssert.that(
            !HologramGtswnQuantumAccess.namedNodeType(SynchronizationFixture.class),
            "legacy nodes without named attribution do not acquire the newer controller-generation contract");
        SimpleAssert.that(
            HologramGtswnQuantumAccess.namedNodeType(NamedNodeFixture.class),
            "the native pair of String attribution getters enables the reviewed named-node format");
        try {
            HologramGtswnQuantumAccess.namedNodeType(PartialNamedNodeFixture.class);
            throw new AssertionError("a partially changed named-node API cannot fall back to the legacy format");
        } catch (HologramGtswnQuantumAccess.Failure expected) {
            SimpleAssert.that(true, "a partial native attribution contract is an explicit compatibility error");
        }
        java.lang.reflect.Method unchanged = HologramGtswnQuantumAccess.requireStatic(
            SynchronizationFixture.class,
            "unchanged",
            boolean.class,
            net.minecraft.entity.player.EntityPlayer.class,
            ItemStack.class);
        SynchronizationFixture.calls = 0;
        HologramGtswnQuantumAccess.synchronizeWith(unchanged, null, null);
        SimpleAssert.that(
            SynchronizationFixture.calls == 1,
            "native synchronization false means unchanged, not a security denial");
        java.lang.reflect.Method broken = HologramGtswnQuantumAccess.requireStatic(
            SynchronizationFixture.class,
            "broken",
            boolean.class,
            net.minecraft.entity.player.EntityPlayer.class,
            ItemStack.class);
        try {
            HologramGtswnQuantumAccess.synchronizeWith(broken, null, null);
            throw new AssertionError("broken native migration synchronization must block");
        } catch (HologramGtswnQuantumAccess.Failure expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("不能确定目标锚点"),
                "native invocation failure is an explicit compatibility blocker");
        }
        for (String method : new String[] { "wrongType", "instance" }) try {
            HologramGtswnQuantumAccess.requireStatic(SynchronizationFixture.class, method, boolean.class);
            throw new AssertionError("changed authority contract must reject " + method);
        } catch (NoSuchMethodException expected) {
            SimpleAssert.that(true, "native method return type and static contract are checked");
        }
        NBTTagCompound data = new NBTTagCompound();
        data.setString("placerUUID", "cdf90139-63c0-4e89-905e-20833e0683a2");
        SimpleAssert.that(
            HologramNetworkBindings.validOwnerField(data, "placerUUID", true),
            "canonical native placer UUID is reviewed");
        data.setString("placerUUID", "not-a-uuid");
        SimpleAssert.that(
            !HologramNetworkBindings.validOwnerField(data, "placerUUID", true),
            "malformed UUID is never silently cleaned as attribution");
        data.setTag("placerUUID", new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        SimpleAssert.that(
            !HologramNetworkBindings.validOwnerField(data, "placerUUID", true),
            "an attribution key cannot hide a physical item");
        ItemStack terminalPayload = new ItemStack(Items.stick);
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setTag("QT_EncryptionKey", new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        terminalPayload.setTagCompound(invalid);
        HologramNetworkBindings.validateTerminalFields(terminalPayload);
        SimpleAssert.that(
            terminalPayload.getTagCompound()
                .equals(invalid),
            "terminal credentials are neither copied into the blueprint nor interpreted as GTMB authorization");
        invalid.setInteger("QT_LastAppliedMigration", 0);
        try {
            HologramNetworkBindings.validateTerminalFields(terminalPayload);
            throw new AssertionError("native migration cursor retains its exact long type");
        } catch (IOException expected) {
            SimpleAssert.that(true, "invalid migration data remains blocked independently of security credentials");
        }
        NBTTagCompound origin = new NBTTagCompound(), payload = new NBTTagCompound();
        origin.setString("world", "cdf90139-63c0-4e89-905e-20833e0683a2");
        origin.setInteger("anchorDim", 0);
        origin.setInteger("anchorX", 20);
        origin.setInteger("anchorY", 64);
        origin.setInteger("anchorZ", 40);
        origin.setInteger("version", 2);
        origin.setLong("migrationCursor", 0L);
        origin.setString("controllerIdentity", "39efead7-a1d5-4ef2-af4b-94c71e338794");
        payload.setTag(HologramNetworkBindings.ORIGINAL, origin);
        HologramNetworkBindings.validateOriginal(payload);
        origin.setInteger("migrationCursor", 0);
        try {
            HologramNetworkBindings.validateOriginal(payload);
            throw new AssertionError("private migration cursor must preserve its native long type");
        } catch (IOException expected) {
            SimpleAssert.that(true, "a wrong-type private cursor cannot silently bind a coordinate replacement");
        }
        origin.setLong("migrationCursor", 0L);
        origin.setString("controllerIdentity", "not-a-controller-uuid");
        try {
            HologramNetworkBindings.validateOriginal(payload);
            throw new AssertionError("private controller generation must be a canonical UUID");
        } catch (IOException expected) {
            SimpleAssert.that(true, "invalid private controller generations remain blocked");
        }
    }

    public static final class SynchronizationFixture {

        static int calls;

        public static boolean unchanged(net.minecraft.entity.player.EntityPlayer actor, ItemStack terminal) {
            calls++;
            return false;
        }

        public static boolean broken(net.minecraft.entity.player.EntityPlayer actor, ItemStack terminal) {
            throw new IllegalStateException("native failure");
        }

        public static String wrongType() {
            return "false";
        }

        public boolean instance() {
            return false;
        }
    }

    public static class PartialNamedNodeFixture {

        public String getPlacerUUID() {
            return "";
        }
    }

    public static final class NamedNodeFixture extends PartialNamedNodeFixture {

        public String getPlacerName() {
            return "";
        }
    }

    private static void completeNamedClassification() throws Exception {
        HologramPresets.Preset raw = new HologramPresets.Preset();
        raw.cells.add(cell(0, 100));
        raw.cells.add(cell(1, 200));
        HologramPresets.Cell ordinary = HologramPresets.cell(new ItemStack(Blocks.stone), 2, 0, 0, -1, false);
        ordinary.block = "minecraft:stone";
        raw.cells.add(ordinary);
        String before = new com.google.gson.Gson().toJson(raw);
        NBTTagList defaults = HologramQuantumGroups.groups(raw);
        SimpleAssert.that(
            defaults.tagCount() == 1 && defaults.getCompoundTagAt(0)
                .getTagList("cells", 10)
                .tagCount() == 2 && !HologramQuantumGroups.confirmed(raw),
            "all quantum nodes start in one unconfirmed default class regardless of old source networks");
        HologramBlueprintProcessing.Policy optional = HologramBlueprintProcessing.defaults("share", 1);
        optional.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset automatic = HologramBlueprintProcessing.process(raw, optional, 0, 0, 64, 0);
        SimpleAssert.that(
            HologramQuantumGroups.groups(automatic)
                .tagCount() == 1
                && HologramQuantumGroups.groups(automatic)
                    .getCompoundTagAt(0)
                    .getTagList("cells", 10)
                    .tagCount() == 2,
            "an unconfigured blueprint can finish author processing with one automatic default group");
        SimpleAssert
            .that(before.equals(new com.google.gson.Gson().toJson(raw)), "automatic defaulting leaves source exact");
        HologramPresets.Preset classified = HologramQuantumGroups.configure(raw, defaults);
        SimpleAssert
            .that(HologramQuantumGroups.confirmed(classified), "complete default class is explicitly confirmable");
        HologramPresets.Preset copied = HologramPresets.copy(classified);
        SimpleAssert
            .that(HologramQuantumGroups.confirmed(copied), "classification receipt survives canonical blueprint copy");
        HologramPresets.Preset reloaded = new com.google.gson.Gson()
            .fromJson(new com.google.gson.Gson().toJson(classified), HologramPresets.Preset.class);
        SimpleAssert.that(HologramQuantumGroups.confirmed(reloaded), "classification survives blueprint serialization");
        copied.cells.get(1).x = 8;
        SimpleAssert
            .that(!HologramQuantumGroups.confirmed(copied), "moving a classified node invalidates its exact cell map");
        copied = HologramPresets.copy(classified);
        copied.cells.get(1).excluded = true;
        SimpleAssert.that(
            !HologramQuantumGroups.confirmed(copied),
            "excluding a node requires confirmation of the remaining map");
        copied = HologramPresets.copy(classified);
        copied.cells.get(0).z = -copied.cells.get(0).x;
        copied.cells.get(1).z = -copied.cells.get(1).x;
        copied.cells.get(1).x = 0;
        SimpleAssert.that(
            !HologramQuantumGroups.confirmed(copied),
            "rotating relative node positions invalidates stale classification coordinates");
        NBTTagList duplicate = (NBTTagList) defaults.copy();
        duplicate.getCompoundTagAt(0)
            .getTagList("cells", 10)
            .appendTag(
                duplicate.getCompoundTagAt(0)
                    .getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .copy());
        rejectGroups(raw, duplicate, "duplicate node");
        NBTTagList missing = (NBTTagList) defaults.copy();
        missing.getCompoundTagAt(0)
            .getTagList("cells", 10)
            .removeTag(0);
        rejectGroups(raw, missing, "unassigned node");
        NBTTagList extra = (NBTTagList) defaults.copy();
        NBTTagCompound nonNode = new NBTTagCompound();
        nonNode.setInteger("x", 2);
        nonNode.setInteger("y", 0);
        nonNode.setInteger("z", 0);
        extra.getCompoundTagAt(0)
            .getTagList("cells", 10)
            .appendTag(nonNode);
        rejectGroups(raw, extra, "ordinary block masquerading as a node");
        NBTTagList wrongPosition = (NBTTagList) defaults.copy();
        wrongPosition.getCompoundTagAt(0)
            .getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setShort("x", (short) 0);
        rejectGroups(raw, wrongPosition, "wrong coordinate type");
        for (String name : new String[] { "", "\u00a7csecret", "bad\nname" }) {
            NBTTagList invalid = (NBTTagList) defaults.copy();
            invalid.getCompoundTagAt(0)
                .setString("name", name);
            rejectGroups(raw, invalid, "invalid group name");
        }
        NBTTagList invalidId = (NBTTagList) defaults.copy();
        invalidId.getCompoundTagAt(0)
            .setString("id", "q0");
        rejectGroups(raw, invalidId, "invalid group identity");
        NBTTagList wrongCells = (NBTTagList) defaults.copy();
        NBTTagList strings = new NBTTagList();
        strings.appendTag(new net.minecraft.nbt.NBTTagString("0,0,0"));
        wrongCells.getCompoundTagAt(0)
            .setTag("cells", strings);
        rejectGroups(raw, wrongCells, "wrong member list type");
        SimpleAssert.eq(
            before,
            new com.google.gson.Gson().toJson(raw),
            "all accepted and rejected classifications leave source exact");
    }

    private static void privateOriginsAndShareProjection() throws Exception {
        HologramPresets.Preset raw = new HologramPresets.Preset();
        for (int i = 0; i < 2; i++) {
            HologramPresets.Cell cell = cell(i, 100 + i * 100);
            NBTTagCompound tag = tag(cell);
            NBTTagCompound origin = (NBTTagCompound) tag.getCompoundTag(HologramNetworkBindings.CAPTURE_ANCHOR)
                .copy();
            origin.setString("world", "cdf90139-63c0-4e89-905e-20833e0683a2");
            tag.setTag(HologramNetworkBindings.ORIGINAL, origin);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            raw.cells.add(cell);
        }
        raw = HologramQuantumGroups.configure(raw, HologramQuantumGroups.groups(raw));
        HologramBlueprintProcessing.Policy self = HologramBlueprintProcessing.defaults("self", 1);
        self.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset privateCopy = HologramBlueprintProcessing.process(raw, self, 0, 0, 64, 0);
        privateCopy = HologramAePolicy.sanitize(privateCopy).preset;
        for (int i = 0; i < 2; i++) {
            NBTTagCompound tag = tag(privateCopy.cells.get(i));
            SimpleAssert.that(
                "q1".equals(tag.getString(HologramNetworkBindings.GROUP))
                    && "preserve".equals(tag.getString(HologramNetworkBindings.MODE))
                    && tag.getCompoundTag(HologramNetworkBindings.ORIGINAL)
                        .getInteger("anchorX") == 100 + i * 100,
                "one author class preserves every distinct original private anchor through repeated sanitization");
        }
        HologramBlueprintProcessing.Policy share = HologramBlueprintProcessing.defaults("share", 1);
        share.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset portable = HologramBlueprintProcessing.process(raw, share, 0, 0, 64, 0);
        for (HologramPresets.Cell cell : portable.cells) {
            NBTTagCompound tag = tag(cell);
            SimpleAssert.that(
                !tag.hasKey(HologramNetworkBindings.ORIGINAL) && !tag.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
                    && "rebind".equals(tag.getString(HologramNetworkBindings.MODE))
                    && "q1".equals(tag.getString(HologramNetworkBindings.GROUP)),
                "sharing removes save-local provenance and keeps only the reviewed anonymous recipient slot");
        }
        NBTTagCompound malformed = tag(raw.cells.get(0));
        malformed.getCompoundTag(HologramNetworkBindings.ORIGINAL)
            .setString("world", "bad-world-uuid");
        try {
            HologramNetworkBindings.validateOriginal(malformed);
            throw new AssertionError("invalid world identity must reject");
        } catch (IOException expected) {
            SimpleAssert.that(true, "malformed source UUID cannot be repaired or substituted");
        }
    }

    private static void rejectGroups(HologramPresets.Preset source, NBTTagList groups, String message)
        throws Exception {
        String before = new com.google.gson.Gson().toJson(source);
        try {
            HologramQuantumGroups.configure(source, groups);
            throw new AssertionError("classification accepted " + message);
        } catch (IOException expected) {
            SimpleAssert.eq(before, new com.google.gson.Gson().toJson(source), "atomic rejection: " + message);
        }
    }

    private static void grouping() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.cells.add(cell(0, 100));
        source.cells.add(cell(1, 100));
        source.cells.add(cell(2, 200));
        String[] originals = source.cells.stream()
            .map(cell -> cell.tileNbt)
            .toArray(String[]::new);
        HologramAePolicy.Draft sanitized = HologramAePolicy.sanitize(source);
        String first = tag(sanitized.preset.cells.get(0)).getString(HologramNetworkBindings.GROUP);
        SimpleAssert.that(
            first.equals(tag(sanitized.preset.cells.get(1)).getString(HologramNetworkBindings.GROUP)),
            "same captured network anchor remains one local group");
        SimpleAssert.that(
            !first.equals(tag(sanitized.preset.cells.get(2)).getString(HologramNetworkBindings.GROUP)),
            "different source networks remain independent groups");
        for (int i = 0; i < source.cells.size(); i++) {
            SimpleAssert.that(originals[i].equals(source.cells.get(i).tileNbt), "source draft is unchanged");
            NBTTagCompound clean = tag(sanitized.preset.cells.get(i));
            SimpleAssert.that(
                !clean.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR) && !clean.hasKey("GTSWN_IncorporationIdentity"),
                "no source anchor or incorporation UUID on disk");
            SimpleAssert.that("".equals(clean.getString("OwnerUUID")), "source player identity is removed");
            SimpleAssert.that("join".equals(clean.getString(HologramNetworkBindings.ROLE)), "join role is retained");
            SimpleAssert.that(
                clean.getCompoundTag("filter")
                    .getString("mode")
                    .equals("whitelist"),
                "independent ghost filter configuration survives deidentification");
            SimpleAssert.eq(
                5,
                HologramBlueprintResources.plan(clean).items.get(0).stackSize,
                "ordinary physical inventory still requires exact payment");
        }
        HologramAePolicy.Draft again = HologramAePolicy.sanitize(sanitized.preset);
        for (int i = 0; i < again.preset.cells.size(); i++) SimpleAssert.that(
            sanitized.preset.cells.get(i).tileNbt.equals(again.preset.cells.get(i).tileNbt),
            "portable network sanitization is idempotent");
    }

    private static void malformedGroup() throws Exception {
        HologramPresets.Preset malformed = new HologramPresets.Preset();
        HologramPresets.Cell cell = cell(0, 100);
        NBTTagCompound payload = tag(cell);
        payload.removeTag(HologramNetworkBindings.CAPTURE_ANCHOR);
        payload.setString(HologramNetworkBindings.GROUP, "q0");
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        malformed.cells.add(cell);
        try {
            HologramAePolicy.sanitize(malformed);
            throw new AssertionError("invalid local group must reject before placement");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("group"),
                "malformed group has an explicit report");
        }
        payload.removeTag(HologramNetworkBindings.GROUP);
        payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, new NBTTagCompound());
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        try {
            HologramAePolicy.sanitize(malformed);
            throw new AssertionError("incomplete anchor cannot silently become a selectable network");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("锚点"),
                "partial anchor is not guessed");
        }
    }

    private static void bindingSurvivesDisabledPayload() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.cells.add(cell(0, 100));
        HologramPresets.Preset portable = HologramAePolicy.sanitize(source).preset;
        HologramNetworkBindings.editHint(portable, "q1", "Protected production network");
        NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
            portable.cells.get(0).tileNbt,
            java.util.UUID.randomUUID(),
            "placer",
            20,
            64,
            30,
            false,
            false,
            null);
        SimpleAssert.that(
            selected != null && "q1".equals(selected.getString(HologramNetworkBindings.GROUP))
                && "join".equals(selected.getString(HologramNetworkBindings.ROLE))
                && "Protected production network".equals(selected.getString(HologramNetworkBindings.HINT)),
            "turning off optional NBT and covers never removes the mandatory binding descriptor");
        SimpleAssert.that(
            !selected.hasKey("Inventory") && !selected.hasKey("filter"),
            "mandatory network binding does not restore the omitted physical inventory or configuration");
    }

    private static void physicalPatternsCost() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagList patterns = new NBTTagList();
        patterns.appendTag(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        payload.setTag("patterns", patterns);
        SimpleAssert.eq(
            7,
            HologramBlueprintResources.plan(payload).items.get(0).stackSize,
            "calling ordinary inventory patterns never authorizes free copying");
    }

    private static HologramPresets.Cell cell(int x, int anchorX) throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        NBTTagCompound payload = new NBTTagCompound(), anchor = new NBTTagCompound();
        anchor.setInteger("anchorDim", 73);
        anchor.setInteger("anchorX", anchorX);
        anchor.setInteger("anchorY", 64);
        anchor.setInteger("anchorZ", 321);
        payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, anchor);
        payload.setString(HologramNetworkBindings.ROLE, "join");
        payload.setString("GTSWN_IncorporationIdentity", "private-source-identity");
        payload.setString("OwnerUUID", "private-player-identity");
        NBTTagCompound filter = new NBTTagCompound();
        filter.setString("mode", "whitelist");
        payload.setTag("filter", filter);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(Items.diamond, 5).writeToNBT(new NBTTagCompound()));
        payload.setTag("Inventory", inventory);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        return cell;
    }

    private static NBTTagCompound tag(HologramPresets.Cell cell) throws Exception {
        return HologramBlueprintNbt.decode(cell.tileNbt);
    }
}

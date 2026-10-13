package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import appeng.api.AEApi;
import appeng.api.definitions.IItemDefinition;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.helpers.IInterfaceHost;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseInvTile;

/** Real registered AE items/parts, called after FML initialization. Never joins/ticks/extracts a native object. */
public final class HologramAeBlueprintCoreRegression {

    private static final String PREFIX = "[GTMB-AE-CORE] ";
    private static int checks;

    private HologramAeBlueprintCoreRegression() {}

    /** Root smoke fixture supplies a genuine server actor; these fixtures do not create/edit a live world. */
    public static void run(EntityPlayerMP actor) throws Exception {
        if (actor == null) throw new IllegalArgumentException("Native actor fixture is required");
        checks = 0;
        Block drive = AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get();
        emptyNativeShellCapability(drive);
        cableBusTargetHost();
        unknownCell(drive);
        lostPhysicalMarker(drive);
        malformedPhysicalMarkers(drive);
        importedCellCard(drive);
        discardedField(drive);
        interfaceStorageAliases();
        previewCacheSchema();
        deletedPartMarkers(false);
        deletedPartMarkers(true);
        for (IItemDefinition definition : Arrays.asList(
            AEApi.instance()
                .definitions()
                .parts()
                .toggleBus(),
            AEApi.instance()
                .definitions()
                .parts()
                .p2PTunnelME(),
            AEApi.instance()
                .definitions()
                .parts()
                .quartzFiber()))
            secondaryProxy(definition, actor);
        patternRotation();
        physicalSlotDto();
        System.out.println(PREFIX + "PASS " + checks + " assertions; actual native registration/proxy/transport APIs");
    }

    private static void unknownCell(Block drive) throws Exception {
        ItemStack unknown = AEApi.instance()
            .definitions()
            .items()
            .spatialCell2()
            .maybeStack(1)
            .get();
        NBTTagCompound external = new NBTTagCompound();
        external.setInteger("StorageDim", 999991);
        external.setString("diskuuid", "original-external-pointer");
        unknown.setTagCompound(external);
        NBTTagCompound raw = cellPayload(unknown), original = (NBTTagCompound) raw.copy();
        HologramAeBlueprint.Analysis first = HologramAeBlueprint.analyze(drive, 0, raw);
        check(first.blocking && issue(first, "unsupported_cell", "inv/item0"), "unknown cell blocks installation");
        ItemStack sample = slot(first, "inv/item0").stack();
        check(sample.isItemEqual(unknown) && !sample.hasTagCompound(), "unknown cell retains blank type only");
        check(raw.equals(original), "source cell/external pointer snapshot is unchanged");
        HologramAeBlueprint.Analysis second = HologramAeBlueprint.analyze(drive, 0, first.payload());
        check(
            second.blocking && issue(second, "unsupported_cell", "inv/item0"),
            "bare unreviewed cell stays blocked after reanalysis");
        check(
            HologramAeBlueprint.updateSlot(drive, 0, second.payload(), "inv/item1", null).blocking,
            "unrelated empty slot edit cannot retire unknown cell blocker");
        for (HologramAeBlueprint.Slot candidate : second.slots)
            check(!candidate.issueCode.equals("unsupported_cell"), "unknown cell has no discard-only bypass");
        expectDenied(
            () -> HologramAeBlueprint.resolveDiscardedIssue(second.payload(), "unsupported_cell", "inv/item0"),
            "unknown cell cannot be approved with a tombstone");
        ItemStack reviewed = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        check(
            !HologramAeBlueprint.updateSlot(drive, 0, second.payload(), "inv/item0", reviewed).blocking,
            "explicit replacement with reviewed empty cell resolves that slot");
        check(
            !HologramAeBlueprint.updateSlot(drive, 0, second.payload(), "inv/item0", null).blocking,
            "explicit clear resolves unknown cell");
    }

    private static void lostPhysicalMarker(Block drive) throws Exception {
        NBTTagCompound bad = new NBTTagCompound();
        new ItemStack(Items.diamond).writeToNBT(bad);
        bad.setString(HologramBlueprintNbt.ITEM_REGISTRY, "missing:physical_item_for_fixture");
        NBTTagCompound raw = root();
        NBTTagCompound inv = new NBTTagCompound();
        inv.setTag("item0", bad);
        raw.setTag("inv", inv);
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        HologramAeBlueprint.Analysis first = HologramAeBlueprint.analyze(drive, 0, raw);
        check(
            issue(first, "missing_physical_item", "inv/item0") && first.blocking,
            "missing physical record is a blocker");
        check(
            !first.payload()
                .getCompoundTag("inv")
                .hasKey("item0"),
            "unrestorable physical record is not retained");
        check(
            HologramAeBlueprint.analyze(drive, 0, first.payload()).blocking,
            "removed invalid physical record cannot disappear on second analyze");
        check(raw.equals(source), "missing-item source record is unchanged");
        HologramAeBlueprint.Analysis unrelated = HologramAeBlueprint
            .updateSlot(drive, 0, first.payload(), "inv/item1", null);
        check(unrelated.blocking, "unrelated edit does not acknowledge missing physical inventory");
        HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
            .updateSlot(drive, 0, first.payload(), "inv/item0", null);
        check(!cleared.blocking, "explicit invalid-slot CLEAR acknowledges its omitted marker");
        check(
            HologramAeBlueprint.resolvedIssuePaths("inv/item0", first, cleared)
                .contains("inv/item0"),
            "server receives exact resolved issue path");
    }

    private static void malformedPhysicalMarkers(Block drive) throws Exception {
        NBTTagCompound raw = root(), inv = new NBTTagCompound();
        inv.setString("item0", "invalid-slot-record");
        raw.setTag("inv", inv);
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        HologramAeBlueprint.Analysis badSlot = HologramAeBlueprint.analyze(drive, 0, raw);
        check(
            badSlot.blocking && issue(badSlot, "missing_physical_item", "inv/item0"),
            "malformed physical slot remains an editable blocked draft");
        check(
            HologramAeBlueprint.analyze(drive, 0, badSlot.payload()).blocking,
            "malformed physical slot loss persists through reanalysis");
        check(
            !HologramAeBlueprint.updateSlot(drive, 0, badSlot.payload(), "inv/item0", null).blocking,
            "malformed physical slot accepts explicit CLEAR");
        check(raw.equals(source), "malformed physical slot source is unchanged");
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        NBTTagCompound cellTag = new NBTTagCompound(), cards = new NBTTagCompound(), diamond = new NBTTagCompound();
        new ItemStack(Items.diamond).writeToNBT(diamond);
        cards.setTag("#0", diamond);
        cellTag.setTag("upgrades", cards);
        cell.setTagCompound(cellTag);
        HologramAeBlueprint.Analysis badCard = HologramAeBlueprint.analyze(drive, 0, cellPayload(cell));
        check(
            badCard.blocking && issue(badCard, "cell_upgrade_invalid", "inv/item0/tag/upgrades/#0"),
            "non-upgrade physical cell card produces a blocker instead of refusing the draft");
        check(
            HologramAeBlueprint.analyze(drive, 0, badCard.payload()).blocking,
            "invalid retained cell card remains blocked after reanalysis");
        check(
            !HologramAeBlueprint.updateSlot(drive, 0, badCard.payload(), "inv/item0", null).blocking,
            "clearing the cell explicitly resolves its invalid embedded physical card");
    }

    private static void importedCellCard(Block drive) throws Exception {
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        ItemStack fuzzy = AEApi.instance()
            .definitions()
            .materials()
            .cardFuzzy()
            .maybeStack(1)
            .get();
        NBTTagCompound cellTag = new NBTTagCompound(), upgrades = new NBTTagCompound(), card = new NBTTagCompound();
        fuzzy.writeToNBT(card);
        card.setString(
            HologramBlueprintNbt.ITEM_REGISTRY,
            String.valueOf(Item.itemRegistry.getNameForObject(fuzzy.getItem())));
        card.setShort("id", (short) Item.getIdFromItem(Items.diamond)); // Simulate an imported registry-id mismatch.
        upgrades.setTag("#0", card);
        cellTag.setTag("upgrades", upgrades);
        cell.setTagCompound(cellTag);
        NBTTagCompound source = cellPayload(cell), unchanged = (NBTTagCompound) source.copy();
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(drive, 0, source);
        int paid = 0;
        for (ItemStack material : analysis.materials)
            if (material.isItemEqual(fuzzy) && ItemStack.areItemStackTagsEqual(material, fuzzy))
                paid += material.stackSize;
        check(
            !analysis.blocking && paid == 1,
            "imported embedded card registry identity is resolved before native inventory loading");
        check(source.equals(unchanged), "imported card numeric-id source remains unchanged");
        upgrades.getCompoundTag("#0")
            .setString(HologramBlueprintNbt.ITEM_REGISTRY, "missing:embedded_cell_card");
        cell.setTagCompound(cellTag);
        HologramAeBlueprint.Analysis missing = HologramAeBlueprint.analyze(drive, 0, cellPayload(cell));
        check(
            missing.blocking && issue(missing, "missing_physical_item", "inv/item0/tag/upgrades/#0"),
            "missing imported embedded card is omitted with an exact persistent physical blocker");
        check(
            HologramAeBlueprint.analyze(drive, 0, missing.payload()).blocking,
            "missing embedded card remains blocked after sanitization");
        check(
            !HologramAeBlueprint.updateSlot(drive, 0, missing.payload(), "inv/item0/tag/upgrades/#0", null).blocking,
            "exact embedded-card CLEAR resolves its omitted physical marker");
    }

    private static void discardedField(Block drive) throws Exception {
        NBTTagCompound raw = root(), physical = new NBTTagCompound();
        new ItemStack(Items.diamond, 32).writeToNBT(physical);
        raw.setTag("unreviewedHiddenBuffer", physical);
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        HologramAeBlueprint.Analysis first = HologramAeBlueprint.analyze(drive, 0, raw);
        check(
            first.blocking && !first.payload()
                .hasKey("unreviewedHiddenBuffer"),
            "unreviewed physical field is removed with blocker");
        HologramAeBlueprint.Analysis second = HologramAeBlueprint.analyze(drive, 0, first.payload());
        check(
            second.blocking && issue(second, "unreviewed_tile_field", "unreviewedHiddenBuffer"),
            "loss blocker persists with no unsafe shadow");
        HologramAeBlueprint.Slot review = null;
        for (HologramAeBlueprint.Slot candidate : second.slots) if (candidate.issueCode.equals("unreviewed_tile_field")
            && candidate.issuePath.equals("unreviewedHiddenBuffer")) review = candidate;
        check(
            review != null && !review.enabled && review.kind.equals("readonly"),
            "source-loss tombstone is server-issued readonly");
        final HologramAeBlueprint.Slot selected = review;
        expectDenied(
            () -> HologramAeBlueprint.updateSlot(drive, 0, second.payload(), selected.id, new ItemStack(Items.diamond)),
            "review tombstone cannot receive an item");
        HologramAeBlueprint.Analysis resolved = HologramAeBlueprint
            .updateSlot(drive, 0, second.payload(), selected.id, null);
        check(
            !resolved.blocking && !resolved.payload()
                .hasKey("unreviewedHiddenBuffer"),
            "explicit tombstone CLEAR acknowledges loss without restoring it");
        check(
            HologramAeBlueprint.resolvedIssuePaths(selected.id, second, resolved)
                .contains("unreviewedHiddenBuffer"),
            "tombstone maps to exact original issue path");
        check(raw.equals(source), "tombstone never changes the source physical buffer");
    }

    private static void interfaceStorageAliases() throws Exception {
        Block block = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        HologramResourceWorld sourceWorld = HologramResourceWorld.create(block, 0);
        TileEntity sourceTile = block.createTileEntity(sourceWorld, 0);
        sourceTile.setWorldObj(sourceWorld);
        sourceWorld.tile = sourceTile;
        IInventory inherited = ((AEBaseInvTile) sourceTile).getInternalInventory();
        IInventory canonical = ((IInterfaceHost) sourceTile).getInventoryByName("storage");
        check(
            inherited == canonical,
            "official interface inherited and duality storage access the same native inventory");
        ItemStack stock = new ItemStack(Items.diamond, 7);
        inherited.setInventorySlotContents(0, stock.copy());
        NBTTagCompound raw = HologramNativeCapture.capture(sourceTile), source = (NBTTagCompound) raw.copy();
        check(
            raw.getCompoundTag("inv")
                .getCompoundTag("item0")
                .equals(
                    raw.getCompoundTag("storage")
                        .getCompoundTag("#0")),
            "official nonempty interface writers serialize the same physical stock under both native aliases");
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, 0, raw);
        check(
            !analysis.blocking && analysis.materials.size() == 1
                && analysis.materials.get(0)
                    .isItemEqual(stock)
                && analysis.materials.get(0).stackSize == 7,
            "equivalent native interface aliases charge physical storage exactly once");
        int physicalStorageSlots = 0;
        for (HologramAeBlueprint.Slot candidate : analysis.slots)
            if (candidate.id.startsWith("storage/")) physicalStorageSlots++;
        check(
            physicalStorageSlots == 9 && analysis.slots.stream()
                .noneMatch(candidate -> candidate.id.startsWith("inv/")),
            "interface container describes one nine-slot storage inventory without duplicate alias slots");
        TileEntity restored = HologramAeBlueprint
            .createNative(block, 0, analysis.payload(), HologramResourceWorld.create(block, 0));
        ItemStack actual = ((AEBaseInvTile) restored).getInternalInventory()
            .getStackInSlot(0);
        check(
            actual != null && actual.stackSize == 7 && ItemStack.areItemStacksEqual(actual, stock),
            "native interface read-event order preserves the exact paid storage stock");
        HologramBlueprintResources.Plan paid = HologramAeBlueprint
            .resourcePlan(block, 0, analysis.payload(), HologramAeBlueprint.blockSample(block, 0));
        check(
            paid.items.size() == 1 && paid.items.get(0).stackSize == 7
                && paid.items.get(0)
                    .isItemEqual(stock),
            "real reservation projection neither double-charges aliases nor repeats the main interface shell");
        NBTTagCompound legacy = (NBTTagCompound) raw.copy();
        legacy.removeTag("storage");
        HologramAeBlueprint.Analysis imported = HologramAeBlueprint.analyze(block, 0, legacy);
        check(
            !imported.blocking && slot(imported, "storage/#0").stack().stackSize == 7,
            "legacy inherited-only interface storage is retained and paid through the canonical slots");
        NBTTagCompound selected = (NBTTagCompound) raw.copy();
        selected.removeTag("inv");
        HologramAeBlueprint.Analysis onlyCanonical = HologramAeBlueprint.analyze(block, 0, selected);
        TileEntity canonicalRestored = HologramAeBlueprint
            .createNative(block, 0, onlyCanonical.payload(), HologramResourceWorld.create(block, 0));
        check(
            ((AEBaseInvTile) canonicalRestored).getInternalInventory()
                .getStackInSlot(0).stackSize == 7,
            "canonical-only selected storage receives an equivalent native alias instead of being cleared by inherited read");
        NBTTagCompound conflict = (NBTTagCompound) raw.copy(), alternate = new NBTTagCompound();
        new ItemStack(Items.gold_ingot, 9).writeToNBT(alternate);
        conflict.getCompoundTag("inv")
            .setTag("item0", alternate);
        NBTTagCompound conflictSource = (NBTTagCompound) conflict.copy();
        HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(block, 0, conflict);
        check(
            blocked.blocking && issue(blocked, "interface_storage_alias_conflict", "storage/#0")
                && HologramAeBlueprint.analyze(block, 0, blocked.payload()).blocking,
            "different physical interface aliases remain blocked even after their unsafe alternate is sanitized");
        check(
            blocked.payload()
                .getCompoundTag("inv")
                .getCompoundTag("item0")
                .equals(
                    blocked.payload()
                        .getCompoundTag("storage")
                        .getCompoundTag("#0")),
            "blocked interface payload contains only the single sanitized canonical storage representation");
        HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
            .updateSlot(block, 0, blocked.payload(), "storage/#0", null);
        check(
            !cleared.blocking && cleared.materials.isEmpty(),
            "explicit canonical storage CLEAR resolves its conflicting alias and removes both blueprint representations");
        HologramAeBlueprint.Analysis changed = HologramAeBlueprint
            .updateSlot(block, 0, analysis.payload(), "storage/#0", new ItemStack(Items.diamond, 3));
        check(
            !changed.blocking && changed.materials.size() == 1 && changed.materials.get(0).stackSize == 3,
            "validated storage SET synchronizes the inherited alias and charges only the new marker once");
        NBTTagCompound extra = (NBTTagCompound) raw.copy();
        extra.getCompoundTag("inv")
            .setTag("item99", alternate.copy());
        HologramAeBlueprint.Analysis hidden = HologramAeBlueprint.analyze(block, 0, extra);
        check(
            hidden.blocking && issue(hidden, "unknown_inventory_slot", "inv/item99")
                && HologramAeBlueprint.analyze(block, 0, hidden.payload()).blocking,
            "unknown inherited physical slots are never accepted through alias normalization");
        check(
            raw.equals(source) && conflict.equals(conflictSource)
                && ItemStack.areItemStacksEqual(inherited.getStackInSlot(0), stock),
            "interface alias edits and native round trips leave the original source and stock unchanged");
    }

    private static void previewCacheSchema() throws Exception {
        Block cable = registeredBlock("appeng.block.networking.BlockCableBus");
        NBTTagCompound raw = HologramNativeCompatibilityFixture.seed(cable, 11, 22, 33);
        raw.setBoolean("gtmb.nativePreview", true);
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(cable, 0, raw);
        check(
            !analysis.blocking && !analysis.payload()
                .hasKey("gtmb.aeCableVisual")
                && !analysis.payload()
                    .hasKey("gtmb.nativePreview"),
            "project-owned exact display cache is omitted without blocking a genuine native cable");
        check(
            analysis.issues.stream()
                .noneMatch(issue -> issue.path.startsWith("gtmb.")),
            "recognized display transport does not fill the player report with technical cache warnings");
        check(
            HologramAeBlueprint.resourcePlan(cable, 0, analysis.payload(), null).items.size() == 1,
            "display cache has no physical fee and does not alter the real cable shell cost");
        check(raw.equals(source), "display cache sanitization never edits the captured source");
        NBTTagCompound bad = (NBTTagCompound) raw.copy(), hidden = new NBTTagCompound();
        new ItemStack(Items.diamond, 32).writeToNBT(hidden);
        bad.getCompoundTag("gtmb.aeCableVisual")
            .setTag("hiddenPhysical", hidden);
        HologramAeBlueprint.Analysis unknown = HologramAeBlueprint.analyze(cable, 0, bad);
        check(
            unknown.blocking && issue(unknown, "unreviewed_cable_field", "gtmb.aeCableVisual")
                && HologramAeBlueprint.analyze(cable, 0, unknown.payload()).blocking,
            "unrecognized data inside a display-cache name remains a persistent physical-loss blocker");
        NBTTagCompound wrongType = (NBTTagCompound) raw.copy();
        wrongType.setTag("gtmb.nativePreview", hidden.copy());
        HologramAeBlueprint.Analysis malformed = HologramAeBlueprint.analyze(cable, 0, wrongType);
        check(
            malformed.blocking && HologramAeBlueprint.analyze(cable, 0, malformed.payload()).blocking,
            "a compound hidden behind the boolean preview marker is never silently accepted");
        NBTTagCompound ordinary = root();
        ordinary.setBoolean("gtmb.nativePreview", true);
        Block drive = AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get();
        check(
            !HologramAeBlueprint.analyze(drive, 0, ordinary).blocking,
            "ordinary AE native capture recognizes the same exact boolean transport marker");
    }

    private static void deletedPartMarkers(boolean multipart) throws Exception {
        Block block = registeredBlock(
            multipart ? "codechicken.multipart.BlockMultipart" : "appeng.block.networking.BlockCableBus");
        ItemStack invalid = AEApi.instance()
            .definitions()
            .parts()
            .toggleBus()
            .maybeStack(1)
            .get();
        // Minecraft clamps negative damage to zero, which is a registered cable variant.
        invalid.setItemDamage(Short.MAX_VALUE);
        check(
            invalid.getItemDamage() == Short.MAX_VALUE
                && ((IPartItem) invalid.getItem()).createPartFromItemStack(invalid.copy()) == null,
            "actual native factory rejects the positive unregistered part metadata");
        ItemStack retained = AEApi.instance()
            .definitions()
            .parts()
            .quartzFiber()
            .maybeStack(1)
            .get();
        NBTTagCompound bus = root(), invalidDef = new NBTTagCompound(), retainedDef = new NBTTagCompound();
        invalid.writeToNBT(invalidDef);
        retained.writeToNBT(retainedDef);
        bus.setTag("def:2", invalidDef);
        bus.setTag("def:3", retainedDef);
        NBTTagCompound hidden = new NBTTagCompound(), physical = new NBTTagCompound();
        new ItemStack(Items.diamond, 32).writeToNBT(physical);
        hidden.setTag("unreviewedHiddenBuffer", physical);
        bus.setTag("extra:2", hidden);
        NBTTagCompound raw = multipart ? multipartPayload(bus) : (NBTTagCompound) bus.copy();
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        String prefix = multipart ? "@part=0/" : "";
        HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(block, 0, raw);
        check(
            blocked.blocking && issue(blocked, "unsupported_part", prefix + "extra:2"),
            "unregistered native part metadata creates its permanent exact-side blocker " + prefix);
        HologramAeBlueprint.Analysis deleted = HologramAeBlueprint
            .updateSlot(block, 0, blocked.payload(), prefix + "def:2", null);
        check(
            !deleted.blocking && !HologramAeBlueprint.analyze(block, 0, deleted.payload()).blocking,
            "native definition CLEAR retires corresponding extra blockers through complete reanalysis " + prefix);
        check(
            HologramAeBlueprint.resolvedIssuePaths(prefix + "def:2", blocked, deleted)
                .contains(prefix + "extra:2"),
            "definition deletion reports the exact retired extra issue to server metadata " + prefix);
        check(
            deleted.materials.isEmpty(),
            "deleted unsupported part does not restore or charge omitted hidden inventory " + prefix);
        HologramBlueprintResources.Plan full = HologramAeBlueprint.resourcePlan(block, 0, deleted.payload(), null);
        check(
            full.items.size() == 1 && full.items.get(0).stackSize == 1
                && full.items.get(0)
                    .isItemEqual(retained),
            "complete repaired draft costs exactly its retained native shell " + prefix);
        HologramBlueprintResources.Plan extra = HologramAeBlueprint.resourcePlan(block, 0, deleted.payload(), retained);
        check(
            extra.items.isEmpty() && extra.fluids.isEmpty() && extra.energy.signum() == 0,
            "paid main shell has no duplicate or deleted-part extra cost " + prefix);
        check(raw.equals(source), "definition CLEAR and material projection preserve source snapshot " + prefix);
        NBTTagCompound stillBlocked = blocked.payload();
        stillBlocked.getTagList(HologramAeBlueprint.UNRESOLVED, 10)
            .appendTag(lossMarker("unsupported_host", ""));
        HologramAeBlueprint.Analysis host = HologramAeBlueprint
            .updateSlot(block, 0, stillBlocked, prefix + "def:2", null);
        check(
            host.blocking && issue(host, "unsupported_host", ""),
            "part deletion cannot acknowledge an unrelated unknown host " + prefix);
        NBTTagCompound otherBus = (NBTTagCompound) bus.copy();
        otherBus.removeTag("def:2");
        otherBus.removeTag("extra:2");
        otherBus.setTag("extra:3", hidden.copy());
        NBTTagCompound otherRaw;
        String otherPath;
        if (multipart) {
            otherRaw = multipartPayload(bus);
            otherBus.setString("id", "ae2_cablebus");
            otherRaw.getTagList("parts", 10)
                .appendTag(otherBus);
            otherPath = "@part=1/extra:3/unreviewedHiddenBuffer";
        } else {
            otherRaw = (NBTTagCompound) bus.copy();
            otherRaw.setTag("extra:3", hidden.copy());
            otherPath = "extra:3/unreviewedHiddenBuffer";
        }
        NBTTagCompound otherSource = (NBTTagCompound) otherRaw.copy();
        HologramAeBlueprint.Analysis both = HologramAeBlueprint.analyze(block, 0, otherRaw);
        HologramAeBlueprint.Analysis one = HologramAeBlueprint
            .updateSlot(block, 0, both.payload(), prefix + "def:2", null);
        check(
            one.blocking && issue(one, "unreviewed_part_field", otherPath),
            "deleting one side preserves another side or FMP part's physical-loss blocker " + prefix);
        check(otherRaw.equals(otherSource), "unrelated part marker and captured source remain untouched " + prefix);
    }

    private static NBTTagCompound multipartPayload(NBTTagCompound bus) {
        NBTTagCompound raw = root(), part = (NBTTagCompound) bus.copy();
        part.setString("id", "ae2_cablebus");
        NBTTagList parts = new NBTTagList();
        parts.appendTag(part);
        raw.setTag("parts", parts);
        return raw;
    }

    private static NBTTagCompound lossMarker(String code, String path) {
        NBTTagCompound marker = new NBTTagCompound();
        marker.setString("code", code);
        marker.setString("path", path);
        marker.setString("message", "native deletion fixture");
        return marker;
    }

    private static void secondaryProxy(IItemDefinition definition, EntityPlayerMP actor) throws Exception {
        ItemStack stack = definition.maybeStack(1)
            .get();
        check(stack.getItem() instanceof IPartItem, "native part definition implements IPartItem");
        IPart part = ((IPartItem) stack.getItem()).createPartFromItemStack(stack.copy());
        String type = part.getClass()
            .getName();
        String declaring = type.contains("ToggleBus") ? "appeng.parts.misc.PartToggleBus" : type;
        Field outer = Class.forName(declaring)
            .getDeclaredField("outerProxy");
        outer.setAccessible(true);
        AENetworkProxy secondary = (AENetworkProxy) outer.get(part);
        NBTTagCompound source = new NBTTagCompound(), node = new NBTTagCompound();
        node.setInteger("p", 9);
        node.setLong("k", 1234567);
        node.setLong("g", 0);
        source.setTag("part", node.copy());
        source.setTag("outer", node.copy());
        NBTTagCompound saved = (NBTTagCompound) source.copy();
        part.readFromNBT(source);
        Field data = AENetworkProxy.class.getDeclaredField("data");
        data.setAccessible(true);
        check(data.get(secondary) != null, type + " native read supplies pending secondary data");
        Method prepare = HologramAeBlueprint.class.getDeclaredMethod("preparePart", Object.class, EntityPlayerMP.class);
        prepare.setAccessible(true);
        prepare.invoke(null, part, actor);
        Field owner = AENetworkProxy.class.getDeclaredField("owner");
        owner.setAccessible(true);
        Field ready = AENetworkProxy.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        Field nativeNode = AENetworkProxy.class.getDeclaredField("node");
        nativeNode.setAccessible(true);
        for (AENetworkProxy proxy : Arrays.asList(((IGridProxyable) part).getProxy(), secondary)) {
            check(
                data.get(proxy) == null && owner.get(proxy) == actor,
                type + " pending data is cleared and actor is bound");
            check(
                !ready.getBoolean(proxy) && nativeNode.get(proxy) == null,
                type + " reset occurs before native ready/node creation");
        }
        Block cable = registeredBlock("appeng.block.networking.BlockCableBus");
        NBTTagCompound bus = root(), def = new NBTTagCompound();
        stack.writeToNBT(def);
        bus.setTag("def:2", def);
        bus.setTag("extra:2", saved.copy());
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(cable, 0, bus);
        check(
            !analysis.payload()
                .getCompoundTag("extra:2")
                .hasKey("outer"),
            type + " sanitizer removes exact outer root");
        check(
            !issue(analysis, "unreviewed_part_field", "extra:2/outer"),
            type + " outer root is reviewed rather than misclassified");
        check(source.equals(saved), type + " native source NBT remains unchanged");
    }

    private static void patternRotation() throws Exception {
        Block block = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        ItemStack pattern = AEApi.instance()
            .definitions()
            .items()
            .encodedPattern()
            .maybeStack(1)
            .get();
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setBoolean("crafting", false);
        NBTTagList inputs = new NBTTagList(), outputs = new NBTTagList();
        NBTTagCompound input = new NBTTagCompound();
        new ItemStack(Items.iron_ingot).writeToNBT(input);
        NBTTagCompound nested = new NBTTagCompound();
        nested.setString("ownerUUID", "recipe-owner-must-survive");
        nested.setInteger("x", 19);
        nested.setInteger("y", 28);
        nested.setInteger("z", -31);
        input.setTag("tag", nested);
        inputs.appendTag(input);
        NBTTagCompound output = new NBTTagCompound();
        new ItemStack(Items.gold_ingot).writeToNBT(output);
        outputs.appendTag(output);
        recipe.setTag("in", inputs);
        recipe.setTag("out", outputs);
        pattern.setTagCompound(recipe);
        NBTTagCompound raw = root(), patterns = new NBTTagCompound(), encoded = new NBTTagCompound();
        pattern.writeToNBT(encoded);
        patterns.setTag("#0", encoded);
        raw.setTag("patterns", patterns);
        NBTTagCompound source = (NBTTagCompound) raw.copy();
        String saved = canonical(raw);
        HologramBlockTransforms.Result turned = HologramBlockTransforms.turn(block, "", 0, saved, null, false);
        NBTTagCompound after = HologramBlueprintNbt.decode(turned.tileNbt)
            .getCompoundTag("patterns")
            .getCompoundTag("#0")
            .getCompoundTag("tag");
        // Persisted transport adds only stable registry-name markers; compare the complete native recipe.
        NBTTagCompound nativeRecipe = HologramBlueprintNbt.nativePayload(after);
        check(
            nativeRecipe.equals(recipe),
            "rotation transport preserves exact native pattern recipe ownerUUID and coordinates");
        check(raw.equals(source), "pattern rotation leaves the original recipe/source snapshot unchanged");
        check(
            HologramAeBlueprint.rotateIssuePath("@part=0/extra:2/storage/#0", false)
                .equals("@part=0/extra:" + HologramVirtualTransform.front(2, false) + "/storage/#0"),
            "FMP issue paths follow the exact moved AE side");
    }

    private static NBTTagCompound cellPayload(ItemStack stack) {
        NBTTagCompound raw = root(), inv = new NBTTagCompound(), item = new NBTTagCompound();
        stack.writeToNBT(item);
        inv.setTag("item0", item);
        raw.setTag("inv", inv);
        return raw;
    }

    private static NBTTagCompound root() {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("x", 11);
        raw.setInteger("y", 22);
        raw.setInteger("z", 33);
        raw.setString("orientation_forward", "NORTH");
        raw.setString("orientation_up", "UP");
        return raw;
    }

    private static void physicalSlotDto() throws Exception {
        Block block = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        ItemStack pattern = AEApi.instance()
            .definitions()
            .items()
            .encodedPattern()
            .maybeStack(1)
            .get();
        ItemStack card = AEApi.instance()
            .definitions()
            .materials()
            .cardCrafting()
            .maybeStack(1)
            .get();
        ItemStack ingredient = new ItemStack(Items.iron_ingot);
        NBTTagCompound authored = new NBTTagCompound();
        authored.setString("ownerUUID", "legal-recipe-owner-must-survive");
        authored.setString("owner", "RecipeAuthor");
        authored.setInteger("x", 19);
        authored.setInteger("y", -28);
        authored.setInteger("z", 31);
        ingredient.setTagCompound(authored);
        NBTTagCompound recipe = new NBTTagCompound(), input = new NBTTagCompound(), output = new NBTTagCompound();
        NBTTagList inputs = new NBTTagList(), outputs = new NBTTagList();
        ingredient.writeToNBT(input);
        new ItemStack(Items.gold_ingot).writeToNBT(output);
        inputs.appendTag(input);
        outputs.appendTag(output);
        recipe.setBoolean("crafting", false);
        recipe.setBoolean("substitute", false);
        recipe.setTag("in", inputs);
        recipe.setTag("out", outputs);
        pattern.setTagCompound(recipe);
        NBTTagCompound nativePattern = pattern.writeToNBT(new NBTTagCompound());
        NBTTagCompound nativeCard = card.writeToNBT(new NBTTagCompound());
        NBTTagCompound raw = root(), patterns = new NBTTagCompound(), upgrades = new NBTTagCompound();
        patterns.setTag("#0", nativePattern.copy());
        upgrades.setTag("#0", nativeCard.copy());
        raw.setTag("patterns", patterns);
        raw.setTag("upgrades", upgrades);
        NBTTagCompound sourceBefore = (NBTTagCompound) raw.copy();
        String encoded = HologramBlueprintNbt.encodeCanonical(block, raw);
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint
            .analyze(block, 0, HologramBlueprintNbt.decode(encoded));
        check(!analysis.blocking, "genuine Interface native encoded pattern/card enters the DTO schema");
        NBTTagCompound canonicalBefore = analysis.payload();
        check(
            canonicalBefore.getCompoundTag("patterns")
                .getCompoundTag("#0")
                .getCompoundTag("tag")
                .getTagList("in", 10)
                .getCompoundTagAt(0)
                .hasKey(HologramBlueprintNbt.ITEM_REGISTRY, 8),
            "persisted pattern keeps nested stable registry transport markers before DTO projection");
        HologramAeProtocol.Preview preview = new HologramAeProtocol.Preview(
            "native-dto",
            "tool",
            "group",
            7,
            11,
            22,
            33,
            block,
            0,
            encoded);
        NBTTagList dto = preview.snapshot(analysis)
            .getTagList("slots", 10);
        ItemStack shownPattern = null, shownCard = null;
        for (int index = 0; index < dto.tagCount(); index++) {
            NBTTagCompound row = dto.getCompoundTagAt(index);
            if (row.getString("id")
                .equals("patterns/#0")) shownPattern = ItemStack.loadItemStackFromNBT(row.getCompoundTag("stack"));
            if (row.getString("id")
                .equals("upgrades/#0")) shownCard = ItemStack.loadItemStackFromNBT(row.getCompoundTag("stack"));
        }
        check(
            ItemStack.areItemStacksEqual(shownPattern, pattern) && shownPattern.writeToNBT(new NBTTagCompound())
                .equals(nativePattern),
            "actual pattern DTO is whole native ItemStack/NBT equal to physical source including recipe owner UUID and xyz");
        check(
            ItemStack.areItemStacksEqual(shownCard, card) && shownCard.writeToNBT(new NBTTagCompound())
                .equals(nativeCard),
            "actual upgrade card DTO is whole native ItemStack/NBT equal to physical source");
        check(
            analysis.materials.size() == 2 && analysis.materials.stream()
                .anyMatch(stack -> ItemStack.areItemStacksEqual(stack, pattern))
                && analysis.materials.stream()
                    .anyMatch(stack -> ItemStack.areItemStacksEqual(stack, card)),
            "native physical pattern/card material projection remains exact and paid once");
        check(
            analysis.payload()
                .equals(canonicalBefore) && raw.equals(sourceBefore)
                && pattern.writeToNBT(new NBTTagCompound())
                    .equals(nativePattern)
                && card.writeToNBT(new NBTTagCompound())
                    .equals(nativeCard),
            "DTO projection changes neither canonical transport markers nor physical source pattern/card NBT");
        portablePlacedConfiguration(block, analysis);
    }

    private static void portablePlacedConfiguration(Block block, HologramAeBlueprint.Analysis blueprint)
        throws Exception {
        TileEntity actual = HologramAeBlueprint
            .createNative(block, 0, blueprint.payload(), HologramResourceWorld.create(block, 0));
        NBTTagCompound nativeBefore = new NBTTagCompound();
        actual.writeToNBT(nativeBefore);
        NBTTagCompound expected = HologramBlueprintNbt
            .decode(HologramBlueprintNbt.encodeCanonical(block, nativeBefore));
        NBTTagCompound canonicalBefore = (NBTTagCompound) expected.copy();
        check(
            expected.getCompoundTag("patterns")
                .getCompoundTag("#0")
                .getCompoundTag("tag")
                .getTagList("in", 10)
                .getCompoundTagAt(0)
                .hasKey(HologramBlueprintNbt.ITEM_REGISTRY, 8),
            "portable placed comparison receives genuine persisted nested item registry markers");
        check(
            HologramAeBlueprint.matchesPlaced(block, 0, actual, expected),
            "actual native Interface pattern/card configuration matches identical portable canonical configuration");
        NBTTagCompound quantityChanged = (NBTTagCompound) expected.copy();
        quantityChanged.getCompoundTag("patterns")
            .getCompoundTag("#0")
            .getCompoundTag("tag")
            .getTagList("in", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        check(
            !HologramAeBlueprint.matchesPlaced(block, 0, actual, quantityChanged),
            "portable normalization preserves different exact recipe ingredient quantity");
        NBTTagCompound cardChanged = (NBTTagCompound) expected.copy(), card = new NBTTagCompound();
        AEApi.instance()
            .definitions()
            .materials()
            .cardPatternCapacity()
            .maybeStack(1)
            .get()
            .writeToNBT(card);
        cardChanged.getCompoundTag("upgrades")
            .setTag("#0", card);
        check(
            !HologramAeBlueprint.analyze(block, 0, cardChanged).blocking,
            "different card fixture remains inside the real native Interface upgrade schema");
        check(
            !HologramAeBlueprint.matchesPlaced(block, 0, actual, cardChanged),
            "portable normalization preserves different valid native upgrade card metadata");
        NBTTagCompound configChanged = (NBTTagCompound) expected.copy(), ghost = new NBTTagCompound();
        appeng.util.item.AEItemStack.create(new ItemStack(Items.diamond))
            .writeToNBT(ghost);
        NBTTagCompound config = configChanged.getCompoundTag("config");
        config.setTag("#0", ghost);
        configChanged.setTag("config", config);
        check(
            !HologramAeBlueprint.analyze(block, 0, configChanged).blocking,
            "different ghost fixture remains inside the real native Interface configuration schema");
        check(
            !HologramAeBlueprint.matchesPlaced(block, 0, actual, configChanged),
            "portable normalization preserves changed native ghost configuration");
        NBTTagCompound invalid = (NBTTagCompound) expected.copy();
        invalid.setString("unreviewed_external_reference", "must-not-be-acknowledged");
        check(
            !HologramAeBlueprint.matchesPlaced(block, 0, actual, invalid),
            "portable normalization retains unknown-schema blocker validation");
        NBTTagCompound nativeAfter = new NBTTagCompound();
        actual.writeToNBT(nativeAfter);
        check(
            expected.equals(canonicalBefore) && nativeBefore.equals(nativeAfter),
            "all portable configuration comparisons preserve canonical transport markers and entire actual native source");
    }

    private static String canonical(NBTTagCompound raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            CompressedStreamTools.write(raw, output);
        }
        return Base64.getEncoder()
            .encodeToString(bytes.toByteArray());
    }

    private static Block registeredBlock(String type) {
        for (Object value : Block.blockRegistry) if (value instanceof Block && value.getClass()
            .getName()
            .equals(type)) return (Block) value;
        throw new AssertionError("Missing native block " + type);
    }

    private static HologramAeBlueprint.Slot slot(HologramAeBlueprint.Analysis analysis, String id) {
        for (HologramAeBlueprint.Slot slot : analysis.slots) if (slot.id.equals(id)) return slot;
        throw new AssertionError("Missing slot " + id);
    }

    private static boolean issue(HologramAeBlueprint.Analysis analysis, String code, String path) {
        for (HologramAeBlueprint.Issue issue : analysis.issues)
            if (issue.code.equals(code) && issue.path.equals(path)) return true;
        return false;
    }

    private static void cableBusTargetHost() throws Exception {
        Block block = registeredBlock("appeng.block.networking.BlockCableBus");
        ItemStack main = AEApi.instance()
            .definitions()
            .parts()
            .iface()
            .maybeStack(1)
            .get();
        check(
            Block.getBlockFromItem(main.getItem()) == net.minecraft.init.Blocks.air,
            "genuine CBC primary interface material is not a block item");
        NBTTagCompound raw = HologramNativeCompatibilityFixture.seed(block, 10, 64, 20);
        NBTTagCompound def = new NBTTagCompound(), extra = new NBTTagCompound();
        main.writeToNBT(def);
        ((IPartItem) main.getItem()).createPartFromItemStack(main.copy())
            .writeToNBT(extra);
        raw.setTag("def:2", def);
        raw.setTag("extra:2", extra);
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, 0, raw);
        check(!analysis.blocking, "genuine CBC target source uses reviewed native part schema");
        NBTTagCompound canonical = analysis.payload(), before = (NBTTagCompound) canonical.copy();
        String encoded = HologramBlueprintNbt.encodeCanonical(block, canonical);
        HologramBlueprintResources.Plan costs = HologramAeBlueprint.resourcePlan(block, 0, canonical, null);
        NBTTagCompound display = HologramDisplaySnapshot.target(block, main, -1, null, encoded, true);
        check(
            display.hasKey("nativeTile", 10) && display.getCompoundTag("nativeTile")
                .equals(HologramBlueprintNbt.decode(encoded)),
            "registered declared CBC host publishes the exact filtered canonical nativeTile for ItemMultiPart material");
        check(
            !HologramDisplaySnapshot.target(main, -1, null, encoded, true)
                .hasKey("nativeTile")
                && !HologramDisplaySnapshot.target(net.minecraft.init.Blocks.stone, main, -1, null, encoded, true)
                    .hasKey("nativeTile"),
            "CBC display never guesses the host from an arbitrary part material and preserves the old signature");
        HologramBlueprintResources.Plan after = HologramAeBlueprint.resourcePlan(block, 0, canonical, null);
        check(
            costs.items.size() == after.items.size() && costs.items.size() == 2 && canonical.equals(before),
            "declared-host display preserves exact source and two native shell costs");
        for (int index = 0; index < costs.items.size(); index++) check(
            ItemStack.areItemStacksEqual(costs.items.get(index), after.items.get(index)),
            "CBC display cannot change a native component material stack");
    }

    private static void emptyNativeShellCapability(Block drive) throws Exception {
        Block iface = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        check(
            !HologramAeBlueprint.supportsEmptyNativeShell(registeredBlock("appeng.block.networking.BlockCableBus")),
            "CBC cannot bypass retained multipart structure as an ordinary empty shell");
        for (Block block : Arrays.asList(drive, iface)) {
            check(
                HologramAeBlueprint.supportsEmptyNativeShell(block),
                "reviewed ordinary native factory can install a filtered empty shell");
            NBTTagCompound source = new NBTTagCompound(), inv = new NBTTagCompound(), row = new NBTTagCompound();
            new ItemStack(net.minecraft.init.Items.diamond, 37).writeToNBT(row);
            inv.setTag("item0", row);
            source.setTag("inv", inv);
            source.setTag("patterns", row.copy());
            source.setTag("upgrades", row.copy());
            source.setTag("config", row.copy());
            source.setString("orientation_forward", "WEST");
            source.setByte("paintedColor", (byte) 3);
            NBTTagCompound before = (NBTTagCompound) source.copy();
            NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
                block,
                0,
                HologramBlueprintNbt.encodeCanonical(block, source),
                java.util.UUID.randomUUID(),
                "builder",
                10,
                64,
                20,
                false,
                false,
                null);
            HologramBlueprintResources.Plan full = HologramAeBlueprint.resourcePlan(block, 0, selected, null);
            ItemStack shell = HologramAeBlueprint.blockSample(block, 0);
            HologramBlueprintResources.Plan extra = HologramAeBlueprint.resourcePlan(block, 0, selected, shell);
            check(
                full.items.size() == 1 && full.items.get(0).stackSize == 1
                    && full.items.get(0)
                        .isItemEqual(shell)
                    && extra.items.isEmpty()
                    && extra.fluids.isEmpty()
                    && extra.energy.signum() == 0,
                "filtered native canonical costs exactly one ordinary shell and zero omitted physical/ghost items");
            TileEntity nativeTile = HologramAeBlueprint
                .createNative(block, 0, selected, HologramResourceWorld.create(block, 0));
            net.minecraft.inventory.IInventory nativeInventory = ((AEBaseInvTile) nativeTile).getInternalInventory();
            for (int slot = 0; slot < nativeInventory.getSizeInventory(); slot++)
                check(nativeInventory.getStackInSlot(slot) == null, "filtered native shell inventory is empty");
            check(
                !selected.hasKey("orientation_forward") && !selected.hasKey("paintedColor")
                    && !selected.hasKey("proxy")
                    && source.equals(before),
                "filtered native shell omits source direction/paint/identity and preserves the entire source");
        }
    }

    private interface Action {

        void run() throws Exception;
    }

    private static void expectDenied(Action action, String message) throws Exception {
        boolean denied = false;
        try {
            action.run();
        } catch (IOException expected) {
            denied = true;
        }
        check(denied, message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(PREFIX + message);
        checks++;
        System.out.println(PREFIX + "PASS " + message);
    }
}

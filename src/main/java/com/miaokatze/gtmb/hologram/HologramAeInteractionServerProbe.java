package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Independent native-source and disk witnesses for a real AE socket epoch; does not perform planner actions. */
public final class HologramAeInteractionServerProbe {

    private final HologramAeBlueprintSmoke.Fixtures fixtures;
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final NBTTagCompound[] sourceInventories;
    private NBTTagCompound fullSourceCell;
    private boolean sourcePrepared;
    private final java.util.List<ItemStack> initialCosts = new java.util.ArrayList<>();
    private final java.util.List<NBTTagCompound> paidReview = new java.util.ArrayList<>();
    private final Path library;
    private final Path clientLibrary;
    private java.util.Map<String, String> localStamp;
    private java.util.Map<String, String> localBaseline;
    private boolean cancelledLocalVerified;
    private String confirmedLocalId = "", confirmedLocalDigest = "";
    private int confirmedNativeCells;
    private LibraryStamp stamp;
    private boolean watchingItems;
    private int spawnedItems;
    private NBTTagCompound actorInventory;
    private Container realContainer;
    private int heldSlot;

    public HologramAeInteractionServerProbe(WorldServer world, EntityPlayerMP actor, Path isolatedFixtureRoot,
        boolean extended) throws Exception {
        Path root = isolatedFixtureRoot.toRealPath();
        require(
            root.getNameCount() > 2 && root.toString()
                .contains("hologram"),
            "isolated hologram fixture root required");
        library = root.resolve("MiaoBlueprints")
            .resolve("hologram-presets.json")
            .normalize();
        require(library.startsWith(root), "library escaped the isolated fixture root");
        clientLibrary = root.getParent()
            .resolve("client/MiaoBlueprints")
            .normalize();
        require(
            !HologramLibraryFacade.enabled() || clientLibrary.startsWith(root.getParent()) && root.getFileName()
                .toString()
                .equals("server"),
            "known sibling client directory required for private file witness");
        Path ancestor = library;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        require(
            ancestor.toRealPath()
                .startsWith(root),
            "library path follows a link outside the fixture");
        this.world = world;
        this.actor = actor;
        // Independent native sourceOwner, genuine full cell/pattern/card contents, and no sanitized source injection.
        fixtures = HologramAeBlueprintSmoke.createFixtures(world, actor, 648, 88, 192, extended);
        sourceInventories = new NBTTagCompound[fixtures.sourceCount];
    }

    /** Called by the server tick driver only after two genuine world ticks from native source creation. */
    public void completeSourcePreparation() throws Exception {
        require(!sourcePrepared, "native source preparation must happen once");
        HologramAeBlueprintSmoke.assertSourceReady(fixtures);
        for (int i = 0; i < fixtures.sourceCount; i++) initialCosts.addAll(fixtures.componentCosts(i));
        // A native disabled slot remains serialized. Only the imported blueprint copy may be cleared by the UI.
        ((appeng.helpers.IInterfaceHost) fixtures.sourceTile(1)).getInterfaceDuality()
            .getPatterns()
            .setInventorySlotContents(35, fixtures.sourcePattern());
        for (int i = 0; i < sourceInventories.length; i++) sourceInventories[i] = fixtures.sourceInventory(i);
        fullSourceCell = fixtures.sourceCell()
            .writeToNBT(new NBTTagCompound());
        stamp = LibraryStamp.read(library);
        if (HologramLibraryFacade.enabled()) {
            localStamp = localFiles();
            localBaseline = new java.util.TreeMap<>(localStamp);
        }
        sourcePrepared = true;
    }

    public HologramAeBlueprintSmoke.Fixtures fixtures() {
        return fixtures;
    }

    public java.util.List<ItemStack> initialCosts() {
        require(sourcePrepared, "native source costs requested before native tick preparation");
        java.util.List<ItemStack> copies = new java.util.ArrayList<>();
        for (ItemStack item : initialCosts) copies.add(item.copy());
        return copies;
    }

    public void beginVirtualEpoch() throws IOException {
        assertSourceUnchanged("virtual epoch start");
        stamp = LibraryStamp.read(library);
        if (HologramLibraryFacade.enabled()) {
            localStamp = localFiles();
            localBaseline = new java.util.TreeMap<>(localStamp);
            cancelledLocalVerified = false;
        }
        heldSlot = actor.inventory.currentItem;
        actorInventory = actorInventory();
        realContainer = actor.openContainer;
        require(
            realContainer == actor.inventoryContainer,
            "AE virtual epoch requires original real inventory container");
        spawnedItems = 0;
        watchingItems = true;
    }

    public void assertVirtualEpoch(String stage) throws IOException {
        require(watchingItems, stage + ": virtual epoch not started");
        require(spawnedItems == 0, stage + ": spawned native item entities=" + spawnedItems);
        require(
            actor.openContainer == realContainer && actor.inventory.currentItem == heldSlot,
            stage + ": server player container/hand changed");
        require(actorInventory.equals(actorInventory()), stage + ": server player inventory/cursor changed");
        assertSourceUnchanged(stage);
    }

    public void assertCancelledSave(String stage) throws IOException {
        assertVirtualEpoch(stage);
        require(stamp.equals(LibraryStamp.read(library)), stage + ": cancelled/blocked save changed the library");
        if (HologramLibraryFacade.enabled()) {
            require(localStamp.equals(localFiles()), stage + ": cancelled/blocked save changed private client files");
            cancelledLocalVerified = true;
        }
    }

    public void assertConfirmedSaveChangedLibrary(String stage) throws IOException {
        assertVirtualEpoch(stage);
        LibraryStamp after = LibraryStamp.read(library);
        if (HologramLibraryFacade.enabled()) {
            require(stamp.equals(after), stage + ": private save unexpectedly changed the shared server library");
            assertConfirmedPrivateFile(stage);
            return;
        }
        require(
            after.exists && !after.digest.equals(stamp.digest),
            stage + ": real confirmed save wrote no new blueprint");
        stamp = after;
    }

    public void endVirtualEpoch() {
        watchingItems = false;
    }

    /** Call throughout paid placement too: actual source cells and exact encoded patterns must remain intact. */
    public void assertSourceUnchanged(String stage) throws IOException {
        require(sourcePrepared, stage + ": native source baseline is not prepared");
        for (int i = 0; i < sourceInventories.length; i++) require(
            sourceInventories[i].equals(fixtures.sourceInventory(i)),
            stage + ": native source inventory changed at " + i);
        require(
            fullSourceCell.equals(
                fixtures.sourceCell()
                    .writeToNBT(new NBTTagCompound())),
            stage + ": original filled137-iron cell contents/partition/card changed");
    }

    @SubscribeEvent
    public void spawned(EntityJoinWorldEvent event) {
        if (watchingItems && event.world == world && event.entity instanceof EntityItem) spawnedItems++;
    }

    public NBTTagCompound receipt() throws IOException {
        NBTTagCompound out = new NBTTagCompound();
        out.setInteger("sourceCount", fixtures.sourceCount);
        out.setInteger("spawnedDuringVirtualEpoch", spawnedItems);
        out.setBoolean("virtualEpochActive", watchingItems);
        out.setBoolean(
            "serverInventoryUnchangedExceptPlannerDraft",
            actorInventory != null && actorInventory.equals(actorInventory()));
        out.setBoolean("libraryExists", Files.exists(library));
        out.setString("librarySha256", LibraryStamp.read(library).digest);
        out.setBoolean("clientPrivateLibrary", HologramLibraryFacade.enabled());
        out.setString("confirmedLocalId", confirmedLocalId);
        out.setString("confirmedLocalSha256", confirmedLocalDigest);
        out.setInteger("confirmedNativeCells", confirmedNativeCells);
        out.setBoolean("confirmedClientCanonicalFile", !confirmedLocalId.isEmpty());
        out.setBoolean("cancelledClientLocalFilesUnchanged", cancelledLocalVerified);
        NBTTagList baseline = new NBTTagList();
        if (localBaseline != null) for (java.util.Map.Entry<String, String> entry : localBaseline.entrySet()) {
            NBTTagCompound file = new NBTTagCompound();
            file.setString("file", entry.getKey());
            file.setString("sha256", entry.getValue());
            baseline.appendTag(file);
        }
        out.setTag("beforeClientLocalFiles", baseline);
        out.setIntArray("sourceX", fixtures.sourceX.clone());
        out.setInteger("sourceY", fixtures.y);
        out.setInteger("sourceZ", fixtures.z);
        return out;
    }

    private java.util.Map<String, String> localFiles() throws IOException {
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        if (!Files.exists(clientLibrary)) return result;
        require(
            clientLibrary.toRealPath()
                .startsWith(
                    clientLibrary.getParent()
                        .getParent()
                        .getParent()
                        .getParent()
                        .toRealPath()),
            "private library escaped isolated sibling client");
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(clientLibrary, "*.json")) {
            for (Path file : files) result.put(
                file.getFileName()
                    .toString(),
                sha(HologramBlueprintSharing.readBytes(file)));
        }
        return result;
    }

    private void assertConfirmedPrivateFile(String stage) throws IOException {
        try {
            java.lang.reflect.Field sessions = HologramService.class.getDeclaredField("SESSIONS");
            sessions.setAccessible(true);
            Object session = ((java.util.Map<?, ?>) sessions.get(null)).get(actor.getUniqueID());
            require(session != null, stage + ": missing actual server session");
            java.lang.reflect.Field localId = session.getClass()
                .getDeclaredField("libraryLocalId");
            localId.setAccessible(true);
            String id = HologramLibraryService.localId((String) localId.get(session), false);
            java.util.Map<String, String> after = localFiles();
            String digest = after.get(id + ".json");
            require(
                digest != null && !digest.equals(localStamp.get(id + ".json")),
                stage + ": confirmed save wrote no new private file");
            HologramPresets.Preset actual = HologramBlueprintSharing.decode(
                new String(
                    HologramBlueprintSharing.readBytes(clientLibrary.resolve(id + ".json")),
                    java.nio.charset.StandardCharsets.UTF_8));
            require(id.equals(actual.id), stage + ": private file identity mismatch");
            require("SocketAEV005".equals(actual.name), stage + ": private file name mismatch: " + actual.name);
            require(
                actor.getUniqueID()
                    .toString()
                    .equals(actual.authorUuid)
                    && actor.getCommandSenderName()
                        .equals(actual.authorName),
                stage + ": private file creator mismatch");
            java.lang.reflect.Field presetField = session.getClass()
                .getDeclaredField("libraryPrivatePreset");
            presetField.setAccessible(true);
            HologramPresets.Preset expected = (HologramPresets.Preset) presetField.get(session);
            require(expected != null, stage + ": missing reviewed canonical draft");
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject actualJson = gson.toJsonTree(actual)
                .getAsJsonObject(),
                expectedJson = gson.toJsonTree(expected)
                    .getAsJsonObject();
            actualJson.remove("id");
            expectedJson.remove("id");
            require(
                actualJson.equals(expectedJson),
                stage + ": native private file differs from the exact approved canonical draft");
            int nativeCells = 0;
            for (HologramPresets.Cell cell : actual.cells)
                if (HologramAePolicy.handles(cell.block(), cell.tileNbt)) nativeCells++;
            require(nativeCells == fixtures.sourceCount, stage + ": private file omitted native source devices");
            confirmedLocalId = id;
            confirmedLocalDigest = digest;
            confirmedNativeCells = nativeCells;
            localStamp = after;
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Actual private save witness failed", failure);
        }
    }

    /** Freeze the real paid draft before its world position changes; never derives a substitute configuration. */
    void freezePaidReview(Object session, HologramCapture capture) throws Exception {
        require(paidReview.isEmpty(), "paid review witness already frozen");
        java.lang.reflect.Field presetField = session.getClass()
            .getDeclaredField("virtualPreset");
        presetField.setAccessible(true);
        HologramPresets.Preset preset = (HologramPresets.Preset) presetField.get(session);
        java.lang.reflect.Method retainMethod = HologramService.class
            .getDeclaredMethod("placementRetainCovers", session.getClass());
        java.lang.reflect.Method keysMethod = HologramService.class
            .getDeclaredMethod("placementKeys", session.getClass());
        retainMethod.setAccessible(true);
        keysMethod.setAccessible(true);
        boolean covers = (Boolean) retainMethod.invoke(null, session);
        Object selected = keysMethod.invoke(null, session);
        java.util.List<String> keys = selected == null ? null : new java.util.ArrayList<>();
        if (keys != null) for (Object key : (java.util.List<?>) selected) keys.add((String) key);
        for (HologramCapture.Cell cell : capture.cells) if (HologramAePolicy.handles(cell.block, cell.tileNbt)) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(
                "block",
                String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block)));
            row.setInteger("metadata", cell.meta);
            row.setString("capturedTile", cell.tileNbt);
            row.setTag(
                "expected",
                HologramBlueprintNbt.decode(cell.tileNbt)
                    .copy());
            row.setBoolean("retainNbt", preset.retainNbt);
            row.setBoolean("retainCovers", covers);
            row.setBoolean("allKeys", keys == null);
            NBTTagList keyTags = new NBTTagList();
            if (keys != null) for (String key : keys) keyTags.appendTag(new net.minecraft.nbt.NBTTagString(key));
            row.setTag("keys", keyTags);
            paidReview.add(row);
        }
        require(paidReview.size() == fixtures.sourceCount, "paid review witness must include every native source");
    }

    /** Persist actual native writer output and the frozen expected payload before the strict placed assertion. */
    void recordPaidJobState(Object session, HologramCapture capture, java.util.Map<String, Integer> before,
        java.util.Map<String, Integer> reviewedCosts, int placeEvents, Path directory) throws Exception {
        Path destination = directory.toRealPath();
        require(
            destination.getFileName()
                .toString()
                .equals("receipts")
                && destination.toString()
                    .contains("hologram-interaction"),
            "isolated interaction receipts required");
        NBTTagCompound state = new NBTTagCompound();
        for (java.lang.reflect.Field field : session.getClass()
            .getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(session);
            if (value instanceof String) state.setString(field.getName(), (String) value);
            else if (value instanceof Boolean) state.setBoolean(field.getName(), (Boolean) value);
            else if (value instanceof Integer) state.setInteger(field.getName(), (Integer) value);
            else if (value instanceof Long) state.setLong(field.getName(), (Long) value);
            else if (value instanceof NBTTagCompound) state.setTag(field.getName(), ((NBTTagCompound) value).copy());
        }
        state.setInteger("actualPlaceEvents", placeEvents);
        state.setTag("actualPlayerInventory", actorInventory());
        NBTTagList beforeItems = new NBTTagList(), costs = new NBTTagList();
        for (java.util.Map.Entry<String, Integer> entry : before.entrySet()) beforeItems.appendTag(countRow(entry));
        for (java.util.Map.Entry<String, Integer> entry : reviewedCosts.entrySet()) costs.appendTag(countRow(entry));
        state.setTag("beforePaidInventoryCounts", beforeItems);
        state.setTag("reviewedCostCounts", costs);
        java.lang.reflect.Field statusesField = session.getClass()
            .getDeclaredField("planStatus");
        java.lang.reflect.Field targetsField = session.getClass()
            .getDeclaredField("planTargets");
        java.lang.reflect.Field workField = session.getClass()
            .getDeclaredField("work");
        statusesField.setAccessible(true);
        targetsField.setAccessible(true);
        workField.setAccessible(true);
        java.util.List<?> planStatuses = (java.util.List<?>) statusesField.get(session);
        java.util.List<?> planTargets = (java.util.List<?>) targetsField.get(session);
        java.util.List<?> work = (java.util.List<?>) workField.get(session);
        NBTTagList rows = new NBTTagList();
        int index = 0;
        for (HologramCapture.Cell cell : capture.cells) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", index++);
            row.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            row.setString(
                "plannedBlock",
                String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block)));
            row.setInteger("plannedMetadata", cell.meta);
            row.setString("status", cell.status);
            row.setString(
                "planStatus",
                index - 1 < planStatuses.size() ? String.valueOf(planStatuses.get(index - 1)) : "<absent>");
            row.setBoolean("inWork", work.contains(index - 1));
            row.setBoolean("nativeSupported", HologramNativePlacement.supported(cell.block));
            row.setBoolean("unknown", cell.unknown);
            row.setBoolean("excluded", cell.excluded);
            row.setBoolean("anchor", cell.anchor);
            row.setString("role", cell.role);
            if (index - 1 < planTargets.size() && planTargets.get(index - 1) instanceof ItemStack) {
                ItemStack target = (ItemStack) planTargets.get(index - 1);
                row.setTag("planTarget", target.writeToNBT(new NBTTagCompound()));
                row.setString(
                    "targetItemBlock",
                    String.valueOf(
                        net.minecraft.block.Block.blockRegistry
                            .getNameForObject(net.minecraft.block.Block.getBlockFromItem(target.getItem()))));
            }
            row.setString("resourceError", cell.resourceError);
            row.setString("resourceFingerprint", cell.resourceFingerprint);
            row.setString("tileNbt", cell.tileNbt);
            row.setString(
                "actualBlock",
                String.valueOf(
                    net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(cell.x, cell.y, cell.z))));
            row.setInteger("actualMetadata", world.getBlockMetadata(cell.x, cell.y, cell.z));
            net.minecraft.tileentity.TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            row.setBoolean("actualTilePresent", tile != null);
            if (tile != null) {
                NBTTagCompound nativeTag = new NBTTagCompound();
                tile.writeToNBT(nativeTag);
                row.setTag("actualTile", nativeTag);
            }
            if (cell.resourcePlan != null) {
                NBTTagList items = new NBTTagList();
                for (ItemStack item : cell.resourcePlan.items) items.appendTag(item.writeToNBT(new NBTTagCompound()));
                row.setTag("cachedResourceItems", items);
            }
            rows.appendTag(row);
            System.out.println(
                "[GTMB-HOLOGRAM-AE-INTERACTION-SERVER] job cell=" + (index - 1)
                    + " pos="
                    + cell.x
                    + ","
                    + cell.y
                    + ","
                    + cell.z
                    + " planned="
                    + row.getString("plannedBlock")
                    + " actual="
                    + row.getString("actualBlock")
                    + " status="
                    + cell.status
                    + " planStatus="
                    + row.getString("planStatus")
                    + " inWork="
                    + row.getBoolean("inWork")
                    + " resourceError="
                    + cell.resourceError);
        }
        state.setTag("actualJobCells", rows);
        NBTTagList frozen = new NBTTagList();
        for (NBTTagCompound row : paidReview) frozen.appendTag(row.copy());
        state.setTag("frozenPaidReview", frozen);
        writeEvidence(destination.resolve("ae-paid-job-state.nbt"), state);
        System.out.println(
            "[GTMB-HOLOGRAM-AE-INTERACTION-SERVER] job diagnostic=" + state.getString("status")
                + " job="
                + state.getInteger("job")
                + " completed="
                + state.getInteger("completed")
                + " missing="
                + state.getInteger("jobMissingCells")
                + " unsupported="
                + state.getInteger("jobUnsupportedCells")
                + " protected="
                + state.getInteger("jobProtectedCells")
                + " actualPlaceEvents="
                + placeEvents);
    }

    private static NBTTagCompound countRow(java.util.Map.Entry<String, Integer> entry) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("identity", entry.getKey());
        row.setInteger("count", entry.getValue());
        return row;
    }

    NBTTagCompound recordPlacedComparison(HologramCapture.Cell cell, int index, Path directory) throws Exception {
        require(index >= 0 && index < paidReview.size(), "paid comparison index outside frozen review");
        Path destination = directory.toRealPath();
        require(
            destination.getFileName()
                .toString()
                .equals("receipts")
                && destination.toString()
                    .contains("hologram-interaction"),
            "isolated interaction receipts required");
        NBTTagCompound expectedRow = paidReview.get(index);
        String block = String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block));
        require(
            expectedRow.getString("block")
                .equals(block) && expectedRow.getInteger("metadata") == cell.meta,
            "paid native ordering/identity differs from frozen review");
        NBTTagCompound expected = (NBTTagCompound) expectedRow.getCompoundTag("expected")
            .copy();
        java.util.List<String> keys = expectedRow.getBoolean("allKeys") ? null : new java.util.ArrayList<>();
        if (keys != null) {
            NBTTagList keyTags = expectedRow.getTagList("keys", 8);
            for (int i = 0; i < keyTags.tagCount(); i++) keys.add(keyTags.getStringTagAt(i));
        }
        NBTTagCompound selectedExpected = HologramBlueprintNbt.forPlacement(
            cell.block,
            cell.meta,
            expectedRow.getString("capturedTile"),
            actor.getUniqueID(),
            actor.getCommandSenderName(),
            cell.x,
            cell.y,
            cell.z,
            expectedRow.getBoolean("retainNbt"),
            expectedRow.getBoolean("retainCovers"),
            keys);
        NBTTagCompound actual = new NBTTagCompound();
        net.minecraft.tileentity.TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
        if (tile != null) tile.writeToNBT(actual);
        writeEvidence(destination.resolve("ae-paid-reviewed-" + index + ".nbt"), expected);
        writeEvidence(destination.resolve("ae-paid-actual-" + index + ".nbt"), actual);
        NBTTagCompound report = new NBTTagCompound();
        report.setString("block", block);
        report.setInteger("metadata", cell.meta);
        report.setIntArray("placedPosition", new int[] { cell.x, cell.y, cell.z });
        report.setBoolean("actualTilePresent", tile != null);
        report.setTag("frozenReviewOptions", expectedRow.copy());
        report.setTag("forPlacementExpected", selectedExpected.copy());
        report.setTag(
            "currentDraftExpected",
            HologramBlueprintNbt.decode(cell.tileNbt)
                .copy());
        HologramAeBlueprint.Analysis left = HologramAeBlueprint.analyze(cell.block, cell.meta, actual);
        HologramAeBlueprint.Analysis right = HologramAeBlueprint.analyze(cell.block, cell.meta, selectedExpected);
        report.setBoolean("actualBlocking", left.blocking);
        report.setBoolean("expectedBlocking", right.blocking);
        NBTTagCompound canonicalActual = left.payload(), canonicalExpected = right.payload();
        report.setTag("canonicalActual", canonicalActual.copy());
        report.setTag("canonicalExpected", canonicalExpected.copy());
        // Apply the production native transport boundary only; preserve every legal configuration field.
        canonicalActual = HologramBlueprintNbt.nativePayload(canonicalActual);
        canonicalExpected = HologramBlueprintNbt.nativePayload(canonicalExpected);
        for (String key : new String[] { "x", "y", "z" }) {
            canonicalActual.removeTag(key);
            canonicalExpected.removeTag(key);
        }
        java.util.List<String> differences = new java.util.ArrayList<>();
        difference("", canonicalExpected, canonicalActual, differences);
        NBTTagList rows = new NBTTagList();
        for (String diff : differences) rows.appendTag(new net.minecraft.nbt.NBTTagString(diff));
        report.setTag("comparisonDifferences", rows);
        report.setBoolean(
            "nativeConfigurationMatches",
            tile != null && HologramAeBlueprint.matchesPlaced(cell.block, cell.meta, tile, selectedExpected));
        writeEvidence(destination.resolve("ae-paid-comparison-" + index + ".nbt"), report);
        System.out.println(
            "[GTMB-HOLOGRAM-AE-INTERACTION-SERVER] placed comparison index=" + index
                + " block="
                + block
                + " pos="
                + cell.x
                + ","
                + cell.y
                + ","
                + cell.z
                + " actualBlocking="
                + left.blocking
                + " expectedBlocking="
                + right.blocking
                + " differences="
                + differences);
        return (NBTTagCompound) selectedExpected.copy();
    }

    private static void writeEvidence(Path path, NBTTagCompound value) throws IOException {
        require(!Files.isSymbolicLink(path), "receipt evidence cannot replace a symbolic link");
        try (java.io.OutputStream out = Files.newOutputStream(path)) {
            net.minecraft.nbt.CompressedStreamTools.writeCompressed(value, out);
        }
    }

    private static void difference(String path, net.minecraft.nbt.NBTBase expected, net.minecraft.nbt.NBTBase actual,
        java.util.List<String> out) throws ReflectiveOperationException {
        if (expected == null ? actual == null : expected.equals(actual)) return;
        if (expected instanceof NBTTagCompound && actual instanceof NBTTagCompound) {
            NBTTagCompound a = (NBTTagCompound) expected, b = (NBTTagCompound) actual;
            java.util.Set<String> keys = new java.util.TreeSet<>();
            for (Object key : a.func_150296_c()) keys.add((String) key);
            for (Object key : b.func_150296_c()) keys.add((String) key);
            for (String key : keys) difference(path + "/" + key, a.getTag(key), b.getTag(key), out);
        } else if (expected instanceof NBTTagList && actual instanceof NBTTagList) {
            java.lang.reflect.Field contents = cpw.mods.fml.relauncher.ReflectionHelper
                .findField(NBTTagList.class, "tagList", "field_74747_a");
            java.util.List<?> a = (java.util.List<?>) contents.get(expected),
                b = (java.util.List<?>) contents.get(actual);
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) difference(
                path + "/[" + i + "]",
                i < a.size() ? (net.minecraft.nbt.NBTBase) a.get(i) : null,
                i < b.size() ? (net.minecraft.nbt.NBTBase) b.get(i) : null,
                out);
        } else out.add(path + " expected=" + typedValue(expected) + " actual=" + typedValue(actual));
    }

    private static String typedValue(net.minecraft.nbt.NBTBase value) {
        return value == null ? "<absent>" : "type" + value.getId() + ":" + value;
    }

    private NBTTagCompound actorInventory() {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList main = new NBTTagList(), armor = new NBTTagList();
        for (int i = 0; i < actor.inventory.mainInventory.length; i++)
            main.appendTag(actorStack(actor.inventory.mainInventory[i], i == heldSlot));
        for (ItemStack stack : actor.inventory.armorInventory) armor.appendTag(actorStack(stack, false));
        result.setTag("main", main);
        result.setTag("armor", armor);
        result.setTag("cursor", actorStack(actor.inventory.getItemStack(), false));
        return result;
    }

    private static NBTTagCompound actorStack(ItemStack value, boolean tool) {
        if (value == null) return new NBTTagCompound();
        NBTTagCompound result = value.writeToNBT(new NBTTagCompound());
        if (tool && result.hasKey("tag", 10)) {
            NBTTagCompound tags = result.getCompoundTag("tag");
            tags.removeTag("gtitHologramDraft");
            tags.removeTag("gtitHologramPreset");
            if (tags.hasNoTags()) result.removeTag("tag");
        }
        return result;
    }

    private static final class LibraryStamp {

        final boolean exists;
        final String digest;
        final FileTime modified;
        final long size;

        LibraryStamp(boolean exists, String digest, FileTime modified, long size) {
            this.exists = exists;
            this.digest = digest;
            this.modified = modified;
            this.size = size;
        }

        static LibraryStamp read(Path library) throws IOException {
            if (!Files.exists(library)) return new LibraryStamp(false, "absent", FileTime.fromMillis(0), 0);
            return new LibraryStamp(
                true,
                sha(Files.readAllBytes(library)),
                Files.getLastModifiedTime(library),
                Files.size(library));
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof LibraryStamp)) return false;
            LibraryStamp other = (LibraryStamp) value;
            return exists == other.exists && size == other.size
                && digest.equals(other.digest)
                && modified.equals(other.modified);
        }

        @Override
        public int hashCode() {
            return digest.hashCode();
        }
    }

    private static String sha(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;

import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Persistent native fixtures and a separate, explicitly admitted connected production-probe session. */
final class TestLabNativeTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private final boolean build;
    private final TestLabNativeCatalog catalog;
    private final Map<String, NBTTagCompound> manifests = new LinkedHashMap<>();
    private final TestLabNativeMachines machines;
    private int phase, page, cell, entry, solid, placed, unsupported;
    private long settleUntil;
    private TestLabZone source, target;
    private NBTTagCompound current;
    private NBTTagList entries;
    private boolean ready;
    private String currentPage = "";

    TestLabNativeTask(TestLabRuntime runtime, boolean build) throws Exception {
        this.runtime = runtime;
        this.build = build;
        Path input = runtime.config().nativeCatalogFile;
        if (input == null) catalog = new TestLabNativeCatalog(runtime.config().nativeMods);
        else {
            if (Files.isSymbolicLink(input) || !Files.isRegularFile(input) || Files.size(input) > 16 * 1024 * 1024)
                throw new IOException("Native client catalog must be a regular compressed NBT file <=16MiB");
            catalog = new TestLabNativeCatalog(
                runtime.config().nativeMods,
                HologramSnapshotCodec.decode(Files.readAllBytes(input)));
        }
        machines = new TestLabNativeMachines(runtime);
        if (runtime.config().nativeMods.isEmpty() && runtime.config().nativeMachines.isEmpty())
            throw new IOException("Select nativePlatformMods and/or nativePlatformMachines in local configuration");
    }

    public int step() throws Exception {
        if (phase == 0) {
            if (!catalog.step()) return TestLabTasks.CONTINUE;
            runtime.evidence().data.setInteger("nativeCatalogPages", catalog.pages.size());
            runtime.evidence().data.setInteger("nativeCatalogIssues", catalog.issues.tagCount());
            if (catalog.enumerationFailed) {
                runtime.ownership()
                    .nativeIndex(manifest());
                throw new IOException(
                    "Native creative enumeration failed; provide a complete real CLIENT-exported nativePlatformCatalogFile");
            }
            for (int i = 0; i < catalog.issues.tagCount(); i++) if ("no-registered-items".equals(
                catalog.issues.getCompoundTagAt(i)
                    .getString("kind"))) {
                        runtime.ownership()
                            .nativeIndex(manifest());
                        throw new IOException(
                            "Requested registry mod has no owned items: " + catalog.issues.getCompoundTagAt(i)
                                .getString("registry"));
                    }
            phase = 1;
            return TestLabTasks.CONTINUE;
        }
        if (phase == 1) {
            if (page == catalog.pages.size()) {
                phase = 7;
                return TestLabTasks.CONTINUE;
            }
            TestLabNativeCatalog.Page selected = catalog.pages.get(page);
            current = runtime.ownership()
                .nativePage(selected, runtime.config(), build);
            source = runtime.config()
                .nativeZone(selected.index, false);
            target = runtime.config()
                .nativeZone(selected.index, true);
            if ("READY".equals(current.getString("state"))) {
                manifests.put(selected.id, current);
                page++;
                return TestLabTasks.CONTINUE;
            }
            if (!build) throw new IOException("Native page is incomplete: " + selected.id);
            runtime.useNativeZones(source, source);
            currentPage = selected.id;
            entries = new NBTTagList();
            current.setTag("entries", entries);
            current.setString("state", "BUILDING");
            current.setString("kind", "native-mod-catalog");
            current.setInteger("dimension", runtime.config().dimension);
            current.setIntArray("first", new int[] { source.x, source.y, source.z });
            current.setIntArray("second", new int[] { source.x + 31, source.y + 15, source.z + 31 });
            // An interrupted page can be rebuilt under this same page claim. Completed pages are never cleared.
            cell = entry = solid = placed = unsupported = 0;
            phase = current.getBoolean("freshClaim") ? 3 : 2;
            current.removeTag("freshClaim");
            return TestLabTasks.CONTINUE;
        }
        if (phase == 2) {
            int[] at = source.cell(cell++);
            runtime.requireOwned(at[0], at[1], at[2], 1, 1, 1);
            TestLabFixtureWorld.clear(runtime.world(), at[0], at[1], at[2]);
            if (cell == source.cells()) {
                cell = 0;
                phase = 3;
            }
            return TestLabTasks.CONTINUE;
        }
        if (phase == 3) {
            runtime.world()
                .setBlock(source.x + cell % 32, source.y, source.z + cell / 32, Blocks.glass, 0, 2);
            if (++cell == 1024) {
                cell = 0;
                phase = 4;
            }
            return TestLabTasks.CONTINUE;
        }
        if (phase == 4) {
            if (entry == catalog.pages.get(page).stacks.size()) {
                phase = 5;
                cell = 0;
                settleUntil = runtime.ticks() + 40;
                return TestLabTasks.CONTINUE;
            }
            showcase(catalog.pages.get(page).stacks.get(entry), entry++);
            return TestLabTasks.CONTINUE;
        }
        if (phase == 5) {
            if (runtime.ticks() < settleUntil) return TestLabTasks.WAIT;
            if (cell < entries.tagCount()) {
                NBTTagCompound row = entries.getCompoundTagAt(cell++);
                if ("NATIVE_PLACED".equals(row.getString("placement"))) {
                    int[] p = row.getIntArray("position");
                    String block = Block.blockRegistry.getNameForObject(
                        runtime.world()
                            .getBlock(p[0], p[1], p[2]));
                    if (!row.getString("actualBlock")
                        .equals(block) || row.getInteger("actualMetadata")
                            != runtime.world()
                                .getBlockMetadata(p[0], p[1], p[2])) {
                        row.setString("placement", "NATIVE_PLACEMENT_UNSTABLE");
                        placed--;
                        unsupported++;
                    }
                }
                return TestLabTasks.CONTINUE;
            }
            cell = 0;
            phase = 6;
        }
        if (phase == 6) {
            int[] at = source.cell(cell++);
            if (!runtime.world()
                .isAirBlock(at[0], at[1], at[2])) solid++;
            if (cell == source.cells()) {
                current.setInteger("expectedSolid", solid);
                current.setInteger("nativePlaced", placed);
                current.setInteger("unsupportedPlacement", unsupported);
                current.setString("state", "READY");
                current.setString("blueprintResult", "NOT_RUN");
                current.setString(
                    "sourceFingerprintScope",
                    "registered catalog stacks, not a claim of unchanged live tile NBT");
                TestLabFixtureWorld.finish(runtime.world(), source);
                runtime.world().theChunkProviderServer.saveChunks(true, null);
                runtime.ownership()
                    .nativeManifest(page, current);
                manifests.put(current.getString("id"), (NBTTagCompound) current.copy());
                runtime.evidence()
                    .count("nativeBuiltPages", 1);
                page++;
                phase = 1;
            }
            return TestLabTasks.CONTINUE;
        }
        if (phase == 7) {
            if (build) {
                int result = machines.buildStep();
                if (result != TestLabTasks.DONE) return result;
            }
            ready = true;
            phase = 8;
            if (build) {
                runtime.ownership()
                    .nativeIndex(manifest());
                runtime.evidence()
                    .check(
                        "native_platform_construction",
                        "PASS",
                        "Persistent native displays constructed/reused; individual unsupported items remain in manifests; blueprint workflow NOT_RUN");
                return TestLabTasks.DONE;
            }
            runtime.evidence().data.setString("nativeSession", "READY_FOR_PRODUCTION_PROBE");
        }
        return TestLabTasks.WAIT;
    }

    private void showcase(ItemStack original, int index) throws IOException {
        NBTTagCompound row = new NBTTagCompound();
        String registry = Item.itemRegistry.getNameForObject(original.getItem());
        row.setInteger("index", index);
        row.setString("registry", registry);
        row.setString("mod", TestLabNativeCatalog.mod(registry));
        row.setTag("stack", TestLabNativeCatalog.stack(original));
        int chestIndex = index / 27, slot = index % 27;
        int cx = source.x + 1 + chestIndex * 3, cy = source.y + 1, cz = source.z + 23;
        if (slot == 0) {
            if (!runtime.world()
                .setBlock(cx, cy, cz, Blocks.chest, 2, 3))
                throw new IOException("Native showcase chest placement refused");
        }
        TileEntity carrier = runtime.world()
            .getTileEntity(cx, cy, cz);
        if (!(carrier instanceof TileEntityChest)) throw new IOException("Native showcase chest tile missing");
        ((TileEntityChest) carrier).setInventorySlotContents(slot, original.copy());
        carrier.markDirty();
        row.setIntArray("container", new int[] { cx, cy, cz });
        row.setInteger("slot", slot);
        int x = source.x + 1 + index % 16 * 2, y = source.y + 1, z = source.z + 1 + index / 16 * 2;
        row.setIntArray("position", new int[] { x, y, z });
        if (original.getItem() instanceof ItemBlock && Block.getBlockFromItem(original.getItem())
            .getMaterial()
            .isLiquid()) {
            row.setString("placement", "ITEM_ONLY_DYNAMIC_FLUID");
            row.setString(
                "placementDetail",
                "Exact native stack retained; flowing/hot fluid is not released onto a shared display page");
            unsupported++;
        } else if (!(original.getItem() instanceof ItemBlock)) {
            row.setString("placement", "ITEM_ONLY_NATIVE_INVENTORY");
            row.setString(
                "placementDetail",
                "Exact stack retained in native chest; arbitrary item activation is not invoked");
        } else {
            int oldSlot = runtime.actor().player.inventory.currentItem;
            ItemStack old = runtime.actor().player.inventory.getStackInSlot(1);
            ItemStack held = original.copy();
            runtime.actor().player.inventory.setInventorySlotContents(1, held);
            runtime.actor().player.inventory.currentItem = 1;
            try {
                // Use the real registered stack and its native placement callback. No setBlock substitute on failure.
                boolean accepted = held
                    .tryPlaceItemIntoWorld(runtime.actor().player, runtime.world(), x, y - 1, z, 1, 0.5F, 1.0F, 0.5F);
                Block block = runtime.world()
                    .getBlock(x, y, z);
                boolean persisted = block != Blocks.air;
                row.setBoolean("nativeAccepted", accepted);
                row.setString("actualBlock", Block.blockRegistry.getNameForObject(block));
                row.setInteger(
                    "actualMetadata",
                    runtime.world()
                        .getBlockMetadata(x, y, z));
                row.setString("placement", accepted && persisted ? "NATIVE_PLACED" : "NATIVE_PLACEMENT_UNSUPPORTED");
                if (accepted && persisted) placed++;
                else unsupported++;
            } catch (RuntimeException | LinkageError failure) {
                row.setString("placement", "NATIVE_PLACEMENT_ERROR");
                row.setString("placementDetail", failure.toString());
                unsupported++;
            } finally {
                runtime.actor().player.inventory.setInventorySlotContents(1, old);
                runtime.actor().player.inventory.currentItem = oldSlot;
            }
        }
        entries.appendTag(row);
    }

    boolean owns(TestLabZone zone) {
        for (TestLabNativeCatalog.Page page : catalog.pages) {
            TestLabZone a = runtime.config()
                .nativeZone(page.index, false);
            TestLabZone b = runtime.config()
                .nativeZone(page.index, true);
            if (same(zone, a) || same(zone, b)) return true;
        }
        return machines.owns(zone);
    }

    boolean contains(int x, int y, int z, int sx, int sy, int sz) {
        return (source != null && source.contains(x, y, z, sx, sy, sz))
            || (target != null && target.contains(x, y, z, sx, sy, sz))
            || machines.contains(x, y, z, sx, sy, sz);
    }

    private static boolean same(TestLabZone a, TestLabZone b) {
        return a.id.equals(b.id) && a.x == b.x
            && a.y == b.y
            && a.z == b.z
            && a.sizeX == b.sizeX
            && a.sizeY == b.sizeY
            && a.sizeZ == b.sizeZ;
    }

    NBTTagCompound manifest() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("fixtureVersion", "native-platforms-v1");
        tag.setString("state", ready ? "READY" : "PREPARING");
        tag.setInteger("dimension", runtime.config().dimension);
        tag.setString(
            "runId",
            runtime.status()
                .getString("runId"));
        tag.setString("actor", runtime.actor().id.toString());
        tag.setString("currentPage", currentPage);
        NBTTagList pages = new NBTTagList();
        for (TestLabNativeCatalog.Page page : catalog.pages) {
            NBTTagCompound summary = page.summary(runtime.config());
            NBTTagCompound built = manifests.get(page.id);
            summary.setString("state", built == null ? "NOT_BUILT" : built.getString("state"));
            pages.appendTag(summary);
        }
        NBTTagList machinePages = machines.manifest()
            .getTagList("pages", 10);
        for (int i = 0; i < machinePages.tagCount(); i++) pages.appendTag(
            machinePages.getCompoundTagAt(i)
                .copy());
        tag.setTag("pages", pages);
        tag.setTag("issues", catalog.issues.copy());
        tag.setString("blueprintResult", "NOT_RUN");
        return tag;
    }

    NBTTagCompound pageManifest(String id) throws IOException {
        NBTTagCompound tag = manifests.get(id);
        return tag == null ? machines.pageManifest(id) : (NBTTagCompound) tag.copy();
    }

    void useFacility(String id) throws IOException {
        if (build || !ready) throw new IOException("Native-test session is not ready");
        int colon = id.lastIndexOf(':');
        if (colon < 1) throw new IOException("Native facility must be page-id:source or page-id:target");
        String selected = id.substring(0, colon), facility = id.substring(colon + 1);
        if (!("source".equals(facility) || "target".equals(facility))) throw new IOException("Unknown native facility");
        for (TestLabNativeCatalog.Page page : catalog.pages) if (page.id.equals(selected)) {
            NBTTagCompound built = manifests.get(selected);
            if (built == null || !"READY".equals(built.getString("state")))
                throw new IOException("Native platform was not built: " + selected);
            source = runtime.config()
                .nativeZone(page.index, false);
            target = runtime.config()
                .nativeZone(page.index, true);
            runtime.useNativeZones(source, source, target);
            currentPage = selected;
            return;
        }
        machines.useFacility(id);
        currentPage = selected;
        source = target = null;
    }

    public void cleanup() {
        // Persistent fixtures are deliberately retained. Runtime releases leases and restores the real actor.
    }
}

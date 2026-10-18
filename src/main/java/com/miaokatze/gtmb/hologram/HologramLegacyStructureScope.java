package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Recovers only verified plain cells of a legacy member from the immutable editor scene, never a world or box. */
final class HologramLegacyStructureScope {

    private static final Map<HologramCapture, Map<String, Proof>> VERIFIED = new WeakHashMap<>();
    final Set<String> recovered = new HashSet<>();
    private final HologramSession session;
    private final HologramPresets.Structure source;
    private final Scene scene;
    private final HologramAreaSignalInference inference;
    private int cursor;
    private boolean inferred;

    static boolean needed(HologramSession session, HologramPresets.Structure source, String tile, String item,
        int facing) {
        Map<String, Proof> entries = VERIFIED.get(session.capture);
        Proof proof = entries == null ? null : entries.get(source.id);
        if (proof != null && proof.matches(source, tile, item, facing)) return false;
        if (source.main > 1 || source.mainExplicit || !source.channels.isEmpty()) return false;
        // A new controller-only template has no forgotten literal geometry to recover.
        boolean ownedBody = false, unassignedBody = false;
        for (HologramCapture.Cell cell : session.capture.cells) {
            if (cell.anchor || "templateController".equals(cell.role)
                || !cell.userPosition
                || cell.unknown
                || cell.block == null
                || cell.block == Blocks.air) continue;
            ownedBody |= cell.belongsTo(source.id);
            unassignedBody |= cell.memberships()
                .isEmpty();
            if (ownedBody && unassignedBody) return true;
        }
        return false;
    }

    static void verified(HologramCapture capture, HologramPresets.Structure source, String tile, String item,
        int facing) {
        VERIFIED.computeIfAbsent(capture, ignored -> new HashMap<>())
            .put(source.id, new Proof(source, tile, item, facing));
    }

    HologramLegacyStructureScope(HologramSession session, HologramCapture original, HologramPresets.Structure source,
        MTEEnhancedMultiBlockBase<?> owned, ItemStack seed) throws IOException {
        this.session = session;
        this.source = source;
        scene = new Scene(session, original, source);
        inference = HologramAreaSignalInference.detachedScene(owned, seed, scene);
    }

    boolean step(long deadline) throws IOException {
        if (!inferred) {
            if (!inference.step(deadline)) return false;
            if (!inference.verifiedScope()) throw new IOException("无法唯一核实旧蓝图的完整结构范围；原图已保留，请从完整原结构重新导入");
            inferred = true;
        }
        HologramCapture complete = inference.capture();
        for (int work = 0; cursor < complete.cells.size(); cursor++, work++) {
            if (work > 0 && (work >= 4096 || System.nanoTime() >= deadline)) return false;
            HologramCapture.Cell declaration = complete.cells.get(cursor);
            HologramCapture.Cell saved = scene.cell(declaration.x, declaration.y, declaration.z);
            if (saved == null || saved.belongsTo(source.id)
                || !saved.memberships()
                    .isEmpty()
                || session.virtualEdited.contains(HologramValues.position(saved))) continue;
            ItemStack material = scene.material(declaration.x, declaration.y, declaration.z);
            boolean plainAir = saved.block == Blocks.air && !saved.unknown
                && saved.tileNbt.isEmpty()
                && saved.itemNbt.isEmpty()
                && saved.blueprintItem.isEmpty()
                && (saved.blueprintBlock.isEmpty() || "minecraft:air".equals(saved.blueprintBlock));
            if (plainAir || HologramBlueprintSourceMaterial.plainReplay(session, saved, material, scene.validation))
                recovered.add(HologramValues.position(saved));
        }
        return true;
    }

    void cancel() {
        inference.cancel();
    }

    long completed() {
        return inferred ? cursor
            : inference.diagnostics()
                .getLong("comparedCells");
    }

    long total() {
        return inferred ? inference.capture().cells.size() : 0;
    }

    private static final class Scene implements HologramAreaSignalInference.Scene {

        final HologramCapture capture;
        final int x, y, z;
        final Map<String, ItemStack> materials = new HashMap<>();
        final HologramBlueprintValidationCache validation;

        Scene(HologramSession session, HologramCapture capture, HologramPresets.Structure source) {
            this.capture = capture;
            validation = new HologramBlueprintValidationCache(session.virtualPreset);
            x = session.x + source.x;
            y = session.y + source.y - 128;
            z = session.z + source.z;
        }

        HologramCapture.Cell cell(int xx, int yy, int zz) {
            return capture.positions.get((x + xx) + "," + (y + yy) + "," + (z + zz));
        }

        public boolean exists(int xx, int yy, int zz) {
            HologramCapture.Cell cell = cell(xx, yy, zz);
            return cell == null || !cell.unknown;
        }

        public Block block(int xx, int yy, int zz) {
            HologramCapture.Cell cell = cell(xx, yy, zz);
            return cell == null || cell.block == null ? Blocks.air : cell.block;
        }

        public int metadata(int xx, int yy, int zz) {
            HologramCapture.Cell cell = cell(xx, yy, zz);
            return cell == null ? 0 : cell.meta;
        }

        public boolean air(int xx, int yy, int zz) {
            HologramCapture.Cell cell = cell(xx, yy, zz);
            return cell == null
                || cell.block == Blocks.air && !cell.unknown && cell.tileNbt.isEmpty() && cell.itemNbt.isEmpty();
        }

        public ItemStack material(int xx, int yy, int zz) {
            HologramCapture.Cell cell = cell(xx, yy, zz);
            if (cell == null || cell.unknown || cell.blueprintItem.isEmpty()) return null;
            String key = cell.blueprintBlock + ":" + cell.meta + ":" + cell.blueprintItem + ":" + cell.blueprintDamage;
            if (materials.containsKey(key)) return materials.get(key);
            ItemStack sample = null;
            if (cell.blueprintBlock.equals(Block.blockRegistry.getNameForObject(cell.block))) {
                Object item = Item.itemRegistry.getObject(cell.blueprintItem);
                if (!(item instanceof Item) || cell.blueprintDamage < 0) return null;
                sample = new ItemStack((Item) item, 1, cell.blueprintDamage);
                try {
                    if (sample.getItem() instanceof ItemMachines
                        && cell.block != Block.getBlockFromItem(sample.getItem())) sample = null;
                    else HologramPresets.validateMaterialVariant(cell.block, cell.meta, sample, validation);
                } catch (IOException | RuntimeException | LinkageError invalidIdentity) {
                    sample = null;
                }
            }
            materials.put(key, sample);
            return sample;
        }

        public IMetaTileEntity machine(int xx, int yy, int zz) {
            ItemStack sample = material(xx, yy, zz);
            if (sample == null || !(sample.getItem() instanceof ItemMachines)
                || block(xx, yy, zz) != Block.getBlockFromItem(sample.getItem())) return null;
            int id = sample.getItemDamage();
            // Only a type predicate sees the registration object; no construct/check/load/save method runs on it.
            return id >= 0 && id < GregTechAPI.METATILEENTITIES.length ? GregTechAPI.METATILEENTITIES[id] : null;
        }
    }

    private static final class Proof {

        final HologramPresets.Structure source;
        final String tile, item;
        final int facing;

        Proof(HologramPresets.Structure source, String tile, String item, int facing) {
            this.source = HologramBlueprintData.copyStructure(source);
            this.tile = tile;
            this.item = item;
            this.facing = facing;
        }

        boolean matches(HologramPresets.Structure current, String tile, String item, int facing) {
            return this.facing == facing && this.tile.equals(tile)
                && this.item.equals(item)
                && source.id.equals(current.id)
                && source.controllerId == current.controllerId
                && java.util.Objects.equals(source.sourceClass, current.sourceClass)
                && source.x == current.x
                && source.y == current.y
                && source.z == current.z
                && source.main == current.main
                && source.mainExplicit == current.mainExplicit
                && source.noHatches == current.noHatches
                && source.channels.equals(current.channels);
        }
    }
}

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBeacon;
import net.minecraft.block.BlockSponge;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import bartworks.system.material.TileEntityMetaGeneratedBlock;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemMachines;

/** A frozen, reviewed declaration. It never delegates to a GTNL element or placement predicate. */
final class HologramGTNLElement implements IStructureElement<Object> {

    enum Kind {
        BLOCK,
        HATCH,
        COIL,
        BEACON,
        SPONGE
    }

    final String id;
    final String channel;
    final int tier;
    final int coreRow, coreCol;
    private final Kind kind;
    private final List<ItemStack> candidates;
    private final List<ItemStack> placement;
    private final boolean complete;

    HologramGTNLElement(String id, Kind kind, List<ItemStack> candidates, List<ItemStack> placement, boolean complete,
        String channel, int tier, int coreRow, int coreCol) {
        if (id == null || candidates == null || candidates.isEmpty())
            throw new IllegalArgumentException("Empty GTNL declaration");
        this.id = id;
        this.kind = kind;
        this.candidates = freeze(candidates);
        this.placement = freeze(placement);
        this.complete = complete;
        this.channel = channel;
        this.tier = tier;
        this.coreRow = coreRow;
        this.coreCol = coreCol;
    }

    HologramGTNLElement(String id, List<ItemStack> candidates, boolean complete) {
        this(id, Kind.BLOCK, candidates, candidates, complete, "", -1, -1, -1);
    }

    private static List<ItemStack> freeze(List<ItemStack> input) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : input) {
            if (stack == null || stack.getItem() == null || stack.getItemDamage() < 0)
                throw new IllegalArgumentException("Invalid GTNL material");
            ItemStack owned = stack.copy();
            owned.stackSize = 1;
            if (!contains(result, owned)) result.add(owned);
        }
        return Collections.unmodifiableList(result);
    }

    List<ItemStack> candidates() {
        return freeze(candidates);
    }

    ItemStack primary() {
        return candidates.get(0)
            .copy();
    }

    boolean complete() {
        return complete;
    }

    String role() {
        return kind == Kind.HATCH ? "hatch" : "block";
    }

    boolean accepts(ItemStack sample) {
        return contains(candidates, sample);
    }

    /** Static automatic-placement subset only; machine-wide counts are checked separately. */
    boolean placementAllows(ItemStack sample) {
        return contains(placement, sample);
    }

    private static boolean contains(List<ItemStack> entries, ItemStack sample) {
        if (sample == null || sample.getItem() == null) return false;
        for (ItemStack candidate : entries)
            if (candidate.getItem() == sample.getItem() && candidate.getItemDamage() == sample.getItemDamage())
                return true;
        return false;
    }

    /** Structure damage is deliberately not truncated to a nibble (e.g. the debug core is damage 18). */
    int targetMeta(ItemStack sample) {
        return sample.getItem()
            .getMetadata(sample.getItemDamage());
    }

    void describeCell(HologramCapture.Cell cell) {
        ItemStack sample = primary();
        cell.block = Block.getBlockFromItem(sample.getItem());
        cell.meta = targetMeta(sample);
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
        cell.blueprintItem = String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem()));
        cell.blueprintDamage = sample.getItemDamage();
        cell.role = role();
        cell.unknown = false;
        cell.candidateComplete = complete;
        cell.candidateBoundary = complete ? "" : "仅列出本机已审阅候选，其他扩展未获得自动施工资格";
    }

    boolean matchesWorld(World world, int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) return false;
        Block block = world.getBlock(x, y, z);
        if (kind == Kind.BEACON)
            return block instanceof BlockBeacon && world.getTileEntity(x, y, z) instanceof TileEntityBeacon;
        if (kind == Kind.SPONGE && block instanceof BlockSponge) return true;
        if (world.getTileEntity(x, y, z) instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) world.getTileEntity(x, y, z);
            return accepts(new ItemStack(gregtech.api.GregTechAPI.sBlockMachines, 1, gt.getMetaTileID()));
        }
        int damage = world.getBlockMetadata(x, y, z);
        // BW stores the full material ID in this reviewed TE field, not in world metadata.
        if (world.getTileEntity(x, y, z) instanceof TileEntityMetaGeneratedBlock)
            damage = ((TileEntityMetaGeneratedBlock) world.getTileEntity(x, y, z)).mMetaData;
        if (kind == Kind.COIL && block == gregtech.api.GregTechAPI.sBlockCasings5)
            damage = BlockCasings5.getMetaFromCoilHeat(BlockCasings5.getCoilHeatFromDamage(damage));
        return acceptsWorldIdentity(block, damage);
    }

    boolean matchesTarget(World world, int x, int y, int z, ItemStack target) {
        if (!accepts(target) || world == null || !world.blockExists(x, y, z)) return false;
        Block expected = Block.getBlockFromItem(target.getItem());
        if (world.getBlock(x, y, z) != expected) return false;
        if (target.getItem() instanceof ItemMachines) {
            return world.getTileEntity(x, y, z) instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) world.getTileEntity(x, y, z)).getMetaTileID() == target.getItemDamage();
        }
        if (kind == Kind.BEACON && !(world.getTileEntity(x, y, z) instanceof TileEntityBeacon)) return false;
        int actual = world.getBlockMetadata(x, y, z);
        if (world.getTileEntity(x, y, z) instanceof TileEntityMetaGeneratedBlock)
            actual = ((TileEntityMetaGeneratedBlock) world.getTileEntity(x, y, z)).mMetaData;
        if (kind == Kind.COIL && expected == gregtech.api.GregTechAPI.sBlockCasings5)
            actual = BlockCasings5.getMetaFromCoilHeat(BlockCasings5.getCoilHeatFromDamage(actual));
        return actual == targetMeta(target);
    }

    private boolean acceptsWorldIdentity(Block block, int damage) {
        for (ItemStack candidate : candidates)
            if (!(candidate.getItem() instanceof ItemMachines) && Block.getBlockFromItem(candidate.getItem()) == block
                && targetMeta(candidate) == damage) return true;
        return false;
    }

    @Override
    public boolean check(Object context, World world, int x, int y, int z) {
        return matchesWorld(world, x, y, z);
    }

    @Override
    public boolean couldBeValid(Object context, World world, int x, int y, int z, ItemStack trigger) {
        return matchesWorld(world, x, y, z);
    }

    @Override
    public boolean spawnHint(Object context, World world, int x, int y, int z, ItemStack trigger) {
        ItemStack sample = primary();
        com.gtnewhorizon.structurelib.StructureLibAPI
            .hintParticle(world, x, y, z, Block.getBlockFromItem(sample.getItem()), targetMeta(sample));
        return true;
    }

    @Override
    public BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z, ItemStack trigger,
        AutoPlaceEnvironment environment) {
        return BlocksToPlace.create(primary());
    }

    @Override
    public boolean placeBlock(Object context, World world, int x, int y, int z, ItemStack trigger) {
        throw new UnsupportedOperationException("GTNL declarations do not execute world placement");
    }

    @Override
    public List<String> getDescription(Object context) {
        return Collections.singletonList(id);
    }
}

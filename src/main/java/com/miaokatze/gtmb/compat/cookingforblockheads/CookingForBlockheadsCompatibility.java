// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.compat.cookingforblockheads;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.api.BlockTransforms;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativeCapture;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

import cpw.mods.fml.common.Loader;

/** Cooking For Blockheads 1.4.14: local persistence only; never follows a shared kitchen inventory. */
public final class CookingForBlockheadsCompatibility {

    private static final String PREFIX = "net.blay09.mods.cookingforblockheads.";
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    private static final List<GTMBExtensions.Registration> HANDLES = new ArrayList<>();
    private static final String[] SINK = { "FluidName", "Amount", "Tag", "Empty" };
    private static final String[] OVEN = { "BurnTime", "CurrentItemBurnTime", "CookTimes" };

    static {
        TYPES.put("cookingtable", "CookingTable");
        TYPES.put("cookingoven", "Oven");
        TYPES.put("counter", "Counter");
        TYPES.put("countercorner", "CounterCorner");
        TYPES.put("cabinet", "Cabinet");
        TYPES.put("cabinetcorner", "CabinetCorner");
        TYPES.put("fridge", "Fridge");
        TYPES.put("sink", "Sink");
        TYPES.put("toolrack", "ToolRack");
        TYPES.put("toaster", "Toaster");
    }

    private CookingForBlockheadsCompatibility() {}

    public static synchronized void initialize() {
        if (!HANDLES.isEmpty() || !Loader.isModLoaded("cookingforblockheads")) return;
        register();
    }

    static synchronized void register() {
        if (!HANDLES.isEmpty()) return;
        try {
            HANDLES.add(NativeCapture.register("gtmb:cfb/capture", new NativeCapture.Adapter() {

                public boolean supports(TileEntity tile) {
                    return tileName(tile) != null;
                }

                public NBTTagCompound capture(TileEntity tile) throws IOException {
                    // All ten exact serializers are read-only. BaseKitchenTileWithInventory writes
                    // internalInventory, whereas its IInventory methods can expose sharedInventory.
                    NBTTagCompound tag = new NBTTagCompound();
                    tile.writeToNBT(tag);
                    validate(tileName(tile), tag);
                    return tag;
                }
            }));
            HANDLES.add(NativePlacement.register("gtmb:cfb/placement", new NativePlacement.Adapter() {

                public boolean requiresPayload() {
                    return true;
                }

                public boolean supports(Block block) {
                    return blockName(block) != null;
                }

                public TileEntity create(Block block, int meta, NBTTagCompound tag, World world) throws Exception {
                    String name = blockName(block);
                    validate(name, tag);
                    requireFacing(meta);
                    TileEntity tile = Class.forName(PREFIX + "tile.Tile" + TYPES.get(name))
                        .asSubclass(TileEntity.class)
                        .getConstructor()
                        .newInstance();
                    // These reviewed readers need no world. Leave it unset so setWorldObj cannot
                    // allocate render EntityItems before the engine performs the real installation.
                    NBTTagCompound selected = (NBTTagCompound) tag.copy();
                    // Native TileOven replaces its nine default timers even when the field was omitted.
                    if (name.equals("cookingoven") && !selected.hasKey("CookTimes"))
                        selected.setIntArray("CookTimes", new int[9]);
                    tile.readFromNBT(selected);
                    return tile;
                }

                public List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                    // CFB's physical shell items have damage 0. Orientation is world metadata only.
                    return Collections.singletonList(new ItemStack(block, 1, 0));
                }
            }));
            HANDLES.add(GTMBExtensions.nativeDisplay("gtmb:cfb/display", tile -> tileName(tile) != null));
            HANDLES.add(BlockTransforms.register("gtmb:cfb/rotation", new BlockTransforms.Adapter() {

                public boolean accepts(BlockTransforms.State state) {
                    return blockName(state.block) != null;
                }

                public void rotate(BlockTransforms.State state, boolean vertical) throws IOException {
                    if (vertical) throw new IOException("CFB kitchen blocks support horizontal rotation only");
                    requireFacing(state.metadata);
                    switch (state.metadata) {
                        case 2:
                            state.metadata = 5;
                            break;
                        case 5:
                            state.metadata = 3;
                            break;
                        case 3:
                            state.metadata = 4;
                            break;
                        case 4:
                            state.metadata = 2;
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
            }));
            HANDLES.add(ResourceContainers.registerRoot("gtmb:cfb/sink-water", context -> {
                if (!nativeId(context.nativeTileId, "sink") || !context.selectedGroup(SINK)) return null;
                NBTTagCompound root = context.root();
                List<ResourceContainers.FluidAmount> fluids = new ArrayList<>();
                boolean empty = root.hasKey("Empty");
                if (empty) {
                    if (!root.hasKey("Empty", 8) || !root.getString("Empty")
                        .isEmpty() || root.hasKey("FluidName") || root.hasKey("Amount") || root.hasKey("Tag"))
                        throw new IOException("Malformed CFB empty sink tank");
                } else {
                    if (!root.hasKey("FluidName", 8) || !root.hasKey("Amount", 3)
                        || root.hasKey("Tag") && !root.hasKey("Tag", 10))
                        throw new IOException("Incomplete CFB sink fluid identity/amount/tag");
                    int amount = root.getInteger("Amount");
                    FluidStack fluid = FluidStack.loadFluidStackFromNBT(root);
                    if (fluid == null || !root.getString("FluidName")
                        .equals("water") || amount < 0 || amount > 16000)
                        throw new IOException("CFB sink requires finite serialized water in [0,16000] mB");
                    fluids.add(new ResourceContainers.FluidAmount(fluid, amount));
                }
                return claim(SINK, Collections.emptyList(), fluids);
            }));
            HANDLES.add(ResourceContainers.registerRoot("gtmb:cfb/table-book", context -> {
                if (!nativeId(context.nativeTileId, "cookingtable") || !context.selectedGroup("NoFilterBook"))
                    return null;
                NBTTagCompound root = context.root();
                if (!root.hasKey("NoFilterBook", 10)) throw new IOException("CFB NoFilterBook must be a compound");
                NBTTagCompound book = root.getCompoundTag("NoFilterBook");
                List<ItemStack> items = new ArrayList<>();
                if (!book.hasNoTags()) items.add(readItem(book, false));
                return claim(new String[] { "NoFilterBook" }, items, Collections.emptyList());
            }));
            HANDLES.add(ResourceContainers.registerRoot("gtmb:cfb/oven-state", context -> {
                if (!nativeId(context.nativeTileId, "cookingoven") || !context.selectedGroup(OVEN)) return null;
                NBTTagCompound root = context.root();
                checkOven(root);
                if (root.getInteger("BurnTime") != 0)
                    throw new IOException("CFB oven has unpriced remaining fuel; safe blueprint requires an idle oven");
                // Native -1 means the already-paid slot item finished cooking, not saved progress.
                for (int time : root.getIntArray("CookTimes")) if (time > 0)
                    throw new IOException("CFB oven has cooking progress; safe blueprint requires an idle oven");
                return claim(OVEN, Collections.emptyList(), Collections.emptyList());
            }));
        } catch (RuntimeException | LinkageError failure) {
            try {
                close();
            } catch (RuntimeException | LinkageError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static synchronized void close() {
        for (int i = HANDLES.size() - 1; i >= 0; i--) {
            HANDLES.get(i)
                .close();
            HANDLES.remove(i);
        }
    }

    public static String blockName(Block block) {
        if (block == null) return null;
        String registry = Block.blockRegistry.getNameForObject(block);
        if (registry == null || !registry.startsWith("cookingforblockheads:")) return null;
        String name = registry.substring("cookingforblockheads:".length());
        String type = TYPES.get(name);
        return type != null && block.getClass()
            .getName()
            .equals(PREFIX + "block.Block" + type) ? name : null;
    }

    public static String tileName(TileEntity tile) {
        if (tile == null) return null;
        String type = tile.getClass()
            .getName();
        for (Map.Entry<String, String> entry : TYPES.entrySet())
            if (type.equals(PREFIX + "tile.Tile" + entry.getValue())) return entry.getKey();
        return null;
    }

    public static boolean nativeId(String id, String name) {
        return id.equals("cookingforblockheads:" + name) || id.equals("cookingbook:" + name);
    }

    private static ResourceContainers.RootClaim claim(String[] keys, List<ItemStack> items,
        List<ResourceContainers.FluidAmount> fluids) {
        return new ResourceContainers.RootClaim(Arrays.asList(keys), items, fluids, BigInteger.ZERO);
    }

    private static void requireFacing(int meta) throws IOException {
        if (meta < 2 || meta > 5) throw new IOException("Invalid CFB horizontal facing: " + meta);
    }

    /** Checks before native readers which otherwise silently discard out-of-range slots and unknown roots. */
    static void validate(String name, NBTTagCompound root) throws IOException {
        if (name == null || !nativeId(root.getString("id"), name)) throw new IOException("CFB tile identity mismatch");
        List<String> allowed = new ArrayList<>(Arrays.asList("id", "x", "y", "z", "gtmb.nativePreview"));
        boolean inventory = !name.equals("cookingtable") && !name.equals("sink");
        if (inventory) allowed.add("Items");
        if (!name.equals("cookingoven") && !name.equals("toolrack") && !name.equals("toaster")) allowed.add("Color");
        if (Arrays.asList("fridge", "counter", "countercorner", "cabinet", "cabinetcorner")
            .contains(name)) allowed.add("IsFlipped");
        if (name.equals("sink")) allowed.addAll(Arrays.asList(SINK));
        if (name.equals("cookingtable")) allowed.add("NoFilterBook");
        if (name.equals("cookingoven")) allowed.addAll(Arrays.asList(OVEN));
        for (Object key : root.func_150296_c())
            if (!allowed.contains(key)) throw new IOException("Unreviewed CFB persistence root: " + key);
        if (root.hasKey("Color")
            && (!root.hasKey("Color", 1) || root.getByte("Color") < 0 || root.getByte("Color") > 15))
            throw new IOException("Invalid CFB dye color");
        if (root.hasKey("IsFlipped")
            && (!root.hasKey("IsFlipped", 1) || root.getByte("IsFlipped") < 0 || root.getByte("IsFlipped") > 1))
            throw new IOException("Invalid CFB door flip");
        if (inventory && root.hasKey("Items")) {
            if (!root.hasKey("Items", 9)) throw new IOException("CFB Items must be a list");
            NBTTagList rows = root.getTagList("Items", 10);
            if (((NBTTagList) root.getTag("Items")).tagCount() != rows.tagCount())
                throw new IOException("CFB Items must contain compounds");
            int slots = name.equals("cookingoven") ? 20 : name.equals("toolrack") || name.equals("toaster") ? 2 : 27;
            boolean[] seen = new boolean[slots];
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                if (!row.hasKey("Slot", 1)) throw new IOException("CFB item omitted byte slot");
                int slot = row.getByte("Slot");
                if (slot < 0 || slot >= slots || seen[slot])
                    throw new IOException("Duplicate or invalid CFB item slot");
                seen[slot] = true;
                readItem(row, true);
            }
        }
        if (name.equals("cookingoven")) checkOven(root);
    }

    private static ItemStack readItem(NBTTagCompound row, boolean slot) throws IOException {
        for (Object key : row.func_150296_c()) if (!Arrays.asList("id", "idExt", "Count", "Damage", "tag")
            .contains(key) && !(slot && key.equals("Slot"))) throw new IOException("Unreviewed CFB item field: " + key);
        // GTNH 2.9's EndlessIDs 1.7.4 DataUtil reads idExt:int first, then id:int/id:short.
        // Its writer uses id:short=0 plus idExt:int for IDs above 31999.
        if (row.hasKey("id") && !row.hasKey("id", 2) && !row.hasKey("id", 3)
            || row.hasKey("idExt") && !row.hasKey("idExt", 3)
            || !row.hasKey("idExt", 3) && !row.hasKey("id", 2) && !row.hasKey("id", 3)
            || !row.hasKey("Count", 1)
            || !row.hasKey("Damage", 2)
            || row.hasKey("tag") && !row.hasKey("tag", 10)) throw new IOException("Malformed CFB physical item");
        int id = NativeItemIds.read(row);
        Item identity = Item.getItemById(id);
        int count = row.getByte("Count"), damage = row.getShort("Damage");
        if (identity == null || count <= 0 || damage < 0) throw new IOException("Unresolvable CFB physical item");
        ItemStack item = new ItemStack(identity, count, damage);
        if (row.hasKey("tag", 10)) item.setTagCompound(
            (NBTTagCompound) row.getCompoundTag("tag")
                .copy());
        return item;
    }

    private static void checkOven(NBTTagCompound root) throws IOException {
        for (String key : new String[] { "BurnTime", "CurrentItemBurnTime" })
            if (root.hasKey(key) && (!root.hasKey(key, 3) || root.getInteger(key) < 0))
                throw new IOException("Invalid CFB oven runtime field: " + key);
        if (root.hasKey("CookTimes") && (!root.hasKey("CookTimes", 11) || root.getIntArray("CookTimes").length != 9))
            throw new IOException("CFB oven requires exactly nine cooking timers");
        for (int time : root.getIntArray("CookTimes"))
            if (time < -1) throw new IOException("Invalid CFB cooking timer");
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Explicit invisible declarations are handled without tile factories, GL lists or neighbor-empty acceptance. */
public final class NativeInvisibleRegression {

    private NativeInvisibleRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(NativeInvisibleRegression.class, args)) return;
        ClientRendering.Adapter ordinary = block -> block == Blocks.stone;
        SimpleAssert.that(!ordinary.invisible(Blocks.stone, 0), "old adapters retain strict visible policy");
        ClientRendering.Adapter carrier = new ClientRendering.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.stone;
            }

            public boolean invisible(Block block, int meta) {
                return meta == 0;
            }

            public TileEntity createTile(Block block, int meta, World world) {
                throw new AssertionError("invisible tile factory called");
            }
        };
        NBTTagCompound data = new NBTTagCompound(), cell = new NBTTagCompound(), display = new NBTTagCompound();
        cell.setString("id", "minecraft:stone");
        cell.setString("wantId", "minecraft:stone");
        cell.setString("status", "pending");
        NBTTagCompound palette = new NBTTagCompound();
        palette.setFloat("col0_0", .73f);
        display.setTag("nativeTile", palette);
        cell.setTag("actualDisplay", display.copy());
        cell.setTag("targetDisplay", display.copy());
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        data.setTag("cells", cells);
        HologramState state = new HologramState(data);
        NBTTagCompound original = state.fullData();
        try (GTMBExtensions.Registration ignored = ClientRendering.register("gtmb:test/explicit-invisible", carrier)) {
            HologramBlockRender.begin(state);
            SimpleAssert.that(HologramBlockRender.ready(state.cells.get(0), false), "data-only cell is handled");
            SimpleAssert
                .eq(0, HologramBlockRender.vertexCount(state.cells.get(0), false), "no fabricated invisible geometry");
            HologramBlockRender.draw(state.cells.get(0), false);
            java.lang.reflect.Method visible = HologramRenderer.class
                .getDeclaredMethod("previewVisible", HologramState.Cell.class, int.class, int.class, boolean.class);
            visible.setAccessible(true);
            SimpleAssert.eq(
                false,
                visible.invoke(null, state.cells.get(0), Integer.MAX_VALUE, 0, false),
                "data-only cell produces no fallback cube, outline or label");
            SimpleAssert.that(
                !HologramBlockRender.missingTexture(state.cells.get(0), false),
                "no material required by explicit native policy");
            SimpleAssert.eq(original, state.fullData(), "data-only cell and full payload preserved");
            SimpleAssert.that(
                HologramBlockRender.dataOnly(state, false),
                "explicit data-only snapshot is a valid handled display");
            NBTTagCompound mixed = state.fullData();
            mixed.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .setInteger("wantMeta", 1);
            SimpleAssert.that(
                !HologramBlockRender.dataOnly(new HologramState(mixed), false),
                "undeclared empty models are not data-only");
            NBTTagCompound empty = new NBTTagCompound();
            empty.setTag("cells", new NBTTagList());
            SimpleAssert.that(
                !HologramBlockRender.dataOnly(new HologramState(empty), false),
                "empty snapshot is not data-only");
        } finally {
            HologramBlockRender.clear();
        }
        SimpleAssert.that(
            !HologramBlockRender.invisible(state.cells.get(0), false),
            "removing policy restores strict defaults");
        System.out.println("Explicit native invisible/data-only policy passed; ordinary adapter default remains false");
    }
}

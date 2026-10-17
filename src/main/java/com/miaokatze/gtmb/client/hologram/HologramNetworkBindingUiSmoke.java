package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

/** Rendered modal/input probe with intercepted packets; the corresponding server smoke uses real GTSWN networks. */
public final class HologramNetworkBindingUiSmoke {

    private HologramNetworkBindingUiSmoke() {}

    public static String run(HologramScreen current) throws Exception {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke"))
            throw new IllegalStateException("Client smoke is disabled");
        HologramState state = (HologramState) field(current, "state");
        NBTTagCompound fixture = (NBTTagCompound) state.fullData();
        fixture.removeTag("aeSaveReview");
        fixture.setBoolean("networkBindingRequested", true);
        fixture.setBoolean("editable", true);
        NBTTagCompound bindings = new NBTTagCompound();
        NBTTagList groups = new NBTTagList(), inventory = new NBTTagList();
        Block node = null;
        for (Object raw : Block.blockRegistry) if (raw instanceof Block && raw.getClass()
            .getName()
            .equals("com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode")) node = (Block) raw;
        if (node == null) throw new IllegalStateException("Actual GTSWN node item is unavailable");
        for (int i = 0; i < 2; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "q" + (i + 1));
            row.setInteger("cells", 2);
            row.setString("hint", "量子网络组 " + (i + 1) + " · 原设备组提示");
            row.setString("reason", "选择携带网络坐标的量子节点或终端");
            groups.appendTag(row);
            NBTTagCompound item = new NBTTagCompound();
            item.setInteger("slot", 20 + i);
            item.setBoolean("available", true);
            item.setString("name", "已验证的真实量子节点 " + (i + 1));
            item.setTag("item", new ItemStack(node).writeToNBT(new NBTTagCompound()));
            inventory.appendTag(item);
        }
        bindings.setTag("groups", groups);
        bindings.setTag("inventory", inventory);
        fixture.setTag("networkBindings", bindings);
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        set(probe, "captureRenderSmokeAction", true);
        probe.setWorldAndResolution(Minecraft.getMinecraft(), current.width, current.height);
        for (int i = 0; i < 2; i++) {
            set(probe, "pendingSequence", -1L);
            HologramMuiProbeInput.renderFixture(probe, 0, 0, 0);
            click(probe, 60000 + i);
            HologramMuiProbeInput.renderFixture(probe, 0, 0, 0);
            click(probe, 61020 + i);
            NBTTagCompound request = (NBTTagCompound) field(probe, "capturedRenderSmokeAction");
            require(
                "bindBlueprintNetwork".equals(request.getString("op"))
                    && ("q" + (i + 1)).equals(request.getString("group"))
                    && request.getInteger("slot") == 20 + i,
                "actual_drawn_group_and_inventory_click_" + i);
            require(
                !request.hasKey("tag") && !request.hasKey("item")
                    && !request.toString()
                        .contains("Anchor"),
                "client_sends_only_server_owned_group_and_inventory_index_" + i);
        }
        set(probe, "pendingSequence", -1L);
        HologramMuiProbeInput.key(probe, '\0', Keyboard.KEY_E);
        require(
            !(Boolean) field(probe, "networkDrawer") && "closeNetworkBindings"
                .equals(((NBTTagCompound) field(probe, "capturedRenderSmokeAction")).getString("op")),
            "E_returns_to_root_without_starting_placement");
        return "network_modal=two_groups actual_registered_node_icons drawn_group_and_36_inventory_controls local_hints packet=group+slot E=return";
    }

    private static void click(HologramScreen screen, int id) throws Exception {

        HologramMuiProbeInput.control(screen, id);
    }

    private static Object field(Object object, String name) throws Exception {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(object, name));
    }

    private static void set(Object object, String name, Object value) throws Exception {

        HologramUiDiagnostics.writeDiagnosticField(object, name, value);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("[GTMB-NETWORK-UI] FAIL " + name);
        System.out.println("[GTMB-NETWORK-UI] PASS " + name);
    }
}

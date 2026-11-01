package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.oredict.OreDictionary;

import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.RecipeViewerSettingsImpl;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Server-authorized drafts; native MUI widgets and NEI ghost samples never move real inventory. */
final class HologramVoucherScreen extends ModularScreen {

    private static final String[] KEYS = { "allowedModIds", "allowedBlocks", "allowedOres", "deniedModIds",
        "deniedBlocks", "deniedOres" };
    private static final String[] TYPES = { "Mod", "方块", "矿词" };
    private static HologramVoucherScreen active;
    private final List<Set<String>> rules = new ArrayList<>();
    private final List<NBTTagCompound> mods = new ArrayList<>();
    private final StringValue search = new StringValue(""), exact = new StringValue("");
    private NBTTagCompound canonical;
    private Container playerContainer;
    private int type, page, rulePage;
    private boolean blacklist, pending, enabled;
    private String notice = "";
    private ItemStack sample;
    private String cachedSearch = "";
    private int cachedType = -1;
    private List<String> cachedSources = java.util.Collections.emptyList();

    HologramVoucherScreen(NBTTagCompound state) {
        super("gtmb", new VoucherPanel());
        receive(state);
        build();
    }

    static void show(NBTTagCompound state) {
        if (active != null && Minecraft.getMinecraft().currentScreen != null) active.receive(state);
        else new HologramVoucherScreen(state).open();
    }

    private void open() {
        active = this;
        playerContainer = Minecraft.getMinecraft().thePlayer.openContainer;
        RecipeViewerSettingsImpl viewer = new RecipeViewerSettingsImpl();
        viewer.enable();
        ClientGUI.open(this, viewer, HologramAeGhostContainer::new);
    }

    private static final class VoucherPanel extends ModularPanel {

        VoucherPanel() {
            super("gtmb_voucher");
            size(560, 420).background(HologramMuiTheme.CLOTH);
        }

        @Override
        public void transform(IViewportStack stack) {
            int width = getScreen().getScreenArea().width;
            int height = getScreen().getScreenArea().height;
            float scale = Math.min(1f, Math.min(width / 560f, height / 420f));
            stack.translate((width - 560 * scale) / 2f, (height - 420 * scale) / 2f);
            stack.scale(scale, scale);
        }
    }

    @Override
    public void onOpen() {
        if (Minecraft.getMinecraft().thePlayer.openContainer == getContainer())
            Minecraft.getMinecraft().thePlayer.openContainer = playerContainer;
    }

    @Override
    public void onClose() {
        if (active == this) active = null;
        super.onClose();
    }

    void receive(NBTTagCompound state) {
        canonical = (NBTTagCompound) state.copy();
        pending = false;
        enabled = state.getBoolean("enabled");
        rules.clear();
        for (String key : KEYS) {
            Set<String> list = new TreeSet<>();
            NBTTagList entries = state.getTagList(key, 8);
            for (int i = 0; i < entries.tagCount(); i++) list.add(entries.getStringTagAt(i));
            rules.add(list);
        }
        mods.clear();
        NBTTagList entries = state.getTagList("mods", 10);
        for (int i = 0; i < entries.tagCount(); i++) mods.add(entries.getCompoundTagAt(i));
        mods.sort(
            java.util.Comparator.comparing(
                row -> row.getString("id")
                    .toLowerCase(Locale.ROOT)));
        cachedType = -1;
        notice = state.getString("status");
    }

    private void build() {
        ModularPanel panel = getMainPanel();
        HologramMuiTheme.header(panel, () -> "猫猫装饰抵用券 · 服务器规则", 560);
        panel.child(
            HologramMuiTheme.textField(search, 14, 38, 260, 19, 128)
                .hintText("搜索 Mod / 方块 / 矿词"));
        for (int i = 0; i < 3; i++) {
            final int selected = i;
            panel.child(
                HologramMuiTheme
                    .button(() -> (type == selected ? "✓ " : "") + TYPES[selected], 14 + i * 87, 62, 83, 19, () -> {
                        type = selected;
                        page = rulePage = 0;
                    }));
        }
        panel.child(HologramMuiTheme.button(() -> blacklist ? "黑名单（优先禁用）" : "白名单（允许抵扣）", 286, 38, 260, 19, () -> {
            blacklist = !blacklist;
            rulePage = 0;
        }));
        panel.child(
            HologramMuiTheme.label(() -> "当前" + TYPES[type] + "规则 · 点击删除", 286, 63, 260, 16, HologramMuiTheme.TITLE));
        for (int i = 0; i < 10; i++) {
            final int row = i;
            panel.child(HologramMuiTheme.button(() -> sourceLabel(row), 14, 86 + i * 18, 260, 17, () -> {
                List<String> list = sources();
                int at = sourceOffset(list) + row;
                if (!pending && at < list.size()) add(list.get(at));
            })
                .tooltipBuilder(t -> {
                    List<String> list = sources();
                    int at = sourceOffset(list) + row;
                    if (at >= list.size()) return;
                    t.addLine(list.get(at));
                    if (type == 0) for (NBTTagCompound mod : mods) if (mod.getString("id")
                        .equals(list.get(at))) {
                            t.addLine(mod.getString("name") + " " + mod.getString("version"));
                            t.addLine(mod.getString("description"));
                        }
                })
                .tooltipAutoUpdate(true));
            panel.child(HologramMuiTheme.button(() -> ruleLabel(row), 286, 86 + i * 18, 260, 17, () -> {
                List<String> list = new ArrayList<>(selected());
                int at = rulePage * 10 + row;
                if (!pending && at < list.size()) {
                    selected().remove(list.get(at));
                    notice = "草稿已修改，保存后生效";
                }
            }));
        }
        panel.child(HologramMuiTheme.button(() -> "◀", 14, 270, 40, 18, () -> page = Math.max(0, page - 1)));
        panel.child(
            HologramMuiTheme.label(
                () -> "" + (sourceOffset(sources()) / 10 + 1) + " / " + Math.max(1, (sources().size() + 9) / 10),
                58,
                270,
                170,
                18,
                HologramMuiTheme.MUTED));
        panel.child(
            HologramMuiTheme.button(
                () -> "▶",
                234,
                270,
                40,
                18,
                () -> page = Math.min(page + 1, Math.max(0, (sources().size() - 1) / 10))));
        panel.child(HologramMuiTheme.button(() -> "◀", 286, 270, 40, 18, () -> rulePage = Math.max(0, rulePage - 1)));
        panel.child(
            HologramMuiTheme.label(
                () -> selected().size() + " 条 · " + (rulePage + 1) + " 页",
                330,
                270,
                170,
                18,
                HologramMuiTheme.MUTED));
        panel.child(
            HologramMuiTheme.button(
                () -> "▶",
                506,
                270,
                40,
                18,
                () -> rulePage = Math.min(rulePage + 1, Math.max(0, (selected().size() - 1) / 10))));
        panel.child(
            HologramMuiTheme.textField(exact, 14, 293, 390, 19, 192)
                .hintText("namespace:block@meta / 矿词 / Mod ID"));
        panel.child(HologramMuiTheme.button(() -> "添加规则", 414, 293, 132, 19, () -> add(exact.getStringValue())));
        panel.child(
            new Sample(-1).pos(14, 322)
                .size(22));
        for (int i = 0; i < 36; i++) panel.child(
            new Sample(i).pos(48 + i % 18 * 27, 318 + i / 18 * 25)
                .size(23));
        panel.child(
            HologramMuiTheme
                .label(() -> "← NEI 拖入 / 点击背包样本，按当前类型添加；数量与 NBT 不参与方块规则", 14, 370, 532, 14, HologramMuiTheme.MUTED));
        panel.child(
            HologramMuiTheme.button(() -> enabled ? "抵用：开启" : "抵用：禁用", 14, 392, 104, 20, () -> enabled = !enabled));
        panel.child(
            HologramMuiTheme.button(() -> "恢复默认并保存", 126, 392, 138, 20, () -> save(true))
                .setEnabledIf(w -> !pending));
        panel.child(
            HologramMuiTheme.primaryButton(() -> pending ? "保存中…" : "保存至服务器", 410, 392, 136, 20, () -> save(false))
                .setEnabledIf(w -> !pending));
        panel.child(
            HologramMuiTheme.label(() -> notice, 272, 392, 130, 20, HologramMuiTheme.TITLE)
                .tooltipBuilder(t -> t.addLine(notice))
                .tooltipAutoUpdate(true));
    }

    private Set<String> selected() {
        return rules.get(type + (blacklist ? 3 : 0));
    }

    private void add(String value) {
        if (pending || value == null || !value.matches("[A-Za-z0-9_.:|@*+/-]+")) return;
        selected().add(value.toLowerCase(Locale.ROOT));
        notice = "草稿已修改，保存后生效";
    }

    private List<String> sources() {
        String filter = search.getStringValue()
            .toLowerCase(Locale.ROOT);
        if (cachedType == type && cachedSearch.equals(filter)) return cachedSources;
        List<String> result = new ArrayList<>();
        if (type == 0) for (NBTTagCompound mod : mods) {
            if ((mod.getString("id") + " " + mod.getString("name")).toLowerCase(Locale.ROOT)
                .contains(filter)) result.add(mod.getString("id"));
        }
        if (type == 1) for (Object key : Block.blockRegistry.getKeys()) {
            String id = String.valueOf(key);
            if (!id.equals("minecraft:air") && id.toLowerCase(Locale.ROOT)
                .contains(filter)) result.add(id);
        }
        if (type == 2) for (String ore : OreDictionary.getOreNames()) if (ore.toLowerCase(Locale.ROOT)
            .contains(filter)) result.add(ore);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        cachedType = type;
        cachedSearch = filter;
        cachedSources = result;
        return result;
    }

    private int sourceOffset(List<String> list) {
        return Math.min(page, Math.max(0, (list.size() - 1) / 10)) * 10;
    }

    private String sourceLabel(int row) {
        List<String> list = sources();
        int at = sourceOffset(list) + row;
        return at < list.size() ? "+ " + list.get(at) : "";
    }

    private String ruleLabel(int row) {
        List<String> list = new ArrayList<>(selected());
        int at = rulePage * 10 + row;
        return at < list.size() ? "− " + list.get(at) : "";
    }

    private void save(boolean defaults) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "voucherEditor");
        request.setBoolean("save", true);
        request.setBoolean("defaults", defaults);
        request.setBoolean("enabled", enabled);
        request.setLong("revision", canonical.getLong("revision"));
        for (int i = 0; i < KEYS.length; i++) {
            NBTTagList list = new NBTTagList();
            for (String entry : rules.get(i)) list.appendTag(new NBTTagString(entry));
            request.setTag(KEYS[i], list);
        }
        pending = true;
        HologramClient.stampSemanticRevision(request);
        HologramNetwork.sendAction(request);
    }

    private void sample(ItemStack stack) {
        if (stack == null || Block.getBlockFromItem(stack.getItem()) == null
            || Block.getBlockFromItem(stack.getItem()) == net.minecraft.init.Blocks.air) {
            notice = "请选择方块物品";
            return;
        }
        sample = stack.copy();
        sample.stackSize = 1;
        String id = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        if (type == 0) add(id.substring(0, id.indexOf(':')));
        else if (type == 1) add(id + "@" + stack.getItemDamage());
        else {
            int[] ids = OreDictionary.getOreIDs(stack);
            for (int ore : ids) add(OreDictionary.getOreName(ore));
            if (ids.length == 0) notice = "此方块没有矿词";
        }
    }

    private final class Sample extends ItemDisplayWidget
        implements Interactable, RecipeViewerGhostIngredientSlot<ItemStack> {

        private final int index;

        Sample(int index) {
            this.index = index;
            item(
                new ObjectValue.Dynamic<>(
                    ItemStack.class,
                    () -> index < 0 ? sample : Minecraft.getMinecraft().thePlayer.inventory.mainInventory[index],
                    ignored -> {}));
            background(HologramMuiTheme.PANEL);
        }

        @Override
        public Result onMousePressed(int button) {
            sample(
                index < 0 ? Minecraft.getMinecraft().thePlayer.inventory.getItemStack()
                    : Minecraft.getMinecraft().thePlayer.inventory.mainInventory[index]);
            return Result.SUCCESS;
        }

        @Override
        public boolean handleDragAndDrop(ItemStack stack, int button) {
            sample(stack.copy());
            return true;
        }
    }
}

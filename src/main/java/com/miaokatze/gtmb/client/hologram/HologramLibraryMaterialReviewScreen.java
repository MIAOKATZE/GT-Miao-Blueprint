// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.client.hologram.HologramLibraryClient.PlainAvailability;
import com.miaokatze.gtmb.client.hologram.HologramLocalPlacement.Submission;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramMaterials;

/** Lightweight local modal over the already-present world ghost; no second workspace or source read. */
final class HologramLibraryMaterialReviewScreen extends HologramMuiScreen implements LocalView {

    private final HologramLocalPlacement placement;
    private final Ticket ticket;
    private final CapturedDescription description;
    private HologramMaterialConfirmationDialog dialog;
    private PlainAvailability availability = PlainAvailability.SOURCE;
    private boolean voucherSelected;
    private final java.util.List<Integer> bindGroups;
    private int groupCursor;
    private final java.util.SortedMap<Integer,Integer> choices=new java.util.TreeMap<>();
    private int group(){return groupCursor==0?0:bindGroups.get(groupCursor-1);}
    @Override public boolean hasBindingChoices(){return !bindGroups.isEmpty();}
    @Override public String bindingGroupLabel(){return group()==0?"量子组：默认槽":"量子组：q"+group();}
    @Override public String bindingSlotLabel(){
        Integer slot=choices.get(group());
        return slot==null?(group()==0?"终端：未选择":"终端：沿用默认槽"):"终端：背包槽 "+(slot+1);
    }
    private void nextTerminal(ItemStack[] inventory) {
        int group=group(),start=choices.getOrDefault(group,-1);
        for(int i=start+1;i<Math.min(36,inventory.length);i++){
            if(com.miaokatze.gtmb.hologram.HologramSavedBindingChoices.terminal(inventory[i])){
                if(!choices.containsKey(group)&&choices.size()>=com.miaokatze.gtmb.hologram.HologramSavedBindingChoices.MAX_CHOICES){
                    HologramClient.selectionNotice("已达到单次控制消息的单选上限，可为其余类群设置默认槽");return;
                }
                choices.put(group,i);return;
            }
        }
        choices.remove(group);
        if(start<0)HologramClient.selectionNotice("背包中没有可选量子终端；请先放入已绑定终端");
    }
    private int[] bindingChoices(){return choices.entrySet().stream().mapToInt(e->(e.getKey()<<6)|e.getValue()).toArray();}

    HologramLibraryMaterialReviewScreen(HologramLocalPlacement placement, Submission frozen, Minecraft client) {
        super("library_material_review", 1, 1);
        this.placement = placement;
        var view=frozen.display().nativeViewport;
        bindGroups=frozen.source().nativeReceipt!=null&&view!=null?view.bindingGroups:java.util.Collections.emptyList();
        ticket = new Ticket(placement, frozen, client, this);
        description = frozen.source().nativeReceipt == null
            ? new CapturedDescription(HologramLibraryMaterialForecast.fromCapture(frozen.source()))
            : new CapturedDescription(frozen.display(), client.thePlayer.getHeldItem());
    }

    @Override
    protected void buildPanel() {
        panel().invisible();
        dialog = new HologramMaterialConfirmationDialog(panel(), this);
    }

    @Override
    public void updateScreen() {
        Minecraft client = Minecraft.getMinecraft();
        if (!ticket.current(client)) returnToWorld(client);
        else if (dialog != null) dialog.tick(true);
    }

    @Override
    public LocalFrame localFrame() {
        Minecraft client = Minecraft.getMinecraft();
        if (!ticket.current(client)) return null;
        availability = HologramLibraryClient.INSTANCE.plainPlacementAvailability(ticket.frozen, client);
        LocalFrame frame = description.frame(() -> client.thePlayer.inventory.mainInventory, ticket.slot);
        return availability == PlainAvailability.READY ? frame : new LocalFrame(
            frame.rows(), frame.scope(), frame.status() + " · " + availability.reason,
            frame.emptyLabel(), frame.ready(), frame.description());
    }

    @Override
    public String localSubmitLabel() {
        return availability == PlainAvailability.READY ? "开始施工（按所选料源）" : "施工不可用 · " + availability.reason;
    }

    @Override
    public boolean localSubmitAvailable() {
        return availability == PlainAvailability.READY && ticket.current(Minecraft.getMinecraft());
    }

    @Override
    public boolean localVoucherAvailable() {
        return availability == PlainAvailability.READY && ticket.current(Minecraft.getMinecraft());
    }

    @Override
    public boolean localVoucherSelected() {
        return voucherSelected;
    }

    @Override
    public String localVoucherLabel() {
        return voucherSelected ? "✓ 请求装饰抵扣 · 服务端核验" : "使用猫猫装饰抵用券";
    }

    @Override
    public String localSourcesLabel() {
        Minecraft client = Minecraft.getMinecraft();
        if (!ticket.current(client)) return "材料来源：上下文已失效";
        var settings = HologramMaterials.read(client.thePlayer.getHeldItem());
        return "所选料源：" + (settings.main ? "物品栏 " : "")
            + (settings.containers ? "背包 " : "") + (settings.me ? "无线 AE" : "")
            + " · 优先" + (settings.priority == 1 ? "背包" : settings.priority == 2 ? "AE" : "物品栏");
    }

    /** A choice belongs to this exact confirmation, never mutates the tool or the frozen source. */
    static Submission paymentChoice(Submission frozen, boolean voucherSelected) {
        return paymentChoice(frozen,voucherSelected,new int[0]);
    }
    static Submission paymentChoice(Submission frozen,boolean voucherSelected,int[] bindingSlots) {
        if (frozen == null) return null;
        NBTTagCompound intent = frozen.intent();
        if(bindingSlots.length>0)intent.setIntArray("savedBindingSlots",bindingSlots.clone());
        intent.setBoolean("voucherRequested", voucherSelected);
        return new Submission(frozen.source(), frozen.display(), frozen.lockGeneration(), intent);
    }

    @Override
    public void localAction(LocalAction action) {
        deferAction(() -> {
            Minecraft client = Minecraft.getMinecraft();
            if (action == LocalAction.BACK) returnToWorld(client);
            else if(action==LocalAction.BIND_GROUP&&hasBindingChoices()&&ticket.current(client))groupCursor=(groupCursor+1)%(bindGroups.size()+1);
            else if(action==LocalAction.BIND_SLOT&&hasBindingChoices()&&ticket.current(client))nextTerminal(client.thePlayer.inventory.mainInventory);
            else if (action == LocalAction.VOUCHER) {
                if (localVoucherAvailable()) voucherSelected = !voucherSelected;
            } else if (action == LocalAction.REFRESH) {
                if (ticket.current(client))
                    description.refresh(() -> client.thePlayer.inventory.mainInventory, ticket.slot);
            } else if (action == LocalAction.SUBMIT && ticket.current(client)) {
                availability = HologramLibraryClient.INSTANCE.plainPlacementAvailability(ticket.frozen, client);
                if (availability != PlainAvailability.READY) {
                    HologramClient.selectionNotice(availability.reason + "，未开始施工。");
                } else if (dispatchRefused(action, () -> paymentChoice(ticket.consume(client), voucherSelected,bindingChoices()), placement::submitFrozen)) {
                    returnToWorld(client);
                    HologramClient.selectionNotice("当前锁定范围或连接未通过施工检查；已保留位置，未开始施工。");
                }
            }
        });
    }

    static boolean dispatchRefused(LocalAction action, Supplier<Submission> consume,
        BiPredicate<Submission, Boolean> submit) {
        if (action != LocalAction.SUBMIT) return false;
        Submission frozen = consume.get();
        return frozen != null && !submit.test(frozen, true);
    }

    @Override
    protected boolean muiKeyBefore(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) localAction(LocalAction.BACK);
        return true;
    }

    private void returnToWorld(Minecraft client) {
        ticket.retire();
        if (client != null && client.currentScreen == this) client.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        ticket.retire();
    }

    @Override
    protected void onMuiClose() {
        ticket.retire();
    }

    /** The frozen source projection survives local stock refresh; only an owned sample reads slots. */
    static final class CapturedDescription {

        private static final int MAIN_SLOTS = 36;
        private final HologramLibraryMaterialForecast forecast;
        private final HologramLocalMaterialReview nativeReview;
        private final boolean nativeMain;
        private LocalFrame cached;

        CapturedDescription(HologramLibraryMaterialForecast forecast) {
            this.forecast = forecast;
            this.nativeReview = null;
            this.nativeMain = false;
        }

        CapturedDescription(HologramState state, ItemStack tool) {
            this.forecast = null;
            NBTTagCompound settings = new NBTTagCompound();
            nativeMain = HologramMaterials.read(tool).main;
            settings.setBoolean("materialMain", nativeMain);
            nativeReview = new HologramLocalMaterialReview(state, settings);
        }

        LocalFrame frame(Supplier<ItemStack[]> mainInventory, int toolSlot) {
            if (cached == null) refresh(mainInventory, toolSlot);
            return cached;
        }

        void refresh(Supplier<ItemStack[]> mainInventory, int toolSlot) {
            if (nativeReview != null) {
                nativeReview.refresh(nativeMain ? mainInventory.get() : null, toolSlot);
                cached = new LocalFrame(nativeReview.visible(), "已锁定蓝图 · 整份范围",
                    nativeReview.status() + " · 仅为图内用量", "图内没有可列明的材料", nativeReview.ready(), null);
                return;
            }
            boolean canObserve = forecast != null && !forecast.candidates()
                .isEmpty();
            if (!canObserve && cached != null) return;
            ItemStack[] source = canObserve ? mainInventory.get() : null;
            ItemStack[] observed = source == null || source.length < MAIN_SLOTS ? null
                : source.length == MAIN_SLOTS ? source : Arrays.copyOf(source, MAIN_SLOTS);
            HologramLibraryMaterialDescription.Description result = HologramLibraryMaterialDescription
                .describe(forecast, observed, toolSlot);
            String status = forecast != null && forecast.unpriced().cells() == 0
                ? "图内目标用量 · 现场及所选来源待核验" : "施工用量未核算";
            if (result == null) status += " · 本机没有可核对的来源记录";
            else if (result.unlistedCells() > 0) status += " · " + result.unlistedCells() + " 格未列明";
            cached = new LocalFrame(
                new NBTTagCompound(),
                "已锁定蓝图 · 整份范围",
                status,
                "本机没有可列明的蓝图材料记录",
                canObserve,
                result == null ? Collections.emptyList() : result.rows());
        }
    }

    static final class Ticket {

        private final HologramLocalPlacement placement;
        private final Submission frozen;
        private final GuiScreen owner;
        private final int slot, dimension;
        private final ItemStack tool;
        private final String toolId;
        private final int reviewedSources;
        private boolean retired;

        Ticket(HologramLocalPlacement placement, Submission frozen, Minecraft client, GuiScreen owner) {
            this.placement = placement;

            this.frozen = frozen;
            this.owner = owner;
            slot = client.thePlayer.inventory.currentItem;
            dimension = client.thePlayer.dimension;
            tool = client.thePlayer.getHeldItem();
            toolId = id(tool);
            var intent=frozen.intent();reviewedSources=intent.hasKey("reviewedSources",3)?intent.getInteger("reviewedSources"):Integer.MIN_VALUE;
        }

        boolean current(Minecraft client) {
            return !retired && client != null
                && client.currentScreen == owner
                && !placement.submitting()
                && frozen.current(placement, client)
                && !client.thePlayer.isDead
                && ItemNekoHologramProjector.isProjector(tool)
                && client.thePlayer.dimension == dimension
                && client.thePlayer.inventory.currentItem == slot
                && client.thePlayer.getHeldItem() == tool
                && toolId.equals(id(tool))
                && (reviewedSources==Integer.MIN_VALUE||reviewedSources==com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy.settingsKey(HologramMaterials.read(tool)));
        }

        Submission consume(Minecraft client) {
            boolean usable = current(client);
            // A rejected deferred click cannot be retried by restoring the previous slot or screen.
            retire();
            return usable ? frozen : null;
        }

        void retire() {
            retired = true;
        }

        private static String id(ItemStack stack) {
            return stack != null && stack.hasTagCompound() ? stack.getTagCompound()
                .getString("gtitHologramToolId") : "";
        }
    }
}

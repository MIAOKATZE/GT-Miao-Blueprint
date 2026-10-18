package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.Actionable;
import appeng.api.config.CraftingAllow;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.AECableType;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Server-thread, bounded AE requesters. Closing a UI does not own or cancel these jobs. */
public final class HologramAeCraftingOrders {

    private static final int MAX_ORDERS = 64, MAX_ROWS = 1024;
    private static final int MAX_RECEIPTS = 4096;
    private static final String ITEM_RECOVERY = "gtmbHologramMaterialRecovery";
    private static final String FLUID_RECOVERY = "gtmbHologramFluidRecovery";
    private static final String ITEM_AMOUNT = "gtmbRecoveryCount", FLUID_AMOUNT = "gtmbRecoveryAmount";
    private static final String DELIVERY_ORDER = "gtmbCraftingDeliveryOrder",
        DELIVERY_INDEX = "gtmbCraftingDeliveryIndex";
    private static final String UNCERTAIN = "gtmbHologramCraftingUncertainDelivery";
    private static final String DELIVERY = "gtmbHologramCraftingDelivery";
    private static final long POLL_INTERVAL = 20, CPU_WAIT_POLLS = 60;
    private static final Map<UUID, Order> ORDERS = new LinkedHashMap<>();
    private static long clock;

    private HologramAeCraftingOrders() {}

    public static boolean available(EntityPlayerMP player, HologramMaterials.Settings settings) {
        try {
            IGrid grid = new HologramMaterials.Context(player, craftingSettings(settings)).craftingGrid();
            return canDeliver(grid, player);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    public static boolean active(EntityPlayerMP player) {
        Order order = ORDERS.get(player.getUniqueID());
        return order != null && order.active;
    }

    /** The request must be a fresh server-owned quote; client-supplied material rows are never accepted. */
    public static String submit(EntityPlayerMP player, HologramMaterials.Settings settings, NBTTagCompound quote) {
        if (active(player)) return "AE 缺料订单正在后台处理，请等待完成或取消当前订单";
        if (!quote.getBoolean("complete")) return "材料报价尚未完成，请刷新材料后再下单";
        if (ORDERS.size() >= MAX_ORDERS && !ORDERS.containsKey(player.getUniqueID())) return "AE 后台订单队列已满，请稍后重试";
        try {
            HologramMaterials.Context context = new HologramMaterials.Context(player, craftingSettings(settings));
            IGrid grid = context.craftingGrid();
            if (!canDeliver(grid, player)) return "无线终端不可用、超出范围，或缺少 AE 合成/提取/产物存入权限";
            List<Request> requests = requests(quote.getTagList("rows", 10));
            if (requests.isEmpty()) return "没有可交给 AE 合成的缺少物品或流体；EU 和源质需另行补充";
            Order order = new Order(player, context, grid, quote, requests);
            ORDERS.put(player.getUniqueID(), order);
            order.publish();
            return "AE 缺料订单已接收，将在后台计算与合成；关闭界面后仍继续";
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return "材料订单无效：" + invalid.getMessage();
        } catch (RuntimeException | LinkageError unavailable) {
            return "AE 无线网络暂时不可用，请检查终端与网络";
        }
    }

    private static boolean canDeliver(IGrid grid, EntityPlayerMP player) {
        if (grid == null) return false;
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        return security != null && security.hasPermission(player, SecurityPermissions.INJECT);
    }

    /** Tick once from the server END phase. Only link/CPU state is polled, with no material or world rescan. */
    public static void tick() {
        clock++;
        if (clock % POLL_INTERVAL != 0) return;
        Iterator<Order> iterator = ORDERS.values()
            .iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            if (!order.active) {
                if (clock - order.finishedAt >= 1200) iterator.remove();
                continue;
            }
            try {
                order.poll();
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                order.finish("failed", "AE 合成计算已中断，请重新下单", true);
            } catch (ExecutionException | RuntimeException | LinkageError failure) {
                order.finish("failed", "AE 合成失败，请检查样板、材料和 CPU；订单不会自动重复提交", true);
            }
            order.publish();
        }
    }

    public static NBTTagCompound snapshot(EntityPlayerMP player) {
        Order order = ORDERS.get(player.getUniqueID());
        return order == null ? new NBTTagCompound() : order.snapshot();
    }

    /** Cancels only this requester's future/link; undelivered real outputs move to the existing recovery ledger. */
    public static void cancel(EntityPlayerMP player, String reason) {
        Order order = ORDERS.get(player.getUniqueID());
        if (order == null) return;
        if (order.active) {
            order.finish("cancelled", reason, true);
            order.publish();
        }
        ORDERS.remove(player.getUniqueID());
    }

    public static void clearAll(String reason) {
        for (Order order : ORDERS.values()) if (order.active) {
            order.finish("cancelled", reason, true);
            order.publish();
        }
        ORDERS.clear();
        clock = 0;
    }

    /** Login recovery after an unclean server exit never resubmits an order or reconstructs a requester. */
    public static void recoverOrphans(EntityPlayerMP player) {
        if (!active(player)) releaseDeliveries(player, null);
    }

    private static NBTTagCompound recoveryOwner(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    private static long receiptAmount(NBTTagCompound receipt) {
        return Math.max(
            0L,
            receipt.getBoolean("fluidDelivery") ? receipt.getLong(FLUID_AMOUNT) : receipt.getInteger(ITEM_AMOUNT));
    }

    private static void receiptAmount(NBTTagCompound receipt, long amount) {
        if (receipt.getBoolean("fluidDelivery")) receipt.setLong(FLUID_AMOUNT, amount);
        else receipt.setInteger(ITEM_AMOUNT, (int) Math.min(Integer.MAX_VALUE, amount));
    }

    private static void releaseDeliveries(EntityPlayerMP player, String orderId) {
        NBTTagCompound owner = recoveryOwner(player);
        NBTTagList queued = owner.getTagList(DELIVERY, 10), retained = new NBTTagList();
        NBTTagList items = owner.getTagList(ITEM_RECOVERY, 10), fluids = owner.getTagList(FLUID_RECOVERY, 10);
        for (int i = 0; i < queued.tagCount(); i++) {
            NBTTagCompound receipt = queued.getCompoundTagAt(i);
            if (orderId != null && !orderId.equals(receipt.getString(DELIVERY_ORDER))) {
                retained.appendTag(receipt);
                continue;
            }
            if (receiptAmount(receipt) <= 0) continue;
            NBTTagCompound released = (NBTTagCompound) receipt.copy();
            released.removeTag(DELIVERY_ORDER);
            released.removeTag(DELIVERY_INDEX);
            released.removeTag("fluidDelivery");
            released.setBoolean("gtmbRecoveryMeFirst", true);
            (receipt.getBoolean("fluidDelivery") ? fluids : items).appendTag(released);
        }
        owner.setTag(DELIVERY, retained);
        owner.setTag(ITEM_RECOVERY, items);
        owner.setTag(FLUID_RECOVERY, fluids);
    }

    private static HologramMaterials.Settings craftingSettings(HologramMaterials.Settings settings) {
        HologramMaterials.Settings result = new HologramMaterials.Settings();
        result.main = false;
        result.containers = false;
        result.me = settings != null && settings.me;
        return result;
    }

    private static List<Request> requests(NBTTagList rows) {
        if (rows.tagCount() > MAX_ROWS) throw new IllegalArgumentException("材料种类超过 " + MAX_ROWS);
        Map<HologramBlueprintResources.ExactKey, Request> result = new LinkedHashMap<>();
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            long missing = row.getLong("missing");
            if (missing <= 0) continue;
            IAEStack<?> stack;
            HologramBlueprintResources.ExactKey key;
            if ("item".equals(row.getString("kind"))) {
                ItemStack item = HologramMaterialQuote.readItem(row);
                if (item == null) throw new IllegalArgumentException("物品身份无法解析");
                item = HologramMaterialReservations.craftingSample(item);
                if (item == null) throw new IllegalArgumentException("该材料没有可证明等价的原生合成产物");
                stack = AEItemStack.create(item);
                key = new HologramBlueprintResources.ExactKey(item, null);
            } else if ("fluid".equals(row.getString("kind"))) {
                FluidStack fluid = HologramMaterialQuote.readFluid(row);
                if (!HologramMaterials.supportsFluidNbt(fluid))
                    throw new IllegalArgumentException("AE 无法保留该流体的精确 NBT 身份");
                stack = AEFluidStack.create(fluid);
                key = new HologramBlueprintResources.ExactKey(null, fluid);
            } else continue;
            if (stack == null) throw new IllegalArgumentException("AE 材料类型不可用");
            Request request = result.get(key);
            if (request == null) {
                stack.setStackSize(missing);
                result.put(key, new Request(stack, row.getString("label")));
            } else request.stack.setStackSize(Math.addExact(request.stack.getStackSize(), missing));
        }
        return new ArrayList<>(result.values());
    }

    private static final class Request {

        final IAEStack<?> stack;
        final String label;

        Request(IAEStack<?> stack, String label) {
            this.stack = stack;
            this.label = label;
        }
    }

    private static final class Order implements ICraftingRequester {

        final EntityPlayerMP player;
        final HologramMaterials.Context context;
        final IGrid grid;
        final String session;
        final String id = UUID.randomUUID()
            .toString();
        final long revision, uiSequence, reviewNonce;
        final net.minecraft.world.World world;
        final List<Request> requests;
        final long total;
        boolean active = true;
        int index;
        long done, finishedAt, waiting, publicationSequence;
        String state = "planning", status = "AE 正在后台计算缺料订单";
        Future<ICraftingJob> future;
        ICraftingJob job;
        ICraftingLink link;
        ICraftingCPU cpu;
        boolean nativeCompleted, nativeCancelled;
        long received;
        boolean uncertainDelivery;
        NBTTagCompound published;

        Order(EntityPlayerMP player, HologramMaterials.Context context, IGrid grid, NBTTagCompound quote,
            List<Request> requests) {
            this.player = player;
            this.context = context;
            this.grid = grid;
            this.session = quote.getString("session");
            revision = quote.getLong("planRevision");
            uiSequence = quote.getLong("uiSequence");
            reviewNonce = quote.getLong("placementReviewNonce");
            this.world = player.worldObj;
            this.requests = requests;
            long sum = 0;
            for (Request request : requests) sum = Math.addExact(sum, request.stack.getStackSize());
            total = sum;
        }

        void poll() throws InterruptedException, ExecutionException {
            if (player.isDead || player.worldObj != world
                || context.craftingGrid() != grid
                || !canDeliver(grid, player)) {
                finish("cancelled", "无线终端、维度或 AE 权限已失效，后台订单已安全取消", true);
                return;
            }
            if (link != null) {
                flushDeliveries();
                if (nativeCancelled || link.isCanceled()) {
                    finish("cancelled", "AE 合成任务已取消；已产出材料保留在 ME 网络", false);
                    return;
                }
                if (!nativeCompleted && !link.isDone()) return;
                if (uncertainDelivery || received < requests.get(index).stack.getStackSize()) {
                    finish("failed", "AE 产物未完整交付；已接管产物保存在玩家材料恢复账本，请检查后刷新材料", true);
                    return;
                }
                if (pendingDelivery() > 0) {
                    state = "delivering";
                    status = "AE 已合成，正在将暂存产物存入 ME；请为网络留出空间";
                    return;
                }
                done = Math.addExact(done, requests.get(index).stack.getStackSize());
                index++;
                link = null;
                cpu = null;
                job = null;
                future = null;
                waiting = 0;
                nativeCompleted = nativeCancelled = false;
                received = 0;
            }
            if (index == requests.size()) {
                finish("complete", "AE 缺料合成已完成，材料已进入 ME 网络；请刷新材料后继续施工", false);
                return;
            }
            ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
            if (crafting == null) {
                finish("failed", "AE 网络没有可用的合成服务", true);
                return;
            }
            Request request = requests.get(index);
            PlayerSource source = context.craftingSource();
            if (source == null) {
                finish("cancelled", "AE 合成权限已失效，后台订单已取消", true);
                return;
            }
            if (future == null) {
                future = crafting.beginCraftingJob(world, grid, source, request.stack.copy(), null);
                state = "planning";
                status = "正在计算：" + request.label;
                if (future == null) finish("failed", "AE 无法创建该材料的合成计算", true);
                return;
            }
            if (job == null) {
                if (!future.isDone()) return;
                job = future.get();
                if (job == null || job.isSimulation()) {
                    finish("failed", "AE 无法合成：" + request.label + "；请补齐样板与原料后重新下单", true);
                    return;
                }
            }
            ICraftingCPU selected = null;
            for (ICraftingCPU candidate : crafting.getCpus())
                if (!candidate.isBusy() && candidate.getAvailableStorage() >= job.getByteTotal()
                    && candidate.getCraftingAllowMode() != CraftingAllow.ONLY_NONPLAYER
                    && job.supportsCPUCluster(candidate)
                    && (selected == null || candidate.getAvailableStorage() < selected.getAvailableStorage()))
                    selected = candidate;
            if (selected == null) {
                state = "waiting_cpu";
                status = "等待可用 AE 合成 CPU：" + request.label;
                if (++waiting >= CPU_WAIT_POLLS) finish("failed", "AE 合成 CPU 持续不可用，订单已停止；请检查 CPU 后重新下单", true);
                return;
            }
            // A real requester creates non-standalone links. Native AE therefore cannot merge another player's
            // standalone order into this CPU; canceling our link never cancels somebody else's combined order.
            link = crafting.submitJob(job, this, selected, false, source);
            if (link == null) {
                finish("failed", "AE 拒绝合成订单；网络材料或 CPU 状态已变化，请刷新后重新下单", true);
                return;
            }
            cpu = selected;
            state = "crafting";
            status = "AE 正在合成：" + request.label;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return active && link != null && !link.isCanceled() && !link.isDone() ? ImmutableSet.of(link)
                : ImmutableSet.of();
        }

        @Override
        public IGridNode getActionableNode() {
            return active ? context.craftingNode() : null;
        }

        @Override
        public IGridNode getGridNode(ForgeDirection side) {
            return getActionableNode();
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection side) {
            return AECableType.NONE;
        }

        @Override
        public void securityBreak() {
            finish("cancelled", "AE 安全权限已撤销，独立合成订单已取消；暂存产物保留", true);
        }

        @Override
        public void jobStateChange(ICraftingLink changed) {
            if (changed != link) return;
            nativeCancelled = changed.isCanceled();
            nativeCompleted = changed.isDone();
        }

        /**
         * Acknowledge only real native outputs after putting them in the player's persistent delivery ledger.
         * Delivery into ME occurs on a later poll, outside AE's injection recursion guard. No outputs are minted.
         */
        @Override
        public IAEStack<?> injectCraftedItems(ICraftingLink requestedLink, IAEStack<?> output, Actionable mode) {
            if (!active || requestedLink != link
                || output == null
                || output.getStackSize() <= 0
                || index >= requests.size()
                || !output.isSameType((Object) requests.get(index).stack)) return output;
            NBTTagCompound owner = recoveryOwner(player);
            NBTTagList queued = owner.getTagList(DELIVERY, 10);
            boolean fluid = output instanceof IAEFluidStack;
            if (!fluid && !(output instanceof IAEItemStack)) return output;
            long wanted = Math
                .min(output.getStackSize(), Math.max(0L, requests.get(index).stack.getStackSize() - received));
            NBTTagCompound last = queued.tagCount() == 0 ? null : queued.getCompoundTagAt(queued.tagCount() - 1);
            boolean merge = last != null && id.equals(last.getString(DELIVERY_ORDER))
                && last.getInteger(DELIVERY_INDEX) == index
                && last.getBoolean("fluidDelivery") == fluid;
            long room = fluid ? Long.MAX_VALUE - (merge ? receiptAmount(last) : 0L)
                : (long) Integer.MAX_VALUE - (merge ? receiptAmount(last) : 0L);
            long capacity = Math.max(0, MAX_RECEIPTS - queued.tagCount()) * (long) Integer.MAX_VALUE;
            if (merge) capacity = Math.min(Long.MAX_VALUE - room, capacity) + room;
            if (fluid && queued.tagCount() < MAX_RECEIPTS) capacity = Long.MAX_VALUE;
            long accepted = Math.min(wanted, capacity);
            if (mode == Actionable.MODULATE && accepted > 0) {
                long left = accepted;
                if (merge && room > 0) {
                    long add = Math.min(room, left);
                    receiptAmount(last, receiptAmount(last) + add);
                    left -= add;
                }
                while (left > 0) {
                    long chunk = fluid ? left : Math.min(Integer.MAX_VALUE, left);
                    NBTTagCompound receipt;
                    if (fluid) {
                        FluidStack identity = ((IAEFluidStack) output).getFluidStack()
                            .copy();
                        identity.amount = 1;
                        receipt = identity.writeToNBT(new NBTTagCompound());
                    } else {
                        ItemStack identity = ((IAEItemStack) output).getItemStack()
                            .copy();
                        identity.stackSize = 1;
                        receipt = HologramMaterials.itemReceipt(identity);
                    }
                    receipt.setBoolean("fluidDelivery", fluid);
                    receipt.setString(DELIVERY_ORDER, id);
                    receipt.setInteger(DELIVERY_INDEX, index);
                    receiptAmount(receipt, chunk);
                    queued.appendTag(receipt);
                    left -= chunk;
                }
                owner.setTag(DELIVERY, queued);
                received = Math.addExact(received, accepted);
            }
            if (accepted >= output.getStackSize()) return null;
            return output.copy()
                .setStackSize(output.getStackSize() - accepted);
        }

        long pendingDelivery() {
            NBTTagList queued = recoveryOwner(player).getTagList(DELIVERY, 10);
            long pending = 0;
            for (int i = 0; i < queued.tagCount(); i++) {
                NBTTagCompound receipt = queued.getCompoundTagAt(i);
                if (id.equals(receipt.getString(DELIVERY_ORDER)) && receipt.getInteger(DELIVERY_INDEX) == index)
                    pending = Math.addExact(pending, receiptAmount(receipt));
            }
            return pending;
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        void flushDeliveries() {
            NBTTagCompound owner = recoveryOwner(player);
            NBTTagList queued = owner.getTagList(DELIVERY, 10);
            IStorageGrid storage = grid.getCache(IStorageGrid.class);
            PlayerSource source = context.craftingSource();
            if (storage == null || source == null) return;
            int processed = 0;
            long deadline = System.nanoTime() + 5_000_000L;
            for (int i = 0; i < queued.tagCount() && processed < 96; i++) {
                if (processed > 0 && System.nanoTime() >= deadline) break;
                NBTTagCompound receipt = queued.getCompoundTagAt(i);
                if (!id.equals(receipt.getString(DELIVERY_ORDER)) || receipt.getInteger(DELIVERY_INDEX) != index
                    || receiptAmount(receipt) <= 0) continue;
                processed++;
                IAEStack output = receipt.getBoolean("fluidDelivery")
                    ? AEFluidStack.create(FluidStack.loadFluidStackFromNBT(receipt))
                    : AEItemStack.create(HologramMaterials.recoveryItem(receipt));
                if (output == null || !output.isSameType((Object) requests.get(index).stack)) {
                    uncertainDelivery = true;
                    continue;
                }
                IMEMonitor monitor = storage.getMEMonitor(output.getStackType());
                if (monitor == null) continue;
                long offered = receiptAmount(receipt);
                output.setStackSize(offered);
                try {
                    IAEStack remainder = monitor.injectItems(output, Actionable.MODULATE, source);
                    long left = remainder == null ? 0 : remainder.getStackSize();
                    if (left < 0 || left > offered || remainder != null && !output.isSameType((Object) remainder))
                        throw new IllegalStateException("Invalid native crafting delivery acknowledgement");
                    receiptAmount(receipt, left);
                } catch (RuntimeException | LinkageError unacknowledged) {
                    // Never re-offer an amount whose native insertion may have succeeded before throwing.
                    NBTTagList audit = owner.getTagList(UNCERTAIN, 10);
                    audit.appendTag(receipt.copy());
                    owner.setTag(UNCERTAIN, audit);
                    receiptAmount(receipt, 0);
                    uncertainDelivery = true;
                }
            }
        }

        void finish(String outcome, String message, boolean cancelNative) {
            if (!active) return;
            active = false;
            state = outcome;
            status = message;
            finishedAt = clock;
            if (cancelNative) {
                if (future != null && !future.isDone()) future.cancel(true);
                if (link != null && !link.isDone() && !link.isCanceled()) link.cancel();
            }
            releaseDeliveries(player, id);
        }

        NBTTagCompound snapshot() {
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("active", active);
            result.setString("state", state);
            result.setString("status", status);
            result.setString("orderId", id);
            result.setLong("orderSequence", publicationSequence);
            result.setLong("orderUiSequence", uiSequence);
            result.setLong("planRevision", revision);
            result.setLong("placementReviewNonce", reviewNonce);
            result.setLong("done", done);
            result.setLong("total", total);
            result.setInteger("completedRows", index);
            result.setInteger("totalRows", requests.size());
            result.setLong("received", received);
            result.setLong("pendingDelivery", pendingDelivery());
            if (link != null) {
                result.setBoolean("standalone", link.isStandalone());
                result.setString("craftingId", link.getCraftingID());
            }
            if (cpu != null && link != null && !link.isDone() && !link.isCanceled()) {
                result.setLong("cpuTotal", Math.max(0, cpu.getStartItemCount()));
                result.setLong("cpuDone", Math.max(0, cpu.getStartItemCount() - cpu.getRemainingItemCount()));
                long cpuTotal = result.getLong("cpuTotal");
                if (cpuTotal > 0 && index < requests.size()) {
                    long partial = java.math.BigInteger.valueOf(requests.get(index).stack.getStackSize())
                        .multiply(java.math.BigInteger.valueOf(Math.min(cpuTotal, result.getLong("cpuDone"))))
                        .divide(java.math.BigInteger.valueOf(cpuTotal))
                        .longValue();
                    result.setLong("done", Math.min(total, done + partial));
                }
            }
            return result;
        }

        void publish() {
            NBTTagCompound current = snapshot();
            current.removeTag("orderSequence");
            if (current.equals(published)) return;
            published = (NBTTagCompound) current.copy();
            HologramSession attached = HologramWorkspace.find(player.getUniqueID());
            if (attached == null || attached.player != player || !session.equals(attached.id)) return;
            NBTTagCompound patch = new NBTTagCompound();
            NBTTagCompound progress = new NBTTagCompound();
            progress.setString("scope", "server");
            progress.setString("phase", "material.order");
            progress.setBoolean("materialOrderOnly", true);
            progress.setString("session", session);
            progress.setLong("planRevision", revision);
            progress.setLong("uiSequence", uiSequence);
            progress.setLong("placementReviewNonce", reviewNonce);
            progress.setString("orderId", id);
            progress.setLong("orderSequence", ++publicationSequence);
            progress.setString("outcome", active ? "running" : "complete".equals(state) ? "completed" : state);
            progress.setBoolean("complete", !active);
            progress.setString("status", status);
            progress.setLong("done", current.getLong("done"));
            progress.setLong("total", total);
            progress.setString("unit", "materials");
            current.setLong("orderSequence", publicationSequence);
            progress.setTag("materialOrder", current);
            patch.setBoolean("progressOnly", true);
            patch.setTag("progress", progress);
            HologramWorkspace.snapshotSink()
                .send(player, patch);
        }
    }

}

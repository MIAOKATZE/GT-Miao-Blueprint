package com.miaokatze.gtmb.hologram;

import java.math.BigInteger;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.PowerUnits;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.helpers.WirelessTerminalGuiObject;
import gregtech.api.GregTechAPI;
import gregtech.common.misc.WirelessNetworkManager;

/** Uses wireless terminal identity/range only; all AE power is withdrawn from the authenticated grid. */
final class HologramEnergyNetwork implements HologramEnergy.Bank {

    private static final String AE_RECOVERY = "gtmbHologramAeEnergyRecovery";
    private static final String EU_RECOVERY = "gtmbHologramEnergyRecovery";
    private static final Logger LOG = LogManager.getLogger("gtmb");
    private final EntityPlayerMP player;
    private final Connection connection;

    HologramEnergyNetwork(EntityPlayerMP player) {
        this(player, null);
    }

    HologramEnergyNetwork(EntityPlayerMP player, String networkKey) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote)
            throw new IllegalArgumentException("Energy banks require a server player");
        this.player = player;
        connection = findConnection(player, networkKey);
    }

    @Override
    public BigInteger euAvailable() {
        return WirelessNetworkManager.getUserEU(player.getUniqueID());
    }

    @Override
    public boolean takeEu(BigInteger amount) {
        return WirelessNetworkManager.addEUToGlobalEnergyMap(player.getUniqueID(), amount.negate());
    }

    @Override
    public void refundEu(BigInteger amount) {
        try {
            if (WirelessNetworkManager.addEUToGlobalEnergyMap(player.getUniqueID(), amount)) return;
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Retained buffer EU refund queued", failure);
        }
        NBTTagCompound owner = owner(player);
        BigInteger prior = owner.hasKey(EU_RECOVERY, 8) ? new BigInteger(owner.getString(EU_RECOVERY))
            : BigInteger.ZERO;
        owner.setString(
            EU_RECOVERY,
            prior.add(amount)
                .toString());
    }

    @Override
    public boolean aeAvailable() {
        if (connection == null) return false;
        Connection current = findConnection(player, connection.key);
        return current != null && current.grid == connection.grid && current.energy == connection.energy;
    }

    @Override
    public double takeAe(double amount, boolean simulate) {
        if (!aeAvailable()) return 0;
        // ONE transfers the exact cached AE amount; CONFIG would multiply a stored buffer by usage scaling.
        return connection.energy
            .extractAEPower(amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, PowerMultiplier.ONE);
    }

    @Override
    public void refundAe(double amount) {
        double remaining = amount;
        boolean uncertain = false;
        try {
            remaining = checkedRemainder(connection.energy.injectPower(amount, Actionable.MODULATE), amount);
        } catch (RuntimeException | LinkageError failure) {
            // A native provider may throw after accepting power. Automatic retry would risk a duplicate refund.
            uncertain = true;
            LOG.warn("Retained buffer AE refund needs review: native acknowledgement failed", failure);
        }
        if (remaining > 0) {
            NBTTagCompound owner = owner(player);
            NBTTagList queue = owner.getTagList(AE_RECOVERY, 10);
            NBTTagCompound row = new NBTTagCompound();
            row.setString("network", connection.key);
            row.setDouble("amount", remaining);
            if (uncertain) row.setBoolean("uncertain", true);
            queue.appendTag(row);
            owner.setTag(AE_RECOVERY, queue);
        }
    }

    @Override
    public double rfToEuPerHundred() {
        return GregTechAPI.mRFtoEU;
    }

    @Override
    public double aeToEu() {
        return PowerUnits.AE.convertTo(PowerUnits.EU, 1);
    }

    static void flushPending(EntityPlayerMP player) {
        NBTTagCompound owner = owner(player);
        NBTTagList queue = owner.getTagList(AE_RECOVERY, 10);
        NBTTagList remaining = new NBTTagList();
        for (int i = 0; i < queue.tagCount(); i++) {
            NBTTagCompound row = queue.getCompoundTagAt(i);
            double amount = row.getDouble("amount");
            if (!Double.isFinite(amount) || amount <= 0) continue;
            if (row.getBoolean("uncertain")) {
                remaining.appendTag(row.copy());
                continue;
            }
            Connection current = findConnection(player, row.getString("network"));
            if (current != null) {
                try {
                    amount = checkedRemainder(current.energy.injectPower(amount, Actionable.MODULATE), amount);
                    row.setDouble("amount", amount);
                } catch (RuntimeException | LinkageError failure) {
                    row.setBoolean("uncertain", true);
                    LOG.warn("Retained buffer AE recovery needs review: native acknowledgement failed", failure);
                }
            }
            if (amount > 0) remaining.appendTag(row.copy());
        }
        owner.setTag(AE_RECOVERY, remaining);
    }

    private static double checkedRemainder(double value, double offered) {
        if (!Double.isFinite(value) || value < 0 || value > offered)
            throw new IllegalStateException("Invalid native AE refund remainder");
        return value;
    }

    private static NBTTagCompound owner(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    private static Connection findConnection(EntityPlayerMP player, String requestedKey) {
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack == null || stack.stackSize <= 0) continue;
            try {
                IWirelessTermHandler handler = AEApi.instance()
                    .registries()
                    .wireless()
                    .getWirelessTerminalHandler(stack);
                if (handler == null) continue;
                String key = handler.getEncryptionKey(stack);
                if (key == null || (requestedKey != null && !requestedKey.equals(key))) continue;
                // Native constructor initializes terminal settings: isolate that work from the real inventory item.
                WirelessTerminalGuiObject terminal = new WirelessTerminalGuiObject(
                    handler,
                    stack.copy(),
                    player,
                    player.worldObj,
                    -1,
                    0,
                    0);
                if (!terminal.rangeCheck()) continue;
                IGridNode node = terminal.getActionableNode();
                if (node == null || !node.isActive()) continue;
                IGrid grid = node.getGrid();
                if (grid == null) continue;
                ISecurityGrid security = grid.getCache(ISecurityGrid.class);
                if (security == null || !security.hasPermission(player, SecurityPermissions.EXTRACT)
                    || !security.hasPermission(player, SecurityPermissions.BUILD)) continue;
                IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
                if (energy != null && energy.isNetworkPowered()) return new Connection(key, grid, energy);
            } catch (RuntimeException | LinkageError unavailable) {
                // A stale, out-of-range or unsupported terminal cannot authorize a network withdrawal.
            }
        }
        return null;
    }

    private static final class Connection {

        final String key;
        final IGrid grid;
        final IEnergyGrid energy;

        Connection(String key, IGrid grid, IEnergyGrid energy) {
            this.key = key;
            this.grid = grid;
            this.energy = energy;
        }
    }
}

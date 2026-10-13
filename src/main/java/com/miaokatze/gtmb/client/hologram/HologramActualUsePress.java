package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import cpw.mods.fml.common.gameevent.TickEvent;

/** A physical use binding held across real ticks; callbacks still follow the normal client/network path. */
final class HologramActualUsePress {

    private boolean active, releasing, checkWorldLock;
    private int heldTicks, repeatedPairs;
    private long lockedNonce = -1;
    private int[] lockedAnchor;
    private long started;
    private long startedWorldTick;

    void begin(Minecraft mc, boolean checkWorldLock) {
        require(!active, "a new physical right press requires the previous sampled release");
        active = true;
        releasing = false;
        this.checkWorldLock = checkWorldLock;
        heldTicks = repeatedPairs = 0;
        lockedNonce = -1;
        lockedAnchor = null;
        started = System.nanoTime();
        startedWorldTick = mc.theWorld.getTotalWorldTime();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        require(mc.gameSettings.keyBindUseItem.getIsKeyPressed(), "actual configured use binding did not press");
    }

    boolean tick(TickEvent.ClientTickEvent event, Minecraft mc) {
        if (!active) return false;
        require(System.nanoTime() - started < 10000000000L, "actual physical use press/release exceeded ten seconds");
        if (event.phase == TickEvent.Phase.START) {
            if (heldTicks < 8 || mc.theWorld.getTotalWorldTime() - startedWorldTick < 8
                || checkWorldLock && (lockedNonce < 0 || repeatedPairs < 2)) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
                heldTicks++;
            } else {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
                releasing = true;
            }
            return true;
        }
        if (releasing) {
            require(!mc.gameSettings.keyBindUseItem.getIsKeyPressed(), "use binding release was not sampled on END");
            if (checkWorldLock) require(
                mc.currentScreen == null && HologramClient.state != null
                    && HologramClient.state.data.getBoolean("virtualLocked")
                    && !HologramClient.state.data.getBoolean("open")
                    && HologramClient.state.data.getLong("placementReviewNonce") == lockedNonce,
                "held repeat callback issued review before a sampled physical release");
            active = false;
            if (checkWorldLock) System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS physical use hold ticks=" + heldTicks
                    + " worldTicks="
                    + (mc.theWorld.getTotalWorldTime() - startedWorldTick)
                    + " repeatedBlockAirPairs="
                    + repeatedPairs
                    + " first-lock stays-world nonce="
                    + lockedNonce);
            return true;
        }
        // Vanilla GUI focus clears all bindings; the held physical button is asserted again on the next sample.
        if (!checkWorldLock && mc.currentScreen != null)
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        require(
            mc.gameSettings.keyBindUseItem.getIsKeyPressed(),
            "physical use binding released before hold observation");
        if (checkWorldLock && HologramClient.state != null && HologramClient.state.data.getBoolean("virtualLocked")) {
            NBTTagCompound state = HologramClient.state.data;
            require(
                mc.currentScreen == null && !state.getBoolean("open"),
                "held first RIGHT reopened actual review UI");
            int[] anchor = { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") };
            if (lockedNonce < 0) {
                lockedNonce = state.getLong("placementReviewNonce");
                lockedAnchor = anchor;
            }
            require(
                lockedNonce == state.getLong("placementReviewNonce") && java.util.Arrays.equals(lockedAnchor, anchor),
                "held first RIGHT repeated a second world action or changed its lock");
            if (heldTicks % 4 == 0) {
                // Exercise both native callbacks on the repeat interval, while the binding stays physically held.
                mc.objectMouseOver = new MovingObjectPosition(
                    anchor[0],
                    anchor[1],
                    anchor[2],
                    1,
                    Vec3.createVectorHelper(anchor[0] + .5, anchor[1] + 1, anchor[2] + .5));
                mc.playerController.onPlayerRightClick(
                    mc.thePlayer,
                    mc.theWorld,
                    mc.thePlayer.getHeldItem(),
                    anchor[0],
                    anchor[1],
                    anchor[2],
                    1,
                    mc.objectMouseOver.hitVec);
                mc.objectMouseOver = null;
                mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
                repeatedPairs++;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

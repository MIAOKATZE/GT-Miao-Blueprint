package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.nbt.NBTTagCompound;

/** Default world preview facade; GUI previews own independent sessions with the identical raw renderer. */
public final class HologramCutRawPreview {

    private static final Set<HologramCutRawPreviewSession> SESSIONS = Collections
        .newSetFromMap(new WeakHashMap<HologramCutRawPreviewSession, Boolean>());
    private static final HologramCutRawPreviewSession WORLD = newSession();

    static {
        if (Minecraft.getMinecraft()
            .getResourceManager() instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) Minecraft.getMinecraft()
                .getResourceManager()).registerReloadListener(
                    manager -> {
                        for (HologramCutRawPreviewSession session : new java.util.ArrayList<>(SESSIONS))
                            session.reload();
                    });
        }
    }

    private HologramCutRawPreview() {}

    static HologramCutRawPreviewSession newSession() {
        HologramCutRawPreviewSession session = new HologramCutRawPreviewSession();
        SESSIONS.add(session);
        return session;
    }

    public static void update(NBTTagCompound entry) {
        WORLD.update(entry);
    }

    public static void render(double x, double y, double z, float ticks) {
        WORLD.render(x, y, z, ticks);
    }

    public static List<String> diagnostics() {
        return WORLD.diagnostics();
    }

    public static NBTTagCompound inspection() {
        return WORLD.inspection();
    }

    public static NBTTagCompound snapshot() {
        return WORLD.snapshot();
    }

    public static NBTTagCompound inspectionCell(int x, int y, int z) {
        return WORLD.inspectionCell(x, y, z);
    }

    public static String digest(NBTTagCompound entry) {
        return HologramCutRawPreviewSession.digest(entry);
    }

    public static void release() {
        WORLD.release();
    }
}

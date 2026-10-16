package com.miaokatze.gtmb.testlab;

import java.io.File;
import java.io.IOException;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/** Configuration-only lifecycle facade. Disabled calls do not initialize dimensions, worlds, handlers or probes. */
public final class TestLab {

    private static TestLabConfig config;
    private static TestLabRuntime runtime;
    private static boolean dimensionRegistered, providerRegistered, handlerRegistered;
    private static String reason = "Local config opt-in has not been loaded";

    private TestLab() {}

    public static void preInit(File configurationDirectory) {
        try {
            config = TestLabConfig.load(configurationDirectory);
            reason = config.enabled ? "Configured; awaiting dimension registration" : "Disabled in gtmb-testlab.cfg";
        } catch (Exception failure) {
            config = null;
            reason = "Config rejected: " + failure.getMessage();
            GTMiaoBlueprint.LOG.error("GTMB testlab stays disabled", failure);
        }
    }

    public static void init() {
        if (config == null || !config.enabled || providerRegistered) return;
        if (DimensionManager.isDimensionRegistered(config.dimension)) {
            reason = "Dimension ID occupied; lab disabled";
            return;
        }
        if (!DimensionManager.registerProviderType(config.provider, TestLabWorldProvider.class, false)) {
            reason = "Provider ID occupied; lab disabled";
            return;
        }
        providerRegistered = true;
        try {
            com.miaokatze.gtmb.util.ProbeChunkTickets.ensureRegistered();
            // Registering the dimension during init would make loadAllWorlds eagerly create it.
            reason = "Provider configured; awaiting save ownership validation after normal worlds load";
        } catch (RuntimeException failure) {
            DimensionManager.unregisterProviderType(config.provider);
            dimensionRegistered = providerRegistered = false;
            reason = "Registration failed: " + failure;
            GTMiaoBlueprint.LOG.error("GTMB testlab registration rolled back", failure);
        }
    }

    public static void serverStarting(FMLServerStartingEvent event) {
        if (config == null || !config.enabled || !providerRegistered || dimensionRegistered) return;
        try {
            if (DimensionManager.isDimensionRegistered(config.dimension))
                throw new IOException("Dimension ID became occupied; lab disabled for this save");
            if (MinecraftServer.getServer() == null || MinecraftServer.getServer()
                .worldServerForDimension(0) == null)
                throw new IOException("Overworld unavailable for save ownership validation");
            new TestLabOwnership(
                MinecraftServer.getServer()
                    .worldServerForDimension(0)
                    .getSaveHandler()
                    .getWorldDirectory()
                    .toPath(),
                config);
            // FMLServerStarting follows loadAllWorlds. Register only after the actual save was validated.
            DimensionManager.registerDimension(config.dimension, config.provider);
            dimensionRegistered = true;
            runtime = new TestLabRuntime(config);
            FMLCommonHandler.instance()
                .bus()
                .register(runtime);
            handlerRegistered = true;
            if (config.performance) HologramRuntimeMetrics.setEnabled(true);
            event.registerServerCommand(new TestLabCommand(runtime));
            reason = "Enabled for the validated save; world remains unloaded until an operation or durable cut redo";
        } catch (Exception failure) {
            if (handlerRegistered && runtime != null) FMLCommonHandler.instance()
                .bus()
                .unregister(runtime);
            handlerRegistered = false;
            runtime = null;
            if (config.performance) HologramRuntimeMetrics.setEnabled(false);
            if (dimensionRegistered) DimensionManager.unregisterDimension(config.dimension);
            dimensionRegistered = false;
            reason = "Save registration refused: " + failure.getMessage();
            GTMiaoBlueprint.LOG.error("GTMB testlab stays unavailable for this save", failure);
        }
    }

    public static void serverStopping() {
        if (runtime == null) return;
        runtime.stop("Server stopping");
        FMLCommonHandler.instance()
            .bus()
            .unregister(runtime);
        handlerRegistered = false;
        runtime = null;
        if (config.performance) HologramRuntimeMetrics.setEnabled(false);
        // The dimension is unregistered only after Forge has stopped/unloaded the server worlds.
    }

    public static void serverStopped() {
        if (!dimensionRegistered || config == null) return;
        if (DimensionManager.isDimensionRegistered(config.dimension)
            && DimensionManager.getProviderType(config.dimension) == config.provider)
            DimensionManager.unregisterDimension(config.dimension);
        dimensionRegistered = false;
        reason = "Server stopped; provider retained, next save requires a fresh ownership validation";
    }

    public static boolean isEnabled() {
        return config != null && config.enabled && dimensionRegistered;
    }

    static TestLabConfig configuration() {
        if (!isEnabled()) throw new IllegalStateException("Testlab is disabled");
        return config;
    }

    public static NBTTagCompound status() {
        NBTTagCompound n = runtime == null ? new NBTTagCompound() : runtime.status();
        n.setBoolean("configEnabled", config != null && config.enabled);
        n.setBoolean("enabled", isEnabled());
        n.setBoolean("dimensionRegistered", dimensionRegistered);
        n.setBoolean("providerRegistered", providerRegistered);
        n.setBoolean("providerReady", providerRegistered);
        n.setBoolean("handlerRegistered", handlerRegistered);
        n.setString("reason", reason);
        n.setInteger("dimensionId", config == null ? TestLabConfig.DEFAULT_DIMENSION : config.dimension);
        n.setInteger("providerId", config == null ? TestLabConfig.DEFAULT_DIMENSION : config.provider);
        n.setInteger("maxOwnedChunks", config == null ? 0 : config.maxChunks);
        if (runtime == null) {
            n.setString("state", isEnabled() ? "IDLE" : "DISABLED");
            n.setString("runId", "");
            n.setString("runKind", "");
        }
        if (config != null) {
            NBTTagList zones = new NBTTagList();
            for (TestLabZone zone : config.zones) zones.appendTag(zone.tag());
            n.setTag("zones", zones);
        }
        return n;
    }

    public static NBTTagCompound report() {
        return runtime == null ? new NBTTagCompound() : runtime.report();
    }

    public static ItemStack testItem() {
        return runtime == null || runtime.actor() == null ? null
            : runtime.actor()
                .tool();
    }

    /** Narrow read-only admission for a common-side native fixture bridge; does not load chunks or grant permission. */
    public static boolean isOwnedFixture(World world, EntityPlayerMP actor, int x, int y, int z, int sx, int sy,
        int sz) {
        return isEnabled() && runtime != null && runtime.ownsFixture(world, actor, x, y, z, sx, sy, sz);
    }

    /** Actual special-effect entry: ordinary projector behavior is separate, and is never made unlimited. */
    public static boolean useTestItem(ItemStack item, EntityPlayerMP actor, World world, int x, int y, int z) {
        return isEnabled() && runtime != null
            && runtime.active()
            && runtime.actor() != null
            && runtime.ownsFixture(world, actor, x, y, z, 1, 1, 1)
            && runtime.actor()
                .use(item, actor, world, x, y, z);
    }
}

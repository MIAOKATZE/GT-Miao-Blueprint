package miaotest.host;

import static miaotest.host.ClientAccess.call;
import static miaotest.host.ClientAccess.field;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.GtmbWorldProjectionProbe;

/** Project scenario using the shared host's exact owned-save and integrated-server lifecycle. */
public final class GtmbWorldProjectionController {
    private final HostClientQa host;
    private boolean begun, cleanup;
    private int readyTicks;

    public GtmbWorldProjectionController(HostClientQa host) {
        this.host = host;
        if (!"gtmb".equals(host.module) || !"world-projection-events".equals(host.profile))
            throw new IllegalArgumentException("Only the explicit GTMB world event profile may use this driver");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void tick() throws Exception {
        Object mc = host.mc;
        if (!host.launchedIntegrated) {
            Object menu = field(mc, "currentScreen|field_71462_r");
            if (menu == null || !(Class.forName("net.minecraft.client.gui.GuiMainMenu").isInstance(menu)
                || "lumien.custommainmenu.gui.GuiCustom".equals(menu.getClass().getName()))) return;
            host.check("world.no-preexisting-client-world", field(mc, "theWorld|field_71441_e") == null
                && call(mc, "getIntegratedServer|func_71401_C") == null, "Never replace a preexisting world");
            Path requested = Paths.get(System.getProperty("miaotest.host.worldPath"));
            OwnedClientScope.save(requested, requested, host.run);
            Class<?> settings = Class.forName("net.minecraft.world.WorldSettings");
            Class<?> gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
            Class<?> worldType = Class.forName("net.minecraft.world.WorldType");
            Object config = settings.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType)
                .newInstance(61312138L, Enum.valueOf((Class) gameType, "SURVIVAL"), false, false,
                    field(worldType, "FLAT|field_77138_c"));
            host.launchedIntegrated = true;
            call(mc, "launchIntegratedServer|func_71371_a", host.world, "Owned GTMB world event QA", config);
            return;
        }
        Object world = field(mc, "theWorld|field_71441_e");
        Object player = field(mc, "thePlayer|field_71439_g");
        if (!begun) {
            if (world == null || player == null || host.integratedServer == null
                || field(mc, "currentScreen|field_71462_r") != null || ++readyTicks < 20) return;
            host.check("world.real-integrated-login", call(mc, "getIntegratedServer|func_71401_C") == host.integratedServer
                && call(mc, "getNetHandler|func_147114_u") != null, "Real client play connection and the exact owned integrated server");
            Object serverWorld = call(host.integratedServer, "worldServerForDimension|func_71218_a", 0);
            host.ownedSave(serverWorld);
            host.check("world.survival-actor", !Boolean.TRUE.equals(field(field(player, "capabilities|field_71075_bZ"),
                "isCreativeMode|field_75098_d")), "Survival fixture, no creative permissions or material claims");
            GtmbWorldProjectionProbe.begin(mc, host.run, host.out);
            begun = true;
        }
        GtmbWorldProjectionProbe.tick();
        if (!GtmbWorldProjectionProbe.done()) return;
        Throwable failure = GtmbWorldProjectionProbe.failure();
        cleanup();
        JsonObject report = GtmbWorldProjectionProbe.report();
        host.result.add("nativeProfile", report);
        if (failure == null) {
            host.check("gtmb.native-world-projection-events", report.has("passed") && report.get("passed").getAsBoolean(), report.toString());
            host.check("gtmb.probe-cleanup-complete", report.has("cleanupComplete") && report.get("cleanupComplete").getAsBoolean(),
                "Server fixture restored; client observers removed before owned world unload");
        }
        host.finish(failure);
    }

    public void cleanup() throws Exception {
        if (!cleanup && begun && GtmbWorldProjectionProbe.done()) {
            GtmbWorldProjectionProbe.cleanup();
            cleanup = true;
        }
    }
}

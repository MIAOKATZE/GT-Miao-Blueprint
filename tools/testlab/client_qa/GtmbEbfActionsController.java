package miaotest.host;

import static miaotest.host.ClientAccess.call;
import static miaotest.host.ClientAccess.field;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.GtmbEbfActionsProbe;

/** Bounded real GUI/native construction scene inside the host's existing owned integrated lifecycle. */
public final class GtmbEbfActionsController {
    private final HostClientQa host;
    private boolean begun;
    private int readyTicks;

    public GtmbEbfActionsController(HostClientQa host) {
        this.host = host;
        if (!"gtmb".equals(host.module) || !"ebf-actions".equals(host.profile))
            throw new IllegalArgumentException("Only the explicit GTMB ebf-actions profile may use this driver");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void tick() throws Exception {
        Object mc = host.mc;
        if (!host.launchedIntegrated) {
            Object menu = field(mc, "currentScreen|field_71462_r");
            if (menu == null || !(Class.forName("net.minecraft.client.gui.GuiMainMenu").isInstance(menu)
                || "lumien.custommainmenu.gui.GuiCustom".equals(menu.getClass().getName()))) return;
            host.check("ebf.no-preexisting-world", field(mc, "theWorld|field_71441_e") == null
                && call(mc, "getIntegratedServer|func_71401_C") == null, "Never replace an existing client world");
            Path requested = Paths.get(System.getProperty("miaotest.host.worldPath"));
            OwnedClientScope.save(requested, requested, host.run);
            Class<?> settings = Class.forName("net.minecraft.world.WorldSettings");
            Class<?> gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
            Class<?> worldType = Class.forName("net.minecraft.world.WorldType");
            Object config = settings.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType)
                .newInstance(61312138L, Enum.valueOf((Class) gameType, "SURVIVAL"), false, false,
                    field(worldType, "FLAT|field_77138_c"));
            host.launchedIntegrated = true;
            call(mc, "launchIntegratedServer|func_71371_a", host.world, "Owned GTMB three-action QA", config);
            return;
        }
        if (!begun) {
            Object world = field(mc, "theWorld|field_71441_e"), player = field(mc, "thePlayer|field_71439_g");
            if (world == null || player == null || host.integratedServer == null
                || field(mc, "currentScreen|field_71462_r") != null || ++readyTicks < 20) return;
            host.check("ebf.real-integrated-login", call(mc, "getIntegratedServer|func_71401_C") == host.integratedServer
                && call(mc, "getNetHandler|func_147114_u") != null, "Exact owned integrated server and real play connection");
            host.ownedSave(call(host.integratedServer, "worldServerForDimension|func_71218_a", 0));
            host.check("ebf.survival-actor", !Boolean.TRUE.equals(field(field(player, "capabilities|field_71075_bZ"),
                "isCreativeMode|field_75098_d")), "No creative actor or permission bypass");
            GtmbEbfActionsProbe.begin(mc, host.run, host.out);
            begun = true;
        }
        GtmbEbfActionsProbe.tick();
        if (!GtmbEbfActionsProbe.done()) return;
        JsonObject report = GtmbEbfActionsProbe.report();
        host.result.add("nativeProfile", report);
        Throwable failure = GtmbEbfActionsProbe.failure();
        GtmbEbfActionsProbe.cleanup();
        if (failure == null) {
            host.check("ebf.three-native-actions", report.has("ebfActionsSlicePassed")
                && report.get("ebfActionsSlicePassed").getAsBoolean() && report.get("passed").getAsBoolean(), report.toString());
            host.check("ebf.business-restored", report.get("restored").getAsBoolean()
                && !report.get("cleanupPending").getAsBoolean() && report.get("recoveryClear").getAsBoolean(),
                "Actor restored; no pending native journal, delivery or recovery before world unload");
        }
        host.finish(failure);
    }

    public void cleanup() throws Exception {
        if (begun && GtmbEbfActionsProbe.done()) GtmbEbfActionsProbe.cleanup();
    }
}

package miaotest.host;

import com.google.gson.JsonObject;
import java.util.function.BiConsumer;

/** Project-owned canvas facts; shared diagnostics only supplies the observation row. */
public final class GtmbClientObservations implements BiConsumer<Object, JsonObject> {
    public void accept(Object minecraft, JsonObject row) {
        try {
            Object gui = ClientAccess.field(minecraft, "currentScreen|field_71462_r");
            if (gui != null && gui.getClass().getName().startsWith("com.miaokatze.gtmb.client.hologram.")) {
                Object ui = ClientAccess.field(gui, "ui"), canvas = ClientAccess.field(ui, "canvas");
                try { row.addProperty("gtmbCanvasGeneration", (Number) ClientAccess.call(canvas, "renderGeneration")); }
                catch (Throwable unavailable) { row.addProperty("gtmbCanvasGenerationUnavailable", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage()); }
            }
        } catch (Throwable unavailable) { row.addProperty("screenDiagnosticUnavailable", unavailable.toString()); }
    }
}

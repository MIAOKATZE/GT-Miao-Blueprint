package com.miaokatze.gtmb.client.hologram;

/** Runs with testlab output removed from the runtime classpath. */
public final class HologramProductionTestlabIsolation {

    private HologramProductionTestlabIsolation() {}

    public static void main(String[] args) throws Exception {
        try {
            Class.forName("com.miaokatze.gtmb.testlab.TestLab");
            throw new AssertionError("testlab output leaked onto the normal runtime classpath");
        } catch (ClassNotFoundException expected) {}
        System.setProperty("gtmb.hologram.smoketest", "true");
        System.setProperty("gtmb.hologram.realInteractionSmoke", "true");
        HologramUiModules modules = new HologramUiModules(null);
        if (modules.diagnostics == null || modules.diagnostics.getClass() != HologramUiDiagnostics.class)
            throw new AssertionError("normal UI must construct with passive diagnostics");
        if (modules.diagnostics.captureRenderSmokeAction() || modules.diagnostics.smokeAltSelection()
            || modules.diagnostics.smokeCtrlSelection()
            || modules.diagnostics.smokeSameTypeSelection())
            throw new AssertionError("JVM test flags must not change normal UI actions");
        if (com.miaokatze.gtmb.main.OptionalTestLab.call("com.miaokatze.gtmb.testlab.TestLab", "status") != null)
            throw new AssertionError("missing optional lifecycle implementation must remain inert");
        try {
            modules.diagnostics.smokeRenderScenario("construction");
            throw new AssertionError("normal UI accepted a synthetic test action");
        } catch (IllegalStateException expected) {}
        System.out.println("GTMB normal UI construction and absent testlab hooks PASS");
    }
}

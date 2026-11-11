// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.miaokatzecore.api.testlab.LabCase;
import com.miaokatze.miaokatzecore.api.testlab.LabEvents;
import com.miaokatze.miaokatzecore.api.testlab.LabModule;
import com.miaokatze.miaokatzecore.api.testlab.LabResult;

/** Stateless adapter: existing runtime remains the sole actor, ownership, lease and backend authority. */
final class TestLabModule implements LabModule {

    static final TestLabModule INSTANCE = new TestLabModule();
    private final List<LabCase> catalog;

    private TestLabModule() {
        List<LabCase> cases = new ArrayList<LabCase>();
        for (String kind : new String[] { "minimal", "stress", "composite", "native-build", "native-test",
            "recovery-stage", "recovery-resume", "construction-performance", "reset", "refresh" }) {
            boolean connected = "composite".equals(kind) || "native-test".equals(kind);
            cases.add(
                new LabCase(
                    kind,
                    kind,
                    connected ? "client-server" : "server",
                    tags(kind),
                    "minimal".equals(kind),
                    connected));
        }
        cases.add(
            new LabCase(
                "client-ui",
                "Minimal natural-frame native UI profile",
                "client",
                new LinkedHashSet<String>(
                    Arrays.asList(
                        "incoming-fixture",
                        "real-native-ui",
                        "natural-render-tick",
                        "synthetic-native-input",
                        "jvm:gtmb.testlab.clientUi",
                        "jvm:gtmb.testlab.clientUiReceiptDir",
                        "entry:com.miaokatze.gtmb.client.hologram.HologramTestLabUiProfile",
                        "method:install")),
                false,
                false));
        LegacyProbeCatalog.addTo(cases);
        catalog = Collections.unmodifiableList(cases);
    }

    private static java.util.Set<String> tags(String kind) {
        LinkedHashSet<String> tags = new LinkedHashSet<String>(Arrays.asList("owned-world", "bounded-tick"));
        if ("reset".equals(kind) || "refresh".equals(kind))
            tags.addAll(Arrays.asList("cleanup", "production-undo", "action:" + kind));
        else {
            tags.add("action:run");
            if ("stress".equals(kind)) tags.addAll(Arrays.asList("codec-memory", "real-scheduler"));
            else if (kind.startsWith("native-"))
                tags.addAll(Arrays.asList("real-native", "persistent-platform", "external-workflow-probe"));
            else tags.addAll(Arrays.asList("real-backend", "survival-payment"));
            if (kind.startsWith("recovery-")) tags.add("durable-recovery");
        }
        return tags;
    }

    public String id() {
        return "gtmb";
    }

    public boolean enabled() {
        return TestLab.clientMappingAvailable();
    }

    public List<LabCase> cases() {
        return catalog;
    }

    /** Canonical suite selection uses declared defaults, never the complete legacy catalog. */
    String[] commandArguments(String[] args) {
        if (args.length != 2 || !"run".equals(args[0]) || !"all".equals(args[1])) return args;
        String selected = null;
        for (LabCase entry : catalog) {
            if (!entry.defaultSuite()) continue;
            if (selected != null) throw new CommandException("GTMB default suite requires one bounded task");
            selected = entry.id();
        }
        if (selected == null) throw new CommandException("GTMB default suite has no declared task");
        String[] forwarded = args.clone();
        forwarded[1] = selected;
        return forwarded;
    }

    public void execute(ICommandSender sender, String[] args) {
        if (!sender.canCommandSenderUseCommand(2, "miaotest")) throw new CommandException("Operator level 2 required");
        if (args.length == 2 && "run".equals(args[0])
            && (args[1].startsWith("legacy-") || "client-ui".equals(args[1]))) {
            LabEvents.publish(
                id(),
                "",
                args[1],
                "task.finished",
                "isolated-jvm-launch-required",
                LabResult.UNSUPPORTED,
                "Preserved entry class/method/JVM properties are listed in catalog tags; use the isolated launch profile");
            throw new CommandException(
                "Legacy probes require their explicit isolated JVM launch profile; see catalog entry/method/jvm tags");
        }
        TestLabRuntime runtime = TestLab.runtime();
        if (runtime == null) throw new CommandException("GTMB lab has no validated server save");
        new TestLabCommand(runtime).processCommand(sender, commandArguments(args));
    }

    public String status() {
        return TestLab.status()
            .toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot() {
        NBTTagCompound facts = TestLab.status();
        NBTTagCompound report = TestLab.report();
        facts.setString("outcome", facts.getBoolean("active") ? "RUNNING" : facts.getString("state"));
        facts.setBoolean("reportWritten", facts.getBoolean("reportPersisted"));
        facts.setString("reportPath", report.getString("reportPath"));
        facts.setTag(
            "coverage",
            report.getCompoundTag("coverage")
                .copy());
        facts.setTag(
            "retained",
            report.getCompoundTag("retained")
                .copy());
        return Collections.unmodifiableMap((Map<String, Object>) TestLabReport.json(facts));
    }

    public boolean ownsWorld(World world) {
        return TestLab.isEnabled() && world != null
            && world.provider instanceof TestLabWorldProvider
            && DimensionManager.getWorld(world.provider.dimensionId) == world
            && world.provider.dimensionId == TestLab.configuration().dimension
            && DimensionManager.getProviderType(world.provider.dimensionId) == TestLab.configuration().provider;
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** GTSR .70's structure-check semantics: the U declaration contains alternatives, not simultaneous quotas. */
final class HologramGtsrBasicHatchPolicy {

    private static final String CONTROLLER = "com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray";
    private static final String DEFINITION = "com.miaokatze.gtsr.common.machine.turbine.MegaSteamTurbineStructureDef";
    private static final String CONTROLLER_ROLE = CONTROLLER + "$MegaSteamTurbineArrayHatchElement";
    private static final String GTSR_ROLE = "com.miaokatze.gtsr.common.api.enums.GTSRHatchElement";

    static final List<String> GROUPS = Collections.unmodifiableList(Arrays.asList("Input", "Output", "Dynamo"));
    private static final Set<String> U_ROLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(
            Arrays.asList(
                "InputHatch",
                "OutputHatch",
                "SteamInputBus",
                "PressureSteamInput",
                "OverpressureInput",
                "CoolingHatch",
                "DynamoOutput")));

    private static final ClassValue<Boolean> PINNED_CONTROLLER = new ClassValue<Boolean>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName()
                .equals(CONTROLLER)
                && exact(type, CONTROLLER, "5fe1794fb2f6b540e34e5ce3d8a68ffed73ff9a7f68e7ac84689519441d06786")
                && exact(type, DEFINITION, "b941852dae4032a09c08935312c641bcc4d3e4d76e1080de415072cae44a1105")
                && exact(type, CONTROLLER_ROLE, "23dbea50887ba6cbb3b27927cd2b439e4a515cb222172680085d7f88b6ae0041")
                && exact(type, GTSR_ROLE, "cf1f11b08c6aaf55ee604747dd18a52a363a4ac19d0fd1e196aa08ffab49a4db");
        }
    };

    private HologramGtsrBasicHatchPolicy() {}

    static boolean capturedController(Object controller) {
        return controller != null && CONTROLLER.equals(
            controller.getClass()
                .getName())
            && PINNED_CONTROLLER.get(controller.getClass());
    }

    private static boolean exact(Class<?> owner, String name, String expected) {
        String resource = "/" + name.replace('.', '/') + ".class";
        try (InputStream bytes = owner.getResourceAsStream(resource)) {
            if (bytes == null) return false;
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096];
            int total = 0;
            for (int read; (read = bytes.read(buffer)) >= 0;) {
                total += read;
                if (total > 2 * 1024 * 1024) return false;
                hash.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte b : hash.digest()) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return expected.contentEquals(result);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Every native role must be present on the same U alternative chain, not inferred from a controller name. */
    static boolean knownU(Map<String, IHatchElement<?>> roles) {
        if (!U_ROLES.equals(roles.keySet()) || roles.get("InputHatch") != HatchElement.InputHatch
            || roles.get("OutputHatch") != HatchElement.OutputHatch) return false;
        for (String key : U_ROLES) {
            if ("InputHatch".equals(key) || "OutputHatch".equals(key)) continue;
            IHatchElement<?> type = roles.get(key);
            String owner = "SteamInputBus".equals(key) ? GTSR_ROLE : CONTROLLER_ROLE;
            if (!(type instanceof Enum<?>role) || !key.equals(role.name())
                || !owner.equals(
                    role.getDeclaringClass()
                        .getName())
                || role.getDeclaringClass()
                    .getClassLoader() != IHatchElement.class.getClassLoader())
                return false;
        }
        return true;
    }

    /** Credit installed declared alternatives. Newly allocated models must also have formal direct-placement proof. */
    static boolean[] provedGroups(Map<String, IHatchElement<?>> roles, IMetaTileEntity device, boolean automatic) {
        boolean[] result = new boolean[GROUPS.size()];
        if (device == null) return result;
        for (Map.Entry<String, IHatchElement<?>> entry : roles.entrySet()) {
            String role = entry.getKey();
            int group = group(role);
            if (group < 0 || result[group]) continue;
            if (automatic && (!"InputHatch".equals(role) && !"OutputHatch".equals(role) && !"DynamoOutput".equals(role)
                || !ordinaryCandidate(role, device))) continue;
            if (HologramHatchPolicy.matchesHatch(entry.getValue(), device)) result[group] = true;
        }
        return result;
    }

    static int group(String role) {
        return switch (role) {
            case "InputHatch", "SteamInputBus", "PressureSteamInput", "OverpressureInput" -> 0;
            case "OutputHatch", "CoolingHatch" -> 1;
            case "DynamoOutput" -> 2;
            default -> -1;
        };
    }

    /** Exact pinned U's two stock roles share their GT blacklist, plus its inspected inherited class exclusions. */
    static boolean ordinaryCandidate(String role, IMetaTileEntity device) {
        if ("DynamoOutput".equals(role))
            return device instanceof gregtech.api.metatileentity.implementations.MTEHatchDynamo
                || device instanceof tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
        if (!"InputHatch".equals(role) && !"OutputHatch".equals(role)) return false;
        Class<?> type = device.getClass();
        if (HatchElement.InputHatch.mteBlacklist()
            .contains(type)
            || HatchElement.OutputHatch.mteBlacklist()
                .contains(type))
            return false;
        for (; type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if ("com.miaokatze.gtsr.common.machine.base.MTEOverpressureTurbineInputHatch".equals(name)
                || "com.miaokatze.gtsr.common.machine.base.MTESteamCoolingHatch".equals(name)) return false;
        }
        return true;
    }
}

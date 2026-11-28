// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;

import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Loader;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Optional consumption of the reviewed public GTSR 2.1.89/2.1.90 status API. No private producer state. */
final class HologramOreCenterSiteAccess {

    private static final String CENTER = "com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter";

    @Desugar
    record State(int tier, int mask) {

        int main() {
            return tier + 1;
        }

        int sectorCount() {
            return Integer.bitCount(mask);
        }
    }

    private static final ClassValue<Method> STATUS = new ClassValue<Method>() {

        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("operationStatus");
                if (method.getDeclaringClass() != type || method.getReturnType() != NBTTagCompound.class)
                    throw new IllegalArgumentException("NATIVE_SITE_CENTER_STATUS_SIGNATURE");
                return method;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("NATIVE_SITE_CENTER_STATUS_SIGNATURE", failure);
            }
        }
    };

    private HologramOreCenterSiteAccess() {}

    static boolean supports(Object machine) {
        return machine != null && machine.getClass()
            .getName()
            .equals(CENTER);
    }

    static State read(BaseMetaTileEntity controller) throws IOException {
        Object machine = controller.getMetaTileEntity();
        if (!supports(machine)) throw new IOException("NATIVE_SITE_CENTER_CLASS");
        requireReviewedVersion(machine.getClass());
        NBTTagCompound status;
        try {
            status = (NBTTagCompound) STATUS.get(machine.getClass())
                .invoke(machine);
        } catch (InvocationTargetException failure) {
            throw new IOException("NATIVE_SITE_CENTER_STATUS_FAILED", failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("NATIVE_SITE_CENTER_STATUS_UNAVAILABLE", failure);
        }
        if (status == null || !status.hasKey("Tier", 3)
            || !status.hasKey("Sectors", 3)
            || !status.hasKey("Formed", 1)
            || !status.hasKey("CheckingFormation", 1)
            || !status.getBoolean("Formed")
            || status.getBoolean("CheckingFormation")
            || status.getBoolean("ModuleMigrationPending"))
            throw new IOException("NATIVE_SITE_CENTER_FORMATION_UNAVAILABLE");
        int tier = status.getInteger("Tier"), mask = status.getInteger("Sectors");
        if (tier < 0 || tier > 3 || mask < 1 || mask > 63 || (mask & mask + 1) != 0)
            throw new IOException("NATIVE_SITE_CENTER_NON_PREFIX_OR_UNKNOWN_LAYOUT");
        // Current native construction emits the center and wings, not installed module buildings.
        if (!status.hasKey("Installed", 3) || status.getInteger("Installed") != 0)
            throw new IOException("NATIVE_SITE_CENTER_MODULE_EXTENT_UNSUPPORTED");
        return new State(tier, mask);
    }

    private static void requireReviewedVersion(Class<?> type) throws IOException {
        var loaded = Loader.instance()
            .getIndexedModList();
        if (loaded != null && loaded.containsKey("gtsr")) {
            requireVersion(
                loaded.get("gtsr")
                    .getVersion());
            return;
        }
        // Detached fixtures do not run Forge discovery. Read metadata from this actual class's
        // source jar, rather than accepting a similarly named method from an arbitrary classpath.
        var resource = type.getResource("MTEMineralIntegrationCenter.class");
        if (resource == null || !(resource.openConnection() instanceof JarURLConnection connection))
            throw new IOException("NATIVE_SITE_CENTER_SOURCE_UNAVAILABLE");
        connection.setUseCaches(false);
        try {
            var origin = type.getProtectionDomain()
                .getCodeSource();
            if (origin == null) throw new IOException("NATIVE_SITE_CENTER_SOURCE_UNAVAILABLE");
            var location = origin.getLocation();
            // LaunchWrapper's detached fixture CodeSource can name the exact class entry.
            // Normalize its containing jar; Forge's normal file: jar location uses the same comparison.
            if (location.openConnection() instanceof JarURLConnection sourceConnection) {
                sourceConnection.setUseCaches(false);
                String entryName = sourceConnection.getEntryName();
                if (entryName != null && !entryName.equals(
                    type.getName()
                        .replace('.', '/') + ".class"))
                    throw new IOException("NATIVE_SITE_CENTER_SOURCE_MISMATCH");
                location = sourceConnection.getJarFileURL();
            }
            if (!new java.io.File(location.toURI()).getCanonicalFile()
                .equals(
                    new java.io.File(
                        connection.getJarFileURL()
                            .toURI()).getCanonicalFile()))
                throw new IOException("NATIVE_SITE_CENTER_SOURCE_MISMATCH");
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("NATIVE_SITE_CENTER_SOURCE_UNAVAILABLE", invalid);
        }
        try (var jar = connection.getJarFile()) {
            var entry = jar.getJarEntry("mcmod.info");
            if (entry == null) throw new IOException("NATIVE_SITE_CENTER_SOURCE_VERSION_UNAVAILABLE");
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonElement parsed = new JsonParser().parse(reader);
                JsonElement mods = parsed.isJsonObject() ? parsed.getAsJsonObject()
                    .get("modList") : parsed;
                if (mods == null || !mods.isJsonArray())
                    throw new IOException("NATIVE_SITE_CENTER_SOURCE_VERSION_UNAVAILABLE");
                for (JsonElement value : mods.getAsJsonArray()) {
                    if (!value.isJsonObject()) continue;
                    JsonObject metadata = value.getAsJsonObject();
                    if (metadata.has("modid") && "gtsr".equals(
                        metadata.get("modid")
                            .getAsString())) {
                        requireVersion(
                            metadata.get("version")
                                .getAsString());
                        return;
                    }
                }
            } catch (RuntimeException malformed) {
                throw new IOException("NATIVE_SITE_CENTER_SOURCE_VERSION_UNAVAILABLE", malformed);
            }
        }
        throw new IOException("NATIVE_SITE_CENTER_SOURCE_VERSION_UNAVAILABLE");
    }

    private static void requireVersion(String version) throws IOException {
        if (!"2.1.89".equals(version) && !"v2.1.89".equals(version)
            && !"2.1.90".equals(version)
            && !"v2.1.90".equals(version)) throw new IOException("NATIVE_SITE_CENTER_UNREVIEWED_VERSION");
    }
}

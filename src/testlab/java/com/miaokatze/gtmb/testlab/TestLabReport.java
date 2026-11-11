// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Bounded per-run evidence. Metrics are codec/task counters and wall time, never advertised as TPS. */
final class TestLabReport {

    final NBTTagCompound data = new NBTTagCompound();
    final long started = System.nanoTime();

    TestLabReport(String id, String kind, TestLabConfig config) {
        data.setString("runId", id);
        data.setString("kind", kind);
        data.setString("state", "RUNNING");
        data.setString(
            "scope",
            "Dedicated/common server backend and detached snapshot/codec observations; not full-pack, GL or Netty QA");
        data.setBoolean("serverBackendOnly", true);
        data.setBoolean("notNetty", true);
        data.setString(
            "timingScope",
            "Lab work and cleanup wall time excludes final report persistence; status/report expose reportWriteNanos after both atomic files complete");
        NBTTagCompound identity = new NBTTagCompound();
        for (java.util.Map.Entry<String, String> entry : com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity
            .capture(TestLabReport.class)
            .asMap()
            .entrySet()) identity.setString(entry.getKey(), entry.getValue());
        data.setTag("artifact", identity);
        data.setString("module", "gtmb");
        NBTTagCompound coverage = new NBTTagCompound();
        coverage.setString("backend", "production-common-services");
        coverage.setString("actor", "pending-admission");
        coverage.setString("transport", "NOT_RUN");
        coverage.setString("render", "NOT_RUN");
        coverage.setBoolean("nettyDispatchVerified", false);
        coverage.setBoolean("clientRenderVerified", false);
        data.setTag("coverage", coverage);
        NBTTagCompound retained = new NBTTagCompound();
        retained.setBoolean("persistentNativePlatforms", config.nativePlatforms);
        retained.setString(
            "fixtureBlocks",
            "Retained inside owned volumes until explicit reset; native platforms survive reset");
        retained.setString(
            "durableRecovery",
            "Pending production recovery journals are retained; reset cannot bypass them");
        data.setTag("retained", retained);
        data.setTag("checks", new NBTTagList());
        data.setTag("counters", new NBTTagCompound());
        data.setTag("timing", new NBTTagCompound());
        data.setTag("metricsStart", new NBTTagCompound());
        data.setTag("metricsEnd", new NBTTagCompound());
        NBTTagList zones = new NBTTagList();
        for (TestLabZone zone : config.zones) zones.appendTag(zone.tag());
        data.setTag("zones", zones);
    }

    void check(String name, String status, String detail) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("name", name);
        n.setString("status", status);
        n.setString("detail", detail);
        data.getTagList("checks", 10)
            .appendTag(n);
        event("check", name, result(status), "");
    }

    void event(String phase, String check, com.miaokatze.miaokatzecore.api.testlab.LabResult result, String cleanup) {
        com.miaokatze.miaokatzecore.api.testlab.LabEvents
            .publish("gtmb", data.getString("runId"), data.getString("kind"), phase, check, result, cleanup);
    }

    static com.miaokatze.miaokatzecore.api.testlab.LabResult result(String value) {
        try {
            return com.miaokatze.miaokatzecore.api.testlab.LabResult.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return com.miaokatze.miaokatzecore.api.testlab.LabResult.BLOCKED;
        }
    }

    void count(String name, long amount) {
        NBTTagCompound counters = data.getCompoundTag("counters");
        counters.setLong(name, counters.getLong(name) + amount);
    }

    void tick(long nanos) {
        NBTTagCompound timing = data.getCompoundTag("timing");
        timing.setLong("tickCalls", timing.getLong("tickCalls") + 1);
        timing.setLong("tickTotalNanos", timing.getLong("tickTotalNanos") + nanos);
        timing.setLong("tickMaxNanos", Math.max(nanos, timing.getLong("tickMaxNanos")));
    }

    NBTTagCompound snapshot() {
        NBTTagCompound copy = (NBTTagCompound) data.copy();
        if ("RUNNING".equals(copy.getString("state"))) copy.getCompoundTag("timing")
            .setLong("elapsedNanos", System.nanoTime() - started);
        return copy;
    }

    static Object json(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object key : ((NBTTagCompound) tag).func_150296_c())
                result.put(String.valueOf(key), json(((NBTTagCompound) tag).getTag(String.valueOf(key))));
            return result;
        }
        if (tag instanceof NBTTagList) {
            List<Object> result = new ArrayList<>();
            NBTTagList list = (NBTTagList) tag.copy();
            // 1.7.10 exposes no generic indexed getter. Pop the detached tail and then restore list order.
            for (int i = list.tagCount() - 1; i >= 0; i--) result.add(json(list.removeTag(i)));
            java.util.Collections.reverse(result);
            return result;
        }
        if (tag instanceof NBTTagByteArray) return ((NBTTagByteArray) tag).func_150292_c();
        if (tag instanceof NBTTagIntArray) return ((NBTTagIntArray) tag).func_150302_c();
        if (tag instanceof NBTTagString) return ((NBTTagString) tag).func_150285_a_();
        if (tag instanceof NBTPrimitive) {
            if (tag.getId() == 1) return ((NBTPrimitive) tag).func_150291_c() != 0;
            if (tag.getId() == 5 || tag.getId() == 6) return ((NBTPrimitive) tag).func_150286_g();
            return ((NBTPrimitive) tag).func_150291_c();
        }
        return String.valueOf(tag);
    }
}

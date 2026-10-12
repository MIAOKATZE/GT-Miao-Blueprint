package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.common.collect.ArrayListMultimap;
import com.miaokatze.gtmb.api.GTMBApi;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLMissingMappingsEvent;
import cpw.mods.fml.common.registry.GameData;
import cpw.mods.fml.common.registry.GameRegistry;

/** Opt-in checks of actual co-installed FML mods; all GTIT access is reflective and test-only. */
public final class HologramCoexistenceRegression {

    private static int assertions;

    private HologramCoexistenceRegression() {}

    /** Called by the opt-in dedicated-server fixture after ServerStarted and trade loading. */
    public static void run() throws Exception {
        assertions = 0;
        require(Loader.isModLoaded("gtit"), "actual_fml_gtit_loaded");
        require(Loader.isModLoaded("gtmb"), "actual_fml_gtmb_loaded");
        Item projector = GameRegistry.findItem("gtmb", "neko_hologram_projector");
        require(projector != null, "actual_gtmb_registry_identity");
        require(
            GameRegistry.findItem("gtit", "neko_hologram_projector") == null,
            "no_duplicate_legacy_projector_registration");
        require(GTMBApi.getLegacyMappingContractVersion() == 1, "public_mapping_contract_v1");
        require(
            GTMBApi.isEngineOwner() && "gtmb".equals(GTMBApi.getEngineOwnerModId()),
            "only_gtmb_owns_hologram_engine");
        require(
            GTMBApi.getProjectorItem() == projector && GTMBApi.isProjector(new ItemStack(projector)),
            "ready_api_returns_actual_registered_projector");
        oldEngineAbsent();
        trade(projector);
        legacyMapping(projector);
        System.out.println(
            "[GTMB-HOLOGRAM-COEXIST] FINAL PASS assertions=" + assertions
                + " actual FML registries and trade database; placement/material receipts remain separately required");
    }

    private static void oldEngineAbsent() throws Exception {
        ModContainer gtit = Loader.instance()
            .getIndexedModList()
            .get("gtit");
        require(gtit != null, "actual_gtit_mod_container");
        File source = gtit.getSource();
        require(source != null && source.isFile(), "coexistence_uses_real_packaged_gtit_artifact");
        String[] removed = { "com/miaokatze/gtit/hologram/", "com/miaokatze/gtit/client/hologram/",
            "com/miaokatze/gtit/common/items/hologram/", "com/miaokatze/gtit/mixin/hologram/",
            "com/miaokatze/gtit/asm/GtitHologramLateMixinLoader", "mixins.gtit.hologram.json" };
        try (JarFile jar = new JarFile(source)) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement()
                    .getName();
                for (String prefix : removed)
                    if (name.startsWith(prefix)) throw new AssertionError("old GTIT engine artifact remains: " + name);
            }
        }
        require(true, "gtit_artifact_contains_no_old_engine_classes_or_late_config");
        ClassLoader loader = HologramCoexistenceRegression.class.getClassLoader();
        require(
            loader.getResource("com/miaokatze/gtit/hologram/HologramService.class") == null,
            "no_legacy_service_from_another_classpath_artifact");
        require(
            loader.getResource("mixins.gtit.hologram.json") == null,
            "no_legacy_late_config_from_another_classpath_artifact");
    }

    private static void trade(Item projector) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtit.trade.v2.NekoTradeDatabase");
        Object database = type.getField("INSTANCE")
            .get(null);
        Object group = type.getMethod("getTradeGroup", UUID.class)
            .invoke(database, UUID.fromString("d7f5e8f2-247c-49af-b19b-d2f726ce34d0"));
        require(group != null, "actual_builtin_projector_trade_group_loaded");
        Object value = call(group, "getTrades");
        require(value instanceof List<?>, "actual_trade_group_entries");
        List<?> trades = (List<?>) value;
        require(trades.size() == 1, "one_projector_trade_in_dedicated_group");
        Object trade = trades.get(0);
        Object costsValue = call(trade, "getCurrencyCosts");
        require(costsValue instanceof Map<?, ?>, "actual_currency_cost_api");
        Map<?, ?> costs = (Map<?, ?>) costsValue;
        require(
            costs.size() == 1 && Integer.valueOf(1)
                .equals(costs.get("neko")),
            "actual_trade_cost_is_exactly_one_normal_neko");
        require(!costs.containsKey("shimmeringNeko"), "no_shimmering_neko_cost");
        List<?> inputs = (List<?>) call(trade, "getFromItems");
        require(inputs.size() == 1, "exactly_one_paid_input_entry");
        Item normalCoin = GameRegistry.findItem("gtit", "neko_coin");
        require(normalCoin != null, "actual_normal_neko_coin_registered");
        ItemStack input = (ItemStack) call(inputs.get(0), "getBaseStack");
        require(
            input.getItem() == normalCoin && ((Number) call(inputs.get(0), "getStackSize")).intValue() == 1,
            "real_input_is_one_registered_normal_neko_coin");
        List<?> outputs = (List<?>) call(trade, "getToItems");
        require(outputs.size() == 1, "exactly_one_projector_output_entry");
        Object output = outputs.get(0);
        Object stack = call(output, "getBaseStack");
        require(
            stack instanceof ItemStack && ((ItemStack) stack).getItem() == projector,
            "real_trade_output_is_gtmb_registered_item");
        require(((Number) call(output, "getStackSize")).intValue() == 1, "real_trade_outputs_one_projector");
    }

    /** Exercise native Forge rematching in a separate registry; never swap the live world's GameData. */
    private static void legacyMapping(Item projector) throws Exception {
        Class<?> compatibility = Class.forName("com.miaokatze.gtit.crossmod.gtmb.GtmbProjectorCompat");
        Method remap = compatibility.getMethod("remapMissingItems", FMLMissingMappingsEvent.class);
        FMLMissingMappingsEvent.MissingMapping old = new FMLMissingMappingsEvent.MissingMapping(
            "\u0002gtit:neko_hologram_projector",
            24001);
        ArrayListMultimap<String, FMLMissingMappingsEvent.MissingMapping> missing = ArrayListMultimap.create();
        missing.put("gtit", old);
        remap.invoke(null, new FMLMissingMappingsEvent(missing));
        require(
            old.getAction() == FMLMissingMappingsEvent.Action.REMAP && old.getTarget() == projector,
            "actual_gtit_handler_requests_one_way_gtmb_target");
        Constructor<GameData> empty = GameData.class.getDeclaredConstructor();
        empty.setAccessible(true);
        GameData sandbox = empty.newInstance();
        require(
            GameData.processIdRematches(Collections.singletonList(old), true, sandbox, new HashMap<>())
                .isEmpty(),
            "native_forge_rematch_resolves_old_numeric_id_in_sandbox");
        Field itemRegistry = GameData.class.getDeclaredField("iItemRegistry");
        itemRegistry.setAccessible(true);
        Object registry = itemRegistry.get(sandbox);
        require(
            registry.getClass()
                .getMethod("getRaw", int.class)
                .invoke(registry, 24001) == projector,
            "sandbox_saved_numeric_id_points_to_real_gtmb_item");
        Method lookup = null;
        for (Method method : registry.getClass()
            .getMethods()) {
            if (method.getName()
                .equals("getObject") && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0].isAssignableFrom(String.class)) {
                lookup = method;
                break;
            }
        }
        require(
            lookup != null && lookup.invoke(registry, old.name) == projector,
            "native_forge_sandbox_adds_legacy_name_alias");
        require(
            GameRegistry.findItem("gtit", "neko_hologram_projector") == null,
            "sandbox_rematch_does_not_modify_live_registry");
        ItemStack original = new ItemStack(projector, 2, 7);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("fixture", "full-payload-preserved");
        NBTTagCompound nested = new NBTTagCompound();
        nested.setLong("exact", 9007199254740993L);
        payload.setTag("nested", nested);
        original.setTagCompound(payload);
        ItemStack restored = ItemStack.loadItemStackFromNBT(original.writeToNBT(new NBTTagCompound()));
        require(
            restored != null && restored.getItem() == projector
                && restored.stackSize == 2
                && restored.getItemDamage() == 7
                && payload.equals(restored.getTagCompound()),
            "real_target_item_nbt_roundtrip_preserves_count_damage_and_nested_payload");
        FMLMissingMappingsEvent.MissingMapping reverse = new FMLMissingMappingsEvent.MissingMapping(
            "\u0002gtmb:neko_hologram_projector",
            24002);
        ArrayListMultimap<String, FMLMissingMappingsEvent.MissingMapping> reverseMissing = ArrayListMultimap.create();
        reverseMissing.put("gtmb", reverse);
        remap.invoke(null, new FMLMissingMappingsEvent(reverseMissing));
        require(reverse.getAction() == FMLMissingMappingsEvent.Action.DEFAULT, "no_reverse_gtmb_to_gtit_mapping");
    }

    private static Object call(Object receiver, String method) throws Exception {
        Method target = receiver.getClass()
            .getMethod(method);
        return target.invoke(receiver);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
        System.out.println("[GTMB-HOLOGRAM-COEXIST] ASSERT PASS " + label);
    }
}

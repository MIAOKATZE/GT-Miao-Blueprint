package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.ForgeDirection;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GlassTier;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemMachines;

/** The reviewed rc2.1 machines, rebuilt from declarations without executing any upstream factory. */
final class HologramGTNLSemantics {

    static final String COMMIT = "5f54ba584866595577df46999f2685a232af3334";
    private static final String PREFIX = "com.science.gtnl.common.machine.multiblock.";
    private static final Map<String, String> FAMILIES = families();
    private static final List<AutoCloseable> REGISTRATIONS = new ArrayList<>();
    private static final Map<Class<?>, HologramGTNLElement> COMMON_ELEMENTS = new LinkedHashMap<>();
    private static final Map<String, String> INSTALL_FAILURES = new LinkedHashMap<>();
    private static final Map<IStructureElement<?>, GtsrState> STEAM_COMPATIBILITY = new WeakHashMap<>();
    private static Catalog catalog;

    private HologramGTNLSemantics() {}

    private static Map<String, String> families() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(PREFIX + "AssemblerMatrix", "assembler_matrix");
        result.put(PREFIX + "steam.LargeSteamMixer", "large_steam_mixer");
        result.put(PREFIX + "AtomicEnergyExcitationPlant", "aeep");
        result.put(PREFIX + "NuclearReactor", "nuclear_reactor");
        result.put(PREFIX + "structuralReconstructionPlan.PrecisionAssembler", "precision_assembler");
        return Collections.unmodifiableMap(result);
    }

    static boolean supports(Object controller) {
        return controller != null && FAMILIES.containsKey(
            controller.getClass()
                .getName());
    }

    static boolean isGTNLController(Object controller) {
        return controller instanceof MTEEnhancedMultiBlockBase && controller.getClass()
            .getName()
            .startsWith("com.science.gtnl.");
    }

    static String unsupportedReason(Object controller) {
        String environment = environmentFailure();
        return !environment.isEmpty() ? environment
            : controller != null && INSTALL_FAILURES.containsKey(
                controller.getClass()
                    .getName()) ? INSTALL_FAILURES.get(
                        controller.getClass()
                            .getName())
                        : supports(controller) ? "GTNL_ADAPTER_UNAVAILABLE" : "GTNL_UNREVIEWED_MACHINE";
    }

    /** Only this adapter's two exact CommonElements leaf registrations can resolve through this bridge. */
    static synchronized HologramGTNLElement reviewedElement(IStructureElement<?> element) {
        if (element instanceof HologramGTNLElement) return (HologramGTNLElement) element;
        return element == null ? null : COMMON_ELEMENTS.get(element.getClass());
    }

    /** Called after normal mod registration; no optional GTNL classes are statically linked. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static synchronized void install() {
        if (!REGISTRATIONS.isEmpty() || !Loader.isModLoaded("sciencenotleisure")) return;
        ClassLoader loader = HologramGTNLSemantics.class.getClassLoader();
        for (Map.Entry<String, String> entry : FAMILIES.entrySet()) {
            try {
                Class type = Class.forName(entry.getKey(), false, loader);
                GTMBExtensions.Registration registration = StructureSources
                    .registerContextual("gtmb:gtnl/" + entry.getValue(), type, HologramGTNLSemantics::describe);
                REGISTRATIONS.add(registration);
            } catch (ClassNotFoundException | LinkageError unavailable) {
                // Keep startup available; a request for this family still cannot fall back to native capture.
                INSTALL_FAILURES.put(
                    entry.getKey(),
                    "GTNL_CLASS_UNAVAILABLE " + unavailable.getClass()
                        .getSimpleName());
            }
        }
        if (environmentFailure().isEmpty()) installCommonElements(loader);
    }

    static synchronized void close() {
        for (int i = REGISTRATIONS.size() - 1; i >= 0; i--) {
            try {
                REGISTRATIONS.get(i)
                    .close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("GTNL_ADAPTER_REMOVAL", failure);
            }
        }
        REGISTRATIONS.clear();
        COMMON_ELEMENTS.clear();
        INSTALL_FAILURES.clear();
        STEAM_COMPATIBILITY.clear();
        catalog = null;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void installCommonElements(ClassLoader loader) {
        // Load only the two reviewed anonymous leaf types, without touching the enum or its initialization.
        int index = 0;
        for (String name : Arrays.asList("BlockBeacon", "BlockSponge")) {
            index++;
            try {
                Class<?> type = Class.forName("com.science.gtnl.utils.enums.CommonElements$" + index, false, loader);
                if (!IStructureElement.class.isAssignableFrom(type)) {
                    INSTALL_FAILURES.put("common." + name, "GTNL_COMMON_CLASS_SHAPE");
                    continue;
                }
                HologramGTNLElement replacement = commonElement(name);
                REGISTRATIONS.add(
                    HologramDeclarationAdapters.registerScopedReader(
                        (Class) type,
                        ignored -> new HologramDeclarationAdapters.Declaration(replacement)));
                COMMON_ELEMENTS.put(type, replacement);
            } catch (ClassNotFoundException | LinkageError unavailable) {
                INSTALL_FAILURES.put("common." + name, "GTNL_COMMON_CLASS_UNAVAILABLE");
            }
        }
    }

    static HologramGTNLElement commonElement(String name) {
        List<ItemStack> materials = new ArrayList<>();
        if ("BlockBeacon".equals(name)) {
            materials.add(new ItemStack(Blocks.beacon, 1, 0));
            return element("common.beacon", HologramGTNLElement.Kind.BEACON, materials, materials, false, "", -1);
        }
        if (!"BlockSponge".equals(name)) throw new IllegalArgumentException("Unreviewed CommonElements value");
        materials.add(new ItemStack(Blocks.sponge, 1, 0));
        Block alternative = GameRegistry.findBlock("etfuturum", "sponge");
        if (alternative != null) {
            materials.add(new ItemStack(alternative, 1, 0));
            materials.add(new ItemStack(alternative, 1, 1));
        }
        return element("common.sponge", HologramGTNLElement.Kind.SPONGE, materials, materials, false, "", -1);
    }

    private static String environmentFailure() {
        Map<String, ModContainer> mods = Loader.instance()
            .getIndexedModList();
        ModContainer gtnl = mods.get("sciencenotleisure");
        if (gtnl == null) return "GTNL_NOT_LOADED";
        if (!"0.2.7-rc2.1".equals(gtnl.getVersion())) return "GTNL_UNREVIEWED_VERSION " + gtnl.getVersion();
        ModContainer library = mods.get("gtnllib");
        if (library == null || !"1.1.1".equals(library.getVersion())) return "GTNL_LIBRARY_VERSION";
        return "";
    }

    private static StructureSources.Resolution describe(Object source, ItemStack trigger,
        StructureSources.CaptureIntent intent) {
        String failure = environmentFailure();
        if (!failure.isEmpty()) return StructureSources.Resolution.unsupported(failure);
        if (!(source instanceof MTEEnhancedMultiBlockBase) || !supports(source))
            return StructureSources.Resolution.unsupported("GTNL_UNREVIEWED_MACHINE");
        try {
            MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) source;
            Observation observation = new Observation(machine);
            Catalog materials = materials();
            String family = FAMILIES.get(
                source.getClass()
                    .getName());
            NBTTagCompound metadata = metadata(family, intent, observation);
            StructureSources.Plan plan = plan(family, trigger, intent, observation, materials, metadata);
            return StructureSources.Resolution.ready(plan, () -> observation.failure(machine, materials));
        } catch (ReflectiveOperationException | IOException failureReadingDefinition) {
            return StructureSources.Resolution
                .unsupported("GTNL_DECLARATION_UNAVAILABLE " + failureReadingDefinition.getMessage());
        }
    }

    private static synchronized Catalog materials() throws ReflectiveOperationException, IOException {
        if (catalog == null || !catalog.current()) catalog = new Catalog();
        return catalog;
    }

    private static StructureSources.Plan plan(String family, ItemStack trigger, StructureSources.CaptureIntent intent,
        Observation observation, Catalog materials, NBTTagCompound metadata)
        throws ReflectiveOperationException, IOException {
        StructureDefinition.Builder<Object> builder = StructureDefinition.builder();
        List<StructureSources.Piece> pieces = new ArrayList<>();
        NBTTagCompound parameters = metadata.getCompoundTag("parameters");
        switch (family) {
            case "assembler_matrix":
                builder.addShape("main", HologramGTNLShapes.structure("assembler_matrix"));
                builder.addElement(
                    'A',
                    materials.hatch(
                        "assembler.shell",
                        materials.block("gtnl", "metaCasing02", 4),
                        "maintenance",
                        "input_bus",
                        "output_bus",
                        "energy"));
                List<ItemStack> wall = materials.glasses(trigger);
                wall.add(materials.block("gtnl", "metaCasing02", 5));
                builder.addElement('B', new HologramGTNLElement("assembler.window", wall, true));
                List<ItemStack> core = new ArrayList<>();
                for (int damage : new int[] { 5, 6, 7, 8, 18, 9 })
                    core.add(materials.block("gtnl", "metaCasing02", damage));
                builder.addElement('C', new HologramGTNLElement("assembler.core", core, true));
                pieces.add(new StructureSources.Piece("main", 4, 8, 0, trigger));
                rule(metadata, "core_count", 343, 343, "C", "核心区必须保留343格；调试核心物品damage为18");
                rule(metadata, "speed_cores", 0, 5, "C", "加速核心最多5个");
                break;
            case "large_steam_mixer":
                int steamTier = channel(trigger, "machine_casing", 2);
                parameters.setInteger("machine_casing", steamTier);
                builder.addShape("main", HologramGTNLShapes.structure("large_steam_mixer"));
                HologramGTNLElement steamShell = materials.hatch(
                    "mixer.shell",
                    steamTier == 1 ? materials.block("gt", "sBlockCasings1", 10)
                        : materials.block("gt", "sBlockCasings2", 0),
                    "steam",
                    "steam_big",
                    "steam_wireless",
                    "steam_input_bus",
                    "steam_output_bus",
                    "input_bus",
                    "output_bus",
                    "input",
                    "output",
                    "maintenance");
                synchronized (STEAM_COMPATIBILITY) {
                    STEAM_COMPATIBILITY.put(steamShell, observation.gtsr);
                }
                builder.addElement('A', steamShell);
                builder.addElement(
                    'B',
                    fixed(
                        "mixer.gear",
                        materials.block("gt", "sBlockCasings2", steamTier + 1),
                        "machine_casing",
                        steamTier));
                builder.addElement(
                    'C',
                    fixed(
                        "mixer.pipe",
                        materials.block("gt", "sBlockCasings2", steamTier + 11),
                        "machine_casing",
                        steamTier));
                builder.addElement(
                    'D',
                    fixed(
                        "mixer.firebox",
                        materials.block("gt", "sBlockCasings3", steamTier + 12),
                        "machine_casing",
                        steamTier));
                builder.addElement('E', new HologramGTNLElement("mixer.glass", materials.glasses(trigger), true));
                builder.addElement(
                    'F',
                    fixed(
                        "mixer.industrial",
                        materials.block("gtnl", "metaCasing02", steamTier),
                        "machine_casing",
                        steamTier));
                pieces.add(new StructureSources.Piece("main", 3, 3, 0, trigger));
                rule(metadata, "casing_min", 30, -1, "A", "至少30个外壳；A/B/C/D/F共同使用同一个machine_casing信道");
                rule(metadata, "steam_supply", 1, -1, "A", "普通、大型、无线供汽三类合计至少一个");
                rule(metadata, "steam_normal", 0, 1, "A", "普通供汽最多一个；大型和无线没有结构max1");
                rule(metadata, "steam_auto", 0, 1, "A", "每类供汽的原生自动补放在已有该类时停止，原位置可以SKIP");
                break;
            case "aeep":
                int fieldTier = channel(trigger, "machine_casing", 4);
                int coilTier = channel(trigger, "coil", HeatingCoilLevel.getMaxTier() + 1);
                parameters.setInteger("machine_casing", fieldTier);
                parameters.setInteger("coil", coilTier);
                boolean air = intent == StructureSources.CaptureIntent.LIVE_SNAPSHOT && observation.renderActive;
                builder.addShape("main", HologramGTNLShapes.structure("atomic_energy_excitation_plant"));
                builder.addShape("sphere", HologramGTNLShapes.sphere(false));
                builder.addShape("sphere_air", HologramGTNLShapes.sphere(true));
                builder.addElement('A', new HologramGTNLElement("aeep.glass", materials.glasses(trigger), true));
                builder.addElement(
                    'B',
                    fixed(
                        "aeep.field",
                        materials.block("gg", "FRF_Coil_" + fieldTier, 0),
                        "machine_casing",
                        fieldTier));
                builder.addElement('C', fixed("aeep.defc", materials.block("kuba", "defcCasingBlock", 7), "", -1));
                int coilMeta = BlockCasings5.getMetaFromCoilHeat(HeatingCoilLevel.getFromTier((byte) (coilTier - 1)));
                List<ItemStack> coil = Collections.singletonList(materials.block("gt", "sBlockCasings5", coilMeta));
                builder.addElement(
                    'D',
                    element("aeep.coil", HologramGTNLElement.Kind.COIL, coil, coil, true, "coil", coilTier));
                builder
                    .addElement('E', fixed("aeep.active_casing", materials.block("gt", "sBlockCasings10", 7), "", -1));
                builder.addElement(
                    'F',
                    materials.hatch(
                        "aeep.shell",
                        materials.block("gt", "sBlockCasings9", 11),
                        "maintenance",
                        "input_bus",
                        "input",
                        "output",
                        "energy",
                        "parallel"));
                builder.addElement('G', fixed("aeep.frame", materials.frame(Materials.Neutronium), "", -1));
                builder.addElement(
                    'H',
                    fixed("aeep.rebolted", materials.block("bw", "BWBlockCasingsAdvanced", 31895), "", -1));
                builder.addElement('I', fixed("aeep.bolted", materials.block("bw", "BWBlockCasings", 31895), "", -1));
                builder.addElement('J', fixed("aeep.sphere.j", materials.block("gt", "sBlockMetal4", 13), "", -1));
                builder.addElement('K', fixed("aeep.sphere.k", materials.block("gt", "sBlockMetal4", 14), "", -1));
                builder.addElement('L', StructureUtility.isAir());
                pieces.add(new StructureSources.Piece("main", 8, 8, 3, trigger));
                pieces.add(new StructureSources.Piece(air ? "sphere_air" : "sphere", 4, 4, -7, trigger));
                metadata.setString("sphere", air ? "sphere_air" : "sphere");
                metadata.getCompoundTag("capabilities")
                    .setBoolean("construct", !air);
                rule(metadata, "casing_min", 350, -1, "F", "至少350个耐热Trinium镀层外壳");
                rule(metadata, "heating_coil", 56, 56, "D", "56格加热线圈同阶，线圈等级不得为None");
                rule(metadata, "sphere_j", air ? 0 : 52, air ? 0 : 52, "sphere", "模板需要52个J与135个K；现场活跃态187格为空气");
                rule(metadata, "sphere_k", air ? 0 : 135, air ? 0 : 135, "sphere", "原球体542个空格保持skip");
                restriction(metadata, "工作态或渲染态禁止改变旋转/翻转；默认对齐规则不允许竖直翻转");
                break;
            case "nuclear_reactor":
                int reactorTier = channel(trigger, "casing", 3);
                parameters.setInteger("casing", reactorTier);
                String piece = reactorTier == 1 ? "main" : "tier" + reactorTier;
                builder.addShape(piece, HologramGTNLShapes.nuclear(reactorTier));
                ItemStack seal = materials.block("gtpp", "blockCasings2Misc", 1);
                builder.addElement('X', fixed("nuclear.seal", seal, "", -1));
                builder.addElement(
                    'A',
                    materials.hatch("nuclear.pipe", materials.block("gt", "sBlockCasings13", 2), "output"));
                builder.addElement('B', materials.hatch("nuclear.shell", seal, "output"));
                builder.addElement(
                    'C',
                    materials.hatch("nuclear.structural", materials.block("gtpp", "blockCasings2Misc", 2), "output"));
                for (int row = 0; row < HologramGTNLShapes.coreSize(reactorTier); row++)
                    for (int col = 0; col < HologramGTNLShapes.coreSize(reactorTier); col++)
                        if (HologramGTNLShapes.coreCell(reactorTier, row, col)) {
                            List<ItemStack> candidates = materials.options(seal, "nuclear_item", "nuclear_fluid");
                            builder.addElement(
                                HologramGTNLShapes.coreChar(row, col),
                                new HologramGTNLElement(
                                    "nuclear.core",
                                    HologramGTNLElement.Kind.HATCH,
                                    candidates,
                                    candidates,
                                    true,
                                    "casing",
                                    reactorTier,
                                    row,
                                    col));
                        }
                pieces.add(new StructureSources.Piece(piece, (2 * reactorTier + 3) / 2, 2, 0, trigger));
                rule(metadata, "nuclear_item", 1, -1, "core", "有效核心位置至少一个核物品仓；切角/外壳不能放核仓");
                rule(metadata, "nuclear_fluid", 1, -1, "core", "有效核心位置至少一个核流体仓");
                rule(metadata, "output", 1, -1, "A/B/C", "至少一个普通输出仓；核心密封块合法不代表整机已满足核仓数量");
                break;
            case "precision_assembler":
                int machineTier = channel(trigger, "machine_casing", 10) - 1;
                int unitTier = channel(trigger, "unit_casing", 5) - 1;
                parameters.setInteger("machine_casing", machineTier + 1);
                parameters.setInteger("unit_casing", unitTier + 1);
                parameters.setInteger("machineTier", machineTier);
                parameters.setInteger("unitTier", unitTier);
                builder.addShape("main", HologramGTNLShapes.structure("precise_assembler"));
                builder.addElement('A', new HologramGTNLElement("precision.glass", materials.glasses(trigger), true));
                builder.addElement(
                    'B',
                    fixed(
                        "precision.machine",
                        materials.block("gt", "sBlockCasings1", machineTier),
                        "machine_casing",
                        machineTier));
                builder.addElement('C', fixed("precision.frame", materials.frame(Materials.TungstenSteel), "", -1));
                builder.addElement(
                    'D',
                    materials.hatch(
                        "precision.unit",
                        materials.block(
                            "gg",
                            unitTier == 0 ? "impreciseUnitCasing" : "preciseUnitCasing",
                            Math.max(0, unitTier - 1)),
                        "input",
                        "input_bus",
                        "output_bus",
                        "maintenance",
                        "energy",
                        "parallel"));
                pieces.add(new StructureSources.Piece("main", 4, 4, 0, trigger));
                rule(metadata, "unit_casing_min", 30, -1, "D", "至少30个同阶单元外壳；machine_casing与unit_casing是独立信道");
                rule(metadata, "energy_tier", 0, machineTier < 9 ? machineTier : -1, "D", "UHV以下机壳不得接入高于机壳等级的能源仓");
                restriction(metadata, "同一玻璃等级；相同几何不代表50种材料组合等价");
                break;
            default:
                throw new IOException("GTNL_UNREVIEWED_MACHINE");
        }
        if (!"large_steam_mixer".equals(family)) {
            rule(metadata, "maintenance", 0, 1, "hatches", "维护仓最多1个；atLeast不等于最终逐类至少1个");
            rule(metadata, "parallel", 0, 1, "hatches", "并行控制仓最多1个");
        }
        return new StructureSources.Plan(builder.build(), pieces, metadata);
    }

    private static HologramGTNLElement fixed(String id, ItemStack stack, String channel, int tier) {
        List<ItemStack> items = Collections.singletonList(stack);
        return element(id, HologramGTNLElement.Kind.BLOCK, items, items, true, channel, tier);
    }

    private static HologramGTNLElement element(String id, HologramGTNLElement.Kind kind, List<ItemStack> candidates,
        List<ItemStack> placement, boolean complete, String channel, int tier) {
        return new HologramGTNLElement(id, kind, candidates, placement, complete, channel, tier, -1, -1);
    }

    static int channel(ItemStack trigger, String channel, int max) {
        int value = ChannelDataAccessor.hasSubChannel(trigger, channel)
            ? ChannelDataAccessor.getChannelData(trigger, channel)
            : trigger.stackSize;
        return Math.max(1, Math.min(max, value));
    }

    static List<ItemStack> inferenceTriggers(MTEEnhancedMultiBlockBase<?> machine, ItemStack seed) {
        if (!supports(machine)) return Collections.emptyList();
        String family = FAMILIES.get(
            machine.getClass()
                .getName());
        List<ItemStack> result = new ArrayList<>();
        int first = "precision_assembler".equals(family) ? 10
            : "aeep".equals(family) ? 4
                : "nuclear_reactor".equals(family) ? 3 : "large_steam_mixer".equals(family) ? 2 : 1;
        int second = "precision_assembler".equals(family) ? 5
            : "aeep".equals(family) ? HeatingCoilLevel.getMaxTier() + 1 : 1;
        for (int a = 1; a <= first; a++) for (int b = 1; b <= second; b++) {
            ItemStack signal = seed.copy();
            String primary = "nuclear_reactor".equals(family) ? "casing" : "machine_casing";
            if (first > 1) ChannelDataAccessor.setChannelData(signal, primary, a);
            if (second > 1)
                ChannelDataAccessor.setChannelData(signal, "aeep".equals(family) ? "coil" : "unit_casing", b);
            result.add(signal);
        }
        return result;
    }

    private static NBTTagCompound metadata(String family, StructureSources.CaptureIntent intent,
        Observation observation) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("gtnlSchema", 1);
        result.setString("sourceId", "gtmb:gtnl/" + family);
        result.setString("family", family);
        result.setString("purpose", intent.name());
        result.setString("sourceCommit", COMMIT);
        result.setBoolean("known", true);
        result.setBoolean("partial", false);
        result.setBoolean("commonElementsSupported", COMMON_ELEMENTS.size() == 2);
        result.setString("state", observation.renderActive ? "render_active" : "dormant");
        result.setBoolean("formed", observation.formed);
        result.setLong(
            "observedTick",
            observation.base.getWorld()
                .getTotalWorldTime());
        result.setBoolean("poseLocked", "aeep".equals(family) && (observation.formed || observation.renderActive));
        result.setBoolean("verticalFlipAllowed", false);
        NBTTagCompound inheritedChecks = new NBTTagCompound();
        inheritedChecks.setBoolean("requiresHeatingCoil", "aeep".equals(family));
        // All five reviewed classes keep MultiMachineBase's -1 glass-energy limit. Only AEEP inherits
        // GTMMultiMachineBase.checkHatch, but its checkEnergyHatch override is intentionally empty.
        inheritedChecks.setInteger("glassEnergyTierLimit", -1);
        inheritedChecks.setBoolean("genericEnergyCheck", false);
        inheritedChecks.setBoolean("laserConfigApplies", false);
        result.setTag("inheritedChecks", inheritedChecks);
        result.setTag("parameters", new NBTTagCompound());
        result.setTag("rules", new NBTTagList());
        result.setTag("restrictions", new NBTTagList());
        if (observation.gtsr != null) {
            result.setTag("gtsr", observation.gtsr.metadata());
            if (observation.gtsr.present) {
                restriction(
                    result,
                    observation.gtsr.coolingDeclared() ? "GTSR增强配置与类型标记允许声明冷却仓角色；实际冷却和耗汽功能须另作运行验收"
                        : "GTSR特殊冷却登记未获声明确认；冷却仓仍按已审阅的普通输出仓接受关系显示");
                restriction(result, "GTSR无管开关只约束其消费者；不限制GTNL原生无线蒸汽仓，也不为其增加tier0限制");
            }
        }
        NBTTagCompound capabilities = new NBTTagCompound();
        for (String name : Arrays.asList("render", "preserveExact", "editKnownCandidate", "materialPlan", "construct"))
            capabilities.setBoolean(name, true);
        result.setTag("capabilities", capabilities);
        return result;
    }

    private static void rule(NBTTagCompound metadata, String id, int min, int max, String scope, String text) {
        NBTTagCompound rule = new NBTTagCompound();
        rule.setString("id", id);
        rule.setInteger("min", min);
        rule.setInteger("max", max);
        rule.setString("scope", scope);
        rule.setString("text", text);
        metadata.getTagList("rules", 10)
            .appendTag(rule);
    }

    private static void restriction(NBTTagCompound metadata, String text) {
        metadata.getTagList("restrictions", 8)
            .appendTag(new NBTTagString(text));
    }

    private static Object field(String type, String name) throws ReflectiveOperationException {
        return declaredField(Class.forName(type, false, HologramGTNLSemantics.class.getClassLoader()), name).get(null);
    }

    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static final class Observation {

        final IGregTechTileEntity base;
        final Object world, facing;
        final int x, y, z;
        final boolean renderActive, formed;
        final GtsrState gtsr;

        Observation(MTEEnhancedMultiBlockBase<?> machine) throws ReflectiveOperationException, IOException {
            base = machine.getBaseMetaTileEntity();
            if (base == null || base.getWorld() == null) throw new IOException("GTNL_CONTROLLER_UNATTACHED");
            world = base.getWorld();
            x = base.getXCoord();
            y = base.getYCoord();
            z = base.getZCoord();
            facing = machine.getExtendedFacing();
            formed = declaredField(machine.getClass(), "mMachine").getBoolean(machine);
            renderActive = "aeep".equals(
                FAMILIES.get(
                    machine.getClass()
                        .getName()))
                && declaredField(machine.getClass(), "isRenderActive").getBoolean(machine);
            gtsr = (PREFIX + "steam.LargeSteamMixer").equals(
                machine.getClass()
                    .getName()) ? GtsrState.read(machine.getClass()) : null;
        }

        String failure(MTEEnhancedMultiBlockBase<?> machine, Catalog materials) {
            try {
                if (!environmentFailure().isEmpty()) return environmentFailure();
                if (machine.getBaseMetaTileEntity() != base || base.getWorld() != world
                    || base.getXCoord() != x
                    || base.getYCoord() != y
                    || base.getZCoord() != z
                    || machine.getExtendedFacing() != facing) return "GTNL_CONTROLLER_OR_POSE_CHANGED";
                if (formed != declaredField(machine.getClass(), "mMachine").getBoolean(machine))
                    return "GTNL_FORMED_STATE_CHANGED";
                if ("aeep".equals(
                    FAMILIES.get(
                        machine.getClass()
                            .getName()))
                    && renderActive != declaredField(machine.getClass(), "isRenderActive").getBoolean(machine))
                    return "GTNL_RENDER_STATE_CHANGED";
                if (!materials.current()) return "GTNL_REGISTRY_CHANGED";
                if (gtsr != null && !gtsr.fingerprint()
                    .equals(
                        GtsrState.read(machine.getClass())
                            .fingerprint()))
                    return "GTNL_GTSR_COMPATIBILITY_CHANGED";
                return null;
            } catch (ReflectiveOperationException failure) {
                return "GTNL_STATE_UNREADABLE";
            }
        }
    }

    /** Only post-initialization configuration fields and transformed interface identities are observed. */
    private static final class GtsrState {

        private static final String COOLING = "com.miaokatze.gtsr.common.machine.base.MTESteamCoolingHatch";
        private static final String PRESSURE = "com.miaokatze.gtsr.common.machine.base.MTEPressureSteamCoolingHatch";
        final boolean present, reviewed, readable, enhancement, pipeless, bridge, holder;
        final String version;

        GtsrState(boolean present, String version, boolean reviewed, boolean readable, boolean enhancement,
            boolean pipeless, boolean bridge, boolean holder) {
            this.present = present;
            this.version = version;
            this.reviewed = reviewed;
            this.readable = readable;
            this.enhancement = enhancement;
            this.pipeless = pipeless;
            this.bridge = bridge;
            this.holder = holder;
        }

        static GtsrState read(Class<?> controller) {
            ModContainer addon = Loader.instance()
                .getIndexedModList()
                .get("gtsr");
            if (addon == null) return new GtsrState(false, "", false, true, false, false, false, false);
            String version = addon.getVersion();
            if (!"v2.1.1".equals(version))
                return new GtsrState(true, version, false, false, false, false, false, false);
            boolean bridge = hasInterface(controller, "com.miaokatze.gtsr.api.compat.ISelfManagedSteamMachine");
            boolean holder = hasInterface(controller, "com.miaokatze.gtsr.api.compat.ICoolingHatchHolder");
            try {
                // Config is initialized by GTSR's normal preInit before GTMB can capture a world controller.
                // In particular, never load GTNLMixinSelection or call its startup/disk configuration helpers.
                Class<?> config = Class
                    .forName("com.miaokatze.gtsr.config.Config", false, HologramGTNLSemantics.class.getClassLoader());
                boolean enhancement = config.getField("gtnlEnhancement")
                    .getBoolean(null);
                boolean pipeless = config.getField("gtnlPipelessSteam")
                    .getBoolean(null);
                return new GtsrState(true, version, true, true, enhancement, pipeless, bridge, holder);
            } catch (ReflectiveOperationException | LinkageError unreadable) {
                return new GtsrState(true, version, true, false, false, false, bridge, holder);
            }
        }

        private static boolean hasInterface(Class<?> type, String name) {
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                for (Class<?> implemented : current.getInterfaces()) if (implemented.getName()
                    .equals(name) || hasInterface(implemented, name)) return true;
            return false;
        }

        boolean coolingDeclared() {
            return reviewed && readable && enhancement && bridge && holder;
        }

        String fingerprint() {
            return version + ':'
                + present
                + ':'
                + reviewed
                + ':'
                + readable
                + ':'
                + enhancement
                + ':'
                + pipeless
                + ':'
                + bridge
                + ':'
                + holder;
        }

        String coolingRole(IMetaTileEntity prototype) {
            if (!coolingDeclared() || prototype == null) return "";
            boolean cooling = false;
            for (Class<?> current = prototype.getClass(); current != null; current = current.getSuperclass()) {
                if (current.getName()
                    .equals(PRESSURE)) return "gtsr:pressure_cooling";
                cooling |= current.getName()
                    .equals(COOLING);
            }
            return cooling ? "gtsr:cooling" : "";
        }

        NBTTagCompound metadata() {
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("present", present);
            result.setString("version", version);
            result.setBoolean("reviewedVersion", reviewed);
            result.setBoolean("configurationReadable", readable);
            result.setBoolean("enhancement", enhancement);
            result.setBoolean("pipeless", pipeless);
            result.setBoolean("selfManagedBridge", bridge);
            result.setBoolean("coolingHolder", holder);
            result.setBoolean("specialCoolingDeclared", coolingDeclared());
            result.setBoolean("runtimeVerified", false);
            result.setString("fingerprint", fingerprint());
            result.setString("evidence", "配置与变换类型标记的声明说明；不代表冷却、耗汽或四组合实机验收");
            result.setString("pipelessScope", "仅GTSR消费者；GTNL原生无线仓不受P或tier0限制");
            return result;
        }
    }

    /** Snapshots known registries; no suppliers, glass lazy initialization, MTE factory, or enum callbacks. */
    private static final class Catalog {

        final List<ItemStack> glasses = new ArrayList<>();
        final Map<String, List<ItemStack>> hatches = new LinkedHashMap<>();
        final Map<String, Class<?>> types = new LinkedHashMap<>();
        final Map<Integer, IMetaTileEntity> prototypes = new LinkedHashMap<>();
        final Map<String, Block> blocks = new LinkedHashMap<>();
        final Map<String, String> owners = new LinkedHashMap<>();
        final Map<String, Integer> glassTiers = new LinkedHashMap<>();
        final IMetaTileEntity[] registry;
        final Map<Pair<Integer, Integer>, Pair<Block, Integer>> glassRegistry;

        @SuppressWarnings("unchecked")
        Catalog() throws ReflectiveOperationException, IOException {
            owners.put("gtnl", "com.science.gtnl.loader.BlockLoader");
            owners.put("gt", "gregtech.api.GregTechAPI");
            owners.put("gtpp", "gtPlusPlus.core.block.ModBlocks");
            owners.put("bw", "bartworks.system.material.WerkstoffLoader");
            owners.put("gg", "goodgenerator.loader.Loaders");
            owners.put("kuba", "kubatech.loaders.BlockLoader");
            Map<Pair<Integer, Integer>, Pair<Block, Integer>> registered = (Map<Pair<Integer, Integer>, Pair<Block, Integer>>) field(
                GlassTier.class.getName(),
                "tierToGlass");
            glassRegistry = new LinkedHashMap<>(registered);
            for (Map.Entry<Pair<Integer, Integer>, Pair<Block, Integer>> entry : registered.entrySet()) {
                Pair<Block, Integer> material = entry.getValue();
                if (material.getLeft() == null) throw new IOException("GTNL_GLASS_REGISTRY_NOT_READY");
                ItemStack stack = new ItemStack(material.getLeft(), 1, material.getRight());
                glassTiers.put(
                    materialKey(stack),
                    entry.getKey()
                        .getLeft());
            }
            List<Pair<Block, Integer>> primary = (List<Pair<Block, Integer>>) field(
                GlassTier.class.getName(),
                "mainGlass");
            for (Pair<Block, Integer> material : primary) {
                if (material == null || material.getLeft() == null)
                    throw new IOException("GTNL_GLASS_REGISTRY_NOT_READY");
                glasses.add(new ItemStack(material.getLeft(), 1, material.getRight()));
            }
            for (Map.Entry<Pair<Integer, Integer>, Pair<Block, Integer>> entry : registered.entrySet())
                if (entry.getKey()
                    .getRight() != 0)
                    glasses.add(
                        new ItemStack(
                            entry.getValue()
                                .getLeft(),
                            1,
                            entry.getValue()
                                .getRight()));
            if (!primary.isEmpty()) {
                Pair<Block, Integer> last = primary.get(primary.size() - 1);
                glasses.add(new ItemStack(last.getLeft(), 1, last.getRight()));
            }
            if (glasses.isEmpty() || glasses.size() > 128) throw new IOException("GTNL_GLASS_REGISTRY_NOT_READY");
            type("maintenance", "gregtech.api.metatileentity.implementations.MTEHatchMaintenance");
            type("input_bus", "gregtech.api.metatileentity.implementations.MTEHatchInputBus");
            type("dual_input", "gregtech.common.tileentities.machines.IDualInputHatch");
            type("output_bus", "gregtech.api.metatileentity.implementations.MTEHatchOutputBus");
            type("input", "gregtech.api.metatileentity.implementations.MTEHatchInput");
            type("output", "gregtech.api.metatileentity.implementations.MTEHatchOutput");
            type("energy", "gregtech.api.metatileentity.implementations.MTEHatchEnergy");
            type("energy_multi", "tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti");
            type("energy_laser", "tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel");
            type("parallel", "com.science.gtnl.common.machine.hatch.ParallelControllerHatch");
            type("steam", "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.MTEHatchCustomFluidBase");
            type("steam_big", "com.science.gtnl.common.machine.hatch.CustomFluidHatch");
            type("steam_wireless", "com.science.gtnl.common.machine.hatch.WirelessSteamEnergyHatch");
            type(
                "steam_input_bus",
                "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.MTEHatchSteamBusInput");
            type(
                "steam_output_bus",
                "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.MTEHatchSteamBusOutput");
            type("nuclear_item", "com.science.gtnl.common.machine.hatch.NuclearItemBus");
            type("nuclear_fluid", "com.science.gtnl.common.machine.hatch.NuclearFluidHatch");
            if (GregTechAPI.METATILEENTITIES == null || GregTechAPI.sBlockMachines == null)
                throw new IOException("GTNL_MACHINE_REGISTRY_NOT_READY");
            registry = GregTechAPI.METATILEENTITIES.clone();
            for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
                IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
                if (prototype == null) continue;
                for (Map.Entry<String, Class<?>> type : types.entrySet()) {
                    if (!type.getValue()
                        .isInstance(prototype)) continue;
                    if ("steam".equals(type.getKey()) && !ordinarySteam(prototype)) continue;
                    if ("steam_big".equals(type.getKey()) && types.get("steam_wireless")
                        .isInstance(prototype)) continue;
                    if ("input_bus".equals(type.getKey()) && types.get("steam_input_bus")
                        .isInstance(prototype)) continue;
                    if ("output_bus".equals(type.getKey()) && types.get("steam_output_bus")
                        .isInstance(prototype)) continue;
                    hatches.get(type.getKey())
                        .add(new ItemStack(GregTechAPI.sBlockMachines, 1, id));
                    prototypes.put(id, prototype);
                }
            }
        }

        void type(String role, String name) throws ClassNotFoundException {
            types.put(role, Class.forName(name, false, HologramGTNLSemantics.class.getClassLoader()));
            hatches.put(role, new ArrayList<>());
        }

        boolean ordinarySteam(IMetaTileEntity prototype) throws ReflectiveOperationException {
            return !types.get("steam_big")
                .isInstance(prototype)
                && declaredField(prototype.getClass(), "mLockedFluid").get(prototype) == Materials.Steam.mGas;
        }

        ItemStack block(String owner, String name, int damage) throws ReflectiveOperationException, IOException {
            String key = owner + ":" + name;
            Block block = blocks.get(key);
            if (block == null) {
                Object registered = field(owners.get(owner), name);
                if (!(registered instanceof Block) || Item.getItemFromBlock((Block) registered) == null)
                    throw new IOException("GTNL_BLOCK_NOT_REGISTERED " + key);
                block = (Block) registered;
                blocks.put(key, block);
            }
            return new ItemStack(block, 1, damage);
        }

        ItemStack frame(Materials material) throws IOException {
            ItemStack result = GTOreDictUnificator.get(OrePrefixes.frameGt, material, 1);
            if (result == null) throw new IOException("GTNL_FRAME_NOT_REGISTERED " + material.name());
            return result;
        }

        List<ItemStack> glasses(ItemStack trigger) {
            int hint = ChannelDataAccessor.hasSubChannel(trigger, "glass")
                ? ChannelDataAccessor.getChannelData(trigger, "glass")
                : Math.min(trigger.stackSize, GlassTier.getMaxTierIndex());
            List<ItemStack> result = new ArrayList<>(glasses);
            ItemStack primary = result.remove(Math.max(0, Math.min(result.size() - 1, hint - 1)));
            result.add(0, primary);
            return result;
        }

        List<ItemStack> options(ItemStack casing, String... roles) {
            List<ItemStack> result = new ArrayList<>();
            result.add(casing);
            for (String role : roles) {
                result.addAll(hatches.get(role));
                if ("input_bus".equals(role)) result.addAll(hatches.get("dual_input"));
                if ("energy".equals(role)) {
                    result.addAll(hatches.get("energy_multi"));
                    result.addAll(hatches.get("energy_laser"));
                }
            }
            return result;
        }

        HologramGTNLElement hatch(String id, ItemStack casing, String... roles) {
            List<ItemStack> candidates = options(casing, roles);
            List<ItemStack> automatic = new ArrayList<>(candidates);
            if (id.startsWith("mixer.")) automatic.removeIf(
                stack -> stack.getItem() instanceof ItemMachines
                    && (is(stack, "steam_big") && stack.getItemDamage() != 22518
                        || is(stack, "steam_wireless") && stack.getItemDamage() != 22576
                        || is(stack, "steam") && stack.getItemDamage() != 31040));
            automatic.removeIf(stack -> inputPlacementBlacklisted(stack));
            return element(id, HologramGTNLElement.Kind.HATCH, candidates, automatic, true, "", -1);
        }

        boolean inputPlacementBlacklisted(ItemStack stack) {
            if (!is(stack, "input_bus")) return false;
            IMetaTileEntity prototype = prototypes.get(stack.getItemDamage());
            for (Class<?> type = prototype.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if ("gregtech.common.tileentities.machines.multi.purification.MTEHatchLensHousing".equals(name)
                    || "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.MTEHatchExtrusion".equals(name)
                    || "gregtech.common.tileentities.machines.MTEHatchPatternProvider".equals(name)
                    || "kubatech.tileentity.gregtech.hatch.MTEHatchElectrode".equals(name)) return true;
            }
            return false;
        }

        boolean is(ItemStack stack, String role) {
            if (stack == null || !(stack.getItem() instanceof ItemMachines)) return false;
            IMetaTileEntity prototype = prototypes.get(stack.getItemDamage());
            if (prototype == null) return false;
            if ("energy".equals(role)) return types.get("energy")
                .isInstance(prototype)
                || types.get("energy_multi")
                    .isInstance(prototype)
                || types.get("energy_laser")
                    .isInstance(prototype);
            if ("input_bus".equals(role)) for (ItemStack dual : hatches.get("dual_input"))
                if (dual.getItemDamage() == stack.getItemDamage()) return true;
            for (ItemStack registered : hatches.get(role))
                if (registered.getItemDamage() == stack.getItemDamage()) return true;
            return false;
        }

        boolean current() {
            if (registry.length != GregTechAPI.METATILEENTITIES.length) return false;
            for (int i = 0; i < registry.length; i++) if (registry[i] != GregTechAPI.METATILEENTITIES[i]) return false;
            try {
                if (!glassRegistry.equals(field(GlassTier.class.getName(), "tierToGlass"))) return false;
            } catch (ReflectiveOperationException failure) {
                return false;
            }
            for (Map.Entry<Integer, IMetaTileEntity> entry : prototypes.entrySet())
                if (GregTechAPI.METATILEENTITIES[entry.getKey()] != entry.getValue()) return false;
            for (Map.Entry<String, Block> entry : blocks.entrySet()) {
                int colon = entry.getKey()
                    .indexOf(':');
                try {
                    if (field(
                        owners.get(
                            entry.getKey()
                                .substring(0, colon)),
                        entry.getKey()
                            .substring(colon + 1))
                        != entry.getValue()) return false;
                } catch (ReflectiveOperationException failure) {
                    return false;
                }
            }
            return true;
        }
    }

    private static String materialKey(ItemStack stack) {
        return Item.itemRegistry.getNameForObject(stack.getItem()) + "@" + stack.getItemDamage();
    }

    static String hatchRole(IStructureElement<?> element, ItemStack stack) {
        if (!(element instanceof HologramGTNLElement) || !((HologramGTNLElement) element).accepts(stack)) return "";
        try {
            Catalog materials = materials();
            if ("mixer.shell".equals(((HologramGTNLElement) element).id)) {
                GtsrState compatibility;
                synchronized (STEAM_COMPATIBILITY) {
                    compatibility = STEAM_COMPATIBILITY.get(element);
                }
                if (compatibility != null) {
                    String cooling = compatibility.coolingRole(materials.prototypes.get(stack.getItemDamage()));
                    if (!cooling.isEmpty()) return cooling;
                }
            }
            for (String role : Arrays.asList(
                "nuclear_item",
                "nuclear_fluid",
                "steam_wireless",
                "steam_big",
                "steam",
                "parallel",
                "maintenance",
                "energy",
                "input_bus",
                "output_bus",
                "input",
                "output",
                "steam_input_bus",
                "steam_output_bus")) if (materials.is(stack, role)) return "gtnl:" + role;
            return "";
        } catch (ReflectiveOperationException | IOException failure) {
            return "";
        }
    }

    static boolean allowedFacing(IStructureElement<?> element, ItemStack stack, ForgeDirection direction) {
        if (!(element instanceof HologramGTNLElement) || !((HologramGTNLElement) element).accepts(stack)
            || !(stack.getItem() instanceof ItemMachines)
            || direction == ForgeDirection.UNKNOWN) return false;
        int id = stack.getItemDamage();
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] == null)
            return false;
        try {
            String owner = GregTechAPI.METATILEENTITIES[id].getClass()
                .getMethod("isFacingValid", ForgeDirection.class)
                .getDeclaringClass()
                .getName();
            if (owner.equals("com.science.gtnl.common.machine.hatch.NuclearItemBus")
                || owner.equals("com.science.gtnl.common.machine.hatch.NuclearFluidHatch"))
                return direction == ForgeDirection.UP;
            return owner.equals("gregtech.api.metatileentity.implementations.MTEHatchInput")
                || owner.equals("gregtech.api.metatileentity.implementations.MTEHatchOutput")
                || owner.equals("gregtech.api.metatileentity.implementations.MTEHatchInputBus")
                || owner.equals("gregtech.api.metatileentity.implementations.MTEHatchOutputBus")
                || owner.equals("gregtech.api.metatileentity.implementations.MTEHatchMaintenance")
                || owner.equals("gregtech.api.metatileentity.implementations.MTEHatchEnergy")
                || owner
                    .equals("gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.MTEHatchCustomFluidBase")
                || owner.equals("tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti")
                || owner.equals("com.science.gtnl.common.machine.hatch.CustomFluidHatch")
                || owner.equals("com.science.gtnl.common.machine.hatch.ParallelControllerHatch");
        } catch (NoSuchMethodException failure) {
            return false;
        }
    }

    /** Pure plan validation; never invokes the controller's checkMachine, hatch adders, or counts. */
    static String constructionFailure(HologramSession session) {
        if (session.capture == null) return null;
        if (session.virtual && session.virtualPreset != null) {
            HologramPresets.Preset preset = session.virtualPreset;
            String targetFailure = savedControllerTargetFailure(session);
            if (targetFailure != null) return targetFailure;
            if (preset.structures != null && !preset.structures.isEmpty()) {
                for (HologramPresets.Structure structure : preset.structures) {
                    if (structure == null || !structure.enabled) continue;
                    String failure = savedConstructionFailure(
                        session,
                        HologramBlueprintData.sourceClass(preset, structure),
                        structure.controllerId,
                        structure.semanticNbt,
                        structure.id);
                    if (failure != null) return failure;
                }
                return null;
            }
            return savedConstructionFailure(
                session,
                HologramBlueprintData.sourceClass(preset, null),
                preset.controllerId,
                preset.semanticNbt,
                "");
        }
        if (!session.capture.sourceMetadata.hasKey("gtnlSchema"))
            return isGTNLController(session.machine) ? "GTNL_DECLARATION_RESCAN_REQUIRED" : null;
        return validatePlan(session, session.capture.sourceMetadata, "", true);
    }

    private static String savedConstructionFailure(HologramSession session, String sourceClass, int controllerId,
        String encodedProof, String structureId) {
        IMetaTileEntity controller = registeredPrototype(controllerId);
        boolean declaredGTNL = sourceClass != null && sourceClass.startsWith("com.science.gtnl.");
        if (!declaredGTNL && !isGTNLController(controller)) return null;
        if (!declaredGTNL || controller != null && !sourceClass.equals(
            controller.getClass()
                .getName()))
            return "GTNL_SAVED_SOURCE_MISMATCH";
        String family = FAMILIES.get(sourceClass);
        if (family == null) return "GTNL_UNREVIEWED_MACHINE";
        if (!isGTNLController(controller)) return "GTNL_SAVED_SOURCE_MISMATCH";
        NBTTagCompound proof = savedProof(encodedProof);
        if (proof == null) return "GTNL_SAVED_RESCAN_REQUIRED: 请重新读取该机器的新建模板";
        if (!family.equals(proof.getString("family"))) return "GTNL_SAVED_SOURCE_MISMATCH";
        return validatePlan(session, proof, structureId, false);
    }

    /** Saved labels cannot hide a GTNL controller material behind an ordinary or disabled source. */
    private static String savedControllerTargetFailure(HologramSession session) {
        HologramPresets.Preset preset = session.virtualPreset;
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            if (cell.anchor || cell.excluded || !HologramSessionPolicy.membershipEnabled(session, cell)) continue;
            ItemStack target = HologramCellPolicy.targetFor(session, cell, index);
            if (target == null || !(target.getItem() instanceof ItemMachines)) continue;
            IMetaTileEntity controller = registeredPrototype(target.getItemDamage());
            if (!isGTNLController(controller)) continue;
            String actualClass = controller.getClass()
                .getName();
            boolean matched = false;
            if (preset.structures != null && !preset.structures.isEmpty()) {
                for (HologramPresets.Structure source : preset.structures) if (source != null && source.enabled
                    && cell.belongsTo(source.id)
                    && source.controllerId == target.getItemDamage()
                    && actualClass.equals(HologramBlueprintData.sourceClass(preset, source))) matched = true;
            } else matched = preset.controllerId == target.getItemDamage()
                && actualClass.equals(HologramBlueprintData.sourceClass(preset, null));
            if (!matched) return "GTNL_SAVED_SOURCE_MISMATCH";
        }
        return null;
    }

    private static IMetaTileEntity registeredPrototype(int id) {
        return id >= 0 && id < GregTechAPI.METATILEENTITIES.length ? GregTechAPI.METATILEENTITIES[id] : null;
    }

    private static NBTTagCompound savedProof(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            NBTTagCompound proof = HologramBlueprintNbt.decode(encoded);
            return proof != null && proof.getInteger("gtnlSchema") == 1
                && COMMIT.equals(proof.getString("sourceCommit"))
                && "NEW_TEMPLATE".equals(proof.getString("purpose")) ? proof : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static String validatePlan(HologramSession session, NBTTagCompound metadata, String structureId,
        boolean requireSource) {
        if (!metadata.getCompoundTag("capabilities")
            .getBoolean("construct")) return "GTNL_LIVE_AIR_SPHERE: 现场空气球壳不能作为新建模板施工，请切换新建模板重新采集";
        if (requireSource && !session.capture.sourceCurrent()) return session.capture.sourceValidityFailure();
        if (!environmentFailure().isEmpty()) return environmentFailure();
        try {
            Catalog materials = materials();
            String family = metadata.getString("family");
            Map<String, Integer> counts = new LinkedHashMap<>();
            Integer glassTier = null;
            Integer precisionMachineTier = null;
            Integer heatingCoilTier = null;
            int maxEnergyTier = -1;
            String elementPrefix = "assembler_matrix".equals(family) ? "assembler."
                : "large_steam_mixer".equals(family) ? "mixer."
                    : "nuclear_reactor".equals(family) ? "nuclear."
                        : "precision_assembler".equals(family) ? "precision." : "aeep.";
            int interpreted = 0;
            for (int index = 0; index < session.capture.cells.size(); index++) {
                HologramCapture.Cell cell = session.capture.cells.get(index);
                if (!structureId.isEmpty() && !cell.belongsTo(structureId)) continue;
                if (cell.anchor || cell.element == StructureUtility.isAir()) continue;
                if (!(cell.element instanceof HologramGTNLElement))
                    return "GTNL_SAVED_RESCAN_REQUIRED: 保存的来源信息不授予施工资格，请重新读取模板";
                interpreted++;
                HologramGTNLElement element = (HologramGTNLElement) cell.element;
                if (!element.id.startsWith(elementPrefix)) return "GTNL_SAVED_SOURCE_MISMATCH";
                increment(counts, "cells:" + element.id);
                if ("precision.machine".equals(element.id)) {
                    if (precisionMachineTier != null && precisionMachineTier != element.tier)
                        return "GTNL_PRECISION_MACHINE_TIER_MISMATCH";
                    precisionMachineTier = element.tier;
                }
                if ("aeep.coil".equals(element.id)) {
                    if (element.tier < 1 || element.tier > HeatingCoilLevel.getMaxTier() + 1)
                        return "GTNL_AEEP_HEATING_COIL_REQUIRED";
                    if (heatingCoilTier != null && heatingCoilTier != element.tier)
                        return "GTNL_AEEP_HEATING_COIL_TIER_MISMATCH";
                    heatingCoilTier = element.tier;
                }
                ItemStack chosen = HologramCellPolicy.targetFor(session, cell, index);
                if (!element.accepts(chosen)) return "GTNL_INVALID_TARGET " + element.id;
                if (chosen.getItem() instanceof ItemMachines) {
                    boolean facingKnown = false;
                    for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
                        facingKnown |= allowedFacing(element, chosen, direction);
                    if (!facingKnown) return "GTNL_HATCH_FACING_UNREVIEWED";
                    Integer facing = session.facingPins.get(HologramValues.position(cell));
                    if (facing != null && !allowedFacing(element, chosen, ForgeDirection.getOrientation(facing)))
                        return "GTNL_HATCH_FACING_INVALID";
                }
                if (!(chosen.getItem() instanceof ItemMachines)) increment(counts, element.id);
                for (String role : materials.types.keySet()) if (materials.is(chosen, role)) increment(counts, role);
                if ("assembler.core".equals(element.id) && chosen.getItemDamage() == 9) increment(counts, "speed");
                if ("precision.glass".equals(element.id)) {
                    Integer current = materials.glassTiers.get(materialKey(chosen));
                    if (current == null || glassTier != null && !glassTier.equals(current))
                        return "GTNL_GLASS_TIER_MISMATCH";
                    glassTier = current;
                }
                if ("precision_assembler".equals(family) && materials.is(chosen, "energy")) {
                    IMetaTileEntity hatch = materials.prototypes.get(chosen.getItemDamage());
                    maxEnergyTier = Math.max(maxEnergyTier, ((MTEHatch) hatch).mTier);
                }
                if ("large_steam_mixer".equals(family) && session.autoTargets.containsKey(index)
                    && !element.matchesTarget(session.player.worldObj, cell.x, cell.y, cell.z, chosen)) {
                    for (String role : Arrays.asList("steam", "steam_big", "steam_wireless"))
                        if (materials.is(chosen, role)) increment(counts, "auto_" + role);
                }
            }
            if (interpreted == 0) return "GTNL_SAVED_RESCAN_REQUIRED";
            if (!"large_steam_mixer".equals(family) && count(counts, "maintenance") > 1)
                return "GTNL_MAINTENANCE_MAX_1";
            if (!"large_steam_mixer".equals(family) && count(counts, "parallel") > 1) return "GTNL_PARALLEL_MAX_1";
            switch (family) {
                case "assembler_matrix":
                    if (count(counts, "assembler.core") != 343) return "GTNL_ASSEMBLER_CORE_343";
                    if (count(counts, "speed") > 5) return "GTNL_ASSEMBLER_SPEED_MAX_5";
                    break;
                case "large_steam_mixer":
                    if (count(counts, "mixer.shell") < 30) return "GTNL_MIXER_CASING_MIN_30";
                    if (count(counts, "steam") + count(counts, "steam_big") + count(counts, "steam_wireless") < 1)
                        return "GTNL_MIXER_STEAM_REQUIRED";
                    if (count(counts, "steam") > 1) return "GTNL_MIXER_NORMAL_STEAM_MAX_1";
                    for (String role : Arrays.asList("steam", "steam_big", "steam_wireless"))
                        if (count(counts, "auto_" + role) > 0 && count(counts, role) > 1)
                            return "GTNL_STEAM_AUTO_ALREADY_PRESENT " + role;
                    break;
                case "aeep":
                    if (count(counts, "aeep.shell") < 350) return "GTNL_AEEP_CASING_MIN_350";
                    if (heatingCoilTier == null || count(counts, "aeep.coil") != 56)
                        return "GTNL_AEEP_HEATING_COIL_REQUIRED";
                    if (count(counts, "aeep.sphere.j") != 52 || count(counts, "aeep.sphere.k") != 135)
                        return "GTNL_AEEP_SPHERE_MATERIALS";
                    break;
                case "nuclear_reactor":
                    if (count(counts, "nuclear_item") < 1) return "GTNL_NUCLEAR_ITEM_REQUIRED";
                    if (count(counts, "nuclear_fluid") < 1) return "GTNL_NUCLEAR_FLUID_REQUIRED";
                    if (count(counts, "output") < 1) return "GTNL_NUCLEAR_OUTPUT_REQUIRED";
                    break;
                case "precision_assembler":
                    if (count(counts, "precision.unit") < 30) return "GTNL_PRECISION_CASING_MIN_30";
                    if (precisionMachineTier == null) return "GTNL_PRECISION_MACHINE_TIER_MISSING";
                    if (precisionMachineTier < 9 && maxEnergyTier > precisionMachineTier)
                        return "GTNL_PRECISION_ENERGY_TIER";
                    break;
                default:
                    return "GTNL_UNREVIEWED_MACHINE";
            }
            return null;
        } catch (ReflectiveOperationException | IOException failure) {
            return "GTNL_CONSTRAINTS_UNAVAILABLE " + failure.getMessage();
        }
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.put(key, count(values, key) + 1);
    }

    private static int count(Map<String, Integer> values, String key) {
        return values.getOrDefault(key, 0);
    }
}

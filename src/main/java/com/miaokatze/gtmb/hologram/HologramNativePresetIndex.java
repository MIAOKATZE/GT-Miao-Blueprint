// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** The hatch-only topology observed alongside one native capture; never rescans the machine. */
public final class HologramNativePresetIndex {

    private static final int MAX_IDS = 256;
    private static final int MAX_SLOTS = 4096;
    private static final int MAX_CLASSES = 128;

    @Desugar
    public record Choice(int index, int itemId, int facing) {}

    @Desugar
    public record Outcome(List<Choice> choices, boolean complete, String reason) {

        public Outcome {
            choices = Collections.unmodifiableList(new ArrayList<>(choices));
        }
    }

    @FunctionalInterface
    interface Candidates {

        List<ItemStack> at(int index) throws IOException;

        /** Capture-owned proof of identical automatic candidates, including the effective piece trigger. */
        default Object sharedKey(int index) throws IOException {
            return null;
        }
    }

    private static final class Category {

        final IStructureElement<?> declaration;
        final Map<String, IHatchElement<?>> roles = new LinkedHashMap<>();
        final List<Slot> slots = new ArrayList<>();
        final boolean complete;

        Category(IStructureElement<?> declaration, Map<String, IHatchElement<?>> types, Map<String, Integer> quotas) {
            this.declaration = declaration;
            boolean sound = HologramHatchPolicy.globalRequirementsComplete(declaration);
            for (IStructureElement<?> part : HologramElementCatalog.parts(declaration))
                for (Map.Entry<IHatchElement<?>, Integer> entry : HologramHatchPolicy.requirements(part)
                    .entrySet()) {
                        IHatchElement<?> type = entry.getKey();
                        if (type == null || type.name() == null
                            || type.name()
                                .isEmpty()
                            || entry.getValue() < 1) {
                            sound = false;
                            continue;
                        }
                        IHatchElement<?> prior = types.putIfAbsent(type.name(), type);
                        if (prior != null && prior != type) sound = false;
                        roles.putIfAbsent(type.name(), type);
                        quotas.merge(type.name(), entry.getValue(), Math::max);
                    }
            complete = sound;
        }
    }

    @Desugar
    private record Slot(int index, int installed, boolean open) {}

    @Desugar
    private record Offer(int id, int tier, String profileRole, Set<String> roles, int[] credits, boolean[] allowed) {}

    @Desugar
    private record Position(Slot slot, Category category, List<Offer> offers) {}

    @Desugar
    private record ProfileUnit(int id, String role) {}

    @Desugar
    private record SavedProfileKey(String role, HologramBlueprintResources.ExactKey item) {}

    private final Map<IStructureElement<?>, Category> classes = new IdentityHashMap<>();
    private final List<Category> hatchClasses = new ArrayList<>();
    private final Map<String, IHatchElement<?>> types = new LinkedHashMap<>();
    private final Map<String, Integer> quotas = new LinkedHashMap<>();
    private int slots;
    private boolean unsupported;
    private boolean incompleteSite;
    private final boolean reviewedGtsr;

    HologramNativePresetIndex() {
        this(null);
    }

    HologramNativePresetIndex(Object controller) {
        reviewedGtsr = HologramGtsrBasicHatchPolicy.capturedController(controller);
    }

    void record(IStructureElement<?> element, HologramControllerTarget.Cell cell, int index) {
        if (cell.anchor()) return;
        record(element, index, cell.actualStack(), cell.actualBlock() == Blocks.air, cell.localObservationMissing());
    }

    void record(IStructureElement<?> element, int index, ItemStack actual, boolean open, boolean unknown) {
        if (element == null) return;
        Category category = classes.get(element);
        if (category == null) {
            category = new Category(element, types, quotas);
            classes.put(element, category);
            unsupported |= !category.complete;
            if (!category.roles.isEmpty()) {
                hatchClasses.add(category);
                unsupported |= hatchClasses.size() > MAX_CLASSES;
            }
        }
        if (category.roles.isEmpty()) return;
        if (++slots > MAX_SLOTS || unknown) {
            unsupported = true;
            incompleteSite = true;
            return;
        }
        int id = hatchId(actual);
        category.slots.add(new Slot(index, id, id == 0 && open));
    }

    NBTTagCompound captureProfile(Map<Integer, ItemStack> overrides, NBTTagCompound settings,
        java.util.function.IntFunction<ItemStack> installed) throws IOException {
        if (incompleteSite) throw new IOException("当前仓室现场未完整读取。");
        NBTTagCompound profile = new NBTTagCompound();
        profile.setInteger("tier", settings.getInteger("hatchTier"));
        profile.setBoolean("downgrade", settings.getBoolean("hatchDowngrade"));
        Map<SavedProfileKey, NBTTagCompound> stored = new LinkedHashMap<>();
        Set<Integer> unrecognized = new HashSet<>();
        overrides.forEach((index, stack) -> { if (hatchId(stack) != 0) unrecognized.add(index); });
        int eligible = 0;
        for (Category category : hatchClasses) for (Slot slot : category.slots) {
            ItemStack stack = overrides.containsKey(slot.index()) ? overrides.get(slot.index())
                : slot.installed() == 0 ? null : installed.apply(slot.index());
            if (hatchId(stack) == 0) continue;
            unrecognized.remove(slot.index());
            if (HologramRecovery.hasSeal(stack)) throw new IOException("不能保存带回收凭记的仓室。");
            String role = profileRole(category, stack);
            if (role.isEmpty()) throw new IOException("当前仓室缺少可保存的原生声明角色。");
            if (++eligible > 512) throw new IOException("当前仓室搭配超过保存上限。");
            var key = new SavedProfileKey(role, new HologramBlueprintResources.ExactKey(stack, null));
            NBTTagCompound entry = stored.get(key);
            if (entry == null) {
                if (stored.size() >= 128) throw new IOException("当前仓室搭配超过型号上限。");
                String item = (String) net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
                if (item == null || item.isEmpty()) throw new IOException("当前仓室未注册。");
                entry = new NBTTagCompound();
                entry.setString("role", role);
                entry.setString("item", item);
                entry.setInteger("damage", stack.getItemDamage());
                if (stack.hasTagCompound()) entry.setTag(
                    "tag",
                    stack.getTagCompound()
                        .copy());
                stored.put(key, entry);
            }
            entry.setInteger("count", entry.getInteger("count") + 1);
        }
        if (!unrecognized.isEmpty()) throw new IOException("部分手选仓室没有可保存的原生声明位置。");
        NBTTagList entries = new NBTTagList();
        for (NBTTagCompound entry : stored.values()) entries.appendTag(entry);
        profile.setTag("entries", entries);
        return profile;
    }

    Prepared prepare(NBTTagCompound settings, Map<Integer, Integer> manual, Candidates candidates) throws IOException {
        return prepare(settings, manual, candidates, candidates);
    }

    Prepared prepare(NBTTagCompound settings, Map<Integer, Integer> manual, Candidates candidates,
        Candidates gtsrCatalog) throws IOException {
        boolean custom = settings.hasKey("hatchProfile", 10);
        boolean gtsrBasic = reviewedGtsr && !custom
            && !incompleteSite
            && !hatchClasses.isEmpty()
            && hatchClasses.size() <= MAX_CLASSES
            && hatchClasses.stream()
                .allMatch(category -> HologramGtsrBasicHatchPolicy.knownU(category.roles))
            && quotas.values()
                .stream()
                .allMatch(count -> count == 1);
        if (unsupported && !gtsrBasic) return Prepared.unavailable("仓室现场或原生配额未完整确认。");
        if (types.isEmpty()) return Prepared.unavailable("此结构未声明整机仓室配额。");
        int tier = settings.getInteger("hatchTier");
        boolean downgrade = settings.getBoolean("hatchDowngrade");
        if (!custom && (!settings.hasKey("hatchTier", 3) || tier < 0 || tier > 127))
            return Prepared.unavailable("电压级无效。");
        List<ProfileUnit> profile = custom ? profile(settings.getCompoundTag("hatchProfile")) : null;
        if (custom && profile == null) return Prepared.unavailable("搭配为空或含无法按标准型号绑定的仓室。");
        List<String> roleNames = gtsrBasic ? HologramGtsrBasicHatchPolicy.GROUPS : new ArrayList<>(types.keySet());
        int[] remaining = new int[roleNames.size()];
        for (int i = 0; i < remaining.length; i++) remaining[i] = gtsrBasic ? 1 : quotas.get(roleNames.get(i));
        List<Position> positions = new ArrayList<>();
        Map<Category, List<Slot>> free = new LinkedHashMap<>();
        Map<Integer, Integer> planStock = new LinkedHashMap<>();
        for (Category category : hatchClasses) {
            List<Slot> available = new ArrayList<>();
            for (Slot slot : category.slots) {
                Integer chosen = manual.get(slot.index());
                int fixed = chosen == null ? slot.installed() : chosen;
                if (fixed != 0) {
                    Offer proof = gtsrBasic ? gtsrOffer(fixed, category, false) : offer(fixed, category, roleNames);
                    if (proof.roles()
                        .isEmpty()) return Prepared.unavailable("已有或手选仓室与原生声明不匹配。");
                    if (custom) {
                        int match = -1;
                        for (int i = 0; i < profile.size(); i++) {
                            ProfileUnit unit = profile.get(i);
                            if (unit.id() == fixed && proof.profileRole()
                                .equals(unit.role())) {
                                match = i;
                                break;
                            }
                        }
                        if (match < 0) return Prepared.unavailable("已有或手选仓室与该搭配不一致。");
                        profile.remove(match);
                    }
                    debit(remaining, proof.credits());
                } else if (chosen == null && slot.open()) available.add(slot);
            }
            if (!available.isEmpty()) free.put(category, available);
        }
        if ((!custom || profile.isEmpty()) && fulfilled(remaining))
            return new Prepared(new int[0], Collections.emptyList(), remaining, profile, "");
        for (Map.Entry<Category, List<Slot>> group : free.entrySet()) {
            Category category = group.getKey();
            List<Slot> available = group.getValue();
            Map<Object, List<Offer>> shared = new LinkedHashMap<>();
            Candidates source = gtsrBasic ? gtsrCatalog : candidates;
            for (Slot slot : available) {
                Object key = source.sharedKey(slot.index());
                List<Offer> cached = key == null ? null : shared.get(key);
                if (cached != null) {
                    if (!cached.isEmpty()) positions.add(new Position(slot, category, cached));
                    continue;
                }
                List<Offer> options = new ArrayList<>();
                Set<Integer> seen = new HashSet<>();
                for (ItemStack stack : source.at(slot.index())) {
                    int id = hatchId(stack);
                    if (id == 0 || stack.hasTagCompound() || !seen.add(id)) continue;
                    Offer candidate = gtsrBasic ? gtsrOffer(id, category, true) : offer(id, category, roleNames);
                    if (candidate.roles()
                        .isEmpty()) continue;
                    if (custom) {
                        boolean matches = false;
                        for (ProfileUnit unit : profile) if (unit.id() == id && candidate.profileRole()
                            .equals(unit.role())) {
                                matches = true;
                                break;
                            }
                        if (!matches) continue;
                    } else if (candidate.tier() != tier && (!downgrade || candidate.tier() > tier)) continue;
                    options.add(candidate);
                    planStock.putIfAbsent(id, planStock.size());
                    if (planStock.size() > MAX_IDS) return Prepared.unavailable("仓室型号过多，请指定更窄的电压级。");
                }
                options.sort(
                    Comparator.comparingInt(Offer::tier)
                        .reversed());
                if (key != null) shared.put(key, options);
                if (!options.isEmpty()) positions.add(new Position(slot, category, options));
            }
        }
        int[] ids = new int[planStock.size()];
        planStock.forEach((id, index) -> ids[index] = id);
        return new Prepared(ids, positions, remaining, profile, "");
    }

    private static List<ProfileUnit> profile(NBTTagCompound value) {
        NBTTagList entries = value.getTagList("entries", 10);
        if (entries.tagCount() == 0 || entries.tagCount() > 128) return null;
        List<ProfileUnit> result = new ArrayList<>();
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound row = entries.getCompoundTagAt(i);
            ItemStack stack = HologramHatchProfiles.stack(row);
            String role = row.getString("role");
            int count = row.getInteger("count"), id = hatchId(stack);
            if (id == 0 || stack.hasTagCompound()
                || role.isEmpty()
                || role.length() > 256
                || count < 1
                || count > 512 - result.size()) return null;
            for (int n = 0; n < count; n++) result.add(new ProfileUnit(id, role));
        }
        return result;
    }

    private static Offer gtsrOffer(int id, Category category, boolean automatic) {
        ItemStack sample = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
        boolean[] groups = HologramGtsrBasicHatchPolicy
            .provedGroups(category.roles, ItemMachines.getMetaTileEntity(sample), automatic);
        Set<String> matches = new LinkedHashSet<>();
        int[] satisfied = new int[groups.length];
        for (int i = 0; i < groups.length; i++) if (groups[i]) {
            matches.add(HologramGtsrBasicHatchPolicy.GROUPS.get(i));
            satisfied[i] = 1;
        }
        return new Offer(id, HologramCellPolicy.hatchTier(sample), "", matches, satisfied, groups);
    }

    private Offer offer(int id, Category category, List<String> roleNames) {
        ItemStack sample = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
        IMetaTileEntity mte = ItemMachines.getMetaTileEntity(sample);
        Set<String> matches = new LinkedHashSet<>();
        for (Map.Entry<String, IHatchElement<?>> term : category.roles.entrySet())
            if (HologramHatchPolicy.contribution(term.getValue(), mte) > 0) matches.add(term.getKey());
        int[] credits = new int[roleNames.size()];
        boolean[] allowed = new boolean[roleNames.size()];
        for (int i = 0; i < credits.length; i++) {
            String role = roleNames.get(i);
            credits[i] = HologramHatchPolicy.contribution(types.get(role), mte);
            allowed[i] = matches.contains(role);
        }
        return new Offer(
            id,
            HologramCellPolicy.hatchTier(sample),
            profileRole(category, sample),
            matches,
            credits,
            allowed);
    }

    private static String profileRole(Category category, ItemStack sample) {
        var pure = HologramElementCatalog.pure(category.declaration);
        if (pure != null) {
            String role = pure.candidateRoleId(sample);
            return role == null ? "" : role;
        }
        IMetaTileEntity mte = ItemMachines.getMetaTileEntity(sample);
        if (mte != null) for (IStructureElement<?> part : HologramElementCatalog.parts(category.declaration))
            for (IHatchElement<?> type : HologramHatchPolicy.declared(part))
                if (HologramHatchPolicy.matchesHatch(type, mte)) return type.name();
        return "";
    }

    private static int hatchId(ItemStack stack) {
        if (stack == null || HologramCellPolicy.hatchTier(stack) == Integer.MAX_VALUE) return 0;
        int id = stack.getItemDamage();
        return id > 0 && id <= 32767 ? id : 0;
    }

    private static void debit(int[] need, int[] credits) {
        for (int i = 0; i < need.length; i++) need[i] = Math.max(0, need[i] - credits[i]);
    }

    private static boolean fulfilled(int[] need) {
        for (int demand : need) if (demand > 0) return false;
        return true;
    }

    public static final class Prepared {

        private final int[] ids, needed;
        private final List<Position> positions;
        private final List<ProfileUnit> profile;
        private final String unavailable;
        private java.util.function.Supplier<String> validity = () -> null;

        private Prepared(int[] ids, List<Position> positions, int[] needed, List<ProfileUnit> profile,
            String unavailable) {
            this.ids = ids;
            this.positions = positions;
            this.needed = needed;
            this.profile = profile;
            this.unavailable = unavailable;
        }

        static Prepared unavailable(String reason) {
            return new Prepared(new int[0], Collections.emptyList(), new int[0], null, reason);
        }

        public int[] stockIds() {
            return ids.clone();
        }

        Prepared guarded(java.util.function.Supplier<String> guard) {
            validity = guard;
            return this;
        }

        public Outcome resolve(int[] counts, boolean creative) {
            if (!unavailable.isEmpty()) return failure(unavailable);
            String invalid = validity.get();
            if (invalid != null) return failure(invalid);
            if (!creative && (counts == null || counts.length != ids.length)) return failure("库存回执不完整。");
            Map<Integer, Integer> stock = new LinkedHashMap<>();
            for (int i = 0; i < ids.length; i++)
                stock.put(ids[i], creative ? Integer.MAX_VALUE : Math.max(0, counts[i]));
            int[] remaining = needed.clone();
            List<Choice> choices = new ArrayList<>();
            if (profile != null) {
                String reason = resolveProfile(stock, remaining, choices);
                if (!reason.isEmpty()) return failure(reason);
            } else {
                // Only lists produced under the same explicit sharing proof represent an entire queue.
                // Unproved/position-dependent candidates get independent lists during prepare.
                Map<List<Offer>, java.util.ArrayDeque<Position>> groups = new LinkedHashMap<>();
                for (Position position : positions)
                    groups.computeIfAbsent(position.offers(), unused -> new java.util.ArrayDeque<>())
                        .add(position);
                while (!fulfilled(remaining)) {
                    int bestRole = -1, bestCount = Integer.MAX_VALUE;
                    for (int r = 0; r < remaining.length; r++) if (remaining[r] > 0) {
                        int count = 0;
                        for (java.util.ArrayDeque<Position> group : groups.values())
                            if (!group.isEmpty() && bestOffer(group.peekFirst(), r, stock) != null)
                                count += group.size();
                        if (count < bestCount) {
                            bestRole = r;
                            bestCount = count;
                        }
                    }
                    if (bestRole < 0 || bestCount == 0) return failure("仓室位置或所选库存不足，未改动。");
                    java.util.ArrayDeque<Position> selectedGroup = null;
                    Offer selected = null;
                    for (java.util.ArrayDeque<Position> group : groups.values()) if (!group.isEmpty()) {
                        Offer offer = bestOffer(group.peekFirst(), bestRole, stock);
                        if (offer != null && (selected == null || offer.tier() > selected.tier())) {
                            selectedGroup = group;
                            selected = offer;
                        }
                    }
                    if (selectedGroup == null || selected == null) return failure("仓室候选已失效。");
                    Position chosen = selectedGroup.removeFirst();
                    stock.computeIfPresent(selected.id(), (id, count) -> count - 1);
                    debit(remaining, selected.credits());
                    choices.add(
                        new Choice(
                            chosen.slot()
                                .index(),
                            selected.id(),
                            -1));
                }
            }
            return new Outcome(choices, true, choices.isEmpty() ? "仓室已满足。" : "仓室预设已生成。");
        }

        private Offer bestOffer(Position position, int role, Map<Integer, Integer> stock) {
            for (Offer option : position.offers())
                if (option.allowed()[role] && stock.getOrDefault(option.id(), 0) > 0) return option;
            return null;
        }

        private String resolveProfile(Map<Integer, Integer> stock, int[] remaining, List<Choice> result) {
            if (profile.isEmpty()) return fulfilled(remaining) ? "" : "搭配未满足声明配额。";
            int[] owner = new int[positions.size()];
            Arrays.fill(owner, -1);
            int[] visits = new int[positions.size()];
            int[] work = new int[] { 1_000_000 };
            for (int unit = 0; unit < profile.size(); unit++) {
                ProfileUnit desired = profile.get(unit);
                int available = stock.getOrDefault(desired.id(), 0);
                if (available <= 0) return "搭配所需仓室库存不足。";
                stock.put(desired.id(), available - 1);
                if (!assign(unit, unit + 1, visits, owner, work)) return "搭配没有足够的原生声明位置。";
            }
            for (int i = 0; i < owner.length; i++) if (owner[i] >= 0) {
                Position position = positions.get(i);
                Offer candidate = exact(position, profile.get(owner[i]));
                debit(remaining, candidate.credits());
                result.add(
                    new Choice(
                        position.slot()
                            .index(),
                        candidate.id(),
                        -1));
            }
            return fulfilled(remaining) ? "" : "搭配未满足声明配额。";
        }

        private boolean assign(int unit, int stamp, int[] visits, int[] owners, int[] work) {
            for (int slot = 0; slot < positions.size() && --work[0] > 0; slot++) {
                if (visits[slot] == stamp || exact(positions.get(slot), profile.get(unit)) == null) continue;
                visits[slot] = stamp;
                if (owners[slot] < 0 || assign(owners[slot], stamp, visits, owners, work)) {
                    owners[slot] = unit;
                    return true;
                }
            }
            return false;
        }

        private Offer exact(Position position, ProfileUnit requested) {
            for (Offer offer : position.offers()) if (offer.id() == requested.id() && offer.profileRole()
                .equals(requested.role())) return offer;
            return null;
        }

        private static Outcome failure(String reason) {
            return new Outcome(Collections.emptyList(), false, reason);
        }
    }
}

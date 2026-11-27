// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab.costs;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public final class GtmbConstructionCostsLateMixinLoader implements ILateMixinLoader {

    public String getMixinConfig() {
        return "mixins.gtmb.testlab.construction-costs.json";
    }

    public List<String> getMixins(Set<String> mods) {
        if (!Boolean.getBoolean("gtmb.testlab.constructionCosts") || !Boolean.getBoolean("miaotest.host.environment"))
            return Collections.emptyList();
        return Arrays.asList("MixinSavedJobCosts", "MixinGtEndTickCosts", "MixinJournalPersistCosts");
    }
}

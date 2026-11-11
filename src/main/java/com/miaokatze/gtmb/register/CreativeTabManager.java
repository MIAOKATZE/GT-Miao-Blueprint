// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.register;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

public final class CreativeTabManager {

    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs("gtmb") {

        @Override
        public Item getTabIconItem() {
            return ProjectorRegistry.projector;
        }
    };

    private CreativeTabManager() {}
}

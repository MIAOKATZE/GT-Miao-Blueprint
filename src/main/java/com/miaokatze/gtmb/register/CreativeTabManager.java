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

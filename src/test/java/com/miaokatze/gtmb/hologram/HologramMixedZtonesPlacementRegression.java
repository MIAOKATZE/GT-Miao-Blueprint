// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import java.nio.file.Paths;
public final class HologramMixedZtonesPlacementRegression {
    public static void main(String[] args)throws Exception{
        if(!MinecraftTestBootstrap.enter(HologramMixedZtonesPlacementRegression.class,args))return;
        HologramSavedPlacementAdapterRegression.realZtonesUsersSource(Paths.get(System.getProperty("gtmb.mixedZtonesSource")));
    }
}

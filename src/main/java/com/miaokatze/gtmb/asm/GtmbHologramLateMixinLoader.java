package com.miaokatze.gtmb.asm;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public final class GtmbHologramLateMixinLoader implements ILateMixinLoader {

    public String getMixinConfig() {
        return "mixins.gtmb.hologram.json";
    }

    public List<String> getMixins(Set<String> mods) {
        return Arrays.asList(
            "hologram.MixinHologramBuildPiece",
            "hologram.MixinHologramBuildOrHints",
            "hologram.MixinHologramGregTechDeclarations",
            "hologram.MixinHologramHints",
            "hologram.MixinHologramChannels",
            "hologram.MixinHologramRecoveryMachines",
            "hologram.MixinHologramRecoveryFrames",
            "hologram.MixinHologramHatchPolicy");
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

/** Stored representation only. Passing this gate grants no content, world or construction authority. */
public final class HologramCompactRequiredException extends IOException {

    private static final long serialVersionUID = 1L;
    public static final String MESSAGE = "此旧格式蓝图需要升级，请在本地蓝图库选择该蓝图并点击「升级」";

    public HologramCompactRequiredException() {
        super(MESSAGE);
    }

    public static void require(byte[] source) throws IOException {
        if (!HologramBlueprintCompactCodec.isCompact(source, HologramBlueprintArtifact.MAX_JSON_BYTES))
            throw new HologramCompactRequiredException();
    }
}

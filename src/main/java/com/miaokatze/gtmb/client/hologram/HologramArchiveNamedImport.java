// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.regex.Pattern;

/** Discriminates the explicit archive name without opening any path. */
final class HologramArchiveNamedImport {

    private static final String SUFFIX = ".gtpg";
    private static final Pattern LEAF = Pattern.compile("[\\p{L}\\p{N}_-][\\p{L}\\p{N} _.-]{0,94}\\.[gG][tT][pP][gG]");

    private HologramArchiveNamedImport() {}

    static boolean archiveName(String name) {
        return name != null && name.length() >= SUFFIX.length()
            && name.regionMatches(true, name.length() - SUFFIX.length(), SUFFIX, 0, SUFFIX.length());
    }

    static boolean validLeaf(String name) {
        return name != null && name.length() <= 100
            && LEAF.matcher(name)
                .matches();
    }

    static String refusal(String name) {
        return validLeaf(name) ? "当前版本暂不能安全打开本地只读档案；未导入文件" : "请输入不含路径的 .gtpg 文件名";
    }
}

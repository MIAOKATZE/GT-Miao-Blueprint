package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Small demolition actions share the existing transport without opening a cut journal. */
final class HologramDemolitionProtocol {

    private HologramDemolitionProtocol() {}

    static boolean handles(String action) {
        return "demolish".equals(action) || "demolitionCancel".equals(action) || "demolitionStatus".equals(action);
    }

    static String validate(NBTTagCompound request) throws IOException {
        String action = request.getString("action");
        if (!handles(action)) throw new IOException("未知拆除操作");
        for (Object key : request.func_150296_c()) {
            String name = String.valueOf(key);
            if (!"action".equals(name) && !"requestId".equals(name)
                && !"id".equals(name)
                && !("demolish".equals(action) && "areaSelection".equals(name))) throw new IOException("拆除请求包含无效字段");
        }
        if (!request.hasKey("action", 8) || !request.hasKey("requestId", 8) || !request.hasKey("id", 8))
            throw new IOException("拆除请求数据不完整");
        String id = checkedId(request.getString("requestId"));
        if ("demolitionStatus".equals(action)) checkedId(request.getString("id"));
        else if (!request.getString("id")
            .isEmpty()) throw new IOException("拆除请求不使用暂存条目");
        if ("demolish".equals(action) && !request.hasKey("areaSelection", 10)) throw new IOException("请先完成拆除框选");
        return id;
    }

    private static String checkedId(String value) throws IOException {
        try {
            if (UUID.fromString(value)
                .toString()
                .equals(value)) return value;
        } catch (IllegalArgumentException invalid) {}
        throw new IOException("无效拆除请求编号");
    }

    static HologramAreaSelection selection(NBTTagCompound request) throws IOException {
        HologramAreaSelection result = new HologramAreaSelection();
        result.readSnapshot(request.getCompoundTag("areaSelection"));
        result.bounds();
        return result;
    }

    /** Never evict a mutation nonce during a connection, including rejected or cancelled requests. */
    static final class History {

        private final Map<String, Receipt> receipts = new HashMap<>();

        Receipt find(String id) {
            return receipts.get(id);
        }

        Receipt begin(String id, String action) throws IOException {
            if (receipts.containsKey(id)) throw new IOException("该拆除请求已处理");
            if (receipts.size() >= 65536) throw new IOException("拆除请求记录已满，请重新连接后继续");
            Receipt receipt = new Receipt(action);
            receipts.put(id, receipt);
            return receipt;
        }
    }

    static final class Receipt {

        final String action;
        boolean accepted;

        Receipt(String action) {
            this.action = action;
        }
    }
}

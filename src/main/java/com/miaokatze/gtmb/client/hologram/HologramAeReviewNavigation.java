package com.miaokatze.gtmb.client.hologram;

/** One UI intent awaiting an exact server structure-selection ACK. No edit, token or price authority. */
final class HologramAeReviewNavigation {

    enum Result {
        WAITING,
        READY,
        FAILED,
        COMPLETE
    }

    final String session, structureId;
    final long selectionSequence, revision;
    final int x, y, z;
    final boolean replaceCell;
    private boolean complete;
    private String failure = "";

    HologramAeReviewNavigation(String session, long sequence, long revision, String structureId, int x, int y, int z,
        boolean replaceCell) {
        this.session = session;
        this.selectionSequence = sequence;
        this.revision = revision;
        this.structureId = structureId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.replaceCell = replaceCell;
    }

    static boolean needsSelection(String active, String target) {
        return !active.equals(target);
    }

    Result observe(String session, long sequence, long revision, String active, boolean importing, boolean editable) {
        if (complete) return Result.COMPLETE;
        if (!this.session.equals(session)) return fail("会话已变化，已取消定位；请重新查看当前报告。");
        if (importing) return fail("正在导入区域，已取消定位；请等待导入完成。");
        if (sequence < selectionSequence) return Result.WAITING;
        if (sequence != selectionSequence) return fail("结构操作已变化，已取消定位；请重新查看报告。");
        if (!structureId.equals(active)) return fail("相关结构未能切换；请查看服务端说明后重新定位。");
        if (revision <= this.revision || !editable) return fail("当前结构尚不可编辑，已取消定位；请重新查看报告。");
        complete = true;
        return Result.READY;
    }

    void cancel() {
        complete = true;
    }

    String failure() {
        return failure;
    }

    private Result fail(String message) {
        complete = true;
        failure = message;
        return Result.FAILED;
    }
}

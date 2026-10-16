package com.miaokatze.gtmb.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Independent server END tick queue; cleared on server stop to prevent cross-world tasks. */
public final class ServerTaskScheduler {

    /** 统一 logger（O2-B02：去中心化，名称与 LOG 同为 "gtmb"，日志过滤口径不变） */
    private static final Logger LOG = LogManager.getLogger("gtmb");

    /** 网络线程 → 服务器主线程的任务队列 */
    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

    /** 唯一实例（事件总线注册用） */
    public static final ServerTaskScheduler INSTANCE = new ServerTaskScheduler();

    private ServerTaskScheduler() {}

    /**
     * 将任务调度到服务器主线程执行（供网络包处理器调用）
     *
     * @param task 待执行任务（下一 tick 开始时执行；null 直接忽略）
     */
    public static void scheduleServerTask(Runnable task) {
        if (task != null) {
            SERVER_TASKS.offer(task);
        }
    }

    /**
     * 清空待执行任务（停服时由 {@code CommonProxy.serverStopping} 调用，
     * 防止单玩家连续开新世界时残留任务跨世界执行）
     */
    public static void clear() {
        SERVER_TASKS.clear();
    }

    /**
     * 服务器 tick：最多执行 64 项或 5ms 的可协作任务；余项保持 FIFO 到后续 tick。
     * <p>
     * 任务失败逐个捕获（一个任务异常不影响同批其余任务），统一日志不再按
     * 原三份队列的「邮件/抽奖/签到」文案误导排障。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long measured = com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.serverTasksStarted();
        long deadline = System.nanoTime() + 5_000_000L;
        int executed = 0;
        Runnable task;
        while (executed < 64 && (executed == 0 || System.nanoTime() < deadline)
            && (task = SERVER_TASKS.poll()) != null) {
            executed++;
            try {
                task.run();
            } catch (Throwable t) {
                LOG.error("执行服务器主线程任务失败", t);
            }
        }
        if (com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled())
            com.miaokatze.gtmb.hologram.HologramRuntimeMetrics
                .serverTasksFinished(measured, executed, SERVER_TASKS.size());
    }
}

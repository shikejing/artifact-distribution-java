package com.github.shikejing.artifactdistributionjava.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 目标级互斥锁。
 *
 * <p>分发与回滚可以<b>并发</b>发生，但同一目标上的安装/切换操作必须串行：
 * 每个 targetId 一把可重入锁，保证任一时刻该目标安装的都是某个完整版本，
 * 不会出现两个版本交错写入的资源级混合。
 */
public class TargetLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lock(String targetId) {
        return locks.computeIfAbsent(targetId, k -> new ReentrantLock());
    }

    public <T> T withLock(String targetId, Supplier<T> action) {
        lock(targetId).lock();
        try {
            return action.get();
        } finally {
            lock(targetId).unlock();
        }
    }
}

package com.github.shikejing.artifactdistributionjava.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用故障注入。
 *
 * <p>让指定目标的下一次安装抛出应用失败，用于在本地稳定复现
 * “某目标应用失败 → 按策略停止推进并整体回到上一个完整版本”。
 * 触发一次后自动失效，避免污染后续流程。
 */
public class FaultInjection {

    private final Set<String> failOnce = ConcurrentHashMap.newKeySet();

    /** 让该目标的下一次安装失败。 */
    public void failNextInstallForTarget(String targetId) {
        failOnce.add(targetId);
    }

    public boolean shouldFail(String targetId) {
        return failOnce.remove(targetId);
    }

    public void clear() {
        failOnce.clear();
    }
}

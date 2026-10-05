package com.github.shikejing.artifactdistributionjava.model;

/**
 * 批次灰度分发的整体状态。
 */
public enum RolloutStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}

package com.github.shikejing.artifactdistributionjava.model;

/**
 * 单个目标在一次分发中的推进状态。
 */
public enum TargetState {
    PENDING,
    SKIPPED,
    INSTALLING,
    INSTALLED,
    FAILED,
    ROLLED_BACK
}

package com.github.shikejing.artifactdistributionjava.model;

import java.util.List;
import java.util.Map;

/**
 * 分发过程日志/状态机：区分未完成分发与已提交安装。
 * 每个目标状态独立持久化，支持崩溃后恢复。
 */
public record RolloutJournal(String rolloutId,
                             String packageId,
                             List<List<String>> waves,
                             boolean rollbackOnFailure,
                             RolloutStatus status,
                             int waveIndex,
                             String actor,
                             long createdAt,
                             long updatedAt,
                             String failureReason,
                             Map<String, TargetResult> results) {
}

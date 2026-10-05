package com.github.shikejing.artifactdistributionjava.model;

/**
 * 一次分发中某个目标的推进结果（可持久化、可恢复）。
 */
public record TargetResult(String targetId,
                           TargetState state,
                           String reason,
                           String fromPackageId,
                           String toPackageId,
                           int attempts) {
}

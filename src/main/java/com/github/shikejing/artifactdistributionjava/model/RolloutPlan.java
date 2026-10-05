package com.github.shikejing.artifactdistributionjava.model;

import java.util.List;

/**
 * 灰度策略：有序批次；失败时是否整体回滚。
 */
public record RolloutPlan(String packageId,
                          List<RolloutWave> waves,
                          boolean rollbackOnFailure) {
}

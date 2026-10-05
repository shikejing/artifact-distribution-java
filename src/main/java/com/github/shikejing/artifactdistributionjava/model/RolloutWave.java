package com.github.shikejing.artifactdistributionjava.model;

import java.util.List;

/**
 * 灰度批次：按顺序推进的一组目标。
 */
public record RolloutWave(List<String> targetIds) {
}

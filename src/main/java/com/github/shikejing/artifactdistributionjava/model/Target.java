package com.github.shikejing.artifactdistributionjava.model;

import java.util.Map;

/**
 * 已登记的分发目标（本地）。
 */
public record Target(String id,
                     String name,
                     long quotaBytes,
                     Map<String, String> environment,
                     boolean enabled) {
}

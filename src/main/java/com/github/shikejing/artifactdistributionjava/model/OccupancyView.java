package com.github.shikejing.artifactdistributionjava.model;

import java.util.Map;

/**
 * 资源占用视图。
 */
public record OccupancyView(long usedBytes,
                            long quotaBytes,
                            Map<String, Long> bytesByPackage,
                            Map<String, Long> bytesByResource) {
}

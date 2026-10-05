package com.github.shikejing.artifactdistributionjava.model;

import java.util.List;
import java.util.Map;

/**
 * 版本化资源包的元数据（写入包内 METADATA.json）。
 */
public record PackageMeta(String packageId,
                          String name,
                          String version,
                          Map<String, String> requirements,
                          List<ResourceEntry> entries,
                          long contentSize) {

    public record ResourceEntry(String resourceId, String path, long size, String sha256) {
    }
}

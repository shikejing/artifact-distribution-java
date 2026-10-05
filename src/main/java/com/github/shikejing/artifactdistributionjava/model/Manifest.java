package com.github.shikejing.artifactdistributionjava.model;

import java.util.List;
import java.util.Map;

/**
 * 声明式资源清单：资源列表 + 运行环境兼容性要求 + 逻辑版本号。
 */
public record Manifest(String name,
                       String version,
                       List<ResourceDeclaration> resources,
                       Map<String, String> requirements) {
}

package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.Manifest;
import com.github.shikejing.artifactdistributionjava.model.ResourceDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 测试公共工具：创建临时数据目录与声明式资源来源。
 */
final class TestSupport {

    private TestSupport() {
    }

    static Path createSourceTree(Path root, Map<String, String> resources) throws IOException {
        Files.createDirectories(root);
        for (var entry : resources.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    static Manifest manifest(String name, String version,
                             Map<String, String> resources,
                             Map<String, String> requirements) {
        List<ResourceDeclaration> declarations = resources.entrySet().stream()
                .map(e -> new ResourceDeclaration(e.getKey(), e.getValue()))
                .toList();
        return new Manifest(name, version, declarations, requirements);
    }
}

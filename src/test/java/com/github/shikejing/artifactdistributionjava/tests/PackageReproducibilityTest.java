package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.Manifest;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.service.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.service.PackageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 场景：重复打包。同一份清单 + 同一份来源，任何时刻打出的包必须逐字节一致。
 */
class PackageReproducibilityTest {

    @TempDir
    Path temp;

    @Test
    void sameManifestAndSourceProduceByteIdenticalPackage() throws Exception {
        Path dataDir = temp.resolve("data");
        Path sourceA = temp.resolve("sources-a");
        Path sourceB = temp.resolve("sources-b");

        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("config/app.conf", "name = demo\nport = 8080\n");
        resources.put("scripts/run.sh", "#!/bin/sh\necho hello\n");
        resources.put("data/messages.txt", "你好，世界\n");

        TestSupport.createSourceTree(sourceA, resources);
        // 另一个目录，内容完全相同，但文件 mtime 被刻意改得不同。
        TestSupport.createSourceTree(sourceB, resources);
        Files.setLastModifiedTime(sourceB.resolve("config/app.conf"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));

        Map<String, String> idToPath = new LinkedHashMap<>();
        idToPath.put("app.conf", "config/app.conf");
        idToPath.put("run.sh", "scripts/run.sh");
        idToPath.put("messages", "data/messages.txt");
        Manifest manifest = TestSupport.manifest("demo-bundle", "1.2.0", idToPath,
                Map.of("os", "linux", "runtime.min", "17"));
        long expectedContentSize = resources.values().stream()
                .mapToLong(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();

        PackageBuilder builderA = new PackageBuilder(dataDir, sourceA);
        PackageMeta meta1 = builderA.build(manifest, null);
        // 等待足够长时间：任何潜在时间字段都会不同 —— 但结果仍须一致。
        Thread.sleep(20);
        PackageMeta meta2 = builderA.build(manifest, null);

        // 另一个 builder、另一份来源目录（不同 mtime）再打一次。
        PackageBuilder builderB = new PackageBuilder(dataDir.resolve("other"), sourceB);
        PackageMeta meta3 = builderB.build(manifest, null);

        assertEquals(meta1.packageId(), meta2.packageId(), "重复打包 packageId 必须相同");
        assertEquals(meta1.packageId(), meta3.packageId(),
                "不同来源目录的相同内容也必须得到同一个包");

        Path file1 = dataDir.resolve("packages").resolve(meta1.packageId() + ".rpk");
        Path file2 = dataDir.resolve("other/packages").resolve(meta3.packageId() + ".rpk");
        assertArrayEquals(Files.readAllBytes(file1), Files.readAllBytes(file2),
                "两次打包产物必须逐字节一致");

        assertEquals(expectedContentSize, meta1.contentSize());

        // 校验器必须接受该包并给出一致元数据。
        PackageStore store = new PackageStore(dataDir);
        PackageMeta verified = store.verifyAndLoadMeta(meta1.packageId());
        assertNotNull(verified);
        assertEquals("demo-bundle", verified.name());
        assertEquals("1.2.0", verified.version());
        assertEquals(3, verified.entries().size());
        assertEquals(expectedContentSize, verified.contentSize());
    }

    @Test
    void differentContentProducesDifferentPackage() throws Exception {
        Path dataDir = temp.resolve("data");
        Path source = temp.resolve("sources");
        TestSupport.createSourceTree(source, Map.of("a.txt", "hello"));
        Manifest m1 = TestSupport.manifest("b", "1", Map.of("a", "a.txt"), Map.of());

        PackageMeta first = new PackageBuilder(dataDir, source).build(m1, null);

        // 改一个字节
        Files.writeString(source.resolve("a.txt"), "hellp");
        PackageMeta second = new PackageBuilder(dataDir, source).build(m1, null);

        assertEquals(false, first.packageId().equals(second.packageId()),
                "内容变化必须产生不同包");
    }
}

package com.github.shikejing.artifactdistributionjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

/**
 * 应用上下文加载测试（数据目录指向临时目录，避免污染仓库）。
 */
@SpringBootTest(properties = "artifact.dist.data-dir=${java.io.tmpdir}/app-tests-dist-data")
public class ApplicationTests {

    @TempDir
    static Path tempDir;

    @Test
    public void contentLoad() {
    }
}

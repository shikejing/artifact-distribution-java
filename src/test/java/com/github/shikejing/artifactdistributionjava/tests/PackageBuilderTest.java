package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.ResourceDecl;
import com.github.shikejing.artifactdistributionjava.pack.PackageBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复打包：同一清单与同一来源在任何时候打出的包必须逐字节一致。
 */
class PackageBuilderTest {

    private final PackageBuilder builder = new PackageBuilder();

    @Test
    void repeatedBuildsAreByteIdentical() {
        Manifest m = sampleManifest();
        byte[] first = builder.build(m);
        for (int i = 0; i < 10; i++) {
            assertArrayEquals(first, builder.build(m), "build " + i + " differs");
        }
        assertTrue(builder.verify(first, builder.buildInfo(m).getSha256()));
    }

    @Test
    void resourceAndMetadataOrderDoNotAffectBytes() {
        Manifest a = sampleManifest();
        Manifest b = sampleManifest();
        b.setResources(List.of(b.getResources().get(1), b.getResources().get(0)));
        assertArrayEquals(builder.build(a), builder.build(b));
        assertEquals(builder.buildInfo(a).getBundleId(), builder.buildInfo(b).getBundleId());
    }

    @Test
    void differentContentProducesDifferentBundle() {
        Manifest a = sampleManifest();
        Manifest b = sampleManifest();
        b.getResources().get(0).setContent("changed-content");
        assertNotEquals(builder.buildInfo(a).getSha256(), builder.buildInfo(b).getSha256());
    }

    private Manifest sampleManifest() {
        ResourceDecl r1 = new ResourceDecl("app.conf", "file", "key=value");
        r1.setMetadata(new TreeMap<>(Map.of("mode", "0644", "owner", "root")));
        ResourceDecl r2 = new ResourceDecl("banner.txt", "file", "hello");
        Manifest m = new Manifest();
        m.setName("demo");
        m.setVersion("1.0.0");
        m.setResources(List.of(r1, r2));
        return m;
    }
}

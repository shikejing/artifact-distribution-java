package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.PackageVerificationException;
import com.github.shikejing.artifactdistributionjava.model.Manifest;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.ResourceDeclaration;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从声明式资源清单构建内容可校验、逐字节可复现的版本化资源包。
 *
 * <p>容器格式 RPK1（大端字节序）：
 * <pre>
 *   char[4] "RPK1"                魔数
 *   int32   formatVersion         容器格式版本（当前 1）
 *   int32   metaLength            METADATA 字节长度
 *   bytes   METADATA              确定性 JSON（紧凑、字段字典序），不含 packageId
 *   bytes[] 资源内容               严格按元数据 entries 顺序、首尾相接，无额外分隔
 * </pre>
 * 容器内不含任何时间戳、绝对路径、宿主名或随机值；
 * <b>packageId = 整个容器字节的 SHA-256</b>，包以 {@code packages/&lt;id&gt;.rpk} 存放。
 * 因此同一份清单 + 同一份来源，在任何时间、任何机器上打包结果都逐字节一致；
 * 重复打包只会命中同一文件（已存在时还会校验字节一致性），绝不产生不同结果。
 */
public class PackageBuilder {

    public static final String MAGIC = "RPK1";
    public static final int FORMAT_VERSION = 1;

    private final Path dataDir;
    private final Path defaultSourceDir;

    public PackageBuilder(Path dataDir, Path defaultSourceDir) {
        this.dataDir = dataDir;
        this.defaultSourceDir = defaultSourceDir;
        IoSupport.ensureDirectory(dataDir.resolve("packages"));
    }

    /**
     * 打包。sourceDir 为 null 时使用配置的默认来源目录。
     *
     * @return 含 packageId 的包元数据
     */
    public PackageMeta build(Manifest manifest, Path sourceDir) {
        Path sources = sourceDir != null ? sourceDir : defaultSourceDir;

        List<ResourceBlob> blobs = new ArrayList<>();
        List<PackageMeta.ResourceEntry> entries = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        long totalSize = 0L;

        for (ResourceDeclaration declaration : manifest.resources()) {
            if (declaration.id() == null || declaration.id().isBlank()) {
                throw new PackageVerificationException("资源 ID 不能为空");
            }
            if (!seenIds.add(declaration.id())) {
                throw new PackageVerificationException("资源 ID 重复: " + declaration.id());
            }
            if (!seenPaths.add(declaration.path())) {
                throw new PackageVerificationException("资源路径重复: " + declaration.path());
            }
            Path sourceFile = sources.resolve(declaration.path()).normalize();
            if (!Files.isRegularFile(sourceFile)) {
                throw new PackageVerificationException(
                        "资源不存在或不是普通文件: " + declaration.path() + " (解析为 " + sourceFile + ")");
            }
            byte[] data;
            try {
                data = Files.readAllBytes(sourceFile);
            } catch (IOException e) {
                throw new PackageVerificationException("读取资源失败: " + declaration.path());
            }
            blobs.add(new ResourceBlob(data));
            entries.add(new PackageMeta.ResourceEntry(
                    declaration.id(), declaration.path(), data.length, sha256Hex(data)));
            totalSize += data.length;
        }

        // 元数据本身不含 packageId：ID 完全由容器字节决定，避免“ID 参与自身哈希”的循环。
        StoredMeta stored = new StoredMeta(
                manifest.name() == null ? "" : manifest.name(),
                manifest.version() == null ? "" : manifest.version(),
                new TreeMap<>(manifest.requirements() == null ? Map.of() : manifest.requirements()),
                List.copyOf(entries),
                totalSize);

        byte[] payload = assemble(stored, blobs);
        String packageId = sha256Hex(payload);

        Path target = dataDir.resolve("packages").resolve(packageId + ".rpk");
        if (Files.exists(target)) {
            // 幂等保证：ID 相同则字节必然相同；若不同说明仓库被外部污染，拒绝继续。
            String existingHash = IoSupport.sha256Hex(target);
            if (!existingHash.equals(packageId)) {
                throw new PackageVerificationException(
                        "包仓库内容冲突: " + packageId + " 文件已存在但哈希不一致");
            }
        } else {
            IoSupport.writeAtomic(target, payload);
        }

        return new PackageMeta(packageId, stored.name(), stored.version(),
                stored.requirements(), stored.entries(), stored.contentSize());
    }

    private byte[] assemble(StoredMeta meta, List<ResourceBlob> blobs) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(out))) {
            dos.write(MAGIC.getBytes(StandardCharsets.US_ASCII));
            dos.writeInt(FORMAT_VERSION);
            byte[] metaBytes = JsonCodec.toJsonBytes(meta);
            dos.writeInt(metaBytes.length);
            dos.write(metaBytes);
            for (ResourceBlob blob : blobs) {
                dos.write(blob.data());
            }
            dos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("组装资源包失败", e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 容器内元数据布局（不含 packageId）。 */
    record StoredMeta(String name,
                      String version,
                      Map<String, String> requirements,
                      List<PackageMeta.ResourceEntry> entries,
                      long contentSize) {
    }

    private record ResourceBlob(byte[] data) {
    }
}

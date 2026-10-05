package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.NotFoundException;
import com.github.shikejing.artifactdistributionjava.exception.PackageVerificationException;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 包仓库：RPK 包文件的存放、解析与内容校验。
 *
 * <p>校验口径：重算整个文件的 SHA-256，必须等于文件名中的 packageId；
 * 随后逐个资源按声明的 size/sha256 核对，且容器尾部不得有多余字节。
 */
public class PackageStore {

    private final Path packagesDir;

    public PackageStore(Path dataDir) {
        this.packagesDir = dataDir.resolve("packages");
        IoSupport.ensureDirectory(packagesDir);
    }

    public Path packageFile(String packageId) {
        return packagesDir.resolve(packageId + ".rpk");
    }

    public boolean exists(String packageId) {
        return Files.isRegularFile(packageFile(packageId));
    }

    /** 仅读取元数据，不做内容校验（用于列表等只读场景）。 */
    public PackageMeta loadMeta(String packageId) {
        Path file = packageFile(packageId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("资源包不存在: " + packageId);
        }
        ParsedPackage parsed = parse(file);
        return parsed.meta;
    }

    /** 分发/安装前必须经过该校验。 */
    public PackageMeta verifyAndLoadMeta(String packageId) {
        Path file = packageFile(packageId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("资源包不存在: " + packageId);
        }
        String actual = IoSupport.sha256Hex(file);
        if (!actual.equals(packageId)) {
            throw new PackageVerificationException(
                    "资源包内容校验失败: 期望 " + packageId + "，实际 " + actual);
        }
        ParsedPackage parsed = parse(file);
        // parse 内部已逐资源校验；这里再断言元数据 ID 与文件名一致。
        if (!parsed.meta.packageId().equals(packageId)) {
            throw new PackageVerificationException("资源包 ID 与文件名不一致: " + packageId);
        }
        return parsed.meta;
    }

    /** 供安装器使用：校验通过后取出每个资源的字节内容。 */
    public List<byte[]> loadResourceBytes(String packageId) {
        verifyAndLoadMeta(packageId);
        return parse(packageFile(packageId)).blobs;
    }

    public List<PackageMeta> list() {
        List<PackageMeta> metas = new ArrayList<>();
        try (var stream = Files.list(packagesDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".rpk"))
                    .forEach(p -> {
                        try {
                            metas.add(parse(p).meta);
                        } catch (Exception ignored) {
                            // 损坏包不参与列表
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("列出资源包失败", e);
        }
        metas.sort(Comparator.comparing(PackageMeta::packageId));
        return metas;
    }

    private ParsedPackage parse(Path file) {
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PackageVerificationException("读取资源包失败: " + file);
        }
        String fileNameId = file.getFileName().toString().replace(".rpk", "");
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(all))) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4
                    || !new String(magic, StandardCharsets.US_ASCII).equals(PackageBuilder.MAGIC)) {
                throw new PackageVerificationException("非法资源包魔数: " + file);
            }
            int format = in.readInt();
            if (format != PackageBuilder.FORMAT_VERSION) {
                throw new PackageVerificationException("不支持的资源包格式版本: " + format);
            }
            int metaLength = in.readInt();
            if (metaLength <= 0 || metaLength > all.length) {
                throw new PackageVerificationException("非法元数据长度: " + metaLength);
            }
            byte[] metaBytes = in.readNBytes(metaLength);
            if (metaBytes.length != metaLength) {
                throw new PackageVerificationException("元数据不完整");
            }
            PackageBuilder.StoredMeta stored = JsonCodec.fromJson(
                    new String(metaBytes, StandardCharsets.UTF_8),
                    PackageBuilder.StoredMeta.class);

            List<byte[]> blobs = new ArrayList<>();
            long computedTotal = 0L;
            for (PackageMeta.ResourceEntry entry : stored.entries()) {
                long size = entry.size();
                if (size < 0 || size > Integer.MAX_VALUE) {
                    throw new PackageVerificationException("非法资源大小: " + entry.resourceId());
                }
                byte[] data = in.readNBytes((int) size);
                if (data.length != size) {
                    throw new PackageVerificationException(
                            "资源内容不完整（容器被截断）: " + entry.resourceId());
                }
                String actualHash = PackageBuilder.sha256Hex(data);
                if (!actualHash.equals(entry.sha256())) {
                    throw new PackageVerificationException(
                            "资源内容哈希不匹配: " + entry.resourceId());
                }
                blobs.add(data);
                computedTotal += size;
            }
            if (computedTotal != stored.contentSize()) {
                throw new PackageVerificationException("资源总大小与元数据不一致");
            }
            if (in.read() != -1) {
                throw new PackageVerificationException("资源包含有无法解释的尾部字节（容器可能被污染）");
            }

            PackageMeta meta = new PackageMeta(fileNameId, stored.name(), stored.version(),
                    stored.requirements(), stored.entries(), stored.contentSize());
            return new ParsedPackage(meta, blobs);
        } catch (EOFException e) {
            throw new PackageVerificationException("资源包被截断: " + file);
        } catch (IOException e) {
            throw new PackageVerificationException("解析资源包失败: " + file + " - " + e.getMessage());
        }
    }

    private record ParsedPackage(PackageMeta meta, List<byte[]> blobs) {
    }
}

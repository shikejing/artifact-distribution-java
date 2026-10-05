package com.github.shikejing.artifactdistributionjava.pack;

import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.ResourceDecl;
import com.github.shikejing.artifactdistributionjava.domain.RuntimeRequirement;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把声明式清单打成内容可校验的版本化资源包。
 *
 * 确定性保证：
 *  - 资源按名称排序；
 *  - 元数据按 key 排序；
 *  - 所有变长字段使用长度前缀，无时间戳、无随机数、无平台相关换行；
 * 因此同一清单与同一来源在任何时候打出的包逐字节一致。
 */
@Service
public class PackageBuilder {

    public static final String MAGIC = "ADB1";

    /**
     * 构建资源包，返回确定性的字节内容。
     */
    public byte[] build(Manifest manifest) {
        validate(manifest);
        List<ResourceDecl> sorted = sortedResources(manifest);
        String canonical = canonicalManifest(manifest, sorted);
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);

        StringBuilder head = new StringBuilder();
        head.append(MAGIC).append('\n');
        head.append(canonicalBytes.length).append('\n');
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);

        List<byte[]> contents = new ArrayList<>();
        int total = headBytes.length + canonicalBytes.length;
        for (ResourceDecl r : sorted) {
            byte[] c = contentBytes(r);
            contents.add(c);
            total += c.length;
        }

        byte[] out = new byte[total];
        int pos = 0;
        System.arraycopy(headBytes, 0, out, pos, headBytes.length);
        pos += headBytes.length;
        System.arraycopy(canonicalBytes, 0, out, pos, canonicalBytes.length);
        pos += canonicalBytes.length;
        for (byte[] c : contents) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        return out;
    }

    /**
     * 构建并返回包元信息。bundleId 形如 name:version:sha256前12位。
     */
    public BundleInfo buildInfo(Manifest manifest) {
        byte[] bytes = build(manifest);
        String sha = sha256Hex(bytes);
        BundleInfo info = new BundleInfo();
        info.setName(manifest.getName());
        info.setVersion(manifest.getVersion());
        info.setSha256(sha);
        info.setSizeBytes(bytes.length);
        info.setResourceCount(manifest.getResources().size());
        info.setBundleId(manifest.getName() + ":" + manifest.getVersion() + ":" + sha.substring(0, 12));
        info.setManifest(manifest);
        return info;
    }

    /**
     * 校验包字节与声明的 sha256 是否一致。
     */
    public boolean verify(byte[] bundleBytes, String expectedSha256) {
        return sha256Hex(bundleBytes).equals(expectedSha256);
    }

    public String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void validate(Manifest manifest) {
        if (manifest.getName() == null || manifest.getName().isBlank()) {
            throw new IllegalArgumentException("manifest name is required");
        }
        if (manifest.getVersion() == null || manifest.getVersion().isBlank()) {
            throw new IllegalArgumentException("manifest version is required");
        }
        for (ResourceDecl r : manifest.getResources()) {
            if (r.getName() == null || r.getName().isBlank()) {
                throw new IllegalArgumentException("resource name is required");
            }
        }
    }

    private List<ResourceDecl> sortedResources(Manifest manifest) {
        List<ResourceDecl> sorted = new ArrayList<>(manifest.getResources());
        sorted.sort(Comparator.comparing(ResourceDecl::getName));
        return sorted;
    }

    private byte[] contentBytes(ResourceDecl r) {
        String c = r.getContent() == null ? "" : r.getContent();
        return c.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 手工构造的规范化清单文本：所有字段长度前缀，顺序固定。
     */
    private String canonicalManifest(Manifest manifest, List<ResourceDecl> sorted) {
        StringBuilder sb = new StringBuilder();
        field(sb, "name", manifest.getName());
        field(sb, "version", manifest.getVersion());
        RuntimeRequirement req = manifest.getRequirement();
        if (req == null) {
            sb.append("requirement=none\n");
        } else {
            field(sb, "requirement.os", nullToEmpty(req.getOs()));
            field(sb, "requirement.arch", nullToEmpty(req.getArch()));
            field(sb, "requirement.minRuntimeVersion", nullToEmpty(req.getMinRuntimeVersion()));
            Map<String, String> attrs = new TreeMap<>(req.getRequiredAttributes());
            sb.append("requirement.attrs=").append(attrs.size()).append('\n');
            attrs.forEach((k, v) -> {
                field(sb, "requirement.attr.key", k);
                field(sb, "requirement.attr.value", v);
            });
        }
        sb.append("resources=").append(sorted.size()).append('\n');
        for (ResourceDecl r : sorted) {
            field(sb, "resource.name", r.getName());
            field(sb, "resource.type", nullToEmpty(r.getType()));
            Map<String, String> meta = new TreeMap<>(r.getMetadata());
            sb.append("resource.metadata=").append(meta.size()).append('\n');
            meta.forEach((k, v) -> {
                field(sb, "resource.metadata.key", k);
                field(sb, "resource.metadata.value", v);
            });
            byte[] content = contentBytes(r);
            field(sb, "resource.content.sha256", sha256Hex(content));
            sb.append("resource.content.length=").append(content.length).append('\n');
        }
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void field(StringBuilder sb, String key, String value) {
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        sb.append(key).append(':').append(v.length).append(':').append(value).append('\n');
    }
}

package com.github.shikejing.artifactdistributionjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.InstallRecord;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 本地持久化状态存储（JSON 文件 + 原子写），保证重启后可恢复。
 *
 * 目录布局：
 *   data-dir/targets.json          已登记目标
 *   data-dir/bundles/<id>.json     资源包元信息
 *   data-dir/bundles/<id>.bin      资源包字节
 *   data-dir/installs.json         安装日志（含 PENDING/COMMITTED）
 *   data-dir/audit.log             审计日志（追加）
 */
@Component
public class StateStore {

    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public StateStore(@Value("${artifact.dist.data-dir:./dist-data}") String dataDir) {
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dataDir.resolve("bundles"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveTarget(Target target) {
        List<Target> targets = listTargets();
        targets.removeIf(t -> t.getTargetId().equals(target.getTargetId()));
        targets.add(target);
        writeJson(dataDir.resolve("targets.json"), targets);
    }

    public synchronized Optional<Target> findTarget(String targetId) {
        return listTargets().stream().filter(t -> t.getTargetId().equals(targetId)).findFirst();
    }

    public synchronized List<Target> listTargets() {
        return readJson(dataDir.resolve("targets.json"), new TypeReference<>() {
        });
    }

    public synchronized void saveBundle(BundleInfo bundle, byte[] bytes) {
        try {
            writeJson(dataDir.resolve("bundles").resolve(bundle.getBundleId() + ".json"), bundle);
            Path tmp = dataDir.resolve("bundles").resolve(bundle.getBundleId() + ".bin.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dataDir.resolve("bundles").resolve(bundle.getBundleId() + ".bin"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<BundleInfo> findBundle(String bundleId) {
        Path p = dataDir.resolve("bundles").resolve(bundleId + ".json");
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readAllBytes(p), BundleInfo.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<byte[]> loadBundleBytes(String bundleId) {
        Path p = dataDir.resolve("bundles").resolve(bundleId + ".bin");
        try {
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<BundleInfo> listBundles() {
        try {
            List<BundleInfo> out = new ArrayList<>();
            Path dir = dataDir.resolve("bundles");
            if (!Files.exists(dir)) {
                return out;
            }
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                    out.add(mapper.readValue(Files.readAllBytes(p), BundleInfo.class));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveInstallRecord(InstallRecord record) {
        List<InstallRecord> records = listInstallRecords();
        records.removeIf(r -> r.getRecordId().equals(record.getRecordId()));
        records.add(record);
        writeJson(dataDir.resolve("installs.json"), records);
    }

    public synchronized List<InstallRecord> listInstallRecords() {
        return readJson(dataDir.resolve("installs.json"), new TypeReference<>() {
        });
    }

    public synchronized List<InstallRecord> installRecordsOf(String targetId) {
        return listInstallRecords().stream().filter(r -> r.getTargetId().equals(targetId)).toList();
    }

    public synchronized void appendAudit(String actor, String action, String detail, boolean allowed) {
        String line = Instant.now() + "|" + (allowed ? "ALLOW" : "DENY") + "|" + actor + "|" + action + "|"
                + detail.replace("\n", " ") + "\n";
        try {
            Files.writeString(dataDir.resolve("audit.log"), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<String> listAuditLines() {
        Path p = dataDir.resolve("audit.log");
        try {
            return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> List<T> readJson(Path path, TypeReference<List<T>> type) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(Files.readAllBytes(path), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

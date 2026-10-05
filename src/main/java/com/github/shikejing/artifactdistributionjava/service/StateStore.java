package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 目标安装状态存储。
 *
 * <p>布局（每个目标独立目录，互不干扰）：
 * <pre>
 *   targets/&lt;id&gt;/state/current.json   当前已提交的完整安装（原子写）
 *   targets/&lt;id&gt;/state/history.jsonl  历次提交的安装（append-only，用于回滚与核对）
 * </pre>
 *
 * <p>关键语义：{@code current.json} 只在整个包安装（暂存 + 原子切换）完成之后才更新，
 * 因此它的存在即代表“已提交的完整版本”；安装中途崩溃不会动到它。
 */
public class StateStore {

    private final Path targetsRoot;

    public StateStore(Path dataDir) {
        this.targetsRoot = dataDir.resolve("targets");
        IoSupport.ensureDirectory(targetsRoot);
    }

    public Path targetRoot(String targetId) {
        return targetsRoot.resolve(targetId);
    }

    public Optional<InstallRecord> current(String targetId) {
        Path current = stateDir(targetId).resolve("current.json");
        if (!Files.exists(current)) {
            return Optional.empty();
        }
        return Optional.of(JsonCodec.fromJson(IoSupport.readString(current), InstallRecord.class));
    }

    /**
     * 提交一次安装：先 append 历史（崩溃安全：append 单条 <8KB 的 write 为原子追加），
     * 再原子替换 current.json。即使在两步之间崩溃，历史也不会丢，重启可据此核对。
     */
    public synchronized void commit(InstallRecord record) {
        Path dir = stateDir(record.targetId());
        Path history = dir.resolve("history.jsonl");
        // 统一在提交点记录“提交前生效的版本”，作为精确回滚的依据。
        Optional<InstallRecord> prior = current(record.targetId())
                .filter(r -> !r.packageId().equals(record.packageId()));
        String previousId = prior.map(InstallRecord::packageId).orElse(record.fromPackageId());
        InstallRecord toStore = new InstallRecord(record.targetId(), record.packageId(),
                record.name(), record.version(), record.installedAt(),
                record.totalSize(), previousId);
        String line = JsonCodec.toJson(toStore);
        try {
            Files.write(history, line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入安装历史失败: " + record.targetId(), e);
        }
        IoSupport.writeStringAtomic(dir.resolve("current.json"), line);
    }

    /**
     * 清除当前已提交版本（回滚到“分发前为空”）。
     * 先在历史中追加一条空版本墓碑记录，再删除 current.json（删除本身是原子操作）。
     */
    public synchronized void clearCurrent(String targetId) {
        Path dir = stateDir(targetId);
        InstallRecord tombstone = new InstallRecord(
                targetId, "", "", "", System.currentTimeMillis(), 0L);
        Path history = dir.resolve("history.jsonl");
        try {
            Files.write(history, JsonCodec.toJson(tombstone).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            Files.deleteIfExists(dir.resolve("current.json"));
        } catch (IOException e) {
            throw new IllegalStateException("清除安装状态失败: " + targetId, e);
        }
    }

    /**
     * 回滚提交：安装的是一个已校验的完整旧版本，同样入历史。
     *
     * <p>与普通提交不同，这里显式保留版本链：新 current 的 fromPackageId 取
     * “被恢复版本自己的 fromPackageId”，从而支持连续多次回滚（v3→v2→v1）。
     */
    public synchronized void commitRollback(InstallRecord record) {
        Path dir = stateDir(record.targetId());
        String chainPrevious = history(record.targetId()).stream()
                .filter(r -> r.packageId().equals(record.packageId()))
                .reduce((a, b) -> b)
                .map(InstallRecord::fromPackageId)
                .orElse(record.fromPackageId());
        InstallRecord toStore = new InstallRecord(record.targetId(), record.packageId(),
                record.name(), record.version(), record.installedAt(),
                record.totalSize(), chainPrevious);
        Path history = dir.resolve("history.jsonl");
        try {
            Files.write(history, JsonCodec.toJson(toStore).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入回滚历史失败: " + record.targetId(), e);
        }
        IoSupport.writeStringAtomic(dir.resolve("current.json"), JsonCodec.toJson(toStore));
    }

    /** 历史按时间升序（JSONL 即提交顺序）。 */
    public List<InstallRecord> history(String targetId) {
        Path history = stateDir(targetId).resolve("history.jsonl");
        if (!Files.exists(history)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取安装历史失败: " + targetId, e);
        }
        List<InstallRecord> records = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                records.add(JsonCodec.fromJson(line, InstallRecord.class));
            }
        }
        return records;
    }

    /** 所有目标当前的已提交安装。 */
    public List<InstallRecord> allCurrent() {
        List<InstallRecord> result = new ArrayList<>();
        if (!Files.isDirectory(targetsRoot)) {
            return result;
        }
        try (var dirs = Files.list(targetsRoot)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                Path current = d.resolve("state").resolve("current.json");
                if (Files.exists(current)) {
                    result.add(JsonCodec.fromJson(IoSupport.readString(current), InstallRecord.class));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("枚举安装状态失败", e);
        }
        result.sort(Comparator.comparing(InstallRecord::targetId));
        return result;
    }

    private Path stateDir(String targetId) {
        Path dir = targetsRoot.resolve(targetId).resolve("state");
        IoSupport.ensureDirectory(dir);
        return dir;
    }
}

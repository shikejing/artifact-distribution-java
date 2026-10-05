package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.AuditEntry;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 全流程审计日志：{@code audit.jsonl}，append-only。
 *
 * <p>打包、目标登记、分发、回滚、查看以及<b>越权尝试</b>都会记录，
 * 条目含时间戳、操作人、动作、对象、结果（SUCCESS/DENIED/FAILED/SKIPPED）与详情，
 * 因此任何越权操作事后都可追溯。写入使用 synchronized + append，
 * 保证并发下条目不交错。
 */
public class AuditLog {

    private final Path file;

    public AuditLog(Path dataDir) {
        this.file = dataDir.resolve("audit.jsonl");
        IoSupport.ensureDirectory(dataDir);
        if (!Files.exists(file)) {
            try {
                Files.createFile(file);
            } catch (IOException e) {
                throw new IllegalStateException("初始化审计日志失败", e);
            }
        }
    }

    public synchronized void record(String actor, String action, String target,
                                    String result, String detail) {
        AuditEntry entry = new AuditEntry(System.currentTimeMillis(),
                actor == null ? "anonymous" : actor, action,
                target == null ? "" : target,
                result == null ? "" : result,
                detail == null ? "" : detail);
        try {
            Files.write(file, JsonCodec.toJson(entry).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入审计日志失败", e);
        }
    }

    public synchronized List<AuditEntry> readAll() {
        List<AuditEntry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(JsonCodec.fromJson(line, AuditEntry.class));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取审计日志失败", e);
        }
        return entries;
    }
}

package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.ConflictException;
import com.github.shikejing.artifactdistributionjava.exception.NotFoundException;
import com.github.shikejing.artifactdistributionjava.model.Target;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 本地分发目标登记表：{@code targets.json} 原子读写。
 *
 * <p>目标全部在本地登记（{@code data/distribution/targets/&lt;id&gt;/} 作为其安装根），
 * 打包、分发、回滚都不依赖任何远端系统，可在本地完整复现。
 */
public class TargetRegistry {

    private final Path file;

    public TargetRegistry(Path dataDir) {
        this.file = dataDir.resolve("targets.json");
        IoSupport.ensureDirectory(dataDir);
        if (!Files.exists(file)) {
            IoSupport.writeStringAtomic(file, JsonCodec.toJson(List.of()));
        }
    }

    public synchronized Target register(Target target) {
        if (target.id() == null || target.id().isBlank()) {
            throw new IllegalArgumentException("目标 ID 不能为空");
        }
        if (target.quotaBytes() < 0) {
            throw new IllegalArgumentException("配额不能为负数");
        }
        List<Target> targets = readAll();
        for (Target existing : targets) {
            if (existing.id().equals(target.id())) {
                throw new ConflictException("目标已登记: " + target.id());
            }
        }
        Target normalized = new Target(
                target.id(),
                target.name() == null ? target.id() : target.name(),
                target.quotaBytes(),
                target.environment() == null ? Map.of() : Map.copyOf(target.environment()),
                target.enabled());
        targets.add(normalized);
        persist(targets);
        return normalized;
    }

    public synchronized Target get(String id) {
        return readAll().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("目标未登记: " + id));
    }

    public synchronized List<Target> list() {
        List<Target> targets = readAll();
        targets.sort(Comparator.comparing(Target::id));
        return targets;
    }

    private List<Target> readAll() {
        String json = IoSupport.readString(file);
        return new ArrayList<>(JsonCodec.fromJson(json, new TypeReference<List<Target>>() {
        }));
    }

    private void persist(List<Target> targets) {
        targets.sort(Comparator.comparing(Target::id));
        IoSupport.writeStringAtomic(file, JsonCodec.toJson(targets));
    }
}

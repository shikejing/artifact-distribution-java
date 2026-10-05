package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.PackageVerificationException;
import com.github.shikejing.artifactdistributionjava.exception.RolloutException;
import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.Target;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * 目标安装器：把一个已校验的完整包安装到本地目标目录。
 *
 * <p>安装模型（每个版本独立目录 + 指针原子切换）：
 * <pre>
 *   targets/&lt;id&gt;/releases/&lt;packageId&gt;/
 *       META-INF/package.json        该版本元数据（安装时重写每个资源后再次核对）
 *       resources/&lt;path&gt;             资源文件
 *   targets/&lt;id&gt;/current             指向 releases/&lt;packageId&gt; 的指针文件（原子替换）
 * </pre>
 *
 * <p>流程：
 * <ol>
 *     <li>在 releases/ 下用临时目录<b>暂存</b>完整版本，逐资源写入并逐个哈希校验；</li>
 *     <li>暂存任何一步失败 → 删除临时目录，{@code current} 完全不动（仍是上一个完整版本）；</li>
 *     <li>暂存成功后原子 rename 为正式版本目录，再原子切换 current 指针；</li>
 *     <li>指针只在全部资源就位后切换，因此任何时刻 current 指向的都是某个完整版本，
 *     不会出现资源级混合。</li>
 * </ol>
 * 同一目标的安装/回滚由 {@link TargetLocks} 串行化。
 */
public class Installer {

    private final Path targetsRoot;
    private final PackageStore packageStore;
    private final FaultInjection faultInjection;

    public Installer(Path dataDir, PackageStore packageStore, FaultInjection faultInjection) {
        this.targetsRoot = dataDir.resolve("targets");
        this.packageStore = packageStore;
        this.faultInjection = faultInjection;
        IoSupport.ensureDirectory(targetsRoot);
    }

    /**
     * 安装指定包到目标。调用方需已完成配额与兼容性校验。
     *
     * @return 已提交的安装记录（current 指针已切换后才返回）
     */
    public InstallRecord install(Target target, PackageMeta meta) {
        if (faultInjection.shouldFail(target.id())) {
            throw new RolloutException("故障注入：目标 " + target.id() + " 应用失败");
        }

        Path targetDir = targetsRoot.resolve(target.id());
        Path releasesDir = targetDir.resolve("releases");
        IoSupport.ensureDirectory(releasesDir);
        Path releaseDir = releasesDir.resolve(meta.packageId());

        // 幂等：完全相同的版本已安装则直接视为成功（重启恢复时会用到）。
        if (isCompleteRelease(releaseDir, meta)) {
            activatePointer(targetDir, releaseDir);
            return new InstallRecord(target.id(), meta.packageId(), meta.name(),
                    meta.version(), System.currentTimeMillis(), meta.contentSize());
        }

        // 1. 暂存到唯一临时目录（pid + nano 保证并发/残留不冲突）。
        //    先清扫该目标上一次崩溃可能残留的暂存目录（它们绝不可能是生效版本）。
        cleanupStaleStaging(releasesDir);
        Path staging = releasesDir.resolve(
                ".staging-" + meta.packageId() + "-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        IoSupport.ensureDirectory(staging);
        try {
            List<byte[]> blobs = packageStore.loadResourceBytes(meta.packageId());
            if (blobs.size() != meta.entries().size()) {
                throw new PackageVerificationException("资源数量与元数据不一致");
            }
            for (int i = 0; i < meta.entries().size(); i++) {
                PackageMeta.ResourceEntry entry = meta.entries().get(i);
                byte[] data = blobs.get(i);
                if (data.length != entry.size()
                        || !PackageBuilder.sha256Hex(data).equals(entry.sha256())) {
                    throw new PackageVerificationException("暂存校验失败: " + entry.resourceId());
                }
                Path resourceFile = staging.resolve("resources").resolve(entry.path()).normalize();
                if (!resourceFile.startsWith(staging.resolve("resources"))) {
                    throw new PackageVerificationException("资源路径越界: " + entry.path());
                }
                IoSupport.ensureDirectory(resourceFile.getParent());
                Files.write(resourceFile, data);
                // 落盘后再读一遍核对，防止暂存目录写入异常。
                if (!IoSupport.sha256Hex(resourceFile).equals(entry.sha256())) {
                    throw new PackageVerificationException("暂存资源落盘校验失败: " + entry.resourceId());
                }
            }
            IoSupport.writeStringAtomic(staging.resolve("META-INF").resolve("package.json"),
                    JsonCodec.toJson(meta));

            // 2. 暂存目录原子 rename 为正式版本目录。
            try {
                Files.move(staging, releaseDir);
            } catch (IOException alreadyExists) {
                if (!isCompleteRelease(releaseDir, meta)) {
                    throw new RolloutException("版本目录状态异常: " + meta.packageId());
                }
                deleteQuietly(staging);
            }

            // 3. 原子切换 current 指针 —— 提交点。
            activatePointer(targetDir, releaseDir);

            return new InstallRecord(target.id(), meta.packageId(), meta.name(),
                    meta.version(), System.currentTimeMillis(), meta.contentSize());
        } catch (RolloutException | PackageVerificationException e) {
            deleteQuietly(staging);
            throw e;
        } catch (Exception e) {
            deleteQuietly(staging);
            throw new RolloutException("目标 " + target.id() + " 安装失败: " + e.getMessage());
        }
    }

    /**
     * 把 current 指针切到 releases/&lt;packageId&gt;（同目录临时文件 + 原子 move）。
     * 非符号链接实现，保证 Windows/受限文件系统上同样原子。
     */
    public Optional<Path> activate(String targetId, String packageId) {
        Path targetDir = targetsRoot.resolve(targetId);
        Path releaseDir = targetDir.resolve("releases").resolve(packageId);
        if (!Files.isDirectory(releaseDir)) {
            return Optional.empty();
        }
        activatePointer(targetDir, releaseDir);
        return Optional.of(releaseDir);
    }

    /** 当前 current 指针解析出的版本目录（不存在时返回空）。 */
    public Optional<Path> currentReleaseDir(String targetId) {
        Path pointer = targetsRoot.resolve(targetId).resolve("current");
        if (!Files.exists(pointer)) {
            return Optional.empty();
        }
        String content = IoSupport.readString(pointer).trim();
        Path dir = Path.of(content);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    /**
     * 取消生效：原子删除 current 指针（目标回到“无已提交版本”状态）。
     * 用于把“分发前没有任何版本”的目标回滚到分发前。
     */
    public void deactivate(String targetId) {
        Path pointer = targetsRoot.resolve(targetId).resolve("current");
        try {
            Files.deleteIfExists(pointer);
        } catch (IOException e) {
            throw new RolloutException("取消目标生效版本失败: " + targetId);
        }
    }

    public Path currentPath(String targetId) {
        return targetsRoot.resolve(targetId).resolve("current");
    }

    private void activatePointer(Path targetDir, Path releaseDir) {
        Path pointer = targetDir.resolve("current");
        String body = releaseDir.toAbsolutePath().normalize().toString()
                .replace('\\', '/') + "\n";
        try {
            Path tmp = targetDir.resolve(
                    "current.tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
            Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, pointer, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, pointer, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RolloutException("切换 current 指针失败: " + e.getMessage());
        }
    }

    private boolean isCompleteRelease(Path releaseDir, PackageMeta meta) {
        if (!Files.isDirectory(releaseDir)) {
            return false;
        }
        for (PackageMeta.ResourceEntry entry : meta.entries()) {
            Path file = releaseDir.resolve("resources").resolve(entry.path()).normalize();
            if (!Files.isRegularFile(file)
                    || !IoSupport.sha256Hex(file).equals(entry.sha256())) {
                return false;
            }
        }
        return Files.isRegularFile(releaseDir.resolve("META-INF").resolve("package.json"));
    }

    /** 删除某次中断安装残留的所有暂存目录。 */
    private void cleanupStaleStaging(Path releasesDir) {
        if (!Files.isDirectory(releasesDir)) {
            return;
        }
        try (var entries = Files.list(releasesDir)) {
            entries.filter(p -> p.getFileName().toString().startsWith(".staging-"))
                    .forEach(Installer::deleteQuietly);
        } catch (IOException e) {
            // 清扫失败不阻断正常安装；新安装使用全新的暂存目录。
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(ComparatorHolder.REVERSE).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录残留不影响正确性
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static final class ComparatorHolder {
        static final java.util.Comparator<Path> REVERSE =
                java.util.Comparator.reverseOrder();
    }
}

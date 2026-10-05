package com.github.shikejing.artifactdistributionjava.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 崩溃安全的本地文件操作工具：
 * 临时文件 + 原子 move、目录确保存在、SHA-256 计算。
 */
public final class IoSupport {

    private IoSupport() {
    }

    public static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建目录: " + dir, e);
        }
    }

    /**
     * 原子写入：先写同目录临时文件，再 ATOMIC_MOVE 覆盖目标。
     * 进程在任意时刻崩溃，目标文件要么是旧内容，要么是新内容，不会出现半写。
     */
    public static void writeAtomic(Path target, byte[] content) {
        ensureDirectory(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("原子写入失败: " + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件残留无害
            }
        }
    }

    public static void writeStringAtomic(Path target, String content) {
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + file, e);
        }
    }

    public static byte[] sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算 SHA-256 失败: " + file, e);
        }
    }

    public static String sha256Hex(Path file) {
        return HexFormat.of().formatHex(sha256(file));
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

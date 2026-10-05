package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.Role;
import com.github.shikejing.artifactdistributionjava.model.User;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 本地用户目录：{@code users.json}，token -> 用户。
 *
 * <p>首次启动时写入三个默认账号（token 即简单的不记名凭据，仅用于本地复现）：
 * <pre>
 *   admin-token    ADMIN
 *   operator-token OPERATOR
 *   viewer-token   VIEWER
 * </pre>
 */
public class UserDirectory {

    private final Path file;
    private volatile Map<String, User> users = Map.of();

    public UserDirectory(Path dataDir) {
        this.file = dataDir.resolve("users.json");
        IoSupport.ensureDirectory(dataDir);
        if (!Files.exists(file)) {
            Map<String, User> defaults = new TreeMap<>();
            defaults.put("admin-token", new User("admin", Role.ADMIN));
            defaults.put("operator-token", new User("operator", Role.OPERATOR));
            defaults.put("viewer-token", new User("viewer", Role.VIEWER));
            IoSupport.writeStringAtomic(file, JsonCodec.toJson(defaults));
        }
        reload();
    }

    public synchronized void reload() {
        users = JsonCodec.fromJson(IoSupport.readString(file),
                new TypeReference<Map<String, User>>() {
                });
    }

    public Optional<User> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(token.trim()));
    }
}

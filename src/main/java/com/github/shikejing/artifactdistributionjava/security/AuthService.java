package com.github.shikejing.artifactdistributionjava.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于令牌的操作者鉴权。
 *
 * 配置项：
 *   artifact.dist.auth.tokens=token1:actor1,token2:actor2
 *   artifact.dist.auth.perms.<actor>=PACK,DISTRIBUTE,ROLLBACK,VIEW,REGISTER_TARGET
 * 未配置 perms 的 actor 默认只有 VIEW。
 */
@Service
public class AuthService {

    private final Map<String, String> tokenToActor = new LinkedHashMap<>();
    private final Map<String, Set<Permission>> actorPerms = new LinkedHashMap<>();

    public AuthService(
            @Value("${artifact.dist.auth.tokens:admin-token:admin,ops-token:ops,viewer-token:viewer}")
            String tokens,
            @Value("${artifact.dist.auth.perms.admin:PACK,DISTRIBUTE,ROLLBACK,VIEW,REGISTER_TARGET}")
            String adminPerms,
            @Value("${artifact.dist.auth.perms.ops:DISTRIBUTE,ROLLBACK,VIEW}")
            String opsPerms,
            @Value("${artifact.dist.auth.perms.viewer:VIEW}")
            String viewerPerms) {
        for (String pair : tokens.split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length == 2) {
                tokenToActor.put(kv[0].trim(), kv[1].trim());
            }
        }
        actorPerms.put("admin", parsePerms(adminPerms));
        actorPerms.put("ops", parsePerms(opsPerms));
        actorPerms.put("viewer", parsePerms(viewerPerms));
    }

    private Set<Permission> parsePerms(String s) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(Permission.valueOf(p.trim()));
            }
        }
        return out;
    }

    /**
     * 解析令牌对应的操作者，未知令牌返回 null。
     */
    public String resolveActor(String token) {
        if (token == null) {
            return null;
        }
        String t = token.startsWith("Bearer ") ? token.substring(7) : token;
        return tokenToActor.get(t);
    }

    /**
     * 操作者是否拥有指定权限。
     */
    public boolean hasPermission(String actor, Permission permission) {
        return actor != null
                && actorPerms.getOrDefault(actor, Set.of(Permission.VIEW)).contains(permission);
    }

    /**
     * 校验令牌与权限，失败抛出 SecurityException。
     */
    public String require(String token, Permission permission) {
        String actor = resolveActor(token);
        if (actor == null) {
            throw new SecurityException("unauthenticated: unknown token");
        }
        if (!hasPermission(actor, permission)) {
            throw new SecurityException("forbidden: actor=" + actor + " lacks permission " + permission);
        }
        return actor;
    }

    public Set<Permission> permissionsOf(String actor) {
        return actorPerms.getOrDefault(actor, Set.of(Permission.VIEW));
    }
}

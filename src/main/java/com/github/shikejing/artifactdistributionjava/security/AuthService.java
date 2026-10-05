package com.github.shikejing.artifactdistributionjava.security;

import com.github.shikejing.artifactdistributionjava.exception.AuthorizationException;
import com.github.shikejing.artifactdistributionjava.model.Action;
import com.github.shikejing.artifactdistributionjava.model.User;
import com.github.shikejing.artifactdistributionjava.service.AuditLog;
import com.github.shikejing.artifactdistributionjava.service.Authorizer;
import com.github.shikejing.artifactdistributionjava.service.UserDirectory;

import java.util.Optional;

/**
 * 认证 + 授权 + 审计的统一门面。
 *
 * <p>{@link #require} 是所有写操作/查看操作的入口：先验 token，再查角色权限；
 * 认证失败与越权都会写入审计日志（result=DENIED），然后才抛异常，
 * 保证越权行为被拒绝且可追溯。
 */
public class AuthService {

    private final UserDirectory userDirectory;
    private final Authorizer authorizer;
    private final AuditLog auditLog;

    public AuthService(UserDirectory userDirectory, Authorizer authorizer, AuditLog auditLog) {
        this.userDirectory = userDirectory;
        this.authorizer = authorizer;
        this.auditLog = auditLog;
    }

    public Optional<User> authenticate(String token) {
        return userDirectory.authenticate(token);
    }

    /**
     * 要求已认证且具备指定操作权限，否则记录 DENIED 审计并拒绝。
     */
    public User require(String token, Action action) {
        Optional<User> userOpt = authenticate(token);
        if (userOpt.isEmpty()) {
            auditLog.record(token == null ? "anonymous" : "token:" + mask(token),
                    action.name(), "", "DENIED", "认证失败：无效或缺失的 token");
            throw new AuthorizationException("认证失败：无效或缺失的 token");
        }
        User user = userOpt.get();
        if (!authorizer.isAllowed(user, action)) {
            auditLog.record(user.username(), action.name(), "", "DENIED",
                    "越权：角色 " + user.role() + " 无权执行 " + action);
            throw new AuthorizationException(
                    "越权：角色 " + user.role() + " 无权执行 " + action);
        }
        return user;
    }

    /** 成功/失败/跳过操作的审计便捷方法。 */
    public void audit(User user, Action action, String target, String result, String detail) {
        auditLog.record(user.username(), action.name(), target, result, detail);
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 4) {
            return "***";
        }
        return token.substring(0, 2) + "***";
    }
}

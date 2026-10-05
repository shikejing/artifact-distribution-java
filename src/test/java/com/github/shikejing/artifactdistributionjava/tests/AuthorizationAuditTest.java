package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.exception.AuthorizationException;
import com.github.shikejing.artifactdistributionjava.model.Action;
import com.github.shikejing.artifactdistributionjava.model.AuditEntry;
import com.github.shikejing.artifactdistributionjava.model.Role;
import com.github.shikejing.artifactdistributionjava.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：权限校验与审计。
 * 越权操作必须被拒绝，且在审计日志中留下可追溯的 DENIED 记录。
 */
class AuthorizationAuditTest {

    @TempDir
    Path temp;

    @Test
    void roleMatrixIsEnforcedAndDenialsAreAudited() {
        TestEnv env = TestEnv.create(temp);
        var auth = env.authService();

        // 错误 token：认证失败
        assertThrows(AuthorizationException.class,
                () -> auth.require("not-a-token", Action.VIEW));

        // VIEWER 只能查看
        User viewer = auth.require("viewer-token", Action.VIEW);
        assertEquals(Role.VIEWER, viewer.role());
        AuthorizationException denied = assertThrows(AuthorizationException.class,
                () -> auth.require("viewer-token", Action.START_ROLLOUT));
        assertTrue(denied.getMessage().contains("越权"));

        // OPERATOR 可分发、回滚、打包，但不能管理用户
        auth.require("operator-token", Action.START_ROLLOUT);
        auth.require("operator-token", Action.ROLLBACK);
        auth.require("operator-token", Action.BUILD_PACKAGE);
        assertThrows(AuthorizationException.class,
                () -> auth.require("operator-token", Action.MANAGE_USERS));

        // ADMIN 全部允许
        auth.require("admin-token", Action.MANAGE_USERS);
        auth.require("admin-token", Action.START_ROLLOUT);

        // 审计日志可追溯：包含匿名认证失败与 viewer 越权记录。
        var entries = env.auditLog().readAll();
        assertTrue(entries.stream().anyMatch(e ->
                e.result().equals("DENIED") && e.action().equals("VIEW")
                        && e.actor().startsWith("token:")));
        assertTrue(entries.stream().anyMatch(e ->
                e.result().equals("DENIED") && e.action().equals("START_ROLLOUT")
                        && e.actor().equals("viewer")
                        && e.detail().contains("VIEWER")));
        assertTrue(entries.stream().anyMatch(e ->
                e.result().equals("DENIED") && e.action().equals("MANAGE_USERS")
                        && e.actor().equals("operator")));

        // 每条记录结构完整。
        for (AuditEntry e : entries) {
            assertTrue(e.timestamp() > 0);
            assertTrue(!e.actor().isBlank());
        }
    }

    @Test
    void successfulWriteActionsAreAudited() {
        TestEnv env = TestEnv.create(temp);
        User admin = env.authService().require("admin-token", Action.BUILD_PACKAGE);
        env.authService().audit(admin, Action.BUILD_PACKAGE, "pkg-123", "SUCCESS",
                "name=demo");
        assertTrue(env.auditLog().readAll().stream().anyMatch(e ->
                e.action().equals("BUILD_PACKAGE") && e.target().equals("pkg-123")
                        && e.result().equals("SUCCESS")));
    }
}

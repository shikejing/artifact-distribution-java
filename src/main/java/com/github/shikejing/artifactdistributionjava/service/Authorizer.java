package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.Action;
import com.github.shikejing.artifactdistributionjava.model.Role;
import com.github.shikejing.artifactdistributionjava.model.User;

import java.util.Map;
import java.util.Set;

/**
 * 角色权限矩阵：
 * <ul>
 *   <li>VIEWER：仅查看；</li>
 *   <li>OPERATOR：查看 + 打包 + 登记目标 + 分发 + 回滚；</li>
 *   <li>ADMIN：OPERATOR 的全部 + 用户管理。</li>
 * </ul>
 */
public class Authorizer {

    private static final Map<Role, Set<Action>> MATRIX = Map.of(
            Role.VIEWER, Set.of(Action.VIEW),
            Role.OPERATOR, Set.of(Action.VIEW, Action.BUILD_PACKAGE, Action.REGISTER_TARGET,
                    Action.START_ROLLOUT, Action.ROLLBACK),
            Role.ADMIN, Set.of(Action.VIEW, Action.BUILD_PACKAGE, Action.REGISTER_TARGET,
                    Action.START_ROLLOUT, Action.ROLLBACK, Action.MANAGE_USERS));

    public boolean isAllowed(User user, Action action) {
        return user != null && MATRIX.getOrDefault(user.role(), Set.of()).contains(action);
    }
}

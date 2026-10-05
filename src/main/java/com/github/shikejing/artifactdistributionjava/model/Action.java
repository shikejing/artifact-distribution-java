package com.github.shikejing.artifactdistributionjava.model;

/**
 * 受权限校验保护的操作。
 */
public enum Action {
    BUILD_PACKAGE,
    REGISTER_TARGET,
    START_ROLLOUT,
    ROLLBACK,
    VIEW,
    MANAGE_USERS
}

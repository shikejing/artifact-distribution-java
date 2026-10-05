package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.security.AuthService;
import com.github.shikejing.artifactdistributionjava.service.AuditLog;
import com.github.shikejing.artifactdistributionjava.service.Authorizer;
import com.github.shikejing.artifactdistributionjava.service.CompatibilityChecker;
import com.github.shikejing.artifactdistributionjava.service.FaultInjection;
import com.github.shikejing.artifactdistributionjava.service.Installer;
import com.github.shikejing.artifactdistributionjava.service.OccupancyTracker;
import com.github.shikejing.artifactdistributionjava.service.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.service.PackageStore;
import com.github.shikejing.artifactdistributionjava.service.QuotaService;
import com.github.shikejing.artifactdistributionjava.service.RollbackService;
import com.github.shikejing.artifactdistributionjava.service.RolloutService;
import com.github.shikejing.artifactdistributionjava.service.StateStore;
import com.github.shikejing.artifactdistributionjava.service.TargetLocks;
import com.github.shikejing.artifactdistributionjava.service.TargetRegistry;
import com.github.shikejing.artifactdistributionjava.service.UserDirectory;

import java.nio.file.Path;

/**
 * 在临时目录上装配完整服务图（不启动 Spring），用于场景测试。
 */
record TestEnv(Path dataDir,
               PackageBuilder packageBuilder,
               PackageStore packageStore,
               TargetRegistry targetRegistry,
               StateStore stateStore,
               Installer installer,
               QuotaService quotaService,
               CompatibilityChecker compatibilityChecker,
               OccupancyTracker occupancyTracker,
               RolloutService rolloutService,
               RollbackService rollbackService,
               AuditLog auditLog,
               AuthService authService,
               UserDirectory userDirectory,
               FaultInjection faultInjection,
               TargetLocks targetLocks) {

    static TestEnv create(Path base) {
        Path dataDir = base.resolve("data");
        FaultInjection faultInjection = new FaultInjection();
        TargetLocks targetLocks = new TargetLocks();
        AuditLog auditLog = new AuditLog(dataDir);
        UserDirectory userDirectory = new UserDirectory(dataDir);
        Authorizer authorizer = new Authorizer();
        AuthService authService = new AuthService(userDirectory, authorizer, auditLog);
        PackageStore packageStore = new PackageStore(dataDir);
        PackageBuilder packageBuilder = new PackageBuilder(dataDir, base.resolve("sources"));
        TargetRegistry targetRegistry = new TargetRegistry(dataDir);
        StateStore stateStore = new StateStore(dataDir);
        Installer installer = new Installer(dataDir, packageStore, faultInjection);
        QuotaService quotaService = new QuotaService(stateStore, packageStore);
        CompatibilityChecker compatibilityChecker = new CompatibilityChecker();
        OccupancyTracker occupancyTracker = new OccupancyTracker(
                stateStore, packageStore, targetRegistry);
        RolloutService rolloutService = new RolloutService(dataDir, packageStore,
                targetRegistry, quotaService, compatibilityChecker, installer,
                stateStore, targetLocks, auditLog);
        RollbackService rollbackService = new RollbackService(dataDir, stateStore,
                installer, packageStore, targetRegistry, targetLocks, auditLog);
        return new TestEnv(dataDir, packageBuilder, packageStore, targetRegistry, stateStore,
                installer, quotaService, compatibilityChecker, occupancyTracker,
                rolloutService, rollbackService, auditLog, authService, userDirectory,
                faultInjection, targetLocks);
    }
}

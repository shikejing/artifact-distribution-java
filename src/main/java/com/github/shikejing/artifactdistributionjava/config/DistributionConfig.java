package com.github.shikejing.artifactdistributionjava.config;

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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.file.Path;

/**
 * 分发系统 Bean 装配。
 *
 * <p>所有状态都落在 {@code distribution.data-dir} 指向的本地目录，
 * 不依赖外部数据库/远端目标，整个打包、分发、回滚、恢复流程可在本地复现。
 */
@Configuration
@EnableConfigurationProperties(DistributionProperties.class)
public class DistributionConfig {

    @Bean
    public FaultInjection faultInjection() {
        return new FaultInjection();
    }

    @Bean
    public TargetLocks targetLocks() {
        return new TargetLocks();
    }

    @Bean
    public AuditLog auditLog(DistributionProperties props) {
        return new AuditLog(dataDir(props));
    }

    @Bean
    public UserDirectory userDirectory(DistributionProperties props) {
        return new UserDirectory(dataDir(props));
    }

    @Bean
    public Authorizer authorizer() {
        return new Authorizer();
    }

    @Bean
    public AuthService authService(UserDirectory userDirectory, Authorizer authorizer,
                                   AuditLog auditLog) {
        return new AuthService(userDirectory, authorizer, auditLog);
    }

    @Bean
    public PackageStore packageStore(DistributionProperties props) {
        return new PackageStore(dataDir(props));
    }

    @Bean
    public PackageBuilder packageBuilder(DistributionProperties props) {
        return new PackageBuilder(dataDir(props), Path.of(props.getSourceDir()));
    }

    @Bean
    public TargetRegistry targetRegistry(DistributionProperties props) {
        return new TargetRegistry(dataDir(props));
    }

    @Bean
    public StateStore stateStore(DistributionProperties props) {
        return new StateStore(dataDir(props));
    }

    @Bean
    public Installer installer(DistributionProperties props, PackageStore packageStore,
                               FaultInjection faultInjection) {
        return new Installer(dataDir(props), packageStore, faultInjection);
    }

    @Bean
    public QuotaService quotaService(StateStore stateStore, PackageStore packageStore) {
        return new QuotaService(stateStore, packageStore);
    }

    @Bean
    public CompatibilityChecker compatibilityChecker() {
        return new CompatibilityChecker();
    }

    @Bean
    public OccupancyTracker occupancyTracker(StateStore stateStore, PackageStore packageStore,
                                             TargetRegistry targetRegistry) {
        return new OccupancyTracker(stateStore, packageStore, targetRegistry);
    }

    @Bean
    public RolloutService rolloutService(DistributionProperties props,
                                         PackageStore packageStore,
                                         TargetRegistry targetRegistry,
                                         QuotaService quotaService,
                                         CompatibilityChecker compatibilityChecker,
                                         Installer installer,
                                         StateStore stateStore,
                                         TargetLocks targetLocks,
                                         AuditLog auditLog) {
        return new RolloutService(dataDir(props), packageStore, targetRegistry,
                quotaService, compatibilityChecker, installer, stateStore,
                targetLocks, auditLog);
    }

    @Bean
    public RollbackService rollbackService(DistributionProperties props,
                                           StateStore stateStore,
                                           Installer installer,
                                           PackageStore packageStore,
                                           TargetRegistry targetRegistry,
                                           TargetLocks targetLocks,
                                           AuditLog auditLog) {
        return new RollbackService(dataDir(props), stateStore, installer, packageStore,
                targetRegistry, targetLocks, auditLog);
    }

    static Path dataDir(DistributionProperties props) {
        return Path.of(props.getDataDir()).toAbsolutePath().normalize();
    }

    /**
     * 进程启动后自动恢复未完成的分发：
     * 已提交安装不重装，未提交的安全重试，已推进目标不丢失。
     */
    @org.springframework.stereotype.Component
    public static class StartupRecovery {

        private final RolloutService rolloutService;

        public StartupRecovery(RolloutService rolloutService) {
            this.rolloutService = rolloutService;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void recover() {
            rolloutService.recoverPending();
        }
    }
}

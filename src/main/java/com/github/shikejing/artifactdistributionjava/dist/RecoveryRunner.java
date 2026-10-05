package com.github.shikejing.artifactdistributionjava.dist;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 进程启动时执行崩溃恢复：回收未提交的 PENDING 安装。
 */
@Component
public class RecoveryRunner implements ApplicationRunner {

    private final DistributionService distributionService;

    public RecoveryRunner(DistributionService distributionService) {
        this.distributionService = distributionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        distributionService.recoverAfterRestart();
    }
}

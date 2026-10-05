# 资源包分级分发与目标资源占用追踪

一套完全本地化、可复现的**声明式资源 → 版本化资源包 → 灰度分发 → 占用追踪 → 回滚**系统。
基于 Spring Boot 3 / Java 17，不依赖外部数据库或任何远端目标：目标在本地登记，
打包、分发、回滚、崩溃恢复的全过程都能在一台机器上复现与验证。

## 1. 能力总览

| # | 能力 | 关键实现 |
|---|------|----------|
| 1 | 内容可校验、逐字节可复现的版本化包 | 自定义确定性容器 RPK1；`packageId = 整包 SHA-256`；无时间戳/随机值 |
| 2 | 批次灰度分发 + 失败停止 + 整体回滚 | 波次状态机；失败即停止后续目标，已推进目标整体回到上一完整版本 |
| 3 | 分发与回滚并发 | 每目标一把互斥锁；“暂存目录 + 原子切换”，任一时刻只有完整版本 |
| 4 | 资源占用追踪与配额拒绝 | 按目标/包/资源三维汇总；拒绝原因含配额、占用、需求量、超出量 |
| 5 | 运行环境兼容性约束 | 精确匹配 + `.min` 最低版本；不满足的目标**跳过**且原因可查，不影响他人 |
| 6 | 中途失败 / 重启恢复 | journal 区分 `INSTALLING`（未完成）与 `INSTALLED`（已提交）；启动自动恢复 |
| 7 | 权限校验与全流程审计 | ADMIN/OPERATOR/VIEWER 三角色；append-only JSONL 审计，越权留痕 |
| 8 | 自动化测试与文档 | 覆盖重复打包、部分失败、并发回滚、配额超限、重启恢复、越权审计、REST 全链路 |

## 2. 资源包格式与可复现性

容器文件：`packages/<packageId>.rpk`，大端字节序：

```
char[4] "RPK1"      魔数
int32   1           格式版本
int32   metaLength  METADATA 字节长度
bytes   METADATA    确定性 JSON（紧凑、字段按字典序），不含 packageId
bytes[] 资源内容     严格按元数据 entries 顺序首尾相接
```

- 确定性保证：JSON 紧凑输出且字段/Map 键排序；容器**不写入任何时间戳、绝对路径、
  主机名或随机字节**；资源字节直接来自来源文件。
- 因此**同一份清单 + 同一份来源，在任何时间、任何机器上打出的包逐字节一致**；
  重复打包命中同一文件（已存在还会校验字节一致性），绝不产生不同结果。
  来源文件的修改时间（mtime）不参与打包。
- `packageId` 即整个容器字节的 SHA-256，也是文件名。分发/安装前由
  `PackageStore.verifyAndLoadMeta` 重算整包哈希（必须等于 ID）并逐个资源核对
  size/SHA-256，容器多出尾部字节也会被拒绝。

## 3. 分发语义（重要）

### 3.1 目标即完整版本
每个目标的安装布局：

```
targets/<id>/
  releases/<packageId>/          每个版本一个完整目录
    META-INF/package.json
    resources/<资源路径>
  releases/.staging-...          暂存目录（安装失败/崩溃会被清理）
  current                        指针文件：内容为当前生效 releases 目录的绝对路径
  state/current.json             当前已提交安装记录（含 fromPackageId）
  state/history.jsonl            历次已提交安装（append-only）
```

安装流程是**暂存 → 校验 → 原子 rename 为正式版本目录 → 原子切换 current 指针**：

- 暂存期间任何一步失败（含故障注入），删除暂存目录，`current` 完全不动；
- 指针只在所有资源就位后才切换，因此 `current` 在任一时刻都只指向某个
  **已提交的完整版本**，不存在“半个包”或资源级混合；
- 安装对相同版本幂等（重启恢复时不会重复安装）。

### 3.2 波次灰度
`RolloutPlan.waves` 是有序批次，按波次顺序推进、批内逐目标推进。每个目标依次经过：

1. 停用检查（停用 → `SKIPPED`）；
2. 兼容性检查（不满足 → `SKIPPED`，记录原因，**不阻断**其他目标）；
3. 配额检查（不足 → `FAILED`，属于硬失败）；
4. 暂存安装并原子提交（异常 → `FAILED`）。

### 3.3 失败策略
任一目标 `FAILED` 时立即**停止**推进（同波次后续目标与后续波次都不再执行）。
当 `rollbackOnFailure=true`（默认建议）时，把本次真正推进过（`INSTALLED`/`INSTALLING`）
的目标全部恢复到分发前版本：

- 分发前有旧版本：原子切回旧版本目录（本地缺失时用已校验包完整重装同一版本）；
- 分发前为空：删除 `current` 指针并写入墓碑记录。

失败目标自身保持 `FAILED`。分发最终状态为 `ROLLED_BACK`（不回滚则为 `FAILED`）。

### 3.4 兼容性
`requirements` 写在清单里，目标在登记时用 `environment` 描述自身能力：

- `key=value`：目标必须有同名环境属性且取值完全相等；
- `key.min=17`（键以 `.min` 结尾）：目标属性 `key` 按点分版本比较，不得低于该值；
- 缺少属性即不兼容。跳过原因写入 journal 与审计日志。

### 3.5 配额
目标登记时声明 `quotaBytes`。安装前计费口径：

```
安装后占用 = 当前占用 - 同包当前版本大小（升级释放旧版本） + 待安装包大小
```

超过配额拒绝安装，错误消息逐项给出：目标、配额、当前占用、可释放量、待安装需求量、
预计占用、超出量，便于核对。

### 3.6 并发
分发编排有全局调度锁；目标的安装/切指针在**每目标锁**内进行。因此可以：

- 多个分发涉及不同目标时互不阻塞（调度串行化，但每个目标切换互斥）；
- 分发进行中对某目标手动回滚是安全的：该目标只会在完整版本间切换。

### 3.7 崩溃与重启恢复
journal 持久化于 `rollouts/R00000x.json`，对每个目标单独记录状态并原子落盘：

- `PENDING`：尚未处理；`INSTALLING`：**未完成**；`INSTALLED`：**已提交**；
  `SKIPPED`：已跳过；`FAILED`：应用失败；`ROLLED_BACK`：已回滚。
- 波次游标 `waveIndex` 在整波完成后推进，崩溃后不会重跑整个波次。
- 应用启动（`ApplicationReadyEvent`）时自动扫描 `RUNNING/PENDING` 的 journal 并恢复：
  - 目标实际已提交到新版本而日志停在 `INSTALLING`：对账后认领为 `INSTALLED`，**不重装**；
  - 目标未提交：清理残留暂存目录后安全重试（安装幂等）；
  - 已结束（`COMPLETED/FAILED/ROLLED_BACK`）的分发绝不重跑。

### 3.8 手动回滚
`POST /api/targets/{id}/rollback` 将目标恢复到 `current.fromPackageId` 指向的
上一个已提交版本（切指针或完整重装），该操作同样进入历史，支持连续回滚
（v3 → v2 → v1），并在目标锁内完成。

## 4. 权限与审计

| 角色（token） | 权限 |
|---|---|
| ADMIN（`admin-token`） | 查看、打包、登记、分发、回滚、用户管理 |
| OPERATOR（`operator-token`） | 查看、打包、登记、分发、回滚 |
| VIEWER（`viewer-token`） | 仅查看 |

- 所有接口必须携带 `X-Auth-Token`；认证失败 / 越权返回 HTTP 403，
  错误体为 `{"error":"AUTHORIZATION_DENIED","reason":"..."}`。
- 审计日志 `audit.jsonl`（append-only）记录打包、登记、分发逐目标成功/跳过/失败、
  失败后回滚、手动回滚、查看以及**所有越权尝试（DENIED）**，含时间、操作人、
  动作、对象、结果、详情。

## 5. 配置项（`application.properties`）

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `distribution.data-dir` | `DISTRIBUTION_DATA_DIR` | `data/distribution` | 本地数据根：包仓库、目标登记、安装状态、journal、审计、用户 |
| `distribution.source-dir` | `DISTRIBUTION_SOURCE_DIR` | `data/sources` | 声明式资源默认来源根（打包请求未指定 sourceDir 时使用） |
| `server.port` | — | `0`（随机端口） | HTTP 端口 |

首次启动在数据目录写入 `users.json`（三个默认 token，可直接修改文件后重启生效）。

## 6. HTTP API

所有接口需 `X-Auth-Token` 头。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/packages` | BUILD_PACKAGE | 打包；body：`{"manifest":{...},"sourceDir":"可选"}` |
| GET  | `/api/packages` | VIEW | 列出包 |
| GET  | `/api/packages/{id}` | VIEW | 读取并校验包元数据 |
| POST | `/api/targets` | REGISTER_TARGET | 登记本地目标 |
| GET  | `/api/targets` | VIEW | 列出目标 |
| POST | `/api/rollouts` | START_ROLLOUT | 创建并执行灰度分发 |
| POST | `/api/rollouts/{id}/resume` | START_ROLLOUT | 手动续跑未完成分发 |
| GET  | `/api/rollouts[/{id}]` | VIEW | 查询分发/列表（含每目标状态与原因） |
| POST | `/api/targets/{id}/rollback` | ROLLBACK | 手动回滚到上一版本 |
| GET  | `/api/targets/{id}/occupancy` | VIEW | 目标占用（总量/按包/按资源） |
| GET  | `/api/occupancy/packages` | VIEW | 各资源包在所有目标上的占用合计 |
| GET  | `/api/occupancy/resources` | VIEW | 各资源在所有目标上的占用合计 |
| GET  | `/api/audit` | VIEW | 读取审计日志（本次查看本身也会被审计） |

清单 JSON 示例：

```json
{
  "name": "demo",
  "version": "1.2.0",
  "resources": [
    {"id": "app.conf", "path": "conf/app.conf"}
  ],
  "requirements": {"os": "linux", "runtime.min": "17"}
}
```

分发计划示例：

```json
{
  "packageId": "<sha256>",
  "waves": [
    {"targetIds": ["canary"]},
    {"targetIds": ["node-a", "node-b"]}
  ],
  "rollbackOnFailure": true
}
```

## 7. 本地快速复现

```bash
mvn spring-boot:run            # 启动（随机端口，见日志）
TOKEN=operator-token

# 1) 准备来源
mkdir -p /tmp/src/conf && printf 'port=8080\n' > /tmp/src/conf/app.conf

# 2) 打包（同内容多次执行，返回的 packageId 与文件字节永远相同）
curl -s -X POST localhost:$PORT/api/packages -H "X-Auth-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"manifest":{"name":"demo","version":"1.0",
       "resources":[{"id":"app","path":"conf/app.conf"}],
       "requirements":{"os":"linux"}},"sourceDir":"/tmp/src"}'

# 3) 登记目标（可登记一个 quotaBytes 很小的目标来观察配额拒绝）
curl -s -X POST localhost:$PORT/api/targets -H "X-Auth-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"t1","name":"t1","quotaBytes":1000000,
       "environment":{"os":"linux","runtime":"18"},"enabled":true}'

# 4) 灰度分发
curl -s -X POST localhost:$PORT/api/rollouts -H "X-Auth-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"packageId":"<上一步的id>","waves":[{"targetIds":["t1"]}],
       "rollbackOnFailure":true}'

# 5) 占用 / 审计
curl -s localhost:$PORT/api/targets/t1/occupancy -H "X-Auth-Token: viewer-token"
curl -s localhost:$PORT/api/audit -H "X-Auth-Token: admin-token"
```

## 8. 自动化测试

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `PackageReproducibilityTest` | 重复打包逐字节一致（含不同 mtime/不同目录）、内容变化则包变化 |
| `PartialFailureRollbackTest` | 波次中某目标失败 → 停止推进、已推进目标回到上一完整版本、无暂存残留 |
| `ConcurrentRollbackTest` | 分发与回滚多线程并发，观察线程持续校验无资源级混合 |
| `QuotaAndCompatibilityTest` | 配额超限拒绝原因可核对；兼容性跳过不阻断他人；占用视图正确 |
| `RestartRecoveryTest` | 失败后重启、已提交未记账重启、暂存中途崩溃重启三类恢复 |
| `ManualRollbackTest` | v1→v2→v3 后连续手动回滚 v3→v2→v1，版本链完整 |
| `AuthorizationAuditTest` | 角色矩阵、越权拒绝、DENIED 审计留痕 |
| `DistributionApiTest` | REST 全链路（打包/登记/分发/跳过/占用/审计/403） |

## 9. 代码结构

```
model/        领域模型（清单、包元数据、目标、journal、审计、角色…）
exception/    业务异常（配额、兼容性、校验、越权、未找到、冲突…）
util/         JsonCodec（确定性 JSON）、IoSupport（原子写、SHA-256）
service/      PackageBuilder / PackageStore / Installer / RolloutService /
              RollbackService / StateStore / QuotaService / CompatibilityChecker /
              OccupancyTracker / TargetRegistry / TargetLocks / AuditLog /
              UserDirectory / Authorizer / FaultInjection
security/     AuthService（认证+授权+审计门面）
web/          DistributionController、GlobalExceptionHandler
config/       DistributionProperties、DistributionConfig（装配 + 启动恢复）
```

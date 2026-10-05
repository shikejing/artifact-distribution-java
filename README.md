# artifact-distribution-java

资源包的分级分发与目标资源占用追踪。把声明式资源清单打成内容可校验的版本化资源包，
按灰度策略分发到本地登记的目标，追踪每个目标上的资源占用，并支持完整回滚。
整个打包、分发与回滚过程均在本地完成，可复现、可验证。

## 核心概念

- **Manifest（声明式清单）**：描述资源包名称、版本、资源列表与运行环境要求（`RuntimeRequirement`）。
- **Bundle（资源包）**：由 (`PackageBuilder`) 由清单打出的版本化包，`bundleId = name:version:sha256前12位`。
- **Target（目标）**：本地登记的分发目标，携带属性（`os`/`arch`/`runtimeVersion`/自定义）与配额（`Quota`）。
- **InstallRecord（安装日志）**：目标上每次安装的持久化记录，状态机为
  `PENDING -> COMMITTED`，失败为 `FAILED`，被替换为 `SUPERSEDED`。

## 分发语义

### 确定性打包
- 资源按名称排序、元数据按 key 排序、所有变长字段长度前缀，无时间戳与随机数；
- 同一清单与同一来源在任何时候打出的包**逐字节一致**，重复打包不产生不同结果；
- 分发前对包字节做 sha256 完整性校验。

### 分批灰度与失败策略
- `distribute(bundleId, targetIds, batchSize)` 按批次推进；
- 任一目标**应用失败**（含配额拒绝）即停止推进，后续目标不再尝试；
- 本次推进中已安装的目标自动**回滚到上一个完整版本**（推进前没有任何版本的目标被卸载，
  回到推进前状态），不留半应用状态；
- **兼容性不满足**的目标被跳过（`SKIPPED` + 可核对原因），不影响其他目标继续推进。

### 版本原子性与并发
- 同一目标同一产品线（包名）任一时刻只有一个 `COMMITTED` 完整版本；
- 安装先提交新版本再淘汰旧版本，回滚先提交旧版本再淘汰当前版本，
  因此分发与回滚并发进行时目标始终处于某个已提交的完整版本，无资源级混合；
- 显式回滚在没有上一个完整版本时被拒绝（保持当前版本），拒绝写入审计。

### 崩溃恢复
- 安装以 `PENDING -> COMMITTED` 两阶段日志提交；
- 进程重启后（`RecoveryRunner` / `recoverAfterRestart()`）未提交的 `PENDING` 记录被回收为
  `FAILED`，已提交的安装保持不变：不重复安装、不丢失已推进的目标；
- 重复分发当前已安装的版本是幂等的，不产生新的安装记录。

### 占用追踪与配额
- 占用口径：目标上每个 `COMMITTED` 包计 1 个包占用，其每个资源计 1 个资源占用与 content 字节占用；
- 配额项：`maxBundles` / `maxResources` / `maxBytes`；
- 超限拒绝安装并返回可核对原因（`max=/current=/requested=`），同时写 `QUOTA_REJECT` 审计；
- `GET /api/usage/{targetId}` 查询每个包、每个资源在目标上的占用。

### 权限与审计
- 操作权限：`PACK` / `DISTRIBUTE` / `ROLLBACK` / `VIEW` / `REGISTER_TARGET`；
- 所有操作（含越权拒绝、配额拒绝、跳过、恢复）写入持久化审计日志 `audit.log`。

## REST API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/targets` | REGISTER_TARGET | 登记目标（含属性与配额） |
| GET | `/api/targets` | VIEW | 列出目标 |
| POST | `/api/bundles` | PACK | 从清单打包并入库 |
| GET | `/api/bundles` | VIEW | 列出资源包 |
| POST | `/api/distributions` | DISTRIBUTE | 灰度分发 `{bundleId, targetIds, batchSize}` |
| POST | `/api/rollbacks` | ROLLBACK | 回滚 `{targetId, bundleName}` |
| GET | `/api/versions` | VIEW | 各目标当前提交的完整版本 |
| GET | `/api/usage/{targetId}` | VIEW | 目标占用视图 |
| GET | `/api/audit` | VIEW | 审计日志 |

鉴权：`Authorization: <token>`（或 `Bearer <token>`）。越权返回 403 并写审计。

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `artifact.dist.data-dir` | `./dist-data` | 本地状态目录（targets/bundles/installs/audit） |
| `artifact.dist.auth.tokens` | `admin-token:admin,ops-token:ops,viewer-token:viewer` | 令牌到操作者的映射 |
| `artifact.dist.auth.perms.admin` | 全部权限 | admin 权限集 |
| `artifact.dist.auth.perms.ops` | `DISTRIBUTE,ROLLBACK,VIEW` | ops 权限集 |
| `artifact.dist.auth.perms.viewer` | `VIEW` | viewer 权限集 |

目标兼容性属性约定：`os`、`arch`、`runtimeVersion`（点分版本，按段比较），
其余键由资源包 `requiredAttributes` 按需匹配。

## 本地复现与验证

```bash
mvn test          # 运行全部自动化测试
mvn spring-boot:run   # 本地启动（数据落在 ./dist-data）
```

测试覆盖：重复打包逐字节一致（`PackageBuilderTest`）、部分失败停止推进并回滚
（`PartialFailureTest`）、分发与回滚并发原子性（`ConcurrentRollbackTest`）、
配额超限拒绝（`QuotaExceededTest`）、崩溃重启恢复（`RestartRecoveryTest`）、
兼容性跳过与越权拒绝（`CompatibilityAndAuthTest`）。

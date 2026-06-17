# UC 部署(per-user token-exchange + 阿里云 OSS)

一条命令把 `server.properties` 和 `hibernate.properties` **从模板渲染**出来并启动 Unity Catalog server。
真实密钥只放在本地、gitignored 的 `uc.env` 里,**不进仓库**;仓库里只有占位符模板。

**核心理念**:你基本只需设一个 `UC_HOME`(指向**持久化/云盘**路径)。server.properties、hibernate.properties、
relyt_jwks.json、以及 **H2 元数据库**(catalog/schema/table/外部 location/凭证/用户/权限全在里面)都落在
`UC_HOME` 之下 —— 重启/重建容器都不丢。

## 文件
| 文件 | 是否提交 | 说明 |
|---|---|---|
| `server.properties.template` | ✅ | 带 `${VAR}` 占位符的 server.properties 模板 |
| `hibernate.properties.template` | ✅ | H2 元数据库配置模板(H2 路径 = `${UC_DB_FILE}`,在 UC_HOME 下) |
| `uc.env.example` | ✅ | 参数样例(无密钥),拷成 `uc.env` 后填写 |
| `uc.env` | ❌ **gitignored** | **真实密钥/参数**,只在本地,绝不提交 |
| `deploy-uc.sh` | ✅ | 渲染两个配置 + 启动 UC |
| `uc_add_jwks_key.sh` | ✅ | 登记/轮转 Relyt 实例公钥到 JWKS 文件(校验 + 绑定 issuer) |
| `README.md` | ✅ | 本文件 |

## 用法
```bash
cd deploy
cp uc.env.example uc.env      # 首次:拷贝样例
vi uc.env                     # 填 UC_HOME(云盘路径)+ Aliyun 凭证 + issuer/audience
./deploy-uc.sh                # 渲染配置并前台启动 UC
# 后台:  setsid nohup ./deploy-uc.sh >/tmp/uc.log 2>&1 </dev/null & disown
```
`deploy-uc.sh` 会:校验必填项 → 渲染 `server.properties` + `hibernate.properties`(路径默认都在 `UC_HOME` 下)
→ 建好 H2 目录 → `cd UC_HOME && bin/start-uc-server`(缺 jar 时 sbt 自动 build,需 JDK 17+)。

## 需要提供的参数(uc.env)
| 变量 | 必填 | 含义 / 示例 |
|---|---|---|
| `UC_HOME` | 否¹ | **持久化根(云盘路径)**。默认 = `deploy` 上一级。所有状态文件都在它下面 |
| `UC_AUTHORIZATION` | 否 | `enable`(默认,启用鉴权)/ `disable` |
| `UC_ALLOWED_ISSUERS` | **是** | 受信任的 Relyt 实例 id,逗号分隔,如 `1024`。须与 JWKS 里各 key 的 `issuer` 一致 |
| `UC_AUDIENCES` | **是** | subject_token 的 audience,如 `unitycatalog-server`(须与签发端 `unity.audience` 一致) |
| `UC_ACCESS_TOKEN_TTL` | 否 | 换发 token 有效期(ISO-8601,如 `PT1H`)。**留空 = 不过期(opt-in)** |
| `ALIYUN_REGION` | **是** | 如 `cn-hangzhou` |
| `ALIYUN_ACCESS_KEY` | **是** | master RAM 用户 AK(用于 STS AssumeRole) |
| `ALIYUN_SECRET_KEY` | **是** | master RAM 用户 SK |
| `ALIYUN_MASTER_ROLE_ARN` | **是** | master RAM 主体 ARN,如 `acs:ram::<账号ID>:user/<用户名>` |

¹ `UC_HOME` 技术上可不填(用默认仓库根),但**生产务必显式指向云盘**,否则容器重建会丢元数据。

### 路径覆盖(可选,一般不用填)
都默认在 `UC_HOME` 下,只有想把某个文件单独挪走时才设:

| 变量 | 默认 |
|---|---|
| `UC_SERVER_PROPERTIES` | `$UC_HOME/etc/conf/server.properties` |
| `UC_HIBERNATE_PROPERTIES` | `$UC_HOME/etc/conf/hibernate.properties` |
| `UC_EXTERNAL_JWKS_FILE` | `$UC_HOME/etc/conf/relyt_jwks.json` |
| `UC_DB_FILE` | `$UC_HOME/etc/db/h2db`(H2 文件,免 `.mv.db` 后缀) |

## 持久化(重启不丢)
- UC 的**全部元数据**(catalog/schema/table、外部 location、凭证、用户、权限)存在 H2 文件库
  `$UC_DB_FILE`(默认 `$UC_HOME/etc/db/h2db.mv.db`)。
- 把 **`UC_HOME` 指向持久卷/云盘** → 配置 + JWKS + H2 都在其下,重启/重建不丢。
- ⚠️ H2 是**单进程文件库**,不支持 UC 多实例/HA。要 HA / 多实例,改用外部 **PostgreSQL/MySQL**:
  把 `hibernate.properties.template` 的 `connection.url/driver` 换成 PG/MySQL(参考仓库
  `etc/db/postgres-example.yml` / `mysql-example.yml`),并按需把连接串也参数化进 `uc.env`。

## 日志
- UC 服务日志:**`$UC_HOME/etc/logs/server.log`**(滚动归档 `server-<时间>-<序号>.log.gz`);CLI 日志 `etc/logs/cli.log`。
  路径相对工作目录,`cd UC_HOME` 启动后即落在 `UC_HOME` 下 —— 把 `UC_HOME` 指向云盘,日志也一并持久化。
- 配置文件:**`etc/conf/server.log4j2.properties`**(log4j2)。常用滚动/级别参数:

  | 配置项 | 含义 | 默认 |
  |---|---|---|
  | `appender.rollingFile.fileName` | 当前日志文件路径 | `etc/logs/server.log` |
  | `appender.rollingFile.policies.size.size` | 单文件多大触发滚动 | `10MB` |
  | `appender.rollingFile.policies.time.interval` | 按时间滚动间隔 | `1`(天) |
  | `appender.rollingFile.strategy.max` | 保留多少个归档(超出删最旧) | `5` |
  | `rootLogger.level` | 日志级别(trace/debug/info/warn/error) | `info` |

  改完**重启 UC 生效**(log4j2 也支持热加载,但部署里直接重启最简单)。例如要更大留存:把 `size` 调到 `50MB`、`strategy.max` 调到 `20`;排查问题临时开 `rootLogger.level = debug`。
- `var/log/observation.log` 不是业务日志(Armeria 可观测性组件按默认建的空文件),已 gitignore,忽略即可。

## 注册 storage location credential 的约束(重要)
UC 要求 external location 的 URL 层级**互不重叠**(相同 / 父 / 子 都算重叠)。所以**同一层数据,要么统一注册到库(db)级别,要么统一到表(table)级别,不要 db 级和表级混着建**。

- ❌ 反例(会失败):
  - `oss://bucket/db`            → credential A
  - `oss://bucket/db/table1`     → credential B  ← 与上一条父子重叠,**第二条创建直接报错**(无论先建哪条,后建的那条被拒)。
- ✅ 正确(二选一,层级一致):
  - 全库级:`oss://bucket/db` 一条;  ##推荐
  - 全表级:`oss://bucket/db/table1`、`oss://bucket/db/table2` … 各一条(彼此不重叠)。

原因:vend 时是"按数据路径找**覆盖它的那个** external location → 取其凭证里的 role 去 AssumeRole"。若 db 级和表级并存,一个表路径会被两条 location 同时覆盖,UC **无法判别该用哪份凭证(哪个 role)**——所以干脆在创建期就禁止这种重叠,混用会创建失败。

> 一句话:**同一 bucket 下,credential 的粒度要么全到 db、要么全到 table,别混。**

## 安全要点
- **`uc.env` 已被 `.gitignore` 忽略**,真实密钥不会被提交。
- 渲染出的 `server.properties` / `hibernate.properties` 含真实值,是**运行时产物**:仓库里那两份请保持占位符,
  **不要提交渲染后的版本**(推前脱敏,或 `git update-index --skip-worktree etc/conf/server.properties`)。
- UC 当前**明文存凭证**(代码里 `// TODO: encrypt the credential`)→ H2 文件/数据库要做**静态加密 + 严格访问控制**。
- 登记/轮转实例公钥(脚本随本目录提供):
  `./uc_add_jwks_key.sh <public_key.jwk.json> $UC_EXTERNAL_JWKS_FILE <issuer>`
  (第 3 参 issuer 会绑定到该 key,UC 仅给该 issuer 的 token 用它验签)。

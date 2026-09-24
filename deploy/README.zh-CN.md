# UC 部署（per-user token-exchange + 阿里云 OSS）

[English](README.md) | **简体中文**

一条命令把 `server.properties` 和 `hibernate.properties` **从模板渲染**出来并启动 Unity Catalog server。
真实密钥只放在本地、gitignored 的 `uc.env` 里，**不进仓库**；仓库里只有占位符模板。

**核心理念**：你基本只需设一个 `UC_HOME`（指向**持久化/云盘**路径）。`server.properties`、
`hibernate.properties`、`relyt_jwks.json`、以及 **H2 元数据库**（catalog/schema/table、外部 location、
凭证、用户、权限全在里面）都落在 `UC_HOME` 之下 —— 重启/重建容器都不丢。

> Unity Catalog 的其它部署方式见 [`../docker`](../docker) 与 [`../helm`](../helm)。
> 本目录是 Relyt 集成所用的脚本化部署方式。

## 前置条件

| 依赖 | 说明 |
|---|---|
| **JDK 17+** | 缺少 server jar 时 `deploy-uc.sh` 会回落到 sbt 构建 |
| **Python 3** | 用于替换模板里的 `${VAR}` 占位符 |
| `UC_HOME` 的**持久化路径** | 见[持久化](#持久化)；生产必须 |
| **阿里云 RAM 用户 + 角色** | 一个能 `AssumeRole` 的 master RAM 用户（AK/SK），以及每个 external location 对应的角色 |
| **Node.js 18+** 与 `yarn` | 仅在需要 Web UI 时；见[运行 UI](#运行-ui) |

## 快速开始

```bash
cd deploy
cp uc.env.example uc.env      # 首次：拷贝样例
vi uc.env                     # 填 UC_HOME（云盘路径）+ 阿里云凭证 + audience
./deploy-uc.sh                # 渲染配置并前台启动 UC
# 后台： setsid nohup ./deploy-uc.sh >/tmp/uc.log 2>&1 </dev/null & disown
```

`deploy-uc.sh` 会校验必填项，渲染 `server.properties` 与 `hibernate.properties`（路径默认都在
`UC_HOME` 下），建好 H2 目录，然后执行 `cd $UC_HOME && bin/start-uc-server --port $UC_PORT`。

### 验证服务已启动

服务监听 **`UC_PORT`（默认 `8088`）**：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8088/api/2.1/unity-catalog/catalogs
```

在 `UC_AUTHORIZATION=enable` 下，**返回 `401` 才是正确状态**：说明端口可达且鉴权已开启。返回 `200`
说明鉴权**没开**，检查 `UC_AUTHORIZATION`。其它情况（连接被拒、超时）说明服务没起来，见[故障排查](#故障排查)。

> `8088` 与 `bin/start-uc-with-ui.sh`、UI 默认的代理目标、以及 phoenix/presto 的 e2e 用例保持一致。
> 需要换端口就给 `deploy-uc.sh` 传 `--port`，或设 `UC_PORT`；显式传的 `--port` 优先级高于 `UC_PORT`。

## 运行 UI

`deploy-uc.sh` **只启动 server**。Web UI 由 `ui/server.js` 提供：它把 `/api` 代理到 UC server，
并注入 admin token，使浏览器端免登录。

容器镜像的入口脚本 [`bin/start-uc-with-ui.sh`](../bin/start-uc-with-ui.sh) 在 `UC_ENABLE_UI` 为真值
（`1`/`true`/`yes`/`on`）时同时拉起两个进程，否则只跑 server：

```bash
UC_ENABLE_UI=true UC_PORT=8088 UI_PORT=3000 bin/start-uc-with-ui.sh
```

若要在脚本化部署旁边跑 UI，需先构建一次前端产物（`ui/build` 未入库），再自行启动 UI server：

```bash
cd ui && yarn install && yarn build && cd ..
UC_TARGET=http://localhost:8089 \
PORT=3000 HOST=0.0.0.0 node ui/server.js
```

> ⚠️ **授权关闭时服务端不校验身份，UI 同样不会要求登录。** 请把 `3000` 端口限制在可信网络内，
> 绝不要暴露公网。

## 文件

| 文件 | 是否提交 | 说明 |
|---|---|---|
| `server.properties.template` | ✅ | 带 `${VAR}` 占位符的 server.properties 模板 |
| `hibernate.properties.template` | ✅ | H2 元数据库配置模板（H2 路径 = `${UC_DB_FILE}`，在 `UC_HOME` 下） |
| `uc.env.example` | ✅ | 参数样例（无密钥），拷成 `uc.env` 后填写 |
| `uc.env` | ❌ **gitignored** | **真实密钥/参数**，只在本地，绝不提交 |
| `deploy-uc.sh` | ✅ | 渲染两个配置 + 启动 UC |
| `uc_add_jwks_key.sh` | ✅ | 登记/轮转 Relyt 实例公钥到 JWKS 文件（校验 + 绑定 issuer） |
| `README.md` / `README.zh-CN.md` | ✅ | 本文件 |

## 配置项（`uc.env`）

| 变量 | 必填 | 含义 / 示例 |
|---|---|---|
| `UC_HOME` | 否¹ | **持久化根（云盘路径）**。默认 = `deploy/` 上一级。所有状态文件都在它下面 |
| `UC_PORT` | 否 | 服务监听端口，默认 `8088` |
| `UC_AUTHORIZATION` | 否 | `enable`（默认，启用鉴权）/ `disable` |
| `UC_ALLOWED_ISSUERS` | 否 | **可选**的额外受信 issuer（逗号分隔）。受信 issuer 主要由 JWKS 文件派生（各 key 的 `issuer` 成员，热加载），此项与之取**并集**，仅用于信任**不在本地 JWKS** 的 issuer（如 OIDC discovery）。**Relyt 部署留空即可**：完全以 JWKS 为准；接入新 DWSU = 往 JWKS 追加公钥、热加载零重启、无需改此项 |
| `UC_AUDIENCES` | **是** | subject_token 的 audience，如 `unitycatalog-server`（须与签发端 `unity.audience` 一致） |
| `UC_ACCESS_TOKEN_TTL` | 否 | 换发 token 有效期（ISO-8601，如 `PT12H`、`PT30M`；`12h` 会被拒绝）。**留空 = 不过期**。这一个值对所有调用方生效 —— coordinator 和 UI 共用。`uc.env.example` 预置 `PT12H`，部署就用这个值：选它是因为 coordinator 基于 `exp` 的刷新路径当时还没在生产跑过，默认值先留足余量；要调短，等那条路径在你自己的环境里被观察过之后再说 |
| `UC_AUTHORIZATION_URL` | 否 | IdP 的 OAuth 授权端点，如 `https://login.microsoftonline.com/<租户id>/oauth2/v2.0/authorize`。**四项登录变量全空 = 关闭托管登录**，见 [UI 登录](#ui-登录) |
| `UC_TOKEN_URL` | 否 | IdP 的 OAuth 令牌端点，如 `https://login.microsoftonline.com/<租户id>/oauth2/v2.0/token` |
| `UC_CLIENT_ID` | 否 | 为 UI 登录注册的应用的 client id；`deploy-uc.sh` 会自动把它追加进 `UC_AUDIENCES` |
| `UC_CLIENT_SECRET` | 否 | 该应用的 client secret，**只留在服务端**，不下发浏览器。有有效期，到期要轮换 |
| `UC_EXTERNAL_URL` | 否 | 浏览器访问 UC 的基地址（如 `https://uc.example.com`），当前置代理不传 `X-Forwarded-Proto`/`X-Forwarded-Host` 时需要；留空则按请求推导 |
| `UC_ADMIN_PASSWORD` | 否 | 内置 `admin` 在 `<ui>/login/admin` 的登录密码。留空 = 关闭该入口，见 [UI 登录](#ui-登录) |
| `ALIYUN_REGION` | **是** | 如 `cn-hangzhou` |
| `ALIYUN_ACCESS_KEY` | **是** | master RAM 用户 AK（用于 STS `AssumeRole`） |
| `ALIYUN_SECRET_KEY` | **是** | master RAM 用户 SK |
| `ALIYUN_MASTER_ROLE_ARN` | **是** | master RAM 主体，如 `acs:ram::<账号ID>:user/<用户名>`。⚠️ 尽管变量名里是 `ROLE`，这里填的通常是 RAM **用户** ARN —— 即*发起* `AssumeRole` 的身份 —— **不是**被扮演的那个 per-location 角色 |

¹ `UC_HOME` 技术上可不填（回落到安装根），但**生产务必显式指向云盘**，否则容器重建会丢元数据。

### 路径覆盖（可选，一般不用填）

都默认在 `UC_HOME` 下，只有想把某个文件单独挪走时才设：

| 变量 | 默认 |
|---|---|
| `UC_SERVER_PROPERTIES` | `$UC_HOME/etc/conf/server.properties` |
| `UC_HIBERNATE_PROPERTIES` | `$UC_HOME/etc/conf/hibernate.properties` |
| `UC_EXTERNAL_JWKS_FILE` | `$UC_HOME/etc/conf/relyt_jwks.json` |
| `UC_DB_FILE` | `$UC_HOME/etc/db/h2db`（H2 文件，免 `.mv.db` 后缀） |

## 持久化

- `UC_HOME` 既是**安装根**（必须含 `bin/start-uc-server`、构建产物和依赖缓存，脚本会校验），也是**状态根**。
  其中不可再生、必须持久化的只有 `etc/conf`（配置 + JWKS + 签名身份）和 `etc/db`（H2）。
  ⚠️ 容器部署**只挂这两个子目录**，别把整个 `UC_HOME` 挂成卷 —— 卷会遮蔽镜像里的二进制，
  换 tag 升级后跑的仍是卷里的旧版本。
- ⚠️ H2 是**单进程文件库**，不支持 UC 多实例/HA。要 HA / 多实例，改用外部 **PostgreSQL/MySQL**：
  把 `hibernate.properties.template` 的 `connection.url` / `driver` 换成 PG/MySQL（参考仓库
  `etc/db/postgres-example.yml` / `mysql-example.yml`），并按需把连接串也参数化进 `uc.env`。

### `etc/conf` 整个目录必须持久化

⚠️ `etc/conf` 里除了可再生的渲染配置（`server.properties` / `hibernate.properties`），还存着**不可再生的
签名身份**：`private_key.der` / `public_key.der` / `key_id.txt`。UC **每次启动都会检查这三个文件：
三个都在才复用，缺任意一个就当场重新生成密钥对和新的 `key_id`**（`certs.json` / `token.txt` 随之重写）
—— 也就是说，只要这个目录没落在持久存储上，一次重启/重建就会换掉一套密钥。密钥一换，**此前签发的所有
access token 和 admin service token 立即验签失败**（下游 401）。

### `etc/db` 整个目录必须持久化

⚠️ UC 的**全部元数据**（catalog/schema/table、外部 location、凭证、用户、权限）只存在 H2 文件库
`$UC_DB_FILE`（默认 `$UC_HOME/etc/db/h2db.mv.db`）里，目录一丢就等于回到空实例，这些全得重建。

## 日志

- UC 服务日志：**`$UC_HOME/etc/logs/server.log`**（滚动归档 `server-<时间>-<序号>.log.gz`）；
  CLI 日志 `etc/logs/cli.log`。路径相对工作目录，脚本从 `UC_HOME` 启动，所以把 `UC_HOME` 指向云盘，
  日志也一并持久化。
- 配置文件：**`etc/conf/server.log4j2.properties`**（log4j2）。常用滚动/级别参数：

  | 配置项 | 含义 | 默认 |
  |---|---|---|
  | `appender.rollingFile.fileName` | 当前日志文件路径 | `etc/logs/server.log` |
  | `appender.rollingFile.policies.size.size` | 单文件多大触发滚动 | `10MB` |
  | `appender.rollingFile.policies.time.interval` | 按时间滚动间隔 | `1`（天） |
  | `appender.rollingFile.strategy.max` | 保留多少个归档（超出删最旧） | `5` |
  | `rootLogger.level` | 日志级别（trace/debug/info/warn/error） | `info` |

  改完**重启 UC 生效**（log4j2 也支持热加载，但部署里直接重启最简单）。例如要更大留存：把 `size`
  调到 `50MB`、`strategy.max` 调到 `20`；排查问题临时开 `rootLogger.level = debug`。
- `var/log/observation.log` 不是业务日志（Armeria 可观测性组件按默认建的空文件），已 gitignore，忽略即可。

## 注册 storage location credential 的约束（重要）

UC 要求 external location 的 URL 层级**互不重叠**（相同 / 父 / 子 都算重叠）。所以**同一层数据，要么统一
注册到库（db）级别，要么统一到表（table）级别，不要 db 级和表级混着建**。

- ❌ 反例（会失败）：
  - `oss://bucket/db`            → credential A
  - `oss://bucket/db/table1`     → credential B  ← 与上一条父子重叠，**第二条创建直接报错**（无论先建哪条，后建的那条被拒）。
- ✅ 正确（二选一，层级一致）：
  - 全库级：`oss://bucket/db` 一条；  ## 推荐
  - 全表级：`oss://bucket/db/table1`、`oss://bucket/db/table2` … 各一条（彼此不重叠）。

原因：vend 时是"按数据路径找**覆盖它的那个** external location → 取其凭证里的 role 去 AssumeRole"。
若 db 级和表级并存，一个表路径会被两条 location 同时覆盖，UC **无法判别该用哪份凭证（哪个 role）**
—— 所以干脆在创建期就禁止这种重叠，混用会创建失败。

> 一句话：**同一 bucket 下，credential 的粒度要么全到 db、要么全到 table，别混。**

## 接入新 DWSU（在线热生效，无需重启）

每个 DWSU（Relyt 实例）用自己的实例私钥给 JWT 签名，UC 用登记的公钥验签。接入一个新 DWSU 只需把它的公钥
**追加进 JWKS 文件**（`UC_EXTERNAL_JWKS_FILE`，默认 `$UC_HOME/etc/conf/relyt_jwks.json`）：UC 每次验签
都现读该文件，受信 issuer 也从文件内各 key 的 `issuer` 成员实时派生 —— **改完即生效，不用重启 UC，
不用改 `uc.env` / `server.properties`**。这一点与部署形态无关：裸进程、docker、K8s 都一样，K8s 的
ConfigMap 只是"改这个文件"的一种投递方式。

三个字段必须对齐，错一个 UC 返 401：JWK 的 `issuer` == 实例 id == JWT 的 `iss`；JWK 的 `kid` ==
实例签名密钥的 key id；`UC_AUDIENCES` == JWT 的 `aud`。

1. **导出公钥**（在新 DWSU 侧）：由实例签名密钥导出**公钥 JWK 条目**（单个对象，含 `kty`/`crv`/`kid`/`x`/`y`
   与 `issuer`），保存为 `<实例id>.jwk.json`。Relyt 运维在实例 master 上执行
   `decrypt_uc_instance_key.sh <实例id>`，取输出里的 `jwks-entry`。**私钥留在实例侧，不要拷出。**
2. **登记**（在 UC 所在机器上，脚本按 `kid` 去重、可重复执行，写入为原子替换，在线请求不会读到半截文件）：
   ```bash
   cd deploy
   ./uc_add_jwks_key.sh <实例id>.jwk.json "$UC_EXTERNAL_JWKS_FILE" <实例id>
   ```
   传入完整的 `{"keys":[...]}` 会被脚本拒绝，只传那一个 JWK 对象。
3. **验证**（在该 DWSU 上）：
   ```sql
   SELECT * FROM relyt_get_external_schema_tables('<catalog>.<schema>');
   ```
   能列出表即签名、验签、UC 授权全通。UC 返 401 / `Invalid issuer` / `Token verification failed` 时，
   核对 JWKS 条目的 `issuer` / `kid` 与该实例签名密钥的 issuer / key id 是否一致。

**下线 DWSU**：从 JWKS 文件 `keys` 数组里删掉该 `kid` 的条目，同样即时生效。

注意：

- **不要**把新实例 id 加进 `UC_ALLOWED_ISSUERS`。该项是启动快照，改了要重启；且受信 issuer 已由 JWKS
  派生，Relyt 场景不需要它。
- 容器化部署时 JWKS 文件要按**目录**挂载，不要以单文件方式挂（docker 单文件 bind-mount、K8s `subPath`）：
  原子替换会换掉文件 inode，单文件挂载在容器内看不到更新。

## 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| `ERROR: required variable not set: X` | `uc.env` 里缺 `X`，见[配置项](#配置项ucenv) |
| `ERROR: env file not found` | 需在 `deploy/` 下执行，或先 `cp uc.env.example uc.env` |
| `ERROR: $UC_HOME/bin/start-uc-server not found` | `UC_HOME` 指到了非 UC 安装根的位置 —— 它既要放状态，也要含二进制 |
| 起不来、sbt 构建报错 | JDK 低于 17，见[前置条件](#前置条件) |
| curl 端口连接被拒 / 超时 | 服务没起来 —— 看控制台输出与 `$UC_HOME/etc/logs/server.log` |
| curl 返回 `200` 而不是 `401` | 鉴权没开，设 `UC_AUTHORIZATION=enable` 后重启 |
| UC 返 `401` / `Invalid issuer` / `Token verification failed` | JWKS 条目的 `issuer`/`kid` 与实例不一致，或 `UC_AUDIENCES` 与 token 的 `aud` 不同，见[接入新 DWSU](#接入新-dwsu在线热生效无需重启) |
| 本来正常，重启后所有 token 全部失效 | `etc/conf` 没做持久化，签名密钥被重新生成，见 [`etc/conf` 必须持久化](#etcconf-整个目录必须持久化) |
| 创建 external location 报重叠错误 | db 级与表级 location 重叠，见[注册 storage location credential 的约束](#注册-storage-location-credential-的约束重要) |
| 读表时 OSS 拒绝访问 | 凭证里的 role 缺 bucket 上的 `oss:ListObjects`（配 `oss:Prefix` 条件），只给 `oss:GetObject` 不够 |
| 换 tag 升级后跑的仍是旧版本 | 整个 `UC_HOME` 被挂成了卷，遮蔽了镜像里的二进制；只挂 `etc/conf` 和 `etc/db` |


## UI 登录

UI 必须登录。**每个入口都有自己的地址，应用地址不是入口**：没有会话时应用地址会跳到 `<ui>/login`。
有哪些入口由服务端配置在运行时决定 —— 页面加载时读 `GET /auth/providers`，所以一份 UI 构建适用于所有
部署，改配置不用重新打镜像。

| 地址 | 给谁用 | 由什么开启 |
|---|---|---|
| **`<ui>/login`** —— 默认页，提供 **Sign in with Microsoft** | 员工，用自己的 M365 账号 | `UC_AUTHORIZATION_URL` 等四项 |
| **`<ui>/login/admin`** —— 密码表单，刻意不从登录页链接 | 运维方 | `UC_ADMIN_PASSWORD` |
| **`<ui>/login/token`** —— 粘贴本服务器签发的访问令牌 | 运维方 | 始终可用 |

三者最终都落到同一个 `UC_TOKEN` cookie，下游不区分来源。登录后 catalog / schema / 表列表按该用户被授予
的权限过滤（过滤发生在服务端），管理员作为 metastore owner 看得到全部。

`deploy-uc.sh` 会在"授权开着但一个登录入口都没配"时告警，因为那样没人能登录。

**用访问令牌登录**：在 `<ui>/login/token` 粘贴一个**用户**的访问令牌，或直接打开
`<ui>/login/token?token=<令牌>`，页面用完会立即把令牌从地址栏抹掉。服务端按校验普通请求的同一套规则
校验它，然后**原样**作为会话 cookie 返回，所以会话不会比令牌活得久。

> ⚠️ `etc/conf/token.txt` 里那个令牌**不能用于登录**。它的 `sub` 是 `server`（服务令牌，供服务端自用），
> 而 UC 里从来不存在 email 为 `server` 的用户——`/auth/token/login` 会以
> `no enabled user named 'server'` 拒绝。这个入口登录成的是**令牌 `sub` 所指的那个用户**，权限也就是
> 那个用户的权限；要拿一个可登录的令牌，走 `/auth/tokens` 交换，或用 `<ui>/login/admin` 登录后从
> `UC_TOKEN` cookie 里取。

早期版本是由 UI server 把这个令牌注入到每个接口请求里，等于让 UI 的地址本身成为一条无需登录的入口。
现在 UI server 不再携带任何凭据。

### 接 Microsoft Entra ID（M365）

#### 1. Entra 侧

为 UI 注册一个应用：

| 项 | |
|---|---|
| 应用注册 → `租户 id`、`client id`、`client secret`。前两个在应用概览页上下并排、都是 GUID，但**是两样东西**：`目录(租户) ID` 标识这个组织，填进两个 URL；`应用程序(客户端) ID` 标识这个应用，填进 `UC_CLIENT_ID` | ✅ |
| **`email` 可选声明** —— 不配的话账号匹配不到 UC 用户 | ✅ |
| 回调地址：`<浏览器访问 UC 的地址>/api/1.0/unity-control/auth/callback` | ✅ |

谁能做：租户的普通成员在"允许用户注册应用"和用户同意都开着时可以自己注册并建密钥，但多数企业会关掉
这两个开关。UC 申请的 `openid`、`profile`、`email` 三个权限本身不需要管理员同意；若应用被设为"需要分配
用户"，则要管理员分配。除非客户另有说明，直接找租户管理员，十分钟的事，顺带把应用归属和密钥轮换责任
定下来。

租户 id 和两个端点地址是公开信息，知道客户的邮箱域名就能查，不用登录：

```bash
curl -s https://login.microsoftonline.com/<客户域名>/v2.0/.well-known/openid-configuration
```

返回里的 `issuer` 带着租户 GUID，`authorization_endpoint` 和 `token_endpoint` 就是下面要填的两项。

#### 2. UC 侧（`uc.env`）

```bash
UC_AUTHORIZATION_URL=https://login.microsoftonline.com/<租户id>/oauth2/v2.0/authorize
UC_TOKEN_URL=https://login.microsoftonline.com/<租户id>/oauth2/v2.0/token
UC_CLIENT_ID=<ui-client-id>
UC_CLIENT_SECRET=<ui-client-secret>
UC_ACCESS_TOKEN_TTL=PT12H
```

`deploy-uc.sh` 会从这四项派生出令牌校验需要的另外两项，逐条打印，且只做**追加**，不覆盖已有值：

| 派生项 | 来源 | 条件 |
|---|---|---|
| `UC_AUDIENCES` += client id | `UC_CLIENT_ID` | 总是。id_token 的 `aud` 就是应用本身 |
| `UC_ALLOWED_ISSUERS` += `https://login.microsoftonline.com/<租户id>/v2.0` | `UC_AUTHORIZATION_URL` | 仅当授权地址是 v2.0 端点且租户写成 GUID |

v1.0 端点的签发方是 `https://sts.windows.net/<租户id>/`，按域名写的租户不体现 GUID，`common` /
`organizations` 按实际租户签发。这三类脚本不猜，只告警并指向 `.well-known/openid-configuration` 的
`issuer` 字段，那就是要填进 `UC_ALLOWED_ISSUERS` 的值。

Relyt 实例那条本地 JWKS 链路不受影响：issuer 在 JWKS 文件里有 key 的走文件验签，其余受信 issuer 走
OIDC discovery。

UI 代理会转发 `X-Forwarded-Proto` / `X-Forwarded-Host`，UC 据此拼出浏览器实际使用的回调地址；若前面还有
一层代理把这两个头去掉了，就填 `UC_EXTERNAL_URL`。

#### 3. 建用户

**没有自动建号**：每个要登录的员工必须先在 UC 里存在（`POST /scim2/Users`），邮箱要与令牌里的 `email`
声明**逐字节一致**。UC 依次用 `email`、`preferred_username` / `upn`（Entra 的登录名）、`sub` 去匹配，全部
按邮箱查。授权照常授给这个 UC 用户。**删号重建会丢掉该用户的全部授权**，因为授权按用户 UUID 存。

#### 4. 客户端密钥会过期

Entra 的 client secret 带有效期，6、12 或 24 个月，取决于创建时的选择，临近到期它不会主动提醒。到期前
先把影响面看清楚，免得当天过度紧张。

**哪些会停，哪些不会。** 这个密钥在整个系统里只用于一次请求：`/auth/callback` 里服务端拿授权码换
id_token。其余全都不经过它：

| 链路 | 密钥过期后 |
|---|---|
| 客户程序经 Relyt 读写 | 不受影响 —— coordinator 用本地 JWKS 文件里的实例密钥认证，不走 Entra |
| Spark 持 UC token 读元数据 | 不受影响，同上 |
| 已登录 UI 的人 | 在令牌有效期内照常使用（`UC_ACCESS_TOKEN_TTL`，默认 `PT12H`）：`UC_TOKEN` 由 UC 自己签发、自己无状态验签 |
| 用 M365 账号**重新登录** | **失败** |
| `<ui>/login/admin` 与 `<ui>/login/token` | 不受影响 —— 两者都不碰 Entra，运维方不会被锁在门外 |

**现象与排查陷阱。** 失败发生在 Entra 已经认证成功**之后**：用户在微软那边登录明明成功了，跳回来却报
错。浏览器上看到的是

```
Identity provider rejected the authorization code: 401 Unauthorized (invalid_client, AADSTS7000222)
```

括号里那个码是区分三种同样报 401 的原因的唯一依据：密钥过期是 `AADSTS7000222`，回调地址与注册不符是
`AADSTS50011`，授权码被重复使用是 `AADSTS54005`。IdP 返回的完整描述（含 correlation id）记在服务端日志
的 `WARN` 级别 —— **先看日志再改配置**。

**轮换：可以做到零登录中断。** 一个应用允许同时挂两条有效密钥，所以顺序比时机更重要：

1. 在 Entra 的「证书和机密」里，**趁旧密钥尚未过期**新建一条，此时新旧并存且都有效
2. 把新值填进 `UC_CLIENT_SECRET`，重新渲染配置（`deploy-uc.sh`）
3. **重启 UC server。这一步省不掉**：`ServerProperties` 只在启动时读一次文件，没有热加载，改完渲染后的 `server.properties` 不重启不生效
4. 用 M365 账号登录一次，确认新密钥可用
5. 回 Entra 删除旧的那条

重启对已登录的人无感，他们的 cookie 是无状态校验的，重启后继续有效；受影响的只有重启这段时间内无法
完成新登录。

**运维建议。** 创建时直接选 Entra 允许的最长有效期（24 个月），并把到期日记进运维台账或日历，因为没有
任何东西会提醒你。想要更稳的话，用 Graph API 定期查应用的 `passwordCredentials[].endDateTime`，提前一个
月告警。

还要说明当前实现的边界：UC 只支持 client secret，没有证书凭据或联合凭据的配置项，换取令牌时固定拼
`client_secret` 表单字段。如果安全规范不允许落地长期静态密钥，那是一处需要改代码的增强点，建议单独开
issue 跟踪，不要在配置项里找。

#### 5. 验证权限过滤

用两个授权不同的 M365 账号分别登录，确认看到的 catalog / schema / 表列表不同。过滤在服务端完成，UI 侧
没有任何配置参与。

### 管理员登录

配 `UC_ADMIN_PASSWORD`，打开 `<ui>/login/admin`，用户名是内置的 `admin`。密码与其它密钥一同存在渲染后的
`server.properties` 里，比对是常量时间的，失败会延迟一秒 —— 这能减缓但挡不住暴力猜测，请用足够长的随机
值，并把 UI 放在既有的网络管控之后。

#### `admin` 是应急通道，不是日常运维账号

管理员权限在 UC 里不是一种账号类型，而是**一个事实**：某个用户在授权表里持有 metastore 的 `OWNER`。
`GET /auth/capabilities` 返回的 `metastore_admin` 就是在报告这件事。原则上它可以授予和收回，也可以同时
有多个持有者。

在 UI 的 Users 页面，管理员对任意**在用**账号可以「Make administrator」/「Withdraw administrator」，
列表里管理员带 `Admin` 标签。对应接口：

```bash
# 提为管理员
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  "$UC_URL/api/1.0/unity-control/metastore/admins/ops@corp.com"

# 收回
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "$UC_URL/api/1.0/unity-control/metastore/admins/ops@corp.com"

# 看当前有哪些管理员（其它接口都看不到：OWNER 会被 /permissions 过滤掉）
curl -H "Authorization: Bearer $TOKEN" \
  "$UC_URL/api/1.0/unity-control/metastore/admins"
```

**任何管理员都能提拔和收回，不限于内置 `admin`。** metastore OWNER 已经是权限格的顶点——管理员提拔别人
并没有把谁提到自己之上；反过来，只让 `admin` 有这个权力，等于把应急口令账号拉进日常运维，正是下面要
避免的习惯。

服务端守着两条线：不能把停用账号提为管理员（它签不进来，只会让"谁在管"变得模糊），以及**收回后必须
至少还剩一个在用的管理员**。后一条不是形式主义：`UnityAccessUtil.initializeAdmin` 只在 `admin` 用户
**完全不存在**时才补授 OWNER，所以一个还在、但丢了 OWNER 的 `admin` 行，重启也修不回来。

> 历史说明：`OWNER` 不在公开的 `Privilege` 枚举里，所以它不能走 `/permissions` 通道，上面这组接口是
> 专门为此开的。之所以不把 `OWNER` 塞进那个枚举：该枚举被所有 securable 共用，放进去等于同时给
> catalog/schema/table 开了转移属主的口子，那是另一个需要单独设计的特性。

**日常运维请用被授予 metastore OWNER 的真人 M365 账号，不要共用 `admin` 口令。** 三个原因：

1. **它绕过 Entra 的全部控制。** `<ui>/login/admin` 不经过 Entra，因此 MFA、条件访问、以及"员工离职即禁用"
   都对它无效。共用口令的后果是：某人离职后在 Entra 里被禁用，他照样能用记在本地的口令登进 UC。
2. **它让操作无法归因。** 服务端日志和后续的审计记录里，操作者永远是 `admin`，分不出是谁做的。对于
   删用户、改所有权、发放云凭证这类动作，这等于没有审计。
3. **它是唯一的救命通道，用得越多越容易废掉。** `admin` 这个账号本身可以被停用或降权（见
   `UnityAccessUtil.initializeAdmin` 的注释），而口令登录**不检查该账号是否还存在、是否还是 ENABLED**
   —— 账号被停用或删除后，`<ui>/login/admin` 仍然返回登录成功并种下 cookie，但随后每个接口调用都会被
   403 拒绝，表现为"登录成功却什么都做不了"的死局。把它留着只做应急，就不会走到这一步。

**生产建议**：把 `UC_ADMIN_PASSWORD` 留空。留空时 `GET /auth/providers` 不会报告 `admin_login`，
`<ui>/login/admin` 这个入口在登录页和服务端都不存在。真需要应急时（Entra 故障、所有管理员都进不来）
再填上并重启，用完清空。

> `<ui>/login/token` 是同性质的应急入口，同样不要作为日常方式；它使用的 `etc/conf/token.txt` 令牌永不过期，
> 见[安全要点](#安全要点)。

### 程序怎么访问 UC

客户程序不以 M365 身份访问 UC。它们经 Relyt：coordinator 用登记在本地 JWKS 文件里的实例密钥向 UC 认证
（见[接入新 DWSU](#接入新-dwsu在线热生效无需重启)）；Spark 则直接持 UC 令牌读元数据。上面这套微软集成
是给**用 UI 的人**准备的。

## 安全要点

- **`uc.env` 已被 `.gitignore` 忽略**，真实密钥不会被提交。
- 渲染出的 `server.properties` / `hibernate.properties` 含真实值，是**运行时产物**：仓库里那两份请保持
  占位符，**不要提交渲染后的版本**（推前脱敏，或
  `git update-index --skip-worktree etc/conf/server.properties`）。
- UC 当前**明文存凭证**（代码里 `// TODO: encrypt the credential`）→ H2 文件/数据库要做**静态加密 +
  严格访问控制**。
- `etc/conf/token.txt` 里的 admin service token **永不过期**，重启只会写入一个新的、并不会让旧的失效
  （签名密钥是持久化的）。请按管理员凭据对待；确需作废，需轮换 `etc/conf` 下的密钥文件并重启。
- 登记/轮转实例公钥（脚本随本目录提供，完整步骤见上文[接入新 DWSU](#接入新-dwsu在线热生效无需重启)）：
  `./uc_add_jwks_key.sh <public_key.jwk.json> $UC_EXTERNAL_JWKS_FILE <issuer>`
  （第 3 参 issuer 会绑定到该 key，UC 仅给该 issuer 的 token 用它验签）。

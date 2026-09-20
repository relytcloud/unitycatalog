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
UC_TARGET=http://localhost:8088 \
UC_TOKEN_FILE="$UC_HOME/etc/conf/token.txt" \
PORT=3000 HOST=0.0.0.0 node ui/server.js
```

> ⚠️ **UI 会把 admin token 注入到每个接口请求，因此任何能访问它的人都等同于 Unity Catalog 管理员，
> 且无需登录。** 请把 `3000` 端口限制在可信网络内，绝不要暴露公网。

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
| `UC_ACCESS_TOKEN_TTL` | 否 | 换发 token 有效期（ISO-8601，如 `PT1H`）。**留空 = 不过期**；`uc.env.example` 特意预置了 `PT1H`，无特殊理由请保留 |
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

## Microsoft Entra ID 登录

除了 JWKS 文件，UC 还可以直接把 Microsoft Entra ID（Azure AD）作为 token-exchange 的受信 issuer，这样
Microsoft 365 租户里的用户不需要实例签名密钥，也能用 Entra ID token 换取 UC 的 access token。这条路径与
上文的 DWSU 接入彼此独立，可以同时启用。

### 1. 应用注册（App registration）

在目标租户里创建一个应用注册（**App registrations → New registration**），从它的 **Overview** 页取两个
值：

- **Directory (tenant) ID** → `UC_ENTRA_TENANT_ID`
- **Application (client) ID** → `UC_CLIENT_ID`

再到 **Certificates & secrets** 下创建一个 client secret → `UC_CLIENT_SECRET`。服务端自己从不使用这个
secret —— 它只用 Entra 的公钥验签 —— 但跑 authorization-code 流程的客户端（目前是 CLI）需要它。

如果要用 CLI 登录，还要在 **Authentication → Add a platform → Mobile and desktop applications**
下注册一个 `http://localhost:<port>` 形式的 redirect URI，并把同一个端口填到 `UC_REDIRECT_PORT`（见
第 4 步）。Entra 对 confidential client 的 redirect URI 是**精确匹配**的，所以两边的端口必须固定且一致。

### 2. 把 `email` 加成 optional claim —— 这一步必须做

在 **Token configuration → Add optional claim → ID** 下添加 `email`。不加的话，ID token 里只有 `sub`
（一个不透明的 GUID）和 `preferred_username`；UC 会回退到用 `sub` 当 principal，而这个 GUID 永远匹配不到
已开通的用户，该租户的每次交换都会失败。服务端把这种情况单独区分出来了 —— 没有 `email` claim 的 token
会报：

```
The subject token has no 'email' claim, so the principal fell back to 'sub'. For a
Microsoft Entra ID token, add 'email' as an optional claim on the app registration so
the token carries the address the user is provisioned under.
```

这条消息直接点明了怎么改。视租户配置而定，可能还需要为 `email` claim 授予管理员同意（admin consent），
或配置一个已验证域名，Entra 才会真正下发它。

### 3. 用户必须已在 UC 中存在 —— 没有 JIT 自动开通

UC 不会在首次登录时自动建用户。请先通过 SCIM 在 UC 里开通每个用户，用的地址要与 `email` claim 携带的
**完全一致**。能解析出邮箱但未开通的主体会报 `User not provisioned: <email>` —— 与上面缺 claim 的消息
明显不同，一眼就能分清是"改应用注册"还是"开通这个用户"。

### 4. 在 `uc.env` 里配这几个值

```
UC_ENTRA_TENANT_ID=<directory-tenant-id>
UC_CLIENT_ID=<application-client-id>
UC_CLIENT_SECRET=<client-secret-value>
# 仅 CLI 登录需要，见下文。
UC_REDIRECT_PORT=8020
```

**有了 `UC_REDIRECT_PORT`，CLI 才能对着 Entra 登录。** 它会被渲染成 `server.redirect-port`，CLI
读它来决定登录回调监听哪个端口（`Oauth2CliExchange.findAvailablePort()`）；留空则 CLI 随机挑一个空闲
端口。而 Entra 对 confidential client 的 redirect URI 是精确匹配的，随机端口永远对不上已注册的 URI，所以
CLI 登录需要：固定的 `UC_REDIRECT_PORT` + 在应用注册里注册上对应的 `http://localhost:<port>`（第 1 步）。
服务端自身不使用这个值 —— 如果只用原始 token 做交换，留空即可。

改完要**重启 UC** 才生效（与 JWKS 文件不同，这几项是启动快照，不热加载）。`deploy-uc.sh` 会从 tenant id
自动推导 CLI 用的 authorization / token URL
（`https://login.microsoftonline.com/<tenant>/oauth2/v2.0/{authorize,token}`），只有要覆盖它们时才自己设
`UC_AUTHORIZATION_URL` / `UC_TOKEN_URL`。

**`UC_ALLOWED_ISSUERS` 不需要加 Entra 的 issuer。** 设了 `UC_ENTRA_TENANT_ID` 后，服务端会自动推导出
`https://login.microsoftonline.com/<tenant>/v2.0` 并信任它，同时接受 `UC_CLIENT_ID` 作为 audience；这些
都是在 JWKS 文件与 `UC_ALLOWED_ISSUERS` 已有内容之上取并集。`UC_ALLOWED_ISSUERS` 保持上文写的样子即可。

### 5. 与静态 JWKS 文件共存

两个受信来源是**按 issuer 分流**的，不是按"有没有 JWKS 文件"来分：静态文件（`UC_EXTERNAL_JWKS_FILE`）
只对它**声明过**的 issuer（即各 key 的 `issuer` 成员）有效；其它 issuer —— 包括 Entra —— 一律走 OIDC
discovery。所以一套部署可以同时跑 DWSU token-exchange 和 Entra 登录，
[DWSU 在线接入流程](#接入新-dwsu在线热生效无需重启)完全不受影响。

两者在实现上也有意不同：JWKS 文件每次验签都现读（不缓存，所以新追加的 DWSU key 不重启即刻生效）；而对
Entra，服务端按 issuer 把**构建好的 key provider** 缓存 24 小时 —— 缓存的不只是 discovery 文档 —— 所以
正常情况下一次 token 交换既不会重新拉 discovery 文档，也不会重新拉 Entra 的 JWKS。底层的 key 查询另有
按 issuer 的限流：最多突发 10 次，之后每 6 秒回补 1 次（即每分钟 10 次）。每次**成功**的
discovery 拉取都会打一条 `info` 日志
（`resolved signing keys by OIDC discovery`，每个 issuer 每个缓存周期一条，不是每次交换一条），在出厂默认的
`rootLogger.level = info` 下就能看到：某个 issuer 只出现一次、之后不再出现，就说明缓存是生效的。
这条日志有意放在**解析成功之后**：失败的 discovery 从不入缓存，如果在拉取前就打，只要对端挂了，
每次交换都会重复一条。issuer 出问题时改由下面那条 `warn` 汇报，每个 issuer 最多每分钟一条。

当**配置了 `UC_EXTERNAL_JWKS_FILE`**、却有 issuer 回退到 discovery 时，服务端还会打一条 `warn`，写明是
哪个 issuer、哪个文件。对 DWSU 的 issuer，这就是它 JWK 的 `issuer` 成员与 token 的 `iss` 对不上的信号
（写错一个字符就会被静默改走 discovery，然后在那边失败）；而在混合部署里 Entra 的 issuer 出现这条属于
预期之内，可以忽略。

### 6. 网络要求

UC 需要能出网访问 `login.microsoftonline.com`（HTTPS），用于拉取 Entra 的 OIDC discovery 文档和 JWKS。
若该端点不可达、返回非 2xx，或 key 查询被限流，token 交换返回 **503**（`UNAVAILABLE`）而不是 401 ——
上游故障按故障报，不会当成 token 被拒。discovery 请求超过 5 秒超时则返回 **504**（`DEADLINE_EXCEEDED`）。
只有确实找不到签名 key（`kid` 连 Entra 自己都不认）才仍然是 401。

discovery 文档里给出的 `jwks_uri` 在真正发起请求前会先校验：必须是 `https`，并且会拒绝环回地址、
链路本地地址、内网地址、运营商级 NAT 地址和组播地址，因此 discovery 文档无法把请求指向云厂商的元数据
接口或 `file://` 路径。但要注意这层校验的边界——它只作用于交给我们的那个 URL，而实际抓取会跟随同协议的
重定向，所以一个你已经信任的 issuer 仍然可以把 JWKS 请求重定向到内网的某个 `https` 地址。换句话说，
信任一个 issuer 归根结底仍是对该 issuer 运营方的信任，服务端无法完全兜住。

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

# 换皮打包

换应用名 / 图标 / 状态栏颜色 / 包名 / 版本号 / **默认服务端地址**，全程不改源码。

> 默认地址在编译期写死；首次启动可由用户手动填一个 origin（IP/域名 + 端口），
> 之后运行时仍只放行这一个 origin，不会变成能打开任意网址的浏览器。

## 使用步骤

1. 改 `branding.properties`（名称、颜色、包名、版本号、服务端地址）
2. 换图标：把一张**正方形** `png` 放到 `branding/icon/ic_launcher.png`；删掉该文件则恢复默认图标

   **图标要求**：
   - **文件名**：`ic_launcher.png`（路径固定）
   - **形状**：正方形（1:1），否则会被 sips 强制拉伸
   - **大小**：建议 ≥ 512×512（最大输出 192×192，过小会模糊）
   - **格式**：PNG（无损），JPG/WebP 也可但建议 PNG
   - **背景**：建议不透明纯色（系统会套圆形蒙层）
3. 执行：

```bash
./branding/build.sh customerA         # 出一客户的包：推代码 + 触发 CI + 打印下载地址（本地不用 JDK）

./branding/build.sh                   # 只打 debug 包（debug 调试签名，可直接安装）
./branding/build.sh release           # 只打 release 包（配了 keystore.properties 则自动签名）
./branding/build.sh all               # debug + release 一起打
./branding/build.sh release --clean   # 先 clean 再打包
./branding/build.sh release --upload  # 打 release 并上传到自己的服务器
```

脚本会自动定位 Android Studio 的 JDK、生成图标与 `network_security_config.xml`、编译，并打印 APK 路径与 SHA-256。

> 也可以不改脚本，直接在 Android Studio 点 Run：图标 / 网络安全配置由 `applyBrandingIcon`、`generateNetworkSecurityConfig` 任务在构建前自动生成，配置同样生效。

## 配置项（branding.properties）

| 键 | 默认值 | 说明 |
|---|---|---|
| `app_name` | `Agent` | 桌面显示名称 |
| `status_bar_color` | `#3964FE` | 状态栏颜色，`#RRGGBB` |
| `customer` | _(空)_ | 客户代号（小写字母/数字/下划线，字母开头）。非空时包名 = `application_id.customer` |
| `application_id` | `com.foresh.agent` | 基础包名。不要用 `com.a1` 这类随机包名 |
| `version_code` | `1` | 整数，覆盖安装升级时必须递增 |
| `version_name` | `1.0` | 展示用版本号 |
| `http_host` | _(空)_ | HTTP 直连固定 IP（默认地址之一；明文已全局放开，供手动填写的 IP 使用） |
| `http_port` | `8080` | HTTP 直连端口 |
| `https_host` | _(空)_ | HTTPS 固定域名 |
| `https_port` | `443` | HTTPS 端口 |
| `fixed_url` | **必填** | 默认启动地址（用户首次启动没手填地址时用它），必须是上面两个地址之一 |
| `fallback_url` | _(空)_ | 默认地址加载失败时切换一次；仅在手填地址为空时生效 |
| `block_external_subresources` | `true` | 拦截非白名单子资源；H5 依赖第三方 CDN 时设为 `false` |
| `open_external_links` | `true` | 外部 http(s) 链接点击后交系统浏览器打开；`false` = 直接拦截 |
| `deep_link_pages` | `home:/,tasks:/tasks` | 深链枚举页，`name:path` 逗号分隔 |

值留空则回退默认值；但 `fixed_url` 必填，且配置错误（颜色 / 版本号 / 地址越界 / 包名非法 / 未配置任何 host）会在编译前直接报错。

以上每一项都可以被覆盖：按客户用 `branding/customers/<customer>.properties`，按次用 `-Pkey=value` 或 `BRAND_*` 环境变量（见「每个客户一套配置」与「优先级」）。

### 同时支持 HTTP 固定 IP 与 HTTPS 域名

在 `branding.properties` 里把两个地址都写上，`fixed_url` 选一个作为启动地址：

```properties
http_host=192.168.1.100
http_port=8080
https_host=agent.ai.foresh.com
https_port=443
fixed_url=https://agent.ai.foresh.com
fallback_url=http://192.168.1.100:8080   # 域名连不上时自动走 IP:8080
```

白名单同时放行这两个 origin（仅在用户没手填地址时）；`network_security_config.xml` 已全局放开明文，
因为首次启动允许手动填 `http://IP:端口`。实际能否加载由运行时 `UrlPolicy` 的单一 origin 白名单决定。

- `agent://` 深链只接受枚举页面：`agent://open?page=home`、`agent://open?page=tasks`；**禁止**传入完整 URL。
- WebView 跳转 / 重定向 / 下载 / 上传 / 子资源都会过白名单：白名单内地址在 App 内加载；外部 http(s) 链接按 `open_external_links` 交系统浏览器；其他协议（`tel`/`mailto`/`intent` 等）一律拦截。

## release 签名（一次性配置）

1. 生成密钥库（只需一次，密码自己保管好，丢失 = 老用户无法覆盖升级；**已生成过就不要更换**）：

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -genkeypair -v -keystore release.keystore -alias agent \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Agent 任务台, OU=Mobile, O=foresh, L=济南, ST=山东, C=CN"
```

2. 把 `keystore.properties.example` 复制为根目录 `keystore.properties`，填入真实密码。
3. 以后 `./branding/build.sh release` 直接产出已签名 APK；没配置时仍会正常编译，只是不签名并打印警告。

> ⚠️ `release.keystore` 和 `keystore.properties` 不要提交到任何公开仓库；务必异地备份，签名证书换了就不能覆盖升级老用户的安装。release 固定开启 V1/V2/V3 签名与混淆 + 资源压缩。

## 上传到服务器（本地可选）

> GitHub Actions 不做上传，只在本地需要时用这个命令。

打包后把 release APK 上传到 Web 目录：

```bash
./branding/build.sh release --upload
```

- 远程文件名取自 `branding.properties` 的 `customer`，即 `agent-<customer>.apk`（例：`customer=zcwx` → `agent-zcwx.apk`）。
- 默认目标：`root@foresh:/root/workspace/webserver/website/www/html/apk/`。
- 上传成功后会打印下载地址：`https://file.foresh.com/apk/agent-<customer>.apk`（例：`https://file.foresh.com/apk/agent-zcwx.apk`）。
- 可用环境变量覆盖，不需要改脚本：

```bash
UPLOAD_TARGET=root@1.2.3.4 UPLOAD_DIR=/var/www/html/apk ./branding/build.sh release --upload
UPLOAD_URL_BASE=https://cdn.example.com/apk ./branding/build.sh release --upload
```

限制：`--upload` 仅对 `release` / `all` 生效；`customer` 为空、`keystore.properties` 缺失（未签名）或找不到 release APK 时会直接报错中止，不会上传错误产物。

## GitHub Actions 自动构建

仓库已带 `.github/workflows/android-release.yml`：在 GitHub 上自动编译、签名并产出 APK artifact，**不需要上传服务器**。

### 1. 配置 Secrets（一次性，必填）

Settings → Secrets and variables → Actions → **Secrets**：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 签名库 `release.keystore` 的 base64 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 别名（当前为 `agent`） |
| `KEY_PASSWORD` | 密钥密码 |

生成 `KEYSTORE_BASE64`：

```bash
base64 -i release.keystore | pbcopy       # macOS：直接进剪贴板
base64 -i release.keystore | tr -d '\n'   # 或输出后整行复制
```

### 2. 配置项（全部可在 GitHub 上配，可选）

Settings → Secrets and variables → Actions → **Variables**。需要覆盖哪项就建哪个，**没建的自动用 `branding.properties` 的值**：

| Variable | 对应 branding.properties |
|---|---|
| `BRAND_APP_NAME` | `app_name` |
| `BRAND_STATUS_BAR_COLOR` | `status_bar_color` |
| `BRAND_CUSTOMER` | `customer` |
| `BRAND_APPLICATION_ID` | `application_id` |
| `BRAND_VERSION_CODE` | `version_code` |
| `BRAND_VERSION_NAME` | `version_name` |
| `BRAND_HTTP_HOST` | `http_host` |
| `BRAND_HTTP_PORT` | `http_port` |
| `BRAND_HTTPS_HOST` | `https_host` |
| `BRAND_HTTPS_PORT` | `https_port` |
| `BRAND_FIXED_URL` | `fixed_url` |
| `BRAND_FALLBACK_URL` | `fallback_url` |
| `BRAND_BLOCK_EXTERNAL_SUBRESOURCES` | `block_external_subresources` |
| `BRAND_OPEN_EXTERNAL_LINKS` | `open_external_links` |
| `BRAND_DEEP_LINK_PAGES` | `deep_link_pages` |

命名规则：`BRAND_` + 配置项名大写（非字母数字转 `_`）。未配置的 Variable 会展开成空串，已被视为“未设置”自动回退，不会把 `branding.properties` 的值冲掉。

> 图标不是文本配置项，无法用 Variable 传文件。多客户时把各自的 `png` 放进 `branding/icon/`，按以下顺序自动选择：
> 1. `branding/icon/<customer>.png`（例：`branding/icon/zcwx.png`）
> 2. `branding/icon/ic_launcher.png`（通用）
> 3. `branding/icon/default/ic_launcher.png`（兜底）
>
> 这样 CI 里只改 `BRAND_CUSTOMER` 就会自动换成对应图标。

### 2.1 每个客户一套配置

每个客户可以有自己的一份配置文件，覆盖 `fixed_url` / `app_name` / 域名 等任意项：

```bash
cp branding/customers/_template.properties branding/customers/customerA.properties
# 只写需要覆盖的项，其余继承 branding.properties
```

```properties
# branding/customers/customerA.properties
app_name=客户A
http_host=10.0.0.5
http_port=8091
fixed_url=http://10.0.0.5:8091
```

- 文件名就是客户代号（`customerA.properties` → `customer` 为 `customerA`）。
- 文件名以 `_` 开头的不会被打包，所以 `_template.properties` 可以放心放在这。
- 包名仍为 `application_id + "." + customer`（例：`com.foresh.agent.customerA`）。
- 客户只需存在**图标**或**配置文件**任一，就会被列入构建目标。

### 3. 出包方式（三种）

**A. 一条命令出某个客户（日常用，推荐）**

```bash
./branding/build.sh customerA
```

它会：提交推送当前改动 → 推一个名为 `customerA` 的 tag → GitHub 收到 tag 事件识别出「这是 customerA」，只编译这一个 → 立刻打印下载地址（此时通常还没编译完）：

```
🚀 触发 CI 构建：customerA
🔗 下载地址（编译完成后可用）:
   https://github.com/kicer/h5apk/releases/download/customerA/agent-customerA.apk
```

> 原理就是推 tag，所以本地**只需要 git**，不需要 JDK/Android SDK、也不需要额外工具。
> tag 会被强推到当前提交（同名 tag 每次滚动），GitHub 据此只编译指定客户。
> 若改动未提交且 tag 已指向当前提交，推送不会产生事件，脚本会提醒你去 Actions 页 Re-run。

**B. 网页手动 Run workflow**

Actions → `Build Android APK` → Run workflow：

- `customer` **留空 = 单包**（`com.foresh.agent`，产物 `agent.apk`）
- 填 `all` = 全部客户（每个客户各一个包）
- 填客户代号 = 只打这一个（历史换皮用法）

**C. 推版本 tag = 单包**

```bash
git tag v2.2.0 && git push origin master --tags
```

> `v1.2.3` 形式 → 出**单包**（`_base`）；客户名 tag → 只打该客户（历史兼容）；其他 tag 报错退出。

**打哪些包：** 不指定客户时只出**单包**（`_base`）；指定客户时才扫 `branding/customers/<customer>.properties` + `branding/icon/<customer>.png`（`_` 开头的除外）。**客户列表就是这两个目录里的文件名**。

本地可以预览本次会打哪些目标：

```bash
bash branding/ci-targets.sh
# 构建目标: ["_base"]
```

### 4. 产物去哪找

**单包**（默认）：Release tag = `agent`，产物 `agent.apk`：

```
https://github.com/<owner>/<repo>/releases/tag/agent
https://github.com/<owner>/<repo>/releases/download/agent/agent.apk
```

**按客户换皮**（历史用法）：一个客户一个 Release（tag = 客户代号），产物 `agent-<customer>.apk`。

> ⚠️ 仓库是私有的，所以这个下载链接**需要登录 GitHub** 才能用。要给别人直接点链接下载，得把仓库改成公开（那就建议把真实 IP/域名改从 Secrets 注入，别写在仓库里）。

### 5. 优先级

```
workflow 手动输入(-P)  >  GitHub Variables(BRAND_*)  >  branding/customers/<customer>.properties  >  branding.properties  >  代码默认值
```

本地同理，`build.sh` 支持透传 `-P`：

```bash
./branding/build.sh release -Pcustomer=customerA -Pversion_code=100
```

想本地看「会打哪些客户」：

```bash
bash branding/ci-targets.sh
```

> 为兼容 CI，图标生成已从 macOS 的 `sips` 换成纯 Java（ImageIO），默认图标从 `.webp` 换成 `.png`（Java 读不了 WebP）；`gradle.properties` 加了 `-Djava.awt.headless=true`。

## 注意事项

- `app_name`、`status_bar` 两个资源由 `build.gradle` 的 `resValue` 注入，**不要**再在 `res/values/strings.xml` 里定义同名资源。
- 权限：`INTERNET` + `RECORD_AUDIO`（语音输入）；`allowBackup=false`；组件 `exported` 最小化（仅 `MainActivity`）。
- 不使用动态加载 / 热更新 / 插件化；`fixed_url`、IP、端口默认写在 `BuildConfig`，运行时只允许用户在设置页填一个 origin，不接受 Intent 传入的完整 URL。
- 因为允许手填 `http://IP:端口`，`usesCleartextTraffic=true` + `base-config cleartextTrafficPermitted=true`（自用取舍）。
- 换 `application_id` 或加 `customer` = 新应用（与旧包共存，数据不互通）。
- 图标只替换了传统 mipmap 图标；如需 Android 8+ 自适应图标（前景/背景分层）再扩展。

## 给 H5 方生成唤起链接

```
agent://open?page=home    # 打开首页
agent://open?page=tasks   # 打开任务页
```

只允许 `deep_link_pages` 中枚举的页面；不再支持 `agent://load?url=...` 与 `save=1`。

## 发布前自检

- [ ] `branding.properties` 里的 IP / 域名 / 端口与线上一致
- [ ] release 使用统一正式签名，版本号递增
- [ ] APK 上传 VirusTotal 自检
- [ ] 上架 / 收录 / 备案信息就绪

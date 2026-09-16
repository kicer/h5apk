# h5app —— H5 壳 APK 打包

把固定网址的 H5 页面封装成 Android APK，一套代码给多个客户批量出包。

**核心特点：地址、包名、图标全部在编译期写死，装成 APK 后无法修改。**
不提供任何「输入网址」入口，不是马甲包工具。

---

## 目录速查

```
app/                      Android 工程（只有一个 MainActivity + WebView 壳）
branding/
  branding.properties     所有客户的公共默认配置 ★经常改
  customers/              每个客户一个配置文件 ★新增客户来这里
    _template.properties  模板（_ 开头，不会被打包）
    zcwx.properties       客户 zcwx
  icon/
    <客户>.png            某客户的图标（可选，文件名 = 客户代号）
    ic_launcher.png       通用图标（没配客户图标时用它）
    default/ic_launcher.png  兜底图标
  res/                    构建时自动生成（图标 + network_security_config），别手改
  build.sh                ★一条命令出包
  ci-targets.sh           计算本次要打哪些客户（build.sh 与 CI 都用它）
  README.md               配置项与安全设计的详细说明
.github/workflows/
  android-release.yml     GitHub Actions：编译 + 签名 + 发 Release
```

---

## 快速上手：给一个客户出包

```bash
# 1. 建配置（客户代号 = 文件名，小写字母/数字/下划线，字母开头）
cp branding/customers/_template.properties branding/customers/customerA.properties
vim branding/customers/customerA.properties       # 填这个客户的地址

# 2. 放图标（可选）
cp ~/a.png branding/icon/customerA.png

# 3. 出包（本地只需要 git）
./branding/build.sh customerA
```

输出：

```
📦 提交并推送改动...
🚀 触发 CI 构建：customerA
🔗 下载地址（编译完成后可用）:
   https://github.com/kicer/h5apk/releases/download/customerA/agent-customerA.apk
📋 构建进度: https://github.com/kicer/h5apk/actions
```

**只会编译 customerA，不碰其他客户。** 本地不需要 JDK / Android SDK。

> 打开这个命令时 CI 通常还没编译完，链接要等 1~4 分钟才可用（首次更久）。

---

## 客户是什么

**客户 = `branding/customers/<客户代号>.properties`（必须有），图标可选。**

- 客户列表**就是** `branding/customers/` 和 `branding/icon/` 里的文件名，没有别的来源。
- 配置文件只写「和 `branding.properties` 不同的值」，其余全部继承。空文件也可以（表示全继承）。
- 图标查找顺序：`branding/icon/<客户>.png` → `branding/icon/ic_launcher.png` → `branding/icon/default/ic_launcher.png`
- 包名 = `application_id + "." + 客户代号`，例：`com.foresh.agent.customerA`

查看当前会被打包的客户：

```bash
bash branding/ci-targets.sh
# 构建目标: ["zcwx"]
```

### 配置项

所有键都可以写在 `branding.properties`（公共默认）或 `branding/customers/<客户>.properties`（该客户覆盖）：

| 键 | 说明 |
|---|---|
| `app_name` | 桌面显示的应用名 |
| `status_bar_color` | 状态栏颜色 `#RRGGBB` |
| `application_id` | 基础包名（一般不用改） |
| `version_code` / `version_name` | 版本号；`version_code` 覆盖安装时必须递增 |
| `http_host` / `http_port` | HTTP 直连地址（`network_security_config` 只对它开放明文） |
| `https_host` / `https_port` | HTTPS 域名 |
| `fixed_url` | 启动地址，必须是上面两个地址之一，host/port 写法要完全一致（**必填**） |
| `fallback_url` | 启动地址加载失败时自动切换一次（可选） |
| `block_external_subresources` | 是否拦截非白名单子资源，默认 `true` |
| `open_external_links` | 外部 http(s) 链接是否交系统浏览器打开，默认 `true` |
| `deep_link_pages` | 深链枚举页，格式 `name:path`，逗号分隔 |

`branding.properties` 里**不要写** `customer`（客户由目录里的文件名决定）。

### 配置优先级（高 → 低）

```
命令行 -Pkey=value  >  环境变量 BRAND_<KEY>  >  customers/<客户>.properties  >  branding.properties  >  代码默认值
```

---

## 出包的三种方式

| 方式 | 用途 | 打多少客户 |
|---|---|---|
| `./branding/build.sh <客户>` | **日常**，加/改一个客户 | 只打该客户 |
| Actions 页 → Run workflow | 网页操作；`customer` 留空 | 留空 = 全部客户 |
| `git tag v2.1.3 && git push origin master --tags` | 版本 tag | 全部客户 |

**原理**：`build.sh <客户>` 就是把一个**和客户同名的 tag** 强推到当前提交；CI 收到 tag 事件后判断「tag 名是不是已知客户」——是就只打它，是 `v1.2.3` 形式就打全部，都不是则直接报错退出。

> 同名 tag 每次滚动更新（`git push -f`），所以不存在一堆历史 tag。

---

## 产物在哪

**① 你自己的服务器（直接给客户/自己用）**

```
https://file.foresh.com/apk/agent-<客户>.apk
```

CI 编译完会自动 scp 过去（需要先配下面两个 Secret，没配就自动跳过这步）。

**② GitHub Release（按客户存档/备份）**

```
https://github.com/kicer/h5apk/releases/tag/<客户>
https://github.com/kicer/h5apk/releases/download/<客户>/agent-<客户>.apk
```

Release 内容：`agent-<客户>.apk` + `SHA256SUMS.txt`。

- 某客户构建失败不影响其他客户（只有构建成功才会更新它的 Release）。
- 每次运行的中间产物也保留在 Actions 的 Artifacts 里（30 天）。
- ⚠️ 仓库是**私有**的，GitHub 那个链接**需要登录**才能点开；发给客户用 ① 。

---

## 首次配置（只需一次）

GitHub 仓库 → Settings → Secrets and variables → Actions → **Secrets**：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i release.keystore \| tr -d '\n' \| pbcopy` 的结果 |
| `KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword` |
| `KEY_ALIAS` | `agent` |
| `KEY_PASSWORD` | `keystore.properties` 里的 `keyPassword` |

想让 CI 编译完**直接把 APK 传到你自己的服务器**，再加两个 Secret（不加就自动跳过）：

| Secret | 值 |
|---|---|
| `DEPLOY_HOST` | 服务器地址，CI 里无法解析你本地 ssh 的 `foresh` 别名，**必须写真实 IP/域名** |
| `DEPLOY_SSH_KEY` | 专用部署私钥的全文（见下） |
| `SSH_KNOWN_HOSTS`（可选） | `ssh-keyscan -H <服务器>` 的输出；不配则每次自动 keyscan |

生成部署密钥（在你本机跑，不要用你个人密钥）：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/deploy_h5apk -N '' -C 'gha-deploy'
# 公钥装到服务器
ssh-copy-id -i ~/.ssh/deploy_h5apk.pub root@<服务器>
# 私钥全文（含 BEGIN/END 首尾行）复制到 DEPLOY_SSH_KEY
cat ~/.ssh/deploy_h5apk
```

部署目标可用 Variables 覆盖（都可留空用默认值）：`DEPLOY_USER`（默认 `root`）、`DEPLOY_DIR`（默认 `/root/workspace/webserver/website/www/html/apk`）、`DEPLOY_URL_BASE`（默认 `https://file.foresh.com/apk`）。

想覆盖配置项也可以在 **Variables** 里建 `BRAND_APP_NAME`、`BRAND_VERSION_CODE` 等（前缀 `BRAND_` + 配置项名大写）。

> `release.keystore` 和 `keystore.properties` 已被 `.gitignore` 忽略，不会进仓库；**务必异地备份**，签名换了老用户无法覆盖升级。

---

## 版本号怎么涨

改 `branding.properties` 的 `version_code`（递增）和 `version_name`，跟客户配置一起提交即可。所有客户共用同一套版本号（每个客户是独立 App，各自版本线互不影响）。

---

## 安全设计要点

| 项 | 做法 |
|---|---|
| 地址白名单 | 所有加载/跳转/重定向/下载/上传/子资源都过 `UrlPolicy`，只放行编译期写死的地址 |
| 深链 | 只支持 `agent://open?page=home` 这类枚举页，**禁止**外部传入完整 URL |
| 明文流量 | `usesCleartextTraffic=false` + `network_security_config` 仅对 `http_host` 开放明文 |
| WebView | 关文件访问、关第三方 Cookie、关自动播放、混合内容 `NEVER_ALLOW`、不使用 JS 桥 |
| 权限 | 只有 `INTERNET`；`allowBackup=false`；`exported` 最小化 |
| 混淆 | release 开启 `minifyEnabled` + `shrinkResources`，V1/V2/V3 签名 |

详见 `branding/README.md`。

---

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| `./branding/build.sh xxx` 报「找不到客户」 | `branding/customers/xxx.properties` 或 `branding/icon/xxx.png` 不存在 |
| 推到 tag 但 Actions 没反应 | tag 已指向当前提交（推送不产生事件）→ 到 Actions 页点 Re-run，或先改点东西 |
| CI 报 `缺少 secret KEYSTORE_BASE64` | 上面 4 个 Secrets 没配 |
| CI 报 `fixed_url 不在允许范围内` | `fixed_url` 与 `http_host:http_port`（或 `https_host:https_port`）不一致 |
| 报 `applyBrandingIcon` 找不到图标 | 图标必须是 **PNG**（Java 读不了 WebP） |
| 本地构建卡在下载 Gradle | `gradle/wrapper/gradle-wrapper.properties` 里是腾讯镜像，本机已缓存；不要随意改成 `-bin`（本机可能下不完整） |
| 页面外部 CDN 图片挂掉 | `block_external_subresources=true` 所致，需要就改 `false` |
| 页面链接点了没反应 | `open_external_links=false` 会拦截外链 |

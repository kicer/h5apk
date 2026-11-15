# h5app —— H5 壳 APK 打包

把 H5 页面封装成 Android APK。默认一个包（`com.foresh.agent`）走到底，也能按客户换皮。

**地址默认在编译期写死；首次启动允许用户手动填一个服务器 origin（IP/域名 + 端口），
之后运行时只放行这一个 origin——仍然是单站点壳，不是浏览器。**
包名、图标在编译期固定。

---

## 目录速查

```
app/                      Android 工程（只有一个 MainActivity + WebView 壳）
branding/
  branding.properties     默认配置 ★经常改
  icon/
    ic_launcher.png       应用图标
    default/ic_launcher.png  兜底图标
  res/                    构建时自动生成（图标 + network_security_config），别手改
  build.sh                ★一条命令出包
  README.md               配置项与安全设计的详细说明
.github/workflows/
  android-release.yml     GitHub Actions：编译 + 签名 + 发 Release
```

---

## 快速上手：出一版（单包）

```bash
# 1. 改版本号（覆盖安装时 version_code 必须递增）
vim branding/branding.properties

# 2. 出包（本地只需要 git）
./branding/build.sh v2.2.0
```

输出：

```
📦 提交并推送改动...
🚀 触发 CI 构建：v2.2.0（单包）
🔗 下载地址（编译完成后可用）:
   ① 你的服务器 : https://file.foresh.com/apk/agent.apk
   ② GitHub 备份: https://github.com/kicer/h5apk/releases/download/agent/agent.apk
```

包名固定 `com.foresh.agent`，产物 `agent.apk`。首次启动会让用户填服务器地址（可跳过）。

> 打开这个命令时 CI 通常还没编译完，链接要等 1~4 分钟才可用（首次更久）。

### 历史用法：按客户换皮

```bash
cp branding/customers/_template.properties branding/customers/customerA.properties
cp ~/a.png branding/icon/customerA.png
./branding/build.sh customerA    # 包名变成 com.foresh.agent.customerA
```

---

## 客户是什么

**客户 = `branding/customers/<客户代号>.properties`（必须有），图标可选。**

- 客户列表**就是** `branding/customers/` 和 `branding/icon/` 里的文件名，没有别的来源。
- 配置文件只写「和 `branding.properties` 不同的值」，其余全部继承。空文件也可以（表示全继承）。
- 图标查找顺序：`branding/icon/<客户>.png` → `branding/icon/ic_launcher.png` → `branding/icon/default/ic_launcher.png`
- 包名 = `application_id`（单包，不追加客户后缀）

### 配置项

所有键都写在 `branding.properties`：

| 键 | 说明 |
|---|---|
| `app_name` | 桌面显示的应用名 |
| `status_bar_color` | 状态栏颜色 `#RRGGBB` |
| `application_id` | 基础包名（一般不用改） |
| `version_code` / `version_name` | 版本号；`version_code` 覆盖安装时必须递增 |
| `http_host` / `http_port` | HTTP 直连地址（明文已全局放开，供手动填写的 `http://IP:端口` 使用） |
| `https_host` / `https_port` | HTTPS 域名 |
| `fixed_url` | **默认**启动地址，必须是上面两个地址之一（用户首次启动没手填地址时用它，**必填**） |
| `fallback_url` | 默认地址加载失败时自动切换一次（可选；手填地址时不生效） |
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

| 方式 | 用途 | 打多少包 |
|---|---|---|
| `./branding/build.sh v2.2.0` | **日常**（单包） | 1 个：`com.foresh.agent` → `agent.apk` |
| `./branding/build.sh <客户>` | 历史：按客户换皮 | 只打该客户 |
| Actions 页 → Run workflow | 网页操作；`customer` 留空 | 留空 = 单包；填 `all` = 全部客户 |
| `git tag v2.2.0 && git push origin master --tags` | 版本 tag | 单包 |

**原理**：`build.sh <tag>` 就是把同名 tag 强推到当前提交；CI 收到 tag 事件后判断——是已知客户名就只打该客户，是 `v1.2.3` 形式就打单包（`_base`），都不是则报错退出。

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
| 地址白名单 | 所有加载/跳转/重定向/下载/上传/子资源都过 `UrlPolicy`，只放行当前生效的**单一 origin**（默认来自编译期，手填后只放行手填的那个） |
| 深链 | 只支持 `agent://open?page=home` 这类枚举页，**禁止**外部传入完整 URL |
| 明文流量 | `usesCleartextTraffic=true` + `base-config cleartextTrafficPermitted=true`：允许手填 `http://IP:8090` 的自用取舍，地址仍受单一 origin 约束 |
| WebView | 关文件访问、关第三方 Cookie、关自动播放、混合内容 `NEVER_ALLOW`；JS 桥仅暴露语音录音（`VoiceBridge`，3 个无参方法，无文件/存储读写能力） |
| 权限 | `INTERNET` + `RECORD_AUDIO`（语音输入）；`allowBackup=false`；`exported` 最小化 |
| 混淆 | release 开启 `minifyEnabled` + `shrinkResources`，V1/V2/V3 签名 |

详见 `branding/README.md`。

---

## 单包与首次设置

默认只出一个包：`applicationId=com.foresh.agent`，产物 `agent.apk`（版本 tag 或手动 Run workflow 留空 customer）。

- **首次启动**弹原生设置页：「服务器地址」只填 IP 或域名，端口默认 `8090`（写 `https://` 则默认 443）；
  留空或点「跳过」就用手填为空时的默认地址（`fixed_url`）。
- **改地址**：返回键菜单 →「服务器设置」（与「清空缓存」「退出应用」并列）。
- **解析规则**：只接受 origin，带路径/参数/账号一律报错；`1.2.3.4` → `http://1.2.3.4:8090`；
  误粘贴的尾部 `/`、`/api`、`/api/health` 会自动剔掉。
- **保存前先校验**：请求 `<origin>/api/health`，要求 HTTP 200 + JSON 可解析 + `ok===true` + `service==='agent-core'`。
  失败会分开提示：DNS/连不上、404（端口或反代路径不对）、不是 agent-core、证书不受信任、系统禁明文。
  网络/证书异常时可以点「仍然使用…（跳过检查）」强行继续。
- 手填地址**不会**自动切到 `fallback_url`（填错就改，不静默换服务器）。
- 仍在出按客户换皮的包时的兼容：`./branding/build.sh <客户>` 或 workflow 里填客户代号，包名变成 `com.foresh.agent.<客户>`（与单包不互通）。

---

## 语音输入（录音桥）

页面通过 `window.AgentNative`（由 `VoiceBridge` 注入）按住录音：壳用 `AudioRecord` 以
16kHz / 单声道 / 16bit 采集，每约 100ms 通过 `onNativeVoiceChunk(base64Pcm, 16000)` 推一片
裸 PCM；页面按停顿切段并逐段 `POST /api/asr/transcribe`。`stopVoice()` 只停采集，不再回传整段音频。
完整契约见 `voice-input.md`。

- 首次点击申请 `RECORD_AUDIO`，授权后自动开始录音；拒绝则提示「需要麦克风权限」。
- 只有「白名单页面 + App 在前台」才真正开录（防第三方 CDN 脚本静默取麦），单实例状态机防连点。
- 切后台 / 换页会取消录音并回报 `cancelled`；不做前台服务（短交互不值得常驻通知）。
- 单次上限 180 秒（页面 + 壳各兜一道）；16kHz 单声道裸 PCM，每秒约 32KB、Base64 后约 43KB。
- 声波由页面用每片 PCM 的峰值计算；壳不做静音判定、切段或音量计算。
- 服务端没接 ASR（`GET /api/asr/status` 返回 `enabled:false`）时页面不显示录音按钮，壳不受影响。

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
| 点麦克风提示「需要麦克风权限」 | 之前拒绝过权限；到系统设置 → 应用 → 权限里打开麦克风（「不再询问」不会再弹窗） |
| 页面没有麦克风按钮 | 服务端 `GET /api/asr/status` 返回 `enabled:false`（`ASR_URL` 未配置），或页面既无桥又非 HTTPS |
| 首次启动停在「服务器地址」页 | 预期行为；直接点「跳过，使用默认地址」即可 |
| 填完地址打不开、想换回域名 | 返回键 → 「服务器设置」→「恢复默认地址」 |

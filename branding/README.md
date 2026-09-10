# 换皮打包

换应用名 / 图标 / 状态栏颜色 / 包名 / 版本号 / **服务端地址**，全程不改源码。

> 所有地址均为**编译期写死**，装成 APK 后无法修改，也不提供任何「输入网址」入口。

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
./branding/build.sh                   # 只打 debug 包（debug 调试签名，可直接安装）
./branding/build.sh release           # 只打 release 包（配了 keystore.properties 则自动签名）
./branding/build.sh all               # debug + release 一起打
./branding/build.sh release --clean   # 先 clean 再打包
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
| `http_host` | _(空)_ | HTTP 直连固定 IP（`network_security_config` 只对它开放明文） |
| `http_port` | `8080` | HTTP 直连端口 |
| `https_host` | _(空)_ | HTTPS 固定域名 |
| `https_port` | `443` | HTTPS 端口 |
| `fixed_url` | **必填** | 启动地址，必须是上面两个地址之一，且 host/port 完全一致 |
| `fallback_url` | _(空)_ | 备用地址，启动地址首次加载失败时切换一次 |
| `block_external_subresources` | `true` | 拦截非白名单子资源；H5 依赖第三方 CDN 时设为 `false` |
| `open_external_links` | `true` | 外部 http(s) 链接点击后交系统浏览器打开；`false` = 直接拦截 |
| `deep_link_pages` | `home:/,tasks:/tasks` | 深链枚举页，`name:path` 逗号分隔 |

值留空则回退默认值；但 `fixed_url` 必填，且配置错误（颜色 / 版本号 / 地址越界 / 包名非法 / 未配置任何 host）会在编译前直接报错。

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

白名单同时放行这两个 origin；`network_security_config.xml` 只对 `http_host` 开放明文，其余一律禁明文（HTTPS 域名无需声明，始终允许）。

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

## 注意事项

- `app_name`、`status_bar` 两个资源由 `build.gradle` 的 `resValue` 注入，**不要**再在 `res/values/strings.xml` 里定义同名资源。
- 权限只保留 `INTERNET`；`allowBackup=false`；组件 `exported` 最小化（仅 `MainActivity`）。
- 不使用动态加载 / 热更新 / 插件化 / `addJavascriptInterface`；`fixed_url`、IP、端口全部写在 `BuildConfig`，运行时不从 Intent 接收。
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

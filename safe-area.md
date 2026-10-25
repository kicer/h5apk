# 安全区 / 键盘：壳与 H5 的契约

壳把系统占用区以 **CSS 变量**注入页面。H5 只认这些变量，不要依赖
`env(safe-area-inset-*)`——Android WebView 基本恒为 0（Chromium 只在 fullscreen
场景才把窗口 insets 交给渲染层），补 `viewport-fit=cover` 也拿不到。

实现见 `MainActivity.setupSafeAreaInsets()`。

---

## 一、变量

| 变量 | 含义 | 单位 | 谁写 |
|---|---|---|---|
| `--sat` | 顶部：状态栏 + 刘海 | CSS px | 壳 |
| `--sab` | 底部：导航条 | CSS px | 壳 |
| `--sal` / `--sar` | 左右侧边刘海（横屏才有值，竖屏恒为 0） | CSS px | 壳 |
| `--kb-native` | 键盘高度，收起时为 0 | CSS px | 壳 |
| `--kb-page` | 页面用 `visualViewport` 估出来的键盘高度 | CSS px | 页面 |

**单位是 CSS px，不是物理像素。** 壳已经按 `density` 换算过，可以直接写进
`padding` / `height` / `top`，不需要再除 `devicePixelRatio`。

变量写在 `document.documentElement` 的**行内样式**上，所以能覆盖 `:root` 里的默认值
（比如 `--sat: env(safe-area-inset-top, 0px)`，那行留着给 iOS / 浏览器 / 添加到主屏用）。

## 二、注入时机

- insets 变化时：旋转、键盘起落、导航方式切换；
- 每次新文档的 `onPageCommitVisible` / `onPageFinished` 会重新注入（换页后行内样式会丢）；
- SPA 内部路由不换文档，变量一直有效。

壳在收到第一帧 insets 之前不写任何值，所以页面的默认值不会被 `0` 覆盖。

## 三、键盘：必须算残差，不能直接减

`--kb` 直接取 `max(--kb-page, --kb-native)` 在多数情况下对，但有一种组合会**多扣一个键盘高度**：

| 场景 | `innerHeight` | `--kb-page` | `--kb-native` | `max` 的结果 |
|---|---|---|---|---|
| A. 窗口被 resize（旧系统 + `adjustResize`，非 edge-to-edge） | 变矮 | 0 | 0 | 0 ✅ |
| B. 只缩 visual viewport（`interactive-widget=resizes-visual`，默认） | 不变 | 键盘高 | 键盘高 | 键盘高 ✅ |
| C. 缩 layout viewport（`interactive-widget=resizes-content` 且 WebView 支持） | **已经变矮** | 0 | 键盘高 | 键盘高 ❌ 多扣 |

场景 C 在 Android 15 / targetSdk 35（系统强制 edge-to-edge）上真实存在。二选一：

**做法 1（推荐，改动最小）：不要写 `interactive-widget=resizes-content`。**
默认的 `resizes-visual` 落在场景 B，`max` 就是对的。

**做法 2：保留 `resizes-content`，`--kb` 改成残差：**

```js
// 键盘收起时记一次基准值（旋转/窗口变化时也要重记）
var baseInnerHeight = window.innerHeight;

function updateKb() {
  var kbNative = parseFloat(
    getComputedStyle(document.documentElement).getPropertyValue('--kb-native')) || 0;

  // 浏览器已经替我们让出的高度（resizes-content 或窗口 resize 时 > 0）
  var layoutShrink = Math.max(0, baseInnerHeight - window.innerHeight);

  // 双指缩放时 visualViewport 会变矮，那不是键盘，必须排除
  var kbPage = 0;
  if (!window.visualViewport || window.visualViewport.scale === 1) {
    kbPage = Math.max(0, window.innerHeight - (window.visualViewport ? window.visualViewport.height : window.innerHeight));
  }

  var kb = Math.max(kbPage, Math.max(0, kbNative - layoutShrink));
  document.documentElement.style.setProperty('--kb', kb + 'px');
}
```

三种场景都对：A → 0，B → 键盘高，C → 0（已经让过了）。

## 四、布局建议

```css
:root {
  --sat: env(safe-area-inset-top, 0px);   /* 壳注入会覆盖 */
  --sab: env(safe-area-inset-bottom, 0px);
  --kb: 0px;
}

/* 顶部：内容让开状态栏 */
.header {
  padding-top: var(--sat);
  /* 状态栏区域画的是页面自己的背景：Android 15 起 setStatusBarColor 已失效，
     状态栏是透明的，所以 header 背景必须铺到最上面 */
  background: #fff;
}

/* 主体：整页固定 shell，靠减高度把 footer 顶到键盘上沿 */
#app {
  height: calc(100% - var(--kb));
}

/* 底部：键盘弹起时导航条被键盘盖住，别再多留一条 */
.footer {
  padding-bottom: max(var(--sab) - var(--kb), 0px);
}

/* 左右（横屏刘海） */
.page {
  padding-left: var(--sal);
  padding-right: var(--sar);
}
```

输入框仍被遮时，在 `focus` 里补一次滚动：

```js
input.addEventListener('focus', function () {
  setTimeout(function () { input.scrollIntoView({ block: 'center' }); }, 300);
});
```

## 五、验证

1. `chrome://inspect` 需要壳开启 `WebView.setWebContentsDebuggingEnabled(true)`——
   **只有 debug 包开了，release 包不开**。要用 release 包验证，先打 debug 包。
2. 连上后看实际注入值：

```js
var s = getComputedStyle(document.documentElement);
['--sat', '--sab', '--sal', '--sar', '--kb-native', '--kb-page'].forEach(function (k) {
  console.log(k, s.getPropertyValue(k));
});
```

3. 键盘起落时看 `--kb-native` 是否跟着变：
   - 跟着变 → 壳能拿到 IME insets，页面只依赖 `--kb` 即可；
   - 恒为 0 → 该 WebView 版本没把 IME insets 传下来，只能靠 `--kb-page`。

## 六、边界

- 页面若改 `initial-scale` 或用双指缩放，CSS px 与 `density` 的换算会偏；
  H5 侧保持 `width=device-width, initial-scale=1` 就不用管。
- 非 edge-to-edge 时（Android 15 以下、且壳没主动全屏）三个 insets 都是 0，
  注入 0 不影响原有布局，同一套代码在新旧系统上都能跑。
- 壳不消费 insets（listener 原样返回），不改变系统对窗口的默认布局。

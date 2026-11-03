# Folia TV

把 [Folia](https://github.com/lip520/folia-major)（沉浸式歌词播放器）搬到电视/投影仪：
Android TV WebView 壳 + 遥控器 D-pad 导航，Folia Web 版与网易云 API 跑在局域网 Mac 上。

已在坚果投影仪（JmGO，WebView Chrome 83，1920x1080）实测：遥控器浏览歌单、OK 播放、音频输出、歌词匹配流程全部可用。

## 架构

```
遥控器 D-pad ──► MainActivity（透传按键，不拦截）
                     │  Chromium 生成 trusted Arrow*/Enter
                     ▼
              WebView 加载 Folia Web ──► 页面原生键盘导航（React）
                     │
                     ├── http://<Mac>:8080  Folia Web（vite preview）
                     └── http://<Mac>:3000  NeteaseCloudMusicApiEnhanced
```

**关键设计：按键必须透传。** Folia 桌面版自带完整键盘导航（方向键滚动、Enter 打开/播放），
但只响应 trusted 事件——`evaluateJavascript` 注入的 synthetic 事件到不了 React 业务层
（实测：能滚动、打不开歌单）。所以壳不拦截 DPAD/ENTER，只接管 MENU（改服务地址）和 BACK。

## 目录

- `app/` — Android 壳（单 Activity 全屏 WebView，minSdk 24）
- `patches/folia-web-chrome83.patch` — Folia Web 版 Chrome 83 兼容补丁：
  - `index.html` 注入 polyfill（Object.hasOwn / Array.at / Promise.any / structuredClone / Intl.Segmenter …）
  - `vite.config.ts` 构建目标降到 `chrome83`
  - `textDirection.ts` 修复 RTL 脚本名正则（Chrome 83 解析期崩溃）
  - `scripts/fix-css-tv.mjs` CSS 后处理（解包 @layer、:where/:is 降级、LightningCSS targets chrome83、展开 inset），已挂到 `npm run build` 尾部

## 部署

```bash
# 1. Mac：先打 Chrome 83 补丁再构建（folia-major 更新后补丁会被冲掉，必须重新 apply，
#    否则 Chrome 83 WebView 解析 `||=` 直接 SyntaxError，页面会永远停在 Folia 启动画面）
cd folia-major && git apply ../folia-tv/patches/folia-web-chrome83.patch
npm run build && npx vite preview --host 0.0.0.0 --port 8080   # :8080

# 2. Mac：启动网易云 API（NeteaseCloudMusicApiEnhanced）  # :3000
cd netease-api && HOST=0.0.0.0 node app.js

# 3. 构建并安装 TV 壳
gradle assembleDebug
adb connect <投影仪IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动直接用内置默认地址并记住，之后按**菜单键**可改。

## 遥控器操作

- 方向键：移动 / 滚动卡片；OK：打开 / 播放
- 搜索框 OK 后弹出系统屏幕键盘，方向键选字、移到 GO 提交
- 播放/暂停键：播放/暂停；上一首/下一首键：切歌（仅 player 视图生效，Folia 键盘桥限制）
- 短按菜单键：服务地址设置；长按菜单键：打开命令面板（Ctrl+K）

## 已知问题

- 云盘歌曲无封面/歌词元数据 → 全屏可视化区呈黑屏+进度条（正常，官方曲库歌曲有完整歌词视图）
- ~~全屏播放视图按返回会退到桌面~~（已修复：BACK 转页面原生 Escape，由 Folia 自己退出播放视图/关闭弹层；页面无层可关时才退桌面）
- ~~搜索提交后系统软键盘不收起、遮挡搜索结果~~（已修复：tvnav.js 监听路由进入 `#search/` 时自动 blur 输入框）
- ~~播放/暂停键被系统 MediaSession 拦截、无法控制 WebView 音频~~（已修复：注册 MediaSession，系统媒体键路由到 Callback，JS 直接控制 `<audio>`；同时 2 秒轮询回传歌曲信息更新 MediaSession 元数据）
- ~~遥控器上一首/下一首键不生效~~（已修复：MediaSession onSkipToNext/onSkipToPrevious 派发 synthetic Ctrl+Arrow 给 Folia 键盘桥；仅 player 视图生效）
- ~~长按菜单键打不开命令面板、松手反而弹设置框~~（已修复：dispatchKeyEvent 全量消费 MENU 导致框架 onKeyLongPress 永不触发，改为按下自行计时判定长按；顺带修复设置框地址文字白底白字不可见的问题）
- 两个 Mac 服务为前台进程，Mac 需保持唤醒；开机自启需自行配置 launchd

## 参考

- [whycfawa/folia-android](https://github.com/whycfawa/folia-android)：Capacitor 打包的 Folia Android 版，其 MediaSession + PlaybackActionReceiver 架构为媒体键处理提供了参考

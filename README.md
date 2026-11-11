# WirelessAdb

**LSPosed 模块**：开机首次解锁后自动开启无线 ADB，并在状态页查看地址与日志。

包名：`dev.wirelessadb.autostart`  
当前版本：`1.2.17`（libxposed API 102 迁移版，面向一加 15 / ColorOS 17 / Android 17；**修复地址端口号被截断、精简快捷操作、自动复制不再依赖微信输入法**；桌面图标为绿底白螺旋（FLClash 风格，附 Android 13+ 主题图标层），图形占比 60.8%；状态页 Material You 单页 Dashboard、统一线性图标、**整页自由滚动**（无吸顶固定）、app 改名「无线ADB」、无底部导航；修复全部按钮按下时浮现矩形方块；**修复无谓重启 adbd 导致的无线 ADB 断连**：配置未变时不再重启，切换模式时明确提示需用新地址重连；**模块加载状态改用独立标记 + boot_count 锚定**，不再依赖会被滚动截断的日志文本）

<p align="center">
  <img src="docs/screenshot.png" alt="无线 ADB 自启状态页" width="360" />
</p>

> 本仓库是 [shirenran/WirelessAdb-LSPosed](https://github.com/shirenran/WirelessAdb-LSPosed) 的 fork，在原作基础上适配 ColorOS 17 / Android 17 并持续维护。原作版权与 MIT 许可见 [LICENSE](LICENSE) 与文末[致谢](#致谢)。

## 功能
1、TLS 模式：系统「无线调试」（adb_wifi_enabled），端口随机，需配对

2、TCP 模式：等价 adb tcpip <port>，默认 5555，电脑可直接 adb connect IP:5555

3、监听无线调试关闭（仅 TLS）、Wi‑Fi 恢复、亮屏，必要时重新开启

4、地址变化时自动写入系统剪贴板；点按状态页地址可随时手动复制

5、获取 IP 时跳过 172.19.* VPN 虚拟地址

6、开机早期日志写入 Settings.Global，状态页可查看

### 剪贴板与输入法（常见疑问）

**不必须安装微信输入法**。复制动作写的是 Android **系统剪贴板**（由 system_server 的 `ClipboardManager` 写入），豆包、搜狗、百度、Gboard 等任何输入法都能直接粘贴，本机粘贴也没有 3 秒延迟。

日志里「微信输入法已运行，3 秒后触发跨设备同步」只针对**微信输入法的跨设备粘贴**：它的同步服务启动较慢，所以在其进程运行时会延迟 3 秒再写一次剪贴板，确保这次变更能被它的跨设备通道捕获。这 3 秒是给微信输入法留的窗口，不是系统限制——其他输入法若自带跨设备剪贴板同步，读的是同一个系统剪贴板，同样能同步。

1.2.16 及之前，未安装或未运行微信输入法时**只记日志、不写剪贴板**，地址变化后必须手动点「复制地址」。1.2.17 起改为：微信输入法未运行时**立即写入**剪贴板，并在其日后启动时补发一次以触发跨设备同步；已运行时保留原有 3 秒流程。

状态页交互（1.2.17 起）：点按**地址文字**＝立即复制；点右上**闪电**＝强制重开 adbd（同模式重复点 TLS/TCP 不会重开，这是唯一恢复入口）；「运行记录」标题右侧的刷新图标＝刷新状态与日志。原先的「快捷操作」三个大按钮已移除，能力全部迁移到上述控件。

## 这次改了什么

> 1.0.18 是原仓库 [shirenran/WirelessAdb-LSPosed](https://github.com/shirenran/WirelessAdb-LSPosed) 的最后一个版本，本 fork 从 1.1.0 起继续开发。

| 项 | 1.0.18（legacy） | 1.1.1 |
|---|---|---|
| 框架接口 | Xposed API 82，`assets/xposed_init` + `xposedminversion` | **libxposed API 102**，`META-INF/xposed/java_init.list` + `module.prop` |
| 入口 | `IXposedHookLoadPackage.handleLoadPackage("android")` | `XposedModule.onModuleLoaded` / `onSystemServerStarting` |
| 作用域 | `xposedscope=android`（legacy 侧被反转成 `system`） | `scope.list=system`（system_server；`staticScope=false`，允许在管理器里手动校正） |
| Hook | `XposedBridge.hookAllMethods` + `XposedHelpers` | `hook(Executable).intercept` + 标准反射 + `deoptimize` |
| 日志 | `XposedBridge.log` | `log(priority, tag, msg)`，同时回写状态页 |
| TLS 端口 | 只读 `service.adb.tls.port` 属性 | 属性 → `AdbService.getAdbWirelessPort()` → `dumpsys adb` 三级探测 |
| 兼容性 | 依赖 legacy 兼容层 | 不依赖 legacy 包，适配 LSPosed 2.1.0+ / Android 17 |

针对 ColorOS 17 / Android 17 的加固：

- Hook 入口改为 `onSystemServerStarting` 直接拿 system classloader，AMS 类名多候选 + `SystemServer` 兜底；`mContext` 取不到时按类型扫描整条继承链。
- 一次性环境探测：把 `adb_wifi_enabled`、`service.adb.tls.port`、`init.svc.adbd`、Settings.Global 里所有 adb/wifi 相关项、`AdbService` / `AdbDebuggingManager` / OPlus 扩展类的 wifi 方法签名，以及三条端口探测路径的实测结果写进状态页日志（`[诊断]` 前缀），不臆造未验证的字段。
- 连续回退抑制：Android 17 的 **ADB Wi-Fi 2.0** 会在非信任网络自动关闭无线调试，ColorOS 也可能忽略 `Settings.Global` 写入。60 秒内连续 3 次被回退即暂停自动写回并给出提示，避免与系统策略拉锯。
- TCP 模式增加属性回读校验；IP 获取额外跳过 `198.19.*`（fake-ip VPN）虚拟地址。
- 状态页日志预览从 6 行扩到 12 行，并明确提示「模块未加载」。

### 1.1.1：修复 Android 17 上「端口=-1」

实机验证（一加 15 / PLK110 / ColorOS V17.0.0 / LSPosed 2.2.1）确认 1.1.0 的 API 102 迁移完全生效：

```
[框架] 模块已加载 process=system systemServer=true framework=LSPosed/2.2.1(7939) api=102 capSystem=true
[框架] 已 hook com.android.server.am.ActivityManagerService.systemReady，成功 1/1
[框架] 已取得系统 Context（来源 com.android.server.am.ActivityManagerService）
```

但 `获取地址失败：IP=10.184.1.67，端口=-1` 依旧。根因：Android 17 **不再把 TLS 端口写进 `service.adb.tls.port`**，端口由 `AdbService.getAdbWirelessPort()` 提供（`dumpsys adb` 输出里的 `tls_port=34221` 就是它）。1.1.0 只读属性，所以地址永远拼不出来。

1.1.1 改为三级探测并缓存 5 秒，命中时写 `[端口] TLS 端口=… 来源=…`：

1. `service.adb.tls.port` 系统属性（旧 ROM 兼容）；
2. `ServiceManager.getService("adb")` → `IAdbManager$Stub.asInterface` → 反射 `getAdbWirelessPort()`；
3. 兜底执行 `dumpsys adb`，正则解析 `tls_port=(\d+)`（应对 OEM 改写 AdbService）。

实机验证（2026-11-10，一加 15 / ColorOS V17.0.0 / LSPosed 2.2.1，重启后自动重连）：

```
[诊断] TLS 端口来源探测：属性=-1, AdbService=0, dumpsys=-1     ← 开机早期端口尚未分配
[端口] TLS 端口=36559，来源：AdbService.getAdbWirelessPort
无线 ADB 地址已就绪，等待微信输入法启动后复制：10.184.1.67:36559
```

同一时刻 `dumpsys adb` 的 `adb_wifi.tls_port=36559`，`adb connect 10.184.1.67:36559` 成功 —— 三条证据一致。

诊断还确认 ColorOS 17 在 `AdbService` 上加了定制方法 `disableADBdWifi()` / `enableADBdWifi()` / `isAdbWifiQrSupported()` / `isAdbWifiSupported()`；当前通过 `Settings.Global.adb_wifi_enabled` 写入已能正常开启，暂不调用这些 OEM 私有方法。

### 1.2.12：修复按钮按下时浮现矩形方块

`android:foreground` 用了系统属性 `?android:attr/selectableItemBackground`，在 ColorOS 17 上解析出的是矩形遮罩波纹，与圆角背景形状不匹配，溢出成了「方块」。全部换成本模块自带的带正确圆角遮罩的 `<ripple>` 前景（闪电 `fl_ripple_circle`、模式按钮 `fl_ripple_pill`、快捷操作 `fl_ripple_round_sm`），高亮配色随选中态切换。

### 1.2.13：修复无谓重启 adbd 导致的无线 ADB 断连

`enableTcp` / `enableTls` / `onSetMode` / TCP 轮询重试四处都是无条件重启 adbd，哪怕配置没变 —— adbd 一重启连接必断、TLS 端口还会被重新随机分配。改为幂等：目标状态已生效就只刷新地址，不碰 adbd；端口是否在监听用 Socket 连 `127.0.0.1:<port>` 实测，不只看属性值。

跨模式切换（TLS ↔ TCP）仍会断连，这是平台必然 —— 改为明确提示「端口将重置，请用新地址重连」并自动刷新新地址。顺带修了切换模式后短暂显示旧端口的 bug。

### 1.2.17：地址完整显示、界面精简、自动复制不再依赖输入法

- **修复端口被截断**：地址原先挤在闪电按钮左侧，单行 23sp 放不下 `IP:端口`（截图里 `192.168.100.222:5555` 只显示到 `:55`）。改为地址独占整卡一行，并启用 `autoSizeTextType`（23sp → 最小 14sp）兜底 5 位 TLS 端口与更长 IP。
- **移除「快捷操作」区块**：删掉「立即应用」「复制地址」「刷新记录」三个 64dp 大按钮及 `include_action_button.xml`，能力迁移到已有控件——点地址＝复制，闪电＝强制重开 adbd，日志标题右侧刷新图标＝刷新。功能零丢失，页面少一整块。
- **自动复制不再绑死微信输入法**：此前 `waitForWeType()` 在 `isWeTypeRunning()` 为假时只记日志、不写剪贴板。改为立即写入系统剪贴板，并保留 `pendingAddress`，待微信输入法启动后补发一次以触发跨设备同步；已运行时维持原有 3 秒流程。日志文案同步改准确。
- 详见上方[剪贴板与输入法](#剪贴板与输入法常见疑问)。

### 1.2.16：桌面图标图形缩小 20%

图标内容不变，仅把螺旋在 108dp 画布中的占比从 76% 降到 **60.8%**（`CONTENT_SCALE`），四周留白更宽，视觉上更清爽。背景层是整幅渐变、不参与缩放，因此背景观感完全一致。

### 1.2.15：桌面图标换为绿底白螺旋

桌面图标此前是「蓝渐变 + Wi-Fi 信号弧线」（自 1.2.7 起）。本次换成 FLClash 风格的**绿底白螺旋**，采用 Android 官方模板布局（位图放 `mipmap-*dpi`，`adaptive-icon` 分 background / foreground / monochrome 三层）：

- **背景**：源图拟合的绿→蓝对角渐变（`#284252 → #376049 → #3B7538`），保留原图暗角。
- **前景**：白色陶瓷螺旋 + 投影，保留原图明暗质感；投影与螺旋烘进同一张 RGBA，任何机型上同步缩放不错位。
- **monochrome 层**（Android 13+ 主题图标）：只留螺旋轮廓，由系统按壁纸取色着色。
- 图形占 108dp 画布的 60.8%（1.2.16 起由 76% 缩小 20%），保证 launcher 的 72dp 掩码下外圈白环不被裁切。

图标资源与生成脚本可复现（`tools/gen_launcher_icon.py`），历次造型的归档与回滚方法见 [桌面图标造型记录](docs/桌面图标造型记录.md)。

### 1.2.14：模块加载状态改用独立标记，不再依赖日志文本

原先判「模块已加载」靠 grep 运行日志，而日志会滚动截断（16KB 上限）也会跨重启保留，两个方向都会误报。改为独立的 `Settings.Global` 标记 + `boot_count` 锚定：`boot_count` 不一致即视为上次开机、判定未加载，同一次开机内写入的标记一直有效，随开机自动重置。新增 `SysReflect.systemContext()` 让标记在 AMS 未 ready 时也能写入。顺带修了冷启动首帧硬编码「已准备就绪」。

## 环境要求

- **LSPosed ≥ 2.1.0**（提供 libxposed API 102、兼容 Android 17）。低于该版本不提供 API 102，本模块不会加载。
- 已 root（KernelSU/Zygisk Next 等）且 LSPosed 能注入 system_server。
- 目标机型：一加 15 / ColorOS 17（Android 17 / API 37）；其它 Android 11+ 设备理论上同样可用。

> 注意：API 102 模块与 legacy API 82 模块在 classloader 层互斥。本版本**不能**与旧版共存，升级时先卸载 1.0.18（若签名冲突则必须卸载）。

## 安装

1. 安装 Release APK（若装过 1.0.18 且提示签名冲突，先卸载旧版）。
2. 在 **LSPosed** 中启用本模块。
3. 作用域确认为 **系统框架 / system**（`staticScope=false`，如管理器里未勾选可手动加上；升级/卸载重装后启用状态与作用域会被清空，需重新勾选）。
4. 重启手机并完成首次解锁。
5. 打开「无线 ADB 自启」状态页，看到 `[框架] 模块已加载` 即代表 hook 生效。

> 仅建议在可信局域网使用。切换 TCP 会重启 adbd，当前 ADB 会话可能断开。重启后 TLS 端口会重新随机，电脑端可用 `adb mdns services` 自动发现新地址后再 `adb connect`。

## ColorOS 17 / Android 17 必查项

即使模块已加载，下列系统策略仍可能让无线 ADB 被关闭：

1. **开发者选项 → 关闭「权限监控」/「禁止系统优化」**（ColorOS 对 ADB 的已知限制）。
2. **无线调试 → 把当前 Wi-Fi 标记为「始终允许」**。ADB Wi-Fi 2.0 以 SSID+BSSID 判定可信网络，陌生/公共 Wi-Fi 会自动关闭无线调试。
3. 确认 **USB 调试** 已开启；TLS 模式下电脑端用 `platform-tools ≥ 37.0.0` 的 `adb pair` / `adb connect`。
4. 状态页若出现 `[警告] 60 秒内连续 3 次被系统回退`，说明是策略性关闭，模块已停止写回，需按上面两项处理后再点「立即应用」。

## 排障

| 现象 | 检查 |
|---|---|
| 状态页显示「模块未加载」 | LSPosed 版本是否 ≥ 2.1.0；模块是否启用；作用域是否含系统框架；是否被隐藏/root 检测模块干扰 |
| 有 `[框架] 模块已加载` 但无 `[框架] 已取得系统 Context` | 查看 `[警告]` 里的候选字段与 LSPosed 日志，反馈给维护者 |
| `获取地址失败…端口=-1` | 看 `[诊断] TLS 端口来源探测`：若 `AdbService` 与 `dumpsys` 均为 -1，说明该 ROM 改了 AIDL 或禁止 system_server 执行 dumpsys，把该行回传 |
| `adb_wifi_enabled` 写入后回读不为 1 | ColorOS 忽略写入，走可信网络 + 权限监控两项设置 |
| TCP 5555 连不上 | 看 `[诊断]` 里 `tcp.port` 回读值与 `adbd` 状态；Android 17 重写了 adbd，必要时改用 TLS 模式 |

LSPosed 的详细日志 tag 为 `WirelessAdbAutoStart`，也可直接读 `/data/adb/lspd/log/modules_*.log`。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

需要 Android SDK（`local.properties` 的 `sdk.dir` 或 `ANDROID_HOME`）与 JDK 17+。当前 `compileSdk/targetSdk = 36`：framework 侧全部走反射，不依赖编译期 Android 17 API，无需 SDK platform 37 即可构建。

验证 APK 声明是否打进包：

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep META-INF/xposed
```

应看到 `module.prop`、`java_init.list`、`scope.list` 三个文件，且不应再出现 `assets/xposed_init` 与 `xposedminversion`。

## 致谢

本项目源自 [shirenran/WirelessAdb-LSPosed](https://github.com/shirenran/WirelessAdb-LSPosed)（原作者 shirenran，MIT 协议），最初版本为 1.0.18。

本 fork 在原作基础上做了以下主要改动，均已实机验证（一加 15 / ColorOS V17 / Android 17 / LSPosed 2.2.1）：

- **迁移到 libxposed API 102**：移除 legacy Xposed 82 声明，适配 LSPosed 2.1.0+，可直接注入 `system_server`。
- **修复 Android 17 上 TLS 端口读取失败**：Android 17 不再把端口写进 `service.adb.tls.port`，改为「系统属性 → `AdbService.getAdbWirelessPort()` → `dumpsys adb` 正则」三级探测，解决地址端口显示 `-1`。
- **修复无谓重启 adbd 导致的无线 ADB 断连**：目标配置未变时不再重启 adbd，端口是否在监听用 Socket 实测而非只看属性值。
- **模块加载状态判定改用独立标记**：以 `Settings.Global` + `boot_count` 锚定，不再依赖会滚动截断、且跨重启残留的日志文本。
- **状态页改版**：单页 Dashboard、统一线性图标、界面文案中文化、按钮按压反馈改为带圆角遮罩的自定义波纹。
- **桌面图标改版**：绿底白螺旋（FLClash 风格），新增 Android 13+ 主题图标层。

感谢原作者 shirenran 的开源贡献。如发现上游仍存在的问题，欢迎在原仓库反馈。

## 许可

MIT

原作 `Copyright (c) 2026 shirenran` 的版权声明完整保留于 [LICENSE](LICENSE)。本 fork 的改动部分以 MIT 协议发布。

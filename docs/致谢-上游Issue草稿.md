## 背景

我从 1.0.18 fork 了这个仓库并继续维护（[88558639/WirelessAdb](https://github.com/88558639/WirelessAdb)），目前已迭代到 1.2.15，适配了一加 15 / ColorOS V17 / Android 17。

上一轮见仓库停在 2026-07-29（1.0.18），而 Android 17 上原版有两个**阻断性**问题——模块在 LSPosed 2.1.0+ 下根本不加载，且即使加载也拿不到端口。我把这几处的改动整理出来回馈上游，如果对项目有价值，欢迎合并。

## 一、迁移到 libxposed API 102

LSPosed 2.1.0+ 起 modern API 成为主流，原版的 legacy 声明（`assets/xposed_init` + `xposedminversion`）在部分环境下不再被注入。

| 项 | 1.0.18 | 迁移后 |
|---|---|---|
| 框架接口 | Xposed API 82 | libxposed API 102 |
| 入口 | `IXposedHookLoadPackage.handleLoadPackage` | `XposedModule.onModuleLoaded` / `onSystemServerStarting` |
| 作用域 | `assets/xposed_scope` | `scope.list=system`（`staticScope=false`） |
| Hook | `XposedBridge.hookAllMethods` | `hook(Executable).intercept` + 标准反射 + `deoptimize` |
| 日志 | `XposedBridge.log` | `log(priority, tag, msg)`，同时回写状态页 |

打包后应能看到 `module.prop` / `java_init.list` / `scope.list` 三个文件，且不再出现 `assets/xposed_init`。

## 二、Android 17 上 TLS 端口读取失败（关键修复）

**现象**：状态页一直显示 `获取地址失败：IP=10.184.1.67，端口=-1`，电脑端 `adb connect` 无从下手。

**根因**：Android 17 **不再把端口写进 `service.adb.tls.port`**（实测该属性恒为 `-1`）。端口改由 `AdbService.getAdbWirelessPort()` 提供，只出现在 `dumpsys adb` 输出的 `adb_wifi.tls_port=36559` 里。原版只读系统属性，所以永远拼不出地址。

**修复**：改为三级探测，命中时记录来源：

1. 系统属性 `service.adb.tls.port`（兼容旧 ROM）；
2. `ServiceManager.getService("adb")` → `IAdbManager$Stub.asInterface` → 反射 `getAdbWirelessPort()`；
3. 兜底执行 `dumpsys adb`，正则解析 `tls_port=(\d+)`（应对 OEM 改写 AdbService 的情况）。

结果缓存 5 秒，日志形如：

```
[诊断] TLS 端口来源探测：属性=-1, AdbService=0, dumpsys=-1     ← 开机早期端口尚未分配
[端口] TLS 端口=36559，来源：AdbService.getAdbWirelessPort
无线 ADB 地址已就绪，等待微信输入法启动后复制：10.184.1.67:36559
```

同一时刻 `dumpsys adb` 的 `adb_wifi.tls_port=36559`，`adb connect 10.184.1.67:36559` 成功——三条证据一致。

## 三、无谓重启 adbd 导致无线 ADB 断连

**现象**：点「立即应用」或重复点击同一个模式按钮，无线 ADB 立刻断开、端口也变了，电脑端还得重新找端口。

**根因**：几条路径都是**无条件** `ctl.restart adbd`——哪怕配置一个字都没变。adbd 一重启，当前连接必断，且 **TLS 端口会被重新随机分配**（实测一轮内换了 36559 → 38015 → 39929 → 37939 → 35079 → 43589 六个端口）。

**修复**：改为幂等——先判定「目标状态是否已生效」，生效了就只刷新地址，不碰 adbd：

| 位置 | 判定条件 | 生效时的行为 |
|---|---|---|
| `enableTcp` | 端口属性 == 目标 && `init.svc.adbd == running` && 本机该端口可连 | 跳过重启 |
| `enableTls` | `adb_wifi_enabled == 1` && TLS 端口可读 && 本机该端口可连 | 跳过写回 |
| `onSetMode` | 模式与端口都没变 | 只刷新地址 |
| TCP 轮询重试 | 端口属性已就位 | 跳过重启 |

端口是否真的在监听用 `Socket` 连 `127.0.0.1:<port>` 实测，不只看属性值（属性可能残留而服务已没起）。

> 跨模式切换（TLS ↔ TCP）仍然会断连，这是平台必然——两种监听方式不同，adbd 必须重启才能换，端口也随之改变。上游这边只在 UI 上做了明确提示。

## 四、模块加载状态判定

原先判「模块已加载」靠 grep 运行日志里的 `[框架] 模块已加载`，有两个方向都会误报：日志会滚动截断（`Settings.Global` 上限 16KB），跑久了加载记录被挤掉，模块正常却报「模块未加载」；日志又跨重启保留，上次开机的加载记录还在，模块没加载反而报「已准备就绪」。

改为独立 `Settings.Global` 标记 + `boot_count` 锚定：`boot_count` 不一致即视为上次开机、判定未加载；同一次开机内写入的标记一直有效，随开机自动重置。

## 五、其他

- ColorOS 17 加固：AMS 类名多候选 + `SystemServer` 兜底；`mContext` 取不到时按类型扫描整条继承链；60 秒内连续 3 次被系统回退即暂停自动写回，避免与系统策略拉锯。
- TCP 模式增加属性回读校验；IP 获取额外跳过 `198.19.*`（fake-ip VPN）。
- 状态页改版为单页 Dashboard，统一线性图标，界面文案中文化。
- 修复按钮按下时浮现矩形方块（`?android:attr/selectableItemBackground` 在 ColorOS 上解析出的是矩形遮罩波纹，与圆角背景形状不匹配）。

## 回贡意向

上面每一项我都可以整理成独立 PR：

- **PR A**：API 102 迁移 + Android 17 TLS 端口三级探测（合并后原版在 Android 17 上就能直接用）
- **PR B**：adbd 重启幂等化
- **PR C**：模块加载状态判定

我的 fork 里有完整提交历史可参考：https://github.com/88558639/WirelessAdb

也注意到上游有 5 个未合并的 PR（i18n、SELinux TCP 修复、安全加固、图标重绘、在线客户端监控），如果其中有需要的改动想合进来，我可以基于我的分支帮忙适配。

最后感谢原作者 shirenran 的开源贡献，这个项目在我这儿一直在被使用。

—— 附：本仓库在 LSPosed 2.2.1 / Android 17 / 一加 15 / ColorOS V17.0.0 上实机验证。

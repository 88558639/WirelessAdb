package dev.wirelessadb.autostart;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/** libxposed API 102 入口；作用域 system（system_server）。 */
public final class WirelessAdbHook extends XposedModule {
    private static final String TAG = "WirelessAdbAutoStart";
    private static final String ACTION_LOG = "dev.wirelessadb.autostart.LOG_EVENT";
    private static final String ACTION_REQUEST_COPY = "dev.wirelessadb.autostart.REQUEST_COPY";
    private static final String ACTION_SET_MODE = "dev.wirelessadb.autostart.SET_MODE";
    private static final String ACTION_APPLY = "dev.wirelessadb.autostart.APPLY";
    private static final String LOG_RECEIVER = "dev.wirelessadb.autostart.LogReceiver";
    private static final String MODULE_PACKAGE = "dev.wirelessadb.autostart";
    private static final String WETYPE_PACKAGE = "com.tencent.wetype";
    private static final long REENABLE_COOLDOWN_MS = 8_000L;
    private static final long WIFI_RESTORE_DELAY_MS = 2_500L;
    private static final long ADDRESS_POLL_INTERVAL_MS = 2_500L;
    private static final int ADDRESS_POLL_MAX = 12;

    private static WirelessAdbHook instance;
    private static final List<HookHandle> hookHandles = new ArrayList<>();
    private static final List<String> pendingLogs = new ArrayList<>();

    private static Context systemContext;
    private static AdbController controller;
    private static ClassLoader systemClassLoader;
    private static Handler handler;
    private static boolean armed;
    private static boolean keepersRegistered;
    private static boolean userUnlocked;
    private static String pendingAddress;
    private static boolean copyScheduled;
    private static String lastCopiedAddress;
    private static long lastEnableAt;
    private static final AtomicBoolean enableInFlight = new AtomicBoolean(false);

    private static String cachedMode = AdbModeConfig.MODE_TLS;
    private static int cachedTcpPort = AdbModeConfig.DEFAULT_TCP_PORT;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        instance = this;
        boolean capSystem = (getFrameworkProperties() & PROP_CAP_SYSTEM) != 0;
        logLine("[框架] 模块已加载 process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + "/" + getFrameworkVersion()
                + "(" + getFrameworkVersionCode() + ") api=" + getApiVersion()
                + " capSystem=" + capSystem);
        if (!capSystem) {
            logLine("[警告] 当前框架不支持 system_server hook；请确认 LSPosed 版本 >= 2.1.0 且作用域勾选系统框架");
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        instance = this;
        final ClassLoader loader = param.getClassLoader();
        systemClassLoader = loader;
        logLine("[框架] system_server 启动，classLoader=" + loader.getClass().getName());
        installAmsHook(loader);
        installWeTypeHook(loader);
    }

    private void installAmsHook(ClassLoader loader) {
        String[] candidates = {
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.OplusActivityManagerService",
                "com.android.server.am.ActivityManagerServiceEx"
        };
        for (String name : candidates) {
            Class<?> ams = SysReflect.findClass(loader, name);
            if (ams == null) continue;
            List<Method> methods = SysReflect.findMethods(ams, "systemReady");
            if (methods.isEmpty()) {
                logLine("[框架] " + name + " 无 systemReady，尝试下一个候选");
                continue;
            }
            int ok = 0;
            for (Method method : methods) {
                try {
                    try {
                        deoptimize(method);
                    } catch (Throwable ignored) { }
                    HookHandle handle = hook(method)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    onSystemReady(chain.getThisObject());
                                } catch (Throwable t) {
                                    logLine("systemReady 后续处理失败：" + shortError(t));
                                }
                                return result;
                            });
                    hookHandles.add(handle);
                    ok++;
                } catch (Throwable t) {
                    logLine("[框架] hook " + name + ".systemReady 失败：" + shortError(t));
                }
            }
            logLine("[框架] 已 hook " + name + ".systemReady，成功 " + ok + "/" + methods.size());
            if (ok > 0) return;
        }
        logLine("[警告] AMS.systemReady 全部失败，尝试 SystemServer 兜底");
        installSystemServerFallback(loader);
    }

    private void installSystemServerFallback(ClassLoader loader) {
        Class<?> server = SysReflect.findClass(loader, "com.android.server.SystemServer");
        if (server == null) {
            logLine("[警告] SystemServer 类缺失，兜底不可用；请把状态页与 LSPosed 日志一并反馈");
            return;
        }
        List<Method> methods = new ArrayList<>();
        methods.addAll(SysReflect.findMethods(server, "run"));
        methods.addAll(SysReflect.findMethods(server, "startOtherServices"));
        int ok = 0;
        for (Method method : methods) {
            try {
                HookHandle handle = hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                scheduleContextRecovery(chain.getThisObject(), loader);
                            } catch (Throwable t) {
                                logLine("兜底恢复失败：" + shortError(t));
                            }
                            return result;
                        });
                hookHandles.add(handle);
                ok++;
            } catch (Throwable t) {
                logLine("[框架] hook SystemServer." + method.getName() + " 失败：" + shortError(t));
            }
        }
        logLine("[框架] SystemServer 兜底 hook 成功 " + ok + "/" + methods.size());
    }

    /** 只在 AMS hook 全部失效时使用：从静态实例里找回系统 Context。 */
    private static void scheduleContextRecovery(Object systemServer, final ClassLoader loader) {
        if (armed) return;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        Runnable retry = new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                if (armed || attempts++ > 10) return;
                Object target = findAmsStaticInstance(loader);
                if (target == null) target = systemServer;
                Context ctx = SysReflect.findContext(target);
                if (ctx != null) {
                    onSystemReady(target);
                    return;
                }
                handler.postDelayed(this, 2_000L);
            }
        };
        handler.postDelayed(retry, 2_000L);
    }

    private static Object findAmsStaticInstance(ClassLoader loader) {
        Class<?> ams = SysReflect.findClass(loader, "com.android.server.am.ActivityManagerService");
        if (ams == null) return null;
        for (Field f : ams.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Object.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value != null && value.getClass().getName().contains("ActivityManagerService")) {
                    return value;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void onSystemReady(Object ams) {
        if (armed) return;
        Context ctx = SysReflect.findContext(ams);
        if (ctx == null) {
            logLine("[警告] 未能从 " + (ams == null ? "null" : ams.getClass().getName())
                    + " 取得 Context；候选字段：" + SysReflect.describeFields(
                    ams == null ? null : ams.getClass(), "context"));
            return;
        }
        armed = true;
        handler = new Handler(Looper.getMainLooper());
        systemContext = ctx;
        controller = new AdbController(ctx, WirelessAdbHook::logLine);
        logLine("[框架] 已取得系统 Context（来源 " + ams.getClass().getName() + "）");
        flushPendingLogs();
        controller.probe(systemClassLoader != null ? systemClassLoader : ams.getClass().getClassLoader());
        registerCommandReceivers();
        waitForUnlock();
    }

    private void installWeTypeHook(ClassLoader loader) {
        Class<?> processList = SysReflect.findClass(loader, "com.android.server.am.ProcessList");
        if (processList == null) {
            logLine("[框架] ProcessList 缺失，跳过微信输入法进程监听（不影响无线 ADB 主功能）");
            return;
        }
        int ok = 0;
        for (Method method : SysReflect.findMethods(processList, "handleProcessStartedLocked")) {
            try {
                HookHandle handle = hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                if (pendingAddress != null && !copyScheduled) {
                                    for (Object arg : chain.getArgs()) {
                                        if (isWeTypeProcess(arg)) {
                                            scheduleCopyAfterWeTypeReady("检测到微信输入法进程启动");
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        });
                hookHandles.add(handle);
                ok++;
            } catch (Throwable t) {
                logLine("[框架] hook ProcessList 失败：" + shortError(t));
            }
        }
        logLine("[框架] 微信输入法进程监听就绪 " + ok + " 个 hook");
    }

    private static boolean isWeTypeProcess(Object value) {
        if (value == null || !value.getClass().getName().endsWith(".ProcessRecord")) return false;
        Object info = SysReflect.getField(value, "info");
        if (info != null) {
            Object pkg = SysReflect.getField(info, "packageName");
            if (WETYPE_PACKAGE.equals(pkg)) return true;
        }
        Object name = SysReflect.getField(value, "processName");
        return name != null && name.toString().startsWith(WETYPE_PACKAGE);
    }

    private static void registerCommandReceivers() {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    handler.post(() -> {
                        if (ACTION_REQUEST_COPY.equals(action)) {
                            manualCopyAddress();
                        } else if (ACTION_SET_MODE.equals(action)) {
                            onSetMode(intent);
                        } else if (ACTION_APPLY.equals(action)) {
                            if (controller != null) controller.clearReverts();
                            reloadConfig();
                            enableAdb("界面请求立即应用", true);
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_REQUEST_COPY);
            filter.addAction(ACTION_SET_MODE);
            filter.addAction(ACTION_APPLY);
            systemContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            logLine("命令接收器就绪（复制 / 切换模式 / 立即应用）");
        } catch (Throwable t) {
            logLine("命令接收器注册失败：" + shortError(t));
        }
    }

    private static void onSetMode(Intent intent) {
        String mode = intent.getStringExtra("mode");
        int port = intent.getIntExtra("port", cachedTcpPort);
        boolean changed = false;
        if (mode != null && !mode.isEmpty()) {
            String target = AdbModeConfig.MODE_TCP.equals(mode)
                    ? AdbModeConfig.MODE_TCP : AdbModeConfig.MODE_TLS;
            if (port < 1 || port > 65535) port = cachedTcpPort;
            changed = !target.equals(cachedMode) || port != cachedTcpPort;
            cachedMode = target;
            if (port >= 1 && port <= 65535) cachedTcpPort = port;
            if (changed) {
                AdbModeConfig.setModeAsSystem(systemContext, cachedMode, cachedTcpPort);
                try {
                    Context app = moduleContext();
                    if (app != null) AdbModeConfig.setMode(app, cachedMode, cachedTcpPort);
                } catch (Throwable ignored) { }
            }
        } else {
            reloadConfig();
        }
        if (!changed) {
            // 配置没变：绝不重启 adbd，否则会白白打断正在使用的 ADB 连接。
            logLine("模式未变（" + AdbModeConfig.modeLabel(cachedMode)
                    + "），跳过重启，仅刷新地址");
            if (controller != null) controller.clearReverts();
            checkAddress("模式未变", 0, false);
            return;
        }
        lastCopiedAddress = null;
        lastEnableAt = 0L;
        if (controller != null) controller.clearReverts();
        logLine("切换模式 → " + AdbModeConfig.modeLabel(cachedMode)
                + (AdbModeConfig.MODE_TCP.equals(cachedMode) ? ("，端口 " + cachedTcpPort) : "")
                + "（adbd 将重启，端口与当前连接都会变）");
        if (userUnlocked) enableAdb("切换模式", true);
        else logLine("模式已保存，解锁后生效");
    }

    private static void reloadConfig() {
        try {
            String globalMode = null;
            try {
                globalMode = Settings.Global.getString(systemContext.getContentResolver(), AdbModeConfig.GLOBAL_MODE);
            } catch (Throwable ignored) { }
            if (AdbModeConfig.MODE_TCP.equals(globalMode) || AdbModeConfig.MODE_TLS.equals(globalMode)) {
                cachedMode = globalMode;
            } else {
                Context app = moduleContext();
                if (app != null) cachedMode = AdbModeConfig.getMode(app);
            }
            int globalPort = -1;
            try {
                globalPort = Settings.Global.getInt(systemContext.getContentResolver(),
                        AdbModeConfig.GLOBAL_TCP_PORT, -1);
            } catch (Throwable ignored) { }
            if (globalPort >= 1 && globalPort <= 65535) {
                cachedTcpPort = globalPort;
            } else {
                Context app = moduleContext();
                if (app != null) cachedTcpPort = AdbModeConfig.getTcpPort(app);
            }
            logLine("当前模式：" + AdbModeConfig.modeLabel(cachedMode)
                    + (AdbModeConfig.MODE_TCP.equals(cachedMode) ? (" @" + cachedTcpPort) : ""));
        } catch (Throwable t) {
            logLine("读取配置失败：" + shortError(t));
        }
    }

    private static Context moduleContext() {
        try {
            return systemContext.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void manualCopyAddress() {
        reloadConfig();
        String ip = findWifiIpv4();
        int port = resolveActivePort();
        if (ip == null || port <= 0) {
            logLine("手动复制失败：mode=" + cachedMode + ", IP=" + ip + ", port=" + port);
            return;
        }
        pendingAddress = null;
        copyScheduled = false;
        String address = ip + ":" + port;
        logLine("手动复制（立即）：" + address + " [" + cachedMode + "]");
        copyAddress(address);
    }

    private static void waitForUnlock() {
        try {
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onUserUnlocked("收到 USER_UNLOCKED");
                }
            }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            UserManager users = systemContext.getSystemService(UserManager.class);
            if (users != null && users.isUserUnlocked()) onUserUnlocked("系统已解锁");
            else logLine("系统已启动，等待首次解锁");
        } catch (Throwable t) {
            logLine("监听解锁失败：" + shortError(t));
        }
    }

    private static void onUserUnlocked(String reason) {
        if (userUnlocked) return;
        userUnlocked = true;
        reloadConfig();
        logLine(reason + "，开始保持 ADB（" + AdbModeConfig.modeLabel(cachedMode) + "）");
        registerKeepers();
        enableAdb("首次解锁", true);
    }

    private static void registerKeepers() {
        if (keepersRegistered || systemContext == null || handler == null) return;
        keepersRegistered = true;
        registerAdbWifiObserver();
        registerWifiCallback();
        registerScreenOnReceiver();
    }

    private static void registerAdbWifiObserver() {
        try {
            Uri uri = Settings.Global.getUriFor(AdbController.SETTING_ADB_WIFI);
            systemContext.getContentResolver().registerContentObserver(uri, false, new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    handler.post(() -> {
                        if (!AdbModeConfig.MODE_TLS.equals(cachedMode)) return;
                        int value = adbWifiEnabled();
                        if (value == 1) {
                            if (controller != null) controller.clearReverts();
                            checkAddress("开关变为开启", 0, false);
                            return;
                        }
                        if (value == 0) {
                            logLine("检测到无线调试被关闭，准备重新开启");
                            if (controller != null && controller.shouldSuppressReenable("ContentObserver")) return;
                            enableAdb("ContentObserver", false);
                        }
                    });
                }
            });
            logLine("已注册 adb_wifi_enabled 监听（仅 TLS 模式生效）");
        } catch (Throwable t) {
            logLine("注册无线调试开关监听失败：" + shortError(t));
        }
    }

    private static void registerWifiCallback() {
        try {
            ConnectivityManager cm = systemContext.getSystemService(ConnectivityManager.class);
            if (cm == null) throw new IllegalStateException("ConnectivityManager 不可用");
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.postDelayed(() -> {
                        if (!userUnlocked) return;
                        logLine("Wi-Fi 可用，检查 ADB（" + cachedMode + "）");
                        if (controller != null) controller.clearReverts();
                        keepAlive("Wi-Fi 恢复");
                    }, WIFI_RESTORE_DELAY_MS);
                }

                @Override
                public void onLost(Network network) {
                    handler.post(() -> logLine("Wi-Fi 断开，等待恢复后再保持 ADB"));
                }
            });
            logLine("已注册 Wi-Fi 网络回调");
        } catch (Throwable t) {
            logLine("注册 Wi-Fi 回调失败：" + shortError(t));
        }
    }

    private static void registerScreenOnReceiver() {
        try {
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!userUnlocked) return;
                    handler.postDelayed(() -> keepAlive("亮屏检查"), 1_000L);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON));
            logLine("已注册亮屏检查");
        } catch (Throwable t) {
            logLine("注册亮屏检查失败：" + shortError(t));
        }
    }

    private static void keepAlive(String reason) {
        if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
            if (!isTcpPortReady(cachedTcpPort)) {
                enableAdb(reason, false);
            } else {
                checkAddress(reason, 0, false);
            }
            return;
        }
        if (adbWifiEnabled() != 1) {
            enableAdb(reason, false);
        } else {
            checkAddress(reason, 0, false);
        }
    }

    private static void enableAdb(String reason, boolean forceCopy) {
        if (controller == null || systemContext == null || handler == null) return;
        long now = System.currentTimeMillis();
        if (!forceCopy && now - lastEnableAt < REENABLE_COOLDOWN_MS) {
            logLine("跳过重复开启（冷却中，原因：" + reason + "）");
            return;
        }
        if (!enableInFlight.compareAndSet(false, true)) {
            logLine("开启进行中，跳过：" + reason);
            return;
        }
        lastEnableAt = now;
        try {
            if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
                controller.enableTcp(cachedTcpPort, reason);
            } else {
                controller.enableTls(reason);
            }
            checkAddress(reason, 0, forceCopy);
        } catch (Throwable t) {
            logLine("开启 ADB 失败：" + shortError(t));
        } finally {
            enableInFlight.set(false);
        }
    }

    private static void checkAddress(String reason, int attempt, boolean forceCopy) {
        handler.postDelayed(() -> {
            if (AdbModeConfig.MODE_TLS.equals(cachedMode)) {
                if (adbWifiEnabled() != 1 && attempt == 0) {
                    enableAdb(reason + "-二次确认", forceCopy);
                    return;
                }
            } else if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
                if (!isTcpPortReady(cachedTcpPort) && attempt == 0) {
                    try {
                        SysReflect.setSystemProperty(AdbController.PROP_TCP_PORT, String.valueOf(cachedTcpPort));
                    } catch (Throwable ignored) { }
                }
            }

            String ip = findWifiIpv4();
            int port = resolveActivePort();
            if (ip != null && port > 0) {
                waitForWeType(ip + ":" + port, forceCopy);
            } else if (attempt < ADDRESS_POLL_MAX) {
                if (attempt == 0 || attempt == 4 || attempt == 8) {
                    logLine("等待地址就绪（" + reason + "/" + cachedMode + "）：IP=" + ip
                            + "，端口=" + port + "，第 " + (attempt + 1) + " 次");
                }
                if (AdbModeConfig.MODE_TLS.equals(cachedMode) && (attempt == 3 || attempt == 7)) {
                    try {
                        Settings.Global.putInt(systemContext.getContentResolver(),
                                AdbController.SETTING_ADB_WIFI, 1);
                    } catch (Throwable ignored) { }
                }
                if (AdbModeConfig.MODE_TCP.equals(cachedMode) && (attempt == 3 || attempt == 7)) {
                    // 只有端口属性没就位时才重启；已就位再重启只会白断一次连接。
                    int prop = SysReflect.getSystemPropertyInt(AdbController.PROP_TCP_PORT, -1);
                    if (prop != cachedTcpPort) {
                        try {
                            SysReflect.setSystemProperty(AdbController.PROP_TCP_PORT,
                                    String.valueOf(cachedTcpPort));
                        } catch (Throwable ignored) { }
                        if (controller != null) controller.restartAdbd("TCP 轮询重试");
                    } else {
                        logLine("TCP 端口属性已就位（" + cachedTcpPort + "），跳过轮询重启");
                    }
                }
                checkAddress(reason, attempt + 1, forceCopy);
            } else {
                logLine("获取地址失败（" + reason + "/" + cachedMode + "）：IP=" + ip + "，端口=" + port);
            }
        }, attempt == 0 ? 1_500L : ADDRESS_POLL_INTERVAL_MS);
    }

    private static int resolveActivePort() {
        if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
            int prop = SysReflect.getSystemPropertyInt(AdbController.PROP_TCP_PORT, -1);
            if (prop > 0) return prop;
            return cachedTcpPort > 0 ? cachedTcpPort : -1;
        }
        return controller == null ? -1 : controller.tlsPort();
    }

    private static boolean isTcpPortReady(int expected) {
        return controller != null && controller.tcpPortReady(expected);
    }

    private static int adbWifiEnabled() {
        return controller == null ? -1 : controller.adbWifiEnabled();
    }

    private static void waitForWeType(String address, boolean forceCopy) {
        if (!forceCopy && address.equals(lastCopiedAddress)) return;
        if (address.equals(pendingAddress) && copyScheduled) return;
        pendingAddress = address;
        if (isWeTypeRunning()) {
            scheduleCopyAfterWeTypeReady(forceCopy ? "手动/强制复制" : "微信输入法已经运行");
        } else {
            logLine("无线 ADB 地址已就绪，等待微信输入法启动后复制：" + address);
        }
    }

    private static boolean isWeTypeRunning() {
        try {
            ActivityManager manager = systemContext.getSystemService(ActivityManager.class);
            if (manager == null || manager.getRunningAppProcesses() == null) return false;
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.processName != null && process.processName.startsWith(WETYPE_PACKAGE)) return true;
                if (process.pkgList != null) {
                    for (String pkg : process.pkgList) if (WETYPE_PACKAGE.equals(pkg)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void scheduleCopyAfterWeTypeReady(String reason) {
        if (pendingAddress == null || copyScheduled) return;
        copyScheduled = true;
        logLine(reason + "，等待 3 秒初始化跨设备粘贴服务");
        handler.postDelayed(() -> {
            String address = pendingAddress;
            pendingAddress = null;
            copyScheduled = false;
            if (address != null) copyAddress(address);
        }, 3000L);
    }

    private static void copyAddress(String address) {
        try {
            ClipboardManager clipboard = systemContext.getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("剪贴板服务不可用");
            clipboard.setPrimaryClip(ClipData.newPlainText("无线 ADB 地址", address));
            lastCopiedAddress = address;
            logLine("已复制无线 ADB 地址：" + address);
        } catch (Throwable t) {
            logLine("复制地址失败：" + shortError(t));
        }
    }

    private static String findWifiIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                if (!(name.startsWith("wlan") || name.startsWith("wifi"))) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (host == null) continue;
                        if (host.startsWith("172.19.") || host.startsWith("198.19.")) continue;
                        return host;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 同时写 LSPosed 日志与 Settings.Global（状态页可读）；开机早期先缓存。 */
    private static void logLine(String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        final String line = time + "  " + message;
        WirelessAdbHook self = instance;
        if (self != null) {
            try {
                self.log(Log.INFO, TAG, line);
            } catch (Throwable ignored) { }
        }
        if (systemContext == null) {
            synchronized (pendingLogs) {
                pendingLogs.add(line);
                if (pendingLogs.size() > 40) pendingLogs.remove(0);
            }
            return;
        }
        if (EventLog.appendGlobal(systemContext, line)) return;
        try {
            Intent intent = new Intent(ACTION_LOG)
                    .setComponent(new ComponentName(MODULE_PACKAGE, LOG_RECEIVER))
                    .putExtra("line", line)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            systemContext.sendBroadcast(intent);
        } catch (Throwable t) {
            if (self != null) {
                try {
                    self.log(Log.WARN, TAG, "log broadcast failed: " + t);
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void flushPendingLogs() {
        synchronized (pendingLogs) {
            for (String line : pendingLogs) {
                EventLog.appendGlobal(systemContext, line);
            }
            pendingLogs.clear();
        }
    }

    private static String shortError(Throwable t) {
        String text = t.getClass().getSimpleName() + ": " + t.getMessage();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}

package dev.wirelessadb.autostart;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开启无线 ADB 的落地层。
 * Android 17 引入 ADB Wi-Fi 2.0（非信任网络自动关闭无线调试）；ColorOS 也可能忽略
 * Settings.Global 写入。这里保留原生写入主路径，另加运行时探测与回退抑制，
 * 不臆造未经验证的 ROM 字段名。
 */
final class AdbController {
    interface Logger {
        void log(String line);
    }

    static final String SETTING_ADB_WIFI = "adb_wifi_enabled";
    static final String PROP_TLS_PORT = "service.adb.tls.port";
    static final String PROP_TCP_PORT = "service.adb.tcp.port";

    private static final int MAX_REVERTS = 3;
    private static final long REVERT_WINDOW_MS = 60_000L;
    /** Android 17 起 TLS 端口不再写系统属性，只能向 framework 查询；探测结果缓存 5 秒。 */
    private static final long PORT_CACHE_MS = 5_000L;

    private final Context context;
    private final Logger logger;
    private int revertCount;
    private long revertWindowStart;

    private int cachedTlsPort = -1;
    private long cachedTlsAt;
    private String lastPortSource = "";

    AdbController(Context context, Logger logger) {
        this.context = context;
        this.logger = logger;
    }

    int adbWifiEnabled() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_ADB_WIFI, 0);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    boolean tcpPortReady(int expected) {
        int prop = SysReflect.getSystemPropertyInt(PROP_TCP_PORT, -1);
        return prop > 0 && (expected <= 0 || prop == expected);
    }

    /**
     * TLS 端口，多级探测：
     * 1) 旧 ROM 的 service.adb.tls.port 系统属性；
     * 2) Android 17 的 AdbService.getAdbWirelessPort()（IAdbManager AIDL，dumpsys adb
     *    输出的 tls_port 就来自它）；
     * 3) 兜底解析 dumpsys adb 文本（应对 OEM 改写 AdbService 的情况）。
     */
    int tlsPort() {
        int prop = SysReflect.getSystemPropertyInt(PROP_TLS_PORT, -1);
        if (validPort(prop)) return prop;

        long now = System.currentTimeMillis();
        synchronized (this) {
            if (validPort(cachedTlsPort) && now - cachedTlsAt < PORT_CACHE_MS) {
                return cachedTlsPort;
            }
            cachedTlsAt = now;

            int port = -1;
            String source = "";
            try {
                port = portViaAdbService();
                if (validPort(port)) source = "AdbService.getAdbWirelessPort";
            } catch (Throwable ignored) { }
            if (!validPort(port)) {
                try {
                    port = portViaDumpsys();
                    if (validPort(port)) source = "dumpsys adb (tls_port=)";
                } catch (Throwable ignored) { }
            }

            if (port != cachedTlsPort || !source.equals(lastPortSource)) {
                cachedTlsPort = port;
                lastPortSource = source;
                if (validPort(port)) {
                    logger.log("[端口] TLS 端口=" + port + "，来源：" + source);
                }
            }
            return cachedTlsPort;
        }
    }

    /** TLS：清掉经典 TCP 监听 → 打开无线调试 → 必要时经 AdbService 直调补一刀。 */
    void enableTls(String reason) {
        try {
            String currentTcp = SysReflect.getSystemProperty(PROP_TCP_PORT, "");
            if (currentTcp != null && !currentTcp.isEmpty()
                    && !"-1".equals(currentTcp) && !"0".equals(currentTcp)) {
                SysReflect.setSystemProperty(PROP_TCP_PORT, "-1");
                logger.log("TLS 模式：已清除 " + PROP_TCP_PORT + "（原 " + currentTcp + "）");
                restartAdbd("切回 TLS");
            }
        } catch (Throwable t) {
            logger.log("清除 TCP 端口时出错（可忽略）：" + shortError(t));
        }
        boolean changed = Settings.Global.putInt(context.getContentResolver(), SETTING_ADB_WIFI, 1);
        logger.log((changed ? "已请求开启无线调试 TLS" : "无线调试设置未变化（可能已经开启）")
                + "，原因：" + reason);
        if (adbWifiEnabled() != 1) {
            logger.log("[警告] 写入 adb_wifi_enabled=1 后回读不为 1，ColorOS 可能忽略该写入");
        }
    }

    void enableTcp(int port, String reason) {
        int target = port > 0 ? port : AdbModeConfig.DEFAULT_TCP_PORT;
        try {
            SysReflect.setSystemProperty(PROP_TCP_PORT, String.valueOf(target));
            logger.log("已设置 " + PROP_TCP_PORT + "=" + target + "，原因：" + reason);
        } catch (Throwable t) {
            logger.log("设置 TCP 端口失败：" + shortError(t));
            return;
        }
        restartAdbd("TCP 模式");
        int readBack = SysReflect.getSystemPropertyInt(PROP_TCP_PORT, -1);
        if (readBack != target) {
            logger.log("[警告] " + PROP_TCP_PORT + " 回读=" + readBack + "，期望=" + target
                    + "；Android 17 的 adbd 可能已不读取该属性");
        }
    }

    void restartAdbd(String reason) {
        try {
            SysReflect.setSystemProperty("ctl.restart", "adbd");
            logger.log("已请求重启 adbd（" + reason + "），init.svc.adbd="
                    + SysReflect.getSystemProperty("init.svc.adbd", "?"));
        } catch (Throwable t) {
            logger.log("重启 adbd 失败：" + shortError(t));
        }
    }

    /** 系统把开关自动关回时调用；连续回退过多说明是策略性关闭，停止无意义拉锯。 */
    boolean shouldSuppressReenable(String reason) {
        long now = System.currentTimeMillis();
        if (now - revertWindowStart > REVERT_WINDOW_MS) {
            revertWindowStart = now;
            revertCount = 0;
        }
        revertCount++;
        if (revertCount == MAX_REVERTS) {
            logger.log("[警告] 60 秒内连续 " + MAX_REVERTS
                    + " 次被系统回退（" + reason
                    + "）。疑似 Android 17 ADB Wi-Fi 2.0 可信网络策略或 ColorOS 权限监控，"
                    + "已暂停自动写回；请在开发者选项把当前 Wi-Fi 标记为始终允许，并关闭权限监控/系统优化限制。");
        }
        return revertCount >= MAX_REVERTS;
    }

    void clearReverts() {
        revertCount = 0;
        revertWindowStart = 0L;
    }

    /** 一次性环境探测：把 ColorOS 17 的真实 Global key 与 framework 类写入状态页日志。 */
    void probe(ClassLoader loader) {
        logger.log("[诊断] Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + "), " + Build.MANUFACTURER + "/" + Build.MODEL
                + ", adb_wifi_enabled=" + adbWifiEnabled()
                + ", tls.port=" + SysReflect.getSystemPropertyInt(PROP_TLS_PORT, -1)
                + ", tcp.port=" + SysReflect.getSystemPropertyInt(PROP_TCP_PORT, -1)
                + ", adbd=" + SysReflect.getSystemProperty("init.svc.adbd", "?"));
        logger.log("[诊断] TLS 端口来源探测：" + probePortSources());

        StringBuilder keys = new StringBuilder();
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(Uri.parse("content://settings/global"),
                    new String[]{"name", "value"}, null, null, null);
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex("name");
                int valueIdx = cursor.getColumnIndex("value");
                int shown = 0;
                while (cursor.moveToNext() && shown < 12) {
                    if (nameIdx < 0) break;
                    String name = cursor.getString(nameIdx);
                    if (name == null) continue;
                    String lower = name.toLowerCase();
                    if (!lower.contains("adb") && !lower.contains("wifi")) continue;
                    String value = valueIdx >= 0 ? cursor.getString(valueIdx) : "?";
                    if (keys.length() > 0) keys.append(", ");
                    keys.append(name).append('=').append(value);
                    shown++;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) { }
            }
        }
        logger.log("[诊断] Global adb/wifi keys: "
                + (keys.length() == 0 ? "<不可枚举>" : trim(keys.toString(), 220)));

        Class<?> adbService = SysReflect.findClass(loader,
                "com.android.server.adb.AdbService",
                "com.android.server.adb.AdbService$Lifecycle");
        Class<?> adbManager = SysReflect.findClass(loader,
                "com.android.server.adb.AdbDebuggingManager",
                "com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler");
        logger.log("[诊断] AdbService("
                + (adbService == null ? "缺失" : adbService.getName()) + ") wifi方法: "
                + trim(SysReflect.describeMethods(adbService, "wifi"), 200));
        logger.log("[诊断] AdbDebuggingManager("
                + (adbManager == null ? "缺失" : adbManager.getName()) + ") wifi方法: "
                + trim(SysReflect.describeMethods(adbManager, "wifi"), 200));

        Object internal = probeLocalService(loader);
        if (internal != null) {
            logger.log("[诊断] LocalServices 实例=" + internal.getClass().getName()
                    + " adb/wifi方法: " + trim(SysReflect.describeMethods(internal.getClass(), "adb"), 180));
        }

        Class<?> oplus = SysReflect.findClass(loader,
                "com.android.server.adb.OplusAdbService",
                "com.android.server.adb.ColorOSAdbService",
                "com.android.server.am.OplusActivityManagerService");
        if (oplus != null) {
            logger.log("[诊断] 检测到 OPlus 扩展类 " + oplus.getName()
                    + " wifi方法: " + trim(SysReflect.describeMethods(oplus, "wifi"), 160));
        }
    }

    /** 分别跑一遍三条端口路径，把可用性写进诊断日志，便于一次重启看清 ColorOS 差异。 */
    private String probePortSources() {
        int prop = SysReflect.getSystemPropertyInt(PROP_TLS_PORT, -1);
        int viaService = -1;
        int viaDumpsys = -1;
        StringBuilder errors = new StringBuilder();
        try {
            viaService = portViaAdbService();
        } catch (Throwable t) {
            errors.append("AdbService:").append(shortError(t)).append("; ");
        }
        try {
            viaDumpsys = portViaDumpsys();
        } catch (Throwable t) {
            errors.append("dumpsys:").append(shortError(t)).append("; ");
        }
        return "属性=" + prop + ", AdbService=" + viaService + ", dumpsys=" + viaDumpsys
                + (errors.length() == 0 ? "" : ", 异常=" + trim(errors.toString(), 180));
    }

    /** 通过 ServiceManager 拿到 adb binder，再反射 AIDL 的 getAdbWirelessPort()。 */
    private int portViaAdbService() throws Throwable {
        ClassLoader loader = context.getClassLoader();
        Class<?> sm = Class.forName("android.os.ServiceManager", false, loader);
        Method getService = sm.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "adb");
        if (binder == null) return -1;

        Class<?> stub = Class.forName("android.debug.IAdbManager$Stub", false, loader);
        Method asInterface = stub.getDeclaredMethod("asInterface",
                Class.forName("android.os.IBinder", false, loader));
        asInterface.setAccessible(true);
        Object manager = asInterface.invoke(null, binder);
        if (manager == null) return -1;

        Method getPort = manager.getClass().getMethod("getAdbWirelessPort");
        getPort.setAccessible(true);
        Object value = getPort.invoke(manager);
        return value instanceof Integer ? (Integer) value : -1;
    }

    /** 兜底：dumpsys adb 的 adb_wifi 段里有 tls_port=<port>。 */
    private int portViaDumpsys() {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"dumpsys", "adb"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Pattern pattern = Pattern.compile("tls_port\\s*=\\s*(\\d+)");
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 400) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    try {
                        int port = Integer.parseInt(matcher.group(1));
                        if (validPort(port)) return port;
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) { }
            }
        }
        return -1;
    }

    private static boolean validPort(int port) {
        return port > 0 && port <= 65535;
    }

    /** 探测 LocalServices 里注册的 AdbManagerInternal，仅用于诊断，不擅自调用未知签名。 */
    private Object probeLocalService(ClassLoader loader) {
        Class<?> localServices = SysReflect.findClass(loader, "com.android.server.LocalServices");
        if (localServices == null) return null;
        for (String name : new String[]{
                "android.debug.AdbManagerInternal",
                "com.android.server.adb.AdbManagerInternal"}) {
            Class<?> type = SysReflect.findClass(loader, name);
            if (type == null) continue;
            try {
                return SysReflect.callStatic(localServices, "getService",
                        new Class<?>[]{Class.class}, type);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String trim(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String shortError(Throwable t) {
        String text = t.getClass().getSimpleName() + ": " + t.getMessage();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}

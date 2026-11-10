package dev.wirelessadb.autostart;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行记录。
 * system_server 钩子写 Settings.Global（开机早期可靠）；
 * App/Receiver 写 device-protected prefs；界面合并两者。
 */
public final class EventLog {
    public static final String PREFS = "events";
    public static final String KEY_LOG = "log";
    public static final String KEY_LAST_ADDRESS = "last_address";
    public static final String GLOBAL_LOG = "wirelessadb_autostart_log";
    public static final String GLOBAL_LAST_ADDRESS = "wirelessadb_autostart_last_address";
    /**
     * 模块加载标记。值为「boot_count:uptime毫秒」，两者一起锚定「本次开机的哪一刻加载」。
     * 不能只靠日志文本判断模块是否加载：日志会滚动截断，且跨重启保留旧记录，
     * 会出现「模块明明没加载却显示已就绪」或反过来的误报。
     */
    public static final String GLOBAL_MODULE_LOADED = "wirelessadb_autostart_module_loaded";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final int MAX_CHARS = 16384;
    private static final Pattern ADDRESS =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");

    private EventLog() {}

    /**
     * 供 system_server 钩子调用：写入「本次开机模块已加载」标记。
     * 记录 boot_count + 当时的 uptime，二者共同确定这是哪一次开机的事件。
     */
    public static void markModuleLoaded(Context systemContext) {
        if (systemContext == null) return;
        try {
            int boot = bootCount(systemContext);
            long uptime = SystemClock.elapsedRealtime();
            Settings.Global.putString(systemContext.getContentResolver(),
                    GLOBAL_MODULE_LOADED, boot + ":" + uptime);
        } catch (Throwable ignored) { }
    }

    /**
     * 界面判定：模块在本次开机中是否已加载。
     *
     * 优先用独立标记：值形如「boot_count:uptime毫秒」。
     * - boot_count 与当前不一致 → 标记属于上一次开机，模块当前未加载；
     * - 标记时间晚于当前 uptime → 不可信；
     * - 标记早于当前 uptime → 同一次开机内写的，有效（无论开机多久）。
     *
     * 标记不存在时回退到日志文本判断（兼容旧版本 / 标记写入失败的场景）。
     */
    public static boolean isModuleLoaded(Context context, String log) {
        if (context != null) {
            try {
                String raw = Settings.Global.getString(context.getContentResolver(),
                        GLOBAL_MODULE_LOADED);
                if (raw != null && !raw.isEmpty()) {
                    int sep = raw.indexOf(':');
                    if (sep > 0) {
                        try {
                            int markedBoot = Integer.parseInt(raw.substring(0, sep).trim());
                            long markedUptime = Long.parseLong(raw.substring(sep + 1).trim());
                            int currentBoot = bootCount(context);
                            long currentUptime = SystemClock.elapsedRealtime();
                            // boot_count 变了 → 这条标记属于上一次开机，模块当前未加载。
                            if (currentBoot > 0 && markedBoot != currentBoot) return false;
                            // 标记的 uptime 晚于现在 → 来自未来的标记，不可信。
                            if (markedUptime > currentUptime) return false;
                            // 同一次开机内写入的标记，一直有效（开机再久也算数）。
                            return true;
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (Throwable ignored) { }
        }
        // 回退：旧版本没有独立标记时，仍看日志里有没有加载记录。
        return log != null && log.contains("[框架] 模块已加载");
    }

    /** 当前开机次数；读不到时返回 -1。 */
    public static int currentBootCount(Context context) {
        return context == null ? -1 : bootCount(context);
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), KEY_BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 供 system_server 钩子：写入 Settings.Global。 */
    public static boolean appendGlobal(Context systemContext, String line) {
        if (systemContext == null || line == null || line.isEmpty()) return false;
        try {
            String prev = Settings.Global.getString(systemContext.getContentResolver(), GLOBAL_LOG);
            if (prev == null) prev = "";
            String updated = prev + line + "\n";
            if (updated.length() > MAX_CHARS) {
                updated = updated.substring(updated.length() - MAX_CHARS);
            }
            Settings.Global.putString(systemContext.getContentResolver(), GLOBAL_LOG, updated);
            if (line.contains("已复制无线 ADB 地址：") || line.contains("地址已就绪")) {
                Matcher m = ADDRESS.matcher(line);
                if (m.find()) {
                    Settings.Global.putString(systemContext.getContentResolver(),
                            GLOBAL_LAST_ADDRESS, m.group(1));
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 App / LogReceiver：写入 DE prefs。 */
    public static boolean append(Context context, String line) {
        if (context == null || line == null || line.isEmpty()) return false;
        try {
            Context storage = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String updated = prefs.getString(KEY_LOG, "") + line + "\n";
            if (updated.length() > MAX_CHARS * 2) {
                updated = updated.substring(updated.length() - MAX_CHARS * 2);
            }
            SharedPreferences.Editor editor = prefs.edit().putString(KEY_LOG, updated);
            if (line.contains("已复制无线 ADB 地址：") || line.contains("地址已就绪")) {
                Matcher m = ADDRESS.matcher(line);
                if (m.find()) editor.putString(KEY_LAST_ADDRESS, m.group(1));
            }
            return editor.commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 界面读取：Global + prefs 合并（去重保序）。 */
    public static String readMerged(Context context) {
        String global = "";
        String prefs = "";
        try {
            String g = Settings.Global.getString(context.getContentResolver(), GLOBAL_LOG);
            if (g != null) global = g;
        } catch (Throwable ignored) { }
        try {
            prefs = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LOG, "");
            if (prefs == null) prefs = "";
        } catch (Throwable ignored) { }
        if (global.isEmpty()) return prefs;
        if (prefs.isEmpty()) return global;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String src : new String[]{prefs, global}) {
            for (String line : src.split("\n", -1)) {
                if (line.isEmpty() || !seen.add(line)) continue;
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        return out.toString();
    }

    public static String readLastAddress(Context context) {
        try {
            String g = Settings.Global.getString(context.getContentResolver(), GLOBAL_LAST_ADDRESS);
            if (g != null && !g.isEmpty()) return g;
        } catch (Throwable ignored) { }
        try {
            return context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_ADDRESS, null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

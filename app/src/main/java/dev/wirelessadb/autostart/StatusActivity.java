package dev.wirelessadb.autostart;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusActivity extends Activity {
    private static final int LOG_PREVIEW_LINES = 12;
    private static final Pattern ADDRESS_IN_LOG =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");
    /** 模块日志里的「[端口] TLS 端口=N」，是当前 adbd 实例的真实端口。 */
    private static final Pattern TLS_PORT_IN_LOG =
            Pattern.compile("\\[端口\\]\\s*TLS 端口=(\\d+)");

    private TextView noticeChip;
    private TextView statusTitle;
    private TextView modeLabel;
    private TextView addressView;
    private EditText portInput;
    private View modeTlsBtn;
    private View modeTcpBtn;
    private ImageView modeTlsIcon;
    private ImageView modeTcpIcon;
    private TextView modeTlsText;
    private TextView modeTcpText;
    private View logEmpty;
    private ScrollView logScroll;
    private TextView logView;
    private boolean copiedFlash;

    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        // 不预置文案：首帧就按真实状态判定，避免冷启动瞬间两个「就绪」同时亮。
        refreshAll(null);
    }

    /** 整页自由滚动：滚动区吃状态栏/导航栏留白。 */
    private void applySystemInsets() {
        scroll = findViewById(R.id.scroll);
        int extraTop = dp(12);
        int bottomPad = dp(20);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), top + extraTop,
                    v.getPaddingRight(), bottom + bottomPad);
            return insets.consumeSystemWindowInsets();
        });
        scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll(null);
    }

    private void bindViews() {
        noticeChip = findViewById(R.id.notice_chip);
        statusTitle = findViewById(R.id.status_title);
        modeLabel = findViewById(R.id.mode_label);
        addressView = findViewById(R.id.address_view);
        portInput = findViewById(R.id.port_input);
        modeTlsBtn = findViewById(R.id.mode_tls_btn);
        modeTcpBtn = findViewById(R.id.mode_tcp_btn);
        modeTlsIcon = findViewById(R.id.mode_tls_icon);
        modeTcpIcon = findViewById(R.id.mode_tcp_icon);
        modeTlsText = findViewById(R.id.mode_tls_text);
        modeTcpText = findViewById(R.id.mode_tcp_text);
        logEmpty = findViewById(R.id.log_empty);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);

        // 地址整行点按即复制：这是唯一无条件立即写剪贴板的入口。
        addressView.setOnClickListener(v -> {
            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(70).start())
                    .start();
            sendBroadcast(new Intent("dev.wirelessadb.autostart.REQUEST_COPY").setPackage("android"));
            flashCopied();
            Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
        });

        // 闪电按钮：强制重新开启 adbd（同模式点 TLS/TCP 不会触发，这是唯一恢复入口）。
        findViewById(R.id.hero_action).setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(80).start())
                    .start();
            sendBroadcast(new Intent("dev.wirelessadb.autostart.APPLY").setPackage("android"));
            setNotice("已请求立即应用");
            Toast.makeText(this, "已请求立即应用", Toast.LENGTH_SHORT).show();
            portInput.postDelayed(() -> refreshAll(null), 1500);
        });

        findViewById(R.id.log_refresh).setOnClickListener(v -> refreshAll("记录已刷新"));
    }

    private void wireActions() {
        modeTlsBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TLS));
        modeTcpBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TCP));
        portInput.setText(String.valueOf(AdbModeConfig.getTcpPort(this)));
    }

    private void switchMode(String mode) {
        int port = AdbModeConfig.getTcpPort(this);
        if (AdbModeConfig.MODE_TCP.equals(mode)) {
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (Exception e) {
                port = AdbModeConfig.DEFAULT_TCP_PORT;
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "端口无效，请输入 1–65535", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String previousMode = AdbModeConfig.getMode(this);
        boolean modeChanged = !mode.equals(previousMode);
        AdbModeConfig.setMode(this, mode, port);
        sendBroadcast(new Intent("dev.wirelessadb.autostart.SET_MODE")
                .setPackage("android")
                .putExtra("mode", mode)
                .putExtra("port", port));
        String notice;
        if (!modeChanged) {
            // 同一模式重复点击：配置未变，不应打断当前连接。
            notice = "已是当前模式，未做改动";
        } else {
            // 跨模式切换必然重启 adbd → 端口会变、当前连接会断，必须明确告知。
            notice = AdbModeConfig.MODE_TLS.equals(mode)
                    ? "已切到 TLS，端口将重置，请用新地址重连"
                    : ("已切到 TCP " + port + "，请用新地址重连");
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
        }
        refreshAll(notice);
        if (!modeChanged) {
            Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        }
        portInput.postDelayed(() -> refreshAll(null), 1500);
        // 切模式后端口要等 adbd 重启完才稳定，多刷几次把新地址取回来。
        portInput.postDelayed(() -> refreshAll(null), 4000);
        portInput.postDelayed(() -> refreshAll(null), 8000);
    }

    private void refreshAll(String noticeOverride) {
        String mode = AdbModeConfig.getMode(this);
        int port = AdbModeConfig.getTcpPort(this);
        portInput.setText(String.valueOf(port));
        updateModeButtons(mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 无线调试（随机端口）"
                : ("TCP 固定端口（" + port + "）"));

        String log = readLog();
        String preview = recentLogLines(log, LOG_PREVIEW_LINES);
        boolean empty = preview == null || preview.isEmpty();
        if (empty) {
            logEmpty.setVisibility(View.VISIBLE);
            logScroll.setVisibility(View.GONE);
            logView.setText("");
        } else {
            logEmpty.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            logView.setText(preview);
        }

        String address = resolveAddress(mode, port, log);
        addressView.setText(address == null ? "等待地址…" : address);
        boolean ready = address != null;
        statusTitle.setText(ready ? "连接已就绪" : "等待连接");
        statusTitle.setBackgroundTintList(ColorStateList.valueOf(
                getColor(ready ? R.color.fl_running_bg : R.color.fl_idle_bg)));
        statusTitle.setTextColor(getColor(ready ? R.color.fl_running_fg : R.color.fl_idle_fg));
        boolean moduleLoaded = EventLog.isModuleLoaded(this, log);
        if (noticeOverride != null) {
            setNotice(noticeOverride);
        } else if (!copiedFlash) {
            if (!moduleLoaded) {
                setNotice("模块未加载 · 查作用域与框架版本");
            } else {
                setNotice(ready ? "已准备就绪" : "等待模块生效");
            }
        }
    }

    private void updateModeButtons(String mode) {
        boolean tls = AdbModeConfig.MODE_TLS.equals(mode);
        int selected = getColor(R.color.fl_accent);
        int idle = getColor(R.color.fl_surface_variant);
        int selectedText = getColor(R.color.fl_on_accent);
        int idleText = getColor(R.color.fl_on_surface_variant);

        modeTlsBtn.setBackgroundTintList(ColorStateList.valueOf(tls ? selected : idle));
        modeTcpBtn.setBackgroundTintList(ColorStateList.valueOf(tls ? idle : selected));
        // 选中态决定波纹配色：蓝底用白色高亮，浅底用暗色高亮。
        modeTlsBtn.setActivated(tls);
        modeTcpBtn.setActivated(!tls);
        modeTlsText.setTextColor(tls ? selectedText : idleText);
        modeTcpText.setTextColor(tls ? idleText : selectedText);
        modeTlsIcon.setImageTintList(ColorStateList.valueOf(tls ? selectedText : idleText));
        modeTcpIcon.setImageTintList(ColorStateList.valueOf(tls ? idleText : selectedText));
    }

    private void setNotice(String text) {
        noticeChip.setText(text);
    }

    private void flashCopied() {
        copiedFlash = true;
        setNotice("地址已复制");
        noticeChip.postDelayed(() -> {
            copiedFlash = false;
            refreshAll(null);
        }, 1600);
    }

    private String readLog() {
        return EventLog.readMerged(this);
    }

    /** 只展示最近几条，倒序（最新在上）。 */
    private static String recentLogLines(String log, int limit) {
        if (log == null || log.isEmpty() || limit <= 0) return "";
        String[] lines = log.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) end--;
        if (end == 0) return "";
        int start = Math.max(0, end - limit);
        StringBuilder out = new StringBuilder();
        for (int i = end - 1; i >= start; i--) {
            if (lines[i].trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private String resolveAddress(String mode, int port, String log) {
        String ip = findWifiIpv4();
        // TCP 模式端口是确定的，直接算，避免显示切换前的旧地址。
        if (AdbModeConfig.MODE_TCP.equals(mode)) {
            if (ip != null) return ip + ":" + port;
        }
        // TLS 端口每次 adbd 重启都会重排，取日志里最新的 [端口] 行，而不是缓存地址。
        int tlsPort = latestTlsPort(log);
        if (ip != null && tlsPort > 0) return ip + ":" + tlsPort;
        String saved = EventLog.readLastAddress(this);
        if (saved != null && !saved.isEmpty()) return saved;
        if (log != null) {
            Matcher m = ADDRESS_IN_LOG.matcher(log);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null) return last;
        }
        if (ip == null) return null;
        if (AdbModeConfig.MODE_TCP.equals(mode)) return ip + ":" + port;
        return ip + ":?";
    }

    /** 日志里最后一条「[端口] TLS 端口=N」，即当前 adbd 实例的真实 TLS 端口。 */
    private static int latestTlsPort(String log) {
        if (log == null || log.isEmpty()) return -1;
        Matcher m = TLS_PORT_IN_LOG.matcher(log);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return last;
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
                        if (host != null && host.startsWith("172.19.")) continue;
                        return host;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

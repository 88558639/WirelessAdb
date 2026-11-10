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

    private static final int[] NAV_ITEMS = {
            R.id.nav_dashboard, R.id.nav_mode, R.id.nav_actions, R.id.nav_log};
    private static final int[] NAV_ICONS = {
            R.id.nav_dashboard_icon, R.id.nav_mode_icon, R.id.nav_actions_icon, R.id.nav_log_icon};
    private static final int[] NAV_LABELS = {
            R.id.nav_dashboard_label, R.id.nav_mode_label, R.id.nav_actions_label, R.id.nav_log_label};
    private static final int[] NAV_TARGETS = {
            R.id.section_hero, R.id.section_mode, R.id.section_actions, R.id.section_log};

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
    private ImageView copyIcon;
    private TextView copyLabel;
    private boolean copiedFlash;

    private ScrollView scroll;
    private View applyBtn;
    private final View[] navItems = new View[NAV_ITEMS.length];

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        wireNav();
        refreshAll("已准备就绪");
    }

    /** 避开状态栏 / 导航栏，并给固定底部导航条留出空间。 */
    private void applySystemInsets() {
        scroll = findViewById(R.id.scroll);
        int extraTop = dp(12);
        int navSpace = dp(112);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), top + extraTop,
                    v.getPaddingRight(), bottom + navSpace);
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

        View applyRoot = findViewById(R.id.action_apply);
        View copyRoot = findViewById(R.id.action_copy);
        View refreshRoot = findViewById(R.id.action_refresh);
        applyBtn = applyRoot.findViewById(R.id.action_btn);
        setupAction(applyRoot, R.drawable.ic_check_cyan, "立即应用",
                "向 system_server 请求开启无线 ADB", v -> {
                    sendBroadcast(new Intent("dev.wirelessadb.autostart.APPLY").setPackage("android"));
                    setNotice("已请求立即应用");
                    Toast.makeText(this, "已请求立即应用", Toast.LENGTH_SHORT).show();
                    portInput.postDelayed(() -> refreshAll("记录已刷新"), 1500);
                });
        setupAction(copyRoot, R.drawable.ic_copy, "复制地址",
                "把当前 IP:端口写入剪贴板", v -> {
                    sendBroadcast(new Intent("dev.wirelessadb.autostart.REQUEST_COPY").setPackage("android"));
                    flashCopied();
                    setNotice("地址已复制");
                    Toast.makeText(this, "已请求立即复制", Toast.LENGTH_SHORT).show();
                    portInput.postDelayed(() -> refreshAll(null), 1200);
                });
        setupAction(refreshRoot, R.drawable.ic_refresh, "刷新记录",
                "重新读取状态页与运行日志", v -> refreshAll("记录已刷新"));

        findViewById(R.id.hero_action).setOnClickListener(v -> applyBtn.performClick());

        copyIcon = copyRoot.findViewById(R.id.action_icon);
        copyLabel = copyRoot.findViewById(R.id.action_label);
    }

    private void setupAction(View root, int iconRes, String label, String sub,
                             View.OnClickListener click) {
        ImageView icon = root.findViewById(R.id.action_icon);
        TextView text = root.findViewById(R.id.action_label);
        TextView subView = root.findViewById(R.id.action_sub);
        View btn = root.findViewById(R.id.action_btn);
        icon.setImageResource(iconRes);
        text.setText(label);
        subView.setText(sub);
        btn.setOnClickListener(v -> {
            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(70).start())
                    .start();
            click.onClick(v);
        });
    }

    private void wireActions() {
        modeTlsBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TLS));
        modeTcpBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TCP));
        portInput.setText(String.valueOf(AdbModeConfig.getTcpPort(this)));
    }

    /** 底部四项作为页面锚点，点击滚动到对应区块并高亮。 */
    private void wireNav() {
        for (int i = 0; i < NAV_ITEMS.length; i++) {
            final int index = i;
            navItems[i] = findViewById(NAV_ITEMS[i]);
            navItems[i].setOnClickListener(v -> {
                setNavSelected(index);
                View target = findViewById(NAV_TARGETS[index]);
                scroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(16)));
            });
        }
    }

    private void setNavSelected(int active) {
        for (int i = 0; i < navItems.length; i++) {
            if (navItems[i] == null) continue;
            int color = getColor(i == active ? R.color.fl_accent : R.color.fl_icon);
            ImageView icon = navItems[i].findViewById(NAV_ICONS[i]);
            TextView label = navItems[i].findViewById(NAV_LABELS[i]);
            icon.setImageTintList(ColorStateList.valueOf(color));
            label.setTextColor(color);
        }
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
        AdbModeConfig.setMode(this, mode, port);
        sendBroadcast(new Intent("dev.wirelessadb.autostart.SET_MODE")
                .setPackage("android")
                .putExtra("mode", mode)
                .putExtra("port", port));
        String notice = AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 模式已应用"
                : ("TCP 端口 " + port + " 已应用");
        refreshAll(notice);
        Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        portInput.postDelayed(() -> refreshAll(null), 1500);
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
        boolean moduleLoaded = log != null && log.contains("[框架] 模块已加载");
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
        copyIcon.setImageResource(R.drawable.ic_check_cyan);
        copyLabel.setText("已复制");
        copyLabel.postDelayed(() -> {
            copiedFlash = false;
            copyIcon.setImageResource(R.drawable.ic_copy);
            copyLabel.setText("复制地址");
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
        String saved = EventLog.readLastAddress(this);
        if (saved != null && !saved.isEmpty()) return saved;
        if (log != null) {
            Matcher m = ADDRESS_IN_LOG.matcher(log);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null) return last;
        }
        String ip = findWifiIpv4();
        if (ip == null) return null;
        if (AdbModeConfig.MODE_TCP.equals(mode)) return ip + ":" + port;
        return ip + ":?";
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

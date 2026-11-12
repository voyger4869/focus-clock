package com.focus.clock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    // 接收通知按钮指令的广播接收器（前台时立即执行 JS）
    private BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && TimerService.ACTION_CMD.equals(intent.getAction())) {
                String cmd = intent.getStringExtra("command");
                if (cmd != null) dispatchToJs(cmd);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setBackgroundColor(Color.parseColor("#1a1b26"));
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        // 注入 JS 桥，用于导出文件保存到本地
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        // 注册通知按钮指令广播接收器
        registerCmdReceiver();

        // 沉浸式状态栏：状态栏与背景同色，内容正常显示在状态栏下方（不裁切）
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#1a1b26"));
        window.setNavigationBarColor(Color.parseColor("#1a1b26"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView.loadUrl("file:///android_asset/index.html");

        // Android 13+ 请求通知权限（用于专注结束通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }

        // 首次启动：引导用户将 App 加入后台白名单，避免国产 ROM 杀后台导致计时中断
        showBatteryWhitelistGuideIfNeeded();
    }

    /** 首次启动引导：提示后台保活白名单设置 */
    private void showBatteryWhitelistGuideIfNeeded() {
        try {
            SharedPreferences sp = getSharedPreferences("focus_clock_prefs", Context.MODE_PRIVATE);
            if (sp.getBoolean("battery_guide_shown", false)) return;
            sp.edit().putBoolean("battery_guide_shown", true).apply();

            new AlertDialog.Builder(this)
                .setTitle("保持后台计时")
                .setMessage("为了让专注倒计时在锁屏或切到后台时也能准确运行，"
                    + "建议将本应用加入系统的「后台不限制 / 自启动白名单」。\n\n"
                    + "部分手机（小米、华为、OPPO、vivo 等）的省电策略可能会在后台暂停应用。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBatteryWhitelistSettings();
                    }
                })
                .setNegativeButton("稍后再说", null)
                .show();
        } catch (Exception ignored) {}
    }

    /** 打开电池优化/后台管理设置页 */
    private void openBatteryWhitelistSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
            }
            // 兜底：打开应用详情页
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // 忽略，某些 ROM 无对应设置页
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {}
        }
    }

    private class JsBridge {
        @JavascriptInterface
        public void saveFile(String filename, String content) {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                writer.write(content);
                writer.flush();
                writer.close();
                fos.close();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "已保存到下载目录：" + filename, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        /**
         * 计时控制桥：JS 端在开始/暂停/取消计时时调用，
         * 由原生层前台服务在后台维持计时并在结束时发通知。
         * @param action  "start" | "pause" | "cancel"
         * @param payload JSON 字符串，含 mode、endAt(毫秒)、taskName
         */
        @JavascriptInterface
        public void timer(String action, String payload) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(MainActivity.this, TimerService.class);
                    intent.putExtra("action", action);
                    if (payload != null && !payload.isEmpty()) {
                        JSONObject o = new JSONObject(payload);
                        intent.putExtra("mode", o.optString("mode", "focus"));
                        intent.putExtra("endAt", o.optLong("endAt", 0));
                        intent.putExtra("taskName", o.optString("taskName", ""));
                        intent.putExtra("taskId", o.optString("taskId", ""));
                        intent.putExtra("startedAt", o.optLong("startedAt", 0));
                        intent.putExtra("total", o.optLong("total", 0));
                    }
                    // 只有"开始计时"才需要前台服务保活。
                    // pause/cancel 会立即停止服务，若用 startForegroundService
                    // 会因 5 秒内未 startForeground 抛异常导致闪退。
                    boolean needForeground = "start".equals(action);
                    if (needForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                } catch (Exception e) {
                    // 忽略解析错误，绝不让桥接异常导致 WebView 崩溃
                }
            });
        }

        /**
         * 取回原生层在后台期间完成的计时事件（JSON 数组字符串），
         * 供 JS 端补记 session，避免后台/锁屏时丢失专注记录。
         */
        @JavascriptInterface
        public String consumeCompletions() {
            try {
                return TimerService.consumeCompletions(MainActivity.this);
            } catch (Exception e) {
                return "[]";
            }
        }

        /**
         * 取回通知按钮触发的待执行指令（pause/skip/next），返回字符串或空。
         */
        @JavascriptInterface
        public String consumeCommand() {
            try {
                return TimerService.consumeCommand(MainActivity.this);
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** 注册通知按钮指令广播接收器 */
    private void registerCmdReceiver() {
        try {
            IntentFilter filter = new IntentFilter(TimerService.ACTION_CMD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(cmdReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(cmdReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    /** 把指令传给 WebView 的 JS 端执行（window.__nativeCmd(cmd)） */
    private void dispatchToJs(final String cmd) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("(function(){try{if(window.__nativeCmd)window.__nativeCmd('"+cmd+"');}catch(e){}})();", null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复前台时，若存在通知按钮触发的待执行指令（App 之前不在前台），
        // 让 JS 端在页面加载/恢复后自行通过 consumeCommand 读取执行。
        // 这里不直接 evaluateJavascript，因为 WebView 可能尚未加载完成。
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(cmdReceiver);
        } catch (Exception ignored) {}
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

package com.focus.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 前台服务：在后台维持计时，并在专注/休息结束时发出系统通知。
 *
 * JS 端通过 AndroidBridge.timer(action, json) 调用：
 *   - "start"  : 开始计时（payload 含 endAt 毫秒时间戳、mode、taskName）
 *   - "pause"  : 暂停
 *   - "cancel" : 取消（重置/跳过/完成时调用，移除通知）
 *
 * 计时本身由 JS 基于绝对时间戳追平；本服务主要职责是：
 *   1) 前台服务保活，避免 WebView 被系统回收导致后台完全停止；
 *   2) 在 endAt 到达时发出系统通知（即使 App 退到后台/锁屏）。
 */
public class TimerService extends Service {

    private static final String CHANNEL_ID = "focus_timer";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_DONE_ID = 2;
    // 后台完成的计时事件暂存（供 JS 端补记，避免后台无记录）
    private static final String PREFS_NAME = "focus_clock_completions";
    private static final String KEY_EVENTS = "pending_events";
    // 待执行指令（通知按钮点击 → 通知 JS 端执行）
    public static final String KEY_CMD = "pending_cmd";
    public static final String ACTION_CMD = "com.focus.clock.TIMER_CMD";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private long endAt = 0;
    private String mode = "focus";
    private String taskName = "";
    private String taskId = "";
    private long startedAt = 0;
    private long totalSec = 0;
    private boolean active = false;
    private boolean inForeground = false;
    private boolean stopping = false;
    private long lastNotifyUpdate = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            handleAction(action, intent);
        }
        // 不自动重启：计时状态由 JS 端维护并追平
        return START_NOT_STICKY;
    }

    private void handleAction(String action, Intent intent) {
        if ("start".equals(action)) {
            String m = intent.getStringExtra("mode");
            long e = intent.getLongExtra("endAt", 0);
            String t = intent.getStringExtra("taskName");
            String tid = intent.getStringExtra("taskId");
            long sat = intent.getLongExtra("startedAt", 0);
            long tot = intent.getLongExtra("total", 0);
            start(m, e, t, tid, sat, tot);
        } else if ("pause".equals(action)) {
            // 通知按钮「暂停」→ 通知 JS 端执行暂停
            dispatchCommandToJs("pause");
            stopTicking();
            stopSelfSafe();
        } else if ("skip".equals(action)) {
            // 通知按钮「跳过」→ 通知 JS 端跳过本轮
            dispatchCommandToJs("skip");
            cancel();
        } else if ("startNext".equals(action)) {
            // 结束通知「开始下一轮」→ 通知 JS 端开始下一模式
            dispatchCommandToJs("next");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_DONE_ID);
            stopSelfSafe();
        } else if ("cancel".equals(action)) {
            cancel();
        }
    }

    /**
     * 把指令传给 JS：写 SharedPreferences（App 不在前台时，下次打开读取），
     * 并发广播给 MainActivity（App 在前台时立即执行）。
     */
    private void dispatchCommandToJs(String cmd) {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CMD, cmd).apply();
            Intent b = new Intent(ACTION_CMD);
            b.setPackage(getPackageName());
            b.putExtra("command", cmd);
            sendBroadcast(b);
        } catch (Exception ignored) {}
    }

    private void start(String m, long e, String t, String tid, long sat, long tot) {
        this.mode = (m == null) ? "focus" : m;
        this.endAt = e;
        this.taskName = (t == null) ? "" : t;
        this.taskId = (tid == null) ? "" : tid;
        this.startedAt = sat;
        this.totalSec = tot;
        this.stopping = false;
        this.active = true;
        this.lastNotifyUpdate = 0;

        // 进入前台：满足 startForegroundService 的 5 秒内必须 startForeground 的要求
        enterForeground();

        stopTickingOnly();
        if (this.endAt > 0) {
            ticker = new Runnable() {
                @Override
                public void run() {
                    if (stopping) return;
                    long remain = endAt - System.currentTimeMillis();
                    if (remain <= 0) {
                        fireCompletionNotification();
                    } else {
                        // 每 500ms 检查；每隔约 15s 更新一次通知上的倒计时
                        if (System.currentTimeMillis() - lastNotifyUpdate >= 15000) {
                            updateOngoingNotification();
                            lastNotifyUpdate = System.currentTimeMillis();
                        }
                        handler.postDelayed(this, 500);
                    }
                }
            };
            handler.postDelayed(ticker, 500);
        }
    }

    /** 更新持续通知上的剩余时间（通知栏内联显示倒计时） */
    private void updateOngoingNotification() {
        if (!active || !inForeground) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFICATION_ID, buildOngoingNotification());
        } catch (Exception ignored) {}
    }

    /** 进入前台服务状态（带通知），确保后台保活 */
    private void enterForeground() {
        try {
            Notification n = buildOngoingNotification();
            startForeground(NOTIFICATION_ID, n);
            inForeground = true;
        } catch (Exception e) {
            // 某些情况下（如权限受限）无法进入前台，仍尽力继续计时
            inForeground = false;
        }
    }

    /** 仅停止 ticker，不触碰前台状态 */
    private void stopTickingOnly() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    /** 停止计时并退出前台 */
    private void stopTicking() {
        stopTickingOnly();
        exitForeground();
        active = false;
    }

    /** 退出前台状态（兼容 API 21-23 与 24+ 的 stopForeground 重载） */
    private void exitForeground() {
        if (!inForeground) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {}
        inForeground = false;
    }

    /** 安全地停止服务（幂等） */
    private void stopSelfSafe() {
        if (stopping) return;
        stopping = true;
        stopTicking();
        stopSelf();
    }

    private void cancel() {
        // 取消结束通知，然后退出前台并停止服务
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_DONE_ID);
        stopSelfSafe();
    }

    /** 计时结束：发出系统通知 */
    private void fireCompletionNotification() {
        if (stopping) return;
        stopping = true;
        stopTickingOnly();
        // 退出前台（移除持续通知），但服务本身稍后停止以播完提示音
        exitForeground();
        active = false;

        String title;
        String text;
        if ("focus".equals(mode)) {
            title = "专注完成";
            text = (taskName != null && !taskName.isEmpty())
                ? "\u300c" + taskName + "\u300d专注结束，休息一下吧"
                : "专注结束，休息一下吧";
        } else if ("break".equals(mode) || "longbreak".equals(mode)) {
            title = "休息结束";
            text = "休息时间到了，开始新的专注吧";
        } else {
            title = "计时结束";
            text = "计时已完成";
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                   .setContentTitle(title)
                   .setContentText(text)
                   .setContentIntent(contentIntent)
                   .setAutoCancel(true)
                   .setPriority(Notification.PRIORITY_HIGH)
                   .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);

            // 结束通知加一个「开始下一轮」按钮
            String nextLabel = ("focus".equals(mode)) ? "开始休息" : "开始专注";
            Intent nextIntent = new Intent(this, TimerService.class).setAction("startNext");
            PendingIntent nextPi = PendingIntent.getService(
                this, 12, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_media_play, nextLabel, nextPi);

            nm.notify(NOTIFICATION_DONE_ID, builder.build());
        }

        playCompletionTone();
        // 记录完成事件到 SharedPreferences，供 JS 端补记 session（后台无记录修复）
        recordCompletion();
        // 延迟停止服务，确保提示音播完
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, 1500L);
    }

    /** 把本次完成的计时写入待补记队列（仅专注模式有效） */
    private void recordCompletion() {
        if (!"focus".equals(mode)) return;
        long dur = totalSec;
        // 若没有拿到 total，用 endAt - startedAt 估算
        if (dur <= 0 && startedAt > 0 && endAt > 0) {
            dur = Math.max(0, (endAt - startedAt) / 1000);
        }
        if (dur <= 0) return;

        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_EVENTS, "[]");
            JSONArray arr = new JSONArray(raw);
            JSONObject ev = new JSONObject();
            long started = startedAt > 0 ? startedAt : (endAt - dur * 1000);
            ev.put("id", System.currentTimeMillis() + "_" + (int)(Math.random()*100000));
            ev.put("taskId", taskId);
            ev.put("startedAt", started);
            ev.put("durationSec", dur);
            ev.put("mode", "focus");
            arr.put(ev);
            // 只保留最近 50 条，防止异常堆积
            while (arr.length() > 50) {
                JSONArray trimmed = new JSONArray();
                for (int i = arr.length() - 50; i < arr.length(); i++) {
                    trimmed.put(arr.get(i));
                }
                arr = trimmed;
            }
            sp.edit().putString(KEY_EVENTS, arr.toString()).apply();
        } catch (JSONException e) {
            // 忽略
        }
    }

    /** 播放结束提示音（后台/锁屏也能响，因由原生层触发） */
    private void playCompletionTone() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            int[] tones = {
                ToneGenerator.TONE_PROP_BEEP,
                ToneGenerator.TONE_PROP_BEEP2,
                ToneGenerator.TONE_PROP_BEEP
            };
            for (int i = 0; i < tones.length; i++) {
                final int tone = tones[i];
                final int idx = i;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try { tg.startTone(tone, 180); } catch (Exception ignored) {}
                    }
                }, idx * 200L);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { tg.release(); } catch (Exception ignored) {}
                }
            }, tones.length * 200L + 300L);
        } catch (Exception e) {
            // 忽略：提示音失败不影响通知
        }
    }

    /** 持续中的前台通知 */
    private Notification buildOngoingNotification() {
        String title;
        if ("focus".equals(mode)) {
            title = (taskName != null && !taskName.isEmpty()) ? "专注中：" + taskName : "专注计时中";
        } else if ("break".equals(mode) || "longbreak".equals(mode)) {
            title = "休息中";
        } else {
            title = "计时中";
        }

        // 计算剩余时间用于通知栏显示（分钟级，向上取整避免“剩余 0 分钟”）
        String remainText = "计时进行中";
        if (endAt > 0) {
            long remain = Math.max(0, endAt - System.currentTimeMillis());
            long m = (remain + 59999) / 60000; // 向上取整到分钟
            remainText = "剩余 " + m + " 分钟";
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 暂停按钮
        Intent pauseIntent = new Intent(this, TimerService.class).setAction("pause");
        PendingIntent pausePi = PendingIntent.getService(
            this, 10, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 跳过按钮
        Intent skipIntent = new Intent(this, TimerService.class).setAction("skip");
        PendingIntent skipPi = PendingIntent.getService(
            this, 11, skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
               .setContentTitle(title)
               .setContentText(remainText)
               .setContentIntent(contentIntent)
               .setOngoing(true)
               .setPriority(Notification.PRIORITY_LOW)
               .addAction(android.R.drawable.ic_media_pause, "暂停", pausePi)
               .addAction(android.R.drawable.ic_media_next, "跳过", skipPi);

        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "专注计时", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("专注计时进行中与结束提醒");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /**
     * 供 JS 桥调用：取回并清空待补记的完成事件，返回 JSON 数组字符串。
     */
    public static String consumeCompletions(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_EVENTS, "[]");
            sp.edit().remove(KEY_EVENTS).apply();
            return raw;
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 供 JS 桥调用：取回并清空待执行指令（通知按钮触发），返回指令字符串或空。
     */
    public static String consumeCommand(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cmd = sp.getString(KEY_CMD, "");
            sp.edit().remove(KEY_CMD).apply();
            return cmd;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroy() {
        stopTickingOnly();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

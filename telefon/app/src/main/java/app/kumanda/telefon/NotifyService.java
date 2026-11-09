package app.kumanda.telefon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Arka plan servisi: uygulama kapali/arka plandayken bile bilgisayardaki Kumanda'ya bagli kalir ve
 * onay / soru / is bitti olaylarini bildirim olarak gosterir. Kucuk, kalici bir "bagli" bildirimi tasir (Android kurali).
 */
public class NotifyService extends Service {
    public static final String CH_LIVE = "kumanda_live";
    public static final String CH_EVENTS = "kumanda_events2";   // ses + titresim (kanal ayarlari olusturulunca sabitlenir, o yuzden yeni kimlik)
    private static final int ID_LIVE = 7001;
    private OkHttpClient http;
    private WebSocket ws;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int retry = 0;
    private boolean stopped = false;
    private String lastKey = "";
    private long lastAt = 0;

    public static void start(Context c) {
        Intent i = new Intent(c, NotifyService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    public static void stop(Context c) { c.stopService(new Intent(c, NotifyService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel live = new NotificationChannel(CH_LIVE, "Bağlantı", NotificationManager.IMPORTANCE_MIN);
            live.setDescription("Bilgisayara bağlı kalır (sessiz)"); live.setShowBadge(false);
            nm.createNotificationChannel(live);
            NotificationChannel ev = new NotificationChannel(CH_EVENTS, "Kumanda olayları", NotificationManager.IMPORTANCE_HIGH);
            ev.setDescription("Onay bekliyor, soru soruyor, iş bitti — ses ve titreşimle");
            ev.enableVibration(true); ev.setVibrationPattern(new long[]{0, 250, 120, 250});
            ev.enableLights(true); ev.setLightColor(0xFFE08A5B); ev.setShowBadge(true);
            ev.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ev);
        }
        http = new OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();
    }

    public static final String ACT_ANSWER = "app.kumanda.telefon.ANSWER";
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID_LIVE, liveNotification("Bilgisayara bağlanıyor…"));
        stopped = false;
        if (intent != null && ACT_ANSWER.equals(intent.getAction())) {
            // Bildirimdeki dugme: Onayla / Hayir / Izin ver -> bilgisayara ws ile ilet
            String tabId = intent.getStringExtra("tabId"), key = intent.getStringExtra("key"), kind = intent.getStringExtra("kind");
            try {
                JSONObject j = "consent".equals(kind) ? new JSONObject().put("type", "action").put("name", "consent").put("tabId", tabId)
                                                     : new JSONObject().put("type", "answer").put("tabId", tabId).put("key", key);
                if (ws == null || !ws.send(j.toString())) { pendingSend = j.toString(); connect(); }
            } catch (Exception ignored) {}
            try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(intent.getIntExtra("nid", 0)); } catch (Exception ignored) {}
            return START_STICKY;
        }
        connect();
        return START_STICKY;
    }
    private String pendingSend = null;

    private Notification liveNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("Kumanda").setContentText(text)
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(pi).build();
    }
    private void updateLive(String text) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_LIVE, liveNotification(text)); } catch (Exception ignored) {}
    }

    /** Kayitli adresten ws adresi ve anahtar: http://ip:port/#TOKEN ya da ?t=TOKEN */
    private String[] target() {
        SharedPreferences p = getSharedPreferences("kumanda", MODE_PRIVATE);
        String url = p.getString("url", null); if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String token = u.getFragment(); if (token == null || token.isEmpty()) token = u.getQueryParameter("t");
            if (token == null) return null;
            String scheme = "https".equals(u.getScheme()) ? "wss" : "ws";
            int port = u.getPort(); String host = u.getHost();
            return new String[]{ scheme + "://" + host + (port > 0 ? ":" + port : "") + "/", token };
        } catch (Exception e) { return null; }
    }

    private void connect() {
        if (stopped) return;
        String[] t = target();
        if (t == null) { updateLive("Adres yok · uygulamayı açıp QR okut"); return; }
        try { if (ws != null) ws.cancel(); } catch (Exception ignored) {}
        Request req = new Request.Builder().url(t[0]).build();
        final String token = t[1];
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                retry = 0; updateLive("Bilgisayara bağlı");
                try { w.send(new JSONObject().put("type", "auth").put("token", token).toString()); } catch (Exception ignored) {}
                if (pendingSend != null) { final String p = pendingSend; pendingSend = null; h.postDelayed(() -> { try { w.send(p); } catch (Exception ignored) {} }, 400); }
            }
            @Override public void onMessage(WebSocket w, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    if ("notify".equals(m.optString("type"))) show(m);
                } catch (Exception ignored) {}
            }
            @Override public void onClosed(WebSocket w, int code, String reason) { scheduleReconnect(code == 1008 ? "Anahtar geçersiz · QR'ı yeniden okut" : "Bağlantı koptu · yeniden deneniyor"); }
            @Override public void onFailure(WebSocket w, Throwable e, Response r) { scheduleReconnect("Bilgisayara ulaşılamıyor · yeniden deneniyor"); }
        });
    }
    private void scheduleReconnect(String why) {
        if (stopped) return;
        updateLive(why);
        long wait = Math.min(60000, 2000L * (long) Math.pow(2, Math.min(retry++, 5)));
        h.postDelayed(this::connect, wait);
    }

    private PendingIntent answerIntent(int nid, String tabId, String key, String kind, int which) {
        Intent i = new Intent(this, NotifyService.class).setAction(ACT_ANSWER).putExtra("tabId", tabId).putExtra("key", key).putExtra("kind", kind).putExtra("nid", nid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, nid * 10 + which, i, flags) : PendingIntent.getService(this, nid * 10 + which, i, flags);
    }
    /** Uygulama acilinca olay bildirimlerini temizle (simge rozeti sifirlansin) */
    public static void clearEvents(Context c) {
        try { NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE); if (Build.VERSION.SDK_INT >= 23) { for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() != ID_LIVE) nm.cancel(s.getId()); } } catch (Exception ignored) {}
    }
    private void show(JSONObject m) {
        String kind = m.optString("kind"), title = m.optString("title", "Kumanda"), body = m.optString("body", "");
        String key = kind + "|" + m.optString("tabId") + "|" + body;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastAt < 15000) return;   // ayni olayi iki kez gosterme
        lastKey = key; lastAt = now;
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String icon = "done".equals(kind) ? "✅ " : "approval".equals(kind) || "consent".equals(kind) ? "⚠️ " : "❓ ";
        int nid = ("kumanda-" + kind + "-" + m.optString("tabId")).hashCode();
        int pendingN = 1; try { if (Build.VERSION.SDK_INT >= 23) { pendingN = 0; for (android.service.notification.StatusBarNotification sn : ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).getActiveNotifications()) if (sn.getId() != ID_LIVE) pendingN++; pendingN++; } } catch (Exception ignored) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_EVENTS)
            .setNumber(pendingN).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(icon + title).setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS)
            .setVibrate(new long[]{0, 250, 120, 250})
            .setCategory("done".equals(kind) ? NotificationCompat.CATEGORY_STATUS : NotificationCompat.CATEGORY_REMINDER);
        // Dogrudan bildirimden yanit: Onayla / Hayir (onay), Izin ver (izin). Soru icin uygulamayi ac.
        if ("approval".equals(kind) || "consent".equals(kind)) {
            String tabId = m.optString("tabId");
            if ("approval".equals(kind)) {
                b.addAction(0, "✅ Onayla", answerIntent(nid, tabId, "yes", kind, 1));
                b.addAction(0, "✋ Hayır", answerIntent(nid, tabId, "no", kind, 2));
            } else b.addAction(0, "✅ İzin ver", answerIntent(nid, tabId, "yes", kind, 1));
        }
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(nid, b.build()); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() { stopped = true; try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {} super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

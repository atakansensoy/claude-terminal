package app.kumanda.telefon;

import android.Manifest;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import androidx.activity.result.ActivityResultLauncher;

/**
 * Kumanda telefon uygulamasi: ince bir pencere. Icerik bilgisayardaki Kumanda'nin adresinden gelir;
 * APK'yi guncellemeye gerek kalmadan tum yenilikler kendiliginden yansir.
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "kumanda";
    private static final String KEY_URL = "url";
    private static final String CHANNEL = "kumanda";
    private WebView web;
    private SharedPreferences prefs;
    private PermissionRequest pendingPerm;
    private ActivityResultLauncher<ScanOptions> scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        scanner = registerForActivityResult(new ScanContract(), r -> { if (r.getContents() != null) connectTo(r.getContents()); });
        createChannel();
        String fromIntent = urlFromIntent(getIntent());
        if (fromIntent != null) connectTo(fromIntent);
        else if (prefs.getString(KEY_URL, null) != null) openWeb(prefs.getString(KEY_URL, null));
        else showSetup(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String u = urlFromIntent(intent);
        if (u != null) connectTo(u);
    }

    private String urlFromIntent(Intent i) {
        if (i == null || i.getData() == null) return null;
        Uri d = i.getData();
        if ("kumanda".equals(d.getScheme())) {
            if ("setup".equals(d.getHost())) { showSetup(null); return null; }
            String u = d.getQueryParameter("u");
            return u;
        }
        return null;
    }

    // ---------- Kurulum ekrani ----------
    private void showSetup(String err) {
        if (web != null) { web.destroy(); web = null; }
        setContentView(R.layout.setup);
        Button scan = findViewById(R.id.scan), go = findViewById(R.id.go);
        EditText url = findViewById(R.id.url);
        TextView e = findViewById(R.id.err);
        if (err != null) e.setText(err);
        String prev = prefs.getString(KEY_URL, null);
        if (prev != null) url.setText(prev);
        scan.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 11); return;
            }
            startScan();
        });
        go.setOnClickListener(v -> connectTo(url.getText().toString().trim()));
        url.setOnEditorActionListener((tv, a, ev) -> { connectTo(url.getText().toString().trim()); return true; });
    }

    private void startScan() {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt("Bilgisayardaki Telefon penceresindeki QR kodu okut");
        o.setBeepEnabled(false);
        o.setOrientationLocked(true);
        scanner.launch(o);
    }

    private void connectTo(String raw) {
        if (raw == null) return;
        String u = raw.trim();
        if (u.isEmpty()) { showSetup("Adres boş."); return; }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (!u.contains("#") && !u.contains("?t=")) { showSetup("Adreste anahtar yok (# işaretinden sonraki bölüm). QR kodu okutmayı dene."); return; }
        prefs.edit().putString(KEY_URL, u).apply();
        openWeb(u);
    }

    // ---------- Web gorunumu ----------
    @SuppressWarnings("SetJavaScriptEnabled")
    private void openWeb(String url) {
        if (web == null) {
            web = new WebView(this);
            setContentView(web);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setSupportZoom(false);
            s.setUserAgentString(s.getUserAgentString() + " KumandaApp/1.0");
            CookieManager.getInstance().setAcceptCookie(true);
            web.setBackgroundColor(0xFF0B0C10);
            web.addJavascriptInterface(new Bridge(), "KumandaApp");
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if ("kumanda".equals(u.getScheme())) { if ("setup".equals(u.getHost())) showSetup(null); return true; }
                    // Ayni sunucu icindeki sayfalar uygulamada kalir; dis baglantilar tarayicida acilir
                    String base = prefs.getString(KEY_URL, "");
                    if (base.startsWith(u.getScheme() + "://" + u.getAuthority())) return false;
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                    if (req.isForMainFrame()) v.loadData(offlineHtml(), "text/html; charset=utf-8", "utf-8");
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(PermissionRequest request) {
                    // Mikrofon (konusarak yazma): once Android izni
                    boolean mic = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) mic = true;
                    if (!mic) { request.deny(); return; }
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(request.getResources());
                    else { pendingPerm = request; ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, 12); }
                }
            });
            web.setDownloadListener((u, ua, cd, mime, len) -> {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u));
                    String name = URLUtil.guessFileName(u, cd, mime);
                    r.setTitle(name); r.setMimeType(mime);
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                    Toast.makeText(this, "İndiriliyor: " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(this, "İndirilemedi", Toast.LENGTH_SHORT).show(); }
            });
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            askNotifications();
        }
        web.loadUrl(url);
    }

    private String offlineHtml() {
        return "<html><body style='background:#0b0c10;color:#c9d1e0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>" +
               "<div><div style='font-size:40px'>📡</div><h3>Bilgisayara ulaşılamıyor</h3><p style='color:#8a93a3;max-width:300px'>Bilgisayar açık ve Kumanda çalışıyor mu? Telefon aynı Wi‑Fi'de ya da Tailscale'de mi? Birkaç saniyede yeniden denenir.</p>" +
               "<p><a href='kumanda://setup' style='color:#e08a5b'>Adresi değiştir</a></p></div>" +
               "<script>setTimeout(function(){location.href='" + prefs.getString(KEY_URL, "") + "'},6000)</script></body></html>";
    }

    // ---------- Bildirimler ----------
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Kumanda", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Yanıt hazır, onay bekliyor");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }
    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
    }

    /** Sayfanin (remote.html) kullandigi kopru: window.KumandaApp.* */
    private class Bridge {
        @JavascriptInterface public void notify(String title, String body, String tag) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            Intent i = new Intent(MainActivity.this, MainActivity.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(MainActivity.this, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(MainActivity.this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(tag == null ? 1 : tag.hashCode(), b.build());
        }
        @JavascriptInterface public void setup() { runOnUiThread(() -> showSetup(null)); }
        @JavascriptInterface public String version() { return "1.0"; }
        @JavascriptInterface public void keepScreenOn(boolean on) { runOnUiThread(() -> { if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }); }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, p, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (code == 11 && ok) startScan();
        if (code == 12 && pendingPerm != null) { if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny(); pendingPerm = null; }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            // Sayfa icinde acik bir alt ekran varsa once onu kapat (sayfa kendi halleder), yoksa uygulamayi arka plana al
            web.evaluateJavascript("(function(){return window.kumandaBack ? !!window.kumandaBack() : false})()", v -> { if (!"true".equals(v)) moveTaskToBack(true); });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
}

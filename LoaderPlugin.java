package de.foxloader.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

// Fox Loader: Links von X, TikTok, Instagram, Facebook, Reddit, YouTube auslesen,
// Videos/Bilder/Ton in die Galerie laden, Video und Ton zusammenfuegen, teilen, abspielen.
@CapacitorPlugin(name = "Loader")
public class LoaderPlugin extends Plugin {

    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String FOLDER = "Fox Loader";

    private final Set<String> cancelled = Collections.synchronizedSet(new HashSet<String>());
    private String pendingShare = null;

    // ---------------- Teilen an Fox Loader ----------------

    @Override
    public void load() {
        Activity a = getActivity();
        if (a != null) {
            String t = textOf(a.getIntent());
            if (t != null) {
                pendingShare = t;
            }
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String t = textOf(intent);
        if (t != null) {
            JSObject o = new JSObject();
            o.put("text", t);
            notifyListeners("shared", o, true);
        }
    }

    private String textOf(Intent i) {
        if (i == null) {
            return null;
        }
        String act = i.getAction();
        String t = null;
        if (Intent.ACTION_SEND.equals(act)) {
            t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t == null) {
                CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                t = cs == null ? null : cs.toString();
            }
        } else if (Intent.ACTION_VIEW.equals(act)) {
            t = i.getDataString();
        }
        if (t != null) {
            i.setAction(Intent.ACTION_MAIN); // nur einmal verarbeiten
        }
        return t;
    }

    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject o = new JSObject();
        o.put("text", pendingShare == null ? "" : pendingShare);
        pendingShare = null;
        call.resolve(o);
    }

    @PluginMethod
    public void clip(PluginCall call) {
        JSObject o = new JSObject();
        String t = "";
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence cs = cd.getItemAt(0).coerceToText(getContext());
                    if (cs != null) {
                        t = cs.toString();
                    }
                }
            }
        } catch (Exception e) {
            // Zwischenablage nicht lesbar
        }
        o.put("text", t);
        call.resolve(o);
    }

    // ---------------- Internet ----------------

    private static class Req {
        String method = "GET";
        String body = null;
        JSObject headers = new JSObject();
        boolean cookies = false;
    }

    private Req reqOf(PluginCall call) {
        Req r = new Req();
        r.method = call.getString("method", "GET");
        r.body = call.getString("body", null);
        JSObject h = call.getObject("headers", null);
        if (h != null) {
            r.headers = h;
        }
        r.cookies = Boolean.TRUE.equals(call.getBoolean("cookies", false));
        return r;
    }

    private static String webCookies(String url) {
        try {
            String c = CookieManager.getInstance().getCookie(url);
            return c == null ? "" : c;
        } catch (Exception e) {
            return "";
        }
    }

    // Verbindung oeffnen, Weiterleitungen (Kurzlinks) selbst verfolgen
    private HttpURLConnection open(String url, Req r, String range) throws Exception {
        String u = url;
        String method = r == null ? "GET" : r.method;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(40000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
            if (r != null) {
                Iterator<String> it = r.headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, r.headers.getString(k));
                }
                if (r.cookies) {
                    String ck = webCookies(u);
                    String extra = r.headers.has("Cookie") ? r.headers.getString("Cookie") : "";
                    String all = (ck + (ck.isEmpty() || extra.isEmpty() ? "" : "; ") + extra).trim();
                    if (!all.isEmpty()) {
                        c.setRequestProperty("Cookie", all);
                    }
                }
            }
            if (range != null) {
                c.setRequestProperty("Range", range);
            }
            if (r != null && r.body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                c.setDoOutput(true);
                byte[] b = r.body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new Exception("Weiterleitung ohne Ziel");
                }
                u = new URL(new URL(u), loc).toString();
                if (code == 303 || ((code == 301 || code == 302) && "POST".equals(method))) {
                    method = "GET";
                }
                continue;
            }
            return c;
        }
        throw new Exception("Zu viele Weiterleitungen");
    }

    @PluginMethod
    public void get(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        final Req r = reqOf(call);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = open(url, r, null);
                    int code = c.getResponseCode();
                    String body = "";
                    if (!"HEAD".equals(r.method)) {
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        if (in != null) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) > 0 && bo.size() < 12000000) {
                                bo.write(buf, 0, n);
                            }
                            in.close();
                            body = bo.toString("UTF-8");
                        }
                    }
                    StringBuilder sc = new StringBuilder();
                    List<String> cookies = c.getHeaderFields().get("Set-Cookie");
                    if (cookies == null) {
                        cookies = c.getHeaderFields().get("set-cookie");
                    }
                    if (cookies != null) {
                        for (String s : cookies) {
                            String p = s.split(";", 2)[0];
                            if (sc.length() > 0) {
                                sc.append("; ");
                            }
                            sc.append(p);
                        }
                    }
                    JSObject o = new JSObject();
                    o.put("status", code);
                    o.put("body", body);
                    o.put("url", c.getURL().toString());
                    o.put("cookies", sc.toString());
                    o.put("type", c.getContentType() == null ? "" : c.getContentType());
                    o.put("length", c.getContentLength());
                    call.resolve(o);
                } catch (Exception e) {
                    call.reject("Keine Verbindung: " + e.getMessage());
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    // Vorschaubild als data:-Adresse (manche Seiten sperren Bilder in fremden Apps)
    @PluginMethod
    public void image(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        final Req r = reqOf(call);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = open(url, r, null);
                    if (c.getResponseCode() != 200) {
                        throw new Exception("HTTP " + c.getResponseCode());
                    }
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > 4000000) {
                            throw new Exception("zu gross");
                        }
                    }
                    in.close();
                    String type = c.getContentType();
                    if (type == null || !type.startsWith("image/")) {
                        type = "image/jpeg";
                    }
                    JSObject o = new JSObject();
                    o.put("data", "data:" + type.split(";")[0] + ";base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                    call.resolve(o);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    // ---------------- Abruf wie im Browser (unsichtbare Seite der jeweiligen Plattform) ----------------
    // Instagram blockt Abrufe, die nicht aus einer echten Webseite kommen. Darum laeuft der Abruf
    // in einer unsichtbaren WebView auf instagram.com, mit allen Cookies und Kopfzeilen eines Browsers.

    static final String DESK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private WebView hv = null;
    private String hvOrigin = null;
    private boolean hvReady = false;
    private final List<Runnable> hvWait = new ArrayList<>();
    private final HashMap<String, PluginCall> hvCalls = new HashMap<>();
    private int hvSeq = 0;

    public class HvBridge {
        @JavascriptInterface
        public void done(String id, int status, String body) {
            PluginCall c;
            synchronized (hvCalls) {
                c = hvCalls.remove(id);
            }
            if (c != null) {
                JSObject o = new JSObject();
                o.put("status", status);
                o.put("body", body == null ? "" : body);
                c.resolve(o);
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureHv(Activity act, String origin) {
        if (hv == null) {
            hv = new WebView(act);
            WebSettings st = hv.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setUserAgentString(DESK_UA);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(hv, true);
            hv.addJavascriptInterface(new HvBridge(), "FoxHv");
            hv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!hvReady) {
                        hvReady = true;
                        List<Runnable> w = new ArrayList<>(hvWait);
                        hvWait.clear();
                        for (Runnable r : w) {
                            r.run();
                        }
                    }
                }
            });
        }
        if (!origin.equals(hvOrigin)) {
            hvOrigin = origin;
            hvReady = false;
            hv.loadUrl(origin + "/robots.txt");
        }
    }

    @PluginMethod
    public void webFetch(final PluginCall call) {
        final String origin = call.getString("origin", "https://www.instagram.com");
        final String url = call.getString("url", "");
        final String method = call.getString("method", "GET");
        final String body = call.getString("body", null);
        JSObject h = call.getObject("headers", null);
        final String headers = h == null ? "{}" : h.toString();
        final Activity act = getActivity();
        if (act == null || url == null || !url.startsWith(origin + "/")) {
            call.reject("Adresse passt nicht");
            return;
        }
        final String id = "f" + (++hvSeq);
        synchronized (hvCalls) {
            hvCalls.put(id, call);
        }
        final String js = "(function(){var h=" + headers + ";"
                + "try{var m=document.cookie.match(/(?:^|; )csrftoken=([^;]+)/);if(m&&!h['X-CSRFToken'])h['X-CSRFToken']=m[1];}catch(e){}"
                + "fetch(" + JSONObject.quote(url) + ",{method:" + JSONObject.quote(method) + ",headers:h,credentials:'include'"
                + (body != null ? ",body:" + JSONObject.quote(body) : "") + "})"
                + ".then(function(r){return r.text().then(function(t){FoxHv.done('" + id + "',r.status,t);});})"
                + ".catch(function(e){FoxHv.done('" + id + "',0,String(e));});})();";
        act.runOnUiThread(() -> {
            try {
                ensureHv(act, origin);
                Runnable run = () -> hv.evaluateJavascript(js, null);
                if (hvReady) {
                    run.run();
                } else {
                    hvWait.add(run);
                }
            } catch (Throwable e) {
                PluginCall c;
                synchronized (hvCalls) {
                    c = hvCalls.remove(id);
                }
                if (c != null) {
                    c.reject(String.valueOf(e.getMessage()));
                }
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PluginCall c;
            synchronized (hvCalls) {
                c = hvCalls.remove(id);
            }
            if (c != null) {
                c.reject("Zeitueberschreitung");
            }
        }, 45000);
    }

    // ---------------- Seite im Hintergrund oeffnen und mitlesen ----------------
    // Oeffnet z. B. ein Instagram-Profil unsichtbar wie ein Browser und reicht alle Daten,
    // die die Seite selbst laedt und die "match" enthalten, an die App weiter.

    private WebView gv = null;
    private String gMatch = "";

    public class GrabBridge {
        @JavascriptInterface
        public void data(String text) {
            JSObject o = new JSObject();
            o.put("text", text);
            notifyListeners("grab", o);
        }

        @JavascriptInterface
        public void url(String u) {
            JSObject o = new JSObject();
            o.put("url", u);
            notifyListeners("grabUrl", o);
        }
    }

    private String hookJs() {
        return "(function(){if(window.__foxHook)return;window.__foxHook=1;var M=" + JSONObject.quote(gMatch) + ";"
                + "function send(t){try{if(t&&typeof t==='string'&&t.indexOf(M)>=0)FoxGrab.data(t);}catch(e){}}"
                + "try{var of=window.fetch;window.fetch=function(){return of.apply(this,arguments).then(function(r){try{r.clone().text().then(send).catch(function(){});}catch(e){}return r;});};}catch(e){}"
                + "try{var os=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){var x=this;"
                + "x.addEventListener('load',function(){try{if(!x.responseType||x.responseType==='text')send(x.responseText);}catch(e){}});return os.apply(this,arguments);};}catch(e){}"
                + "})();";
    }

    private static final String SCAN_JS = "(function(){try{FoxGrab.url(location.href);var s=document.scripts;for(var i=0;i<s.length;i++){var t=s[i].textContent;"
            + "if(t&&t.indexOf(window.__foxM||'')>=0)FoxGrab.data(t);}}catch(e){}})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "RequiresFeature"})
    @PluginMethod
    public void grabStart(final PluginCall call) {
        final String url = call.getString("url", "");
        final String match = call.getString("match", "");
        final Activity act = getActivity();
        if (act == null || url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        act.runOnUiThread(() -> {
            try {
                gMatch = match;
                if (gv == null) {
                    gv = new WebView(act);
                    WebSettings st = gv.getSettings();
                    st.setJavaScriptEnabled(true);
                    st.setDomStorageEnabled(true);
                    st.setUserAgentString(DESK_UA);
                    st.setMediaPlaybackRequiresUserGesture(true);
                    st.setLoadsImagesAutomatically(false);
                    CookieManager.getInstance().setAcceptThirdPartyCookies(gv, true);
                    gv.addJavascriptInterface(new GrabBridge(), "FoxGrab");
                    gv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageCommitVisible(WebView view, String u) {
                            view.evaluateJavascript(hookJs(), null);
                        }

                        @Override
                        public void onPageFinished(WebView view, String u) {
                            view.evaluateJavascript(hookJs(), null);
                            view.evaluateJavascript("window.__foxM=" + JSONObject.quote(gMatch) + ";" + SCAN_JS, null);
                            view.postDelayed(() -> {
                                if (gv != null) {
                                    gv.evaluateJavascript(SCAN_JS, null);
                                }
                            }, 2500);
                        }
                    });
                    // hinter der App-Oberflaeche einhaengen, damit die Seite eine echte Groesse hat
                    ViewGroup content = act.findViewById(android.R.id.content);
                    content.addView(gv, 0, new ViewGroup.LayoutParams(dp(act, 420), dp(act, 900)));
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    try {
                        HashSet<String> rules = new HashSet<>();
                        rules.add("*");
                        WebViewCompat.addDocumentStartJavaScript(gv, hookJs(), rules);
                    } catch (Throwable ignored) {
                        // aeltere WebView: Haken kommt dann beim Seitenaufbau
                    }
                }
                gv.loadUrl(url);
                call.resolve();
            } catch (Throwable e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    @PluginMethod
    public void grabScroll(final PluginCall call) {
        final Activity act = getActivity();
        if (act == null) {
            call.resolve();
            return;
        }
        act.runOnUiThread(() -> {
            if (gv != null) {
                gv.evaluateJavascript("window.scrollBy(0,-300);setTimeout(function(){window.scrollTo(0,document.documentElement.scrollHeight);},200);", null);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void grabStop(final PluginCall call) {
        final Activity act = getActivity();
        if (act == null) {
            call.resolve();
            return;
        }
        act.runOnUiThread(() -> {
            if (gv != null) {
                try {
                    ViewGroup content = act.findViewById(android.R.id.content);
                    content.removeView(gv);
                    gv.destroy();
                } catch (Exception ignored) {
                    // egal
                }
                gv = null;
            }
            call.resolve();
        });
    }

    // ---------------- Anmeldung (Instagram, Facebook) ----------------

    private static String siteUrl(String site) {
        return "facebook".equals(site) ? "https://www.facebook.com" : "x".equals(site) ? "https://x.com" : "https://www.instagram.com";
    }

    private static boolean loggedIn(String site) {
        String c = webCookies(siteUrl(site));
        return "facebook".equals(site) ? c.contains("c_user=") : "x".equals(site) ? c.contains("auth_token=") : c.contains("sessionid=");
    }

    // Einzelnes Cookie lesen (z. B. ct0 fuer X)
    @PluginMethod
    public void cookie(PluginCall call) {
        String all = webCookies(call.getString("url", "https://x.com"));
        String name = call.getString("name", "");
        String val = "";
        for (String part : all.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                val = kv[1];
            }
        }
        JSObject o = new JSObject();
        o.put("value", val);
        call.resolve(o);
    }

    @PluginMethod
    public void logins(PluginCall call) {
        JSObject o = new JSObject();
        o.put("instagram", loggedIn("instagram"));
        o.put("facebook", loggedIn("facebook"));
        o.put("x", loggedIn("x"));
        call.resolve(o);
    }

    @PluginMethod
    public void logout(PluginCall call) {
        String site = call.getString("site", "instagram");
        String[] domains = "facebook".equals(site)
                ? new String[]{"https://www.facebook.com", "https://m.facebook.com", "https://facebook.com"}
                : "x".equals(site) ? new String[]{"https://x.com", "https://twitter.com"}
                : new String[]{"https://www.instagram.com", "https://instagram.com"};
        String dom = "facebook".equals(site) ? ".facebook.com" : "x".equals(site) ? ".x.com" : ".instagram.com";
        CookieManager cm = CookieManager.getInstance();
        for (String d : domains) {
            String all = cm.getCookie(d);
            if (all == null) {
                continue;
            }
            for (String part : all.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.isEmpty()) {
                    continue;
                }
                String exp = name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                cm.setCookie(d, exp);
                cm.setCookie(d, exp + "; Domain=" + dom);
            }
        }
        cm.flush();
        hvOrigin = null;
        JSObject o = new JSObject();
        o.put("ok", !loggedIn(site));
        call.resolve(o);
    }

    @PluginMethod
    public void login(final PluginCall call) {
        final String site = call.getString("site", "instagram");
        final Activity act = getActivity();
        if (act == null) {
            call.reject("Keine Ansicht");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void run() {
                try {
                    final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    LinearLayout root = new LinearLayout(act);
                    root.setOrientation(LinearLayout.VERTICAL);
                    root.setBackgroundColor(Color.WHITE);

                    LinearLayout bar = new LinearLayout(act);
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setGravity(Gravity.CENTER_VERTICAL);
                    bar.setBackgroundColor(Color.rgb(17, 17, 20));
                    int pad = dp(act, 10);
                    bar.setPadding(dp(act, 16), pad, pad, pad);
                    TextView t = new TextView(act);
                    t.setText("facebook".equals(site) ? "Bei Facebook anmelden" : "x".equals(site) ? "Bei X anmelden" : "Bei Instagram anmelden");
                    t.setTextColor(Color.WHITE);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                    bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    Button done = new Button(act);
                    done.setText("Fertig");
                    done.setAllCaps(false);
                    done.setTextColor(Color.WHITE);
                    done.setBackgroundColor(Color.rgb(232, 100, 12));
                    bar.addView(done);
                    root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    final WebView w = new WebView(act);
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setUserAgentString(UA);
                    CookieManager.getInstance().setAcceptCookie(true);
                    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
                    w.setWebViewClient(new WebViewClient());
                    root.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                    w.loadUrl("facebook".equals(site) ? "https://m.facebook.com/login/" : "x".equals(site) ? "https://x.com/i/flow/login" : "https://www.instagram.com/accounts/login/");

                    done.setOnClickListener(v -> d.dismiss());
                    d.setOnDismissListener(di -> {
                        CookieManager.getInstance().flush();
                        hvOrigin = null;
                        try {
                            w.destroy();
                        } catch (Exception ignored) {
                            // egal
                        }
                        JSObject o = new JSObject();
                        o.put("ok", loggedIn(site));
                        call.resolve(o);
                    });
                    d.setContentView(root);
                    d.show();
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    // ---------------- Vorschau (Video/Ton in der App abspielen, ohne zu speichern) ----------------

    @SuppressLint("UnsafeOptInUsageError")
    @PluginMethod
    public void preview(final PluginCall call) {
        final List<JSONObject> items = new ArrayList<>();
        try {
            JSArray arr = call.getArray("items", null);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    items.add(arr.getJSONObject(i));
                }
            }
            if (items.isEmpty()) {
                JSONObject one = new JSONObject();
                one.put("url", call.getString("url", ""));
                one.put("audio", call.getString("audio", null));
                one.put("title", call.getString("title", "Vorschau"));
                JSObject h = call.getObject("headers", null);
                if (h != null) {
                    one.put("headers", h);
                }
                items.add(one);
            }
        } catch (Exception e) {
            call.reject("Liste nicht lesbar");
            return;
        }
        final int start = Math.max(0, Math.min(items.size() - 1, call.getInt("start", 0)));
        final boolean canLoad = !Boolean.FALSE.equals(call.getBoolean("download", true));
        final boolean cookies = Boolean.TRUE.equals(call.getBoolean("cookies", false));
        final Activity act = getActivity();
        if (act == null) {
            call.reject("Keine Ansicht");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int n = items.size();
                    final int[] idx = {start};
                    final boolean[] done = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        done[i] = items.get(i).optBoolean("done", false);
                    }
                    final ExoPlayer[] P = new ExoPlayer[1];

                    LinearLayout root = new LinearLayout(act);
                    root.setOrientation(LinearLayout.VERTICAL);
                    root.setBackgroundColor(Color.BLACK);

                    LinearLayout bar = new LinearLayout(act);
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setGravity(Gravity.CENTER_VERTICAL);
                    bar.setPadding(dp(act, 6), dp(act, 8), dp(act, 10), dp(act, 8));
                    Button close = new Button(act);
                    close.setText("\u2715");
                    close.setTextColor(Color.WHITE);
                    close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                    close.setBackgroundColor(Color.TRANSPARENT);
                    bar.addView(close, new LinearLayout.LayoutParams(dp(act, 52), dp(act, 48)));
                    final TextView t = new TextView(act);
                    t.setTextColor(Color.WHITE);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    t.setMaxLines(2);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    final Button load = new Button(act);
                    load.setAllCaps(false);
                    load.setTextColor(Color.WHITE);
                    load.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    load.setPadding(dp(act, 16), 0, dp(act, 16), 0);
                    if (canLoad) {
                        bar.addView(load, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 44)));
                    }
                    root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    // Quellen bauen (jede mit eigenen Kopfzeilen; Bild+Ton zusammengesetzt)
                    List<MediaSource> srcs = new ArrayList<>();
                    for (JSONObject it : items) {
                        HashMap<String, String> hdr = new HashMap<>();
                        String ua = UA;
                        JSONObject h = it.optJSONObject("headers");
                        if (h != null) {
                            Iterator<String> ks = h.keys();
                            while (ks.hasNext()) {
                                String k = ks.next();
                                if ("User-Agent".equalsIgnoreCase(k)) {
                                    ua = h.optString(k, UA);
                                } else {
                                    hdr.put(k, h.optString(k, ""));
                                }
                            }
                        }
                        String url = it.optString("url", "");
                        if (cookies) {
                            String ck = webCookies(url);
                            if (!ck.isEmpty()) {
                                hdr.put("Cookie", ck);
                            }
                        }
                        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory()
                                .setUserAgent(ua)
                                .setAllowCrossProtocolRedirects(true)
                                .setConnectTimeoutMs(20000)
                                .setReadTimeoutMs(30000)
                                .setDefaultRequestProperties(hdr);
                        ProgressiveMediaSource.Factory pf = new ProgressiveMediaSource.Factory(hf);
                        MediaSource src = pf.createMediaSource(MediaItem.fromUri(url));
                        String audio = it.isNull("audio") ? "" : it.optString("audio", "");
                        if (audio.startsWith("https://")) {
                            src = new MergingMediaSource(src, pf.createMediaSource(MediaItem.fromUri(audio)));
                        }
                        srcs.add(src);
                    }
                    final ExoPlayer player = new ExoPlayer.Builder(act).build();
                    P[0] = player;
                    player.setMediaSources(srcs, start, 0);
                    player.setRepeatMode(Player.REPEAT_MODE_ONE);
                    player.prepare();
                    player.setPlayWhenReady(true);

                    final PlayerView pv = new PlayerView(act);
                    pv.setPlayer(player);
                    pv.setKeepScreenOn(true);
                    root.addView(pv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                    final TextView info = new TextView(act);
                    info.setTextColor(Color.rgb(200, 200, 200));
                    info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    info.setGravity(Gravity.CENTER);
                    info.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 14));
                    root.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    final Runnable upd = new Runnable() {
                        @Override
                        public void run() {
                            int i = idx[0];
                            String title = items.get(i).optString("title", "Vorschau");
                            t.setText(n > 1 ? (i + 1) + " / " + n + "  \u00b7  " + title : title);
                            load.setText(done[i] ? "\u2713 Geladen" : "\u2193 Laden");
                            load.setBackgroundColor(done[i] ? Color.rgb(23, 138, 90) : Color.rgb(232, 100, 12));
                            info.setTextColor(Color.rgb(200, 200, 200));
                            info.setText(n > 1 ? "Nach oben wischen = n\u00e4chstes Video \u00b7 nach unten = vorheriges" : "");
                            info.setVisibility(n > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
                        }
                    };
                    upd.run();

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlayerError(PlaybackException e) {
                            info.setVisibility(android.view.View.VISIBLE);
                            info.setTextColor(Color.rgb(255, 170, 150));
                            info.setText("Vorschau geht hier nicht (" + e.getErrorCodeName() + "). Laden klappt trotzdem meistens." + (n > 1 ? "\nWeiterwischen zum n\u00e4chsten Video." : ""));
                        }

                        @Override
                        public void onMediaItemTransition(MediaItem mi, int reason) {
                            int c = player.getCurrentMediaItemIndex();
                            if (c >= 0 && c < n) {
                                idx[0] = c;
                                upd.run();
                            }
                        }
                    });

                    final GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                            if (e1 == null || n < 2) {
                                return false;
                            }
                            float dy = e2.getY() - e1.getY();
                            float dx = e2.getX() - e1.getX();
                            if (Math.abs(dy) < dp(act, 70) || Math.abs(dy) < Math.abs(dx) * 1.3f || Math.abs(vy) < 500) {
                                return false;
                            }
                            int nx = idx[0] + (dy < 0 ? 1 : -1);
                            if (nx < 0 || nx >= n) {
                                info.setVisibility(android.view.View.VISIBLE);
                                info.setTextColor(Color.rgb(255, 210, 120));
                                info.setText(nx < 0 ? "Das ist das erste Video." : "Das war das letzte Video.");
                                return true;
                            }
                            idx[0] = nx;
                            P[0].seekTo(nx, 0);
                            P[0].setPlayWhenReady(true);
                            upd.run();
                            return true;
                        }
                    });

                    final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
                        @Override
                        public boolean dispatchTouchEvent(MotionEvent ev) {
                            if (gd.onTouchEvent(ev)) {
                                MotionEvent c = MotionEvent.obtain(ev);
                                c.setAction(MotionEvent.ACTION_CANCEL);
                                super.dispatchTouchEvent(c);
                                c.recycle();
                                return true;
                            }
                            return super.dispatchTouchEvent(ev);
                        }
                    };

                    close.setOnClickListener(v -> d.dismiss());
                    load.setOnClickListener(v -> {
                        int i = idx[0];
                        if (done[i]) {
                            return;
                        }
                        done[i] = true;
                        upd.run();
                        JSObject o = new JSObject();
                        o.put("index", i);
                        notifyListeners("previewDownload", o);
                    });
                    d.setOnDismissListener(di -> {
                        try {
                            pv.setPlayer(null);
                            player.release();
                        } catch (Exception ignored) {
                            // egal
                        }
                        JSObject o = new JSObject();
                        o.put("action", "close");
                        o.put("index", idx[0]);
                        call.resolve(o);
                    });
                    d.setContentView(root);
                    d.show();
                } catch (Throwable e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- Herunterladen ----------------

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled.add(call.getString("id", ""));
        call.resolve();
    }

    private interface Prog {
        void on(long got, long total);
    }

    // Laedt eine Adresse in einen Strom. chunked: in 8-MB-Stuecken (YouTube drosselt sonst).
    private long fetchTo(String id, String url, Req r, boolean chunked, OutputStream out, Prog p) throws Exception {
        long got = 0;
        long total = -1;
        final long CH = 8L * 1024 * 1024;
        byte[] buf = new byte[65536];
        while (true) {
            String range = chunked ? "bytes=" + got + "-" + (got + CH - 1) : null;
            HttpURLConnection c = open(url, r, range);
            try {
                int code = c.getResponseCode();
                if (code == 416 && chunked && got > 0) {
                    break;
                }
                if (code != 200 && code != 206) {
                    throw new Exception("Server antwortet mit " + code);
                }
                if (total < 0) {
                    String cr = c.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        try {
                            total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                        } catch (Exception ignored) {
                            total = -1;
                        }
                    }
                    if (total < 0 && code == 200) {
                        total = c.getContentLength();
                    }
                }
                InputStream in = c.getInputStream();
                long part = 0;
                int n;
                long lastT = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.contains(id)) {
                        in.close();
                        throw new InterruptedException("abgebrochen");
                    }
                    out.write(buf, 0, n);
                    got += n;
                    part += n;
                    long now = System.currentTimeMillis();
                    if (now - lastT > 200) {
                        lastT = now;
                        p.on(got, total);
                    }
                }
                in.close();
                if (!chunked || code == 200 || part < CH || (total > 0 && got >= total)) {
                    break;
                }
            } finally {
                c.disconnect();
            }
        }
        p.on(got, total);
        if (total > 0 && got < total) {
            throw new Exception("Download unvollstaendig");
        }
        if (got == 0) {
            throw new Exception("Leere Datei");
        }
        return got;
    }

    private void progress(String id, int pct, long got, String phase) {
        JSObject p = new JSObject();
        p.put("id", id);
        p.put("got", got);
        p.put("pct", pct);
        p.put("phase", phase);
        notifyListeners("progress", p);
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String id = call.getString("id", "x");
        final String url = call.getString("url", "");
        final String audio = call.getString("audio", null);
        final String kind = call.getString("kind", "video");
        final String extIn = call.getString("ext", "");
        final boolean chunked = Boolean.TRUE.equals(call.getBoolean("chunked", false));
        final boolean toCache = Boolean.TRUE.equals(call.getBoolean("toCache", false));
        final String name = clean(call.getString("name", "fox"));
        final Req r = reqOf(call);
        r.method = "GET";
        r.body = null;
        if (url == null || !url.startsWith("https://") || (audio != null && !audio.startsWith("https://"))) {
            call.reject("Ungueltige Adresse");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (getActivity() != null) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4711);
            }
            call.reject("storage");
            return;
        }
        cancelled.remove(id);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String ext = extIn == null || extIn.isEmpty()
                        ? ("image".equals(kind) ? "jpg" : "audio".equals(kind) ? "m4a" : "mp4") : extIn.toLowerCase();
                String mime = mimeOf(ext);
                String file = name + "." + ext;
                File tmpV = new File(getContext().getCacheDir(), "dl-" + id + ".v");
                File tmpA = new File(getContext().getCacheDir(), "dl-" + id + ".a");
                File tmpM = new File(getContext().getCacheDir(), "dl-" + id + ".mp4");
                Saved sv = null;
                try {
                    long size;
                    if (toCache) {
                        // nur zwischenspeichern (z. B. fuer MP3-Umwandlung in der App)
                        File cf = new File(getContext().getCacheDir(), "conv-" + id.replaceAll("[^A-Za-z0-9_-]", "_") + "." + ext);
                        FileOutputStream fo = new FileOutputStream(cf);
                        try {
                            size = fetchTo(id, url, r, chunked, fo, (g, t) -> progress(id, t > 0 ? (int) (g * 100 / t) : -1, g, "load"));
                        } finally {
                            fo.close();
                        }
                        JSObject res = new JSObject();
                        res.put("path", cf.getAbsolutePath());
                        res.put("size", size);
                        call.resolve(res);
                        return;
                    }
                    if (audio == null) {
                        sv = newFile(kind, file, mime, name, ext);
                        size = fetchTo(id, url, r, chunked, sv.out, (g, t) -> progress(id, t > 0 ? (int) (g * 100 / t) : -1, g, "load"));
                        sv.out.close();
                    } else {
                        // Video und Ton getrennt laden und zusammenfuegen
                        final long[] vt = {0};
                        FileOutputStream fv = new FileOutputStream(tmpV);
                        long gv = fetchTo(id, url, r, chunked, fv, (g, t) -> {
                            vt[0] = t;
                            progress(id, t > 0 ? (int) (g * 85 / t) : -1, g, "video");
                        });
                        fv.close();
                        FileOutputStream fa = new FileOutputStream(tmpA);
                        fetchTo(id, audio, r, chunked, fa, (g, t) -> progress(id, 85 + (t > 0 ? (int) (g * 10 / t) : 0), gv + g, "audio"));
                        fa.close();
                        progress(id, 96, gv, "mux");
                        mux(tmpV, tmpA, tmpM);
                        tmpV.delete();
                        tmpA.delete();
                        sv = newFile(kind, file, mime, name, ext);
                        FileInputStream fi = new FileInputStream(tmpM);
                        byte[] b = new byte[65536];
                        int n;
                        size = 0;
                        while ((n = fi.read(b)) > 0) {
                            sv.out.write(b, 0, n);
                            size += n;
                        }
                        fi.close();
                        sv.out.close();
                        tmpM.delete();
                    }
                    String result = finish(sv, mime);
                    JSObject res = new JSObject();
                    res.put("uri", result);
                    res.put("size", size);
                    res.put("mime", mime);
                    res.put("file", file);
                    call.resolve(res);
                } catch (Throwable e) {
                    if (sv != null) {
                        sv.abort(getContext().getContentResolver());
                    }
                    call.reject(e instanceof InterruptedException ? "abgebrochen" : String.valueOf(e.getMessage()));
                } finally {
                    tmpV.delete();
                    tmpA.delete();
                    tmpM.delete();
                    cancelled.remove(id);
                }
            }
        }).start();
    }

    // ---------------- Datei stueckweise schreiben (fuer Telegram: Daten kommen aus der App-Seite) ----------------

    private final HashMap<String, Saved> parts = new HashMap<>();
    private final HashMap<String, String[]> partInfo = new HashMap<>();
    private final HashMap<String, long[]> partSize = new HashMap<>();

    @PluginMethod
    public void fileOpen(PluginCall call) {
        String id = call.getString("id", "x");
        String kind = call.getString("kind", "video");
        String ext = call.getString("ext", "mp4").toLowerCase();
        String name = clean(call.getString("name", "fox"));
        if (Build.VERSION.SDK_INT < 29
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (getActivity() != null) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4711);
            }
            call.reject("storage");
            return;
        }
        try {
            String mime = mimeOf(ext);
            Saved sv = newFile(kind, name + "." + ext, mime, name, ext);
            synchronized (parts) {
                parts.put(id, sv);
                partInfo.put(id, new String[]{mime, name + "." + ext});
                partSize.put(id, new long[]{0});
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void fileWrite(PluginCall call) {
        String id = call.getString("id", "x");
        Saved sv;
        long[] sz;
        synchronized (parts) {
            sv = parts.get(id);
            sz = partSize.get(id);
        }
        if (sv == null) {
            call.reject("Datei nicht offen");
            return;
        }
        try {
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            sv.out.write(b);
            sz[0] += b.length;
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void fileClose(PluginCall call) {
        String id = call.getString("id", "x");
        Saved sv;
        String[] info;
        long[] sz;
        synchronized (parts) {
            sv = parts.remove(id);
            info = partInfo.remove(id);
            sz = partSize.remove(id);
        }
        if (sv == null) {
            call.reject("Datei nicht offen");
            return;
        }
        try {
            sv.out.close();
            String uri = finish(sv, info[0]);
            JSObject o = new JSObject();
            o.put("uri", uri);
            o.put("mime", info[0]);
            o.put("file", info[1]);
            o.put("size", sz[0]);
            call.resolve(o);
        } catch (Exception e) {
            sv.abort(getContext().getContentResolver());
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void fileAbort(PluginCall call) {
        String id = call.getString("id", "x");
        Saved sv;
        synchronized (parts) {
            sv = parts.remove(id);
            partInfo.remove(id);
            partSize.remove(id);
        }
        if (sv != null) {
            sv.abort(getContext().getContentResolver());
        }
        call.resolve();
    }

    private static String mimeOf(String ext) {
        switch (ext) {
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "m4a":
                return "audio/mp4";
            case "mp3":
                return "audio/mpeg";
            default:
                return "video/mp4";
        }
    }

    private static class Saved {
        Uri uri;
        File legacy;
        OutputStream out;

        void abort(ContentResolver cr) {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
                // egal
            }
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                    // egal
                }
            }
            if (legacy != null) {
                legacy.delete();
            }
        }
    }

    private Saved newFile(String kind, String file, String mime, String name, String ext) throws Exception {
        Saved s = new Saved();
        boolean img = "image".equals(kind);
        boolean aud = "audio".equals(kind);
        String dirName = img ? Environment.DIRECTORY_PICTURES : aud ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContext().getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dirName + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri col = img ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : aud ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            s.uri = cr.insert(col, v);
            if (s.uri == null) {
                throw new Exception("Galerie nimmt die Datei nicht an");
            }
            s.out = cr.openOutputStream(s.uri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(dirName), FOLDER);
            dir.mkdirs();
            File f = new File(dir, file);
            int k = 1;
            while (f.exists()) {
                f = new File(dir, name + " (" + (k++) + ")." + ext);
            }
            s.legacy = f;
            s.out = new FileOutputStream(f);
        }
        return s;
    }

    private String finish(Saved s, String mime) {
        if (s.uri != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContext().getContentResolver().update(s.uri, v, null, null);
            return s.uri.toString();
        }
        MediaScannerConnection.scanFile(getContext(), new String[]{s.legacy.getAbsolutePath()}, new String[]{mime}, null);
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".foxupdate", s.legacy).toString();
    }

    // Video-Spur aus der einen Datei und Ton-Spur aus der anderen in eine MP4 packen (ohne Neukodieren)
    private static void mux(File video, File audio, File out) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = new MediaExtractor();
        MediaMuxer mm = null;
        try {
            ev.setDataSource(video.getAbsolutePath());
            ea.setDataSource(audio.getAbsolutePath());
            int tv = track(ev, "video/");
            int ta = track(ea, "audio/");
            if (tv < 0) {
                throw new Exception("Keine Bildspur gefunden");
            }
            mm = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            ev.selectTrack(tv);
            MediaFormat fv = ev.getTrackFormat(tv);
            if (fv.containsKey("rotation-degrees")) {
                mm.setOrientationHint(fv.getInteger("rotation-degrees"));
            }
            int ov = mm.addTrack(fv);
            int oa = -1;
            if (ta >= 0) {
                ea.selectTrack(ta);
                oa = mm.addTrack(ea.getTrackFormat(ta));
            }
            mm.start();
            ByteBuffer bb = ByteBuffer.allocate(8 * 1024 * 1024);
            copy(ev, mm, ov, bb);
            if (oa >= 0) {
                copy(ea, mm, oa, bb);
            }
            mm.stop();
        } finally {
            if (mm != null) {
                try {
                    mm.release();
                } catch (Exception ignored) {
                    // egal
                }
            }
            ev.release();
            ea.release();
        }
    }

    private static int track(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressLint("WrongConstant")
    private static void copy(MediaExtractor ex, MediaMuxer mm, int outTrack, ByteBuffer bb) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            bb.clear();
            int n = ex.readSampleData(bb, 0);
            if (n < 0) {
                break;
            }
            info.offset = 0;
            info.size = n;
            info.presentationTimeUs = ex.getSampleTime();
            int f = ex.getSampleFlags();
            info.flags = (f & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mm.writeSampleData(outTrack, bb, info);
            ex.advance();
        }
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]+", " ").trim();
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t.isEmpty() ? "fox" : t;
    }

    // ---------------- Fertige Dateien ----------------

    @PluginMethod
    public void cacheDelete(PluginCall call) {
        String path = call.getString("path", "");
        File f = new File(path == null ? "" : path);
        try {
            if (f.getCanonicalPath().startsWith(getContext().getCacheDir().getCanonicalPath())) {
                f.delete();
            }
        } catch (Exception ignored) {
            // egal
        }
        call.resolve();
    }

    @PluginMethod
    public void exists(PluginCall call) {
        JSObject o = new JSObject();
        boolean ok = false;
        try {
            Uri u = Uri.parse(call.getString("uri", ""));
            Cursor cur = getContext().getContentResolver().query(u, null, null, null, null);
            if (cur != null) {
                ok = cur.getCount() > 0;
                cur.close();
            }
        } catch (Exception e) {
            ok = false;
        }
        o.put("ok", ok);
        call.resolve(o);
    }

    @PluginMethod
    public void remove(PluginCall call) {
        try {
            getContext().getContentResolver().delete(Uri.parse(call.getString("uri", "")), null, null);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void play(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(call.getString("uri", "")), call.getString("mime", "video/mp4"));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Keine App zum Abspielen gefunden");
        }
    }

    @PluginMethod
    public void share(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(call.getString("mime", "video/mp4"));
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(call.getString("uri", "")));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(i, "Teilen");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(ch);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url", "")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Keine App gefunden");
        }
    }
}

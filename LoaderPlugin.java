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
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ---------------- Anmeldung (Instagram, Facebook) ----------------

    private static String siteUrl(String site) {
        return "facebook".equals(site) ? "https://www.facebook.com" : "https://www.instagram.com";
    }

    private static boolean loggedIn(String site) {
        String c = webCookies(siteUrl(site));
        return "facebook".equals(site) ? c.contains("c_user=") : c.contains("sessionid=");
    }

    @PluginMethod
    public void logins(PluginCall call) {
        JSObject o = new JSObject();
        o.put("instagram", loggedIn("instagram"));
        o.put("facebook", loggedIn("facebook"));
        call.resolve(o);
    }

    @PluginMethod
    public void logout(PluginCall call) {
        String site = call.getString("site", "instagram");
        String[] domains = "facebook".equals(site)
                ? new String[]{"https://www.facebook.com", "https://m.facebook.com", "https://facebook.com"}
                : new String[]{"https://www.instagram.com", "https://instagram.com"};
        String dom = "facebook".equals(site) ? ".facebook.com" : ".instagram.com";
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
                    t.setText("facebook".equals(site) ? "Bei Facebook anmelden" : "Bei Instagram anmelden");
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
                    w.loadUrl("facebook".equals(site) ? "https://m.facebook.com/login/" : "https://www.instagram.com/accounts/login/");

                    done.setOnClickListener(v -> d.dismiss());
                    d.setOnDismissListener(di -> {
                        CookieManager.getInstance().flush();
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

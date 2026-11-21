package de.foxloader.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Fox Loader: Links von X/Twitter auslesen, Videos und Bilder in die Galerie laden, teilen, abspielen.
@CapacitorPlugin(name = "Loader")
public class LoaderPlugin extends Plugin {

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
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

    // ---------------- Internet: Seite/JSON abrufen ----------------

    @PluginMethod
    public void get(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Ungueltige Adresse");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = open(url);
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String body = "";
                    if (in != null) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0 && bo.size() < 4000000) {
                            bo.write(buf, 0, n);
                        }
                        in.close();
                        body = bo.toString("UTF-8");
                    }
                    JSObject o = new JSObject();
                    o.put("status", code);
                    o.put("body", body);
                    o.put("url", c.getURL().toString());
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

    private HttpURLConnection open(String url) throws Exception {
        // Weiterleitungen (z. B. t.co-Kurzlinks) selbst verfolgen
        String u = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new Exception("Weiterleitung ohne Ziel");
                }
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("Zu viele Weiterleitungen");
    }

    // ---------------- Herunterladen in die Galerie ----------------

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled.add(call.getString("id", ""));
        call.resolve();
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String id = call.getString("id", "x");
        final String url = call.getString("url", "");
        final String kind = call.getString("kind", "video");
        final String name = clean(call.getString("name", "fox"));
        if (url == null || !url.startsWith("https://")) {
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
                boolean video = !"image".equals(kind);
                String ext = video ? ".mp4" : (url.contains("format=png") || url.endsWith(".png") ? ".png" : ".jpg");
                String mime = video ? "video/mp4" : (ext.equals(".png") ? "image/png" : "image/jpeg");
                String file = name + ext;
                HttpURLConnection c = null;
                Uri dst = null;
                File legacy = null;
                ContentResolver cr = getContext().getContentResolver();
                try {
                    c = open(url);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        throw new Exception("Server antwortet mit " + code);
                    }
                    long total = c.getContentLength();
                    OutputStream out;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER);
                        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        Uri col = video
                                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                        dst = cr.insert(col, v);
                        if (dst == null) {
                            throw new Exception("Galerie nimmt die Datei nicht an");
                        }
                        out = cr.openOutputStream(dst);
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), FOLDER);
                        dir.mkdirs();
                        legacy = new File(dir, file);
                        int k = 1;
                        while (legacy.exists()) {
                            legacy = new File(dir, name + " (" + (k++) + ")" + ext);
                        }
                        out = new FileOutputStream(legacy);
                    }
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[65536];
                    long got = 0;
                    long lastT = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled.contains(id)) {
                            throw new InterruptedException("abgebrochen");
                        }
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastT > 200) {
                            lastT = now;
                            JSObject p = new JSObject();
                            p.put("id", id);
                            p.put("got", got);
                            p.put("total", total);
                            p.put("pct", total > 0 ? (int) (got * 100 / total) : -1);
                            notifyListeners("progress", p);
                        }
                    }
                    in.close();
                    out.close();
                    if (total > 0 && got != total) {
                        throw new Exception("Download unvollstaendig");
                    }
                    String result;
                    if (dst != null) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        cr.update(dst, v, null, null);
                        result = dst.toString();
                    } else {
                        MediaScannerConnection.scanFile(getContext(), new String[]{legacy.getAbsolutePath()}, new String[]{mime}, null);
                        result = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".foxupdate", legacy).toString();
                    }
                    JSObject r = new JSObject();
                    r.put("uri", result);
                    r.put("size", got);
                    r.put("mime", mime);
                    r.put("file", file);
                    call.resolve(r);
                } catch (Exception e) {
                    if (dst != null) {
                        try {
                            cr.delete(dst, null, null);
                        } catch (Exception ignored) {
                            // egal
                        }
                    }
                    if (legacy != null) {
                        legacy.delete();
                    }
                    call.reject(e instanceof InterruptedException ? "abgebrochen" : String.valueOf(e.getMessage()));
                } finally {
                    cancelled.remove(id);
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
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

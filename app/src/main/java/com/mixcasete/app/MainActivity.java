package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    public static WeakReference<MainActivity> self;

    private WebView wv;
    private boolean npInit = false;
    private String pendingExport = null;

    private static final int REQ_OPEN = 777;
    private static final int REQ_WRITE = 42;

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        self = new WeakReference<>(this);

        FrameLayout root = new FrameLayout(this);

        wv = new WebView(this);
        config(wv.getSettings());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                notifyOrientation();
            }
        });
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new Bridge(), "Android");
        root.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        new Thread(() -> cleanupDuplicateBackups()).start();
        requestNotifPermission();
        wv.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        notifyOrientation();
    }

    private void notifyOrientation() {
        if (wv == null) return;
        final boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        runOnUiThread(() -> wv.evaluateJavascript(
                "window.onOrientationChange && window.onOrientationChange(" + landscape + ")", null));
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 501);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void config(WebSettings s) {
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    public void onPlayerEvent(String event) {
        runOnUiThread(() -> wv.evaluateJavascript(
                "window.onNativePlayerEvent && window.onNativePlayerEvent('" + event + "')", null));
    }

    public class Bridge {

        @JavascriptInterface
        public void openBrowser(final String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void openCastSettings() {
            runOnUiThread(() -> {
                Intent intent = null;
                try {
                    intent = new Intent(Settings.ACTION_CAST_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
                try {
                    intent = new Intent("android.settings.CAST_SETTINGS");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
                try {
                    intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
                try {
                    intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(() -> {
                try {
                    if ("landscape".equals(mode))
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    else if ("portrait".equals(mode))
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    else
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void nativePlay(final String url, final String title) {
            if (url == null || url.trim().isEmpty()) {
                Log.e("MixCaseteAudio", "nativePlay: URL vacía");
                return;
            }
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_CMD, "play_url");
            i.putExtra(PlaybackService.EXTRA_URL, url);
            i.putExtra(PlaybackService.EXTRA_TITLE, title != null ? title : "Mix.Casete");
            PlaybackService.start(MainActivity.this, i);
        }

        @JavascriptInterface public void nativePause() {
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_CMD, "pause");
            PlaybackService.start(MainActivity.this, i);
        }

        @JavascriptInterface public void nativeResume() {
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_CMD, "play");
            PlaybackService.start(MainActivity.this, i);
        }

        @JavascriptInterface public void nativeStop() {
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_CMD, "stop");
            PlaybackService.start(MainActivity.this, i);
        }

        @JavascriptInterface public void nativeSeek(int sec) {
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_CMD, "seek");
            i.putExtra(PlaybackService.EXTRA_SEEK, sec);
            PlaybackService.start(MainActivity.this, i);
        }

        @JavascriptInterface
        public void getStream(final String id) {
            new Thread(() -> {
                String json = null;
                try { json = nativePlayer(id); }
                catch (Exception ignored) {}
                final String out = json;
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.__streamCb && window.__streamCb(" + (out != null ? out : "null") + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void downloadYT(final String id, final String title) {
            new Thread(() -> {
                String path = null;
                try {
                    JSONObject j = new JSONObject(nativePlayer(id));
                    String url = j.getString("url");
                    if (url == null || url.trim().isEmpty()) throw new IOException("url vacía");
                    File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    if (dir != null) {
                        File f = new File(dir, id + ".m4a");
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(120000);
                        c.setRequestProperty("User-Agent", UA);
                        c.setRequestProperty("Referer", "https://www.youtube.com/");
                        c.setRequestProperty("Origin", "https://www.youtube.com");
                        InputStream in = c.getInputStream();
                        FileOutputStream out = new FileOutputStream(f);
                        byte[] buf = new byte[16384];
                        int n;
                        long total = 0;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                        out.close(); in.close();
                        if (total > 10000) path = f.getAbsolutePath(); else f.delete();
                    }
                } catch (Exception ignored) {}
                final String p = path;
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.onDownloaded && window.onDownloaded('" + id + "'," +
                                (p != null ? "'" + p + "'" : "null") + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void exportPlaylist(final String json) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingExport = json;
                requestPermissions(new String[]{ android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_WRITE);
                return;
            }
            doExport(json);
        }

        @JavascriptInterface
        public void exportPdf(final String base64Data, final String fileName) {
            new Thread(() -> {
                final boolean ok = writeBytesToDownloads(base64Data, fileName);
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.onPdfExported && window.onPdfExported(" + ok + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void openPlaylistFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
            });
        }

        @JavascriptInterface
        public void loadPlaylistBackup() {
            new Thread(() -> {
                final String s = readPlaylistFromDownloads();
                if (s != null) runOnUiThread(() -> wv.evaluateJavascript(
                        "window.onPlaylistImported && window.onPlaylistImported(" + JSONObject.quote(s) + ")", null));
            }).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] g) {
        super.onRequestPermissionsResult(rc, perms, g);
        if (rc == REQ_WRITE && g.length > 0 && g[0] == 0 && pendingExport != null) {
            doExport(pendingExport);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            new Thread(() -> {
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    is.close();
                    final String s = bo.toString("UTF-8");
                    runOnUiThread(() -> wv.evaluateJavascript(
                            "window.onPlaylistImported && window.onPlaylistImported(" + JSONObject.quote(s) + ")", null));
                } catch (Exception ignored) {}
            }).start();
        }
    }

    private void doExport(final String json) {
        new Thread(() -> {
            final boolean ok = writePlaylistToDownloads(json);
            runOnUiThread(() -> wv.evaluateJavascript(
                    "window.onExported && window.onExported(" + ok + ")", null));
        }).start();
    }

    private List<Uri> findAllPlaylistUris() {
        List<Uri> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out;
        Cursor c = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Downloads._ID },
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                new String[]{ "MixCasete_playlist%" },
                MediaStore.Downloads._ID + " ASC");
        if (c != null) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                out.add(android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id));
            }
            c.close();
        }
        return out;
    }

    private void cleanupDuplicateBackups() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            List<Uri> all = findAllPlaylistUris();
            for (int i = 1; i < all.size(); i++) {
                try { getContentResolver().delete(all.get(i), null, null); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private boolean writePlaylistToDownloads(String json) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentResolver cr = getContentResolver();
                List<Uri> existing = findAllPlaylistUris();
                Uri target = null;
                if (!existing.isEmpty()) {
                    target = existing.get(0);
                    for (int i = 1; i < existing.size(); i++) {
                        try { cr.delete(existing.get(i), null, null); } catch (Exception ignored) {}
                    }
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, "MixCasete_playlist.json");
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                }
                if (target == null) return false;
                OutputStream os = cr.openOutputStream(target, "wt");
                os.write(json.getBytes("UTF-8"));
                os.close();
                return true;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, "MixCasete_playlist.json");
                FileOutputStream os = new FileOutputStream(f);
                os.write(json.getBytes("UTF-8"));
                os.close();
                return true;
            }
        } catch (Exception e) { return false; }
    }

    private boolean writeBytesToDownloads(String base64Data, String fileName) {
        try {
            byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
            String safeName = (fileName == null || fileName.trim().isEmpty())
                    ? "MixCasete_export.pdf" : fileName.replaceAll("[\\/:*?\"<>|]", "_");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (target == null) return false;
                OutputStream os = getContentResolver().openOutputStream(target);
                os.write(data);
                os.close();
                return true;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, safeName);
                FileOutputStream os = new FileOutputStream(f);
                os.write(data);
                os.close();
                return true;
            }
        } catch (Exception e) { return false; }
    }

    private String readPlaylistFromDownloads() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<Uri> all = findAllPlaylistUris();
                if (all.isEmpty()) return null;
                InputStream is = getContentResolver().openInputStream(all.get(0));
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                return bo.toString("UTF-8");
            } else {
                File f = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "MixCasete_playlist.json");
                if (!f.exists()) return null;
                return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            }
        } catch (Exception e) { return null; }
    }

    private synchronized void ensureNewPipe() {
        if (!npInit) {
            try { NewPipe.init(new HttpDownloader()); }
            catch (Throwable t) { Log.e("MixCaseteAudio", "NewPipe.init failed", t); }
            npInit = true;
        }
    }

    private String newpipeExtract(String id) {
        try {
            ensureNewPipe();
            StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube,
                    "https://www.youtube.com/watch?v=" + id);
            List<AudioStream> audios = info.getAudioStreams();
            if (audios == null || audios.isEmpty()) {
                Log.e("MixCaseteAudio", "No audio streams for id=" + id);
                return null;
            }

            AudioStream best = null;
            for (AudioStream a : audios) {
                if (a == null || a.getContent() == null) continue;
                String url = a.getContent() == null ? "" : a.getContent().toLowerCase();
                String fmt = a.getFormat() != null ? a.getFormat().getName().toLowerCase() : "";
                boolean preferred = url.contains("audio/mp4")
                        || url.contains("mime=audio/mp4")
                        || url.contains(".m4a")
                        || fmt.contains("m4a")
                        || fmt.contains("mp4");

                if (preferred) {
                    if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
                }
            }

            if (best == null) {
                for (AudioStream a : audios) {
                    if (a == null || a.getContent() == null) continue;
                    if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
                }
            }

            if (best == null) return null;
            JSONObject out = new JSONObject();
            out.put("url", best.getContent());
            out.put("title", info.getName());
            out.put("author", info.getUploaderName());
            return out.toString();
        } catch (Throwable t) {
            Log.e("MixCaseteAudio", "newpipeExtract failed for id=" + id, t);
            return null;
        }
    }

    public static class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection conn = (HttpURLConnection) new URL(request.url()).openConnection();
            conn.setRequestMethod(request.httpMethod());
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://www.youtube.com/");
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            conn.setRequestProperty("Accept", "*/*");

            Map<String, List<String>> headers = request.headers();
            if (headers != null) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    for (String v : e.getValue()) conn.addRequestProperty(e.getKey(), v);
                }
            }

            byte[] data = request.dataToSend();
            if (data != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(data); os.close();
            }

            int code = conn.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha", request.url());
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (is != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                body = bo.toString("UTF-8");
            }
            Log.d("MixCaseteAudio", "HTTP " + code + " " + request.url());
            return new Response(code, conn.getResponseMessage(),
                    conn.getHeaderFields(), conn.getURL().toString(), body);
        }
    }

    private String nativePlayer(String id) {
        String r = newpipeExtract(id);
        if (r != null) return r;

        String fallback = fromInstances(id);
        if (fallback != null && fallback.contains("\"url\"")) {
            try {
                JSONObject j = new JSONObject(fallback);
                String url = j.optString("url", "");
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return fallback;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String fromInstances(String id) {
        try {
            JSONArray arr = new JSONArray(httpGet("https://api.invidious.io/instances.json?sort_by=health"));
            int tried = 0;
            for (int i = 0; i < arr.length() && tried < 6; i++) {
                JSONArray entry = arr.optJSONArray(i);
                if (entry == null) continue;
                String host = entry.optString(0, "");
                JSONObject meta = entry.optJSONObject(1);
                if (host.isEmpty() || meta == null || !meta.optBoolean("api", false)) continue;
                tried++;
                try {
                    JSONObject v = new JSONObject(httpGet("https://" + host + "/api/v1/videos/" + id));
                    String url = pickInvidious(v, host);
                    if (url != null) return buildOut(url, v.optString("title", ""), v.optString("author", ""));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        try {
            JSONArray arr = new JSONArray(httpGet("https://piped-instances.kavin.rocks/"));
            int tried = 0;
            for (int i = 0; i < arr.length() && tried < 6; i++) {
                JSONObject inst = arr.optJSONObject(i);
                if (inst == null) continue;
                String api = inst.optString("api_url", "");
                if (api.isEmpty()) continue;
                tried++;
                try {
                    JSONObject v = new JSONObject(httpGet(api + "/streams/" + id));
                    String url = pickPiped(v);
                    if (url != null) return buildOut(url, v.optString("title", ""), v.optString("uploader", ""));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String pickInvidious(JSONObject v, String host) {
        String fallback = null;
        JSONArray ad = v.optJSONArray("adaptiveFormats");
        if (ad != null) {
            for (int i = 0; i < ad.length(); i++) {
                JSONObject f = ad.optJSONObject(i);
                if (f == null) continue;
                String t = f.optString("type", "");
                String u = f.optString("url", "");
                if (u.isEmpty()) continue;
                if (u.startsWith("/")) u = "https://" + host + u;
                if (t.startsWith("audio/mp4")) return u;
                if (fallback == null && t.startsWith("audio")) fallback = u;
            }
        }
        if (fallback != null) return fallback;

        JSONArray fs = v.optJSONArray("formatStreams");
        if (fs != null && fs.length() > 0) {
            JSONObject f0 = fs.optJSONObject(0);
            if (f0 != null) {
                String u = f0.optString("url", "");
                if (u.startsWith("/")) u = "https://" + host + u;
                if (!u.isEmpty()) return u;
            }
        }
        return null;
    }

    private String pickPiped(JSONObject v) {
        JSONArray as = v.optJSONArray("audioStreams");
        String best = null;
        int bestBr = -1;
        if (as != null) {
            for (int i = 0; i < as.length(); i++) {
                JSONObject f = as.optJSONObject(i);
                if (f == null) continue;
                String u = f.optString("url", "");
                if (u.isEmpty()) continue;
                String mime = f.optString("mimeType", "").toLowerCase();
                int br = f.optInt("bitrate", 0);
                boolean preferred = mime.contains("audio/mp4") || mime.contains("m4a") || mime.contains("mp4");
                if (preferred && br > bestBr) { bestBr = br; best = u; }
            }
            if (best == null) {
                for (int i = 0; i < as.length(); i++) {
                    JSONObject f = as.optJSONObject(i);
                    if (f == null) continue;
                    String u = f.optString("url", "");
                    if (u.isEmpty()) continue;
                    int br = f.optInt("bitrate", 0);
                    if (br > bestBr) { bestBr = br; best = u; }
                }
            }
        }
        return best;
    }

    private String buildOut(String url, String title, String author) {
        try {
            JSONObject out = new JSONObject();
            out.put("url", url);
            out.put("title", title);
            out.put("author", author);
            return out.toString();
        } catch (Exception e) { return null; }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("http " + code);
        return readAll(c.getInputStream());
    }

    private String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}

package com.jeep.claudecode;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

import androidx.webkit.WebViewAssetLoader;

/**
 * Claude Code front end. The interface is a local web page (assets/web); Claude Code itself runs
 * in Termux behind a small bridge (assets/bridge.py) that this app starts through Termux's
 * RUN_COMMAND service and reaches on 127.0.0.1 with a per-install token.
 */
public final class MainActivity extends Activity {
    private static final String TERMUX = "com.termux";
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String PYTHON = "/data/data/com.termux/files/usr/bin/python3";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final int PORT = 8765;
    /** VERSION in assets/bridge.py; a running bridge of another version is replaced. */
    private static final int BRIDGE_VERSION = 2;
    private static final String BASE = "http://127.0.0.1:" + PORT;
    private static final String PAGE = "https://appassets.androidplatform.net/assets/web/index.html";
    private static final int CHOOSE_IMAGES = 1;
    private static final int ASK_PERMISSION = 2;

    private WebView web;
    private String token;
    private final ExecutorService http = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean eventsRunning;
    /** Last event seen; -1 asks only for events from now on (nothing from before this launch). */
    private volatile long lastSeq = -1;
    private final List<String> pending = new ArrayList<>();
    private boolean flushQueued;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#FAF9F5"));
        window.setNavigationBarColor(Color.parseColor("#FAF9F5"));
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        token = prefs.getString("token", null);
        if (token == null) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            token = hex.toString();
            prefs.edit().putString("token", token).apply();
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FAF9F5"));
        // As in Via: no overscroll stretch for the WebView itself. Android 12+ stretches by drawing
        // the view into an offscreen layer, where the page shows black while text is selected.
        web.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                openExternal(request.getUrl().toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(pick, CHOOSE_IMAGES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Native(), "Native");
        web.loadUrl(PAGE);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.app && app.back()", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.app && app.resumed()", null);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != CHOOSE_IMAGES) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (fileCallback == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == ASK_PERMISSION) js("app.permissionResult(" + hasRunPermission() + ")");
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private boolean hasTermux() {
        try {
            getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasRunPermission() {
        return checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** One HTTP exchange with the bridge; status -1 when it cannot be reached. */
    private Object[] exchange(String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Token", token);
            // A fresh connection each time: a reused one the bridge has closed loses the answer.
            c.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Object[]{status, in == null ? "" : read(in)};
        } catch (IOException e) {
            return new Object[]{-1, ""};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /** Starts the bridge in Termux in the background, replacing one already running. */
    private boolean runBridge() {
        String script;
        try {
            script = read(getAssets().open("bridge.py"));
        } catch (IOException e) {
            return false;
        }
        Intent intent = new Intent()
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .setAction("com.termux.RUN_COMMAND")
                .putExtra("com.termux.RUN_COMMAND_PATH", PYTHON)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                        new String[]{"-c", script, "--token", token, "--port", String.valueOf(PORT)})
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Claude Code bridge");
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reads the bridge's event stream, reconnecting, and hands events to the page in batches. */
    private void readEvents() {
        while (eventsRunning) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + "/events?since=" + lastSeq).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(40000);
                c.setRequestProperty("X-Token", token);
                if (c.getResponseCode() != 200) throw new IOException("status " + c.getResponseCode());
                js("app.link(true)");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                for (String line; eventsRunning && (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    try {
                        lastSeq = new JSONObject(data).optLong("seq", 0);
                    } catch (org.json.JSONException ignored) {
                        continue;
                    }
                    queue(data);
                }
            } catch (IOException e) {
                js("app.link(false)");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private void queue(String event) {
        synchronized (pending) {
            pending.add(event);
            if (flushQueued) return;
            flushQueued = true;
        }
        ui.postDelayed(() -> {
            String batch;
            synchronized (pending) {
                batch = "[" + String.join(",", pending) + "]";
                pending.clear();
                flushQueued = false;
            }
            web.evaluateJavascript("app.events(" + batch + ")", null);
        }, 33);
    }

    /** Methods the page calls. Results come back through app.reply(id, ...). */
    private final class Native {
        @JavascriptInterface public void request(String id, String method, String path, String body) {
            http.execute(() -> {
                Object[] r = exchange(method, path, body);
                js("app.reply(" + JSONObject.quote(id) + "," + r[0] + "," + JSONObject.quote((String) r[1]) + ")");
            });
        }

        /** Makes sure a bridge with this app's token answers; starts one in Termux if needed. */
        @JavascriptInterface public void connect(String id) {
            http.execute(() -> {
                String result = connectBridge();
                if ("ok".equals(result) && !eventsRunning) {
                    eventsRunning = true;
                    http.execute(MainActivity.this::readEvents);
                }
                js("app.reply(" + JSONObject.quote(id) + ",200," + JSONObject.quote(result) + ")");
            });
        }

        @JavascriptInterface public void restartBridge(String id) {
            http.execute(() -> {
                lastSeq = -1;
                String result = hasRunPermission() && runBridge() ? waitForBridge() : "permission";
                js("app.reply(" + JSONObject.quote(id) + ",200," + JSONObject.quote(result) + ")");
            });
        }

        @JavascriptInterface public void askPermission() {
            ui.post(() -> requestPermissions(new String[]{RUN_COMMAND_PERMISSION}, ASK_PERMISSION));
        }

        @JavascriptInterface public void openTermux() {
            ui.post(() -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX);
                if (launch != null) startActivity(launch);
            });
        }

        @JavascriptInterface public void openUrl(String url) {
            ui.post(() -> openExternal(url));
        }

        @JavascriptInterface public void copy(String text) {
            ui.post(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Claude Code", text));
            });
        }

        @JavascriptInterface public String info() {
            try {
                return new JSONObject()
                        .put("termux", hasTermux())
                        .put("permission", hasRunPermission())
                        .put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    private String connectBridge() {
        Object[] r = exchange("GET", "/health", null);
        if ((int) r[0] == 200 && currentBridge((String) r[1])) return "ok";
        if (!hasTermux()) return "termux";
        if (!hasRunPermission()) return "permission";
        if (!runBridge()) return "start";
        return waitForBridge();
    }

    private String waitForBridge() {
        for (int i = 0; i < 40; i++) {
            try { Thread.sleep(250); } catch (InterruptedException e) { return "timeout"; }
            Object[] r = exchange("GET", "/health", null);
            if ((int) r[0] == 200 && currentBridge((String) r[1])) {
                if (!eventsRunning) {
                    eventsRunning = true;
                    http.execute(this::readEvents);
                }
                return "ok";
            }
        }
        return "timeout";
    }

    private static boolean currentBridge(String health) {
        try {
            return new JSONObject(health).optInt("version") == BRIDGE_VERSION;
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    @Override protected void onDestroy() {
        eventsRunning = false;
        http.shutdownNow();
        super.onDestroy();
    }
}

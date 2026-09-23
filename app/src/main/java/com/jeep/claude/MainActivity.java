package com.jeep.claude;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String START_PAGE = "https://claude.ai/";
    private static final int CREAM = Color.rgb(250, 249, 245);
    private static final int PICK_FILE = 60;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout screen;
    private FrameLayout overlay;
    private ProgressBar loading;
    private WebView site;
    private WebView auxiliary;
    private ValueCallback<Uri[]> pickedFiles;
    private boolean inForeground;

    private final Runnable refreshColor = new Runnable() {
        @Override public void run() {
            if (!inForeground || site == null) return;
            String address = site.getUrl();
            if (address != null && allowed(Uri.parse(address))) {
                site.evaluateJavascript("(function(){var t=document.querySelector('meta[name=\"theme-color\"]');"
                        + "if(t&&t.content)return t.content;"
                        + "return getComputedStyle(document.body).backgroundColor})()", json -> {
                    if (site == null || !inForeground) return;
                    try {
                        String css = new JSONArray("[" + json + "]").getString(0);
                        int shade = cssColor(css);
                        if (shade != Color.TRANSPARENT) paintBars(shade);
                    } catch (Exception ignored) { }
                });
            }
            ui.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        paintBars(CREAM);
        screen = new FrameLayout(this);
        screen.setBackgroundColor(CREAM);
        site = new WebView(this);
        configure(site, false);
        screen.addView(site, new FrameLayout.LayoutParams(-1, -1));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams line = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        screen.addView(loading, line);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(CREAM);
        overlay.setVisibility(View.GONE);
        screen.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        Button close = new Button(this);
        close.setText(R.string.close);
        close.setOnClickListener(v -> closeWindow());
        FrameLayout.LayoutParams closePosition = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        overlay.addView(close, closePosition);
        setContentView(screen);

        if (saved == null || site.restoreState(saved) == null) site.loadUrl(START_PAGE);
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view, boolean secondary) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        view.setBackgroundColor(CREAM);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new Guard(secondary));
        view.setWebChromeClient(new BrowserFeatures(secondary));
    }

    private static boolean allowed(Uri uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
                || host.equals("anthropic.com") || host.endsWith(".anthropic.com");
    }

    private static boolean google(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("google.com") || host.endsWith(".google.com")
                || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com");
    }

    private void reject(Uri destination) {
        if (google(destination)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.google_blocked_title)
                    .setMessage(R.string.google_blocked_body)
                    .setPositiveButton(android.R.string.ok, null).show();
        } else {
            new AlertDialog.Builder(this).setMessage(R.string.untrusted_link)
                    .setPositiveButton(R.string.copy, (d, which) -> {
                        ClipboardManager board = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        board.setPrimaryClip(ClipData.newPlainText("URL", destination.toString()));
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    private final class Guard extends WebViewClient {
        private final boolean secondary;
        Guard(boolean secondary) { this.secondary = secondary; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (allowed(uri)) return false;
            if (secondary) closeWindow();
            reject(uri);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (secondary && "about:blank".equals(url)) return;
            if (!allowed(Uri.parse(url))) {
                view.stopLoading();
                return;
            }
            if (!secondary) {
                paintBars(CREAM);
                loading.setVisibility(View.VISIBLE);
            }
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!secondary) {
                loading.setVisibility(View.GONE);
                ui.removeCallbacks(refreshColor);
                ui.post(refreshColor);
            }
            CookieManager.getInstance().flush();
        }
    }

    private final class BrowserFeatures extends WebChromeClient {
        private final boolean secondary;
        BrowserFeatures(boolean secondary) { this.secondary = secondary; }

        @Override public void onProgressChanged(WebView view, int percent) {
            if (secondary) return;
            loading.setProgress(percent);
            loading.setVisibility(percent == 100 ? View.GONE : View.VISIBLE);
        }

        @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }

        @Override public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (pickedFiles != null) pickedFiles.onReceiveValue(null);
            pickedFiles = callback;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            ArrayList<String> mimeTypes = new ArrayList<>();
            for (String accept : params.getAcceptTypes()) {
                if (accept == null) continue;
                for (String value : accept.split(",")) {
                    String type = value.trim().toLowerCase(Locale.ROOT);
                    if (type.startsWith(".")) {
                        type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                    }
                    if (type != null && type.contains("/") && !mimeTypes.contains(type)) {
                        mimeTypes.add(type);
                    }
                }
            }
            picker.setType(mimeTypes.size() == 1 ? mimeTypes.get(0) : "*/*");
            if (mimeTypes.size() > 1) {
                picker.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
            }
            try {
                startActivityForResult(picker, PICK_FILE);
            } catch (Exception e) {
                pickedFiles = null;
                callback.onReceiveValue(null);
            }
            return true;
        }

        @Override public boolean onCreateWindow(WebView view, boolean dialog,
                boolean userGesture, Message result) {
            if (!userGesture || auxiliary != null) return false;
            auxiliary = new WebView(MainActivity.this);
            configure(auxiliary, true);
            overlay.addView(auxiliary, 0, new FrameLayout.LayoutParams(-1, -1));
            overlay.setVisibility(View.VISIBLE);
            ((WebView.WebViewTransport) result.obj).setWebView(auxiliary);
            result.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView view) {
            closeWindow();
            site.reload();
        }
    }

    private void closeWindow() {
        if (auxiliary != null) {
            overlay.removeView(auxiliary);
            auxiliary.destroy();
            auxiliary = null;
        }
        if (overlay != null) overlay.setVisibility(View.GONE);
        CookieManager.getInstance().flush();
    }

    @Override protected void onActivityResult(int request, int outcome, Intent intent) {
        super.onActivityResult(request, outcome, intent);
        if (request != PICK_FILE || pickedFiles == null) return;
        Uri[] files = null;
        if (outcome == RESULT_OK && intent != null) {
            if (intent.getClipData() != null) {
                ClipData clips = intent.getClipData();
                files = new Uri[clips.getItemCount()];
                for (int i = 0; i < files.length; i++) files[i] = clips.getItemAt(i).getUri();
            } else if (intent.getData() != null) {
                files = new Uri[]{intent.getData()};
            }
        }
        pickedFiles.onReceiveValue(files);
        pickedFiles = null;
    }

    private static int cssColor(String input) {
        if (input == null) return Color.TRANSPARENT;
        String text = input.trim();
        if (text.startsWith("#")) return Color.parseColor(text);
        if (text.startsWith("rgb(")) {
            String[] parts = text.substring(4, text.length() - 1).split(",");
            if (parts.length == 3) return Color.rgb(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
        return Color.TRANSPARENT;
    }

    private void paintBars(int value) {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(value);
        window.setNavigationBarColor(value);
        if (android.os.Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        int old = window.getDecorView().getSystemUiVisibility();
        int bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int light = (299 * Color.red(value) + 587 * Color.green(value)
                + 114 * Color.blue(value)) / 1000 > 128 ? bits : 0;
        window.getDecorView().setSystemUiVisibility((old & ~bits) | light);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (site != null) site.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        inForeground = true;
        ui.removeCallbacks(refreshColor);
        ui.post(refreshColor);
    }

    @Override protected void onPause() {
        inForeground = false;
        ui.removeCallbacks(refreshColor);
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }

    @Override protected void onDestroy() {
        ui.removeCallbacks(refreshColor);
        if (pickedFiles != null) pickedFiles.onReceiveValue(null);
        closeWindow();
        if (site != null) {
            screen.removeView(site);
            site.destroy();
            site = null;
        }
        super.onDestroy();
    }
}

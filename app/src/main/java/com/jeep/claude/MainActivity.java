package com.jeep.claude;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
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
import android.util.Log;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String START_PAGE = "https://claude.ai/";
    private static final int CREAM = Color.rgb(250, 249, 245);
    private static final int PICK_FILE = 60;
    private static final int ASK_MIC = 61;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout screen;
    private FrameLayout overlay;
    private ProgressBar loading;
    private WebView site;
    private WebView auxiliary;
    private Button pageActions;
    private ValueCallback<Uri[]> pickedFiles;
    private PermissionRequest pendingMic;
    private boolean inForeground;
    // Exact hosts approved by the user for this Activity; never approve an entire suffix.
    private final Set<String> approvedHosts = new HashSet<>();
    private AlertDialog navigationDialog;

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

        // Available even after an external authorization page has already opened.
        pageActions = new Button(this);
        pageActions.setText("⋮");
        pageActions.setContentDescription(getString(R.string.link_actions));
        pageActions.setMinWidth(0);
        pageActions.setMinimumWidth(0);
        pageActions.setVisibility(View.GONE);
        pageActions.setOnClickListener(v -> showCurrentPageActions());
        FrameLayout.LayoutParams actionPosition = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.START);
        screen.addView(pageActions, actionPosition);
        setContentView(screen);

        if (saved == null || site.restoreState(saved) == null) site.loadUrl(START_PAGE);
        ui.post(this::updatePageActions);
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
        // Google's sign-in widget may create its popup from an asynchronous callback.
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        view.setBackgroundColor(CREAM);
        CookieManager.getInstance().setAcceptCookie(true);
        // OAuth widgets rely on cross-site cookies; they remain inside this app's WebView profile.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new Guard(secondary));
        view.setWebChromeClient(new BrowserFeatures(secondary));
    }

    private static boolean secureWebLink(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getHost() != null;
    }

    private static boolean allowed(Uri uri) {
        if (!secureWebLink(uri)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
                || host.equals("anthropic.com") || host.endsWith(".anthropic.com")
                || google(uri);
    }

    private static boolean claudeOrigin(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase("claude.ai")
                || host.toLowerCase(Locale.ROOT).endsWith(".claude.ai"));
    }

    private static boolean google(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("google.com") || host.endsWith(".google.com")
                || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com");
    }

    private boolean canOpen(Uri uri) {
        return allowed(uri) || (secureWebLink(uri)
                && approvedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)));
    }

    private void copyLink(Uri destination) {
        ClipboardManager board = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        board.setPrimaryClip(ClipData.newPlainText("URL", destination.toString()));
    }

    private void openBrowser(Uri destination) {
        if (!secureWebLink(destination)) return;
        Intent browser = new Intent(Intent.ACTION_VIEW, destination);
        browser.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(browser);
        } catch (Exception e) {
            copyLink(destination);
            new AlertDialog.Builder(this).setMessage(R.string.no_browser)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void confirmNavigation(WebView view, Uri destination) {
        if (navigationDialog != null && navigationDialog.isShowing()) return;
        if (!secureWebLink(destination)) {
            navigationDialog = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.unsupported_link, destination.toString()))
                    .setPositiveButton(R.string.copy, (d, which) -> copyLink(destination))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        } else {
            navigationDialog = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.external_link, destination.toString()))
                    .setItems(new String[]{getString(R.string.continue_in_app),
                            getString(R.string.open_browser), getString(R.string.copy),
                            getString(android.R.string.cancel)}, (d, which) -> {
                        if (which == 0) {
                            approvedHosts.add(destination.getHost().toLowerCase(Locale.ROOT));
                            if (view == site || view == auxiliary) view.loadUrl(destination.toString());
                        } else if (which == 1) openBrowser(destination);
                        else if (which == 2) copyLink(destination);
                    }).create();
        }
        navigationDialog.show();
    }

    private void updatePageActions() {
        if (pageActions == null) return;
        WebView current = auxiliary != null && overlay.getVisibility() == View.VISIBLE
                ? auxiliary : site;
        String url = current == null ? null : current.getUrl();
        Uri uri = url == null ? null : Uri.parse(url);
        pageActions.setVisibility(secureWebLink(uri) && !allowed(uri) ? View.VISIBLE : View.GONE);
    }

    private void showCurrentPageActions() {
        WebView current = auxiliary != null && overlay.getVisibility() == View.VISIBLE
                ? auxiliary : site;
        String url = current == null ? null : current.getUrl();
        if (url == null) return;
        Uri destination = Uri.parse(url);
        if (!secureWebLink(destination)) return;
        new AlertDialog.Builder(this)
                .setMessage(destination.toString())
                .setItems(new String[]{getString(R.string.open_browser), getString(R.string.copy),
                        getString(android.R.string.cancel)}, (d, which) -> {
                    if (which == 0) openBrowser(destination);
                    else if (which == 1) copyLink(destination);
                }).show();
    }

    private final class Guard extends WebViewClient {
        private final boolean secondary;
        Guard(boolean secondary) { this.secondary = secondary; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (canOpen(uri)) return false;
            confirmNavigation(view, uri);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (secondary && "about:blank".equals(url)) return;
            Uri destination = Uri.parse(url);
            if (!canOpen(destination)) {
                view.stopLoading();
                confirmNavigation(view, destination);
                return;
            }
            if (!secondary) {
                paintBars(CREAM);
                loading.setVisibility(View.VISIBLE);
            }
        }

        @Override public void onPageFinished(WebView view, String url) {
            updatePageActions();
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

        @Override public void onPermissionRequest(PermissionRequest request) {
            // Only the Claude page may request audio. Never grant camera, video,
            // MIDI or future WebView permissions implicitly.
            Uri origin = request.getOrigin();
            String current = site == null ? null : site.getUrl();
            String[] resources = request.getResources();
            boolean audioOnly = resources.length == 1
                    && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0]);
            if (!inForeground || current == null || !claudeOrigin(Uri.parse(current))
                    || !claudeOrigin(origin) || !audioOnly) {
                Log.i("ClaudeMic", "WebView media permission rejected by origin/state/resource policy");
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.i("ClaudeMic", "WebView audio capture granted");
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                Log.i("ClaudeMic", "Requesting Android microphone permission");
                if (pendingMic != null) pendingMic.deny();
                pendingMic = request;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            }
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingMic == request) pendingMic = null;
        }

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
            if (auxiliary != null) return false;
            auxiliary = new WebView(MainActivity.this);
            configure(auxiliary, true);
            overlay.addView(auxiliary, 0, new FrameLayout.LayoutParams(-1, -1));
            overlay.setVisibility(View.VISIBLE);
            updatePageActions();
            ((WebView.WebViewTransport) result.obj).setWebView(auxiliary);
            result.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView view) {
            // The opener receives the OAuth result from the page; reloading here
            // would unnecessarily destroy the current conversation.
            closeWindow();
        }
    }

    private void closeWindow() {
        if (auxiliary != null) {
            overlay.removeView(auxiliary);
            auxiliary.destroy();
            auxiliary = null;
        }
        if (overlay != null) overlay.setVisibility(View.GONE);
        updatePageActions();
        CookieManager.getInstance().flush();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != ASK_MIC || pendingMic == null) return;
        PermissionRequest request = pendingMic;
        pendingMic = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            Log.i("ClaudeMic", "Android microphone permission granted");
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            Log.i("ClaudeMic", "Android microphone permission denied");
            request.deny();
        }
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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask returns to this live WebView; do not call loadUrl here.
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
        if (navigationDialog != null) navigationDialog.dismiss();
        if (pickedFiles != null) pickedFiles.onReceiveValue(null);
        if (pendingMic != null) {
            pendingMic.deny();
            pendingMic = null;
        }
        closeWindow();
        if (site != null) {
            screen.removeView(site);
            site.destroy();
            site = null;
        }
        super.onDestroy();
    }
}

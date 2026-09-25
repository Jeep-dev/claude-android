package com.jeep.claude;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ActionMode;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String START_PAGE = "https://claude.ai/";
    private static final int PICK_FILE = 60;
    private static final int ASK_MIC = 61;
    private static final int SAVE_FILE = 62;
    private static final int BLOB_CHUNK = 512 * 1024; // base64 chars, multiple of 4

    /** MAIN shows only Claude; POPUP keeps window.opener; EXTERNAL is a user-approved page. */
    private enum Role { MAIN, POPUP, EXTERNAL }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout screen;
    private LinearLayout overlay;
    private FrameLayout overlayContent;
    private TextView overlayHost;
    private ProgressBar loading;
    private WebView site;
    private WebView auxiliary;
    private Guard auxiliaryGuard;
    private ValueCallback<Uri[]> pickedFiles;
    private PermissionRequest pendingMic;
    private PendingDownload pendingDownload;
    private boolean inForeground;
    private boolean documentStartScript;
    private AlertDialog navigationDialog;
    private String pageScript;
    private int lastBarColor = Color.TRANSPARENT;
    // Follows the system light/dark theme (res/values-night).
    private int paper;

    private final Runnable refreshColor = new Runnable() {
        @Override public void run() {
            if (!inForeground || site == null) return;
            String address = site.getUrl();
            if (address != null && claudeOrigin(Uri.parse(address))) {
                site.evaluateJavascript("(function(){var m=[].slice.call(document.querySelectorAll("
                        + "'meta[name=\"theme-color\"]')).filter(function(t){return t.content&&"
                        + "(!t.media||matchMedia(t.media).matches)})[0];"
                        + "if(m)return m.content;"
                        + "return document.body?getComputedStyle(document.body).backgroundColor:''})()", json -> {
                    if (site == null || !inForeground) return;
                    try {
                        int shade = cssColor(new JSONArray("[" + json + "]").getString(0));
                        if (shade != Color.TRANSPARENT && shade != lastBarColor) paintBars(shade);
                    } catch (Exception ignored) { }
                });
            }
            ui.postDelayed(this, 10000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        paper = getColor(R.color.paper);
        if (Build.VERSION.SDK_INT >= 29) {
            // Never let the system (or an OEM "dark mode for all apps") recolor the app;
            // Claude supplies its own dark theme.
            getWindow().getDecorView().setForceDarkAllowed(false);
        }
        paintBars(paper);
        screen = new FrameLayout(this);
        screen.setBackgroundColor(paper);
        site = new ClaudeWebView(this);
        configure(site, new Guard(Role.MAIN));
        installDocumentStartScript(site);
        screen.addView(site, new FrameLayout.LayoutParams(-1, -1));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setVisibility(View.GONE);
        screen.addView(loading, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(paper);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button actions = new Button(this);
        actions.setText("⋮");
        actions.setContentDescription(getString(R.string.link_actions));
        actions.setMinWidth(0);
        actions.setMinimumWidth(0);
        actions.setOnClickListener(v -> showCurrentPageActions());
        bar.addView(actions, new LinearLayout.LayoutParams(dp(48), dp(48)));
        // Always show where the overlay is, since the app has no address bar.
        overlayHost = new TextView(this);
        overlayHost.setSingleLine(true);
        overlayHost.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        overlayHost.setTextColor(getColor(R.color.ink));
        bar.addView(overlayHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setText(R.string.close);
        close.setOnClickListener(v -> closeWindow());
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlayContent = new FrameLayout(this);
        overlay.addView(overlayContent, new LinearLayout.LayoutParams(-1, 0, 1f));
        screen.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);

        if (saved == null || site.restoreState(saved) == null) site.loadUrl(START_PAGE);
    }

    /**
     * The main WebView ignores "window hidden" when the app goes to the background. Otherwise
     * Chromium marks the page hidden: its compositor evicts every rendered tile and Claude gets
     * visibilitychange, so returning needs a full re-raster plus Claude's own refresh before
     * anything shows. Staying "visible" keeps the page ready to draw immediately. Android still
     * does not draw an invisible window, so no frames are produced while in the background.
     */
    private static final class ClaudeWebView extends WebView {
        ClaudeWebView(Context context) { super(context); }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view, Guard guard) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Only Claude itself opens popups (e.g. Google sign-in, which may open from an async
        // callback). In an external/popup layer, target=_blank links and window.open load in
        // that same layer and still pass through its Guard, instead of silently doing nothing.
        settings.setSupportMultipleWindows(guard.role == Role.MAIN);
        settings.setJavaScriptCanOpenWindowsAutomatically(guard.role == Role.MAIN);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Pages pick light/dark from prefers-color-scheme; never invert them algorithmically.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        }
        if (Build.VERSION.SDK_INT >= 29) view.setForceDarkAllowed(false);
        if (guard.role == Role.MAIN) {
            // Also keep rastered tiles on background memory trims (see ClaudeWebView).
            // Costs some memory for this one WebView.
            settings.setOffscreenPreRaster(true);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Do not tell websites this app's package name via X-Requested-With.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
        view.setBackgroundColor(paper);
        CookieManager.getInstance().setAcceptCookie(true);
        // Sign-in popups are top-level (first-party); cross-site tracking cookies are not needed.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(guard);
        view.setWebChromeClient(new BrowserFeatures(guard));
        view.setDownloadListener((url, userAgent, disposition, mime, length) ->
                startDownload(view, url, userAgent, disposition, mime));
    }

    private void installDocumentStartScript(WebView view) {
        // Registering before Claude's own scripts lets Enter be handled before the editor sees it.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        String script = pageScript();
        if (script == null) return;
        try {
            WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("https://claude.ai"));
            documentStartScript = true;
        } catch (Exception e) {
            Log.w("ClaudePageScript", "Falling back to page-finished injection", e);
        }
    }

    private static boolean secureWebLink(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getHost() != null;
    }

    private static String host(Uri uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean claudeOrigin(Uri uri) {
        if (!secureWebLink(uri)) return false;
        String host = host(uri);
        return host.equals("claude.ai") || host.endsWith(".claude.ai");
    }

    /** Only the Google sign-in popup opened by Claude itself skips the confirmation. */
    private static boolean googleSignIn(Uri uri) {
        return secureWebLink(uri) && host(uri).equals("accounts.google.com");
    }

    private void copyLink(Uri destination) {
        ClipboardManager board = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("URL", destination.toString());
        if (Build.VERSION.SDK_INT >= 33) {
            // Links may carry sign-in tokens; keep them out of the clipboard preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        board.setPrimaryClip(clip);
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
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

    private static String shortened(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** Every non-Claude navigation stops here first, on the page the user is looking at. */
    private void confirmNavigation(WebView view, Guard guard, Uri destination) {
        if (isFinishing() || isDestroyed()) return;
        if (navigationDialog != null && navigationDialog.isShowing()) navigationDialog.dismiss();
        boolean[] continued = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (!secureWebLink(destination)) {
            builder.setMessage(getString(R.string.unsupported_link, shortened(destination.toString())))
                    .setPositiveButton(R.string.copy, (d, which) -> copyLink(destination))
                    .setNegativeButton(android.R.string.cancel, null);
        } else {
            builder.setTitle(R.string.external_title)
                    .setMessage(getString(R.string.external_link, host(destination),
                            shortened(destination.toString())))
                    .setPositiveButton(R.string.open_browser, (d, which) -> openBrowser(destination))
                    .setNeutralButton(R.string.copy, (d, which) -> copyLink(destination))
                    .setNegativeButton(R.string.continue_in_app, (d, which) -> {
                        continued[0] = true;
                        continueInApp(view, guard, destination);
                    });
        }
        navigationDialog = builder.create();
        // A popup that was never shown is discarded unless the user chose to continue.
        navigationDialog.setOnDismissListener(d -> {
            if (!continued[0] && guard.role == Role.POPUP && !guard.revealed
                    && view == auxiliary) {
                closeWindow();
            }
        });
        navigationDialog.show();
    }

    private void continueInApp(WebView view, Guard guard, Uri destination) {
        if (guard.role == Role.MAIN) {
            // Never navigate the Claude page itself away; open a separate, closable layer.
            openExternal(destination);
            return;
        }
        if (view != auxiliary) return;
        guard.approved.add(host(destination));
        reveal(guard);
        view.loadUrl(destination.toString());
    }

    private void openExternal(Uri destination) {
        discardWindow();
        Guard guard = new Guard(Role.EXTERNAL);
        guard.approved.add(host(destination));
        attachAuxiliary(guard);
        reveal(guard);
        auxiliary.loadUrl(destination.toString());
    }

    private void attachAuxiliary(Guard guard) {
        auxiliary = new WebView(this);
        auxiliaryGuard = guard;
        configure(auxiliary, guard);
        overlayContent.addView(auxiliary, new FrameLayout.LayoutParams(-1, -1));
    }

    private void reveal(Guard guard) {
        if (guard != auxiliaryGuard) return;
        guard.revealed = true;
        overlay.setVisibility(View.VISIBLE);
        updateOverlayHost();
    }

    /** An approved external page that leads back to Claude hands the link to the main page. */
    private void returnToClaude(Uri destination) {
        discardWindow();
        if (site != null) site.loadUrl(destination.toString());
    }

    private void updateOverlayHost() {
        if (overlayHost == null) return;
        String url = auxiliary == null ? null : auxiliary.getUrl();
        Uri uri = url == null ? null : Uri.parse(url);
        overlayHost.setText(secureWebLink(uri) ? host(uri) : "");
    }

    /** Claude-only page behavior: Enter-to-send, composer focus, long-press drawer vs. selection. */
    private String pageScript() {
        if (pageScript == null) {
            StringBuilder script = new StringBuilder();
            for (String asset : new String[]{"claude-send-enter.js", "claude-selection-guard.js"}) {
                try (InputStream input = getAssets().open(asset)) {
                    script.append(new String(readAll(input), StandardCharsets.UTF_8)).append(";\n");
                } catch (Exception e) {
                    Log.w("ClaudePageScript", "Could not load " + asset, e);
                }
            }
            if (script.length() > 0) pageScript = script.toString();
        }
        return pageScript;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        return output.toByteArray();
    }

    private void showCurrentPageActions() {
        String url = auxiliary == null ? null : auxiliary.getUrl();
        Uri destination = url == null ? null : Uri.parse(url);
        if (!secureWebLink(destination)) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.current_page, host(destination)))
                .setPositiveButton(R.string.open_browser, (d, which) -> openBrowser(destination))
                .setNeutralButton(R.string.copy, (d, which) -> copyLink(destination))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Guard extends WebViewClient {
        final Role role;
        // Exact hosts approved for this layer only; cleared when the layer closes.
        final Set<String> approved = ConcurrentHashMap.newKeySet();
        volatile boolean revealed;

        Guard(Role role) {
            this.role = role;
            this.revealed = role == Role.MAIN;
        }

        /** Thread-safe: also used from shouldInterceptRequest. */
        boolean permits(Uri uri) {
            if (!secureWebLink(uri)) return false;
            if (claudeOrigin(uri)) return true;
            if (role == Role.MAIN) return false;
            if (role == Role.POPUP && googleSignIn(uri)) return true;
            return approved.contains(host(uri));
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (role == Role.EXTERNAL && claudeOrigin(uri)) {
                returnToClaude(uri);
                return true;
            }
            if (permits(uri)) {
                if (!revealed) reveal(this);
                return false;
            }
            confirmNavigation(view, this, uri);
            return true;
        }

        // Backstop for navigations that skip shouldOverrideUrlLoading (e.g. form POSTs):
        // the request never reaches an unapproved site.
        @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                WebResourceRequest request) {
            if (!request.isForMainFrame()) return null;
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            if (permits(uri)) return null;
            return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (role == Role.POPUP && "about:blank".equals(url)) return;
            Uri destination = Uri.parse(url);
            if (!permits(destination)) {
                view.stopLoading();
                if (role == Role.MAIN) {
                    // The request was blocked (blank response); never leave Claude's page on it.
                    ui.post(() -> {
                        if (view != site) return;
                        if (view.canGoBack()) view.goBack();
                        else view.loadUrl(START_PAGE);
                    });
                }
                confirmNavigation(view, this, destination);
                return;
            }
            if (!revealed) reveal(this);
            if (role == Role.MAIN) {
                paintBars(paper);
                loading.setVisibility(View.VISIBLE);
            } else {
                updateOverlayHost();
            }
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            if (role != Role.MAIN) updateOverlayHost();
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (role == Role.MAIN) {
                if (!documentStartScript && claudeOrigin(Uri.parse(url))) {
                    String script = pageScript();
                    if (script != null) view.evaluateJavascript(script, null);
                }
                loading.setVisibility(View.GONE);
                ui.removeCallbacks(refreshColor);
                ui.post(refreshColor);
            } else {
                updateOverlayHost();
            }
        }

        // Without this, a crashed or system-killed renderer kills the whole app.
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.w("ClaudeRenderer", "Renderer gone (crashed=" + detail.didCrash() + ")");
            if (isFinishing() || isDestroyed()) return true;
            if (view == site) {
                recreateSite();
            } else if (view == auxiliary) {
                discardWindow();
            }
            return true;
        }
    }

    /** Replaces a main WebView whose renderer is gone and reopens the Claude page it showed. */
    private void recreateSite() {
        String last = site.getUrl();
        // Requests tied to the dead WebView can no longer be answered.
        pendingMic = null;
        if (pickedFiles != null) {
            try {
                pickedFiles.onReceiveValue(null);
            } catch (Exception ignored) { }
            pickedFiles = null;
        }
        if (navigationDialog != null) navigationDialog.dismiss();
        discardWindow();
        screen.removeView(site);
        site.destroy();
        site = new ClaudeWebView(this);
        configure(site, new Guard(Role.MAIN));
        documentStartScript = false;
        installDocumentStartScript(site);
        screen.addView(site, 0, new FrameLayout.LayoutParams(-1, -1));
        Uri lastUri = last == null ? null : Uri.parse(last);
        site.loadUrl(claudeOrigin(lastUri) ? last : START_PAGE);
    }

    private final class BrowserFeatures extends WebChromeClient {
        private final Guard guard;
        BrowserFeatures(Guard guard) { this.guard = guard; }

        @Override public void onProgressChanged(WebView view, int percent) {
            if (guard.role != Role.MAIN) return;
            loading.setProgress(percent);
            loading.setVisibility(percent == 100 ? View.GONE : View.VISIBLE);
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            // Only the main Claude page may request audio. Never grant camera, video,
            // MIDI or future WebView permissions implicitly.
            Uri origin = request.getOrigin();
            String current = site == null ? null : site.getUrl();
            String[] resources = request.getResources();
            boolean audioOnly = resources.length == 1
                    && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0]);
            if (guard.role != Role.MAIN || !inForeground || current == null
                    || !claudeOrigin(Uri.parse(current)) || !claudeOrigin(origin) || !audioOnly) {
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
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            ArrayList<String> mimeTypes = new ArrayList<>();
            boolean anyFile = false;
            for (String accept : params.getAcceptTypes()) {
                if (accept == null) continue;
                for (String value : accept.split(",")) {
                    String type = value.trim().toLowerCase(Locale.ROOT);
                    if (type.isEmpty()) continue;
                    // Extensions map unreliably to provider MIME types (.ts, .py, .md ...);
                    // filtering on them would grey out files the site accepts.
                    if (type.startsWith(".") || !type.contains("/")) {
                        anyFile = true;
                    } else if (!mimeTypes.contains(type)) {
                        mimeTypes.add(type);
                    }
                }
            }
            if (anyFile || mimeTypes.isEmpty()) {
                picker.setType("*/*");
            } else {
                picker.setType(mimeTypes.size() == 1 ? mimeTypes.get(0) : "*/*");
                if (mimeTypes.size() > 1) {
                    picker.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
                }
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
            if (guard.role != Role.MAIN) return false;
            // The popup stays hidden until its first navigation is approved, so the
            // confirmation appears over the page the user clicked on.
            discardWindow();
            attachAuxiliary(new Guard(Role.POPUP));
            ((WebView.WebViewTransport) result.obj).setWebView(auxiliary);
            result.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView view) {
            // The opener receives the OAuth result from the page; reloading here
            // would unnecessarily destroy the current conversation.
            if (view == auxiliary) closeWindow();
        }
    }

    private void closeWindow() {
        // A sign-in flow that finished inside the external layer: let Claude pick it up.
        if (discardWindow() && site != null) site.reload();
    }

    /** Removes the overlay layer; returns whether an external layer ended on a Claude page. */
    private boolean discardWindow() {
        boolean endedOnClaude = false;
        if (auxiliary != null) {
            String url = auxiliary.getUrl();
            endedOnClaude = auxiliaryGuard != null && auxiliaryGuard.role == Role.EXTERNAL
                    && url != null && claudeOrigin(Uri.parse(url));
            overlayContent.removeView(auxiliary);
            auxiliary.destroy();
            auxiliary = null;
            auxiliaryGuard = null;
        }
        if (overlay != null) overlay.setVisibility(View.GONE);
        CookieManager.getInstance().flush();
        return endedOnClaude;
    }

    // --- Downloads: saved only where the user picks, via the system document UI. ---

    private static final class PendingDownload {
        final WebView view;
        final Uri source;
        final String userAgent;
        final String token = "d" + System.nanoTime();

        PendingDownload(WebView view, Uri source, String userAgent) {
            this.view = view;
            this.source = source;
            this.userAgent = userAgent;
        }

        boolean blob() { return "blob".equalsIgnoreCase(source.getScheme()); }
        boolean data() { return "data".equalsIgnoreCase(source.getScheme()); }
    }

    private void startDownload(WebView view, String url, String userAgent, String disposition,
            String mime) {
        Uri source = Uri.parse(url);
        String scheme = source.getScheme() == null ? "" : source.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("blob") && !scheme.equals("data") && !secureWebLink(source)) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingDownload != null) {
            // The previous "save as" screen has not returned yet; do not mix the two up.
            Toast.makeText(this, R.string.download_busy, Toast.LENGTH_LONG).show();
            return;
        }
        String type = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (scheme.equals("data")) type = dataMime(url);
        if (!type.contains("/")) type = "application/octet-stream";
        String name = URLUtil.guessFileName(scheme.equals("https") ? url : "", disposition, type);
        PendingDownload download = new PendingDownload(view, source, userAgent);
        pendingDownload = download;
        if (download.blob()) {
            // Read the blob right away; pages often revoke blob URLs shortly after clicking.
            view.evaluateJavascript("(function(u,k){var s=window.__claudeDownloads||"
                    + "(window.__claudeDownloads={});s[k]={state:'loading'};"
                    + "fetch(u).then(function(r){return r.blob()}).then(function(b){"
                    + "return new Promise(function(ok,no){var f=new FileReader();"
                    + "f.onload=function(){ok(f.result)};f.onerror=no;f.readAsDataURL(b)})})"
                    + ".then(function(d){s[k]={state:'ready',data:d.slice(d.indexOf(',')+1)}})"
                    + ".catch(function(){s[k]={state:'error'}})})("
                    + JSONObject.quote(url) + "," + JSONObject.quote(download.token) + ")", null);
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(type);
        save.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(save, SAVE_FILE);
        } catch (Exception e) {
            cancelDownload();
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String dataMime(String url) {
        int comma = url.indexOf(',');
        String header = comma < 0 ? "" : url.substring(5, comma);
        String type = header.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? "text/plain" : type;
    }

    private void cancelDownload() {
        PendingDownload download = pendingDownload;
        pendingDownload = null;
        if (download != null && download.blob()) forgetBlob(download);
    }

    private void forgetBlob(PendingDownload download) {
        if (download.view != site && download.view != auxiliary) return;
        download.view.evaluateJavascript("(function(k){if(window.__claudeDownloads)"
                + "delete window.__claudeDownloads[k]})(" + JSONObject.quote(download.token) + ")", null);
    }

    private void runIo(Runnable task) {
        if (!io.isShutdown()) io.execute(task);
    }

    private void saveDownload(PendingDownload download, Uri target) {
        if (download.blob()) {
            BlobSink sink = new BlobSink(target);
            runIo(() -> {
                try {
                    sink.out = getContentResolver().openOutputStream(target, "w");
                    if (sink.out == null) sink.failed = true;
                } catch (Exception e) {
                    sink.failed = true;
                }
            });
            saveBlob(download, sink, 0, 0);
            return;
        }
        runIo(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("No output stream");
                if (download.data()) {
                    out.write(decodeDataUrl(download.source.toString()));
                } else {
                    copyHttps(download, out);
                }
                ok = true;
            } catch (Exception e) {
                Log.w("ClaudeDownload", "Download failed", e);
            }
            finishDownload(target, ok);
        });
    }

    private static byte[] decodeDataUrl(String url) {
        int comma = url.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("Malformed data URL");
        String header = url.substring(0, comma).toLowerCase(Locale.ROOT);
        String payload = url.substring(comma + 1);
        return header.endsWith(";base64")
                ? Base64.decode(Uri.decode(payload), Base64.DEFAULT)
                : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static void copyHttps(PendingDownload download, OutputStream out) throws Exception {
        URL url = new URL(download.source.toString());
        // Redirects are followed here, not by HttpURLConnection: it would resend the Cookie
        // header set for the first host (e.g. claude.ai's session) to the redirect target.
        for (int hop = 0; hop <= 5; hop++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("Not https: " + url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                if (download.userAgent != null) connection.setRequestProperty("User-Agent", download.userAgent);
                // Only the cookies the WebView would send to this exact URL; no Referer.
                String cookies = CookieManager.getInstance().getCookie(url.toString());
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400 && status != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    url = new URL(url, location);
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int size;
                    while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** Output for one blob download; only touched on the single IO thread. */
    private static final class BlobSink {
        final Uri target;
        OutputStream out;
        volatile boolean failed;
        BlobSink(Uri target) { this.target = target; }
    }

    /** Pulls the blob's base64 text from the page in chunks; no JavaScript bridge is exposed. */
    private void saveBlob(PendingDownload download, BlobSink sink, int attempts, int offset) {
        if (sink.failed || (download.view != site && download.view != auxiliary)) {
            endBlob(download, sink, false);
            return;
        }
        String key = JSONObject.quote(download.token);
        download.view.evaluateJavascript("(function(k,o,n){var d=(window.__claudeDownloads||{})[k];"
                + "if(!d)return 'missing';if(d.state!=='ready')return d.state;"
                + "return o>=d.data.length?'done':'chunk:'+d.data.substr(o,n)})("
                + key + "," + offset + "," + BLOB_CHUNK + ")", json -> {
            String reply;
            try {
                reply = new JSONArray("[" + json + "]").getString(0);
            } catch (Exception e) {
                reply = "error";
            }
            if (reply.equals("loading") && attempts < 300) {
                ui.postDelayed(() -> saveBlob(download, sink, attempts + 1, offset), 200);
            } else if (reply.startsWith("chunk:")) {
                byte[] bytes;
                try {
                    bytes = Base64.decode(reply.substring(6), Base64.DEFAULT);
                } catch (Exception e) {
                    endBlob(download, sink, false);
                    return;
                }
                runIo(() -> {
                    try {
                        if (!sink.failed) sink.out.write(bytes);
                    } catch (Exception e) {
                        Log.w("ClaudeDownload", "Could not write blob", e);
                        sink.failed = true;
                    }
                });
                saveBlob(download, sink, 0, offset + BLOB_CHUNK);
            } else {
                endBlob(download, sink, reply.equals("done"));
            }
        });
    }

    private void endBlob(PendingDownload download, BlobSink sink, boolean ok) {
        forgetBlob(download);
        runIo(() -> {
            boolean saved = ok && !sink.failed;
            try {
                if (sink.out != null) sink.out.close();
            } catch (Exception e) {
                saved = false;
            }
            finishDownload(sink.target, saved);
        });
    }

    private void finishDownload(Uri target, boolean ok) {
        if (!ok) {
            try {
                DocumentsContract.deleteDocument(getContentResolver(), target);
            } catch (Exception ignored) { }
        }
        ui.post(() -> {
            if (!isDestroyed()) {
                Toast.makeText(this, ok ? R.string.download_done : R.string.download_failed,
                        Toast.LENGTH_LONG).show();
            }
        });
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
            if (results.length > 0
                    && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // "Don't ask again": Android will no longer show a prompt; explain how to enable it.
                new AlertDialog.Builder(this)
                        .setMessage(R.string.mic_blocked)
                        .setPositiveButton(R.string.open_settings, (d, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", getPackageName(), null)));
                            } catch (Exception ignored) { }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        }
    }

    @Override protected void onActivityResult(int request, int outcome, Intent intent) {
        super.onActivityResult(request, outcome, intent);
        if (request == SAVE_FILE) {
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (download == null) return;
            if (outcome == RESULT_OK && intent != null && intent.getData() != null) {
                saveDownload(download, intent.getData());
            } else if (download.blob()) {
                forgetBlob(download);
            }
            return;
        }
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

    static int cssColor(String input) {
        if (input == null) return Color.TRANSPARENT;
        String text = input.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 3 || hex.length() == 4) {
                    StringBuilder longer = new StringBuilder();
                    for (char c : hex.toCharArray()) longer.append(c).append(c);
                    hex = longer.toString();
                }
                if (hex.length() != 6 && hex.length() != 8) return Color.TRANSPARENT;
                // CSS is #RRGGBBAA; Android's parseColor would read #AARRGGBB.
                if (hex.length() == 8 && Integer.parseInt(hex.substring(6), 16) == 0) {
                    return Color.TRANSPARENT;
                }
                return Color.rgb(Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16),
                        Integer.parseInt(hex.substring(4, 6), 16));
            }
            int open = text.indexOf('(');
            if ((text.startsWith("rgb(") || text.startsWith("rgba(")) && text.endsWith(")")) {
                String[] parts = text.substring(open + 1, text.length() - 1)
                        .replace("/", " ").replace(",", " ").trim().split("\\s+");
                if (parts.length < 3) return Color.TRANSPARENT;
                if (parts.length > 3) {
                    String alpha = parts[3];
                    float a = alpha.endsWith("%")
                            ? Float.parseFloat(alpha.substring(0, alpha.length() - 1)) / 100f
                            : Float.parseFloat(alpha);
                    if (a <= 0f) return Color.TRANSPARENT;
                }
                return Color.rgb(channel(parts[0]), channel(parts[1]), channel(parts[2]));
            }
        } catch (NumberFormatException ignored) { }
        return Color.TRANSPARENT;
    }

    private static int channel(String value) {
        float number = value.endsWith("%")
                ? Float.parseFloat(value.substring(0, value.length() - 1)) * 2.55f
                : Float.parseFloat(value);
        return Math.max(0, Math.min(255, Math.round(number)));
    }

    private void paintBars(int value) {
        lastBarColor = value;
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(value);
        window.setNavigationBarColor(value);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
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

    // While text is selected (the selection toolbar is an ActionMode), dragging a handle shows
    // the system magnifier. On some devices the screen is then composited wrongly: the page's
    // large solid backgrounds come out black although the DOM colours are unchanged (confirmed
    // on-device). Rendering the WebViews into their own hardware layer during selection keeps
    // their output a single texture for the system to composite. Normal rendering resumes
    // when the selection ends, so scrolling is unaffected.
    private int selectionModes;

    @Override public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        if (selectionModes++ == 0) setSelectionLayers(View.LAYER_TYPE_HARDWARE);
    }

    @Override public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
        if (selectionModes > 0 && --selectionModes == 0) setSelectionLayers(View.LAYER_TYPE_NONE);
    }

    private void setSelectionLayers(int type) {
        for (WebView view : new WebView[]{site, auxiliary}) {
            if (view != null && view.getLayerType() != type) view.setLayerType(type, null);
        }
    }

    @Override public void onBackPressed() {
        // Back leaves an external/popup layer first; on Claude itself it minimizes the app.
        if (auxiliary != null && overlay.getVisibility() == View.VISIBLE) {
            if (auxiliary.canGoBack()) auxiliary.goBack();
            else closeWindow();
            return;
        }
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (navigationDialog != null) navigationDialog.dismiss();
        if (pickedFiles != null) pickedFiles.onReceiveValue(null);
        if (pendingMic != null) {
            pendingMic.deny();
            pendingMic = null;
        }
        pendingDownload = null;
        discardWindow();
        if (site != null) {
            screen.removeView(site);
            site.destroy();
            site = null;
        }
        io.shutdown();
        super.onDestroy();
    }
}

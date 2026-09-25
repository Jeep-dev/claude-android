package com.jeep.claude;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Claude in a plain WebView. Other websites open in the system browser. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://claude.ai/";
    private static final int CHOOSE_FILE = 1;
    private static final int ASK_MICROPHONE = 2;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMicrophone;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new KeepVisibleWebView(this);
        // The keyboard only opens for the focused view. The container holds focus until the
        // page is touched, so Claude focusing its message box on its own opens no keyboard.
        root = new FrameLayout(this);
        root.setFocusableInTouchMode(true);
        root.addView(web);
        setContentView(root);
        root.requestFocus();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Keep rendered content while the app is in the background, so returning shows it at once.
        settings.setOffscreenPreRaster(true);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, disposition, mimeType, length) ->
                download(url, userAgent, disposition, mimeType));

        if (state == null || web.restoreState(state) == null) web.loadUrl(HOME);
    }

    /**
     * Chromium is not told when the app goes to the background: otherwise it marks the page
     * hidden, drops its rendered content and Claude refreshes on return. Android still draws
     * nothing while the window is not visible.
     */
    private static final class KeepVisibleWebView extends WebView {
        KeepVisibleWebView(Context context) { super(context); }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    /** Claude, its sign-in and Anthropic pages stay in the app. */
    private static boolean inApp(Uri uri) {
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null) return false;
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
                || host.equals("anthropic.com") || host.endsWith(".anthropic.com")
                || host.equals("accounts.google.com");
    }

    private void openOutside(Uri uri) {
        try {
            Intent intent = "intent".equals(uri.getScheme())
                    ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (ActivityNotFoundException | java.net.URISyntaxException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void download(String url, String userAgent, String disposition, String mimeType) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme())) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            // The cookies go only to the host that is being downloaded from.
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && inApp(uri)) request.addRequestHeader("Cookie", cookies);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Embedded frames (e.g. artifact previews) load normally.
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (inApp(uri)) return false;
            openOutside(uri);
            return true;
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page's renderer died; start over instead of letting the app crash.
            recreate();
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            // The system file picker directly, without an app chooser in between.
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
                startActivityForResult(pick, CHOOSE_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            String[] resources = request.getResources();
            boolean microphoneOnly = resources.length == 1
                    && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0]);
            if (!microphoneOnly || !inApp(request.getOrigin())) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(resources);
                return;
            }
            if (pendingMicrophone != null) pendingMicrophone.deny();
            pendingMicrophone = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MICROPHONE);
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code != CHOOSE_FILE || fileCallback == null) {
            super.onActivityResult(code, result, data);
            return;
        }
        Uri[] files = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                files = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < files.length; i++) files[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                files = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(files);
        fileCallback = null;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_MICROPHONE || pendingMicrophone == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingMicrophone.grant(pendingMicrophone.getResources());
        } else {
            pendingMicrophone.deny();
        }
        pendingMicrophone = null;
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        web.saveState(state);
    }

    @Override protected void onPause() {
        super.onPause();
        // Also no keyboard of its own when returning to the app.
        root.requestFocus();
        CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}

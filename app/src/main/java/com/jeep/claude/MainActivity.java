package com.jeep.claude;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final String CLAUDE_URL = "https://claude.ai";
    // Standard Mobile Chrome UA without WebView indicators
    private static final String CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    private static final int DEFAULT_BG_COLOR = Color.parseColor("#FAF9F5");

    private WebView webView;
    private WebView popupWebView;
    private FrameLayout popupContainer;
    private SwipeRefreshLayout swipeRefresh;
    private LinearProgressIndicator progressBar;

    private ValueCallback<Uri[]> uploadMessage;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        updateSystemBarsColor(DEFAULT_BG_COLOR);

        webView = findViewById(R.id.webView);
        popupContainer = findViewById(R.id.popupContainer);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);

        initFileChooser();
        initWebView();
        initBackNavigation();

        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        webView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            swipeRefresh.setEnabled(webView.getScrollY() == 0 && popupContainer.getVisibility() != View.VISIBLE);
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(CLAUDE_URL);
        }
    }

    private void updateSystemBarsColor(int color) {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);

        boolean isLight = isColorLight(color);
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(window, window.getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(isLight);
            insetsController.setAppearanceLightNavigationBars(isLight);
        }
    }

    private boolean isColorLight(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.5;
    }

    private void syncThemeColorFromWeb() {
        if (webView == null) return;
        String js = "(function() {" +
                "  var meta = document.querySelector('meta[name=\"theme-color\"]');" +
                "  if (meta && meta.content) return meta.content;" +
                "  var bg = window.getComputedStyle(document.body).backgroundColor;" +
                "  return bg || '';" +
                "})();";
        webView.evaluateJavascript(js, value -> {
            if (value == null || value.equals("null") || value.isEmpty()) return;
            parseAndApplyColor(value);
        });
    }

    private void parseAndApplyColor(String colorStr) {
        try {
            colorStr = colorStr.replace("\"", "").trim();
            if (colorStr.isEmpty()) return;
            int color;
            if (colorStr.startsWith("#")) {
                color = Color.parseColor(colorStr);
            } else if (colorStr.startsWith("rgb")) {
                String clean = colorStr.replaceAll("[^0-9,]", "");
                String[] parts = clean.split(",");
                if (parts.length >= 3) {
                    int r = Integer.parseInt(parts[0].trim());
                    int g = Integer.parseInt(parts[1].trim());
                    int b = Integer.parseInt(parts[2].trim());
                    color = Color.rgb(r, g, b);
                } else {
                    return;
                }
            } else {
                return;
            }
            updateSystemBarsColor(color);
            webView.setBackgroundColor(color);
        } catch (Exception ignored) {}
    }

    private void initFileChooser() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (uploadMessage == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getClipData() != null) {
                            int count = data.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        } else if (data.getData() != null) {
                            results = new Uri[]{data.getData()};
                        }
                    }
                    uploadMessage.onReceiveValue(results);
                    uploadMessage = null;
                }
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyCommonSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        // Disguise as standard Google Chrome browser
        settings.setUserAgentString(CHROME_UA);

        // Remove X-Requested-With header to bypass Google OAuth disallowed_useragent detection
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        applyCommonSettings(webView.getSettings());
        webView.setBackgroundColor(DEFAULT_BG_COLOR);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isInternalOrAuthUrl(url)) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                CookieManager.getInstance().flush();
                syncThemeColorFromWeb();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                            FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                openPopupWindow(resultMsg);
                return true;
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openPopupWindow(Message resultMsg) {
        closePopup();

        popupWebView = new WebView(MainActivity.this);
        applyCommonSettings(popupWebView.getSettings());

        CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);

        popupContainer.removeAllViews();
        popupContainer.addView(popupWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        popupContainer.setVisibility(View.VISIBLE);

        popupWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onCloseWindow(WebView window) {
                closePopup();
                webView.reload();
            }
        });

        popupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isInternalOrAuthUrl(url)) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {}
                closePopup();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                // If OAuth finished and redirected back to claude.ai
                if (url.startsWith("https://claude.ai") && !url.contains("/login")) {
                    closePopup();
                    webView.loadUrl(url);
                }
            }
        });

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popupWebView);
        resultMsg.sendToTarget();
    }

    private void closePopup() {
        if (popupWebView != null) {
            popupContainer.removeView(popupWebView);
            popupWebView.destroy();
            popupWebView = null;
        }
        popupContainer.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        CookieManager.getInstance().flush();
    }

    private boolean isInternalOrAuthUrl(String url) {
        if (url == null) return false;
        return url.startsWith("https://claude.ai") ||
               url.startsWith("https://auth.anthropic.com") ||
               url.startsWith("https://accounts.google.com") ||
               url.startsWith("https://myaccount.google.com") ||
               url.contains("anthropic.com") ||
               url.contains("google.com");
    }

    private void initBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // If Google login popup is open, back closes the popup
                if (popupContainer.getVisibility() == View.VISIBLE) {
                    if (popupWebView != null && popupWebView.canGoBack()) {
                        popupWebView.goBack();
                    } else {
                        closePopup();
                    }
                    return;
                }
                // Otherwise exit directly to home screen
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        closePopup();
        super.onDestroy();
    }
}

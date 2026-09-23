package com.jeep.claude;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;

/**
 * A small launcher, not an embedded browser. Claude and its Google OAuth flow run
 * in the SAME browser cookie jar. The app never observes passwords, DOM or cookies.
 */
public final class MainActivity extends Activity {
    private static final Uri CLAUDE = Uri.parse("https://claude.ai/");
    private boolean launched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        Button openButton = findViewById(R.id.openButton);
        Button homeButton = findViewById(R.id.homeButton);
        openButton.setOnClickListener(v -> openClaude());
        homeButton.setOnClickListener(v -> moveTaskToBack(true));

        // Only launch automatically once per Activity instance; closing the tab
        // must not produce an infinite open-close loop.
        if (state != null) launched = state.getBoolean("launched", false);
        if (!launched) openButton.post(this::openClaude);
    }

    private void openClaude() {
        CustomTabColorSchemeParams light = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.parseColor("#FAF9F5"))
                .setNavigationBarColor(Color.parseColor("#FAF9F5"))
                .build();
        CustomTabColorSchemeParams dark = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.parseColor("#262624"))
                .setNavigationBarColor(Color.parseColor("#262624"))
                .build();

        CustomTabsIntent tab = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(light)
                .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, dark)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                .setShowTitle(false)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build();
        // Do not set an ephemeral profile: Claude and Google need a persistent,
        // shared browser session to finish sign-in and stay signed in.
        try {
            tab.launchUrl(this, CLAUDE);
            launched = true;
            ((TextView) findViewById(R.id.message)).setText(R.string.browser_closed);
        } catch (ActivityNotFoundException e) {
            ((TextView) findViewById(R.id.message)).setText(R.string.browser_missing);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("launched", launched);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        // Applies to this launcher only. Android does not allow an app to
        // override back navigation inside the browser-owned Custom Tab.
        moveTaskToBack(true);
    }
}

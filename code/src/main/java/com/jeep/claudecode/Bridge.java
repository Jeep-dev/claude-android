package com.jeep.claudecode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Claude Code bridge in Termux (assets/bridge.py): started through Termux's RUN_COMMAND
 * service with a per-install token, reached on 127.0.0.1. Results and events arrive on the
 * main thread.
 */
final class Bridge {
    static final String TERMUX = "com.termux";
    static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String PYTHON = "/data/data/com.termux/files/usr/bin/python3";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final int PORT = 8765;
    private static final String BASE = "http://127.0.0.1:" + PORT;
    /** VERSION in assets/bridge.py; a running bridge of another version is replaced. */
    private static final int VERSION = 2;

    interface Reply { void done(int status, JSONObject data); }
    interface Events { void events(List<JSONObject> batch); void link(boolean up); }

    private final Context context;
    private final String token;
    private final Events listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean reading;
    /** Last event seen; -1 asks only for events from now on (nothing from before this launch). */
    private volatile long lastSeq = -1;
    private final List<JSONObject> pending = new ArrayList<>();
    private boolean flushQueued;

    Bridge(Context context, Events listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        SharedPreferences prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        String saved = prefs.getString("token", null);
        if (saved == null) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            saved = hex.toString();
            prefs.edit().putString("token", saved).apply();
        }
        token = saved;
    }

    boolean hasTermux() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    boolean hasPermission() {
        return context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    void get(String path, Reply reply) { request("GET", path, null, reply); }

    void post(String path, JSONObject body, Reply reply) { request("POST", path, body.toString(), reply); }

    private void request(String method, String path, String body, Reply reply) {
        pool.execute(() -> {
            Object[] r = exchange(method, path, body);
            JSONObject data;
            try {
                data = new JSONObject((String) r[1]);
            } catch (JSONException e) {
                data = new JSONObject();
            }
            JSONObject result = data;
            if (reply != null) ui.post(() -> reply.done((int) r[0], result));
        });
    }

    /** One HTTP exchange; status -1 when the bridge cannot be reached. */
    private Object[] exchange(String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            // Never through a proxy (a VPN app's proxy would hold requests to this phone).
            c = (HttpURLConnection) new URL(BASE + path).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(3000);
            c.setReadTimeout(path.startsWith("/health") ? 3000 : 30000);
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

    /**
     * Makes sure a bridge with this app's token and version answers, starting one in Termux if
     * needed. Result: "ok", "termux", "permission", "start" or "timeout".
     */
    void connect(boolean restart, java.util.function.Consumer<String> done) {
        pool.execute(() -> {
            String result;
            if (!restart && isCurrent(exchange("GET", "/health", null))) result = "ok";
            else if (!hasTermux()) result = "termux";
            else if (!hasPermission()) result = "permission";
            else if (!run()) result = "start";
            else result = waitUntilUp();
            if ("ok".equals(result) && !reading) {
                reading = true;
                pool.execute(this::readEvents);
            }
            String r = result;
            ui.post(() -> done.accept(r));
        });
    }

    private static boolean isCurrent(Object[] health) {
        if ((int) health[0] != 200) return false;
        try {
            return new JSONObject((String) health[1]).optInt("version") == VERSION;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Waits at most 12 s for the bridge just started. */
    private String waitUntilUp() {
        long end = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(300); } catch (InterruptedException e) { return "timeout"; }
            if (isCurrent(exchange("GET", "/health", null))) return "ok";
        }
        return "timeout";
    }

    /** Starts the bridge in Termux in the background; it replaces one already running. */
    private boolean run() {
        String script;
        try {
            script = read(context.getAssets().open("bridge.py"));
        } catch (IOException e) {
            return false;
        }
        lastSeq = -1;
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
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reads the event stream, reconnecting, and hands events over in batches. */
    private void readEvents() {
        boolean up = false;
        while (reading) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + "/events?since=" + lastSeq).openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(3000);
                c.setReadTimeout(40000);
                c.setRequestProperty("X-Token", token);
                if (c.getResponseCode() != 200) throw new IOException("status " + c.getResponseCode());
                if (!up) {
                    up = true;
                    ui.post(() -> listener.link(true));
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                for (String line; reading && (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("data: ")) continue;
                    try {
                        JSONObject event = new JSONObject(line.substring(6));
                        lastSeq = event.optLong("seq", 0);
                        queue(event);
                    } catch (JSONException ignored) {
                        // not an event
                    }
                }
            } catch (IOException e) {
                if (up) {
                    up = false;
                    ui.post(() -> listener.link(false));
                }
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private void queue(JSONObject event) {
        synchronized (pending) {
            pending.add(event);
            if (flushQueued) return;
            flushQueued = true;
        }
        ui.postDelayed(() -> {
            List<JSONObject> batch;
            synchronized (pending) {
                batch = new ArrayList<>(pending);
                pending.clear();
                flushQueued = false;
            }
            listener.events(batch);
        }, 33);
    }

    void close() {
        reading = false;
        pool.shutdownNow();
    }
}

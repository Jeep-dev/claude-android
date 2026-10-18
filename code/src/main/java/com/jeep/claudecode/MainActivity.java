package com.jeep.claudecode;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Claude Code front end: native interface for Claude Code running in this app's own userland behind the bridge. */
public final class MainActivity extends Activity implements Bridge.Events {
    private static final int PICK_FILES = 1;
    private static final int ASK_STORAGE = 2;
    private static final String STORAGE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max"};
    private static final String[] EFFORT_NAMES = {"低", "中", "高", "超高", "最高"};

    private Bridge bridge;
    private Userland userland;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Md md;

    // state
    private boolean linked, everLinked, connecting, resumed;
    /** When the bridge in use started; another value means a new bridge and lost turns. */
    private double bridgeStarted;
    private String setup; // why the bridge cannot be reached ("install" while the userland unpacks), or null
    private boolean installing;
    private int installPercent;
    private String installError = "";
    /** Claude Code has an API key or login to use; assumed until the bridge says otherwise. */
    private boolean authSet = true;
    private String claudeVersion = "";
    private String home = "", defaultCwd = "", cwd, model, effort, defaultModel = "";
    private final List<String> models = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private JSONArray sessions = new JSONArray();
    private final Map<String, Chat> chats = new HashMap<>();
    private final Map<String, String> bySession = new HashMap<>();
    private Chat current;
    private boolean stick = true;
    private String search = "";
    /** For the search in the drawer: sessions whose conversation has the words, with that text. */
    private final Map<String, String> hits = new HashMap<>();
    private final Runnable searchText = this::searchText;

    // views
    private DrawerFrame frame;
    private TextView title, banner, linkText;
    private View linkDot;
    private RecyclerView list;
    private ThreadAdapter adapter;
    private ScrollView emptyView;
    private ImageView toBottom, sendBtn;
    private TextView modelLabel, contextLabel;
    private EditText input;
    private LinearLayout attachments, slashMenu, sessionsBox;
    private HorizontalScrollView attachScroll;
    private ScrollView slashScroll;
    private boolean renderQueued;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        cwd = prefs.getString("cwd", "");
        model = prefs.getString("model", "");
        effort = prefs.getString("effort", "");
        try {
            JSONArray saved = new JSONArray(prefs.getString("commands", "[]"));
            for (int i = 0; i < saved.length(); i++) commands.add(saved.optString(i));
        } catch (JSONException ignored) {
            // none saved
        }
        bridge = new Bridge(this, this);
        userland = new Userland(this);
        md = new Md(this, this::copy, this::openUrl);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        build();
        switchTo(newChat());
        renderSessions();
        connect(false);
        ui.postDelayed(ticker, 120);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (adapter != null) adapter.tick();
            updateLink();
            ui.postDelayed(this, 120);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!linked && !connecting && setup == null) connect(false);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    /** The link stayed down: start the bridge again if it is gone (the service also restarts one that died). */
    private final Runnable recover = () -> {
        if (!linked && !connecting && resumed) connect(false);
    };

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        bridge.close();
        work.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (slashScroll.getVisibility() == View.VISIBLE) { hideMenu(); return; }
        if (frame.isOpen()) { frame.close(); return; }
        moveTaskToBack(true);
    }

    private int dp(float v) { return Ui.dp(this, v); }
    private int col(int res) { return Ui.color(this, res); }

    // ================= layout =================

    private void build() {
        LinearLayout main = Ui.column(this);
        main.setBackgroundColor(col(R.color.bg));

        LinearLayout top = Ui.row(this);
        top.setPadding(dp(6), 0, dp(6), 0);
        ImageView menu = Ui.iconButton(this, R.drawable.ic_sidebar, 38, 20, R.color.text2);
        menu.setOnClickListener(v -> frame.open());
        top.addView(menu, new LinearLayout.LayoutParams(dp(38), dp(38)));
        title = Ui.text(this, "新对话", 14.5f, R.color.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setOnClickListener(v -> scrollToEnd(true));
        top.addView(title, Ui.weight());
        ImageView terminal = Ui.iconButton(this, R.drawable.ic_terminal, 38, 20, R.color.text2);
        terminal.setOnClickListener(v -> openTerminal(null));
        top.addView(terminal, new LinearLayout.LayoutParams(dp(38), dp(38)));
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));

        banner = Ui.text(this, "与 Claude Code 的连接断开了，正在重连…", 13.5f, R.color.danger);
        banner.setPadding(dp(12), dp(9), dp(12), dp(9));
        banner.setBackground(Ui.round(col(R.color.dangerSoft), dp(10)));
        banner.setVisibility(View.GONE);
        main.addView(banner, Ui.fill());
        Ui.margins(banner, this, 12, 4, 12, 0);

        FrameLayout content = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(dp(18), dp(6), dp(18), dp(24));
        list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        list.setItemAnimator(null); // rows change in place; animated moves overlap neighbours
        adapter = new ThreadAdapter(this, md, this::copy, row -> {
            // A row opened or closed. At the bottom the list keeps following the end; elsewhere it
            // stays where the reader is, and an opened row hidden below is scrolled into view
            // (its head staying on screen).
            renderNow();
            if (stick) return;
            list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    list.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (row.getParent() == list) {
                        int below = row.getBottom() - (list.getHeight() - list.getPaddingBottom());
                        int dy = Math.min(below, row.getTop() - list.getPaddingTop());
                        if (dy > 0) list.smoothScrollBy(0, dy);
                    }
                    toBottom.setVisibility(list.canScrollVertically(1) ? View.VISIBLE : View.GONE);
                    return true;
                }
            });
        });
        adapter.queue = queueActions;
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && dy == 0) return;
                // Scrolling up by hand lets go of the bottom; a layout change at the bottom does not.
                boolean byHand = rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
                stick = !(byHand && dy < 0) && !rv.canScrollVertically(1);
                toBottom.setVisibility(stick ? View.GONE : View.VISIBLE);
            }
        });
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (stick && b != ob && list.getScrollState() != RecyclerView.SCROLL_STATE_DRAGGING) scrollToEnd(false);
        });
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyView = new ScrollView(this);
        emptyView.setFillViewport(true);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        toBottom = Ui.iconButton(this, R.drawable.ic_arrow_down, 36, 18, R.color.text2);
        toBottom.setBackground(Ui.press(this, Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)), dp(18)));
        toBottom.setElevation(dp(3));
        toBottom.setVisibility(View.GONE);
        toBottom.setOnClickListener(v -> scrollToEnd(true));
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tb.bottomMargin = dp(12);
        content.addView(toBottom, tb);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        main.addView(buildComposer(), Ui.fill());

        frame = new DrawerFrame(this, main, buildDrawer());
        frame.setListener(() -> {
            hideKeyboard();
            loadSessions();
        });
        setContentView(frame);
    }

    private View buildComposer() {
        LinearLayout wrap = Ui.column(this);
        wrap.setPadding(dp(12), dp(4), dp(12), dp(2));
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.round(col(R.color.surface), dp(26), dp(1), col(R.color.border)));
        card.setElevation(dp(2));
        card.setPadding(dp(6), dp(4), dp(6), dp(4));

        slashScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.min(dp(300),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.4f)), MeasureSpec.AT_MOST));
            }
        };
        slashMenu = Ui.column(this);
        slashScroll.addView(slashMenu);
        slashScroll.setVisibility(View.GONE);
        card.addView(slashScroll, Ui.fill());

        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachments = Ui.row(this);
        attachments.setPadding(dp(6), dp(4), dp(6), dp(4));
        attachScroll.addView(attachments);
        attachScroll.setVisibility(View.GONE);
        card.addView(attachScroll, Ui.fill());

        // One line: + , the text, send (or stop while a reply runs and nothing is typed).
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.BOTTOM);
        ImageView attach = Ui.iconButton(this, R.drawable.ic_plus, 40, 22, R.color.text2);
        attach.setOnClickListener(v -> pickFiles());
        row.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));

        input = new EditText(this);
        input.setBackground(null);
        input.setHint("输入 / 以使用命令");
        input.setHintTextColor(col(R.color.text3));
        input.setTextColor(col(R.color.text));
        input.setTextSize(16);
        input.setPadding(dp(4), dp(9), dp(4), dp(9));
        input.setMinHeight(dp(40));
        input.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.34f));
        // The keyboard's Enter sends (its key shows Send); the text still wraps over several lines.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(Integer.MAX_VALUE);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) {
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) send();
                return true;
            }
            return false;
        });
        // A physical Enter sends too; Shift+Enter makes a new line.
        input.setOnKeyListener((v, code, event) -> {
            if (code != android.view.KeyEvent.KEYCODE_ENTER || event.isShiftPressed()) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) send();
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { showMenu(); updateChrome(); }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = new ImageView(this);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(34), dp(34));
        sp.setMargins(dp(2), 0, dp(2), dp(3));
        row.addView(sendBtn, sp);
        card.addView(row, Ui.fill());
        wrap.addView(card, Ui.fill());

        // Under the box: the model and thinking level (tap to change), and the context in use.
        LinearLayout foot = Ui.row(this);
        foot.setPadding(dp(4), 0, dp(10), 0);
        modelLabel = Ui.text(this, "", 12.5f, R.color.text2);
        modelLabel.setSingleLine(true);
        modelLabel.setEllipsize(TextUtils.TruncateAt.END);
        modelLabel.setGravity(Gravity.CENTER_VERTICAL);
        modelLabel.setPadding(dp(8), 0, dp(8), 0);
        modelLabel.setCompoundDrawablePadding(dp(2));
        android.graphics.drawable.Drawable chev = getDrawable(R.drawable.ic_chevron_down).mutate();
        chev.setBounds(0, 0, dp(14), dp(14));
        chev.setTint(col(R.color.text3));
        modelLabel.setCompoundDrawables(null, null, chev, null);
        modelLabel.setBackground(Ui.press(this, null, dp(10)));
        modelLabel.setOnClickListener(v -> modelSheet());
        foot.addView(modelLabel, new LinearLayout.LayoutParams(-2, dp(30)));
        foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        contextLabel = Ui.text(this, "", 12.5f, R.color.text3);
        contextLabel.setTypeface(Typeface.MONOSPACE);
        foot.addView(contextLabel);
        wrap.addView(foot, Ui.fill());
        return wrap;
    }

    private View buildDrawer() {
        LinearLayout d = Ui.column(this);
        d.setBackgroundColor(col(R.color.sidebar));
        d.setClickable(true);

        LinearLayout head = Ui.row(this);
        head.setPadding(dp(18), dp(18), dp(18), dp(12));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.mascot);
        head.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(19)));
        TextView brand = Ui.text(this, "Claude Code", 19, R.color.text);
        brand.setTypeface(Typeface.create(Ui.SERIF, Typeface.BOLD));
        brand.setPadding(dp(10), 0, 0, 0);
        head.addView(brand);
        d.addView(head, Ui.fill());

        LinearLayout newChat = Ui.row(this);
        newChat.setPadding(dp(12), 0, dp(12), 0);
        newChat.setMinimumHeight(dp(42));
        newChat.setBackground(Ui.press(this, null, dp(12)));
        FrameLayout plus = new FrameLayout(this);
        plus.setBackground(Ui.round(col(R.color.accent), dp(13)));
        plus.addView(Ui.icon(this, R.drawable.ic_plus, 16, android.R.color.white), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        newChat.addView(plus, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView nl = Ui.text(this, "新对话", 15.5f, R.color.text);
        nl.setTypeface(Ui.MEDIUM);
        nl.setPadding(dp(10), 0, 0, 0);
        newChat.addView(nl);
        newChat.setOnClickListener(v -> { frame.close(); switchTo(newChat()); });
        d.addView(newChat, Ui.fill());
        Ui.margins(newChat, this, 12, 4, 12, 8);

        LinearLayout searchBox = Ui.row(this);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(Ui.round(col(R.color.hover), dp(10)));
        searchBox.addView(Ui.icon(this, R.drawable.ic_search, 17, R.color.text3));
        EditText searchInput = new EditText(this);
        searchInput.setBackground(null);
        searchInput.setHint("搜索对话");
        searchInput.setHintTextColor(col(R.color.text3));
        searchInput.setTextColor(col(R.color.text));
        searchInput.setTextSize(15);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(8), 0, 0, 0);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                search = s.toString();
                hits.clear();
                renderSessions();
                ui.removeCallbacks(searchText);
                if (!search.trim().isEmpty()) ui.postDelayed(searchText, 250);
            }
        });
        searchBox.addView(searchInput, Ui.weight());
        d.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(38)));
        Ui.margins(searchBox, this, 12, 0, 12, 8);

        ScrollView scroll = new ScrollView(this);
        sessionsBox = Ui.column(this);
        sessionsBox.setPadding(dp(8), dp(4), dp(8), dp(12));
        scroll.addView(sessionsBox);
        d.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(col(R.color.border));
        d.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout foot = Ui.row(this);
        foot.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout link = Ui.row(this);
        link.setPadding(dp(10), 0, dp(10), 0);
        link.setMinimumHeight(dp(40));
        link.setBackground(Ui.press(this, null, dp(10)));
        linkDot = new View(this);
        link.addView(linkDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        linkText = Ui.text(this, "正在连接…", 14, R.color.text2);
        linkText.setPadding(dp(9), 0, 0, 0);
        link.addView(linkText);
        link.setOnClickListener(v -> { if (linked) settingsSheet(); else connect(true); });
        foot.addView(link, Ui.weight());
        ImageView gear = Ui.iconButton(this, R.drawable.ic_gear, 40, 20, R.color.text2);
        gear.setOnClickListener(v -> settingsSheet());
        foot.addView(gear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        d.addView(foot, Ui.fill());
        return d;
    }

    // ================= chats & rendering =================

    private Chat newChat() {
        Chat chat = new Chat(Chat.newKey());
        chat.cwd = cwd.isEmpty() ? defaultCwd : cwd;
        chats.put(chat.key, chat);
        return chat;
    }

    private void switchTo(Chat chat) {
        if (current != null) current.draft = input.getText().toString();
        current = chat;
        input.setText(chat.draft);
        input.setSelection(input.length());
        stick = true;
        adapter.show(chat, true);
        renderNow();
        renderSessions();
    }

    /** Coalesces updates to one per frame. */
    private void render() {
        if (renderQueued) return;
        renderQueued = true;
        list.postOnAnimation(() -> { renderQueued = false; renderNow(); });
    }

    private void renderNow() {
        adapter.show(current, false);
        boolean empty = current.messages.isEmpty() && !current.running;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) fillEmpty();
        if (stick) scrollToEnd(false);
        updateChrome();
        updateLink();
    }

    private void scrollToEnd(boolean smooth) {
        stick = true;
        toBottom.setVisibility(View.GONE);
        list.post(() -> {
            // The reader may have taken the list meanwhile: never pull it back down.
            if (!stick || list.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) return;
            int count = adapter.getItemCount();
            if (count == 0) return;
            if (smooth) list.smoothScrollToPosition(count - 1);
            else ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(count - 1, -100000);
        });
    }

    private void updateChrome() {
        title.setText(current.title.isEmpty() ? "新对话" : current.title);
        boolean typed = input.getText().toString().trim().length() > 0 || !current.attachments.isEmpty();
        boolean canSend = linked && typed;
        // Plain icons, no filled button: Enter to send, a ringed square to stop the reply.
        boolean stop = current.running && !typed;
        sendBtn.setImageResource(stop ? R.drawable.ic_stop_circle : R.drawable.ic_enter);
        sendBtn.setImageTintList(android.content.res.ColorStateList.valueOf(col(R.color.text)));
        sendBtn.setBackground(Ui.press(this, null, dp(18)));
        sendBtn.setAlpha(stop || canSend ? 1f : 0.35f);
        sendBtn.setEnabled(stop || canSend);
        sendBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        renderAttachments();
        updateFoot();
    }

    /** The model in use: the one picked here, else this chat's, else Claude Code's default. */
    private String modelInUse() {
        if (!model.isEmpty()) return model;
        if (current.model != null && !current.model.isEmpty()) return current.model;
        return defaultModel;
    }

    private void updateFoot() {
        String m = modelInUse();
        String name = m.isEmpty() ? "默认模型" : modelName(m);
        String level = effortInUse();
        modelLabel.setText(level.isEmpty() ? name : name + " " + level);
        // The chat's reported window holds for its own model; one picked since has its own.
        boolean same = model.isEmpty() || model.equals(current.model);
        long window = same && current.window > 0 ? current.window : windowOf(m);
        contextLabel.setText(window > 0 ? count(current.context) + "/" + count(window)
                : current.context > 0 ? count(current.context) : "");
    }

    /** "claude-opus-5-5[1m]" → "opus 5.5"; another provider's name stays as it is. */
    private static String modelName(String id) {
        String n = shortModel(id);
        if (!n.startsWith("claude-")) return n;
        n = n.substring(7).replaceAll("-\\d{8}$", "");
        n = n.replaceAll("-(\\d+)-(\\d+)$", " $1.$2").replaceAll("-(\\d+)$", " $1");
        return n;
    }

    /** 250k, 1m: tokens in round numbers. */
    private static String count(long n) {
        if (n >= 1_000_000) {
            String v = String.format(Locale.ROOT, "%.1f", n / 1e6);
            return (v.endsWith(".0") ? v.substring(0, v.length() - 2) : v) + "m";
        }
        if (n >= 1000) return Math.round(n / 1000.0) + "k";
        return String.valueOf(n);
    }

    /** A model's context window, as Claude Code last reported it; a [1m] model has a million. */
    private long windowOf(String m) {
        try {
            long w = new JSONObject(prefs.getString("windows", "{}")).optLong(m);
            if (w > 0) return w;
        } catch (JSONException ignored) { /* none */ }
        return m.toLowerCase(Locale.ROOT).endsWith("[1m]") ? 1_000_000 : 0;
    }

    private static String shortModel(String m) {
        return m == null || m.isEmpty() ? "默认模型" : m.replaceAll("(?i)\\[1m\\]$", "");
    }

    private void fillEmpty() {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(20), dp(22), dp(40));
        if (setup != null) buildSetup(box);
        else buildHero(box);
        emptyView.removeAllViews();
        emptyView.addView(box, new ViewGroup.LayoutParams(-1, -1));
    }

    private void buildHero(LinearLayout box) {
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.mascot);
        box.addView(mark, new LinearLayout.LayoutParams(dp(84), dp(52)));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 5 ? "夜深了" : h < 11 ? "早上好" : h < 13 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        TextView big = Ui.text(this, hello, 30, R.color.text);
        big.setTypeface(Ui.SERIF);
        big.setPadding(0, dp(18), 0, dp(10));
        big.setGravity(Gravity.CENTER);
        box.addView(big, Ui.fill());
        String folder = Ui.base(current.cwd == null || current.cwd.isEmpty() ? defaultCwd : current.cwd);
        SpannableStringBuilder sub = new SpannableStringBuilder();
        if (linked) {
            sub.append("在 ");
            int s = sub.length();
            sub.append(folder.isEmpty() ? "~" : folder);
            sub.setSpan(new StyleSpan(Typeface.BOLD), s, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sub.append(" 里，想做点什么？");
        } else {
            sub.append("正在启动 Claude Code…");
        }
        TextView p = Ui.text(this, sub, 15, R.color.text2);
        p.setGravity(Gravity.CENTER);
        box.addView(p, Ui.fill());
        if (linked && !authSet) {
            TextView none = Ui.text(this, "还没有配置模型服务：填上 API 地址和 Key，或从 Termux 导入原来的配置。", 14, R.color.danger);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(10));
            box.addView(none, Ui.fill());
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER);
            row.addView(button("填写 API", true, v -> apiSheet()));
            TextView imp = button("从 Termux 导入", false, v -> importSheet());
            row.addView(imp);
            Ui.margins(imp, this, 8, 0, 0, 0);
            box.addView(row, Ui.fill());
            return;
        }
        LinearLayout chips = Ui.column(this);
        chips.setGravity(Gravity.CENTER_HORIZONTAL);
        chips.setPadding(0, dp(24), 0, 0);
        for (String s : new String[]{"看看这个文件夹里有什么", "帮我写一个 Python 脚本", "解释一下最近的报错"}) {
            TextView chip = Ui.text(this, s, 14, R.color.text2);
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setBackground(Ui.press(this, Ui.round(col(R.color.surface), dp(20), dp(1), col(R.color.border)), dp(20)));
            chip.setOnClickListener(v -> { input.setText(s); input.setSelection(s.length()); input.requestFocus(); showKeyboard(); });
            chips.addView(chip, Ui.wrap());
            Ui.margins(chip, this, 0, 0, 0, 8);
        }
        box.addView(chips);
    }

    private void buildSetup(LinearLayout box) {
        box.setGravity(Gravity.START);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.mascot);
        box.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(33)));
        boolean busy = "install".equals(setup);
        TextView h = Ui.text(this, busy ? "正在准备运行环境" : "Claude Code 没能启动", 26, R.color.text);
        h.setTypeface(Ui.SERIF);
        h.setPadding(0, dp(16), 0, dp(8));
        box.addView(h);
        String why;
        if (busy) why = "第一次打开或更新后，要把 Claude Code 和它的 Linux 环境解压到手机上，大约半分钟到一分钟。\n\n" + installPercent + "%";
        else if ("installFailed".equals(setup)) why = "解压运行环境失败：" + installError;
        else why = "后台 30 秒内没有起来。可以重试，或者在设置里看运行日志。";
        TextView p = Ui.text(this, why, 15, R.color.text2);
        p.setLineSpacing(0, 1.25f);
        p.setPadding(0, 0, 0, dp(12));
        box.addView(p);
        if (busy) return;
        LinearLayout actions = Ui.row(this);
        TextView retry = button("重试", true, v -> connect(true));
        retry.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        retry.setTextColor(col(R.color.bg));
        actions.addView(retry);
        TextView logs = button("运行日志", false, v -> logSheet());
        actions.addView(logs);
        Ui.margins(logs, this, 8, 0, 0, 0);
        box.addView(actions);
        Ui.margins(actions, this, 0, 18, 0, 0);
    }

    /** Unpacks the userland (first launch, or an update that brings a new one), then connects. */
    private void install() {
        if (installing) return;
        installing = true;
        setup = "install";
        installPercent = 0;
        linked = false;
        renderNow();
        work.execute(() -> {
            String error = null;
            try {
                userland.install(p -> ui.post(() -> {
                    installPercent = p;
                    if ("install".equals(setup) && emptyView.getVisibility() == View.VISIBLE) fillEmpty();
                }));
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String err = error;
            ui.post(() -> {
                installing = false;
                if (err != null) {
                    setup = "installFailed";
                    installError = err;
                    renderNow();
                    return;
                }
                setup = null;
                connect(false);
                if (!prefs.getBoolean("askedStorage", false)) {
                    prefs.edit().putBoolean("askedStorage", true).apply();
                    requestStorage();
                }
            });
        });
    }

    private TextView button(String label, boolean accent, View.OnClickListener click) {
        TextView b = Ui.text(this, label, 14.5f, accent ? android.R.color.white : R.color.text);
        b.setTypeface(Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(38));
        b.setBackground(Ui.press(this, accent ? Ui.round(col(R.color.accent), dp(10))
                : Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)), dp(10)));
        b.setOnClickListener(click);
        b.setLayoutParams(Ui.wrap());
        return b;
    }

    // ================= sessions =================

    private void loadSessions() {
        if (!linked) { renderSessions(); return; }
        bridge.get("/sessions", (status, data) -> {
            if (status == 200 && data.optJSONArray("sessions") != null) sessions = data.optJSONArray("sessions");
            renderSessions();
        });
    }

    /** Asks the bridge which conversations contain the words being searched for. */
    private void searchText() {
        String q = search.trim();
        if (q.isEmpty() || !linked) return;
        bridge.get("/search?q=" + Uri.encode(q), (status, data) -> {
            if (!q.equals(search.trim()) || status != 200) return;
            hits.clear();
            JSONArray a = data.optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null) hits.put(r.optString("id"), r.optString("snippet"));
            }
            renderSessions();
        });
    }

    private static String group(double mtime) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        long t = (long) (mtime * 1000), s = start.getTimeInMillis(), day = 86_400_000L;
        if (t >= s) return "今天";
        if (t >= s - day) return "昨天";
        if (t >= s - 7 * day) return "过去 7 天";
        if (t >= s - 30 * day) return "过去 30 天";
        return "更早";
    }

    private void renderSessions() {
        if (sessionsBox == null) return;
        sessionsBox.removeAllViews();
        String q = search.trim().toLowerCase(Locale.ROOT);
        String group = "";
        int shown = 0;
        // Pinned ones first, under their own heading.
        java.util.Set<String> pins = pinned();
        List<JSONObject> ordered = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s != null && pins.contains(s.optString("id")) == (pass == 0)) ordered.add(s);
            }
        }
        for (JSONObject s : ordered) {
            String titleText = s.optString("title"), folder = s.optString("cwd"), id = s.optString("id");
            boolean byName = titleText.toLowerCase(Locale.ROOT).contains(q) || folder.toLowerCase(Locale.ROOT).contains(q);
            if (!q.isEmpty() && !byName && !hits.containsKey(id)) continue;
            String g = pins.contains(id) ? "置顶" : group(s.optDouble("mtime"));
            if (!g.equals(group)) {
                group = g;
                TextView label = Ui.text(this, g, 12, R.color.text3);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setPadding(dp(10), dp(14), dp(10), dp(6));
                sessionsBox.addView(label);
            }
            Chat chat = chats.get(bySession.containsKey(id) ? bySession.get(id) : id);
            boolean live = chat != null ? chat.running : s.optBoolean("running");
            LinearLayout item = Ui.column(this);
            item.setPadding(dp(10), dp(9), dp(10), dp(9));
            boolean active = id.equals(current.sessionId);
            item.setBackground(Ui.press(this, active ? Ui.round(col(R.color.active), dp(10)) : null, dp(10)));
            LinearLayout line = Ui.row(this);
            if (live) {
                View dot = new View(this);
                dot.setBackground(Ui.round(col(R.color.accent), dp(4)));
                line.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
                Ui.margins(dot, this, 0, 0, 6, 0);
            }
            TextView t = Ui.text(this, titleText, 15, R.color.text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(t, Ui.weight());
            item.addView(line, Ui.fill());
            TextView meta = Ui.text(this, Ui.base(folder), 12.5f, R.color.text3);
            meta.setPadding(0, dp(3), 0, 0);
            meta.setSingleLine(true);
            if (!q.isEmpty() && !byName) { // found in what was said: show where
                String snip = hits.get(id);
                SpannableStringBuilder sb = new SpannableStringBuilder(snip);
                int at = snip.toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) sb.setSpan(new ForegroundColorSpan(col(R.color.text)), at, at + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                meta.setText(sb);
                meta.setEllipsize(TextUtils.TruncateAt.END);
            }
            item.addView(meta);
            item.setOnClickListener(v -> openSession(id, titleText, folder));
            item.setOnLongClickListener(v -> { sessionActions(id, titleText, folder); return true; });
            sessionsBox.addView(item, Ui.fill());
            shown++;
        }
        if (shown == 0) {
            TextView empty = Ui.text(this, linked ? (q.isEmpty() ? "还没有对话" : "没有匹配的对话") : "连接后显示历史对话", 14, R.color.text3);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            sessionsBox.addView(empty, Ui.fill());
        }
    }

    private void openSession(String id, String titleText, String folder) {
        frame.close();
        String key = bySession.containsKey(id) ? bySession.get(id) : id;
        Chat chat = chats.get(key);
        if (chat == null) {
            chat = new Chat(key);
            chat.sessionId = id;
            chat.cwd = folder;
            chat.loaded = false;
            chats.put(key, chat);
            bySession.put(id, key);
        }
        if (chat.title.isEmpty()) chat.title = titleText;
        switchTo(chat);
        loadHistory(chat);
    }

    /** Reads a saved conversation once; a failed read is tried again on the next link or tap. */
    private void loadHistory(Chat target) {
        if (target.loaded || target.sessionId == null || !linked) return;
        target.loaded = true;
        bridge.get("/history?id=" + Uri.encode(target.sessionId), (status, data) -> {
            if (status != 200) {
                target.loaded = false;
                toast(status == -2 ? "读取对话失败：桥接 30 秒没有回应" : status == -1 ? "读取对话失败：连不上桥接" : "读取对话失败：" + data.optString("error", "HTTP " + status));
                return;
            }
            target.load(data);
            if (!data.optString("cwd").isEmpty()) target.cwd = data.optString("cwd");
            if (current == target) { adapter.show(target, true); stick = true; renderNow(); }
        });
    }

    private java.util.Set<String> pinned() {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONArray a = savedArray("pinned");
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private void sessionActions(String id, String titleText, String folder) {
        Dialog sheet = sheet(titleText, null);
        LinearLayout card = card(sheet);
        boolean pin = pinned().contains(id);
        option(card, R.drawable.ic_pin, pin ? "取消置顶" : "置顶", null, false, () -> {
            sheet.dismiss();
            java.util.Set<String> pins = pinned();
            if (pin) pins.remove(id); else pins.add(id);
            prefs.edit().putString("pinned", new JSONArray(pins).toString()).apply();
            renderSessions();
        });
        option(card, R.drawable.ic_trash, "删除对话", null, true, () -> {
            sheet.dismiss();
            bridge.post("/delete", obj("id", id), (status, data) -> {
                if (status != 200) { toast("删除失败"); return; }
                JSONArray next = new JSONArray();
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject s = sessions.optJSONObject(i);
                    if (s != null && !id.equals(s.optString("id"))) next.put(s);
                }
                sessions = next;
                String key = bySession.containsKey(id) ? bySession.get(id) : id;
                if (current.key.equals(key)) switchTo(newChat());
                chats.remove(key);
                renderSessions();
                toast("已删除");
            });
        });
        sheet.show();
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException ignored) {
            // keys are strings
        }
        return o;
    }

    // ================= bridge events =================

    @Override public void events(List<JSONObject> batch) {
        for (JSONObject ev : batch) onEvent(ev);
        for (Chat ch : new ArrayList<>(chats.values())) sendQueued(ch);
        render();
        if (frame.isOpen()) renderSessions();
    }

    private Chat chatFor(JSONObject ev) {
        String key = ev.optString("chat");
        Chat chat = chats.get(key);
        if (chat != null) return chat;
        JSONObject data = ev.optJSONObject("data");
        String sid = data != null && data.has("session_id") ? data.optString("session_id") : key.startsWith("n-") ? null : key;
        if (sid != null && bySession.containsKey(sid)) return chats.get(bySession.get(sid));
        // Started before this app was: its history loads from the transcript when opened.
        chat = new Chat(key);
        chat.sessionId = sid;
        chat.cwd = defaultCwd;
        chat.loaded = false;
        chats.put(key, chat);
        if (sid != null) bySession.put(sid, key);
        return chat;
    }

    private void onEvent(JSONObject ev) {
        Chat chat = chatFor(ev);
        chat.lastEvent = System.currentTimeMillis();
        String kind = ev.optString("kind");
        JSONObject data = ev.optJSONObject("data");
        if ("cc".equals(kind) && data != null) {
            if ("system".equals(data.optString("type")) && "init".equals(data.optString("subtype"))) {
                SharedPreferences.Editor e = prefs.edit();
                if (data.optJSONArray("tools") != null) e.putString("tools", data.optJSONArray("tools").toString());
                if (data.optJSONArray("mcp_servers") != null) e.putString("mcpServers", data.optJSONArray("mcp_servers").toString());
                e.apply();
                JSONArray cmds = data.optJSONArray("slash_commands");
                if (cmds != null && cmds.length() > 0) {
                    commands.clear();
                    for (int i = 0; i < cmds.length(); i++) if (!cmds.optString(i).startsWith("_")) commands.add(cmds.optString(i));
                    prefs.edit().putString("commands", new JSONArray(commands).toString()).apply();
                }
            }
            if (chat.apply(data)) loadSessions();
            if ("result".equals(data.optString("type")) && chat.window > 0) {
                try {
                    JSONObject w = new JSONObject(prefs.getString("windows", "{}"));
                    String m = chat.model != null ? chat.model : modelInUse();
                    w.put(m, chat.window);
                    if (!model.isEmpty()) w.put(model, chat.window);
                    prefs.edit().putString("windows", w.toString()).apply();
                } catch (JSONException ignored) { /* not saved */ }
            }
            if (chat.sessionId != null) bySession.put(chat.sessionId, chat.key);
        } else if ("state".equals(kind)) {
            if (data != null && data.optBoolean("running") && !chat.running) {
                chat.running = true;
                chat.startedAt = System.currentTimeMillis();
            }
        } else if ("exit".equals(kind)) {
            if (data != null && data.optBoolean("running")) {
                Chat.Msg m = chat.reply(true);
                Chat.Block b = new Chat.Block(Chat.Block.ERROR);
                b.title = "Claude Code 意外退出";
                int code = data.optInt("code");
                // Popen gives a negative code for a signal; 9 is the system killing it.
                String how = code == -9 ? "被系统强制结束（SIGKILL）"
                        : code < 0 ? "被信号 " + (-code) + " 结束" : "退出码 " + code;
                StringBuilder err = new StringBuilder();
                for (String line : data.optString("stderr").split("\n")) {
                    // Claude Code notes models it does not know (DeepSeek's) on every run: not the cause.
                    if (line.trim().isEmpty() || line.contains("unrecognized_model")) continue;
                    err.append('\n').append(line);
                }
                b.text = how + err;
                m.blocks.add(b);
                m.done = true;
                m.touch();
                chat.stopped = true; // Claude Code died: what is queued waits to be sent by hand
            }
            chat.running = false;
        }
    }

    @Override public void link(boolean up) {
        if (up == linked) return;
        if (up) afterLink();
        else {
            linked = false;
            updateLink();
            render();
            ui.removeCallbacks(recover);
            ui.postDelayed(recover, 2000);
        }
    }

    private int connectAttempt;

    /** Connects to the bridge; a restart is never refused, and only the latest attempt counts. */
    private void connect(boolean restart) {
        if (connecting && !restart) return;
        connecting = true;
        int attempt = ++connectAttempt;
        updateLink();
        bridge.connect(restart, result -> {
            if (attempt != connectAttempt) return;
            connecting = false;
            if ("ok".equals(result)) {
                setup = null;
                afterLink();
            } else if ("install".equals(result)) {
                install();
            } else {
                setup = result;
                linked = false;
                renderNow();
            }
        });
    }

    private void afterLink() {
        linked = true;
        everLinked = true;
        setup = null;
        bridge.get("/health", (status, data) -> {
            if (status != 200) return;
            double started = data.optDouble("started", 0);
            if (bridgeStarted != 0 && started != bridgeStarted) {
                // A new bridge: the old one's turns ended with it.
                for (Chat ch : chats.values()) {
                    if (ch.running) fail(ch, "后台重启了", "这一轮被中断，发消息即可接着这个对话继续。");
                }
            }
            bridgeStarted = started;
            home = data.optString("home");
            defaultCwd = data.optString("defaultCwd");
            for (Chat ch : chats.values()) if (ch.cwd == null || ch.cwd.isEmpty()) ch.cwd = cwd.isEmpty() ? defaultCwd : cwd;
            renderNow();
        });
        bridge.get("/config", (status, data) -> {
            if (status != 200) return;
            models.clear();
            JSONArray m = data.optJSONArray("models");
            for (int i = 0; m != null && i < m.length(); i++) models.add(m.optString(i));
            defaultModel = data.optString("defaultModel");
            authSet = data.optBoolean("auth", true);
            claudeVersion = data.optString("claudeVersion");
            updateChrome();
            if (current.messages.isEmpty()) renderNow();
        });
        renderNow();
        loadSessions();
        loadHistory(current);
    }

    private String shownLink;

    private void updateLink() {
        if (linkDot == null) return;
        // Connected only while the bridge keeps pinging: a frozen one keeps the connection open.
        boolean silent = linked && Bridge.lastAlive > 0 && System.currentTimeMillis() - Bridge.lastAlive > 8000;
        String label = silent ? "后台没有响应" : linked ? "Claude Code 运行中" : installing ? "正在准备…"
                : connecting ? "正在启动…" : "未运行 · 点此重试";
        int bannerShown = !linked && !connecting && setup == null && everLinked ? View.VISIBLE : View.GONE;
        if (banner.getVisibility() != bannerShown) banner.setVisibility(bannerShown);
        if (label.equals(shownLink)) return;
        shownLink = label;
        int dot = silent ? R.color.warn : linked ? R.color.ok : connecting ? R.color.text3 : R.color.danger;
        linkDot.setBackground(Ui.round(col(dot), dp(4)));
        linkText.setText(label);
    }

    private boolean hasStorage() { return checkSelfPermission(STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    private void requestStorage() {
        requestPermissions(new String[]{STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
    }

    private Runnable afterStorage;

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != ASK_STORAGE) return;
        Runnable next = afterStorage;
        afterStorage = null;
        refreshSettings();
        if (!hasStorage()) { toast("没有存储权限：Claude Code 看不到手机里的文件"); return; }
        // /sdcard is shared into the userland when the bridge starts.
        if (!installing) connect(true);
        if (next != null) next.run();
    }

    // ================= sending =================

    private void send() {
        Chat chat = current;
        String text = input.getText().toString().replaceAll("\\s+$", "");
        if (text.trim().isEmpty() && chat.attachments.isEmpty()) {
            if (chat.running) stop(chat);
            return;
        }
        if (text.matches("^/\\S[^\\n]*$") && localCommand(text)) {
            input.setText("");
            hideMenu();
            updateChrome();
            return;
        }
        if (!linked) {
            toast(installing ? "运行环境还在准备，稍后再发" : "Claude Code 正在启动，稍后再发");
            if (!connecting) connect(false);
            return;
        }
        List<Chat.Attachment> attached = new ArrayList<>(chat.attachments);
        chat.attachments.clear();
        input.setText("");
        hideMenu();
        if (chat.running) {
            // Written while a reply runs: it waits, and goes when the reply ends.
            chat.queue.add(new Chat.Queued(text, attached));
            stick = true;
            renderNow();
            return;
        }
        hideKeyboard();
        submit(chat, text, attached);
    }

    /** Sends a message as the chat's next turn. */
    private void submit(Chat chat, String text, List<Chat.Attachment> attached) {
        Chat.Msg user = new Chat.Msg(Chat.Msg.USER);
        user.text = text;
        for (Chat.Attachment a : attached) {
            if (a.image) user.images.add("data:" + a.type + ";base64," + a.data);
            else user.files.add(a.name);
        }
        chat.messages.add(user);
        if (chat.title.isEmpty()) {
            String t = (!text.isEmpty() ? text : !user.files.isEmpty() ? user.files.get(0) : "图片").split("\n")[0];
            chat.title = t.length() > 60 ? t.substring(0, 60) : t;
        }
        if (chat.cwd == null || chat.cwd.isEmpty()) chat.cwd = cwd.isEmpty() ? defaultCwd : cwd;
        chat.running = true;
        chat.stopped = false;
        chat.startedAt = System.currentTimeMillis();
        chat.verb = chat.randomVerb();
        chat.detail = "";
        stick = true;
        renderNow();
        upload(chat, attached, 0, new ArrayList<>(), text);
    }

    /** The reply ended: the next queued message goes, unless the reply was stopped by hand. */
    private void sendQueued(Chat chat) {
        if (chat.running || chat.queue.isEmpty() || !linked) return;
        Chat.Queued q = chat.queue.get(0);
        if (chat.stopped && !q.now) return;
        chat.queue.remove(0);
        submit(chat, q.text, q.attachments);
    }

    private final ThreadAdapter.Queue queueActions = new ThreadAdapter.Queue() {
        @Override public void edit(Chat.Queued q) {
            current.queue.remove(q);
            String typed = input.getText().toString();
            String text = typed.trim().isEmpty() ? q.text : q.text + "\n" + typed;
            current.attachments.addAll(0, q.attachments);
            input.setText(text);
            input.setSelection(input.length());
            input.requestFocus();
            showKeyboard();
            renderNow();
        }

        @Override public void cancel(Chat.Queued q) {
            current.queue.remove(q);
            renderNow();
        }

        @Override public void sendNow(Chat.Queued q) {
            Chat chat = current;
            chat.queue.remove(q);
            chat.queue.add(0, q);
            q.now = true;
            if (chat.running) stop(chat);
            else sendQueued(chat);
            renderNow();
        }
    };

    /** Saves non-picture files into the working folder one by one, then sends. */
    private void upload(Chat chat, List<Chat.Attachment> attached, int from, List<String> saved, String text) {
        int i = from;
        while (i < attached.size() && attached.get(i).image) i++;
        if (i >= attached.size()) { deliver(chat, attached, saved, text); return; }
        Chat.Attachment f = attached.get(i);
        int next = i + 1;
        bridge.post("/upload", obj("cwd", chat.cwd, "name", f.name, "data", f.data), (status, data) -> {
            if (status != 200) {
                fail(chat, "发送失败", f.name + " 没能存到 " + Ui.base(chat.cwd) + "：" + data.optString("error", String.valueOf(status)));
                return;
            }
            saved.add(data.optString("path"));
            upload(chat, attached, next, saved, text);
        });
    }

    private void deliver(Chat chat, List<Chat.Attachment> attached, List<String> saved, String text) {
        JSONArray content = new JSONArray();
        for (Chat.Attachment a : attached) {
            if (!a.image) continue;
            content.put(obj("type", "image", "source", obj("type", "base64", "media_type", a.type, "data", a.data)));
        }
        StringBuilder full = new StringBuilder(text.trim().isEmpty() ? "" : text);
        if (!saved.isEmpty()) {
            if (full.length() > 0) full.append("\n\n");
            full.append(saved.size() > 1 ? "附件已保存到：" : "附件已保存为：");
            for (String p : saved) full.append("\n- ").append(p);
        }
        if (full.length() > 0) content.put(obj("type", "text", "text", full.toString()));
        long sentAt = System.currentTimeMillis();
        JSONObject body = obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
                "model", model.isEmpty() ? null : model, "effort", effort.isEmpty() ? null : effort,
                "content", content, "mid", chat.key + ":" + sentAt);
        sendWithRetry(chat, body, sentAt, 0);
    }

    /** The answer may be lost with the message delivered: the bridge ignores a repeated mid. */
    private void sendWithRetry(Chat chat, JSONObject body, long sentAt, int attempt) {
        bridge.post("/send", body, (status, data) -> {
            if (status == 200) return;
            if (status < 0 && attempt < 2) {
                ui.postDelayed(() -> sendWithRetry(chat, body, sentAt, attempt + 1), 1200);
                return;
            }
            if (chat.lastEvent > sentAt) return; // it is running after all
            fail(chat, "发送失败", status == -2 ? "后台没有回应。" : status == -1 ? "连不上后台。" : data.optString("error", String.valueOf(status)));
        });
    }

    private void fail(Chat chat, String titleText, String text) {
        chat.running = false;
        Chat.Msg m = chat.reply(true);
        Chat.Block b = new Chat.Block(Chat.Block.ERROR);
        b.title = titleText;
        b.text = text == null ? "" : text;
        m.blocks.add(b);
        m.done = true;
        m.touch();
        render();
    }

    private void stop(Chat chat) {
        chat.stopping = true;
        bridge.post("/stop", obj("chat", chat.key), null);
    }

    // ================= slash commands =================

    private static final String[][] LOCAL = {
            {"help", "", "显示所有命令"}, {"new", "", "新对话"}, {"clear", "", "新对话（清空上下文）"},
            {"effort", "low|medium|high|xhigh|max|default", "思考级别，从下一条消息起生效"},
            {"model", "名称", "切换模型，不带名称则打开选择"}, {"folder", "", "选择新对话的工作文件夹"},
            {"stop", "", "停止当前回复"}};
    private static final String[][] KNOWN = {
            {"compact", "说明", "压缩上下文，可附上要保留的重点"}, {"context", "", "查看上下文占用"},
            {"cost", "", "查看这次对话的用量"}, {"init", "", "扫描项目，生成 CLAUDE.md"},
            {"recap", "", "总结这次对话"}, {"review", "", "审查当前改动"},
            {"security-review", "", "安全审查当前改动"}, {"simplify", "", "简化刚改过的代码"}};
    private static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "clear", "model", "effort", "heapdump", "color", "config", "focus", "fast", "mcp", "agents", "output-style",
            "reload-plugins", "reload-skills", "auto-mode-setup", "autocompact", "rename", "usage", "insights", "goal",
            "design-consent", "design-revoke", "list-agents", "team-onboarding", "workflow-launch-exec", "doctor"));

    /** name -> {args, description, "local" when the app handles it} */
    private LinkedHashMap<String, String[]> commandList() {
        LinkedHashMap<String, String[]> all = new LinkedHashMap<>();
        for (String[] c : LOCAL) all.put(c[0], new String[]{c[1], c[2], "local"});
        for (String[] c : KNOWN) all.put(c[0], new String[]{c[1], c[2], ""});
        for (String c : commands) if (!all.containsKey(c) && !HIDDEN.contains(c)) all.put(c, new String[]{"", "技能", ""});
        return all;
    }

    private void note(CharSequence text) {
        Chat.Msg m = new Chat.Msg(Chat.Msg.NOTE);
        m.note = text;
        current.messages.add(m);
        stick = true;
        renderNow();
    }

    /** Text from parts: strings as they are, {"b", text} bold, {"c", text} command. */
    private CharSequence styled(Object... parts) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Object p : parts) {
            if (p instanceof String[]) {
                String[] t = (String[]) p;
                int st = s.length();
                s.append(t[1]);
                if ("b".equals(t[0])) {
                    s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.text)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    s.setSpan(new TypefaceSpan("monospace"), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.accentPress)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                s.append(String.valueOf(p));
            }
        }
        return s;
    }

    private String effortLabel(String e) {
        for (int i = 0; i < EFFORTS.length; i++) if (EFFORTS[i].equals(e)) return EFFORT_NAMES[i] + "（" + e + "）";
        return "默认（跟随 Claude Code 设置）";
    }

    private boolean localCommand(String text) {
        String t = text.trim().substring(1);
        String name = t.split("\\s+", 2)[0];
        String arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        switch (name) {
            case "help": {
                List<Object> parts = new ArrayList<>();
                parts.add(new String[]{"b", "命令"});
                for (Map.Entry<String, String[]> e : commandList().entrySet()) {
                    parts.add("\n");
                    parts.add(new String[]{"c", "/" + e.getKey() + (e.getValue()[0].isEmpty() ? "" : " " + e.getValue()[0])});
                    parts.add("  " + e.getValue()[1]);
                }
                note(styled(parts.toArray()));
                return true;
            }
            case "new": case "clear":
                switchTo(newChat());
                return true;
            case "stop":
                if (current.running) stop(current); else toast("现在没有在运行");
                return true;
            case "folder":
                if (current.sessionId != null) toast("已有对话的文件夹不能更改，新对话可以选");
                else folderSheet(null);
                return true;
            case "model":
                if (arg.isEmpty()) { modelSheet(); return true; }
                model = "default".equals(arg) || "默认".equals(arg) ? "" : arg;
                prefs.edit().putString("model", model).apply();
                note(styled("模型：", new String[]{"b", shortModel(model.isEmpty() ? defaultModel : model)},
                        model.isEmpty() ? "（默认）" : "", "，从下一条消息起生效"));
                return true;
            case "effort": {
                String level = null;
                String a = arg.toLowerCase(Locale.ROOT);
                for (int i = 0; i < EFFORTS.length; i++) if (EFFORTS[i].equals(a) || EFFORT_NAMES[i].equals(arg)) level = EFFORTS[i];
                if (a.equals("default") || arg.equals("默认") || a.equals("auto")) level = "";
                if (level == null) {
                    List<Object> parts = new ArrayList<>();
                    parts.add("思考级别：");
                    parts.add(new String[]{"b", effortLabel(effort)});
                    parts.add("\n可选：");
                    for (String e : EFFORTS) { parts.add(new String[]{"c", "/effort " + e}); parts.add("   "); }
                    parts.add(new String[]{"c", "/effort default"});
                    note(styled(parts.toArray()));
                    return true;
                }
                effort = level;
                prefs.edit().putString("effort", effort).apply();
                note(styled("思考级别：", new String[]{"b", effortLabel(effort)}, "，从下一条消息起生效"));
                return true;
            }
            default:
                return false; // Claude Code's own command or skill
        }
    }

    private void showMenu() {
        String v = input.getText().toString();
        if (!v.matches("^/\\S*$")) { hideMenu(); return; }
        String q = v.substring(1).toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String[]>> hits = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<String, String[]> e : commandList().entrySet()) {
            String n = e.getKey().toLowerCase(Locale.ROOT);
            if (n.startsWith(q)) hits.add(e);
            else if (n.contains(q)) rest.add(e);
        }
        hits.addAll(rest);
        if (hits.isEmpty()) { hideMenu(); return; }
        slashMenu.removeAllViews();
        for (Map.Entry<String, String[]> e : hits) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackground(Ui.press(this, null, dp(10)));
            TextView n = Ui.text(this, "/" + e.getKey(), 13.5f, R.color.accentPress);
            n.setTypeface(Ui.MONO);
            row.addView(n);
            TextView d = Ui.text(this, e.getValue()[1], 13.5f, R.color.text2);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(dp(10), 0, 0, 0);
            row.addView(d, Ui.weight());
            String name = e.getKey();
            boolean args = !e.getValue()[0].isEmpty();
            boolean local = "local".equals(e.getValue()[2]);
            row.setOnClickListener(x -> {
                String value = "/" + name + (args ? " " : "");
                input.setText(value);
                input.setSelection(value.length());
                hideMenu();
                if (!args && local) send();
            });
            slashMenu.addView(row, Ui.fill());
        }
        slashScroll.setVisibility(View.VISIBLE);
        slashScroll.scrollTo(0, 0);
    }

    private void hideMenu() { slashScroll.setVisibility(View.GONE); }

    // ================= attachments =================

    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, PICK_FILES);
        } catch (android.content.ActivityNotFoundException e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Chat chat = current;
        for (Uri uri : uris.subList(0, Math.min(8, uris.size()))) {
            work.execute(() -> {
                Chat.Attachment a = readAttachment(uri);
                ui.post(() -> {
                    if (a == null) return;
                    chat.attachments.add(a);
                    updateChrome();
                });
            });
        }
    }

    private Chat.Attachment readAttachment(Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // name stays "file"
        }
        String type = getContentResolver().getType(uri);
        try {
            if (type != null && type.matches("image/(png|jpe?g|gif|webp|bmp|heic|heif)")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1568) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o); }
                if (bmp == null) { ui.post(() -> toast("这张图片读不了")); return null; }
                float scale = Math.min(1f, 1568f / Math.max(bmp.getWidth(), bmp.getHeight()));
                Bitmap out = Bitmap.createBitmap(Math.max(1, Math.round(bmp.getWidth() * scale)),
                        Math.max(1, Math.round(bmp.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(out);
                canvas.drawColor(Color.WHITE);
                canvas.scale(scale, scale);
                canvas.drawBitmap(bmp, 0, 0, null);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, 86, jpeg);
                Bitmap thumb = Bitmap.createScaledBitmap(out, dp(64), Math.max(1, dp(64) * out.getHeight() / out.getWidth()), true);
                return new Chat.Attachment(true, name, "image/jpeg", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP), jpeg.size(), thumb);
            }
            if (size > 25L * 1024 * 1024) { ui.post(() -> toast("文件超过 25MB")); return null; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    bytes.write(buf, 0, n);
                    if (bytes.size() > 25 * 1024 * 1024) { ui.post(() -> toast("文件超过 25MB")); return null; }
                }
            }
            return new Chat.Attachment(false, name, type, Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP), bytes.size(), null);
        } catch (Exception e) {
            ui.post(() -> toast("这个文件读不了"));
            return null;
        }
    }

    private void renderAttachments() {
        List<Chat.Attachment> items = current.attachments;
        attachScroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        String key = items.isEmpty() ? "" : System.identityHashCode(items) + ":" + items.size() + ":" + System.identityHashCode(items.get(items.size() - 1));
        if (key.equals(attachments.getTag())) return;
        attachments.setTag(key);
        attachments.removeAllViews();
        for (Chat.Attachment a : new ArrayList<>(items)) {
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(0, dp(7), dp(7), 0);
            if (a.image) {
                ImageView img = new ImageView(this);
                img.setImageBitmap(a.thumb);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                img.setClipToOutline(true);
                cell.addView(img, new FrameLayout.LayoutParams(dp(64), dp(64)));
            } else {
                LinearLayout f = Ui.column(this);
                f.setGravity(Gravity.CENTER_VERTICAL);
                f.setPadding(dp(10), dp(8), dp(10), dp(8));
                f.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                f.addView(Ui.icon(this, R.drawable.ic_file, 16, R.color.text2));
                TextView n = Ui.text(this, a.name, 12.5f, R.color.text);
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                n.setPadding(0, dp(4), 0, dp(2));
                f.addView(n);
                f.addView(Ui.text(this, Ui.size(a.size), 11, R.color.text3));
                cell.addView(f, new FrameLayout.LayoutParams(dp(150), dp(64)));
            }
            ImageView remove = Ui.icon(this, R.drawable.ic_x, 22, R.color.bg);
            remove.setPadding(dp(5), dp(5), dp(5), dp(5));
            remove.setBackground(Ui.round(col(R.color.text), dp(11), dp(2), col(R.color.surface)));
            remove.setOnClickListener(v -> { current.attachments.remove(a); attachments.setTag(null); updateChrome(); });
            cell.addView(remove, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END | Gravity.TOP));
            ((FrameLayout.LayoutParams) remove.getLayoutParams()).setMargins(0, -dp(7), -dp(7), 0);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            attachments.addView(cell);
            Ui.margins(cell, this, 0, 0, 4, 0);
        }
    }

    // ================= sheets =================

    private Dialog sheet(String titleText, String sub) {
        Dialog d = new Dialog(this, R.style.Sheet);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.column(this);
        android.graphics.drawable.GradientDrawable bg = Ui.round(col(R.color.bg), 0);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        View grab = new View(this);
        grab.setBackground(Ui.round(col(R.color.borderStrong), dp(2)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        box.addView(grab, gp);
        TextView t = Ui.text(this, titleText, 21, R.color.text);
        t.setTypeface(Ui.SERIF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(this, sub, 14, R.color.text2);
            s.setLineSpacing(0, 1.25f);
            s.setPadding(0, dp(8), 0, 0);
            box.addView(s);
        }
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (getResources().getDisplayMetrics().heightPixels * 0.62f), MeasureSpec.AT_MOST));
            }
        };
        LinearLayout content = Ui.column(this);
        content.setPadding(0, dp(12), 0, 0);
        scroll.addView(content);
        box.addView(scroll, Ui.fill());
        box.setTag(content);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    private LinearLayout content(Dialog d) {
        ViewGroup root = d.findViewById(android.R.id.content);
        return (LinearLayout) root.getChildAt(0).getTag();
    }

    private LinearLayout card(Dialog d) {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        content(d).addView(card, Ui.fill());
        return card;
    }

    /** A row: icon, label, and its value on the right. */
    private void option(LinearLayout card, int iconRes, String label, String value, boolean danger, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setMinimumHeight(dp(50));
        row.setBackground(Ui.press(this, null, dp(12)));
        if (iconRes != 0) {
            row.addView(Ui.icon(this, iconRes, 19, danger ? R.color.danger : R.color.text2));
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(14), 1));
        }
        TextView l = Ui.text(this, label, 15.5f, danger ? R.color.danger : R.color.text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(this, value, 14, R.color.text3);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            v.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.45f));
            v.setPadding(dp(12), 0, 0, 0);
            row.addView(v);
        }
        row.setOnClickListener(v -> click.run());
        card.addView(row, Ui.fill());
    }

    private void check(LinearLayout card) {
        LinearLayout last = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
        View tick = Ui.icon(this, R.drawable.ic_check, 19, R.color.accent);
        last.addView(tick);
        Ui.margins(tick, this, 12, 0, 0, 0);
    }

    private String tilde(String p) {
        if (p == null) return "";
        return !home.isEmpty() && p.startsWith(home) ? "~" + p.substring(home.length()) : p;
    }

    private void folderSheet(String path) {
        String at = path != null ? path : !cwd.isEmpty() ? cwd : defaultCwd;
        bridge.get("/dirs?path=" + Uri.encode(at), (status, d) -> {
            if (status != 200) { toast("读取文件夹失败"); return; }
            String here = d.optString("path");
            Dialog sheet = sheet("工作文件夹", tilde(here));
            LinearLayout card = card(sheet);
            String parent = d.isNull("parent") ? "" : d.optString("parent");
            if (!parent.isEmpty()) option(card, R.drawable.ic_up, "上一级", null, false, () -> { sheet.dismiss(); folderSheet(parent); });
            JSONArray dirs = d.optJSONArray("dirs");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                String name = dirs.optString(i);
                option(card, R.drawable.ic_folder, name, null, false, () -> { sheet.dismiss(); folderSheet(here + "/" + name); });
            }
            if (dirs == null || dirs.length() == 0) {
                TextView none = Ui.text(this, "没有子文件夹", 14, R.color.text3);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, dp(18), 0, dp(18));
                card.addView(none, Ui.fill());
            }
            LinearLayout field = Ui.row(this);
            EditText name = new EditText(this);
            name.setHint("新建文件夹名");
            name.setSingleLine(true);
            name.setTextSize(15);
            name.setTextColor(col(R.color.text));
            name.setHintTextColor(col(R.color.text3));
            name.setPadding(dp(12), 0, dp(12), 0);
            name.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
            field.addView(name, new LinearLayout.LayoutParams(0, dp(40), 1));
            TextView make = button("新建", false, v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty() || n.contains("/")) { toast("文件夹名不能为空，也不能带 /"); return; }
                bridge.post("/mkdir", obj("path", here + "/" + n), (s2, r2) -> {
                    if (s2 == 200) { sheet.dismiss(); folderSheet(here + "/" + n); } else toast("新建失败");
                });
            });
            field.addView(make);
            Ui.margins(make, this, 8, 0, 0, 0);
            content(sheet).addView(field, Ui.fill());
            Ui.margins(field, this, 0, 10, 0, 0);
            LinearLayout actions = Ui.row(this);
            TextView cancel = button("取消", false, v -> sheet.dismiss());
            TextView use = button("使用这个文件夹", true, v -> {
                cwd = here;
                prefs.edit().putString("cwd", cwd).apply();
                if (current.sessionId == null) current.cwd = cwd;
                sheet.dismiss();
                renderNow();
                refreshSettings();
            });
            use.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
            use.setTextColor(col(R.color.bg));
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
            actions.addView(use, new LinearLayout.LayoutParams(0, dp(42), 1));
            Ui.margins(use, this, 10, 0, 0, 0);
            content(sheet).addView(actions, Ui.fill());
            Ui.margins(actions, this, 0, 14, 0, 0);
            sheet.show();
        });
    }

    /** The thinking level in use: picked here, else Claude Code's own setting. */
    private String effortInUse() {
        return !effort.isEmpty() ? effort : settingsEnv().optString("CLAUDE_CODE_EFFORT_LEVEL").toLowerCase(Locale.ROOT);
    }

    private void modelSheet() {
        Dialog sheet = sheet("模型", null);
        // Thinking: one segmented bar.
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.setBackground(Ui.round(col(R.color.surface2), dp(12)));
        String level = effortInUse();
        for (String e : EFFORTS) {
            boolean on = e.equals(level);
            TextView b = Ui.text(this, e, 13.5f, on ? R.color.text : R.color.text2);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            if (on) {
                b.setTypeface(Ui.MEDIUM);
                b.setBackground(Ui.round(col(R.color.bg), dp(9), dp(1), col(R.color.border)));
            }
            b.setOnClickListener(v -> {
                effort = e;
                prefs.edit().putString("effort", effort).apply();
                sheet.dismiss();
                updateChrome();
            });
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
        content(sheet).addView(bar, Ui.fill());
        Ui.margins(bar, this, 0, 0, 0, 12);
        // Models: Claude Code's, then ones typed here; each once.
        List<String> all = new ArrayList<>();
        if (!defaultModel.isEmpty()) all.add(defaultModel);
        for (String m : models) if (!all.contains(m)) all.add(m);
        for (String m : usedModels()) if (!all.contains(m)) all.add(m);
        String inUse = model.isEmpty() ? defaultModel : model;
        LinearLayout card = card(sheet);
        for (String m : all) {
            boolean big = m.toLowerCase(Locale.ROOT).endsWith("[1m]");
            option(card, 0, shortModel(m), big ? "1M" : null, false, () -> {
                model = m;
                prefs.edit().putString("model", model).apply();
                sheet.dismiss();
                updateChrome();
            });
            if (m.equals(inUse)) check(card);
        }
        option(card, R.drawable.ic_plus, "其他模型", null, false, () -> {
            sheet.dismiss();
            textSheet("其他模型", "", "模型名", false, (t, done) -> {
                String name = t.trim();
                if (name.isEmpty()) return;
                List<String> used = usedModels();
                used.remove(name);
                used.add(0, name);
                prefs.edit().putString("usedModels", new JSONArray(used.subList(0, Math.min(8, used.size()))).toString()).apply();
                model = name;
                prefs.edit().putString("model", model).apply();
                updateChrome();
                done.run();
            }, null);
        });
        sheet.show();
    }

    private List<String> usedModels() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("usedModels", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (org.json.JSONException ignored) { /* none */ }
        return out;
    }

    private static final String[][] PERMISSION_MODES = {
            {"bypassPermissions", "不询问", "所有操作直接执行"},
            {"acceptEdits", "自动接受编辑", "改文件直接执行，其他需要确认的操作会被拒绝"},
            {"plan", "只做计划", "只读和分析，不改任何东西"},
            {"default", "默认", "需要确认的操作都会被拒绝（这里没法确认）"},
    };

    private Dialog settingsPage;
    private LinearLayout settingsBox;
    /** The launch options last read from the bridge, shown while they are read again. */
    private JSONObject launchCache;

    /** Opens Settings, a full-screen page (or refreshes it when open). */
    private void settingsSheet() {
        if (settingsPage == null) {
            Dialog d = new Dialog(this, R.style.Page);
            LinearLayout root = Ui.column(this);
            root.setBackgroundColor(col(R.color.bg));
            LinearLayout top = Ui.row(this);
            top.setPadding(dp(6), 0, dp(6), 0);
            ImageView back = Ui.iconButton(this, R.drawable.ic_back, 40, 22, R.color.text);
            back.setOnClickListener(v -> d.dismiss());
            top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView t = Ui.text(this, "设置", 18, R.color.text);
            t.setTypeface(Ui.SERIF);
            t.setPadding(dp(6), 0, 0, 0);
            top.addView(t);
            root.addView(top, new LinearLayout.LayoutParams(-1, dp(48)));
            ScrollView scroll = new ScrollView(this);
            settingsBox = Ui.column(this);
            settingsBox.setPadding(dp(16), dp(4), dp(16), dp(28));
            scroll.addView(settingsBox);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            d.setContentView(root);
            if (d.getWindow() != null) d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.setOnDismissListener(x -> settingsPage = null);
            settingsPage = d;
            fillSettings(linked ? launchCache : null);
            d.show();
        }
        refreshSettings();
    }

    /** Reads the options again and redraws Settings, if open. */
    private void refreshSettings() {
        if (settingsPage == null) return;
        if (!linked) { fillSettings(null); return; }
        // Claude Code's version is found a moment after the bridge starts.
        bridge.get("/config", (st, config) -> {
            if (st == 200) claudeVersion = config.optString("claudeVersion");
            bridge.get("/launch", (status, launch) -> {
                if (status == 200) launchCache = launch;
                fillSettings(status == 200 ? launch : null);
            });
        });
    }

    private LinearLayout card(LinearLayout box) {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        box.addView(card, Ui.fill());
        return card;
    }

    /** Settings' contents; the launch options only with the bridge running (null launch). */
    private void fillSettings(JSONObject launch) {
        if (settingsPage == null) return;
        LinearLayout box = settingsBox;
        box.removeAllViews();

        // Claude Code: its state, restart, log.
        LinearLayout run = card(box);
        LinearLayout status = Ui.row(this);
        status.setPadding(dp(14), 0, dp(6), 0);
        status.setMinimumHeight(dp(54));
        View dot = new View(this);
        dot.setBackground(Ui.round(col(linked ? R.color.ok : installing || connecting ? R.color.text3 : R.color.danger), dp(4)));
        status.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView name = Ui.text(this, "Claude Code", 16, R.color.text);
        name.setTypeface(Ui.MEDIUM);
        name.setPadding(dp(12), 0, dp(8), 0);
        status.addView(name);
        status.addView(Ui.text(this, claudeVersion, 13, R.color.text3), new LinearLayout.LayoutParams(0, -2, 1));
        TextView restart = Ui.text(this, "重启", 14.5f, R.color.accentPress);
        restart.setPadding(dp(10), dp(8), dp(10), dp(8));
        restart.setBackground(Ui.press(this, null, dp(8)));
        restart.setOnClickListener(v -> {
            toast("正在重启…");
            linked = false;
            connect(true);
            fillSettings(null);
        });
        status.addView(restart);
        run.addView(status, Ui.fill());
        option(run, R.drawable.ic_list, "日志", null, false, this::logSheet);

        section(box, "模型");
        LinearLayout api = card(box);
        String url = settingsEnv().optString("ANTHROPIC_BASE_URL");
        String host = url.isEmpty() ? "Anthropic" : Uri.parse(url).getHost() != null ? Uri.parse(url).getHost() : url;
        option(api, R.drawable.ic_link, "API", host, false, this::apiSheet);
        option(api, R.drawable.ic_bot, "登录 Claude 账号", null, false, () -> openTerminal("claude\r"));

        section(box, "对话");
        LinearLayout talk = card(box);
        String folderNow = cwd.isEmpty() ? defaultCwd : cwd;
        option(talk, R.drawable.ic_folder, "工作文件夹", folderNow.isEmpty() ? "~/work" : tilde(folderNow), false, () -> {
            if (!linked) { toast("Claude Code 还没启动好"); return; }
            folderSheet(null);
        });
        if (launch != null) {
            String mode = launch.optString("permissionMode", "bypassPermissions");
            String modeLabel = mode;
            for (String[] m : PERMISSION_MODES) if (m[0].equals(mode)) modeLabel = m[1];
            String prompt = launch.optString("appendSystemPrompt");
            String extra = launch.optString("extraArgs");
            option(talk, R.drawable.ic_tool, "权限", modeLabel, false, () -> permissionSheet(mode));
            option(talk, R.drawable.ic_spark, "系统提示", oneLine(prompt), false,
                    () -> textSheet("系统提示", prompt, "", true, (t, done) -> saveLaunch("appendSystemPrompt", t, done), this::refreshSettings));
            option(talk, R.drawable.ic_edit_lines, "启动参数", extra.trim(), false,
                    () -> textSheet("启动参数", extra, "--max-turns 50", false, (t, done) -> saveLaunch("extraArgs", t, done), this::refreshSettings));
            String folder = current.cwd == null || current.cwd.isEmpty() ? folderNow : current.cwd;
            option(talk, R.drawable.ic_book, "CLAUDE.md", "全局", false, () -> memorySheet("global", null, "CLAUDE.md"));
            option(talk, R.drawable.ic_book, "CLAUDE.md", Ui.base(folder), false,
                    () -> memorySheet("folder", folder, "CLAUDE.md · " + Ui.base(folder)));
        }

        section(box, "扩展");
        LinearLayout ext = card(box);
        int skills = skillNames().size();
        option(ext, R.drawable.ic_spark, "技能", skills == 0 ? null : String.valueOf(skills), false, this::skillsSheet);
        if (launch != null) {
            int off = disallowed(launch).size();
            option(ext, R.drawable.ic_tool, "工具", off == 0 ? null : "关闭 " + off, false, () -> toolsSheet(launch));
        }
        int servers = mcpServers().length();
        option(ext, R.drawable.ic_globe, "MCP", servers == 0 ? null : String.valueOf(servers), false, this::mcpSheet);

        // Background: only what still needs doing, and the keep-alive command.
        section(box, "后台");
        LinearLayout keep = card(box);
        if (!hasStorage()) option(keep, R.drawable.ic_folder, "允许访问存储", null, false, this::requestStorage);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            option(keep, R.drawable.ic_check, "关闭电池优化", null, false, () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    toast("系统不支持这个设置");
                }
            });
        }
        option(keep, R.drawable.ic_gear, "应用信息", null, false, () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                toast("打不开应用信息");
            }
        });
        // Android 12+ kills an app's child processes beyond a limit, or busy ones in the background.
        String rish = "rish -c 'dumpsys deviceidle whitelist +" + getPackageName()
                + "; cmd appops set " + getPackageName() + " RUN_ANY_IN_BACKGROUND allow"
                + "; settings put global settings_enable_monitor_phantom_procs false"
                + "; device_config put activity_manager max_phantom_processes 2147483647'";
        option(keep, R.drawable.ic_copy, "复制保活命令", "rish", false, () -> copy(rish));

        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { /* none */ }
        TextView v = Ui.text(this, "v" + version, 12, R.color.text3);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(18), 0, 0);
        box.addView(v, Ui.fill());
    }

    // ---- extensions: skills, tools, MCP servers ----

    private java.io.File skillsDir() { return userland.file(Userland.HOME + "/.claude/skills"); }

    private List<String> skillNames() {
        List<String> out = new ArrayList<>();
        java.io.File[] dirs = skillsDir().listFiles(java.io.File::isDirectory);
        if (dirs != null) for (java.io.File d : dirs) if (new java.io.File(d, "SKILL.md").isFile()) out.add(d.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private String readFile(java.io.File f) {
        try {
            return Userland.read(new java.io.FileInputStream(f));
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** A change Claude Code reads when it starts: idle conversations restart with it. */
    private void relaunch() {
        if (linked) bridge.post("/launch", new JSONObject(), null);
    }

    private void skillsSheet() {
        Dialog sheet = sheet("技能", null);
        LinearLayout card = card(sheet);
        List<String> names = skillNames();
        for (String n : names) {
            option(card, R.drawable.ic_spark, n, null, false, () -> { sheet.dismiss(); skillActions(n); });
        }
        if (names.isEmpty()) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "新建", null, false, () -> {
            sheet.dismiss();
            textSheet("新建技能", "", "名称：小写字母、数字和 -", false, (t, done) -> {
                String n = t.trim();
                if (!n.matches("[a-z0-9][a-z0-9-]*")) { toast("名称只能用小写字母、数字和 -"); return; }
                java.io.File f = new java.io.File(skillsDir(), n + "/SKILL.md");
                if (f.exists()) { toast("已经有这个技能了"); return; }
                try {
                    Userland.write(f, "---\nname: " + n + "\ndescription: 一句话写清楚什么时候该用这个技能\n---\n\n在这里写给 Claude 的做法和步骤。\n");
                } catch (java.io.IOException e) {
                    toast("新建失败：" + e.getMessage());
                    return;
                }
                done.run();
                editSkill(n);
            }, null);
        });
        sheet.show();
    }

    private void skillActions(String n) {
        Dialog sheet = sheet(n, null);
        LinearLayout card = card(sheet);
        option(card, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); editSkill(n); });
        option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            deleteTree(new java.io.File(skillsDir(), n));
            relaunch();
            toast("已删除");
            refreshSettings();
            skillsSheet();
        });
        sheet.show();
    }

    private void editSkill(String n) {
        java.io.File f = new java.io.File(skillsDir(), n + "/SKILL.md");
        textSheet("技能 · " + n, readFile(f), "SKILL.md", true, (t, done) -> {
            try {
                Userland.write(f, t);
            } catch (java.io.IOException e) {
                toast("保存失败：" + e.getMessage());
                return;
            }
            relaunch();
            toast("已保存");
            done.run();
            refreshSettings();
        }, this::skillsSheet);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] items = f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (items != null) for (java.io.File i : items) deleteTree(i);
        f.delete();
    }

    /** The tools Claude Code reported when a conversation last started, and MCP servers' state. */
    private JSONArray savedArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static List<String> disallowed(JSONObject launch) {
        List<String> out = new ArrayList<>();
        for (String t : launch.optString("disallowedTools").replace(',', ' ').trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private void toolsSheet(JSONObject launch) {
        Dialog sheet = sheet("工具", null);
        LinearLayout card = card(sheet);
        JSONArray tools = savedArray("tools");
        List<String> off = disallowed(launch);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) all.add(tools.optString(i));
        for (String t : off) if (!all.contains(t)) all.add(t);
        for (String t : all) {
            boolean on = !off.contains(t);
            String sub = t.startsWith("mcp__") ? t.split("__")[1] : null;
            String label = t.startsWith("mcp__") && t.split("__").length > 2 ? t.substring(t.indexOf("__", 5) + 2) : t;
            option(card, 0, label, sub, false, () -> {
                List<String> next = new ArrayList<>(off);
                if (on) next.add(t); else next.remove(t);
                saveLaunch("disallowedTools", TextUtils.join(" ", next), () -> {
                    sheet.dismiss();
                    refreshSettings();
                    bridge.get("/launch", (status, l) -> { if (status == 200) { launchCache = l; toolsSheet(l); } });
                });
            });
            if (on) check(card);
        }
        if (all.isEmpty()) {
            TextView none = Ui.text(this, "发一条消息后显示", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(12), dp(18), dp(12), dp(18));
            card.addView(none, Ui.fill());
        }
        sheet.show();
    }

    private java.io.File mcpFile() { return userland.file(Userland.HOME + "/.ccapp/mcp.json"); }

    /** The MCP servers added here, by name. */
    private JSONObject mcpServers() {
        try {
            JSONObject s = new JSONObject(readFile(mcpFile())).optJSONObject("mcpServers");
            return s != null ? s : new JSONObject();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private boolean saveMcp(JSONObject servers) {
        try {
            Userland.write(mcpFile(), new JSONObject().put("mcpServers", servers).toString(2) + "\n");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
        relaunch();
        refreshSettings();
        return true;
    }

    private void mcpSheet() {
        Dialog sheet = sheet("MCP", null);
        LinearLayout card = card(sheet);
        // Their state, from when a conversation last started.
        Map<String, String> state = new HashMap<>();
        JSONArray st = savedArray("mcpServers");
        for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o != null) state.put(o.optString("name"), o.optString("status"));
        }
        JSONObject servers = mcpServers();
        List<String> names = new ArrayList<>();
        java.util.Iterator<String> it = servers.keys();
        while (it.hasNext()) names.add(it.next());
        for (String n : names) {
            String s = state.get(n);
            option(card, R.drawable.ic_globe, n, s == null ? null : mcpState(s), false, () -> { sheet.dismiss(); mcpActions(n); });
        }
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (servers.has(e.getKey())) continue;
            option(card, R.drawable.ic_globe, e.getKey(), mcpState(e.getValue()), false, () -> toast("用 claude mcp 管理"));
        }
        if (names.isEmpty() && state.isEmpty()) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "添加", null, false, () -> {
            sheet.dismiss();
            textSheet("添加 MCP", "", "{\n  \"名称\": {\n    \"command\": \"npx\",\n    \"args\": [\"-y\", \"包名\"]\n  }\n}", true, (t, done) -> {
                JSONObject in;
                try {
                    in = new JSONObject(t.trim());
                } catch (JSONException e) {
                    toast("不是有效的 JSON：" + e.getMessage());
                    return;
                }
                // Docs give {"mcpServers": {...}}, or just the servers.
                JSONObject add2 = in.optJSONObject("mcpServers") != null ? in.optJSONObject("mcpServers") : in;
                JSONObject all = mcpServers();
                java.util.Iterator<String> keys = add2.keys();
                int n = 0;
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (add2.optJSONObject(k) == null) { toast(k + " 的配置不是一个对象"); return; }
                    try { all.put(k, add2.optJSONObject(k)); } catch (JSONException ignored) { /* string key */ }
                    n++;
                }
                if (n == 0) { toast("没有找到服务器配置"); return; }
                if (saveMcp(all)) { toast("已添加"); done.run(); }
            }, this::mcpSheet);
        });
        sheet.show();
    }

    private static String mcpState(String s) {
        switch (s) {
            case "connected": return "已连接";
            case "failed": return "连接失败";
            case "pending": return "连接中";
            case "needs-auth": return "需要授权";
            default: return s;
        }
    }

    private void mcpActions(String n) {
        Dialog sheet = sheet(n, null);
        LinearLayout card = card(sheet);
        option(card, R.drawable.ic_pencil, "编辑", null, false, () -> {
            sheet.dismiss();
            String text;
            try { text = mcpServers().optJSONObject(n).toString(2); } catch (Exception e) { text = "{}"; }
            textSheet("MCP · " + n, text, "{ }", true, (t, done) -> {
                JSONObject cfg;
                try { cfg = new JSONObject(t.trim()); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
                JSONObject all = mcpServers();
                try { all.put(n, cfg); } catch (JSONException ignored) { /* string key */ }
                if (saveMcp(all)) { toast("已保存"); done.run(); }
            }, this::mcpSheet);
        });
        option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            JSONObject all = mcpServers();
            all.remove(n);
            if (saveMcp(all)) toast("已删除");
            mcpSheet();
        });
        sheet.show();
    }

    // ---- Claude Code's settings.json, read and written here directly ----

    private void openTerminal(String input) {
        if (!userland.ready()) { toast("运行环境还没准备好"); return; }
        Intent i = new Intent(this, TerminalActivity.class);
        if (input != null) i.putExtra(TerminalActivity.EXTRA_INPUT, input);
        startActivity(i);
    }

    private java.io.File settingsFile() { return userland.file(Userland.HOME + "/.claude/settings.json"); }

    private JSONObject settingsJson() {
        try {
            return new JSONObject(Userland.read(new java.io.FileInputStream(settingsFile())));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject settingsEnv() {
        JSONObject env = settingsJson().optJSONObject("env");
        return env != null ? env : new JSONObject();
    }

    /** Writes settings.json; running conversations pick it up on their next message. */
    private boolean saveSettings(JSONObject json) {
        try {
            Userland.write(settingsFile(), json.toString(2) + "\n");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
        if (linked) {
            bridge.post("/launch", new JSONObject(), null); // restarts idle conversations with it
            bridge.get("/config", (status, data) -> {
                if (status != 200) return;
                models.clear();
                JSONArray m = data.optJSONArray("models");
                for (int i = 0; m != null && i < m.length(); i++) models.add(m.optString(i));
                defaultModel = data.optString("defaultModel");
                authSet = data.optBoolean("auth", true);
                claudeVersion = data.optString("claudeVersion");
                renderNow();
            });
        }
        return true;
    }

    private void apiSheet() {
        Dialog sheet = sheet("API", null);
        JSONObject env = settingsEnv();
        String key = env.optString("ANTHROPIC_AUTH_TOKEN", env.optString("ANTHROPIC_API_KEY"));
        EditText url = field(sheet, "地址", env.optString("ANTHROPIC_BASE_URL"), "https://api.anthropic.com");
        EditText token = field(sheet, "API Key", key, "sk-…");
        EditText name = field(sheet, "模型", env.optString("ANTHROPIC_MODEL"), "");
        LinearLayout actions = Ui.row(this);
        TextView raw = button("settings.json", false, v -> {
            sheet.dismiss();
            String text;
            try { text = settingsJson().toString(2); } catch (JSONException e) { text = "{}"; }
            textSheet("settings.json", text, "{ }", true, (t, done) -> {
                JSONObject json;
                try { json = new JSONObject(t.trim().isEmpty() ? "{}" : t); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
                if (saveSettings(json)) { toast("已保存"); done.run(); }
            }, this::refreshSettings);
        });
        TextView ok = button("保存", true, v -> {
            JSONObject json = settingsJson();
            JSONObject e = json.optJSONObject("env");
            if (e == null) e = new JSONObject();
            put(e, "ANTHROPIC_BASE_URL", url.getText().toString().trim());
            String k = token.getText().toString().trim();
            // One key variable: an auth token works with every provider (and Anthropic's own).
            e.remove("ANTHROPIC_API_KEY");
            put(e, "ANTHROPIC_AUTH_TOKEN", k);
            put(e, "ANTHROPIC_MODEL", name.getText().toString().trim());
            try { json.put("env", e); } catch (JSONException ignored) { /* string key */ }
            if (saveSettings(json)) { toast("已保存"); sheet.dismiss(); refreshSettings(); }
        });
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(raw, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private static void put(JSONObject o, String key, String value) {
        if (value.isEmpty()) { o.remove(key); return; }
        try { o.put(key, value); } catch (JSONException ignored) { /* string key */ }
    }

    private EditText field(Dialog sheet, String label, String value, String hint) {
        TextView l = Ui.text(this, label, 13, R.color.text2);
        l.setPadding(dp(2), dp(8), 0, dp(6));
        content(sheet).addView(l);
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setTextSize(14.5f);
        f.setTypeface(Ui.MONO);
        f.setTextColor(col(R.color.text));
        f.setHintTextColor(col(R.color.text3));
        f.setPadding(dp(12), 0, dp(12), 0);
        f.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        content(sheet).addView(f, new LinearLayout.LayoutParams(-1, dp(44)));
        return f;
    }

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private void importSheet() {
        String command = "mkdir -p /sdcard/Download/ccapp && cp -r ~/.claude ~/work /sdcard/Download/ccapp/";
        Dialog sheet = sheet("从 Termux 导入", "在 Termux 里运行这行，再点导入");
        TextView code = Ui.text(this, command, 12.5f, R.color.text);
        code.setTypeface(Ui.MONO);
        code.setTextIsSelectable(true);
        code.setPadding(dp(11), dp(9), dp(11), dp(9));
        code.setBackground(Ui.round(col(R.color.codeBg), dp(10), dp(1), col(R.color.border)));
        content(sheet).addView(code, Ui.fill());
        LinearLayout actions = Ui.row(this);
        TextView cp = button("复制命令", false, v -> copy(command));
        TextView go = button("导入", true, v -> {
            sheet.dismiss();
            if (hasStorage()) importFromTermux();
            else { afterStorage = this::importFromTermux; requestStorage(); }
        });
        go.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        go.setTextColor(col(R.color.bg));
        actions.addView(cp, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(go, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(go, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        sheet.show();
    }

    /** Copies what Termux put in Download/ccapp into /root, moving Termux's home paths to /root. */
    private void importFromTermux() {
        java.io.File from = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download/ccapp");
        if (!new java.io.File(from, ".claude").isDirectory() && !new java.io.File(from, "work").isDirectory()) {
            toast("没找到 Download/ccapp，先在 Termux 里运行那行命令");
            return;
        }
        toast("正在导入…");
        work.execute(() -> {
            int[] count = {0};
            String error = null;
            try {
                java.io.File home = userland.file(Userland.HOME);
                copyTree(new java.io.File(from, ".claude"), new java.io.File(home, ".claude"), count);
                copyTree(new java.io.File(from, "work"), new java.io.File(home, "work"), count);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String err = error;
            ui.post(() -> {
                if (err != null) { toast("导入出错：" + err); return; }
                toast("已导入 " + count[0] + " 个文件");
                refreshSettings();
                if (linked) {
                    bridge.post("/launch", new JSONObject(), null);
                    afterLink();
                }
            });
        });
    }

    private static final java.util.Set<String> SKIP_IMPORT = new java.util.HashSet<>(java.util.Arrays.asList(
            "downloads", "local", "shell-snapshots", "ide", "statsig", "debug", "cache", "telemetry"));

    private void copyTree(java.io.File from, java.io.File to, int[] count) throws java.io.IOException {
        if (!from.exists()) return;
        if (from.isDirectory()) {
            if (SKIP_IMPORT.contains(from.getName()) && ".claude".equals(from.getParentFile().getName())) return;
            java.io.File[] items = from.listFiles();
            if (items == null) return;
            for (java.io.File f : items) {
                // Claude Code keeps a project's sessions under its folder's path with / as -.
                String name = f.getName().replace(TERMUX_HOME.replace('/', '-').replace('.', '-'), Userland.HOME.replace('/', '-'));
                copyTree(f, new java.io.File(to, name), count);
            }
            return;
        }
        if (!to.getParentFile().isDirectory() && !to.getParentFile().mkdirs()) throw new java.io.IOException("mkdir " + to.getParent());
        String n = from.getName();
        if (n.endsWith(".jsonl") || n.endsWith(".json") || n.endsWith(".md")) {
            String text = Userland.read(new java.io.FileInputStream(from));
            Userland.write(to, text.replace(TERMUX_HOME, Userland.HOME));
        } else {
            try (InputStream in = new java.io.FileInputStream(from); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
                Userland.copy(in, out);
            }
        }
        count[0]++;
    }

    private void logSheet() {
        String bridgeLog = Userland.tail(userland.file(Userland.HOME + "/.ccapp/bridge.log"), 16_000);
        String runLog = Userland.tail(userland.log, 8_000);
        String text = "== bridge.log ==\n" + (bridgeLog.isEmpty() ? "（空）\n" : bridgeLog) + "\n== 启动 ==\n" + (runLog.isEmpty() ? "（空）\n" : runLog);
        Dialog sheet = sheet("日志", null);
        TextView t = Ui.text(this, text, 11.5f, R.color.text);
        t.setTypeface(Ui.MONO);
        t.setTextIsSelectable(true);
        content(sheet).addView(t, Ui.fill());
        TextView cp = button("复制全部", false, v -> copy(text));
        content(sheet).addView(cp);
        Ui.margins(cp, this, 0, 12, 0, 0);
        sheet.show();
        ScrollView scroll = (ScrollView) content(sheet).getParent();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String oneLine(String text) {
        String t = text.trim().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private void saveLaunch(String key, String value, Runnable done) {
        bridge.post("/launch", obj(key, value), (status, data) -> {
            if (status == 200) {
                toast("已保存");
                done.run();
            } else {
                toast(status == 400 ? "参数有误：" + data.optString("error") : "保存失败");
            }
        });
    }

    private void permissionSheet(String mode) {
        Dialog sheet = sheet("权限模式", null);
        LinearLayout card = card(sheet);
        for (String[] m : PERMISSION_MODES) {
            option(card, 0, m[1], null, false, () -> saveLaunch("permissionMode", m[0], () -> {
                sheet.dismiss();
                refreshSettings();
            }));
            if (m[0].equals(mode)) check(card);
        }
        sheet.show();
    }

    private void memorySheet(String scope, String folder, String title) {
        String q = "/memory?scope=" + scope + (folder == null ? "" : "&cwd=" + Uri.encode(folder));
        bridge.get(q, (status, data) -> {
            if (status != 200) { toast("读取 CLAUDE.md 失败"); return; }
            textSheet(title, data.optString("text"), "", true, (t, done) -> {
                JSONObject body = obj("scope", scope, "text", t);
                if (folder != null) body = obj("scope", scope, "cwd", folder, "text", t);
                bridge.post("/memory", body, (s2, r2) -> {
                    if (s2 == 200) { toast("已保存"); done.run(); } else toast("保存失败");
                });
            }, this::refreshSettings);
        });
    }

    private void textSheet(String title, String value, String hint, boolean multiLine,
                           java.util.function.BiConsumer<String, Runnable> save, Runnable back) {
        Dialog sheet = sheet(title, null);
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(col(R.color.text));
        field.setHintTextColor(col(R.color.text3));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        if (multiLine) {
            field.setMinLines(6);
            field.setMaxLines(14);
            field.setTextSize(14);
        } else {
            field.setSingleLine(true);
            field.setTypeface(Ui.MONO);
        }
        content(sheet).addView(field, Ui.fill());
        LinearLayout actions = Ui.row(this);
        TextView cancel = button("取消", false, v -> { sheet.dismiss(); if (back != null) back.run(); });
        TextView ok = button("保存", true, v -> save.accept(field.getText().toString(), () -> {
            sheet.dismiss();
            if (back != null) back.run();
        }));
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
        field.requestFocus();
    }

    private void section(LinearLayout box, String label) {
        TextView t = Ui.text(this, label, 12.5f, R.color.text3);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(6));
        box.addView(t);
    }

    // ================= misc =================

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Claude Code", text));
        toast("已复制");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(url);
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, 0);
    }
}

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
import androidx.recyclerview.widget.SimpleItemAnimator;

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

/** Claude Code front end: native interface for Claude Code running in Termux behind the bridge. */
public final class MainActivity extends Activity implements Bridge.Events {
    private static final int PICK_FILES = 1;
    private static final int ASK_PERMISSION = 2;
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max"};
    private static final String[] EFFORT_NAMES = {"低", "中", "高", "超高", "最高"};

    private Bridge bridge;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Md md;

    // state
    private boolean linked, everLinked, connecting;
    private String setup; // why the bridge cannot be reached, or null
    private String home = "", defaultCwd = "", cwd, model, effort, defaultModel = "";
    private final List<String> models = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private JSONArray sessions = new JSONArray();
    private final Map<String, Chat> chats = new HashMap<>();
    private final Map<String, String> bySession = new HashMap<>();
    private Chat current;
    private boolean stick = true;
    private String search = "";

    // views
    private DrawerFrame frame;
    private TextView title, banner, linkText;
    private View linkDot;
    private RecyclerView list;
    private ThreadAdapter adapter;
    private ScrollView emptyView;
    private ImageView toBottom, sendBtn;
    private EditText input;
    private LinearLayout attachments, slashMenu, sessionsBox;
    private HorizontalScrollView attachScroll;
    private ScrollView slashScroll;
    private TextView folderName, modelName;
    private LinearLayout folderChip;
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
            ui.postDelayed(this, 120);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (!linked && !connecting && setup == null) connect(false);
    }

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
        ImageView menu = Ui.iconButton(this, R.drawable.ic_sidebar, 42, 22, R.color.text2);
        menu.setOnClickListener(v -> frame.open());
        top.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(42)));
        title = Ui.text(this, "新对话", 15.5f, R.color.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setOnClickListener(v -> scrollToEnd(true));
        top.addView(title, Ui.weight());
        ImageView compose = Ui.iconButton(this, R.drawable.ic_compose, 42, 22, R.color.text2);
        compose.setOnClickListener(v -> switchTo(newChat()));
        top.addView(compose, new LinearLayout.LayoutParams(dp(42), dp(42)));
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(46)));

        banner = Ui.text(this, "与 Termux 的连接断开了，正在重连…", 13.5f, R.color.danger);
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
        ((SimpleItemAnimator) list.getItemAnimator()).setSupportsChangeAnimations(false);
        adapter = new ThreadAdapter(this, md, this::copy, this::render);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && dy == 0) return;
                stick = !rv.canScrollVertically(1);
                toBottom.setVisibility(stick ? View.GONE : View.VISIBLE);
            }
        });
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (stick && b != ob) scrollToEnd(false);
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
        wrap.setPadding(dp(10), dp(4), dp(10), dp(10));
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.round(col(R.color.surface), dp(24), dp(1), col(R.color.border)));
        card.setElevation(dp(2));
        card.setPadding(dp(8), dp(6), dp(8), dp(8));

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
        attachments.setPadding(dp(4), dp(2), dp(4), dp(6));
        attachScroll.addView(attachments);
        attachScroll.setVisibility(View.GONE);
        card.addView(attachScroll, Ui.fill());

        input = new EditText(this);
        input.setBackground(null);
        input.setHint("有什么可以帮你的？");
        input.setHintTextColor(col(R.color.text3));
        input.setTextColor(col(R.color.text));
        input.setTextSize(16);
        input.setPadding(dp(8), dp(8), dp(8), dp(6));
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
        card.addView(input, Ui.fill());

        LinearLayout row = Ui.row(this);
        ImageView attach = Ui.iconButton(this, R.drawable.ic_plus, 34, 18, R.color.text2);
        attach.setBackground(Ui.press(this, Ui.round(0, dp(17), dp(1), col(R.color.border)), dp(17)));
        attach.setOnClickListener(v -> pickFiles());
        row.addView(attach, new LinearLayout.LayoutParams(dp(34), dp(34)));
        folderChip = chip(R.drawable.ic_folder, true);
        folderName = (TextView) folderChip.getChildAt(1);
        folderChip.setOnClickListener(v -> {
            if (!linked) { toast("还没连上 Termux"); return; }
            if (current.sessionId != null) { toast("已有对话的文件夹不能更改，新对话可以选"); return; }
            folderSheet(null);
        });
        row.addView(folderChip);
        Ui.margins(folderChip, this, 6, 0, 0, 0);
        LinearLayout modelChip = chip(R.drawable.ic_chevron_down, false);
        modelName = (TextView) modelChip.getChildAt(0);
        modelChip.setOnClickListener(v -> modelSheet());
        row.addView(modelChip);
        Ui.margins(modelChip, this, 6, 0, 0, 0);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        sendBtn = new ImageView(this);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setOnClickListener(v -> send());
        row.addView(sendBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        card.addView(row, Ui.fill());
        wrap.addView(card, Ui.fill());
        return wrap;
    }

    private LinearLayout chip(int iconRes, boolean iconFirst) {
        LinearLayout chip = Ui.row(this);
        chip.setPadding(dp(11), 0, dp(11), 0);
        chip.setBackground(Ui.press(this, Ui.round(0, dp(17), dp(1), col(R.color.border)), dp(17)));
        ImageView icon = Ui.icon(this, iconRes, 15, R.color.text2);
        TextView label = Ui.text(this, "", 13.5f, R.color.text2);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(118));
        if (iconFirst) {
            chip.addView(icon);
            label.setPadding(dp(6), 0, 0, 0);
            chip.addView(label);
        } else {
            chip.addView(label);
            label.setPadding(0, 0, dp(4), 0);
            chip.addView(icon);
        }
        chip.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(34)));
        return chip;
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
            @Override public void afterTextChanged(Editable s) { search = s.toString(); renderSessions(); }
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
            int count = adapter.getItemCount();
            if (count == 0) return;
            if (smooth) list.smoothScrollToPosition(count - 1);
            else ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(count - 1, -100000);
        });
    }

    private void updateChrome() {
        title.setText(current.title.isEmpty() ? "新对话" : current.title);
        String folder = current.cwd == null || current.cwd.isEmpty() ? (cwd.isEmpty() ? defaultCwd : cwd) : current.cwd;
        folderName.setText(folder.isEmpty() ? "~" : Ui.base(folder));
        folderChip.setAlpha(current.sessionId != null ? 0.7f : 1f);
        modelName.setText(shortModel(model.isEmpty() ? defaultModel : model));
        boolean canSend = linked && (input.getText().toString().trim().length() > 0 || !current.attachments.isEmpty());
        if (current.running) {
            sendBtn.setImageResource(R.drawable.ic_stop);
            sendBtn.setImageTintList(android.content.res.ColorStateList.valueOf(col(R.color.bg)));
            sendBtn.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(18)), dp(18)));
            sendBtn.setAlpha(1f);
            sendBtn.setEnabled(true);
        } else {
            sendBtn.setImageResource(R.drawable.ic_arrow_up);
            sendBtn.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            sendBtn.setBackground(Ui.press(this, Ui.round(col(R.color.accent), dp(18)), dp(18)));
            sendBtn.setAlpha(canSend ? 1f : 0.35f);
            sendBtn.setEnabled(canSend);
        }
        sendBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        renderAttachments();
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
            sub.append("正在连接 Termux 里的 Claude Code…");
        }
        TextView p = Ui.text(this, sub, 15, R.color.text2);
        p.setGravity(Gravity.CENTER);
        box.addView(p, Ui.fill());
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
        TextView h = Ui.text(this, "连接 Termux", 26, R.color.text);
        h.setTypeface(Ui.SERIF);
        h.setPadding(0, dp(16), 0, dp(8));
        box.addView(h);
        String why;
        switch (setup) {
            case "termux": why = "没有找到 Termux。"; break;
            case "permission": why = "还没有授权在 Termux 中运行命令。"; break;
            case "timeout": why = "Termux 没有响应：可能还没允许其他 App 调用，或者没装 Python。"; break;
            default: why = "无法启动 Termux 的命令服务。";
        }
        TextView p = Ui.text(this, "Claude Code 在 Termux 里运行，这个 App 是它的界面。" + why, 15, R.color.text2);
        p.setLineSpacing(0, 1.25f);
        p.setPadding(0, 0, 0, dp(12));
        box.addView(p);
        boolean termux = bridge.hasTermux(), perm = bridge.hasPermission();
        String prop = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings";
        step(box, 1, termux, "安装 Termux 和 Claude Code", "需要 F-Droid 或 GitHub 版的 Termux，并已装好 Claude Code（能运行 claude）和 Python：", "pkg install -y python", null);
        step(box, 2, termux && !"timeout".equals(setup) && !"start".equals(setup), "允许其他 App 调用 Termux", "在 Termux 里运行一次：", prop,
                button("打开 Termux", false, v -> openTermux()));
        step(box, 3, perm, "授权本 App 在 Termux 中运行命令", "用来在后台启动 Claude Code 的桥接程序。", null,
                perm ? null : button("授权", true, v -> requestPermissions(new String[]{Bridge.RUN_COMMAND_PERMISSION}, ASK_PERMISSION)));
        TextView retry = button("重新连接", true, v -> connect(true));
        retry.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        retry.setTextColor(col(R.color.bg));
        box.addView(retry);
        Ui.margins(retry, this, 0, 18, 0, 0);
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

    private void step(LinearLayout box, int n, boolean done, String titleText, String text, String cmd, View action) {
        if (n > 1) {
            View line = new View(this);
            line.setBackgroundColor(col(R.color.border));
            box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(14), 0, dp(14));
        TextView no = Ui.text(this, done ? "✓" : String.valueOf(n), 13, done ? R.color.ok : R.color.accentPress);
        no.setGravity(Gravity.CENTER);
        no.setTypeface(Typeface.DEFAULT_BOLD);
        no.setBackground(Ui.round(done ? 0x1F2F8A5B : col(R.color.accentSoft), dp(13)));
        row.addView(no, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(12), dp(2), 0, 0);
        TextView t = Ui.text(this, titleText, 15, R.color.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(t);
        TextView s = Ui.text(this, text, 14, R.color.text2);
        s.setPadding(0, dp(4), 0, 0);
        s.setLineSpacing(0, 1.2f);
        body.addView(s);
        if (cmd != null) {
            LinearLayout box2 = Ui.row(this);
            box2.setBackground(Ui.round(col(R.color.codeBg), dp(10), dp(1), col(R.color.border)));
            TextView code = Ui.text(this, cmd, 12.5f, R.color.text);
            code.setTypeface(Ui.MONO);
            code.setPadding(dp(11), dp(9), dp(11), dp(9));
            code.setLineSpacing(0, 1.2f);
            code.setTextIsSelectable(true);
            box2.addView(code, Ui.weight());
            TextView cp = Ui.text(this, "复制", 13, R.color.text2);
            cp.setPadding(dp(12), dp(9), dp(12), dp(9));
            cp.setBackground(Ui.press(this, null, dp(10)));
            cp.setOnClickListener(v -> copy(cmd));
            box2.addView(cp);
            body.addView(box2, Ui.fill());
            Ui.margins(box2, this, 0, 8, 0, 0);
        }
        if (action != null) {
            body.addView(action);
            Ui.margins(action, this, 0, 10, 0, 0);
        }
        row.addView(body, Ui.weight());
        box.addView(row, Ui.fill());
    }

    // ================= sessions =================

    private void loadSessions() {
        if (!linked) { renderSessions(); return; }
        bridge.get("/sessions", (status, data) -> {
            if (status == 200 && data.optJSONArray("sessions") != null) sessions = data.optJSONArray("sessions");
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
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            String titleText = s.optString("title"), folder = s.optString("cwd"), id = s.optString("id");
            if (!q.isEmpty() && !titleText.toLowerCase(Locale.ROOT).contains(q) && !folder.toLowerCase(Locale.ROOT).contains(q)) continue;
            String g = group(s.optDouble("mtime"));
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
        if (chat.loaded) return;
        chat.loaded = true;
        Chat target = chat;
        bridge.get("/history?id=" + Uri.encode(id), (status, data) -> {
            if (status != 200) { toast("读取对话失败"); return; }
            target.load(data);
            if (!data.optString("cwd").isEmpty()) target.cwd = data.optString("cwd");
            if (current == target) { adapter.show(target, true); stick = true; renderNow(); }
        });
    }

    private void sessionActions(String id, String titleText, String folder) {
        Dialog sheet = sheet(titleText, tilde(folder));
        LinearLayout card = card(sheet);
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
                JSONArray cmds = data.optJSONArray("slash_commands");
                if (cmds != null && cmds.length() > 0) {
                    commands.clear();
                    for (int i = 0; i < cmds.length(); i++) if (!cmds.optString(i).startsWith("_")) commands.add(cmds.optString(i));
                    prefs.edit().putString("commands", new JSONArray(commands).toString()).apply();
                }
            }
            if (chat.apply(data)) loadSessions();
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
                String err = data.optString("stderr").trim();
                b.text = err.isEmpty() ? "退出码 " + data.optInt("code") : err;
                m.blocks.add(b);
                m.done = true;
                m.touch();
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
        }
    }

    private void connect(boolean restart) {
        if (connecting) return;
        connecting = true;
        updateLink();
        bridge.connect(restart, result -> {
            connecting = false;
            if ("ok".equals(result)) {
                setup = null;
                afterLink();
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
            updateChrome();
        });
        renderNow();
        loadSessions();
    }

    private void updateLink() {
        if (linkDot == null) return;
        int dot = linked ? R.color.ok : connecting ? R.color.text3 : R.color.danger;
        linkDot.setBackground(Ui.round(col(dot), dp(4)));
        linkText.setText(linked ? "已连接 Termux" : connecting ? "正在连接…" : "未连接 · 点此重试");
        banner.setVisibility(!linked && !connecting && setup == null && everLinked ? View.VISIBLE : View.GONE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != ASK_PERMISSION) return;
        if (bridge.hasPermission()) connect(true);
        else { toast("没有授权，无法启动 Claude Code"); renderNow(); }
    }

    // ================= sending =================

    private void send() {
        Chat chat = current;
        if (chat.running) { stop(); return; }
        String text = input.getText().toString().replaceAll("\\s+$", "");
        if (text.trim().isEmpty() && chat.attachments.isEmpty()) return;
        if (text.matches("^/\\S[^\\n]*$") && localCommand(text)) {
            input.setText("");
            hideMenu();
            updateChrome();
            return;
        }
        if (!linked) { toast("还没连上 Termux"); return; }
        List<Chat.Attachment> attached = new ArrayList<>(chat.attachments);
        chat.attachments.clear();
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
        chat.startedAt = System.currentTimeMillis();
        chat.verb = chat.randomVerb();
        chat.detail = "";
        input.setText("");
        hideMenu();
        hideKeyboard();
        stick = true;
        renderNow();
        upload(chat, attached, 0, new ArrayList<>(), text);
    }

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
            if (status == -1 && attempt < 2) {
                ui.postDelayed(() -> sendWithRetry(chat, body, sentAt, attempt + 1), 1200);
                return;
            }
            if (chat.lastEvent > sentAt) return; // it is running after all
            fail(chat, "发送失败", status == -1 ? "连不上 Termux 里的桥接程序。" : data.optString("error", String.valueOf(status)));
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

    private void stop() {
        current.stopping = true;
        bridge.post("/stop", obj("chat", current.key), null);
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
                if (current.running) stop(); else toast("现在没有在运行");
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

    private void option(LinearLayout card, int iconRes, String label, String sub, boolean danger, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(50));
        row.setBackground(Ui.press(this, null, dp(12)));
        if (iconRes != 0) row.addView(Ui.icon(this, iconRes, 19, danger ? R.color.danger : R.color.text2));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(iconRes != 0 ? dp(12) : 0, 0, 0, 0);
        texts.addView(Ui.text(this, label, 15.5f, danger ? R.color.danger : R.color.text));
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(this, sub, 12.5f, R.color.text3);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, Ui.weight());
        row.setOnClickListener(v -> click.run());
        card.addView(row, Ui.fill());
    }

    private void check(LinearLayout card) {
        LinearLayout last = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
        last.addView(Ui.icon(this, R.drawable.ic_check, 19, R.color.accent));
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
            Dialog sheet = sheet("选择工作文件夹", "新对话会在这个文件夹里运行 Claude Code。\n" + tilde(here));
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

    private void modelSheet() {
        Dialog sheet = sheet("模型", "对新消息生效；“默认”用 Claude Code 设置里的模型。");
        LinearLayout card = card(sheet);
        List<String> all = new ArrayList<>();
        all.add("");
        all.addAll(models);
        for (String m : all) {
            option(card, 0, m.isEmpty() ? "默认" : shortModel(m), m.isEmpty() ? (defaultModel.isEmpty() ? "settings.json" : defaultModel) : m, false, () -> {
                model = m;
                prefs.edit().putString("model", model).apply();
                sheet.dismiss();
                updateChrome();
            });
            if (m.equals(model)) check(card);
        }
        sheet.show();
    }

    private void settingsSheet() {
        Dialog sheet = sheet("设置", null);
        LinearLayout box = content(sheet);
        section(box, "连接");
        LinearLayout card = card(sheet);
        kv(card, "Termux 桥接", linked ? "已连接" : "未连接");
        kv(card, "默认文件夹", tilde(cwd.isEmpty() ? defaultCwd : cwd));
        kv(card, "模型", model.isEmpty() ? (defaultModel.isEmpty() ? "默认" : defaultModel) : model);
        kv(card, "思考级别", effortLabel(effort));
        LinearLayout actions = Ui.row(this);
        TextView restart = button("重启桥接", false, v -> {
            sheet.dismiss();
            toast("正在重启桥接…");
            linked = false;
            connect(true);
        });
        actions.addView(restart);
        TextView termux = button("打开 Termux", false, v -> openTermux());
        actions.addView(termux);
        Ui.margins(termux, this, 8, 0, 0, 0);
        box.addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 10, 0, 0);
        section(box, "说明");
        LinearLayout info = card(sheet);
        kv(info, "权限模式", "不询问（bypass）");
        kv(info, "发送", "键盘回车或按钮，发完收起键盘");
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { /* none */ }
        kv(info, "版本", version);
        sheet.show();
    }

    private void section(LinearLayout box, String label) {
        TextView t = Ui.text(this, label, 12.5f, R.color.text3);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(6));
        box.addView(t);
    }

    private void kv(LinearLayout card, String k, String v) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(Ui.text(this, k, 14.5f, R.color.text));
        TextView b = Ui.text(this, v, 14.5f, R.color.text2);
        b.setGravity(Gravity.END);
        b.setPadding(dp(12), 0, 0, 0);
        row.addView(b, Ui.weight());
        card.addView(row, Ui.fill());
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

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Bridge.TERMUX);
        if (launch != null) startActivity(launch); else toast("没有找到 Termux");
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

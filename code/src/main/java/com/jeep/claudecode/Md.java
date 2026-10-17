package com.jeep.claudecode;

import android.content.Context;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.linkify.LinkifyPlugin;

/** Markdown in native views: prose through Markwon, fenced code as cards with a copy button. */
final class Md {
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*).*$");

    private final Context c;
    private final Markwon markwon;
    private final Consumer<String> copy;
    private final Consumer<String> openLink;

    Md(Context c, Consumer<String> copy, Consumer<String> openLink) {
        this.c = c;
        this.copy = copy;
        this.openLink = openLink;
        TableTheme table = TableTheme.buildWithDefaults(c)
                .tableBorderColor(Ui.color(c, R.color.border))
                .tableBorderWidth(Ui.dp(c, 1))
                .tableCellPadding(Ui.dp(c, 8))
                .tableHeaderRowBackgroundColor(Ui.color(c, R.color.surface2))
                .tableEvenRowBackgroundColor(Ui.color(c, R.color.bg))
                .tableOddRowBackgroundColor(Ui.color(c, R.color.bg))
                .build();
        markwon = Markwon.builder(c)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(table))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override public void configureTheme(MarkwonTheme.Builder b) {
                        b.codeTextColor(Ui.color(c, R.color.inlineCode))
                                .codeBackgroundColor(Ui.color(c, R.color.surface2))
                                .codeTypeface(Ui.MONO)
                                .codeTextSize(Ui.dp(c, 14))
                                .codeBlockTextColor(Ui.color(c, R.color.text))
                                .codeBlockBackgroundColor(Ui.color(c, R.color.codeBg))
                                .linkColor(Ui.color(c, R.color.accentPress))
                                .blockQuoteColor(Ui.color(c, R.color.borderStrong))
                                .blockMargin(Ui.dp(c, 18))
                                .bulletWidth(Ui.dp(c, 5))
                                .listItemColor(Ui.color(c, R.color.text2))
                                .headingBreakHeight(0)
                                .headingTypeface(Ui.SERIF)
                                .headingTextSizeMultipliers(new float[]{1.4f, 1.25f, 1.12f, 1f, 1f, 1f})
                                .thematicBreakColor(Ui.color(c, R.color.border));
                    }
                })
                .build();
    }

    /** Text and code segments of a reply; an unclosed fence (still streaming) is code. */
    static List<String[]> split(String text) {
        List<String[]> out = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = FENCE.matcher(lines[i]);
            if (!m.matches()) {
                prose.append(lines[i]).append('\n');
                continue;
            }
            String fence = m.group(1);
            String lang = m.group(2);
            StringBuilder code = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length; j++) {
                if (lines[j].trim().startsWith(fence) && lines[j].trim().replace(fence.substring(0, 1), "").isEmpty()) break;
                if (code.length() > 0) code.append('\n');
                code.append(lines[j]);
            }
            if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
            prose.setLength(0);
            out.add(new String[]{"code", code.toString(), lang});
            i = j;
        }
        if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
        return out;
    }

    /**
     * Fills the container with views for the reply text, reusing the views already there (a reply
     * being streamed keeps its views, their widths and the reader's place).
     */
    void render(LinearLayout box, String text) {
        List<String[]> parts = split(text);
        for (int i = 0; i < parts.size(); i++) {
            String[] p = parts.get(i);
            View old = i < box.getChildCount() ? box.getChildAt(i) : null;
            if (old != null && p[0].equals(old.getTag())) {
                if ("code".equals(p[0])) updateCode(old, p[1], p[2]);
                else setProse((TextView) old, p[1]);
                continue;
            }
            while (box.getChildCount() > i) box.removeViewAt(i);
            View v = "code".equals(p[0]) ? code(p[1], p[2]) : prose(p[1]);
            if (i > 0) Ui.margins(v, c, 0, 14, 0, 0);
            box.addView(v);
        }
        while (box.getChildCount() > parts.size()) box.removeViewAt(parts.size());
    }

    TextView prose(String markdown) {
        TextView t = new TextView(c);
        t.setTag("text");
        t.setLayoutParams(Ui.fill());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        t.setTextColor(Ui.color(c, R.color.text));
        t.setTypeface(Ui.SERIF);
        t.setLineSpacing(0, 1.3f);
        t.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        selectable(t);
        setProse(t, markdown);
        return t;
    }

    private void setProse(TextView t, String markdown) {
        String md = markdown.trim();
        if (md.equals(t.getTag(R.id.markdown))) return;
        t.setTag(R.id.markdown, md);
        markwon.setMarkdown(t, md);
        if (t.getWidth() == 0 && md.contains("|")) {
            // Tables size their columns from the text's width: render again once that is known.
            t.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int top, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l == 0) return;
                    t.removeOnLayoutChangeListener(this);
                    t.post(() -> markwon.setMarkdown(t, (String) t.getTag(R.id.markdown)));
                }
            });
        }
    }

    private void updateCode(View card, String code, String lang) {
        TextView name = card.findViewWithTag("lang");
        TextView body = card.findViewWithTag("body");
        name.setText(lang.isEmpty() ? "代码" : lang);
        if (!code.contentEquals(body.getText())) body.setText(code);
        card.setTag(R.id.markdown, code);
    }

    private View code(String code, String lang) {
        LinearLayout card = Ui.column(c);
        card.setTag("code");
        card.setTag(R.id.markdown, code);
        card.setLayoutParams(Ui.fill());
        card.setBackground(Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        card.setClipToOutline(true);
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0);
        head.setMinimumHeight(Ui.dp(c, 34));
        TextView name = Ui.text(c, lang.isEmpty() ? "代码" : lang, 12.5f, R.color.text3);
        name.setTag("lang");
        head.addView(name, Ui.weight());
        LinearLayout copyBtn = Ui.row(c);
        copyBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 8), Ui.dp(c, 5));
        copyBtn.setBackground(Ui.press(c, null, Ui.dp(c, 7)));
        copyBtn.addView(Ui.icon(c, R.drawable.ic_copy, 14, R.color.text2));
        TextView label = Ui.text(c, "复制", 12.5f, R.color.text2);
        label.setPadding(Ui.dp(c, 5), 0, 0, 0);
        copyBtn.addView(label);
        copyBtn.setOnClickListener(v -> copy.accept((String) card.getTag(R.id.markdown)));
        head.addView(copyBtn);
        card.addView(head, Ui.fill());
        View line = new View(c);
        line.setBackgroundColor(Ui.color(c, R.color.border));
        card.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(c, 1)));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        TextView body = Ui.text(c, code, 13, R.color.text);
        body.setTag("body");
        body.setTypeface(Ui.MONO);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        body.setHorizontallyScrolling(true);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        card.addView(scroll, Ui.fill());
        return card;
    }

    /** Selectable text whose links still open with a tap. */
    void selectable(TextView t) {
        t.setTextIsSelectable(true);
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_UP || !(t.getText() instanceof Spanned)) return false;
            if (t.hasSelection() || e.getEventTime() - e.getDownTime() > 350) return false;
            Layout layout = t.getLayout();
            if (layout == null) return false;
            int x = (int) e.getX() - t.getTotalPaddingLeft() + t.getScrollX();
            int y = (int) e.getY() - t.getTotalPaddingTop() + t.getScrollY();
            int line = layout.getLineForVertical(y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false;
            int off = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] links = ((Spanned) t.getText()).getSpans(off, off, ClickableSpan.class);
            if (links.length == 0) return false;
            if (links[0] instanceof android.text.style.URLSpan) openLink.accept(((android.text.style.URLSpan) links[0]).getURL());
            else links[0].onClick(t);
            return true;
        });
    }
}

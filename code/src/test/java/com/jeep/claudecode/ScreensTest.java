package com.jeep.claudecode;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Renders the main screens to PNG files (SHOTS directory) for a visual check. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w392dp-h850dp-xxhdpi")
public class ScreensTest {
    private static final File OUT = new File(System.getProperty("shots", "build/shots"));

    private static void shot(View v, String name) throws Exception {
        OUT.mkdirs();
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream f = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private static void dump(View v, int depth) {
        if (depth > 7) return;
        System.out.println("DUMP " + "  ".repeat(depth) + v.getClass().getSimpleName() + " " + v.getWidth() + "x" + v.getHeight()
                + " vis=" + v.getVisibility() + (v instanceof android.widget.TextView ? " '" + ((android.widget.TextView) v).getText() + "'" : ""));
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1);
        }
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object get(Object o, String field) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void call(Object o, String method) throws Exception {
        Method m = o.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static void idle() {
        for (int i = 0; i < 30; i++) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static JSONObject ev(String chat, String kind, String data) throws Exception {
        return new JSONObject().put("chat", chat).put("kind", kind).put("data", data == null ? JSONObject.NULL : new JSONObject(data));
    }

    private static String se(String event) { return "{\"type\":\"stream_event\",\"event\":" + event + "}"; }

    @Test public void screens() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        idle();
        shot(root, "01-setup");


        set(a, "linked", true);
        set(a, "setup", null);
        set(a, "home", "/data/data/com.termux/files/home");
        set(a, "defaultCwd", "/data/data/com.termux/files/home/work");
        set(a, "defaultModel", "deepseek-flash[1m]");
        Chat chat = (Chat) get(a, "current");
        chat.cwd = "/data/data/com.termux/files/home/work";
        call(a, "renderNow");
        idle();
        shot(root, "02-hero");

        // A conversation, through the same events the bridge sends.
        Chat.Msg user = new Chat.Msg(Chat.Msg.USER);
        user.text = "帮我看看 work 里有什么，再写个统计行数的脚本";
        chat.messages.add(user);
        chat.title = "帮我看看 work 里有什么";
        chat.running = true;
        chat.startedAt = System.currentTimeMillis() - 7000;
        String k = chat.key;
        List<JSONObject> evs = new ArrayList<>();
        evs.add(ev(k, "cc", "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"deepseek-flash\",\"slash_commands\":[\"compact\",\"context\",\"cost\",\"init\",\"simplify\",\"debug\"]}"));
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":1200,\"cache_read_input_tokens\":18000}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"用户想看目录内容，然后写脚本。先 ls。\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_stop\",\"index\":0}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"先看一下文件夹里的内容。\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls -la\\\",\\\"description\\\":\\\"列出 work 文件夹\\\"}\"}}")));
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"total 12\\ndrwx------ 3 u0_a1 u0_a1 4096 .\\n-rw------- 1 u0_a1 u0_a1  210 notes.txt\\n-rw------- 1 u0_a1 u0_a1 1830 pelican-bike.html\"}]}}"));
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"usage\":{\"input_tokens\":900,\"cache_read_input_tokens\":19800}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Write\",\"input\":{}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file_path\\\":\\\"/data/data/com.termux/files/home/work/count.py\\\",\\\"content\\\":\\\"import sys\\\"}\"}}")));
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"File created\"}]}}"));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")));
        String md = "文件夹里有 **2 个文件**：\\n\\n| 文件 | 大小 |\\n|---|---|\\n| notes.txt | 210 B |\\n| pelican-bike.html | 1.8 KB |\\n\\n统计行数的脚本已经写好，保存为 `count.py`：\\n\\n```python\\nimport sys\\n\\nfor path in sys.argv[1:]:\\n    with open(path) as f:\\n        print(path, sum(1 for _ in f))\\n```\\n\\n运行：`python count.py *.txt`。";
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + md + "\"}}")));
        a.events(evs);
        idle();
        shot(root, "03-running");

        List<JSONObject> end = new ArrayList<>();
        end.add(ev(k, "cc", "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\",\"usage\":{\"output_tokens\":406}}"));
        a.events(end);
        idle();
        shot(root, "04-done");

        // Expand the tool and the thinking
        for (Chat.Msg m : chat.messages) for (Chat.Block b : m.blocks) if (b.kind != Chat.Block.TEXT) b.open = true;
        call(a, "renderNow");
        idle();
        RecyclerScroll.top(a);
        idle();
        shot(root, "05-expanded");

        // Drawer
        set(a, "sessions", new JSONArray("[{\"id\":\"s1\",\"title\":\"帮我看看 work 里有什么\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + (System.currentTimeMillis() / 1000) + "},"
                + "{\"id\":\"s2\",\"title\":\"写一个鹈鹕骑自行车的 SVG 动画\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 3600) + "},"
                + "{\"id\":\"s3\",\"title\":\"Xed-Editor 中文翻译\",\"cwd\":\"/data/data/com.termux/files/home/Xed-Editor\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 90000) + "},"
                + "{\"id\":\"s4\",\"title\":\"termux 里装 claude code\",\"cwd\":\"/data/data/com.termux/files/home\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 400000) + "}]"));
        call(a, "renderSessions");
        ((DrawerFrame) get(a, "frame")).open();
        idle();
        shot(root, "06-drawer");
        ((DrawerFrame) get(a, "frame")).close();
        idle();

        // Slash menu and a note
        EditText input = (EditText) get(a, "input");
        input.setText("/");
        idle();
        shot(root, "07-slash");
        input.setText("/effort");
        call(a, "send");
        idle();
        shot(root, "08-note");

        // Every tool's row
        Chat tools = new Chat("tools");
        Chat.Msg tm = new Chat.Msg(Chat.Msg.ASSISTANT);
        String[][] names = {{"Bash", "{\"command\":\"ls\",\"description\":\"List files\"}"}, {"Read", "{\"file_path\":\"/w/a.py\"}"},
                {"Write", "{\"file_path\":\"/w/b.py\"}"}, {"Edit", "{\"file_path\":\"/w/c.py\"}"}, {"MultiEdit", "{\"file_path\":\"/w/d.py\"}"},
                {"NotebookEdit", "{\"notebook_path\":\"/w/e.ipynb\"}"}, {"Glob", "{\"pattern\":\"**/*.py\"}"}, {"Grep", "{\"pattern\":\"TODO\"}"},
                {"WebFetch", "{\"url\":\"https://example.com\"}"}, {"WebSearch", "{\"query\":\"termux python\"}"},
                {"Task", "{\"description\":\"Explore repo\"}"}, {"TodoWrite", "{}"}, {"Skill", "{\"skill\":\"simplify\"}"}};
        for (String[] n : names) {
            Chat.Block b = new Chat.Block(Chat.Block.TOOL);
            b.name = n[0];
            b.input = new JSONObject(n[1]);
            tm.blocks.add(b);
        }
        tm.done = true;
        tools.messages.add(tm);
        Method sw = MainActivity.class.getDeclaredMethod("switchTo", Chat.class);
        sw.setAccessible(true);
        sw.invoke(a, tools);
        idle();
        shot(root, "10-tools");
        sw.invoke(a, chat);
        idle();

        call(a, "settingsSheet");
        idle();
        Dialog d = ShadowDialog.getLatestDialog();
        View sheet = d.getWindow().getDecorView();
        shot(sheet, "09-settings");
    }

    static final class RecyclerScroll {
        static void top(MainActivity a) throws Exception {
            androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) get(a, "list");
            list.scrollToPosition(0);
            set(a, "stick", false);
        }
    }
}

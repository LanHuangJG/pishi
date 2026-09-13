package com.pishi.hotfix;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 内存日志环（最近300条），供 App 内调试页展示 pishi 全链路状态。 */
public final class PishiDebugLog {
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<String>());
    public static void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + msg;
        synchronized (LINES) { LINES.add(line); if (LINES.size() > 300) LINES.remove(0); }
        Log.i("pishi", msg);
    }
    public static List<String> snapshot() { synchronized (LINES) { return new ArrayList<>(LINES); } }
    public static String text() { synchronized (LINES) { return String.join("\n", LINES); } }
}

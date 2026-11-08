package com.ps4lib.linksender;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText urlBox, titleBox;
    private TextView status;
    private LinearLayout listBox;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(root);

        TextView h = new TextView(this);
        h.setText("أرسل رابطًا إلى الـ PS4");
        h.setTextSize(22);
        root.addView(h);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        urlBox = new EditText(this);
        urlBox.setHint("الصق رابط التحميل المباشر هنا");
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setSingleLine(false);
        root.addView(urlBox);

        titleBox = new EditText(this);
        titleBox.setHint("الاسم الذي يظهر في الـ PS4 (اختياري)");
        root.addView(titleBox);

        Button paste = new Button(this);
        paste.setText("لصق من الحافظة");
        paste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    if (t != null) urlBox.setText(extractUrl(t.toString()));
                }
            }
        });
        root.addView(paste);

        Button add = new Button(this);
        add.setText("إضافة");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addLink(extractUrl(urlBox.getText().toString()), titleBox.getText().toString()); }
        });
        root.addView(add);

        TextView lh = new TextView(this);
        lh.setText("القائمة");
        lh.setTextSize(18);
        lh.setPadding(0, dp(16), 0, dp(4));
        root.addView(lh);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        TextView help = new TextView(this);
        help.setText("بعد الإضافة: على الـ PS4 افتح «مكتبة ألعاب PS4» ← الإعدادات ← عنوان الخادم، واكتب العنوان الظاهر أعلاه، ثم اضغط Options لإعادة التحميل، واختر العنصر وحمّله.\n"
                + "الهاتف والـ PS4 على نفس شبكة الواي فاي. اترك هذا التطبيق يعمل (الإشعار) أثناء التحميل. الملف لا يُخزَّن على الهاتف.\n"
                + "أضف فقط روابط ملفات تملك حق تحميلها.");
        help.setTextSize(13);
        help.setPadding(0, dp(16), 0, 0);
        root.addView(help);

        setContentView(sv);
        handleIntent(getIntent());
        startRelay();
        refresh();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startRelay();
        refresh();
    }

    private static String extractUrl(String s) {
        Matcher m = Pattern.compile("https?://\\S+").matcher(s == null ? "" : s);
        return m.find() ? m.group() : (s == null ? "" : s.trim());
    }

    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null) return;
        String url = extractUrl(t);
        urlBox.setText(url);
        addLink(url, "");     // مشاركة رابط من المتصفح تضيفه مباشرة
    }

    private void startRelay() {
        Intent s = new Intent(this, RelayService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        } catch (Exception e) {
            toast("تعذر تشغيل الخدمة: " + e.getMessage());
        }
    }

    private void toast(final String m) {
        ui.post(new Runnable() { public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); } });
    }

    private void addLink(final String url, final String title) {
        if (url.isEmpty()) { toast("الصق الرابط أولًا"); return; }
        toast("جارٍ فحص الرابط...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    LinkStore.Item it = RelayService.store(MainActivity.this).add(url, title);
                    toast("تمت الإضافة: " + it.title + (it.ranges ? "" : " (لا يدعم الاستئناف)"));
                    ui.post(new Runnable() { public void run() { urlBox.setText(""); titleBox.setText(""); } });
                } catch (Exception e) {
                    toast("تعذرت الإضافة: " + e.getMessage());
                }
                refresh();
            }
        }).start();
    }

    private void refresh() {
        ui.post(new Runnable() {
            public void run() {
                String ip = RelayServer.localIp();
                String st;
                if (!RelayService.lastError.isEmpty()) st = RelayService.lastError;
                else if (ip == null) st = "لا يوجد اتصال واي فاي. اتصل بنفس شبكة الـ PS4.";
                else st = "عنوان الخادم للـ PS4:\nhttp://" + ip + ":" + RelayService.PORT + "/catalog.json";
                status.setText(st);
                listBox.removeAllViews();
                List<LinkStore.Item> items = RelayService.store(MainActivity.this).list();
                if (items.isEmpty()) {
                    TextView e = new TextView(MainActivity.this);
                    e.setText("لا يوجد شيء بعد.");
                    listBox.addView(e);
                }
                for (final LinkStore.Item it : items) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView t = new TextView(MainActivity.this);
                    t.setText(it.title + "\n" + String.format("%.1f MB", it.size / 1048576.0));
                    row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    Button del = new Button(MainActivity.this);
                    del.setText("حذف");
                    del.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { RelayService.store(MainActivity.this).remove(it.id); refresh(); }
                    });
                    row.addView(del);
                    listBox.addView(row);
                }
            }
        });
    }
}

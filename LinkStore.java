package com.ps4lib.linksender;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** قائمة الروابط المضافة. التخزين نصي بسيط (سطر لكل رابط، حقول مفصولة بـ TAB). */
public class LinkStore {
    public static class Item {
        public String id, title, file, url, contentId = "";
        public long size, ts;
        public boolean ranges;
    }

    public interface Persist {
        String load();
        void save(String s);
    }

    private final List<Item> items = new ArrayList<Item>();
    private final Persist persist;

    public LinkStore(Persist p) {
        persist = p;
        String s = p == null ? null : p.load();
        if (s == null) return;
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            try {
                Item it = new Item();
                it.id = f[0]; it.title = f[1]; it.file = f[2]; it.size = Long.parseLong(f[3]);
                it.ranges = "1".equals(f[4]); it.ts = Long.parseLong(f[5]); it.url = f[6];
                it.contentId = f.length > 7 ? f[7] : "";
                items.add(it);
            } catch (NumberFormatException ignored) { }
        }
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    private void flush() {
        if (persist == null) return;
        StringBuilder sb = new StringBuilder();
        for (Item it : items)
            sb.append(clean(it.id)).append('\t').append(clean(it.title)).append('\t').append(clean(it.file)).append('\t')
              .append(it.size).append('\t').append(it.ranges ? "1" : "0").append('\t').append(it.ts).append('\t').append(clean(it.url)).append('\t').append(clean(it.contentId)).append('\n');
        persist.save(sb.toString());
    }

    public synchronized List<Item> list() { return new ArrayList<Item>(items); }

    public synchronized Item get(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) { items.remove(i); break; }
        flush();
    }

    static String sha1(String s) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new IOException(e); }
    }

    /** يقرأ Content ID من ترويسة PKG (أول 256 بايت). فارغ إن لم يكن PKG لـ PS4 أو تعذّرت القراءة. */
    static String readContentId(String url) {
        try {
            HttpURLConnection c = Origin.open(url, "bytes=0-255", null, false);
            try {
                if (c.getResponseCode() >= 400) return "";
                java.io.InputStream in = c.getInputStream();
                byte[] b = new byte[256];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                if (n < 0x64 || b[0] != 0x7F || b[1] != 'C' || b[2] != 'N' || b[3] != 'T') return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0x40; i < 0x40 + 36 && b[i] != 0; i++) {
                    char ch = (char) (b[i] & 0xFF);
                    if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) return "";
                    sb.append(ch);
                }
                return sb.toString();
            } finally { c.disconnect(); }
        } catch (Exception e) { return ""; }
    }

    /** يتحقق من الرابط (حجم، دعم الاستئناف، الاسم) ثم يضيفه. يرمي IOException برسالة مفهومة. */
    public Item add(String url, String title) throws IOException {
        url = url == null ? "" : url.trim();
        if (!url.matches("(?i)^https?://\\S+$")) throw new IOException("الرابط يجب أن يبدأ بـ http:// أو https://");
        HttpURLConnection c = Origin.open(url, "bytes=0-0", null, false);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("الموقع رفض الطلب (HTTP " + code + ")");
            String ct = c.getContentType();
            if (ct != null && ct.toLowerCase().contains("text/html"))
                throw new IOException("الرابط يفتح صفحة ويب لا ملفًا (يحتاج غالبًا تسجيل دخول أو صفحة وسيطة). استعمل رابط تحميل مباشر.");
            String cr = c.getHeaderField("Content-Range");
            long size;
            boolean ranges;
            if (code == 206 && cr != null && cr.matches("bytes 0-\\d+/\\d+")) {
                size = Long.parseLong(cr.substring(cr.indexOf('/') + 1));
                ranges = true;
            } else {
                long cl = c.getContentLengthLong();
                if (cl < 0) throw new IOException("الموقع لا يخبر عن حجم الملف، والتطبيق يحتاج الحجم.");
                size = cl;
                ranges = false;
            }
            String name = "";
            String cd = c.getHeaderField("Content-Disposition");
            if (cd != null) {
                Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd);
                if (m.find()) { try { name = URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception e) { name = m.group(1); } }
            }
            if (name.isEmpty()) {
                String path = new URL(c.getURL().toString()).getPath();
                name = path.substring(path.lastIndexOf('/') + 1);
                try { name = URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) { }
            }
            Item it = new Item();
            it.contentId = readContentId(url);
            it.id = sha1(url);
            it.url = url;
            it.size = size;
            it.ranges = ranges;
            it.ts = System.currentTimeMillis() / 1000;
            it.title = (title == null || title.trim().isEmpty()) ? (name.isEmpty() ? "ملف" : name) : title.trim();
            it.file = name.isEmpty() ? "file.pkg" : (name.toLowerCase().endsWith(".pkg") ? name : name + ".pkg");
            synchronized (this) {
                for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(it.id)) { items.remove(i); break; }
                items.add(it);
                flush();
            }
            return it;
        } finally {
            c.disconnect();
        }
    }
}

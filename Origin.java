package com.ps4lib.linksender;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** اتصال بالموقع الأصلي (HTTP أو HTTPS) مع تتبّع التحويلات يدويًا (يشمل التحويل من http إلى https). */
public final class Origin {
    private Origin() {}

    static final String UA = "Mozilla/5.0 (PS4LinkSender)";

    public static HttpURLConnection open(String url, String range, String ifRange, boolean head) throws IOException {
        String cur = url;
        for (int i = 0; i < 9; i++) {
            URL u = new URL(cur);
            String proto = u.getProtocol().toLowerCase();
            if (!proto.equals("http") && !proto.equals("https")) throw new IOException("بروتوكول غير مدعوم");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(head ? "HEAD" : "GET");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) {
                c.setRequestProperty("Range", range);
                if (ifRange != null) c.setRequestProperty("If-Range", ifRange);
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("تحويل بلا عنوان");
                cur = new URL(u, loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("تحويلات كثيرة");
    }
}

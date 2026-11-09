package com.ps4lib.linksender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** خادم HTTP صغير (HTTP/1.0) للـ PS4: يقدّم catalog.json ويمرّر التنزيل من الموقع الأصلي دفقًا مع Range. */
public class RelayServer {
    private final LinkStore store;
    private final int port;
    private ServerSocket ss;
    private volatile boolean running;

    public RelayServer(LinkStore store, int port) { this.store = store; this.port = port; }

    public int port() { return port; }
    public boolean isRunning() { return running; }

    public synchronized void start() throws IOException {
        if (running) return;
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(new Runnable() { public void run() { loop(); } }, "relay-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = ss.accept();
                Thread th = new Thread(new Runnable() { public void run() { handle(s); } }, "relay-conn");
                th.setDaemon(true);
                th.start();
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    public static String localIp() {
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                        if (best == null) best = a.getHostAddress();
                    }
                }
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(' ');
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String safeName(String f) {
        String s = f.replaceAll("[^A-Za-z0-9._-]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.length() > 48) s = s.substring(s.length() - 48);
        return s.isEmpty() ? "file.pkg" : s;
    }

    private static void sendSimple(OutputStream o, int code, String reason, String ctype, byte[] body, Map<String, String> extra, boolean head) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.0 " + code + " " + reason + "\r\n");
        sb.append("Content-Type: ").append(ctype).append("\r\nContent-Length: ").append(body.length).append("\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        o.write(sb.toString().getBytes("UTF-8"));
        if (!head) o.write(body);
        o.flush();
    }

    private static Map<String, String> readHead(InputStream in, String[] reqLine) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) >= 0) {
            bo.write(b);
            if (bo.size() > 16384) throw new IOException("head too large");
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) { state++; if (state == 4) break; }
            else state = 0;
        }
        String[] lines = bo.toString("ISO-8859-1").split("\r\n");
        if (lines.length == 0) throw new IOException("empty");
        reqLine[0] = lines[0];
        Map<String, String> h = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
        }
        return h;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            OutputStream o = s.getOutputStream();
            String[] rl = new String[1];
            Map<String, String> h = readHead(s.getInputStream(), rl);
            String[] p = rl[0].split(" ");
            if (p.length < 2) { sendSimple(o, 400, "Bad Request", "text/plain", "bad".getBytes(), null, false); return; }
            boolean head = p[0].equals("HEAD");
            if (!head && !p[0].equals("GET")) { sendSimple(o, 405, "Method Not Allowed", "text/plain", "no".getBytes(), null, false); return; }
            String path = p[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (path.equals("/") ) {
                sendSimple(o, 200, "OK", "text/plain; charset=utf-8", "PS4 Link Sender is running. catalog: /catalog.json".getBytes("UTF-8"), null, head);
            } else if (path.equals("/catalog.json")) {
                String host = h.get("host");
                String base = "http://" + (host != null ? host : "localhost:" + port);
                StringBuilder sb = new StringBuilder("{\"version\":1,\"games\":[");
                boolean first = true;
                for (LinkStore.Item it : store.list()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"id\":\"").append(esc(it.id)).append("\",\"title\":\"").append(esc(it.title))
                      .append("\",\"description\":\"\",\"version\":\"1.0\",\"size_bytes\":").append(it.size)
                      .append(",\"category\":\"Links\",\"added_ts\":").append(it.ts).append(",\"cover_url\":\"\",\"download_url\":\"")
                      .append(base).append("/dl/").append(esc(it.id)).append('/').append(esc(safeName(it.file))).append("\",\"sha256\":\"\",\"direct_url\":\"").append(esc(it.url))
                      .append("\",\"content_id\":\"").append(esc(it.contentId == null ? "" : it.contentId)).append("\"}");
                }
                sb.append("]}");
                sendSimple(o, 200, "OK", "application/json; charset=utf-8", sb.toString().getBytes("UTF-8"), null, head);
            } else if (path.startsWith("/dl/")) {
                String[] seg = path.substring(4).split("/");
                proxy(o, h, seg.length > 0 ? seg[0] : "", head);
            } else {
                sendSimple(o, 404, "Not Found", "text/plain", "not found".getBytes(), null, head);
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void proxy(OutputStream o, Map<String, String> h, String id, boolean head) throws IOException {
        LinkStore.Item it = store.get(id);
        if (it == null) { sendSimple(o, 404, "Not Found", "text/plain", "unknown id".getBytes(), null, head); return; }
        final String etag = "\"" + it.id + "-" + it.size + "\"";      // ETag ثابت وقوي نصنعه نحن: يضمن عمل الاستئناف حتى لو لم يوفره الموقع
        String range = it.ranges ? h.get("range") : null;
        String ifr = h.get("if-range");
        if (range != null && ifr != null && !ifr.equals(etag)) range = null;   // الملف اختلف عن نسخة العميل ⇒ نرسله كاملًا
        long start = 0;
        boolean openEnd = false;
        if (range != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^bytes=(\\d+)-$").matcher(range.trim());
            if (m.find()) { start = Long.parseLong(m.group(1)); openEnd = true; }
            if (openEnd && start >= it.size) {
                Map<String, String> ex = new HashMap<String, String>();
                ex.put("Content-Range", "bytes */" + it.size);
                sendSimple(o, 416, "Range Not Satisfiable", "text/plain", new byte[0], ex, true);
                return;
            }
        }
        HttpURLConnection c;
        try {
            c = Origin.open(it.url, range, null, head);     // لا نمرّر If-Range: الموقع لا يعرف ETag الخاص بنا
        } catch (IOException e) {
            sendSimple(o, 502, "Bad Gateway", "text/plain", "origin unreachable".getBytes(), null, head);
            return;
        }
        try {
            int code = c.getResponseCode();
            long cl = c.getContentLengthLong();
            String why = null;
            long expect = (range != null && openEnd) ? it.size - start : it.size;
            if (code == 416) why = "origin 416";
            else if (range != null && code != 206) why = "origin ignored Range (" + code + ")";
            else if (range == null && code != 200) why = "origin status " + code;
            else if (cl < 0) why = "origin gave no length";
            else if (cl != expect) why = "origin length " + cl + " != expected " + expect;
            else if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr == null || !cr.matches("bytes " + start + "-\\d+/" + it.size)) why = "origin Content-Range mismatch: " + cr;
            }
            String ct = c.getContentType();
            if (why == null && ct != null && ct.toLowerCase().contains("text/html")) why = "origin returned a web page";
            if (why != null) {
                sendSimple(o, 502, "Bad Gateway", "text/plain", why.getBytes("UTF-8"), null, head);
                return;
            }
            StringBuilder sb = new StringBuilder("HTTP/1.0 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
            sb.append("Content-Type: application/octet-stream\r\nContent-Length: ").append(cl).append("\r\n");
            sb.append("ETag: ").append(etag).append("\r\n");
            if (code == 206) sb.append("Content-Range: bytes ").append(start).append('-').append(it.size - 1).append('/').append(it.size).append("\r\n");
            if (it.ranges) sb.append("Accept-Ranges: bytes\r\n");
            sb.append("\r\n");
            o.write(sb.toString().getBytes("ISO-8859-1"));
            if (!head) {
                InputStream in = c.getInputStream();
                byte[] buf = new byte[262144];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); sent += n; }
                // إن انقطع الموقع قبل الاكتمال نغلق الاتصال فيعيد الـ PS4 المحاولة ويستأنف من حيث وصل
            }
            o.flush();
        } finally {
            c.disconnect();
        }
    }
}

package com.ps4lib.linksender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** خدمة تعمل في المقدمة (إشعار دائم) كي لا يقتلها النظام أثناء تحميل الـ PS4. */
public class RelayService extends Service {
    public static final int PORT = 8080;
    private static LinkStore store;
    private static RelayServer server;
    public static volatile String lastError = "";

    public static synchronized LinkStore store(Context c) {
        if (store == null) {
            final SharedPreferences sp = c.getApplicationContext().getSharedPreferences("links", MODE_PRIVATE);
            store = new LinkStore(new LinkStore.Persist() {
                public String load() { return sp.getString("data", null); }
                public void save(String s) { sp.edit().putString("data", s).apply(); }
            });
        }
        return store;
    }

    public static synchronized boolean running() { return server != null && server.isRunning(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("relay", "PS4 Link Sender", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, "relay");
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setContentTitle("PS4 Link Sender").setContentText("يعمل — اترك التطبيق أثناء التحميل على الـ PS4")
          .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true);
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);

        synchronized (RelayService.class) {
            if (server == null || !server.isRunning()) {
                try {
                    server = new RelayServer(store(this), PORT);
                    server.start();
                    lastError = "";
                } catch (Exception e) {
                    lastError = "تعذر تشغيل الخادم على المنفذ " + PORT + ": " + e.getMessage();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (RelayService.class) {
            if (server != null) server.stop();
            server = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

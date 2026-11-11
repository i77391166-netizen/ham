package com.ham.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Фоновый сервис: своё MQTT-соединение поверх обычного TCP (порт 1883),
 * расшифровка AES-GCM и системные уведомления. Нужен потому, что Android
 * душит WebView в фоне: пока приложение свёрнуто, JS-соединение умирает.
 */
public class HamService extends Service {

    static final String PREFS = "ham.native";
    static final String CH_SERVICE = "ham.svc";
    static final String CH_MSG = "ham.msg";
    static final int ID_SERVICE = 1001;
    static final String ROOT = "hamchat-v1/";

    /** приложение на экране — уведомления не нужны, оно и так показывает */
    static volatile boolean appVisible = false;

    private static volatile HamService instance = null;

    private volatile boolean stop = false;
    private boolean started = false;

    private final List<MiniMqtt> socks = new ArrayList<MiniMqtt>();
    private final Map<String, byte[]> keyCache = new ConcurrentHashMap<String, byte[]>();
    private final LinkedHashMap<String, Boolean> seen =
            new LinkedHashMap<String, Boolean>(300, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 300;
                }
            };

    /* ================== то, что зовёт MainActivity ================== */

    static void setSession(Context c, String json) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("session", json).apply();
        } catch (Throwable ignored) {
        }
    }

    /** комнаты сменились — рвём сокеты, воркеры сами переподключатся с новым списком */
    static void restart() {
        HamService s = instance;
        if (s == null) return;
        List<MiniMqtt> copy;
        synchronized (s.socks) {
            copy = new ArrayList<MiniMqtt>(s.socks);
        }
        for (MiniMqtt m : copy) m.close();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        startAsForeground();
        if (!started) {
            started = true;
            connector("broker.emqx.io", 1883);
            connector("broker.hivemq.com", 1883);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        List<MiniMqtt> copy;
        synchronized (socks) {
            copy = new ArrayList<MiniMqtt>(socks);
        }
        for (MiniMqtt m : copy) m.close();
        instance = null;
        super.onDestroy();
    }

    /* ================== уведомления ================== */

    private void startAsForeground() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel svc = new NotificationChannel(
                        CH_SERVICE, "Связь", NotificationManager.IMPORTANCE_MIN);
                svc.setShowBadge(false);
                svc.setSound(null, null);
                nm.createNotificationChannel(svc);

                NotificationChannel msg = new NotificationChannel(
                        CH_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
                msg.setShowBadge(true);
                nm.createNotificationChannel(msg);
            }
            startForeground(ID_SERVICE, buildServiceNotification());
        } catch (Throwable e) {
            Log.w("ham", "foreground failed", e);
        }
    }

    private PendingIntent openApp(int code) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, code, i, flags);
    }

    private Notification buildServiceNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_SERVICE);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Хам")
                .setContentText("на связи, сообщения придут")
                .setOngoing(true)
                .setContentIntent(openApp(0))
                .setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private void notifyMessage(String room, String nick, String text, String mid) {
        if (appVisible) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String title = (nick == null || nick.length() == 0) ? "Хам" : nick;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_MSG);
            else b = new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSubText("#" + room)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(room.hashCode()))
                    .setPriority(Notification.PRIORITY_HIGH);
            if (Build.VERSION.SDK_INT < 26) b.setDefaults(Notification.DEFAULT_ALL);
            nm.notify((room + "|" + mid).hashCode(), b.build());
        } catch (Throwable e) {
            Log.w("ham", "notify failed", e);
        }
    }

    /* ================== соединения ================== */

    private void connector(final String host, final int port) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                int backoff = 1500;
                while (!stop) {
                    MiniMqtt c = new MiniMqtt(host, port);
                    synchronized (socks) {
                        socks.add(c);
                    }
                    try {
                        c.connect();
                        List<String> rooms = rooms();
                        for (String r : rooms) c.subscribe(ROOT + r);
                        backoff = 1500;
                        c.loop(new MiniMqtt.Handler() {
                            public void onPublish(String topic, byte[] payload) {
                                handlePublish(topic, payload);
                            }
                        });
                    } catch (Throwable e) {
                        Log.w("ham", host + ": " + e.getMessage());
                    } finally {
                        c.close();
                        synchronized (socks) {
                            socks.remove(c);
                        }
                    }
                    if (stop) break;
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    backoff = Math.min(backoff * 2, 30000);
                }
            }
        }, "ham-" + host);
        t.setDaemon(true);
        t.start();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String sessionField(String name) {
        try {
            String js = prefs().getString("session", "");
            if (js == null || js.length() == 0) return "";
            return new JSONObject(js).optString(name, "");
        } catch (Throwable e) {
            return "";
        }
    }

    private List<String> rooms() {
        List<String> out = new ArrayList<String>();
        try {
            String js = prefs().getString("session", "");
            if (js == null || js.length() == 0) return out;
            JSONArray arr = new JSONObject(js).optJSONArray("rooms");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < 20; i++) {
                String r = arr.optString(i, "");
                if (r.length() > 0) out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** копилка сообщений, пришедших пока приложение было свёрнуто */
    private static final Object INBOX_LOCK = new Object();

    static void addInbox(Context c, String json) {
        synchronized (INBOX_LOCK) {
            try {
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray(p.getString("inbox", "[]"));
                arr.put(new JSONObject(json));
                while (arr.length() > 300) arr.remove(0);
                p.edit().putString("inbox", arr.toString()).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    /** JS забирает накопленное и очищает копилку */
    static String takeInbox(Context c) {
        synchronized (INBOX_LOCK) {
            try {
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String s = p.getString("inbox", "[]");
                p.edit().putString("inbox", "[]").apply();
                return s;
            } catch (Throwable e) {
                return "[]";
            }
        }
    }

    /* ================== разбор входящего ================== */

    private void handlePublish(String topic, byte[] payload) {
        try {
            if (!topic.startsWith(ROOT)) return;
            final String room = topic.substring(ROOT.length());
            byte[] key = keyFor(room);
            if (key == null) return;
            byte[] plain = HamCrypto.open(new String(payload, "UTF-8"), key);
            if (plain == null) return;

            JSONObject o = new JSONObject(new String(plain, "UTF-8"));
            String id = o.optString("id", "");
            if (id.length() == 0) return;

            String from = o.optString("from", "");
            if (from.length() > 0 && from.equals(sessionField("myId"))) return;

            String kind = o.optString("kind", "");
            String text = o.optString("text", "");
            if (kind.equals("file") || o.optString("t", "").equals("head")) {
                text = "📎 файл";
            } else if (!(kind.equals("text") || (kind.length() == 0 && text.length() > 0))) {
                return;
            }
            if (text.length() == 0) return;

            synchronized (seen) {
                if (seen.containsKey(id)) return;
                seen.put(id, Boolean.TRUE);
            }
            if (kind.equals("text") || kind.length() == 0) {
                JSONObject it = new JSONObject();
                it.put("room", room);
                it.put("id", id);
                it.put("from", from);
                it.put("nick", o.optString("nick", "собеседник"));
                it.put("ts", o.optLong("ts", System.currentTimeMillis()));
                it.put("kind", "text");
                it.put("text", text.length() > 2000 ? text.substring(0, 2000) : text);
                addInbox(this, it.toString());
            }
            if (text.length() > 400) text = text.substring(0, 400) + "…";
            notifyMessage(room, o.optString("nick", "собеседник"), text, id);
        } catch (Throwable e) {
            Log.w("ham", "bad message: " + e.getMessage());
        }
    }

    private byte[] keyFor(String room) {
        byte[] k = keyCache.get(room);
        if (k != null) return k;
        try {
            k = HamCrypto.pbkdf2("ham-kdf:" + room,
                    "ham-v1-salt".getBytes("UTF-8"), 120000, 32);
            keyCache.put(room, k);
            return k;
        } catch (Throwable e) {
            return null;
        }
    }

    /** после перезагрузки телефона сервис надо поднять заново, иначе уведомлений не будет */
    public static class BootReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Intent i = new Intent(context, HamService.class);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
                else context.startService(i);
            } catch (Throwable ignored) {
            }
        }
    }
}

/** PBKDF2-HMAC-SHA256 (не зависит от версии Android) + AES-GCM, как в WebCrypto. */
final class HamCrypto {

    private HamCrypto() {
    }

    static byte[] pbkdf2(String password, byte[] salt, int iterations, int bytes)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.getBytes("UTF-8"), "HmacSHA256"));
        int blocks = (bytes + 31) / 32;
        byte[] out = new byte[blocks * 32];
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[]{
                    (byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, 32);
        }
        byte[] res = new byte[bytes];
        System.arraycopy(out, 0, res, 0, bytes);
        return res;
    }

    static byte[] open(String b64, byte[] key) {
        try {
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            if (raw.length < 13) return null;
            byte[] iv = Arrays.copyOfRange(raw, 0, 12);
            byte[] ct = Arrays.copyOfRange(raw, 12, raw.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return c.doFinal(ct);
        } catch (Throwable e) {
            return null;
        }
    }
}

/** Минимальный MQTT 3.1.1: CONNECT, SUBSCRIBE, PINGREQ, приём PUBLISH (QoS 0/1). */
final class MiniMqtt {

    interface Handler {
        void onPublish(String topic, byte[] payload);
    }

    private final String host;
    private final int port;
    private final Object wlock = new Object();

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed = false;
    private volatile long lastRecv = 0;
    private int pid = 1;

    MiniMqtt(String host, int port) {
        this.host = host;
        this.port = port;
    }

    void connect() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        lastRecv = System.currentTimeMillis();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x04);
        body.write('M');
        body.write('Q');
        body.write('T');
        body.write('T');
        body.write(0x04);
        body.write(0x02);
        body.write(0x00);
        body.write(60);
        byte[] cid = ("hamn" + Long.toHexString(System.nanoTime())).getBytes("UTF-8");
        body.write((cid.length >> 8) & 0xff);
        body.write(cid.length & 0xff);
        body.write(cid);
        send(0x10, body.toByteArray());

        while (true) {
            int b0 = in.read();
            if (b0 < 0) throw new Exception("eof");
            byte[] p = readBody();
            lastRecv = System.currentTimeMillis();
            if ((b0 >> 4) == 2) {
                if (p.length >= 2 && p[1] != 0) throw new Exception("connack " + p[1]);
                break;
            }
        }

        Thread ping = new Thread(new Runnable() {
            public void run() {
                while (!closed) {
                    try {
                        Thread.sleep(20000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (closed) return;
                    try {
                        sendRaw(new byte[]{(byte) 0xC0, 0x00});
                        if (System.currentTimeMillis() - lastRecv > 75000) close();
                    } catch (Throwable e) {
                        close();
                        return;
                    }
                }
            }
        });
        ping.setDaemon(true);
        ping.start();
    }

    void subscribe(String topic) throws Exception {
        byte[] t = topic.getBytes("UTF-8");
        int id;
        synchronized (wlock) {
            id = pid++;
            if (pid > 60000) pid = 1;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((id >> 8) & 0xff);
        b.write(id & 0xff);
        b.write((t.length >> 8) & 0xff);
        b.write(t.length & 0xff);
        b.write(t);
        b.write(0x00);
        send(0x82, b.toByteArray());
    }

    void loop(Handler h) throws Exception {
        while (!closed) {
            int b0 = in.read();
            if (b0 < 0) throw new Exception("eof");
            byte[] body = readBody();
            lastRecv = System.currentTimeMillis();
            if ((b0 >> 4) != 3) continue;

            int qos = (b0 >> 1) & 3;
            if (body.length < 2) continue;
            int tl = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
            if (2 + tl > body.length) continue;
            String topic = new String(body, 2, tl, "UTF-8");
            int off = 2 + tl;
            int id = 0;
            if (qos > 0 && off + 2 <= body.length) {
                id = ((body[off] & 0xff) << 8) | (body[off + 1] & 0xff);
                off += 2;
            }
            byte[] payload = Arrays.copyOfRange(body, off, body.length);
            try {
                h.onPublish(topic, payload);
            } catch (Throwable ignored) {
            }
            if (qos == 1) {
                sendRaw(new byte[]{0x40, 0x02, (byte) ((id >> 8) & 0xff), (byte) (id & 0xff)});
            }
        }
    }

    private byte[] readBody() throws Exception {
        int len = 0, mult = 1, i = 0;
        while (true) {
            int d = in.read();
            if (d < 0) throw new Exception("eof");
            len += (d & 0x7f) * mult;
            if ((d & 0x80) == 0) break;
            mult *= 128;
            if (++i > 3) throw new Exception("bad length");
        }
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int r = in.read(b, got, len - got);
            if (r < 0) throw new Exception("eof");
            got += r;
        }
        return b;
    }

    private void send(int type, byte[] body) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(type);
        int n = body.length;
        do {
            int d = n % 128;
            n = n / 128;
            if (n > 0) d |= 0x80;
            b.write(d);
        } while (n > 0);
        b.write(body);
        sendRaw(b.toByteArray());
    }

    private void sendRaw(byte[] data) throws Exception {
        synchronized (wlock) {
            if (out == null) throw new Exception("closed");
            out.write(data);
            out.flush();
        }
    }

    void close() {
        closed = true;
        try {
            if (sock != null) sock.close();
        } catch (Throwable ignored) {
        }
        sock = null;
    }
}

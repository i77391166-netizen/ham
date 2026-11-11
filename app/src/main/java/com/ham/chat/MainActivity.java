package com.ham.chat;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 4711;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try{
            getWindow().setStatusBarColor(0xFF0E1621);
            getWindow().setNavigationBarColor(0xFF0E1621);
        }catch(Throwable ignored){}

        web = new WebView(this);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setBackgroundColor(0xFF0E0F10);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new NativeBridge(), "HamNative");

        // без этого выбор файла и обоев молча не работает
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                Intent content = params.createIntent();
                content.addCategory(Intent.CATEGORY_OPENABLE);
                Intent chooser = Intent.createChooser(content, "Выбрать файл");
                try {
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        startHamService();
        askNotifications();
    }

    /* фоновый сервис: держит своё MQTT-соединение и показывает уведомления */
    private void startHamService() {
        try {
            Intent i = new Intent(this, HamService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HamService.appVisible = true;
    }

    @Override
    protected void onPause() {
        HamService.appVisible = false;
        super.onPause();
    }

    /** сохранение картинок и видео из чата в Downloads/Хам */
    private class NativeBridge {

        /** JS отдаёт сервису: кто я и в каких комнатах сижу */
        @JavascriptInterface
        public void setSession(String json) {
            try {
                String old = getSharedPreferences(HamService.PREFS, MODE_PRIVATE)
                        .getString("session", "");
                HamService.setSession(MainActivity.this, json);
                if (old == null || !old.equals(json)) HamService.restart();
            } catch (Throwable ignored) {
            }
        }

        /** сообщения, пришедшие в фоне, пока WebView спал */
        @JavascriptInterface
        public String takeInbox() {
            try {
                return HamService.takeInbox(MainActivity.this);
            } catch (Throwable e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String save(String dataUrl, String name) {
            try {
                if (dataUrl == null) return "err:empty";
                int comma = dataUrl.indexOf(',');
                String raw = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] bytes = Base64.decode(raw, Base64.DEFAULT);

                String clean = (name == null || name.trim().isEmpty()) ? "ham_file" : name;
                clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (clean.length() > 80) clean = clean.substring(clean.length() - 80);

                String path;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, clean);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Хам");
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "err:mediastore";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "err:stream";
                    os.write(bytes);
                    os.close();
                    cv.clear();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    path = "Download/Хам/" + clean;
                } else {
                    File dir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS), "Хам");
                    if (!dir.exists() && !dir.mkdirs()) return "err:mkdir";
                    File out = new File(dir, clean);
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(bytes);
                    fos.close();
                    path = "Download/Хам/" + clean;
                }
                toast("сохранено: " + path);
                return "ok:" + path;
            } catch (Exception e) {
                toast("не сохранилось: " + e.getClass().getSimpleName());
                return "err:" + e.getClass().getSimpleName();
            }
        }

        private void toast(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}

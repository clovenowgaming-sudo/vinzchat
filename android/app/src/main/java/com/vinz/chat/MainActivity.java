package com.vinz.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("c", 0);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        String url = sp.getString("url", BuildConfig.SERVER_URL);
        if (url == null || url.isEmpty()) ask(); else web.loadUrl(url);
    }

    private void ask() {
        final EditText e = new EditText(this);
        e.setHint("https://xxxx.trycloudflare.com");
        new AlertDialog.Builder(this).setTitle("URL server").setView(e).setCancelable(false)
            .setPositiveButton("OK", (d, w) -> {
                String u = e.getText().toString().trim();
                sp.edit().putString("url", u).apply();
                web.loadUrl(u);
            }).show();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}

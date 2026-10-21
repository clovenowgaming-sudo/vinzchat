package com.vinz.chat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

public class Api {
    public interface CB { void done(int code, JSONObject json, String netErr); }

    static final OkHttpClient C = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build();
    static final MediaType J = MediaType.get("application/json");

    static String base() {
        String u = BuildConfig.SERVER_URL.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static void call(String method, String path, String token, JSONObject body, final CB cb) {
        Request req;
        try {
            Request.Builder rb = new Request.Builder().url(base() + "/api/" + path);
            if (token != null && !token.isEmpty()) rb.header("Authorization", "Bearer " + token);
            if (method.equals("GET")) rb.get();
            else rb.method(method, RequestBody.create(body == null ? "{}" : body.toString(), J));
            req = rb.build();
        } catch (Exception e) {
            cb.done(0, null, "URL server tidak valid");
            return;
        }
        C.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) {
                cb.done(0, null, e.getMessage() == null ? "network" : e.getMessage());
            }
            @Override public void onResponse(Call c, Response r) throws IOException {
                String s = r.body() != null ? r.body().string() : "";
                JSONObject j = null;
                try { j = new JSONObject(s); } catch (Exception ignored) {}
                cb.done(r.code(), j, null);
            }
        });
    }
}

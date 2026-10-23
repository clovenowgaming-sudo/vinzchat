package com.vinz.chat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String DOWN = "Tidak terhubung ke server. Periksa internet kamu.";

    SharedPreferences prefs;
    String token = "", me = "", phone = "", cur = "", screen = "";
    ArrayList<String> contacts = new ArrayList<>();
    HashMap<String, ArrayList<JSONObject>> msgs = new HashMap<>();
    HashMap<String, Integer> unread = new HashMap<>();
    WebSocket ws;
    boolean wsUp = false, retrying = false, alive = true;
    Handler h = new Handler(Looper.getMainLooper());
    FrameLayout content;
    TextView banner;
    LinearLayout msgBox, listBox;
    ScrollView msgScroll;

    // ---------- helpers ----------
    int c(int id) { return getResources().getColor(id); }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    void ui(final Runnable r) { runOnUiThread(() -> { if (alive) r.run(); }); }
    JSONObject json(String... kv) {
        JSONObject j = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) j.put(kv[i], kv[i + 1]); } catch (JSONException e) {}
        return j;
    }
    String msg(JSONObject j, String def) { return j != null ? j.optString("error", def) : def; }
    boolean down(JSONObject j, String net) { return net != null || j == null; }

    GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(0);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }
    TextView tv(String t, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
    EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(c(R.color.text));
        e.setHintTextColor(c(R.color.sub));
        e.setBackground(box(c(R.color.surface), c(R.color.line)));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        return e;
    }
    Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(box(c(R.color.primary), 0));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }
    LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(c(R.color.bg));
        return l;
    }
    LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
    LinearLayout topBar(String left, final Runnable la, String title, String right, final Runnable ra) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundColor(c(R.color.primary));
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        if (left != null) {
            TextView l = tv(left, 22, 0xFFFFFFFF, true);
            l.setPadding(0, 0, dp(16), 0);
            l.setOnClickListener(x -> la.run());
            t.addView(l);
        }
        t.addView(tv(title, 19, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (right != null) {
            TextView r = tv(right, 14, 0xFFFFFFFF, false);
            r.setOnClickListener(x -> ra.run());
            t.addView(r);
        }
        return t;
    }
    void setScreen(String name, View v) {
        screen = name;
        msgBox = null;
        listBox = null;
        if (!name.equals("chat")) cur = "";
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // ---------- lifecycle ----------
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("c", 0);
        token = prefs.getString("t", "");
        me = prefs.getString("me", "");
        getWindow().setStatusBarColor(c(R.color.primary));
        getWindow().setNavigationBarColor(c(R.color.bg));
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(c(R.color.bg));
        banner = tv("Tidak terhubung ke server", 13, 0xFFFFFFFF, true);
        banner.setBackgroundColor(c(R.color.error));
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(8), dp(8), dp(8), dp(8));
        banner.setVisibility(View.GONE);
        outer.addView(banner, new LinearLayout.LayoutParams(MATCH, WRAP));
        content = new FrameLayout(this);
        outer.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(outer);
        startApp();
    }
    @Override protected void onDestroy() {
        alive = false;
        if (ws != null) ws.close(1000, null);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (screen.equals("chat")) showList();
        else if (screen.equals("otp")) showLogin(null);
        else if (screen.equals("status") || screen.equals("addc")) showList();
        else super.onBackPressed();
    }

    // ---------- flow ----------
    void startApp() {
        stopWs();
        if (Api.base().isEmpty()) { showOffline("URL server belum diatur saat build APK."); return; }
        showLoading();
        Api.call("GET", "ping", null, null, (code, j, net) -> ui(() -> {
            if (down(j, net) || !j.optBoolean("ok")) { showOffline("Tidak terhubung ke server"); return; }
            if (token.isEmpty()) { showLogin(null); return; }
            Api.call("GET", "contacts", token, null, (c2, j2, n2) -> ui(() -> {
                if (down(j2, n2)) { showOffline("Tidak terhubung ke server"); return; }
                if (c2 == 401) { clearSession(); showLogin(null); return; }
                contacts.clear();
                JSONArray a = j2.optJSONArray("contacts");
                if (a != null) for (int i = 0; i < a.length(); i++) contacts.add(a.optString(i));
                loadContactMeta(j2);
                showList();
                connectWs();
            }));
        }));
    }
    void clearSession() {
        token = "";
        me = "";
        prefs.edit().clear().apply();
        contacts.clear();
        msgs.clear();
        unread.clear();
        cur = "";
    }
    void logout() {
        Api.call("POST", "logout", token, null, (x1, x2, x3) -> {});
        clearSession();
        stopWs();
        showLogin(null);
    }

    // ---------- screens ----------
    void showLoading() {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER);
        l.addView(tv("Menghubungkan...", 16, c(R.color.sub), false));
        setScreen("loading", l);
    }
    void showOffline(String m) {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView ex = tv("!", 56, c(R.color.error), true);
        ex.setGravity(Gravity.CENTER);
        l.addView(ex, lp(0, 0, 0, 8));
        TextView t = tv(m, 18, c(R.color.text), true);
        t.setGravity(Gravity.CENTER);
        l.addView(t, lp(0, 0, 0, 8));
        TextView s = tv("Cek koneksi internet kamu. Kalau tetap gagal, server mungkin sedang mati.", 14, c(R.color.sub), false);
        s.setGravity(Gravity.CENTER);
        l.addView(s, lp(0, 0, 0, 20));
        Button b = button("Coba lagi");
        b.setOnClickListener(x -> startApp());
        l.addView(b, lp(0, 0, 0, 0));
        setScreen("offline", l);
    }
    void showLogin(String err) {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        l.addView(tv("Vinz Chat", 28, c(R.color.primary), true));
        l.addView(tv("Masukkan nomor HP kamu. Kode OTP 6 digit akan dikirim lewat SMS.", 14, c(R.color.sub), false), lp(0, 8, 0, 16));
        final EditText ph = input("0812... atau +14155550123", InputType.TYPE_CLASS_PHONE);
        ph.setText(phone);
        l.addView(ph, lp(0, 0, 0, 8));
        final TextView er = tv(err == null ? "" : err, 13, c(R.color.error), false);
        l.addView(er, lp(0, 0, 0, 8));
        final Button b = button("Kirim OTP");
        b.setOnClickListener(x -> {
            phone = ph.getText().toString().trim();
            b.setEnabled(false);
            er.setText("");
            Api.call("POST", "register", null, json("phone", phone), (cd, j, net) -> ui(() -> {
                b.setEnabled(true);
                if (down(j, net)) er.setText(DOWN);
                else if (cd == 200) showOtp();
                else er.setText(msg(j, "Gagal mengirim OTP"));
            }));
        });
        l.addView(b, lp(0, 0, 0, 0));
        setScreen("login", l);
    }
    void showOtp() {
        LinearLayout l = col();
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        l.addView(tv("Verifikasi", 26, c(R.color.primary), true));
        l.addView(tv("Masukkan kode 6 digit yang dikirim ke " + phone, 14, c(R.color.sub), false), lp(0, 8, 0, 16));
        final EditText code = input("000000", InputType.TYPE_CLASS_NUMBER);
        l.addView(code, lp(0, 0, 0, 8));
        final TextView er = tv("", 13, c(R.color.error), false);
        l.addView(er, lp(0, 0, 0, 8));
        final Button b = button("Verifikasi");
        b.setOnClickListener(x -> {
            b.setEnabled(false);
            er.setText("");
            Api.call("POST", "verify", null, json("phone", phone, "code", code.getText().toString().trim()), (cd, j, net) -> ui(() -> {
                b.setEnabled(true);
                if (down(j, net)) er.setText(DOWN);
                else if (cd == 200) {
                    token = j.optString("token");
                    me = j.optString("phone");
                    prefs.edit().putString("t", token).putString("me", me).apply();
                    startApp();
                } else er.setText(msg(j, "Kode salah"));
            }));
        });
        l.addView(b, lp(0, 0, 0, 12));
        TextView back = tv("Ganti nomor", 14, c(R.color.primary), false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(x -> showLogin(null));
        l.addView(back, lp(0, 0, 0, 0));
        setScreen("otp", l);
    }
    void showListOld() {
        LinearLayout l = col();
        l.addView(topBar(null, null, "Vinz Chat", "Keluar", this::logout), new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(4));
        final EditText add = input("Tambah nomor (harus terdaftar)", InputType.TYPE_CLASS_PHONE);
        row.addView(add, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button plus = button("+");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(WRAP, MATCH);
        pp.leftMargin = dp(8);
        row.addView(plus, pp);
        l.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        final TextView er = tv("", 13, c(R.color.error), false);
        er.setPadding(dp(12), 0, dp(12), dp(4));
        l.addView(er, new LinearLayout.LayoutParams(MATCH, WRAP));
        plus.setOnClickListener(x -> {
            er.setText("");
            Api.call("POST", "contacts", token, json("phone", add.getText().toString().trim()), (cd, j, net) -> ui(() -> {
                if (down(j, net)) er.setText(DOWN);
                else if (cd == 200) {
                    String p = j.optString("phone");
                    if (!contacts.contains(p)) contacts.add(p);
                    add.setText("");
                    renderList();
                } else if (cd == 401) { clearSession(); showLogin(null); }
                else er.setText(msg(j, "Gagal menambah"));
            }));
        });
        ScrollView sv = new ScrollView(this);
        LinearLayout lb = new LinearLayout(this);
        lb.setOrientation(LinearLayout.VERTICAL);
        sv.addView(lb, new ViewGroup.LayoutParams(MATCH, WRAP));
        l.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setScreen("list", l);
        listBox = lb;
        renderList();
    }
    void renderList() {
        if (listBox == null || !screen.equals("list")) return;
        listBox.removeAllViews();
        if (contacts.isEmpty()) {
            TextView t = tv("Belum ada kontak. Tekan + Kontak untuk menyimpan nomor.", 14, c(R.color.sub), false);
            t.setPadding(dp(16), dp(24), dp(16), dp(24));
            listBox.addView(t);
            return;
        }
        for (final String p : contacts) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(16), dp(16), dp(16));
            r.setBackground(box(c(R.color.bg), c(R.color.line)));
            r.addView(tv(label(p), 16, c(R.color.text), false), new LinearLayout.LayoutParams(0, WRAP, 1f));
            Integer n = unread.get(p);
            if (n != null && n > 0) {
                TextView b = tv(String.valueOf(n), 12, 0xFFFFFFFF, true);
                b.setBackground(box(c(R.color.primary), 0));
                b.setPadding(dp(8), dp(2), dp(8), dp(2));
                r.addView(b);
            }
            r.setOnClickListener(x -> openChat(p));
            listBox.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }
    void openChat(String p) {
        refreshMeta();
        unread.remove(p);
        showChat(p);
        renderMsgs();
        loadHistory(p);
    }
    void showChat(String p) {
        cur = p;
        LinearLayout l = col();
        l.addView(topBar("<", this::showList, nameOf(p), null, null), new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView sv = new ScrollView(this);
        LinearLayout bx = new LinearLayout(this);
        bx.setOrientation(LinearLayout.VERTICAL);
        bx.setPadding(0, dp(6), 0, dp(6));
        sv.addView(bx, new ViewGroup.LayoutParams(MATCH, WRAP));
        l.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(c(R.color.surface));
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        final EditText in = input("Pesan", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Button send = button("Kirim");
        send.setOnClickListener(x -> send(in));
        Button att = button("+");
        att.setOnClickListener(x -> pick());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(WRAP, MATCH);
        ap.rightMargin = dp(8);
        bar.addView(att, ap);
        bar.addView(in, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(WRAP, MATCH);
        sp2.leftMargin = dp(8);
        bar.addView(send, sp2);
        l.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        setScreen("chat", l);
        msgBox = bx;
        msgScroll = sv;
    }
    void renderMsgs() {
        if (msgBox == null || !screen.equals("chat")) return;
        msgBox.removeAllViews();
        ArrayList<JSONObject> list = msgs.get(cur);
        if (list == null) return;
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);
        for (JSONObject m : list) {
            boolean mine = m.optString("from").equals(me);
            final JSONObject fm = m.optJSONObject("file");
            if (fm != null) {
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(WRAP, WRAP);
                fp.gravity = mine ? Gravity.END : Gravity.START;
                fp.setMargins(dp(8), dp(3), dp(8), dp(3));
                msgBox.addView(fileBubble(fm, mine, maxW), fp);
                continue;
            }
            TextView t = tv(fm != null ? fileLabel(fm) : m.optString("text"), 15, mine ? 0xFFFFFFFF : c(R.color.text), false);
            if (fm != null) t.setOnClickListener(x -> Xfer.download(MainActivity.this, token, fm));
            t.setMaxWidth(maxW);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setBackground(box(c(mine ? R.color.bubble_me : R.color.bubble_other), 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.gravity = mine ? Gravity.END : Gravity.START;
            p.setMargins(dp(8), dp(3), dp(8), dp(3));
            msgBox.addView(t, p);
        }
        msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- chat logic ----------
    void send(EditText in) {
        if (unreg.contains(cur)) { Toast.makeText(this, "Nomor ini belum terdaftar di Vinz Chat.", Toast.LENGTH_SHORT).show(); return; }
        String t = in.getText().toString().trim();
        if (t.isEmpty()) return;
        if (ws != null && wsUp && ws.send(json("to", cur, "text", t).toString())) in.setText("");
        else Toast.makeText(this, "Tidak terhubung ke server. Pesan tidak terkirim.", Toast.LENGTH_SHORT).show();
    }
    void addMsg(String peer, JSONObject m) {
        ArrayList<JSONObject> list = msgs.get(peer);
        if (list == null) { list = new ArrayList<>(); msgs.put(peer, list); }
        String id = m.optString("id");
        for (JSONObject e : list) if (e.optString("id").equals(id)) return;
        list.add(m);
    }
    void loadHistory(final String p) {
        Api.call("GET", "history?with=" + Uri.encode(p), token, null, (cd, j, net) -> ui(() -> {
            if (net != null) { banner.setVisibility(View.VISIBLE); return; }
            if (cd == 401) { clearSession(); showLogin(null); return; }
            if (cd != 200 || j == null) return;
            ArrayList<JSONObject> list = new ArrayList<>();
            JSONArray a = j.optJSONArray("messages");
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject m = a.optJSONObject(i); if (m != null) list.add(m); }
            msgs.put(p, list);
            if (p.equals(cur)) renderMsgs();
        }));
    }
    void onWsText(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            if (type.equals("err")) { Toast.makeText(this, m.optString("error"), Toast.LENGTH_SHORT).show(); return; }
            if (!type.equals("msg")) return;
            String from = m.optString("from"), to = m.optString("to");
            String peer = from.equals(me) ? to : from;
            addMsg(peer, m);
            if (!contacts.contains(peer)) contacts.add(peer);
            if (peer.equals(cur) && screen.equals("chat")) renderMsgs();
            else {
                if (!from.equals(me)) unread.put(peer, (unread.containsKey(peer) ? unread.get(peer) : 0) + 1);
                renderList();
            }
        } catch (JSONException ignored) {}
    }

    // ---------- file ----------
    JSONObject pendingFile;

    // ---------- kontak, preview, status ----------
    static final int[] BG = {0xFF1E6BFF, 0xFF0B8457, 0xFFB3261E, 0xFF6A1B9A, 0xFFE65100, 0xFF37474F};
    HashMap<String, String> names = new HashMap<>();
    HashSet<String> unreg = new HashSet<>();
    ArrayList<JSONObject> statuses = new ArrayList<>();
    LinearLayout stBox;
    int stBg = 0;

    String nameOf(String p) {
        String n = names.get(p);
        return (n == null || n.isEmpty()) ? p : n;
    }

    String label(String p) {
        String n = names.get(p);
        String suf = unreg.contains(p) ? "  (belum terdaftar)" : "";
        return (n == null || n.isEmpty()) ? p + suf : n + "\n" + p + suf;
    }

    void loadContactMeta(JSONObject j) {
        if (j == null) return;
        JSONArray a = j.optJSONArray("list");
        if (a == null) return;
        names.clear();
        unreg.clear();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String p = o.optString("phone");
            String n = o.optString("name");
            if (!n.isEmpty()) names.put(p, n);
            if (!o.optBoolean("registered")) unreg.add(p);
            if (!contacts.contains(p)) contacts.add(p);
        }
    }

    void refreshMeta() {
        Api.call("GET", "contacts", token, null, (cd, j, net) -> ui(() -> {
            if (cd == 200 && j != null) { loadContactMeta(j); renderList(); }
        }));
    }

    String timeAgo(long ts) {
        long m = (System.currentTimeMillis() - ts) / 60000;
        if (m < 1) return "baru saja";
        if (m < 60) return m + " menit lalu";
        return (m / 60) + " jam lalu";
    }

    void showList() {
        LinearLayout l = col();
        l.addView(topBar(null, null, "Vinz Chat", "Keluar", this::logout), new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout hr = new LinearLayout(this);
        hr.setPadding(dp(10), dp(10), dp(10), dp(6));
        Button st = button("Status");
        st.setOnClickListener(x -> showStatus());
        Button ac = button("+ Kontak");
        ac.setOnClickListener(x -> showAddContact(null));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        hp.rightMargin = dp(6);
        hr.addView(st, hp);
        hr.addView(ac, new LinearLayout.LayoutParams(0, WRAP, 1f));
        l.addView(hr, new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView sv = new ScrollView(this);
        LinearLayout lb = new LinearLayout(this);
        lb.setOrientation(LinearLayout.VERTICAL);
        sv.addView(lb, new ViewGroup.LayoutParams(MATCH, WRAP));
        l.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setScreen("list", l);
        listBox = lb;
        renderList();
    }

    void showAddContact(String err) {
        LinearLayout l = col();
        l.addView(topBar("<", this::showList, "Tambah kontak", null, null), new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), dp(16), dp(16), dp(16));
        final EditText nm = input("Nama (boleh kosong)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText ph = input("Nomor, mis. 0812... atau +14155550123", InputType.TYPE_CLASS_PHONE);
        f.addView(nm, lp(0, 0, 0, 8));
        f.addView(ph, lp(0, 0, 0, 8));
        f.addView(tv("Nomor luar negeri ditulis dengan + dan kode negara. Nomor yang belum terdaftar tetap bisa disimpan, tapi baru bisa dichat setelah orangnya daftar.", 13, c(R.color.sub), false), lp(0, 0, 0, 8));
        final TextView er = tv(err == null ? "" : err, 13, c(R.color.error), false);
        f.addView(er, lp(0, 0, 0, 8));
        final Button b = button("Simpan");
        b.setOnClickListener(x -> {
            b.setEnabled(false);
            er.setText("");
            Api.call("POST", "contacts", token, json("phone", ph.getText().toString().trim(), "name", nm.getText().toString().trim()), (cd, j, net) -> ui(() -> {
                b.setEnabled(true);
                if (down(j, net)) er.setText(DOWN);
                else if (cd == 200) {
                    String p = j.optString("phone");
                    if (!contacts.contains(p)) contacts.add(p);
                    String n = j.optString("name");
                    if (!n.isEmpty()) names.put(p, n);
                    if (!j.optBoolean("registered")) unreg.add(p); else unreg.remove(p);
                    Toast.makeText(this, "Kontak tersimpan", Toast.LENGTH_SHORT).show();
                    showList();
                } else if (cd == 401) { clearSession(); showLogin(null); }
                else er.setText(msg(j, "Gagal menyimpan"));
            }));
        });
        f.addView(b, lp(0, 0, 0, 0));
        l.addView(f, new LinearLayout.LayoutParams(MATCH, WRAP));
        setScreen("addc", l);
    }

    void showStatus() {
        LinearLayout l = col();
        l.addView(topBar("<", this::showList, "Status", null, null), new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(6));
        Button tx = button("Status teks");
        tx.setOnClickListener(x -> statusTextDialog());
        Button md = button("Foto / Video");
        md.setOnClickListener(x -> pickStatusMedia());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        bp.rightMargin = dp(6);
        row.addView(tx, bp);
        row.addView(md, new LinearLayout.LayoutParams(0, WRAP, 1f));
        l.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView sv = new ScrollView(this);
        LinearLayout sb = new LinearLayout(this);
        sb.setOrientation(LinearLayout.VERTICAL);
        sv.addView(sb, new ViewGroup.LayoutParams(MATCH, WRAP));
        l.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setScreen("status", l);
        stBox = sb;
        renderStatus();
        loadStatus();
    }

    void loadStatus() {
        Api.call("GET", "status", token, null, (cd, j, net) -> ui(() -> {
            if (net != null) { Toast.makeText(this, DOWN, Toast.LENGTH_SHORT).show(); return; }
            if (cd == 401) { clearSession(); showLogin(null); return; }
            if (cd != 200 || j == null) return;
            statuses.clear();
            JSONArray a = j.optJSONArray("statuses");
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject s = a.optJSONObject(i); if (s != null) statuses.add(s); }
            renderStatus();
        }));
    }

    void renderStatus() {
        if (stBox == null || !screen.equals("status")) return;
        stBox.removeAllViews();
        if (statuses.isEmpty()) {
            TextView t = tv("Belum ada status. Posting teks, foto, atau video. Status hilang setelah 24 jam dan hanya terlihat oleh kontak yang saling menyimpan nomor.", 14, c(R.color.sub), false);
            t.setPadding(dp(16), dp(24), dp(16), dp(24));
            stBox.addView(t);
            return;
        }
        for (final JSONObject s : statuses) {
            final boolean mine = s.optString("owner").equals(me);
            final String who = mine ? "Status saya" : nameOf(s.optString("owner"));
            String kind = s.optString("kind");
            JSONObject file = s.optJSONObject("file");
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(10), dp(14), dp(10));
            r.setBackground(box(c(R.color.bg), c(R.color.line)));
            View th;
            if (kind.equals("image") && file != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0x22000000);
                th = iv;
                Xfer.loadImage(this, token, file.optString("id"), iv, dp(56));
            } else {
                TextView tt = tv(kind.equals("video") ? "\u25B6" : "Aa", 18, 0xFFFFFFFF, true);
                tt.setGravity(Gravity.CENTER);
                tt.setBackgroundColor(kind.equals("video") ? 0xFF111111 : BG[Math.abs(s.optInt("bg")) % BG.length]);
                th = tt;
            }
            r.addView(th, new LinearLayout.LayoutParams(dp(56), dp(56)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(12), 0, 0, 0);
            tx.addView(tv(who, 16, c(R.color.text), true));
            String desc = kind.equals("text") ? s.optString("text") : (kind.equals("image") ? "Foto" : "Video");
            if (desc.length() > 40) desc = desc.substring(0, 40) + "...";
            tx.addView(tv(timeAgo(s.optLong("ts")) + "  -  " + desc, 13, c(R.color.sub), false));
            r.addView(tx, new LinearLayout.LayoutParams(0, WRAP, 1f));
            r.setOnClickListener(x -> viewer(who, s.optJSONObject("file"), s.optString("text"), s.optInt("bg"), mine ? s.optString("id") : null));
            stBox.addView(r, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    void statusTextDialog() {
        stBg = 0;
        final EditText in = new EditText(this);
        in.setHint("Tulis status...");
        in.setMinLines(4);
        in.setGravity(Gravity.TOP);
        in.setTextColor(0xFFFFFFFF);
        in.setHintTextColor(0xAAFFFFFF);
        in.setTextSize(20);
        in.setBackgroundColor(BG[0]);
        in.setPadding(dp(16), dp(16), dp(16), dp(16));
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), dp(8));
        wrap.addView(in, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button cb = button("Ganti warna");
        cb.setOnClickListener(x -> { stBg = (stBg + 1) % BG.length; in.setBackgroundColor(BG[stBg]); });
        wrap.addView(cb, lp(0, 8, 0, 0));
        new android.app.AlertDialog.Builder(this).setTitle("Status teks").setView(wrap)
            .setPositiveButton("Posting", (d, w) -> {
                String t = in.getText().toString().trim();
                if (t.isEmpty()) return;
                JSONObject b = json("text", t);
                try { b.put("bg", stBg); } catch (JSONException e) {}
                Api.call("POST", "status/text", token, b, (cd, j, net) -> ui(() -> {
                    if (down(j, net)) Toast.makeText(this, DOWN, Toast.LENGTH_SHORT).show();
                    else if (cd != 200) Toast.makeText(this, msg(j, "Gagal posting"), Toast.LENGTH_SHORT).show();
                    else loadStatus();
                }));
            }).setNegativeButton("Batal", null).show();
    }

    void pickStatusMedia() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(i, 79);
    }

    View fileBubble(final JSONObject f, boolean mine, int maxW) {
        final String mime = f.optString("mime");
        int tc = mine ? 0xFFFFFFFF : c(R.color.text);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(box(c(mine ? R.color.bubble_me : R.color.bubble_other), 0));
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        int w = Math.min(maxW, dp(240));
        if (mime.startsWith("image/")) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMinimumHeight(dp(120));
            iv.setBackgroundColor(0x22000000);
            b.addView(iv, new LinearLayout.LayoutParams(w, WRAP));
            Xfer.loadImage(this, token, f.optString("id"), iv, w);
            b.setOnClickListener(x -> viewer(f.optString("name"), f, null, 0, null));
        } else if (mime.startsWith("video/")) {
            TextView pv = tv("\u25B6", 40, 0xFFFFFFFF, true);
            pv.setGravity(Gravity.CENTER);
            pv.setBackgroundColor(0xFF111111);
            b.addView(pv, new LinearLayout.LayoutParams(w, w * 9 / 16));
            TextView cap = tv(f.optString("name") + "\n" + Xfer.size(f.optLong("size")), 13, tc, false);
            cap.setPadding(dp(6), dp(4), dp(6), dp(2));
            cap.setMaxWidth(w);
            b.addView(cap);
            b.setOnClickListener(x -> viewer(f.optString("name"), f, null, 0, null));
        } else {
            TextView t = tv("File: " + f.optString("name") + "\n" + Xfer.size(f.optLong("size")) + "  -  ketuk untuk unduh", 15, tc, false);
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            t.setMaxWidth(w);
            b.addView(t);
            b.setOnClickListener(x -> Xfer.download(MainActivity.this, token, f));
        }
        return b;
    }

    void viewer(String title, final JSONObject f, String text, int bg, final String delId) {
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF111111);
        bar.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView close = tv("Tutup", 15, 0xFFFFFFFF, true);
        close.setPadding(0, 0, dp(16), 0);
        close.setOnClickListener(x -> d.dismiss());
        bar.addView(close);
        bar.addView(tv(title, 16, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (f != null) {
            TextView dl = tv("Unduh", 15, 0xFFFFFFFF, false);
            dl.setOnClickListener(x -> Xfer.download(MainActivity.this, token, f));
            bar.addView(dl);
        }
        if (delId != null) {
            TextView del = tv("   Hapus", 15, 0xFFFF8A80, false);
            del.setOnClickListener(x -> Api.call("POST", "status/delete", token, json("id", delId), (cd, j, net) -> ui(() -> { d.dismiss(); loadStatus(); })));
            bar.addView(del);
        }
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        String mime = f != null ? f.optString("mime") : "";
        if (f == null) {
            body.setBackgroundColor(BG[Math.abs(bg) % BG.length]);
            TextView t = tv(text == null ? "" : text, 24, 0xFFFFFFFF, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(24), dp(24), dp(24));
            body.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else if (mime.startsWith("video/")) {
            final VideoView vv = new VideoView(this);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(MATCH, MATCH);
            vp.gravity = Gravity.CENTER;
            body.addView(vv, vp);
            android.widget.MediaController mc = new android.widget.MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoURI(Uri.parse(Api.base() + "/api/file/" + f.optString("id") + "?token=" + Uri.encode(token)));
            vv.setOnPreparedListener(mp -> vv.start());
            vv.setOnErrorListener((mp, what, extra) -> { Toast.makeText(this, "Video tidak bisa diputar", Toast.LENGTH_LONG).show(); return true; });
            d.setOnDismissListener(x -> vv.stopPlayback());
        } else if (mime.startsWith("image/")) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            body.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
            Xfer.loadImage(this, token, f.optString("id"), iv, getResources().getDisplayMetrics().widthPixels);
        }
        if (f != null && text != null && !text.isEmpty()) {
            TextView cp = tv(text, 15, 0xFFFFFFFF, false);
            cp.setBackgroundColor(0x99000000);
            cp.setPadding(dp(14), dp(10), dp(14), dp(10));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(MATCH, WRAP);
            cl.gravity = Gravity.BOTTOM;
            body.addView(cp, cl);
        }
        d.setContentView(root);
        d.show();
    }

    void pick() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 77);
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 79 && res == RESULT_OK && data != null && data.getData() != null) { Xfer.uploadStatus(this, token, data.getData()); return; }
        if (req == 77 && res == RESULT_OK && data != null && data.getData() != null && !cur.isEmpty())
            Xfer.upload(this, token, cur, data.getData());
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(rc, perms, grants);
        if (rc != 78) return;
        if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && pendingFile != null)
            Xfer.download(this, token, pendingFile);
        else Toast.makeText(this, "Izin penyimpanan ditolak, file tidak bisa disimpan.", Toast.LENGTH_LONG).show();
        pendingFile = null;
    }

    String fileLabel(JSONObject f) {
        String mime = f.optString("mime");
        String kind = mime.startsWith("image/") ? "Foto" : mime.startsWith("video/") ? "Video" : "File";
        return kind + ": " + f.optString("name") + "\n" + Xfer.size(f.optLong("size")) + "  -  ketuk untuk unduh";
    }

    // ---------- websocket ----------
    void stopWs() {
        if (ws != null) { WebSocket w = ws; ws = null; w.close(1000, null); }
        wsUp = false;
        banner.setVisibility(View.GONE);
    }
    void connectWs() {
        if (token.isEmpty() || !alive) return;
        if (ws != null) { ws.close(1000, null); ws = null; }
        Request req;
        try {
            req = new Request.Builder().url(Api.base().replaceFirst("^http", "ws") + "/ws?token=" + token).build();
        } catch (Exception e) { return; }
        ws = Api.C.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(final WebSocket s, Response r) {
                ui(() -> {
  if (s != ws) return;
                    wsUp = true;
                    banner.setVisibility(View.GONE);
                    if (!cur.isEmpty()) loadHistory(cur);
                });
            }
            @Override public void onMessage(final WebSocket s, final String text) {
                ui(() -> { if (s == ws) onWsText(text); });
            }
            @Override public void onClosing(WebSocket s, int code, String reason) { s.close(1000, null); }
            @Override public void onClosed(final WebSocket s, int code, String reason) {
                ui(() -> wsDown(s, false));
            }
            @Override public void onFailure(final WebSocket s, Throwable t, Response r) {
                final boolean unauth = r != null && r.code() == 401;
                ui(() -> wsDown(s, unauth));
            }
        });
    }
    void wsDown(WebSocket s, boolean unauth) {
        if (s != ws) return;
        ws = null;
        wsUp = false;
        if (unauth) { clearSession(); showLogin(null); return; }
        banner.setVisibility(View.VISIBLE);
        if (!retrying) {
            retrying = true;
            h.postDelayed(() -> { retrying = false; connectWs(); }, 3000);
        }
    }
}

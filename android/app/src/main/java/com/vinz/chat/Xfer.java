package com.vinz.chat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONObject;

public class Xfer {
    static final OkHttpClient C = Api.C.newBuilder()
        .writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

    static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static void toast(final MainActivity a, final String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    static void upload(MainActivity a, String token, String to, Uri uri) { send(a, token, to, uri, false); }

    static void uploadStatus(MainActivity a, String token, Uri uri) { send(a, token, null, uri, true); }

    static void send(final MainActivity a, final String token, final String to, final Uri uri, final boolean status) {
        toast(a, status ? "Memposting status..." : "Mengirim file...");
        new Thread(() -> {
            try {
                final ContentResolver cr = a.getContentResolver();
                String name = "file";
                long sz = -1;
                Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int si = c.getColumnIndex(OpenableColumns.SIZE);
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                            if (si >= 0 && !c.isNull(si)) sz = c.getLong(si);
                        }
                    } finally { c.close(); }
                }
                if (sz <= 0) throw new Exception("Ukuran file tidak diketahui atau file kosong");
                String m = cr.getType(uri);
                if (m == null) m = "application/octet-stream";
                final long len = sz;
                RequestBody body = new RequestBody() {
                    @Override public MediaType contentType() { return MediaType.parse("application/octet-stream"); }
                    @Override public long contentLength() { return len; }
                    @Override public void writeTo(BufferedSink sink) throws java.io.IOException {
                        InputStream in = cr.openInputStream(uri);
                        if (in == null) throw new java.io.IOException("File tidak bisa dibaca");
                        try {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
                        } finally { in.close(); }
                    }
                };
                String url = status
                    ? Api.base() + "/api/status/media?name=" + Uri.encode(name) + "&type=" + Uri.encode(m)
                    : Api.base() + "/api/upload?to=" + Uri.encode(to) + "&name=" + Uri.encode(name) + "&type=" + Uri.encode(m);
                Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + token).post(body).build();
                try (Response r = C.newCall(req).execute()) {
                    String s = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        String err = "Gagal mengirim (" + r.code() + ")";
                        try { err = new JSONObject(s).optString("error", err); } catch (Exception ignored) {}
                        toast(a, err);
                    } else if (status) {
                        toast(a, "Status terposting");
                        a.runOnUiThread(a::loadStatus);
                    }
                }
            } catch (Exception e) {
                toast(a, "Tidak terhubung ke server. Tidak terkirim.");
            }
        }).start();
    }

    static void download(final MainActivity a, final String token, final JSONObject f) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
            && a.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.pendingFile = f;
            a.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 78);
            return;
        }
        toast(a, "Mengunduh...");
        new Thread(() -> {
            try {
                String name = f.optString("name", "file").replace("/", "_");
                String mime = f.optString("mime", "application/octet-stream");
                Request req = new Request.Builder().url(Api.base() + "/api/file/" + f.optString("id"))
                    .header("Authorization", "Bearer " + token).build();
                try (Response r = C.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null) throw new Exception("Gagal mengunduh (" + r.code() + ")");
                    OutputStream os;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VinzChat");
                        Uri u = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (u == null) throw new Exception("Tidak bisa menyimpan file");
                        os = a.getContentResolver().openOutputStream(u);
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VinzChat");
                        dir.mkdirs();
                        File out = new File(dir, name);
                        int i = 1;
                        while (out.exists()) out = new File(dir, "(" + (i++) + ") " + name);
                        os = new FileOutputStream(out);
                    }
                    if (os == null) throw new Exception("Tidak bisa menyimpan file");
                    InputStream in = r.body().byteStream();
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    } finally { in.close(); os.close(); }
                }
                toast(a, "Tersimpan di Download/VinzChat");
            } catch (Exception e) {
                String msg = e.getMessage();
                toast(a, msg != null && msg.startsWith("Gagal") ? msg : "Gagal mengunduh. Periksa koneksi.");
            }
        }).start();
    }

    static void loadImage(final MainActivity a, final String token, final String id, final ImageView iv, final int targetW) {
        final String key = id + "@" + targetW;
        Bitmap cached = MEM.get(key);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        new Thread(() -> {
            try {
                File f = new File(a.getCacheDir(), "f_" + id);
                if (!f.exists()) {
                    Request req = new Request.Builder().url(Api.base() + "/api/file/" + id)
                        .header("Authorization", "Bearer " + token).build();
                    try (Response r = C.newCall(req).execute()) {
                        if (!r.isSuccessful() || r.body() == null) return;
                        File tmp = new File(a.getCacheDir(), "t_" + id);
                        FileOutputStream os = new FileOutputStream(tmp);
                        InputStream in = r.body().byteStream();
                        try {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        } finally { in.close(); os.close(); }
                        tmp.renameTo(f);
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= targetW) s *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
                Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                int rot = 0;
                try {
                    int ori = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                    rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
                } catch (Exception ignored) {}
                if (rot != 0) {
                    Matrix mx = new Matrix();
                    mx.postRotate(rot);
                    bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), mx, true);
                }
                final Bitmap out = bm;
                MEM.put(key, out);
                a.runOnUiThread(() -> iv.setImageBitmap(out));
            } catch (Exception ignored) {}
        }).start();
    }
}

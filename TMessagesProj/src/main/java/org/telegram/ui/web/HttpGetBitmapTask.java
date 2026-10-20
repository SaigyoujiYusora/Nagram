package org.telegram.ui.web;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;

import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import tw.nekomimi.nekogram.helpers.ProxyHttp;

public class HttpGetBitmapTask extends AsyncTask<String, Void, Bitmap> {

    private final HashMap<String, String> headers = new HashMap<>();
    private final Utilities.Callback<Bitmap> callback;
    private Exception exception;
    /** When > 0, decode with inSampleSize so neither side exceeds this many px. */
    private int maxSidePx;

    public HttpGetBitmapTask(Utilities.Callback<Bitmap> callback) {
        this.callback = callback;
    }

    public HttpGetBitmapTask setHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public HttpGetBitmapTask setMaxSide(int maxSidePx) {
        this.maxSidePx = maxSidePx;
        return this;
    }

    @Override
    protected Bitmap doInBackground(String... params) {
        String urlString = params[0];
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(urlString);
            urlConnection = ProxyHttp.open(url);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                urlConnection.setRequestProperty(e.getKey(), e.getValue());
            }
            if (!headers.containsKey("User-Agent")) {
                urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            }
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);
            urlConnection.setConnectTimeout(20000);
            urlConnection.setReadTimeout(20000);
            urlConnection.setInstanceFollowRedirects(true);

            int statusCode = urlConnection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                String contentType = urlConnection.getContentType();
                try (InputStream in = new BufferedInputStream(urlConnection.getInputStream())) {
                    if (contentType != null && contentType.contains("svg")) {
                        return SvgHelper.getBitmap(in, 64, 64, false);
                    }
                    if (maxSidePx > 0) {
                        byte[] data = readAll(in, 16 * 1024 * 1024);
                        if (data == null) return null;
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 1;
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            int side = Math.max(bounds.outWidth, bounds.outHeight);
                            while (side / opts.inSampleSize > maxSidePx) {
                                opts.inSampleSize *= 2;
                            }
                        }
                        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    }
                    return BitmapFactory.decodeStream(in);
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            this.exception = e;
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) {
                return null;
            }
        }
        return out.toByteArray();
    }

    @Override
    protected void onPostExecute(Bitmap result) {
        if (callback != null) {
            if (exception == null) {
                callback.run(result);
            } else {
                callback.run(null);
            }
        }
    }
}

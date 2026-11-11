package net.kdt.pojavlaunch.authenticator.brix;

import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Base64;
import org.json.JSONArray;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.Callable;

/** Network helpers for Brix accounts. Safe to call from any thread: the work runs on a short-lived worker thread. */
public final class BrixAuth {
    public static final String API_BASE = "https://brix-api.brix-account.workers.dev";
    private static final String TAG = "BrixAuth";
    private static final long REFRESH_WINDOW_MS = 7L * 24 * 3600 * 1000;

    public enum Result { OK, SKIPPED, OFFLINE, EXPIRED }

    private BrixAuth() {}

    public static boolean needsRefresh(MinecraftAccount a) {
        return a.isBrix && a.expiresAt - System.currentTimeMillis() < REFRESH_WINDOW_MS;
    }

    /** Renews the token if it is close to expiring. Never throws; network trouble gives OFFLINE. */
    public static Result refreshIfNeeded(MinecraftAccount a) {
        if (!needsRefresh(a)) return Result.SKIPPED;
        return callOffThread(() -> doRefresh(a), 10000, Result.OFFLINE);
    }

    /** True if the Brix API answers right now. */
    public static boolean apiReachable() {
        return callOffThread(BrixAuth::ping, 4000, Boolean.FALSE);
    }

    private static Result doRefresh(MinecraftAccount a) throws Exception {
        HttpURLConnection c = open("/authserver/refresh", "POST");
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject().put("accessToken", a.accessToken);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 403) return Result.EXPIRED;
            if (code != 200) return Result.OFFLINE;
            JSONObject res = new JSONObject(readAll(c.getInputStream()));
            a.accessToken = res.getString("accessToken");
            a.expiresAt = res.getLong("expiresAt") * 1000L;
            a.save();
            return Result.OK;
        } finally {
            c.disconnect();
        }
    }

    private static Boolean ping() throws Exception {
        HttpURLConnection c = open("/health", "GET");
        try {
            return c.getResponseCode() == 200;
        } finally {
            c.disconnect();
        }
    }

    /** Face of the account's skin (hat layer included, scaled up), or null if there is no skin or no network. */
    public static Bitmap fetchFace(String profileId) {
        return callOffThread(() -> doFetchFace(profileId), 15000, (Bitmap) null);
    }

    private static Bitmap doFetchFace(String profileId) throws Exception {
        HttpURLConnection c = open("/sessionserver/session/minecraft/profile/" + profileId.replace("-", ""), "GET");
        String json;
        try {
            if (c.getResponseCode() != 200) return null;
            json = readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }

        JSONArray props = new JSONObject(json).getJSONArray("properties");
        String value = null;
        for (int i = 0; i < props.length(); i++) {
            JSONObject p = props.getJSONObject(i);
            if ("textures".equals(p.optString("name"))) value = p.getString("value");
        }
        if (value == null) return null;

        JSONObject textures = new JSONObject(
                new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8)).getJSONObject("textures");
        if (!textures.has("SKIN")) return null;
        String skinUrl = textures.getJSONObject("SKIN").getString("url");
        if (!skinUrl.startsWith(API_BASE + "/textures/")) return null; // only trust our own host

        HttpURLConnection d = (HttpURLConnection) new URL(skinUrl).openConnection();
        d.setConnectTimeout(5000);
        d.setReadTimeout(10000);
        d.setRequestProperty("User-Agent", "BrixLauncher/1.0");
        Bitmap skin;
        try {
            if (d.getResponseCode() != 200) return null;
            skin = BitmapFactory.decodeStream(d.getInputStream());
        } finally {
            d.disconnect();
        }
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;

        Bitmap face = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(face);
        canvas.drawBitmap(Bitmap.createBitmap(skin, 8, 8, 8, 8), 0, 0, null);
        canvas.drawBitmap(Bitmap.createBitmap(skin, 40, 8, 8, 8), 0, 0, null);
        return Bitmap.createScaledBitmap(face, 100, 100, false);
    }

    private static HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "BrixLauncher/1.0");
        return c;
    }

    private static String readAll(InputStream is) {
        try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    private static <T> T callOffThread(Callable<T> task, long timeoutMs, T fallback) {
        final Object[] box = new Object[1];
        Thread t = new Thread(() -> {
            try {
                box[0] = task.call();
            } catch (Exception e) {
                Log.w(TAG, "request failed", e);
            }
        }, "brix-net");
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        @SuppressWarnings("unchecked") T value = (T) box[0];
        return value != null ? value : fallback;
    }
}

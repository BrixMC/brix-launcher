package net.kdt.pojavlaunch.authenticator.brix;

import android.util.Log;

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
    // TEMP for testing: use 7L * 24 * 3600 * 1000 (7 days) in the real build
    private static final long REFRESH_WINDOW_MS = 365L * 24 * 3600 * 1000;

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

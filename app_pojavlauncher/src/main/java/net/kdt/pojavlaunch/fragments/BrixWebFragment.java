package net.kdt.pojavlaunch.fragments;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Shows the Brix website inside the launcher and receives the login result via brix://auth?code=... */
public class BrixWebFragment extends Fragment {
    public static final String TAG = "BRIX_WEB_FRAGMENT";
    private static final String SITE_HOST = "brixmc.github.io";
    private static final String START_URL = "https://brixmc.github.io/login.html?launcher=1";
    private static final String API_BASE = "https://brix-api.brix-account.workers.dev";

    private WebView mWebview;
    private boolean mBlankClient = true;
    private boolean mExchanging = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mWebview = (WebView) inflater.inflate(R.layout.fragment_brix_web, container, false);
        setWebViewSettings();
        if (savedInstanceState == null || mWebview.restoreState(savedInstanceState) == null) {
            mWebview.loadUrl(START_URL);
        }
        return mWebview;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        mWebview.setWebViewClient(new BrixClient());
        mBlankClient = false;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mBlankClient) mWebview.setWebViewClient(new BrixClient());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        mWebview.setWebViewClient(new WebViewClient());
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
    }

    public boolean canGoBack() { return mWebview.canGoBack(); }
    public void goBack() { mWebview.goBack(); }

    class BrixClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if ("brix".equals(uri.getScheme())) {
                String code = "auth".equals(uri.getHost()) ? uri.getQueryParameter("code") : null;
                if (code != null && code.matches("[0-9a-f]{48}")) exchangeCode(code);
                return true;
            }
            // Only our own website may load inside the launcher
            return !("https".equals(uri.getScheme()) && SITE_HOST.equals(uri.getHost()));
        }
    }

    private void exchangeCode(String code) {
        if (mExchanging) return;
        mExchanging = true;
        new Thread(() -> {
            try {
                JSONObject res = postJson("/launcher/exchange", new JSONObject().put("code", code));
                MinecraftAccount account = new MinecraftAccount();
                account.username = res.getString("username");
                account.profileId = res.getString("uuid");
                account.accessToken = res.getString("accessToken");
                account.expiresAt = res.getLong("expiresAt") * 1000L;
                account.isBrix = true;
                ui(() -> {
                    ExtraCore.setValue(BrixLoginFragment.EXTRA_BRIX_LOGIN, account);
                    Tools.backToMainMenu(requireActivity());
                });
            } catch (ApiException e) {
                ui(() -> fail(getString(R.string.brix_err_generic, e.getMessage())));
            } catch (Exception e) {
                ui(() -> fail(getString(R.string.brix_err_network)));
            }
        }).start();
    }

    private void fail(String message) {
        mExchanging = false;
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        mWebview.loadUrl(START_URL);
    }

    private void ui(Runnable r) {
        if (getActivity() == null || !isAdded()) return;
        getActivity().runOnUiThread(r);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (is != null) {
                try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    text = s.hasNext() ? s.next() : "";
                }
            }
            JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code >= 400) throw new ApiException(json.optString("error", "http_" + code));
            return json;
        } finally {
            c.disconnect();
        }
    }

    private static class ApiException extends Exception {
        ApiException(String code) { super(code); }
    }
}

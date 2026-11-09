package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

public class BrixLoginFragment extends Fragment {
    public static final String TAG = "BRIX_LOGIN_FRAGMENT";
    public static final String EXTRA_BRIX_LOGIN = "brix_login_todo";
    private static final String API_BASE = "https://brix-api.brix-account.workers.dev";

    private EditText mUsername;
    private EditText mPassword;
    private View mLoginButton;
    private View mRegisterButton;

    public BrixLoginFragment() {
        super(R.layout.fragment_brix_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mUsername = view.findViewById(R.id.brix_username);
        mPassword = view.findViewById(R.id.brix_password);
        mLoginButton = view.findViewById(R.id.brix_login_button);
        mRegisterButton = view.findViewById(R.id.brix_register_button);
        mLoginButton.setOnClickListener(v -> submit(false));
        mRegisterButton.setOnClickListener(v -> submit(true));
    }

    private void submit(boolean register) {
        String username = mUsername.getText().toString().trim();
        String password = mPassword.getText().toString();
        if (!username.matches("^[A-Za-z0-9_]{3,16}$")) {
            Toast.makeText(requireContext(), R.string.brix_bad_username, Toast.LENGTH_LONG).show();
            return;
        }
        if (password.length() < 8) {
            Toast.makeText(requireContext(), R.string.brix_bad_password, Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                if (register) request("/register", username, password);
                JSONObject res = request("/login", username, password);

                MinecraftAccount account = new MinecraftAccount();
                account.username = res.getString("username");
                account.profileId = res.getString("uuid");
                account.accessToken = res.getString("accessToken");
                account.expiresAt = res.getLong("expiresAt") * 1000L;
                account.isBrix = true;

                ui(() -> {
                    ExtraCore.setValue(EXTRA_BRIX_LOGIN, account);
                    Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, null);
                });
            } catch (ApiException e) {
                ui(() -> {
                    setBusy(false);
                    showError(e.getMessage());
                });
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false);
                    Toast.makeText(requireContext(), R.string.brix_err_network, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showError(String code) {
        int res;
        if ("invalid_credentials".equals(code)) res = R.string.brix_err_credentials;
        else if ("username_taken".equals(code)) res = R.string.brix_err_taken;
        else {
            Toast.makeText(requireContext(), getString(R.string.brix_err_generic, code), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy) {
        mLoginButton.setEnabled(!busy);
        mRegisterButton.setEnabled(!busy);
    }

    private void ui(Runnable r) {
        if (getActivity() == null || !isAdded()) return;
        getActivity().runOnUiThread(r);
    }

    private JSONObject request(String path, String username, String password) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("password", password);
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
        ApiException(String code) {
            super(code);
        }
    }
}

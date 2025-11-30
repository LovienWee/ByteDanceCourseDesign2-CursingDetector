package com.example.cursingdetector.stt;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaiduAuth {

    private static final String TAG = "BaiduAuth";

    private final String apiKey;
    private final String secretKey;
    private final OkHttpClient client = new OkHttpClient();

    private String cachedToken;
    private long tokenExpireAt = 0; // ms

    public BaiduAuth(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    public synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }

        String url = "https://aip.baidubce.com/oauth/2.0/token"
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        Request request = new Request.Builder().url(url).get().build();
        Response resp = client.newCall(request).execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Token HTTP " + resp.code());
        }

        String body = resp.body().string();
        Log.d(TAG, "token resp = " + body);
        JSONObject obj = new JSONObject(body);
        cachedToken = obj.getString("access_token");
        int expiresIn = obj.optInt("expires_in", 2592000); // 30天
        tokenExpireAt = now + expiresIn * 1000L;
        resp.close();
        return cachedToken;
    }
}

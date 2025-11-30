package com.example.cursingdetector.stt;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.example.cursingdetector.model.TranscriptSegment;
import com.example.cursingdetector.util.AudioExtractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BaiduSpeechToTextEngine implements SpeechToTextEngine {

    private static final String TAG = "BaiduSTT";

    private final BaiduAuth auth;
    private final OkHttpClient client = new OkHttpClient();
    private final int devPid;

    private volatile boolean cancelled = false;

    public BaiduSpeechToTextEngine(String apiKey, String secretKey) {
        this.auth = new BaiduAuth(apiKey, secretKey);
        this.devPid=1737;
    }

    public BaiduSpeechToTextEngine(String apiKey, String secretKey, int devPid) {
        this.auth = new BaiduAuth(apiKey, secretKey);
        this.devPid = devPid;
    }

    @Override
    public void start(Context context, Uri videoUri, ResultCallback callback) {
        cancelled = false;
        new Thread(() -> {
            try {
                // 1. 抽音频到 wav
                File wavFile = AudioExtractor.extractToWav(context, videoUri);
                if (cancelled) return;

                // 2. 读文件（建议用 InputStream 自己读，不用 Files.readAllBytes）
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream in = new FileInputStream(wavFile);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                in.close();
                byte[] data = baos.toByteArray();
                String speechBase64 = Base64.encodeToString(data, Base64.NO_WRAP);

                // 3. 拿 token
                String token = auth.getAccessToken();

                // 4. 组 JSON 请求体
                JSONObject req = new JSONObject();
                req.put("format", "wav");      // 音频格式
                req.put("rate", 16000);        // 采样率
                req.put("dev_pid", devPid);      // 英语模型
                req.put("channel", 1);
                req.put("token", token);
                req.put("cuid", "android-demo");
                req.put("len", data.length);
                req.put("speech", speechBase64);

                String url = "https://vop.baidu.com/server_api"; // 短语音识别地址:contentReference[oaicite:3]{index=3}

                MediaType jsonType = MediaType.parse("application/json");
                RequestBody body = RequestBody.create(jsonType, req.toString());

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                Response resp = client.newCall(request).execute();
                if (!resp.isSuccessful()) {
                    throw new RuntimeException("Baidu HTTP " + resp.code());
                }
                String respStr = resp.body().string();
                resp.close();

                Log.d(TAG, "resp = " + respStr);
                JSONObject jo = new JSONObject(respStr);
                int errNo = jo.optInt("err_no", -1);
                if (errNo != 0) {
                    String errMsg = jo.optString("err_msg", "unknown");
                    throw new RuntimeException("Baidu STT error " + errNo + ": " + errMsg);
                }

                JSONArray resultArr = jo.getJSONArray("result");
                String text = resultArr.getString(0); // 整段文本

                if (!cancelled && callback != null && text != null && !text.isEmpty()) {
                    // 这里没有时间戳
                    callback.onFinalResult(new TranscriptSegment(-1.0, -1.0, text));
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (!cancelled && callback != null) {
                    callback.onError(e);
                }
            }
        }).start();
    }

    @Override
    public void stop() {
        cancelled = true;
        // OkHttp 同步调用，简单一点就不做 cancel 了
    }
}

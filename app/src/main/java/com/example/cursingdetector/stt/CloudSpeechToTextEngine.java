package com.example.cursingdetector.stt;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.WorkerThread;

import com.example.cursingdetector.model.TranscriptSegment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 使用 OpenAI Whisper / GPT-4o Transcribe 做语音识别的实现
 * 调用 /v1/audio/transcriptions，response_format=verbose_json
 */
public class CloudSpeechToTextEngine implements SpeechToTextEngine {

    // 建议放到 BuildConfig 或安全存储里，demo 可以先写死
    private final String apiKey;

    private final OkHttpClient client = new OkHttpClient();
    private Call currentCall;

    public CloudSpeechToTextEngine(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void start(Context context, Uri videoUri, ResultCallback callback) {
        // 在后台线程里做：复制文件 → HTTP 请求
        new Thread(() -> {
            try {
                File tempFile = copyUriToCacheFile(context, videoUri);
                doTranscribe(tempFile, callback);
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError(e);
                }
            }
        }).start();
    }

    @Override
    public void stop() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }

    @WorkerThread
    private void doTranscribe(File audioFile, ResultCallback callback) throws Exception {
        // Whisper / audio API
        String url = "https://api.openai.com/v1/audio/transcriptions";

        // 注意：model 可以用 "whisper-1" 或更新的 "gpt-4o-transcribe":contentReference[oaicite:3]{index=3}
        String model = "whisper-1";

        MediaType mediaType = MediaType.parse("audio/mp4");

        RequestBody fileBody = RequestBody.create(mediaType, audioFile);

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                // 要 segment 级别时间戳
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        currentCall = client.newCall(request);

        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (callback != null) {
                    callback.onError(e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("HTTP " + response.code() + ": " + response.message());
                    }
                    String bodyStr = response.body().string();
                    parseAndEmitSegments(bodyStr, callback);
                } catch (Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        callback.onError(e);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void parseAndEmitSegments(String json, ResultCallback callback) throws Exception {
        // verbose_json 的结构大致是：
        // {
        //   "text": "...",
        //   "segments": [
        //      { "start": 0.0, "end": 3.2, "text": "..." },
        //      ...
        //   ]
        // }
        JSONObject root = new JSONObject(json);
        if (!root.has("segments")) {
            // 没有分段，只能当作一整段
            String text = root.optString("text", "");
            if (callback != null && !text.isEmpty()) {
                callback.onFinalResult(new TranscriptSegment(0.0, 0.0, text));
            }
            return;
        }

        JSONArray segments = root.getJSONArray("segments");
        for (int i = 0; i < segments.length(); i++) {
            JSONObject segObj = segments.getJSONObject(i);
            double start = segObj.optDouble("start", 0.0);
            double end = segObj.optDouble("end", start);
            String text = segObj.optString("text", "");
            if (callback != null && !text.isEmpty()) {
                callback.onFinalResult(new TranscriptSegment(start, end, text));
            }
        }
    }

    /** 把 Uri 内容复制到 app 缓存目录的临时文件，方便 OkHttp 读取 */
    private File copyUriToCacheFile(Context context, Uri uri) throws Exception {
        String name = "audio_tmp.mp4";
        File outFile = new File(context.getCacheDir(), name);

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
        return outFile;
    }
}

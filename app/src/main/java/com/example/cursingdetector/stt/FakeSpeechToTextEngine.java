package com.example.cursingdetector.stt;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.example.cursingdetector.model.TranscriptSegment;

// 一个纯假实现：按固定时间点“假装”识别出几句话
public class FakeSpeechToTextEngine implements SpeechToTextEngine {

    private Handler handler = new Handler(Looper.getMainLooper());
    private ResultCallback callback;
    private boolean running = false;

    @Override
    public void start(Context context, Uri videoUri, ResultCallback callback) {
        this.callback = callback;
        running = true;

        // 假设视频里会在 5s、12s、20s 说几句带脏话和不带脏话的
        scheduleSegment(5000,  5.0,  6.0,  "Oh my god this is crazy");
        scheduleSegment(12000, 12.0, 13.0, "What the hell are you doing");
        scheduleSegment(20000, 20.0, 21.5, "This game is so fucking hard");
        scheduleSegment(26000, 26.0, 27.0, "Nice shot, good job");
    }

    private void scheduleSegment(long delayMs, final double start, final double end, final String text) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || callback == null) return;
                TranscriptSegment seg = new TranscriptSegment(start, end, text);
                callback.onFinalResult(seg);
            }
        }, delayMs);
    }

    @Override
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        callback = null;
    }
}

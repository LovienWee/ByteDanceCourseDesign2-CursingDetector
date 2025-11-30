package com.example.cursingdetector.stt;

import android.net.Uri;
import android.content.Context;

import com.example.cursingdetector.model.TranscriptSegment;

public interface SpeechToTextEngine {

    interface ResultCallback {
        void onPartialResult(TranscriptSegment segment); // 可选
        void onFinalResult(TranscriptSegment segment);
        void onError(Throwable t);
    }

    void start(Context context, Uri videoUri, ResultCallback callback);

    void stop();
}

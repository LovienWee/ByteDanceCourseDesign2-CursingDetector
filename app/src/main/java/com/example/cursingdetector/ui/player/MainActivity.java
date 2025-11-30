package com.example.cursingdetector.ui.player;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cursingdetector.R;
import com.example.cursingdetector.model.ProfanityHit;
import com.example.cursingdetector.model.TranscriptSegment;
import com.example.cursingdetector.profanity.ProfanityDetector;
import com.example.cursingdetector.profanity.ProfanityStats;
import com.example.cursingdetector.stt.FakeSpeechToTextEngine;
import com.example.cursingdetector.stt.SpeechToTextEngine;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private StyledPlayerView playerView;
    private ExoPlayer exoPlayer;

    private TextView tvStatus;
    private TextView tvStats;
    private Button btnChooseVideo;

    private Uri currentVideoUri;

    private SpeechToTextEngine sttEngine;
    private ProfanityDetector profanityDetector = new ProfanityDetector();
    private ProfanityStats profanityStats = new ProfanityStats();

    private double currentMuteEndSec = -1; // 当前静音区间结束时间，-1 表示不静音

    private ActivityResultLauncher<String> pickVideoLauncher;

    private RecyclerView rvHits;
    private ProfanityHitAdapter hitAdapter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        tvStatus = findViewById(R.id.tv_status);
        tvStats = findViewById(R.id.tv_stats);
        btnChooseVideo = findViewById(R.id.btn_choose_video);

        // 初始化 ExoPlayer
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);

        // 播放结束监听
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    tvStatus.setText("播放结束");
                    if (sttEngine != null) {
                        sttEngine.stop();
                    }
                    showFinalStatsDialog();
                }
            }
        });

        // 注册文件选择器
        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        onVideoChosen(uri);
                    } else {
                        Toast.makeText(this, "未选择视频", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        btnChooseVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseVideo();
            }
        });

        // 先用假的 STT 引擎模拟
        sttEngine = new FakeSpeechToTextEngine();

        rvHits = findViewById(R.id.rv_hits);
        rvHits.setLayoutManager(new LinearLayoutManager(this));
        hitAdapter = new ProfanityHitAdapter(hit -> {
            // 点击某条命中 → 跳转到对应时间点
            if (exoPlayer != null) {
                long posMs = (long) (hit.startSec * 1000);
                exoPlayer.seekTo(posMs);
                exoPlayer.play();
            }
        });
        rvHits.setAdapter(hitAdapter);
    }

    private void chooseVideo() {
        pickVideoLauncher.launch("video/*");
    }

    private void onVideoChosen(Uri uri) {
        currentVideoUri = uri;
        String name = getFileNameFromUri(uri);
        tvStatus.setText("已选择视频：" + (name != null ? name : uri.toString()));
        startPlayAndDetect();
    }

    private void startPlayAndDetect() {
        profanityStats = new ProfanityStats();
        tvStats.setText("脏话统计：-");
        currentMuteEndSec = -1;
        hitAdapter.setData(profanityStats.hits);

        if (currentVideoUri == null) {
            Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show();
            return;
        }

        // 重置统计
        profanityStats = new ProfanityStats();
        tvStats.setText("脏话统计：-");
        currentMuteEndSec = -1;

        // 设置要播放的视频
        MediaItem mediaItem = MediaItem.fromUri(currentVideoUri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();
        tvStatus.setText("正在播放并实时检测...");

        // 启动语音识别引擎（当前是 Fake 实现）
        if (sttEngine != null) {
            sttEngine.start(this, currentVideoUri, new SpeechToTextEngine.ResultCallback() {
                @Override
                public void onPartialResult(TranscriptSegment segment) {
                    // 这次不处理 partial
                }

                @Override
                public void onFinalResult(TranscriptSegment segment) {
                    handleRecognizedSegment(segment);
                }

                @Override
                public void onError(Throwable t) {
                    Toast.makeText(MainActivity.this, "STT 错误：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void handleRecognizedSegment(TranscriptSegment segment) {
        if (segment == null || segment.text == null) return;

        List<String> badWords = profanityDetector.findProfanityWords(segment.text);
        if (!badWords.isEmpty()) {
            for (String w : badWords) {
                profanityStats.addHit(w, segment.startSec, segment.endSec, segment.text);
                // 新增：立刻把最新一条加到列表
                ProfanityHit last = profanityStats.hits.get(profanityStats.hits.size() - 1);
                runOnUiThread(() -> hitAdapter.addHit(last));
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvStats.setText(profanityStats.buildSummary());
                    tvStatus.setText("检测到脏话：" + segment.text);
                }
            });

            muteForSegment(segment.startSec, segment.endSec);
        }
    }

    private void muteForSegment(double startSec, double endSec) {
        if (exoPlayer == null) return;

        double duration = endSec - startSec;
        if (duration <= 0) return;

        // 立即静音
        exoPlayer.setVolume(0f);
        // 以当前播放位置为基准，持续 duration 秒
        currentMuteEndSec = (exoPlayer.getCurrentPosition() / 1000.0) + duration;

        startVolumeMonitor();
    }

    private void startVolumeMonitor() {
        // 每 200ms 检查是否需要解除静音
        playerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (exoPlayer == null) return;
                if (!exoPlayer.isPlaying()) return;

                if (currentMuteEndSec > 0) {
                    double nowSec = exoPlayer.getCurrentPosition() / 1000.0;
                    if (nowSec >= currentMuteEndSec) {
                        exoPlayer.setVolume(1f);
                        currentMuteEndSec = -1;
                    } else {
                        startVolumeMonitor();
                    }
                }
            }
        }, 200);
    }

    private void showFinalStatsDialog() {
        String summary = profanityStats.buildSummary();
        if (summary == null || summary.isEmpty()) {
            summary = "暂无脏话记录";
        }

        new AlertDialog.Builder(this)
                .setTitle("本局脏话统计")
                .setMessage(summary)
                .setPositiveButton("确定", null)
                .show();
    }

    @Nullable
    private String getFileNameFromUri(Uri uri) {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    result = cursor.getString(idx);
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sttEngine != null) {
            sttEngine.stop();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}

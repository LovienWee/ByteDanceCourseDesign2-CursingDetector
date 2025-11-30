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
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cursingdetector.R;
import com.example.cursingdetector.model.ProfanityHit;
import com.example.cursingdetector.model.TranscriptSegment;
import com.example.cursingdetector.profanity.ProfanityDetector;
import com.example.cursingdetector.profanity.ProfanityStats;
import com.example.cursingdetector.stt.BaiduSpeechToTextEngine;
import com.example.cursingdetector.stt.CloudSpeechToTextEngine;
import com.example.cursingdetector.stt.FakeSpeechToTextEngine;
import com.example.cursingdetector.stt.SpeechToTextEngine;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private StyledPlayerView playerView;
    private ExoPlayer exoPlayer;

    private TextView tvStatus;
    private TextView tvStats;
    private Button btnChooseVideo;

    private Uri currentVideoUri;

    private SpeechToTextEngine sttEngine;
    private final ProfanityDetector profanityDetector = new ProfanityDetector();
    private ProfanityStats profanityStats = new ProfanityStats();

    private double currentMuteEndSec = -1; // 当前静音区间结束时间，-1 表示不静音

    private ActivityResultLauncher<String> pickVideoLauncher;

    private RecyclerView rvHits;
    private ProfanityHitAdapter hitAdapter;

    private MaterialToolbar topAppBar;
    private ScrollView scrollContent;
    private ViewGroup playerContainer;
    private View btnFullscreen;
    private boolean isFullscreen = false;


    // ==== 新增：引擎类型枚举 + 当前选择 ====
    private enum EngineType {
        BAIDU_EN,   // 百度英文模型
        BAIDU_ZH,   // 百度中文模型
        OPENAI,
        FAKE
    }

    private EngineType currentEngineType = EngineType.BAIDU_EN;

    // =====================================

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

        topAppBar = findViewById(R.id.topAppBar);
        scrollContent = findViewById(R.id.scroll_content);
        playerContainer = findViewById(R.id.player_container);
        btnFullscreen = findViewById(R.id.btn_fullscreen);

        btnFullscreen.setOnClickListener(v -> toggleFullscreen());


        // ==== 新增：引擎选择 RadioGroup ====
        Spinner spEngine = findViewById(R.id.sp_engine);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.engine_names,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEngine.setAdapter(adapter);

        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        currentEngineType = EngineType.BAIDU_EN;
                        tvStatus.setText("当前引擎：百度英文模型");
                        break;
                    case 1:
                        currentEngineType = EngineType.BAIDU_ZH;
                        tvStatus.setText("当前引擎：百度中文模型");
                        break;
                    case 2:
                        currentEngineType = EngineType.OPENAI;
                        tvStatus.setText("当前引擎：OpenAI（可能受网络限制）");
                        break;
                    case 3:
                    default:
                        currentEngineType = EngineType.FAKE;
                        tvStatus.setText("当前引擎：本地模拟结果");
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // =================================

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

        btnChooseVideo.setOnClickListener(view -> chooseVideo());

        // ❗原来这里的 sttEngine = new BaiduSpeechToTextEngine(...) 删掉
        // 我们改成在真正开始检测时，根据 currentEngineType 动态创建
        sttEngine = null;

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
        if (currentVideoUri == null) {
            Toast.makeText(this, "请先选择视频", Toast.LENGTH_SHORT).show();
            return;
        }

        // 重置统计 & UI
        profanityStats = new ProfanityStats();
        tvStats.setText("脏话统计：-");
        currentMuteEndSec = -1;
        hitAdapter.setData(profanityStats.hits);

        // ==== 关键：根据当前选择的引擎重新创建 STT ====
        if (sttEngine != null) {
            sttEngine.stop();
        }
        sttEngine = createEngine();
        tvStatus.setText("正在使用 " + getEngineName(currentEngineType) + " 引擎检测...");
        // =================================================

        // 设置要播放的视频
        MediaItem mediaItem = MediaItem.fromUri(currentVideoUri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        // 启动语音识别引擎
        if (sttEngine != null) {
            sttEngine.start(this, currentVideoUri, new SpeechToTextEngine.ResultCallback() {
                @Override
                public void onPartialResult(TranscriptSegment segment) {
                    // 目前百度不返回增量，先留空
                }

                @Override
                public void onFinalResult(TranscriptSegment segment) {
                    handleRecognizedSegment(segment);
                }

                @Override
                public void onError(Throwable t) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "STT 错误：" + t.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        tvStatus.setText("语音识别失败：" + t.getMessage());
                    });
                }
            });
        }
    }

    private void handleRecognizedSegment(TranscriptSegment segment) {
        if (segment == null || segment.text == null) return;

        List<String> badWords = profanityDetector.findProfanityWords(segment.text);
        if (!badWords.isEmpty()) {
            boolean hasTime = segment.startSec >= 0 && segment.endSec >= 0;

            for (String w : badWords) {
                profanityStats.addHit(w, segment.startSec, segment.endSec, segment.text);

                if (hasTime) {
                    ProfanityHit last = profanityStats.hits.get(profanityStats.hits.size() - 1);
                    runOnUiThread(() -> hitAdapter.addHit(last));
                }
            }
        }

        runOnUiThread(() -> {
            tvStats.setText(profanityStats.buildSummary());
            tvStatus.setText("检测到脏话：" + segment.text);
        });
    }

    private void muteForSegment(double startSec, double endSec) {
        if (exoPlayer == null) return;

        double duration = endSec - startSec;
        if (duration <= 0) return;

        exoPlayer.setVolume(0f);
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

    // ==== 根据当前选项创建对应 STT 引擎 ====
    private SpeechToTextEngine createEngine() {
        // 你自己那两个 key
        String baiduApiKey = "62uccZJaWVsnYUirjqLXqvFp";
        String baiduSecretKey = "PWXakUA8FuUaegj7HEYGv3NXCjm5YnUR";

        switch (currentEngineType) {
            case BAIDU_EN:
                // 英文模型 dev_pid = 1737
                return new BaiduSpeechToTextEngine(baiduApiKey, baiduSecretKey, 1737);

            case BAIDU_ZH:
                // 中文普通话模型 dev_pid = 1537
                return new BaiduSpeechToTextEngine(baiduApiKey, baiduSecretKey, 1537);

            case OPENAI:
                // 暂时还连不上，可以先返回 Fake 做占位
                String openaiApiKey = "YOUR_OPENAI_API_KEY";
                return new CloudSpeechToTextEngine(openaiApiKey);

            case FAKE:
            default:
                return new FakeSpeechToTextEngine();
        }
    }

    // ==== 小工具：给用户看的引擎名称 ====
    private String getEngineName(EngineType type) {
        switch (type) {
            case BAIDU_EN:
                return "百度英文模型";
            case BAIDU_ZH:
                return "百度中文模型";
            case OPENAI:
                return "OpenAI";
            case FAKE:
            default:
                return "本地示例";
        }
    }

    private void toggleFullscreen() {
        if (!isFullscreen) {
            // 进入全屏
            isFullscreen = true;

            // 隐藏顶部栏和下方内容
            if (topAppBar != null) topAppBar.setVisibility(View.GONE);
            if (scrollContent != null) scrollContent.setVisibility(View.GONE);

            // 扩大播放器高度：占满剩余空间
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) playerContainer.getLayoutParams();
            params.height = 0;
            params.weight = 1f;
            playerContainer.setLayoutParams(params);

            // 隐藏系统状态栏和导航栏（沉浸式）
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            // 退出全屏
            isFullscreen = false;

            if (topAppBar != null) topAppBar.setVisibility(View.VISIBLE);
            if (scrollContent != null) scrollContent.setVisibility(View.VISIBLE);

            // 恢复播放器高度为 230dp
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) playerContainer.getLayoutParams();
            params.height = dpToPx(230);
            params.weight = 0f;
            playerContainer.setLayoutParams(params);

            // 恢复系统栏
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

}

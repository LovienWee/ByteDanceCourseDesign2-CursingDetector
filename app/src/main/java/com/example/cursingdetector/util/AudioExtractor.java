package com.example.cursingdetector.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 从 mp4 中抽取音频，并在本地转换为 16kHz、单声道、16bit PCM 的 wav 文件。
 * 这样既保证时长和原视频一致，又满足百度短语音接口要求。
 */
public class AudioExtractor {

    public static File extractToWav(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);

        int audioTrackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                format = f;
                break;
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release();
            throw new IllegalStateException("No audio track in file");
        }

        extractor.selectTrack(audioTrackIndex);

        String mime = format.getString(MediaFormat.KEY_MIME);
        int inSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);    // 44100
        int inChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);  // 2

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        // 输出文件：缓存目录下一个临时 wav
        File outFile = new File(context.getCacheDir(), "stt_audio_16k_mono.wav");
        FileOutputStream fos = new FileOutputStream(outFile);

        // 先写一个占位的 44 字节 wav 头，后面再用 WavHeaderPatcher 回填
        fos.write(new byte[44]);

        final int dstSampleRate = 16000;
        final int dstChannels   = 1;

        long totalPcmLen = 0;

        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        boolean isEOS = false;
        long timeoutUs = 10000;

        while (true) {
            if (!isEOS) {
                int inIndex = decoder.dequeueInputBuffer(timeoutUs);
                if (inIndex >= 0) {
                    ByteBuffer buffer = inputBuffers[inIndex];
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIndex, 0, sampleSize,
                                presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
            if (outIndex >= 0) {
                ByteBuffer outBuf = outputBuffers[outIndex];
                byte[] chunk = new byte[info.size];
                outBuf.get(chunk);
                outBuf.clear();

                // ------- 这里开始做：stereo -> mono -> 16k 重采样 -------
                short[] stereo = bytesToShortsLE(chunk);
                short[] mono = stereoToMono(stereo, inChannels);
                short[] mono16k = resample(mono, inSampleRate, dstSampleRate);
                byte[] outBytes = shortsToBytesLE(mono16k);
                // -------------------------------------------------------

                fos.write(outBytes);
                totalPcmLen += outBytes.length;

                decoder.releaseOutputBuffer(outIndex, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = decoder.getOutputBuffers();
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式变化，这里可以忽略
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        fos.flush();
        fos.close();

        // 回填 WAV 头：总 PCM 长度、采样率 16k、单声道
        WavHeaderPatcher.patchWavHeader(outFile, totalPcmLen, dstSampleRate, dstChannels);

        return outFile;
    }

    // ------------------ 工具方法：PCM 小端编解码 ------------------

    private static short[] bytesToShortsLE(byte[] bytes) {
        int len = bytes.length / 2;
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            int lo = bytes[2 * i] & 0xff;
            int hi = bytes[2 * i + 1] << 8;
            out[i] = (short) (hi | lo);
        }
        return out;
    }

    private static byte[] shortsToBytesLE(short[] shorts) {
        byte[] out = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            out[2 * i] = (byte) (shorts[i] & 0xff);
            out[2 * i + 1] = (byte) ((shorts[i] >> 8) & 0xff);
        }
        return out;
    }

    // stereo -> mono（简单平均左右声道）
    private static short[] stereoToMono(short[] stereo, int channelCount) {
        if (channelCount == 1) return stereo;
        int frames = stereo.length / 2;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int l = stereo[2 * i];
            int r = stereo[2 * i + 1];
            mono[i] = (short) ((l + r) / 2);
        }
        return mono;
    }

    // 简单线性插值重采样：srcRate -> dstRate，保持时长不变
    private static short[] resample(short[] input, int srcRate, int dstRate) {
        if (srcRate == dstRate) return input;

        double ratio = (double) dstRate / srcRate;
        int outLen = (int) Math.round(input.length * ratio);
        short[] out = new short[outLen];

        for (int i = 0; i < outLen; i++) {
            double srcIndex = i / ratio;
            int idx0 = (int) Math.floor(srcIndex);
            int idx1 = Math.min(idx0 + 1, input.length - 1);
            double frac = srcIndex - idx0;

            double s0 = input[idx0];
            double s1 = input[idx1];
            double s = s0 + (s1 - s0) * frac;
            out[i] = (short) s;
        }
        return out;
    }
}

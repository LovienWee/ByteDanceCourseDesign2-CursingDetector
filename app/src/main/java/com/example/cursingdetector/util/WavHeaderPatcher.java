package com.example.cursingdetector.util;

import java.io.RandomAccessFile;

/**
 * 简单补写 WAV 头的工具类
 * PCM 16bit, mono, 16kHz
 */
public class WavHeaderPatcher {

    public static void patchWavHeader(
            java.io.File wavFile,
            long totalPcmLen,
            int sampleRate,
            int channels) throws Exception {

        long totalDataLen = totalPcmLen + 36;  // WAV 头部固定 36 字节之后 + data chunk
        long byteRate = sampleRate * channels * 2; // 16-bit PCM => 2 bytes

        RandomAccessFile raf = new RandomAccessFile(wavFile, "rw");

        raf.seek(0);
        byte[] header = new byte[44];

        // ChunkID "RIFF"
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';

        // ChunkSize
        writeInt(header, 4, (int) totalDataLen);

        // Format "WAVE"
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

        // Subchunk1ID "fmt "
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';

        // Subchunk1Size = 16 for PCM
        writeInt(header, 16, 16);

        // AudioFormat = 1 (PCM)
        writeShort(header, 20, (short) 1);

        // NumChannels
        writeShort(header, 22, (short) channels);

        // SampleRate
        writeInt(header, 24, sampleRate);

        // ByteRate
        writeInt(header, 28, (int) byteRate);

        // BlockAlign = channels * 2
        writeShort(header, 32, (short) (channels * 2));

        // BitsPerSample = 16
        writeShort(header, 34, (short) 16);

        // Subchunk2ID "data"
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';

        // Subchunk2Size = PCM 数据长度
        writeInt(header, 40, (int) totalPcmLen);

        // 把头写进去
        raf.write(header, 0, 44);
        raf.close();
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset]     = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShort(byte[] data, int offset, short value) {
        data[offset]     = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}

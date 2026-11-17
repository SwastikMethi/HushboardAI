// Audio files for the decoder tests, made on the device: a plain tone as samples, and the
// same tone written as a compressed (AAC in MP4) file with the platform's own encoder.
package com.example.aikeyboard;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class TestAudio {

    private TestAudio() {}

    /** A 440 Hz tone at half volume. */
    static float[] tone(int sampleRate, double seconds) {
        float[] samples = new float[(int) (sampleRate * seconds)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        return samples;
    }

    /** Writes mono samples as an .m4a file. */
    static void writeAac(File file, float[] mono, int sampleRate) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        MediaMuxer muxer = new MediaMuxer(file.getPath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        int next = 0;
        boolean inputDone = false;
        boolean outputDone = false;
        try {
            while (!outputDone) {
                if (!inputDone) {
                    int in = encoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(in);
                        buffer.clear();
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        int count = Math.min(buffer.remaining() / 2, mono.length - next);
                        long timeUs = next * 1_000_000L / sampleRate;
                        for (int i = 0; i < count; i++) {
                            buffer.putShort((short) (mono[next++] * 32767));
                        }
                        inputDone = next >= mono.length;
                        encoder.queueInputBuffer(in, 0, count * 2, timeUs,
                                inputDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    }
                }
                int out = encoder.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                } else if (out >= 0) {
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0) {
                        muxer.writeSampleData(track, encoder.getOutputBuffer(out), info);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(out, false);
                }
            }
            muxer.stop();
        } finally {
            muxer.release();
            encoder.release();
        }
    }
}

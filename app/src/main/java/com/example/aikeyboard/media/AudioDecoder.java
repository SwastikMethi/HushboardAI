// Turns an audio file the user picked into mono 16 kHz samples, using the platform's own
// decoders. Reads at most a bounded length, so a long recording cannot exhaust memory.
package com.example.aikeyboard.media;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.example.aikeyboard.media.Voice.AudioClip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AudioDecoder {

    private static final long CODEC_TIMEOUT_US = 10_000;

    private AudioDecoder() {}

    /**
     * Blocking; call off the main thread. Throws IOException with a plain reason when the
     * file has no audio or is in a form that cannot be read.
     */
    public static AudioClip decode(Context context, Uri uri, int maxSeconds) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            try {
                extractor.setDataSource(context, uri, null);
            } catch (IOException e) {
                throw new IOException("Not a media file the system can open", e);
            }
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IOException("This file has no audio in it");

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            ensure16Bit(format);
            long maxBytes = (long) maxSeconds * rate * channels * 2;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            String mime = format.getString(MediaFormat.KEY_MIME);

            if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) {
                // Already plain samples (a WAV file): copy them straight out.
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                int read;
                while (pcm.size() < maxBytes
                        && (read = extractor.readSampleData(buffer, 0)) >= 0) {
                    pcm.write(buffer.array(), 0, read);
                    extractor.advance();
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null, null, 0);
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                while (!outputDone && pcm.size() < maxBytes) {
                    if (!inputDone) {
                        int in = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (in >= 0) {
                            int size = extractor.readSampleData(codec.getInputBuffer(in), 0);
                            if (size < 0) {
                                codec.queueInputBuffer(in, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(in, 0, size,
                                        extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int out = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // The decoder knows the real layout only after it has started.
                        MediaFormat decoded = codec.getOutputFormat();
                        rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        ensure16Bit(decoded);
                        maxBytes = (long) maxSeconds * rate * channels * 2;
                    } else if (out >= 0) {
                        byte[] chunk = new byte[info.size];
                        ByteBuffer decodedBytes = codec.getOutputBuffer(out);
                        decodedBytes.position(info.offset);
                        decodedBytes.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                        codec.releaseOutputBuffer(out, false);
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    }
                }
            }

            byte[] bytes = pcm.toByteArray();
            if (bytes.length < 2) throw new IOException("This file has no audio in it");
            short[] interleaved = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(interleaved);
            AudioClip clip = new AudioClip(AudioPipeline.toMono(interleaved, channels), rate);
            return AudioPipeline.resample(clip, AudioPipeline.SPEECH_RATE);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The platform's way of saying it cannot make sense of the file.
            throw new IOException("This audio format cannot be read", e);
        } finally {
            if (codec != null) codec.release();
            extractor.release();
        }
    }

    private static void ensure16Bit(MediaFormat format) throws IOException {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(
                MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
            throw new IOException("This audio format cannot be read");
        }
    }
}

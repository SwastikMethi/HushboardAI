// Plays a clip held in memory, so a result can be listened to before it is shared. One clip
// at a time; whoever owns this stops it when their screen goes away.
package com.example.aikeyboard.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.example.aikeyboard.media.Voice.AudioClip;

public final class AudioPlayback {

    private AudioTrack track;

    /**
     * Starts playing from the beginning, replacing anything already playing.
     *
     * @param onFinished called on the main thread when the clip reaches its end by itself
     */
    public void play(AudioClip clip, Runnable onFinished) {
        stop();
        int frames = clip.samples.length;
        AudioTrack player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                // The whole clip is handed over at once: it is short and already in memory.
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * Float.BYTES)
                .build();
        player.write(clip.samples, 0, frames, AudioTrack.WRITE_BLOCKING);
        player.setNotificationMarkerPosition(frames);
        player.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack reached) {
                if (track == reached) {
                    stop();
                    onFinished.run();
                }
            }

            @Override
            public void onPeriodicNotification(AudioTrack ignored) {
            }
        });
        track = player;
        player.play();
    }

    public boolean isPlaying() {
        return track != null;
    }

    /** Safe to call when nothing is playing. */
    public void stop() {
        if (track == null) return;
        AudioTrack player = track;
        track = null;
        player.pause();
        player.release();
    }
}

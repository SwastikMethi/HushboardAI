// The single persisted source of preferences for the keyboard and the companion app. There
// must be exactly one DataStore per file in the process, so this is created once by
// AppContainer and shared. RxJava stays inside this class's boundary.
package com.example.aikeyboard.data;

import android.content.Context;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

public final class SettingsRepository {

    private final RxDataStore<Preferences> store;
    private final Flowable<AppSettings> stream;
    private final AtomicLong revision = new AtomicLong();
    private volatile AppSettings latest = new AppSettings(0, Map.of());
    // Lives as long as the process: keeps `latest` current for synchronous readers.
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final Disposable ownSubscription;

    public SettingsRepository(Context appContext) {
        store = new RxPreferenceDataStoreBuilder(appContext, "settings").build();
        // One shared upstream, so every observer sees the same snapshot with the same revision.
        stream = store.data().map(this::snapshot).replay(1).autoConnect();
        ownSubscription = stream.subscribe(settings -> latest = settings, error -> { });
    }

    private AppSettings snapshot(Preferences preferences) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<Preferences.Key<?>, Object> entry : preferences.asMap().entrySet()) {
            values.put(entry.getKey().getName(), entry.getValue());
        }
        return new AppSettings(revision.incrementAndGet(), values);
    }

    /**
     * Every snapshot, starting with the current one. Emits on a background thread: observers
     * that touch views must move to the main thread, and must dispose with their owner.
     */
    public Flowable<AppSettings> observe() {
        return stream;
    }

    /** The most recent snapshot, without waiting. Defaults until the first load finishes. */
    public AppSettings latest() {
        return latest;
    }

    public void setBoolean(String key, boolean value) {
        update(PreferencesKeys.booleanKey(key), value);
    }

    /**
     * The user's choice for one model. Turning a model back on also clears the note that it
     * was switched off after a crash: re-enabling it is the user's informed decision.
     */
    public void setModelEnabled(String modelId, boolean enabled) {
        setBoolean(AppSettings.modelEnabledKey(modelId), enabled);
        if (enabled) setBoolean(AppSettings.modelCrashedKey(modelId), false);
    }

    public void setInt(String key, int value) {
        update(PreferencesKeys.intKey(key), value);
    }

    public void setString(String key, String value) {
        update(PreferencesKeys.stringKey(key), value);
    }

    public void setStringSet(String key, Set<String> value) {
        update(PreferencesKeys.stringSetKey(key), value);
    }

    private <T> void update(Preferences.Key<T> key, T value) {
        // The write is asynchronous; observers hear about it through the stream.
        Disposable ignored = store.updateDataAsync(preferences -> {
            MutablePreferences mutable = preferences.toMutablePreferences();
            mutable.set(key, value);
            return Single.just(mutable);
        }).subscribe(done -> { }, error -> { });
    }
}

// The typed contract every model sits behind: one kind of input in, one kind of result out.
// An adapter converts to and from the vendor SDK and nothing else; it has no UI logic.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.data.AppSettings;

public interface ModelAdapter<I, O> {

    String modelId();

    /**
     * Loads the model if needed and runs it. Blocking: called only on the inference thread,
     * one call at a time. A call that has started cannot be interrupted, so callers decide
     * afterwards whether the result is still wanted.
     */
    O run(I input, AppSettings settings) throws Exception;

    /** Frees the model's memory. Called on the inference thread, never during a run. */
    void release();
}

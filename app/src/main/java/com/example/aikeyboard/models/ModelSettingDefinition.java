// A typed description of one model setting, so a single detail screen can render any model.
// Every definition maps to something the verified SDK API or the app itself really does.
package com.example.aikeyboard.models;

public final class ModelSettingDefinition {

    public enum Type { TOGGLE, CHOICE, RANGE }

    /** Name within the model; the stored key is AppSettings.modelSettingKey(modelId, name). */
    public final String name;
    public final Type type;
    /** String resource for the row title. */
    public final int title;
    /** String resource for a one-line explanation, or 0. */
    public final int summary;

    public final boolean defaultOn;      // TOGGLE
    public final String[] values;        // CHOICE: stored values
    public final int labels;             // CHOICE: string-array resource, same order as values
    public final String defaultValue;    // CHOICE
    public final int min;                // RANGE
    public final int max;
    public final int step;
    public final int defaultNumber;

    private ModelSettingDefinition(String name, Type type, int title, int summary,
            boolean defaultOn, String[] values, int labels, String defaultValue,
            int min, int max, int step, int defaultNumber) {
        this.name = name;
        this.type = type;
        this.title = title;
        this.summary = summary;
        this.defaultOn = defaultOn;
        this.values = values;
        this.labels = labels;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
        this.defaultNumber = defaultNumber;
    }

    public static ModelSettingDefinition toggle(String name, int title, boolean defaultOn) {
        return new ModelSettingDefinition(name, Type.TOGGLE, title, 0, defaultOn,
                null, 0, null, 0, 0, 0, 0);
    }

    public static ModelSettingDefinition choice(String name, int title, int summary,
            String[] values, int labels, String defaultValue) {
        return new ModelSettingDefinition(name, Type.CHOICE, title, summary, false,
                values, labels, defaultValue, 0, 0, 0, 0);
    }

    public static ModelSettingDefinition range(String name, int title, int summary,
            int min, int max, int step, int defaultNumber) {
        return new ModelSettingDefinition(name, Type.RANGE, title, summary, false,
                null, 0, null, min, max, step, defaultNumber);
    }
}

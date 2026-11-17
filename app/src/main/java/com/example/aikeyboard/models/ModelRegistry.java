// The model catalog: the single source for the 13 Android models. Adapters are registered in
// code; nothing here is downloaded or executed as code. File lists are copied from the vendor
// SDK's own manifest for SDK 4.3.0 (see docs/model-compatibility.md).
package com.example.aikeyboard.models;

import static com.example.aikeyboard.models.ModelSettingDefinition.choice;
import static com.example.aikeyboard.models.ModelSettingDefinition.range;
import static com.example.aikeyboard.models.ModelSettingDefinition.toggle;

import com.example.aikeyboard.R;
import com.example.aikeyboard.models.ModelDescriptor.Group;
import com.example.aikeyboard.models.ModelDescriptor.ModelFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ModelRegistry {

    // Setting names shared with the adapters that read them.
    public static final String EMO_LIMIT = "limit";
    public static final String EMO_SKIN_TONE = "skin_tone";
    public static final String REDACT_MODE = "mode";
    public static final String REDACT_MODE_WARN = "warn";
    public static final String REDACT_MODE_MASK = "mask";
    public static final String REDACT_MIN_CONFIDENCE = "min_confidence";
    public static final String TOXIC_SENSITIVITY = "sensitivity";
    public static final String SENSITIVITY_FEWER = "fewer";
    public static final String SENSITIVITY_STANDARD = "standard";
    public static final String SENSITIVITY_MORE = "more";

    private static final List<ModelSettingDefinition> NO_SETTINGS = Collections.emptyList();
    private static final List<ModelFile> NOT_CAPTURED = Collections.emptyList();

    private static final List<ModelDescriptor> ALL = Collections.unmodifiableList(Arrays.asList(
            // Text
            new ModelDescriptor("emo", "Emo", Group.TEXT, R.string.model_emo_summary, false,
                    false, "v0.7.0", Arrays.asList(
                    new ModelFile("emo.tflite", 10_222_736L,
                            "2c382e8da94bab64172c0694b0350abb96829bf89eb623008ed0085128621de2"),
                    new ModelFile("emo_meta.json", 6_280L,
                            "a3143258ac4099de65367cd4598073f1f5ce94f0eb505a30331762619967d3f6"),
                    new ModelFile("emo_tokenizer.bin", 733_197L,
                            "f54462a7a2344f15c9430ed4e399c49444f890dd6094c745a0c5391690bf019d")),
                    Arrays.asList(
                            range(EMO_LIMIT, R.string.setting_emo_limit, 0, 1, 5, 1, 3),
                            choice(EMO_SKIN_TONE, R.string.setting_emo_skin_tone, 0,
                                    new String[] {"default", "light", "medium_light", "medium",
                                            "medium_dark", "dark"},
                                    R.array.skin_tone_labels, "default"))),
            new ModelDescriptor("redact", "Redact", Group.TEXT, R.string.model_redact_summary,
                    false, false, "v0.4.2", Arrays.asList(
                    new ModelFile("redact.tflite", 23_929_696L,
                            "2b6237e3a08ee12966929af477fb3723018bacdb74a22f78992b8c0f6b5d545d"),
                    new ModelFile("redact_tokenizer.bin", 391_416L,
                            "81bc354dc99285e05b8f70edca9df3afbd83dfa4b0cea18d0448ab17ce58aae2"),
                    new ModelFile("labels.json", 3_842L,
                            "228835734235f85ec4bf31391a296aa6e8a36684e1171e841ac94f7b3f9b430a")),
                    Arrays.asList(
                            choice(REDACT_MODE, R.string.setting_redact_mode,
                                    R.string.setting_redact_mode_summary,
                                    new String[] {REDACT_MODE_WARN, REDACT_MODE_MASK},
                                    R.array.redact_mode_labels, REDACT_MODE_WARN),
                            toggle("cat_email", R.string.setting_redact_email, true),
                            toggle("cat_phone", R.string.setting_redact_phone, true),
                            toggle("cat_payment", R.string.setting_redact_payment, true),
                            toggle("cat_ids", R.string.setting_redact_ids, true),
                            toggle("cat_names", R.string.setting_redact_names, false),
                            toggle("cat_addresses", R.string.setting_redact_addresses, false),
                            toggle("cat_online", R.string.setting_redact_online, false),
                            range(REDACT_MIN_CONFIDENCE, R.string.setting_redact_confidence,
                                    R.string.setting_redact_confidence_summary, 50, 95, 5, 60))),
            new ModelDescriptor("toxic", "Toxic", Group.TEXT, R.string.model_toxic_summary, true,
                    false, "v0.2.2", Arrays.asList(
                    new ModelFile("litert-mixed/toxic.tflite", 44_193_264L,
                            "0dfb29e8c595bf25d7109b67ac4c7c2b7b38e150f39153d535d82b87819fce46"),
                    new ModelFile("litert-mixed/toxic_meta.json", 2_381L,
                            "db477e6fe2642e0b004808384ae29a0c19c43affa8f216d3ede1dd6cd23f6e2b"),
                    new ModelFile("toxic_tokenizer.bin", 9_412_432L,
                            "11712520c8aa365c2eecf92fa0a719fba84645bc793dafc3aeffcecdad9be80f"),
                    new ModelFile("toxic_table.bin", 49_664_021L,
                            "f9b5932abaff03daec645e6a597971c08fc68e4ece33b13d3a92238cb8347803")),
                    Arrays.asList(
                            toggle("cat_hateful", R.string.setting_toxic_hateful, true),
                            toggle("cat_abusive", R.string.setting_toxic_abusive, true),
                            toggle("cat_threat", R.string.setting_toxic_threat, true),
                            choice(TOXIC_SENSITIVITY, R.string.setting_toxic_sensitivity,
                                    R.string.setting_toxic_sensitivity_summary,
                                    new String[] {SENSITIVITY_FEWER, SENSITIVITY_STANDARD,
                                            SENSITIVITY_MORE},
                                    R.array.sensitivity_labels, SENSITIVITY_STANDARD))),
            new ModelDescriptor("tongue", "Tongue", Group.TEXT, R.string.model_tongue_summary,
                    false, true, "bundled", NOT_CAPTURED, NO_SETTINGS),
            pending("gist", "Gist", Group.TEXT, R.string.model_gist_summary, false, "v2.2.0"),
            // Voice
            pending("voz", "Voz", Group.VOICE, R.string.model_voz_summary, false, "v0.5.0"),
            pending("clear", "Clear", Group.VOICE, R.string.model_clear_summary, false, "v0.3.0"),
            pending("ear", "Ear", Group.VOICE, R.string.model_ear_summary, false, "v0.2.0"),
            pending("uhm", "Uhm", Group.VOICE, R.string.model_uhm_summary, false, "v1.2.0"),
            pending("who", "Who", Group.VOICE, R.string.model_who_summary, true, "v0.3.4"),
            // Images
            pending("eye", "Eye", Group.IMAGES, R.string.model_eye_summary, true, "v1.0.0"),
            pending("moderator", "Moderator", Group.IMAGES, R.string.model_moderator_summary,
                    false, "v1.0.0"),
            // Drawing
            pending("shapes", "Shapes", Group.DRAWING, R.string.model_shapes_summary, false,
                    "v1.0.0")));

    private ModelRegistry() {}

    // A model whose adapter and file list have not landed yet: listed, but "Coming later".
    private static ModelDescriptor pending(String id, String name, Group group, int summary,
            boolean beta, String revision) {
        return new ModelDescriptor(id, name, group, summary, beta, false, revision,
                NOT_CAPTURED, NO_SETTINGS);
    }

    public static List<ModelDescriptor> all() {
        return ALL;
    }

    public static List<ModelDescriptor> inGroup(Group group) {
        List<ModelDescriptor> models = new ArrayList<>();
        for (ModelDescriptor model : ALL) if (model.group == group) models.add(model);
        return models;
    }

    public static ModelDescriptor find(String id) {
        for (ModelDescriptor model : ALL) if (model.id.equals(id)) return model;
        return null;
    }
}

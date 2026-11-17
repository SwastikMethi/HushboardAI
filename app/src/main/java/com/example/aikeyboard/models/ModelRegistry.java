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

    // ponytail: two measurements. On an emulator the app held 3.9 GB with Voz loaded and a
    // 3 GB device killed it while loading. On a phone (OnePlus CPH2423, 12 GB) it held about
    // 5 GB, 5.9 GB with Clear loaded as well. 6.5 GiB refuses the devices that certainly
    // cannot hold that (sold as 6 GB and below, which report under 6 GiB) and admits those
    // sold as 8 GB (about 7 GiB); the crash breaker covers the uncertain middle. Measure on
    // an 8 GB phone before trusting the upper side of this line.
    private static final long VOZ_MIN_RAM_BYTES = 13L << 29;
    // The same kind of line for Eye, which peaked at about 1.5 GB: refused only below 2 GiB.
    private static final long EYE_MIN_RAM_BYTES = 2L << 30;

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
    public static final String GIST_LIMIT = "limit";
    // Both defaults are the vendor SDK's own (checked by SdkDefaultsProbeTest).
    public static final String CLEAR_STRENGTH = "strength";
    public static final int CLEAR_STRENGTH_DEFAULT = 100;
    public static final String UHM_BIAS = "bias";
    public static final String UHM_BIAS_DEFAULT = "balanced";
    /** The score (in hundredths) at which a picture is flagged: the model's own parameter. */
    public static final String MODERATOR_THRESHOLD = "threshold";
    public static final int MODERATOR_THRESHOLD_DEFAULT = 50;

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
            new ModelDescriptor("gist", "Gist", Group.TEXT, R.string.model_gist_summary, false,
                    false, "v2.2.0", Arrays.asList(
                    new ModelFile("gist.tflite", 13_034_484L,
                            "5ec5fd0f2015690432e5834ae33cfc48a11fa69a1947af3f2b8fe37e7b60ee51"),
                    new ModelFile("gist_tokenizer.bin", 4_625_812L,
                            "95081ee4239ab56b27f1df505e159a64156cbd4d700147d7ea7f50ec46cadad3"),
                    new ModelFile("gist_embedding.i8", 66_905_344L,
                            "a55b64cf8d31eac062b49c4a04848363803a03acc2cdfc8b841c4d11177629e8"),
                    new ModelFile("gist_embedding.json", 67L,
                            "74654d0da2733d645b1dce917157019eda9f4539abff544a8d2b039482b253ca"),
                    new ModelFile("gist_config.json", 823L,
                            "4a5e5a73c0e9d7e5dcee44a68316ce2b01775c1bd06ce892e7175c05ba3f3905"),
                    new ModelFile("taxonomy.json", 8_018L,
                            "ad94b05564ffd4af1e102984fbd54751b699ce7cfce49b9c8854f0feb4157d0a")),
                    Arrays.asList(
                            range(GIST_LIMIT, R.string.setting_gist_limit, 0, 1, 5, 1, 3))),
            // Voice
            new ModelDescriptor("voz", "Voz", Group.VOICE, R.string.model_voz_summary, false,
                    false, "v0.5.0", Arrays.asList(
                    new ModelFile("encoder.tflite", 1_207_525_456L,
                            "1152abb5fce2cf50b57b1cd80a1bf70320c32b20656f835bfe08c7faa5c3f885"),
                    new ModelFile("mel.tflite", 1_663_424L,
                            "505d80fc1be255c115c715a6cffdb55e51ff22465bca820e5feb59f0cccabe09"),
                    new ModelFile("decoder.tflite", 24_503_072L,
                            "429ff2b574455733b388f1667d05d6764ac6086417b94d4cc24edabd25d52591"),
                    new ModelFile("meta.litert.json", 579L,
                            "a8ad38f4144d12445dce0e32a723859816596c1c667906c062df97c94a81e741"),
                    new ModelFile("vocab.json", 78_654L,
                            "3eb2f73e8853dce5156c2ed97c0b1bb84eb4ebd201eb1761d15977fc1ea31490"),
                    new ModelFile("embedding.f16", 10_487_040L,
                            "07163e5f6f8a68f9ffeed7a8ff770c68098c2db216232321912188bd614b9a72")),
                    NO_SETTINGS, VOZ_MIN_RAM_BYTES),
            new ModelDescriptor("clear", "Clear", Group.VOICE, R.string.model_clear_summary,
                    false, false, "v0.3.0", Arrays.asList(
                    new ModelFile("clear-studio.tflite", 49_285_108L,
                            "fe2438e4e1137455298321ca952c5d3a4d7dde27eca6b8a495789723212fc79f")),
                    Arrays.asList(
                            range(CLEAR_STRENGTH, R.string.setting_clear_strength,
                                    R.string.setting_clear_strength_summary, 0, 100, 10,
                                    CLEAR_STRENGTH_DEFAULT))),
            new ModelDescriptor("ear", "Ear", Group.VOICE, R.string.model_ear_summary, false,
                    false, "v0.2.0", Arrays.asList(
                    new ModelFile("ear.tflite", 10_671_552L,
                            "a19b43892abe53afba50122a861b10fd76f312a5027a5c659b0738081135feeb"),
                    new ModelFile("languages.json", 628L,
                            "0a08f30dec26ad25730a1fa9375a5d693f5a159032d0cd7818007b7dbc3eae18"),
                    new ModelFile("ear_meta.json", 2_190L,
                            "538d66c747024d4ef2ae33aef273817c7baea21171430fef0c2713a66bb6af18"),
                    new ModelFile("mel_filters.f32", 64_328L,
                            "a3099fcba1a7f30f229b230011146ea0140db8de4f467b44aaa5de70bcc0f325")),
                    NO_SETTINGS),
            new ModelDescriptor("uhm", "Uhm", Group.VOICE, R.string.model_uhm_summary, false,
                    false, "v1.2.0", Arrays.asList(
                    new ModelFile("uhm.tflite", 23_919_472L,
                            "9bb5eaa39bb56306ca58692242117b66728ccd169abe2f215ba50063ef0e8261")),
                    Arrays.asList(
                            choice(UHM_BIAS, R.string.setting_uhm_bias,
                                    R.string.setting_uhm_bias_summary,
                                    new String[] {"precision", "balanced", "recall"},
                                    R.array.uhm_bias_labels, UHM_BIAS_DEFAULT))),
            new ModelDescriptor("who", "Who", Group.VOICE, R.string.model_who_summary, true,
                    false, "v0.3.4", Arrays.asList(
                    new ModelFile("litert-int8/who.tflite", 30_574_096L,
                            "083ab7ef3b3d3b9d7a1b5c701c91e24b5310b87caa1be20a343da6968346860e"),
                    new ModelFile("litert-int8/who_meta.json", 4_090L,
                            "6321db030dcff196a694610c047cb10f632ab95420746dae67723bd46c13c273"),
                    new ModelFile("litert-int8/who_mel_banks.f32", 23_652L,
                            "730d76d5d2099d964c705c82c4556e2b10792d287666aa97edb0633f88f08ce0"),
                    new ModelFile("litert-int8/who_voice_banks.f32", 82_248L,
                            "df33ff45ac021f5d014356b4e9ab4b9f91fadd836649962ee6e89eadfcd264e3"),
                    new ModelFile("litert-int8/who_embed.f16", 2_621_960L,
                            "2f2cb55e4370c30b3d5f0401bf421e018fcc8633f5d3aef225375704c60a8c1c")),
                    NO_SETTINGS),
            // Images
            new ModelDescriptor("eye", "Eye", Group.IMAGES, R.string.model_eye_summary, true,
                    false, "v1.0.0", Arrays.asList(
                    new ModelFile("eye.tflite", 66_038_240L,
                            "25ba485c959a47961ac9243e74e6ad4997bfc88e1267f62aa88827fc9d92894b"),
                    new ModelFile("eye_meta.json", 280_890L,
                            "3678669c840378bca191250597e3a77e5b478e12d3e70c48f827b860551f791c")),
                    NO_SETTINGS, EYE_MIN_RAM_BYTES),
            new ModelDescriptor("moderator", "Moderator", Group.IMAGES,
                    R.string.model_moderator_summary, false, false, "v1.0.0", Arrays.asList(
                    new ModelFile("moderator.tflite", 9_618_016L,
                            "925d11966ea9086acbfeaf4d00c1dd30c738c531695f2004b68880d3f121cd89")),
                    Arrays.asList(
                            range(MODERATOR_THRESHOLD, R.string.setting_moderator_threshold,
                                    R.string.setting_moderator_threshold_summary, 0, 100, 5,
                                    MODERATOR_THRESHOLD_DEFAULT))),
            // Drawing
            new ModelDescriptor("shapes", "Shapes", Group.DRAWING,
                    R.string.model_shapes_summary, false, false, "v1.0.0", Arrays.asList(
                    new ModelFile("shapes.tflite", 974_896L,
                            "8274742f91341e54ae64df4c561a19e5fb4cf591d6b305290d129068f56ef6b9"),
                    new ModelFile("shapes_meta.json", 1_551L,
                            "d570c85233d505f6e19b38b512b81ff735182728240d56e6e87782f5efe1c6a4")),
                    NO_SETTINGS)));

    private ModelRegistry() {}

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

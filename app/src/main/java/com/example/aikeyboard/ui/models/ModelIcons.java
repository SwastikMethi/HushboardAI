// Which drawn icon stands for which model, wherever a model is listed (the catalog and the
// keyboard's AI panel).
package com.example.aikeyboard.ui.models;

import com.example.aikeyboard.R;

public final class ModelIcons {

    private ModelIcons() {}

    public static int of(String modelId) {
        switch (modelId) {
            case "emo":
                return R.drawable.ic_model_emo;
            case "redact":
                return R.drawable.ic_model_redact;
            case "toxic":
                return R.drawable.ic_model_toxic;
            case "tongue":
                return R.drawable.ic_model_tongue;
            case "gist":
                return R.drawable.ic_model_gist;
            case "voz":
                return R.drawable.ic_model_voz;
            case "clear":
                return R.drawable.ic_model_clear;
            case "ear":
                return R.drawable.ic_model_ear;
            case "uhm":
                return R.drawable.ic_model_uhm;
            case "who":
                return R.drawable.ic_model_who;
            case "eye":
                return R.drawable.ic_model_eye;
            case "moderator":
                return R.drawable.ic_model_moderator;
            default:
                return R.drawable.ic_model_shapes;
        }
    }
}

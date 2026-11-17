// The words shown for each kind of finding, shared by the keyboard's review panel and the
// companion app's "Try it" field.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.R;

public final class NoticeText {

    private NoticeText() {}

    /** String resource naming a personal-information category. */
    public static int categoryName(String category) {
        switch (category) {
            case "email":
                return R.string.category_email;
            case "phone":
                return R.string.category_phone;
            case "payment":
                return R.string.category_payment;
            case "ids":
                return R.string.category_ids;
            case "names":
                return R.string.category_names;
            case "addresses":
                return R.string.category_addresses;
            default:
                return R.string.category_online;
        }
    }

    /** String resource naming a kind of flagged wording. */
    public static int toneName(String tone) {
        switch (tone) {
            case "hateful":
                return R.string.tone_hateful;
            case "threat":
                return R.string.tone_threat;
            default:
                return R.string.tone_abusive;
        }
    }
}

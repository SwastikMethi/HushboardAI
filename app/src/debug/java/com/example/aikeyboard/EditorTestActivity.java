// Debug-only host screen with one field per editor type the keyboard must handle:
// plain, one that takes pictures, multi-line, password, no-learning, and each enter action.
package com.example.aikeyboard;

import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

public class EditorTestActivity extends AppCompatActivity {

    /** The last editor action any field received, so tests can see what Enter did. */
    public static volatile int lastEditorAction = -1;
    /** The last picture a keyboard handed to the field that takes pictures, and its type. */
    public static volatile Uri lastContentUri;
    public static volatile String lastContentMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor_test);
        int[] actionFields = {R.id.field_send, R.id.field_search, R.id.field_next,
                R.id.field_done, R.id.field_go};
        for (int id : actionFields) {
            ((EditText) findViewById(id)).setOnEditorActionListener((view, actionId, event) -> {
                lastEditorAction = actionId;
                return true;
            });
        }
        // Declares the field as taking images, and records what arrives.
        ViewCompat.setOnReceiveContentListener(findViewById(R.id.field_rich),
                new String[] {"image/*"}, (view, payload) -> {
                    ClipData clip = payload.getClip();
                    lastContentUri = clip.getItemAt(0).getUri();
                    lastContentMime = clip.getDescription().getMimeType(0);
                    return null; // all of it was taken
                });
    }
}

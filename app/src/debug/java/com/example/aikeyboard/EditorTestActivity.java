// Debug-only host screen with one field per editor type the keyboard must handle:
// plain, multi-line, password, no-learning, and each enter action.
package com.example.aikeyboard;

import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class EditorTestActivity extends AppCompatActivity {

    /** The last editor action any field received, so tests can see what Enter did. */
    public static volatile int lastEditorAction = -1;

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
    }
}

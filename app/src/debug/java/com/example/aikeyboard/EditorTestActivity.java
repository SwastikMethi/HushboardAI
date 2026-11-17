// Debug-only host screen with one field per editor type the keyboard must handle:
// plain, multi-line, password, no-learning, and each enter action.
package com.example.aikeyboard;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class EditorTestActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor_test);
    }
}

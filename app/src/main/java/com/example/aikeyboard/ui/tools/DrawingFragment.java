// Drawing: the user draws one stroke, the shape model says what it is and the tidy version
// is drawn over it. Clear starts again; Share sends the tidy drawing through the system
// sheet. This tidies shapes. It does not read handwriting.
package com.example.aikeyboard.ui.tools;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.databinding.FragmentDrawingBinding;
import com.example.aikeyboard.media.ShareResultController;

import java.io.IOException;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public final class DrawingFragment extends Fragment {

    private FragmentDrawingBinding views;
    private AppContainer app;
    private ShapeRecognizer recognizer;
    private Disposable settingsSubscription;
    // What the status line says about the current drawing; the hint when there is none.
    private int status = R.string.drawing_hint;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        app = AppContainer.from(requireContext());
        recognizer = new ShapeRecognizer(requireContext());
        views = FragmentDrawingBinding.inflate(inflater, container, false);
        views.strokeSurface.setListener(stroke -> {
            if (!recognizer.usable()) return;
            status = R.string.drawing_working;
            render();
            recognizer.recognise(stroke, (shape, failed) -> {
                if (views == null) return;
                views.strokeSurface.setShape(shape);
                status = failed ? R.string.drawing_failed
                        : shape == null ? R.string.drawing_unclear
                        : ShapeRecognizer.nameOf(shape.kind);
                render();
            });
        });
        views.drawingClear.setOnClickListener(v -> {
            recognizer.forget();
            views.strokeSurface.clear();
            status = R.string.drawing_hint;
            render();
        });
        views.drawingShare.setOnClickListener(v -> share());
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        app.models.statuses().observe(getViewLifecycleOwner(), statuses -> render());
        // Disposed with the view; the stream arrives on a background thread.
        settingsSubscription = app.settings.observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(latest -> render());
    }

    @Override
    public void onDestroyView() {
        settingsSubscription.dispose();
        recognizer.forget();
        views = null;
        super.onDestroyView();
    }

    private void render() {
        if (views == null) return;
        boolean usable = recognizer.usable();
        views.strokeSurface.setEnabled(usable);
        views.drawingStatus.setText(usable ? status
                : app.settings.latest().aiEnabled() ? R.string.drawing_no_model
                : R.string.tools_paused);
        boolean drawn = !views.strokeSurface.isEmpty();
        views.drawingClear.setEnabled(drawn);
        // Only a drawing that was looked at can be shared: recognised, or kept as drawn.
        views.drawingShare.setEnabled(usable && drawn && status != R.string.drawing_working);
    }

    private void share() {
        Bitmap drawing = views.strokeSurface.render();
        Context context = requireContext().getApplicationContext();
        app.io.execute(() -> {
            try {
                Intent share = ShareResultController.shareIntent(
                        ShareResultController.writePicture(context, drawing,
                                ShareResultController.PNG), ShareResultController.PNG);
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (isAdded()) startActivity(Intent.createChooser(share, null));
                });
            } catch (IOException e) {
                ContextCompat.getMainExecutor(context).execute(() -> {
                    status = R.string.image_share_failed;
                    render();
                });
            }
        });
    }
}

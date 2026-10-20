package com.folio.gallery;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A simple built-in viewer; no uploads, caching to disk, or destructive actions. */
public class PhotoViewerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0B0D14);
        getWindow().setNavigationBarColor(0xFF0B0D14);
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(0xFF0B0D14);
        setContentView(view);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        TextView back = new TextView(this);
        back.setText("‹  BACK TO GALLERY");
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setTextColor(0xFF80E7DA);
        back.setTextSize(14);
        back.setPadding(26, 18, 18, 18);
        back.setOnClickListener(v -> finish());
        view.addView(back, new LinearLayout.LayoutParams(-1, 56));
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView caption = new TextView(this);
        caption.setText(getIntent().getStringExtra("name"));
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(14);
        caption.setSingleLine();
        caption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(20, 12, 20, 12);
        view.addView(caption, new LinearLayout.LayoutParams(-1, 54));
        String address = getIntent().getStringExtra("uri");
        if (address != null) worker.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(Uri.parse(address), new Size(2048, 2048), null);
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) image.setImageBitmap(b); });
            } catch (Exception ex) {
                handler.post(() -> caption.setText("Cannot open photo: permission or file unavailable"));
            }
        });
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}

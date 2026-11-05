package dev.zakocoder.app.kernel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit user-visible activation only. Never consumes configuration or starts the proxy. */
public final class KernelActivationActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        addText(content, R.string.activation_title, 24, 0);
        addText(content, R.string.activation_body, 15, 24);
        addText(content, R.string.activation_limits, 15, 16);
        Button done = new Button(this);
        done.setText(R.string.activation_done);
        done.setMinHeight(dp(48));
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, -2);
        doneParams.topMargin = dp(32);
        content.addView(done, doneParams);
        Button settings = new Button(this, null, android.R.attr.borderlessButtonStyle);
        settings.setText(R.string.activation_settings);
        settings.setMinHeight(dp(48));
        settings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                // Some managed devices do not expose application settings; keep this page usable.
            }
        });
        content.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private void addText(LinearLayout content, int resource, int size, int top) {
        TextView text = new TextView(this);
        text.setText(resource);
        text.setTextSize(size);
        text.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        content.addView(text, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

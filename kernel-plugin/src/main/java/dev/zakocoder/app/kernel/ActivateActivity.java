package dev.zakocoder.app.kernel;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal activation screen for the optional kernel plugin.
 *
 * Launching the plugin once clears Android's "stopped" package state (and gives OnePlus/ColorOS
 * users an explicit entry to allow associated startup), which is required before another app can
 * bind {@link KernelService} on some OEM builds. The screen intentionally has no configuration or
 * credentials; it only explains how to go back and enable nodes.
 */
public final class ActivateActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("ZakoCoder 内核插件已激活");
        title.setTextSize(20);
        layout.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请返回 ZakoCoder 的「网络代理」页面重新启用节点。\n"
                + "如果系统提示关联启动/自启动被限制，请在系统设置中允许 ZakoCoder 与「ZakoCoder 内核插件」相互启动。");
        hint.setTextSize(14);
        hint.setPadding(0, padding / 2, 0, 0);
        layout.addView(hint);

        setContentView(layout);
    }
}

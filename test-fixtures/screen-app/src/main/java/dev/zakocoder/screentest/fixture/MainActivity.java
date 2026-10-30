package dev.zakocoder.screentest.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;

/** Separate disposable app, never shipped or seeded into user projects. */
public final class MainActivity extends Activity {
    private int counter;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 100, 24, 24);
        TextView status = new TextView(this);
        status.setText("Counter: 0"); status.setTextSize(22); status.setId(1001);
        column.addView(status);
        Button add = new Button(this); add.setText("Increment"); add.setAllCaps(false); add.setId(1002);
        add.setOnClickListener(view -> status.setText("Counter: " + (++counter)));
        column.addView(add);
        EditText name = new EditText(this); name.setHint("Name"); name.setId(1003);
        name.setSingleLine(true); column.addView(name);
        Button greet = new Button(this); greet.setText("Greet"); greet.setAllCaps(false); greet.setId(1004);
        TextView greeting = new TextView(this); greeting.setId(1005);
        greet.setOnClickListener(view -> greeting.setText("Hello " + name.getText()));
        column.addView(greet); column.addView(greeting);
        Button sensitive = new Button(this); sensitive.setText("Password page"); sensitive.setAllCaps(false);
        sensitive.setOnClickListener(view -> {
            EditText password = new EditText(this);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setText("fixture-secret"); column.addView(password); sensitive.setVisibility(View.GONE);
        });
        column.addView(sensitive);
        setContentView(column);
    }
}

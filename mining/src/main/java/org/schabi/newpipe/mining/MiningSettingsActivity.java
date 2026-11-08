package org.schabi.newpipe.mining;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Opens the mining settings on their own, for the panel's Settings button (NewPipe's settings
 * activity can't be opened at a specific screen). Uses the app's theme.
 */
public class MiningSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setId(R.id.mining_settings_container);
        root.setFitsSystemWindows(true);
        setContentView(root);
        setTitle(R.string.pref_mining_header);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mining_settings_container, new MiningSettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}

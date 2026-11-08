package org.schabi.newpipe.mining;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;

/**
 * "Sentence mining" settings (AnkiDroid status and access, note type, deck, notices) on their own,
 * shown by {@link MiningSettingsActivity}. NewPipe's settings screen has its own fragment in
 * {@code :app} with the same XML and {@link MiningPreferences}.
 */
public class MiningSettingsFragment extends PreferenceFragmentCompat {

    // A field, so the permission launcher is registered before onCreate.
    private final MiningPreferences prefs = new MiningPreferences(this);

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.mining_settings);
        prefs.bind();
    }

    @Override
    public void onResume() {
        super.onResume();
        // AnkiDroid may have been installed, granted or changed while we were away.
        prefs.refresh();
    }
}

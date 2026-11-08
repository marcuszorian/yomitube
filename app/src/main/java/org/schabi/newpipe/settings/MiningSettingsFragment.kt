package org.schabi.newpipe.settings

import android.os.Bundle
import org.schabi.newpipe.mining.MiningPreferences

/**
 * Mining fork: the "Sentence mining" screen inside NewPipe's settings. The preferences and their
 * logic live in the `:mining` module ([MiningPreferences], `R.xml.mining_settings`).
 */
class MiningSettingsFragment : BasePreferenceFragment() {

    // A property, so the permission launcher is registered before onCreate.
    private val prefs = MiningPreferences(this)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        prefs.bind()
    }

    override fun onResume() {
        super.onResume()
        // AnkiDroid may have been installed, granted or changed while we were away.
        prefs.refresh()
    }
}

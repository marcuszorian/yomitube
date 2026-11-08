package org.schabi.newpipe.settings

import android.os.Bundle
import org.schabi.newpipe.util.ThemeHelper

/**
 * Mining fork: the mining settings opened straight from the mining panel, in NewPipe's settings
 * theme (light, dark or black, as the user chose).
 */
class MiningSettingsActivity : org.schabi.newpipe.mining.MiningSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.getSettingsThemeStyle(this))
        super.onCreate(savedInstanceState)
    }
}

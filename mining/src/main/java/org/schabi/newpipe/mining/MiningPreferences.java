package org.schabi.newpipe.mining;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;



import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the "Sentence mining" category of the settings screen. Create it as a field of the
 * PreferenceFragmentCompat (so the permission launcher is registered before onCreate), then call
 * {@link #bind()} from onCreatePreferences and {@link #refresh()} from onResume.
 */
public final class MiningPreferences {

    public static final String KEY_PERMISSION = "ankiPermission";
    public static final String KEY_STATUS = "ankiStatus";
    public static final String KEY_ATTRIBUTION = "miningAttribution";
    /** :app's themed subclass of {@link MiningSettingsActivity} (declared in its manifest). */
    private static final String APP_SETTINGS_ACTIVITY =
            "org.schabi.newpipe.settings.MiningSettingsActivity";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final PreferenceFragmentCompat fragment;
    private final ActivityResultLauncher<String> permissionLauncher;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MiningPreferences(@NonNull PreferenceFragmentCompat fragment) {
        this.fragment = fragment;
        this.permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> refresh());
    }

    public void bind() {
        ListPreference model = fragment.findPreference(AnkiExporter.PREF_KEY_MODEL_ID);
        ListPreference deck = fragment.findPreference(AnkiExporter.PREF_KEY_DECK_ID);
        Preference permission = fragment.findPreference(KEY_PERMISSION);
        if (model == null || deck == null || permission == null) {
            return;
        }
        Preference attribution = fragment.findPreference(KEY_ATTRIBUTION);
        if (attribution != null) {
            attribution.setOnPreferenceClickListener(p -> {
                showNotices();
                return true;
            });
        }
        Preference.OnPreferenceChangeListener refreshAfterChange = (preference, newValue) -> {
            mainHandler.post(this::refresh);
            return true;
        };
        model.setOnPreferenceChangeListener(refreshAfterChange);
        deck.setOnPreferenceChangeListener(refreshAfterChange);
        permission.setOnPreferenceClickListener(p -> {
            Context context = fragment.getContext();
            if (context == null) {
                return true;
            }
            AnkiExporter exporter = new AnkiExporter(context);
            if (!exporter.isInstalled()) {
                openAnkiDroidStore(context);
            } else if (!exporter.hasPermission()) {
                if (fragment.shouldShowRequestPermissionRationale(AnkiExporter.PERMISSION)
                        || !wasPermissionAsked(context)) {
                    markPermissionAsked(context);
                    permissionLauncher.launch(AnkiExporter.PERMISSION);
                } else {
                    // Permanently denied: the system dialog won't show again.
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.getPackageName(), null));
                    fragment.startActivity(intent);
                }
            }
            return true;
        });
    }

    /** Reloads note type/deck lists and the status line in the background. */
    public void refresh() {
        Context ctx = fragment.getContext();
        if (ctx == null) {
            return;
        }
        final Context context = ctx.getApplicationContext();
        setSummary(KEY_STATUS, context.getString(R.string.pref_anki_status_checking));
        EXECUTOR.execute(() -> {
            AnkiExporter exporter = new AnkiExporter(context);
            boolean installed = exporter.isInstalled();
            boolean permitted = installed && exporter.hasPermission();
            List<AnkiExporter.Item> models = null;
            List<AnkiExporter.Item> decks = null;
            if (permitted) {
                models = exporter.getModels();
                decks = exporter.getDecks();
                if (models != null && exporter.getModelId() < 0) {
                    long suggested = exporter.findCompatibleModelId();
                    if (suggested >= 0) {
                        exporter.setModelId(suggested);
                    }
                }
            }
            AnkiExporter.StatusInfo status = exporter.checkStatus();
            final List<AnkiExporter.Item> fModels = models;
            final List<AnkiExporter.Item> fDecks = decks;
            final long modelId = exporter.getModelId();
            mainHandler.post(() -> apply(context, installed, permitted, fModels, fDecks, modelId, status));
        });
    }

    private void apply(Context context, boolean installed, boolean permitted,
                       @Nullable List<AnkiExporter.Item> models, @Nullable List<AnkiExporter.Item> decks,
                       long modelId, AnkiExporter.StatusInfo status) {
        if (fragment.getContext() == null) {
            return;
        }
        ListPreference model = fragment.findPreference(AnkiExporter.PREF_KEY_MODEL_ID);
        ListPreference deck = fragment.findPreference(AnkiExporter.PREF_KEY_DECK_ID);
        Preference permission = fragment.findPreference(KEY_PERMISSION);
        if (model == null || deck == null || permission == null) {
            return;
        }
        fill(model, models);
        if (modelId >= 0) {
            model.setValue(Long.toString(modelId));
        }
        fill(deck, decks);

        permission.setVisible(!permitted);
        permission.setTitle(installed ? R.string.pref_anki_permission : R.string.pref_anki_install);

        setSummary(KEY_STATUS, status.message);
    }

    private static void fill(ListPreference pref, @Nullable List<AnkiExporter.Item> items) {
        if (items == null) {
            pref.setEnabled(false);
            return;
        }
        CharSequence[] entries = new CharSequence[items.size()];
        CharSequence[] values = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            entries[i] = items.get(i).name;
            values[i] = Long.toString(items.get(i).id);
        }
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setEnabled(!items.isEmpty());
        // Re-set the value so the simple summary provider picks up the new entries.
        String value = pref.getValue();
        if (value != null) {
            pref.setValue(value);
        }
    }

    private void setSummary(String key, CharSequence summary) {
        Preference pref = fragment.findPreference(key);
        if (pref != null) {
            pref.setSummary(summary);
        }
    }

    private static final String PREF_PERMISSION_ASKED = "ankiPermissionAsked";

    private static boolean wasPermissionAsked(Context context) {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_PERMISSION_ASKED, false);
    }

    private static void markPermissionAsked(Context context) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_PERMISSION_ASKED, true).apply();
    }

    private void openAnkiDroidStore(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.ichi2.anki"));
        try {
            fragment.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            try {
                fragment.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/ankidroid/Anki-Android/releases")));
            } catch (android.content.ActivityNotFoundException ignored) {
            }
        }
    }

    /** Opens "Sources and licences" as its own screen, as the JMdict licence asks. */
    private void showNotices() {
        Context context = fragment.requireContext();
        // The :app subclass of MiningSettingsActivity carries NewPipe's settings theme.
        Intent intent = new Intent().setClassName(context, APP_SETTINGS_ACTIVITY);
        intent.putExtra(MiningSettingsActivity.EXTRA_SOURCES, true);
        fragment.startActivity(intent);
    }
}

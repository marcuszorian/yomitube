package org.schabi.newpipe.mining;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Sources and licences": the JMdict credit on a separate screen reached from a menu, as the
 * EDRDG licence requires, plus the installed dictionary version and the Kuromoji/IPADIC notices.
 */
public class SourcesFragment extends Fragment {

    private static final String TAG = "MiningSources";
    private static final String NOTICE_ASSET = "mining_kuromoji_notice.txt";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Nullable
    private TextView version;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        int pad = dp(context, 20);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        column.addView(heading(context, R.string.mining_sources_dictionary_heading));
        column.addView(body(context, getString(R.string.pref_mining_notices_jmdict)));
        version = body(context, getString(R.string.mining_sources_dictionary_version_loading));
        column.addView(version);

        TextView tokenizer = heading(context, R.string.mining_sources_tokenizer_heading);
        tokenizer.setPadding(0, pad * 2, 0, 0);
        column.addView(tokenizer);
        column.addView(body(context, readAsset(context)));

        ScrollView scroll = new ScrollView(context);
        scroll.setId(View.generateViewId()); // keeps the scroll position on rotation
        scroll.addView(column);
        loadVersion(context.getApplicationContext());
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.pref_mining_attribution);
    }

    @Override
    public void onDestroyView() {
        version = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /** The database may still be copying on first run, so read its version off the main thread. */
    private void loadVersion(Context app) {
        worker.execute(() -> {
            String text;
            try {
                Dictionary d = Dictionary.get(app);
                String tag = d.meta("release_tag");
                if (tag.isEmpty()) tag = d.meta("jmdict_version");
                String date = d.meta("dict_date");
                text = tag.isEmpty() && date.isEmpty()
                        ? app.getString(R.string.mining_sources_dictionary_version_unknown)
                        : app.getString(R.string.mining_sources_dictionary_version,
                                tag.isEmpty() ? "?" : tag, date.isEmpty() ? "?" : date);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read the dictionary version", e);
                text = app.getString(R.string.mining_sources_dictionary_version_unknown);
            }
            final String result = text;
            View root = getView();
            if (root != null) {
                root.post(() -> {
                    if (version != null) version.setText(result);
                });
            }
        });
    }

    private static TextView heading(Context context, int text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        v.setPadding(0, 0, 0, dp(context, 8));
        return v;
    }

    private static TextView body(Context context, String text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextIsSelectable(true);
        v.setPadding(0, 0, 0, dp(context, 12));
        Linkify.addLinks(v, Linkify.WEB_URLS);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        return v;
    }

    private static int dp(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    private static String readAsset(Context context) {
        try (InputStream in = context.getAssets().open(NOTICE_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.w(TAG, "Cannot read " + NOTICE_ASSET, e);
            return "";
        }
    }
}

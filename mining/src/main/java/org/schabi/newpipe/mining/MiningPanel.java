package org.schabi.newpipe.mining;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.RangeSlider;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The mining screen: a panel over the paused, dimmed video. Shows one caption line as word chips,
 * looks up the selected word, lets the user trim the line's audio with live preview on the real
 * player, and saves the card to AnkiDroid.
 *
 * <p>All tokenizing, dictionary lookups and AnkiDroid calls run on a private worker thread; UI
 * updates happen on the main thread. Results from superseded requests are dropped via generation
 * counters.
 */
public final class MiningPanel {

    private static final String TAG = "MiningPanel";
    private static final long PREVIEW_POLL_MS = 25;

    public interface Listener {
        /** Panel is gone (saved, cancelled, or closed by the activity). */
        void onClosed();

        void onOpenSettings();
    }

    private final Activity activity;
    private final Context ui;
    private final ViewGroup container;
    private final PlaybackControl player;
    private final List<Cue> cues;
    private final Listener listener;
    @Nullable
    private final AudioClipper.AudioSource audioSource;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mining-panel");
        t.setDaemon(true);
        return t;
    });
    private final AnkiExporter exporter;
    private final AudioClipper clipper;

    // Views
    private final View root;
    private final View card;
    private final TextView lineInfo;
    private final ImageButton prevButton;
    private final ImageButton nextButton;
    private final FlowLayout chips;
    private final EditText sentenceEdit;
    private final MaterialButton editSentenceButton;
    private final View[] selectionButtons;
    private final TextView selectionText;
    private final MaterialButton otherMeanings;
    private final EditText frontEdit;
    private final EditText glossaryEdit;
    private final TextView trimTimes;
    private final RangeSlider trimSlider;
    private final MaterialButton playButton;
    private final TextView status;
    private final android.widget.CompoundButton pictureInclude;
    private final android.widget.ImageView pictureView;
    private final MaterialButton pictureRetake;
    private final android.widget.CompoundButton wordAudioInclude;
    private final ImageButton wordSpeak;
    private final WordSpeech speech;
    private final MaterialButton settingsButton;
    private final ProgressBar progress;
    private final MaterialButton addButton;

    private final int highlightBg;
    private final int highlightFg;
    private final int chipFg;
    private final int dimFg;

    // Playback state to restore on close
    private final long pausedPositionMs;
    private final boolean wasPlaying;

    // Draft state
    private int cueIndex;
    private String sentence = "";
    private List<Token> tokens = Collections.emptyList();
    private int selStart = -1;
    private int selEnd = -1;
    @Nullable
    private WordLookup.Result lookup;
    private TrimWindow window;
    private long trimStart;
    private long trimEnd;
    private boolean editingSentence;
    private boolean saving;
    private boolean closed;
    /** Current screenshot for the Picture field, or null. Deleted when replaced or discarded. */
    @Nullable
    private File pictureFile;
    /** Bumped per capture request; older captures that finish late are discarded. */
    private int pictureGeneration;
    /** Capture the frame once the player has rendered the seek target. */
    private final Runnable captureAfterSeek = this::capturePicture;

    /** Bumped when the line/sentence changes; tokenize results for older ones are dropped. */
    private int sentenceGeneration;
    /** Bumped on every selection change; lookup results for older selections are dropped. */
    private int lookupGeneration;
    /** Bumped when a save starts or the draft changes; stale save steps are dropped. */
    private int saveGeneration;

    // Preview playback
    private long previewStopAt = -1;
    /** Throttles seeks while a trim handle is dragged. */
    private long lastPreviewSeekAt;
    private long pendingPreviewFrom = -1;
    private long pendingPreviewUntil = -1;
    private final Runnable pendingPreview = () -> {
        if (pendingPreviewFrom >= 0) startPreview(pendingPreviewFrom, pendingPreviewUntil);
    };
    private final Runnable previewMonitor = new Runnable() {
        @Override
        public void run() {
            if (previewStopAt < 0) return;
            if (player.getPosition() >= previewStopAt) {
                stopPreview();
            } else {
                main.postDelayed(this, PREVIEW_POLL_MS);
            }
        }
    };

    @Nullable
    private Object backCallback;

    public MiningPanel(Activity activity, ViewGroup container, PlaybackControl player, List<Cue> cues,
                       int cueIndex, @Nullable AudioClipper.AudioSource audioSource,
                       DataSource.Factory httpDataSourceFactory, Listener listener) {
        this.activity = activity;
        this.ui = new ContextThemeWrapper(activity, R.style.Theme_Mining);
        this.container = container;
        this.player = player;
        this.cues = cues;
        this.cueIndex = cueIndex;
        this.audioSource = audioSource;
        this.listener = listener;
        this.exporter = new AnkiExporter(activity);
        this.clipper = new AudioClipper(httpDataSourceFactory);

        pausedPositionMs = player.getPosition();
        wasPlaying = player.isPlaying();

        root = LayoutInflater.from(ui).inflate(R.layout.mining_panel, container, false);
        card = root.findViewById(R.id.mining_card);
        lineInfo = root.findViewById(R.id.mining_line_info);
        prevButton = root.findViewById(R.id.mining_prev);
        nextButton = root.findViewById(R.id.mining_next);
        chips = root.findViewById(R.id.mining_chips);
        sentenceEdit = root.findViewById(R.id.mining_sentence_edit);
        editSentenceButton = root.findViewById(R.id.mining_edit_sentence);
        selectionButtons = new View[]{
                root.findViewById(R.id.mining_start_left), root.findViewById(R.id.mining_start_right),
                root.findViewById(R.id.mining_end_left), root.findViewById(R.id.mining_end_right)};
        selectionText = root.findViewById(R.id.mining_selection);
        otherMeanings = root.findViewById(R.id.mining_other_meanings);
        frontEdit = root.findViewById(R.id.mining_front);
        glossaryEdit = root.findViewById(R.id.mining_glossary);
        trimTimes = root.findViewById(R.id.mining_trim_times);
        trimSlider = root.findViewById(R.id.mining_trim);
        playButton = root.findViewById(R.id.mining_play);
        status = root.findViewById(R.id.mining_status);
        pictureInclude = root.findViewById(R.id.mining_picture_include);
        pictureView = root.findViewById(R.id.mining_picture);
        pictureRetake = root.findViewById(R.id.mining_picture_retake);
        wordAudioInclude = root.findViewById(R.id.mining_word_audio_include);
        wordSpeak = root.findViewById(R.id.mining_word_speak);
        speech = new WordSpeech(activity, this::onSpeechStateKnown);
        settingsButton = root.findViewById(R.id.mining_settings);
        progress = root.findViewById(R.id.mining_progress);
        addButton = root.findViewById(R.id.mining_add);

        highlightBg = themeColor(com.google.android.material.R.attr.colorPrimaryContainer);
        highlightFg = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
        chipFg = themeColor(com.google.android.material.R.attr.colorOnSurface);
        dimFg = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant);

        wireViews();
    }

    private int themeColor(int attr) {
        TypedValue tv = new TypedValue();
        if (ui.getTheme().resolveAttribute(attr, tv, true)) {
            return tv.resourceId != 0 ? ui.getColor(tv.resourceId) : tv.data;
        }
        return Color.WHITE;
    }

    // ------------------------------------------------------------------ open / close

    @MainThread
    public void open() {
        player.pause();
        container.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyOrientationLayout();
        // Re-fit the card on rotation; NewPipe's activity handles rotation itself, so there is
        // no configuration-change callback to forward.
        root.addOnLayoutChangeListener(orientationListener);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = BackCompat.register(activity, () -> close(true));
        }
        root.requestApplyInsets();
        showLine(cueIndex);
    }

    /**
     * Discards the draft, puts the player back at the paused position and, if {@code resume} and it
     * was playing before, resumes playback.
     */
    @MainThread
    public void close(boolean resume) {
        if (closed) return;
        closed = true;
        stopPreview();
        clipper.cancel();
        worker.shutdownNow();
        speech.shutdown();
        hideKeyboard();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            BackCompat.unregister(activity, backCallback);
        }
        root.removeOnLayoutChangeListener(orientationListener);
        container.removeView(root);
        main.removeCallbacks(captureAfterSeek);
        pictureGeneration++;
        setPicture(null, null);
        player.seekTo(pausedPositionMs);
        if (resume && wasPlaying) player.play();
        listener.onClosed();
    }

    /** Puts the player back at the paused position (activity is pausing and saves position). */
    public void restorePosition() {
        stopPreview();
        player.seekTo(pausedPositionMs);
    }

    public void onConfigurationChanged() {
        applyOrientationLayout();
    }

    /** Landscape: panel on the right with the video visible on the left. Portrait: bottom sheet. */
    private int laidOutOrientation;
    private final View.OnLayoutChangeListener orientationListener =
            (v, l, t, r, b, ol, ot, or, ob) -> {
                int o = v.getResources().getConfiguration().orientation;
                if (o != laidOutOrientation) {
                    // Not during layout: post the re-layout.
                    main.post(this::applyOrientationLayout);
                }
            };

    private void applyOrientationLayout() {
        laidOutOrientation = activity.getResources().getConfiguration().orientation;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        float d = activity.getResources().getDisplayMetrics().density;
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            lp.width = (int) Math.min(w * 0.62f, 640 * d);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = (int) (h * 0.68f);
            lp.gravity = Gravity.BOTTOM;
        }
        card.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ wiring

    private void wireViews() {
        // Tap on the dimmed area outside the card closes the panel.
        root.setOnClickListener(v -> {
            if (!saving) close(true);
        });
        card.setOnClickListener(v -> { /* swallow */ });
        root.findViewById(R.id.mining_close).setOnClickListener(v -> close(true));
        prevButton.setOnClickListener(v -> showLine(cueIndex - 1));
        nextButton.setOnClickListener(v -> showLine(cueIndex + 1));

        editSentenceButton.setOnClickListener(v -> toggleSentenceEdit());
        selectionButtons[0].setOnClickListener(v -> adjustSelection(true, -1));
        selectionButtons[1].setOnClickListener(v -> adjustSelection(true, +1));
        selectionButtons[2].setOnClickListener(v -> adjustSelection(false, -1));
        selectionButtons[3].setOnClickListener(v -> adjustSelection(false, +1));
        otherMeanings.setOnClickListener(this::showOtherMeanings);

        trimSlider.setLabelFormatter(v -> TrimWindow.formatTime((long) v, v >= 60_000));
        trimSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            List<Float> vals = slider.getValues();
            long newStart = window.clampStart((long) (float) vals.get(0), trimEnd);
            long newEnd = window.clampEnd((long) (float) vals.get(1), newStart);
            boolean startMoved = newStart != trimStart;
            boolean endMoved = newEnd != trimEnd;
            trimStart = newStart;
            trimEnd = newEnd;
            updateTrimText();
            int thumb = slider.getActiveThumbIndex();
            if (thumb == 0 || (thumb < 0 && startMoved)) {
                // Start handle: play forward from it.
                requestPreview(trimStart, trimEnd);
            } else if (thumb == 1 || endMoved) {
                // End handle: play the stretch leading up to it.
                requestPreview(TrimWindow.endPreviewStart(trimEnd, trimStart), trimEnd);
            }
        });
        playButton.setOnClickListener(v -> startPreview(trimStart, trimEnd));
        wordSpeak.setOnClickListener(v -> {
            stopPreview();
            if (speech.getState() == WordSpeech.State.READY) {
                speech.speak(Furigana.toSpeech(frontEdit.getText().toString()));
            } else {
                showSpeechProblem();
            }
        });
        pictureRetake.setOnClickListener(v -> {
            stopPreview();
            capturePicture();
        });
        pictureInclude.setOnCheckedChangeListener((b, checked) -> pictureView.setAlpha(checked ? 1f : 0.3f));

        addButton.setOnClickListener(v -> save());
        settingsButton.setOnClickListener(v -> listener.onOpenSettings());

        // Keep the card above the keyboard and clear of system bars / cutouts.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    // ------------------------------------------------------------------ line

    private void showLine(int index) {
        if (index < 0 || index >= cues.size() || saving) return;
        if (editingSentence) toggleSentenceEdit(false);
        stopPreview();
        cueIndex = index;
        Cue cue = cues.get(index);
        lineInfo.setText(ui.getString(R.string.mining_line_info, index + 1, cues.size()));
        prevButton.setEnabled(index > 0);
        prevButton.setAlpha(index > 0 ? 1f : 0.3f);
        nextButton.setEnabled(index < cues.size() - 1);
        nextButton.setAlpha(index < cues.size() - 1 ? 1f : 0.3f);

        window = TrimWindow.around(cue, TrimWindow.PADDING_MS, player.getDuration());
        trimStart = window.defaultStart(cue);
        trimEnd = window.defaultEnd(cue);
        trimSlider.setValueFrom(window.startMs);
        trimSlider.setValueTo(window.endMs);
        trimSlider.setMinSeparationValue(TrimWindow.MIN_LENGTH_MS);
        trimSlider.setValues((float) trimStart, (float) trimEnd);
        updateTrimText();

        // Show the line's frame behind the panel, and use it as the card's picture.
        player.seekTo(cue.startMs);
        main.removeCallbacks(captureAfterSeek);
        main.postDelayed(captureAfterSeek, 400);

        setSentence(cue.text);
    }

    private void updateTrimText() {
        trimTimes.setText(TrimWindow.formatRange(trimStart, trimEnd));
    }

    // ------------------------------------------------------------------ sentence & chips

    private void setSentence(String text) {
        sentence = text == null ? "" : text;
        tokens = Collections.emptyList();
        clearSelection();
        renderChips();
        final int gen = ++sentenceGeneration;
        final String s = sentence;
        if (!Tokenizer.isReady()) setStatus(ui.getString(R.string.mining_dictionary_loading), false);
        runInBackground(() -> {
            List<Token> t;
            try {
                t = Tokenizer.get().tokenize(s);
            } catch (RuntimeException e) {
                Log.e(TAG, "Tokenize failed", e);
                main.post(() -> setStatus(ui.getString(R.string.mining_dictionary_failed,
                        String.valueOf(e.getMessage())), false));
                return;
            }
            main.post(() -> {
                if (closed || gen != sentenceGeneration) return;
                tokens = t;
                renderChips();
                setStatus(ui.getString(R.string.mining_tap_word), false);
            });
        });
    }

    private void renderChips() {
        chips.removeAllViews();
        float d = ui.getResources().getDisplayMetrics().density;
        if (tokens.isEmpty()) {
            // Not tokenized yet: show plain text.
            TextView tv = new TextView(ui);
            tv.setText(sentence);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            tv.setTextColor(dimFg);
            chips.addView(tv);
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            final Token t = tokens.get(i);
            final int index = i;
            TextView tv = new TextView(ui);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            boolean selectable = TextSelection.isSelectable(t);
            if (selectable) {
                tv.setBackgroundResource(R.drawable.mining_chip_background);
                tv.setPadding(Math.round(5 * d), Math.round(3 * d), Math.round(5 * d), Math.round(3 * d));
                tv.setTextColor(chipFg);
                tv.setOnClickListener(v -> onChipTapped(index));
            } else {
                tv.setPadding(Math.round(2 * d), Math.round(3 * d), Math.round(2 * d), Math.round(3 * d));
                tv.setTextColor(dimFg);
            }
            tv.setText(highlighted(t));
            chips.addView(tv);
        }
    }

    /** The token's text with the part inside the current selection highlighted. */
    private CharSequence highlighted(Token t) {
        int a = Math.max(t.start, selStart);
        int b = Math.min(t.end, selEnd);
        if (selStart < 0 || a >= b) return t.surface;
        SpannableString s = new SpannableString(t.surface);
        s.setSpan(new BackgroundColorSpan(highlightBg), a - t.start, b - t.start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(highlightFg), a - t.start, b - t.start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void refreshHighlight() {
        if (chips.getChildCount() != tokens.size()) {
            renderChips();
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            ((TextView) chips.getChildAt(i)).setText(highlighted(tokens.get(i)));
        }
    }

    private void toggleSentenceEdit() {
        toggleSentenceEdit(!editingSentence);
    }

    private void toggleSentenceEdit(boolean edit) {
        if (edit == editingSentence) return;
        editingSentence = edit;
        if (edit) {
            sentenceEdit.setText(sentence);
            sentenceEdit.setVisibility(View.VISIBLE);
            chips.setVisibility(View.GONE);
            editSentenceButton.setText(R.string.mining_save_sentence);
            sentenceEdit.requestFocus();
            sentenceEdit.setSelection(sentenceEdit.length());
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(sentenceEdit, InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideKeyboard();
            String edited = sentenceEdit.getText().toString().replace('\n', ' ').trim();
            sentenceEdit.setVisibility(View.GONE);
            chips.setVisibility(View.VISIBLE);
            editSentenceButton.setText(R.string.mining_edit_sentence);
            if (!edited.equals(sentence)) setSentence(edited);
        }
    }

    // ------------------------------------------------------------------ selection & lookup

    private void clearSelection() {
        selStart = selEnd = -1;
        lookup = null;
        lookupGeneration++;
        saveGeneration++;
        selectionText.setText("");
        frontEdit.setText("");
        glossaryEdit.setText("");
        otherMeanings.setVisibility(View.GONE);
        for (View b : selectionButtons) b.setEnabled(false);
    }

    private void onChipTapped(int tokenIndex) {
        if (saving) return;
        final int gen = ++lookupGeneration;
        final String s = sentence;
        final List<Token> t = tokens;
        Token tapped = t.get(tokenIndex);
        // Show the tapped token immediately; the dictionary may extend it (模擬|戦 → 模擬戦).
        setSelection(tapped.start, tapped.end, false);
        runInBackground(() -> {
            int[] range;
            try {
                range = newLookup().longestMatch(s, t, tokenIndex);
            } catch (RuntimeException e) {
                reportLookupError(e);
                return;
            }
            main.post(() -> {
                if (closed || gen != lookupGeneration) return;
                setSelection(range[0], range[1], true);
            });
        });
    }

    private void adjustSelection(boolean startHandle, int delta) {
        if (selStart < 0 || saving) return;
        int[] r = startHandle
                ? TextSelection.moveStart(selStart, selEnd, delta, sentence.length())
                : TextSelection.moveEnd(selStart, selEnd, delta, sentence.length());
        if (r[0] == selStart && r[1] == selEnd) return;
        setSelection(r[0], r[1], true);
    }

    private void setSelection(int start, int end, boolean runLookup) {
        selStart = start;
        selEnd = end;
        saveGeneration++;
        refreshHighlight();
        selectionText.setText(sentence.substring(start, end));
        selectionButtons[0].setEnabled(start > 0);
        selectionButtons[1].setEnabled(end - start > 1);
        selectionButtons[2].setEnabled(end - start > 1);
        selectionButtons[3].setEnabled(end < sentence.length());
        if (!runLookup) return;

        final int gen = ++lookupGeneration;
        final String s = sentence;
        final List<Token> t = tokens;
        setStatus(ui.getString(R.string.mining_looking_up), false);
        runInBackground(() -> {
            WordLookup.Result r;
            try {
                r = newLookup().lookup(s, t, start, end);
            } catch (RuntimeException e) {
                reportLookupError(e);
                return;
            }
            main.post(() -> {
                if (closed || gen != lookupGeneration) return;
                showLookup(r);
            });
        });
    }

    private WordLookup newLookup() {
        return new WordLookup(Dictionary.get(activity), Tokenizer.get());
    }

    private void reportLookupError(RuntimeException e) {
        Log.e(TAG, "Lookup failed", e);
        main.post(() -> {
            if (!closed) setStatus(ui.getString(R.string.mining_dictionary_failed,
                    String.valueOf(e.getMessage())), false);
        });
    }

    private void showLookup(WordLookup.Result r) {
        lookup = r;
        frontEdit.setText(r.front);
        glossaryEdit.setText(FieldText.htmlToPlain(r.glossary));
        int others = r.alternatives.size();
        if (others > 1) {
            otherMeanings.setText(ui.getString(R.string.mining_other_meanings, others - 1));
            otherMeanings.setVisibility(View.VISIBLE);
        } else {
            otherMeanings.setVisibility(View.GONE);
        }
        setStatus(r.found() ? "" : ui.getString(R.string.mining_no_match), false);
    }

    private void showOtherMeanings(View anchor) {
        final WordLookup.Result r = lookup;
        if (r == null || r.alternatives.size() < 2) return;
        PopupMenu menu = new PopupMenu(ui, anchor);
        for (int i = 0; i < r.alternatives.size(); i++) {
            DictEntry e = r.alternatives.get(i);
            String gloss = e.senses.isEmpty() || e.senses.get(0).glosses.isEmpty()
                    ? "" : " — " + e.senses.get(0).glosses.get(0);
            String label = e.headword + "【" + e.reading + "】" + gloss;
            if (e == r.entry) label = "✓ " + label;
            menu.getMenu().add(0, i, i, label);
        }
        menu.setOnMenuItemClickListener(item -> {
            DictEntry chosen = r.alternatives.get(item.getItemId());
            showLookup(r.withEntry(chosen));
            return true;
        });
        menu.show();
    }

    // ------------------------------------------------------------------ preview

    /** Like {@link #startPreview} but at most one seek per 150 ms, keeping the latest request. */
    private void requestPreview(long from, long until) {
        pendingPreviewFrom = from;
        pendingPreviewUntil = until;
        main.removeCallbacks(pendingPreview);
        long wait = lastPreviewSeekAt + 150 - android.os.SystemClock.uptimeMillis();
        if (wait <= 0) {
            pendingPreview.run();
        } else {
            main.postDelayed(pendingPreview, wait);
        }
    }

    private void startPreview(long from, long until) {
        main.removeCallbacks(pendingPreview);
        pendingPreviewFrom = -1;
        lastPreviewSeekAt = android.os.SystemClock.uptimeMillis();
        previewStopAt = until;
        player.seekTo(from);
        player.play();
        main.removeCallbacks(previewMonitor);
        main.postDelayed(previewMonitor, PREVIEW_POLL_MS);
    }

    private void stopPreview() {
        main.removeCallbacks(previewMonitor);
        main.removeCallbacks(pendingPreview);
        pendingPreviewFrom = -1;
        if (previewStopAt >= 0) {
            previewStopAt = -1;
            player.pause();
        }
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (saving) return;
        if (editingSentence) toggleSentenceEdit(false);
        final String front = frontEdit.getText().toString().trim();
        if (front.isEmpty()) {
            setStatus(ui.getString(R.string.mining_front_empty), false);
            return;
        }
        if (audioSource == null) {
            setStatus(ui.getString(R.string.mining_no_audio_source), false);
            return;
        }
        stopPreview();
        final String glossary = FieldText.plainToHtml(glossaryEdit.getText().toString());
        final String sentenceHtml = Glossary.escape(sentence.trim());
        final long start = trimStart;
        final long end = trimEnd;
        final File picture = pictureInclude.isChecked() ? pictureFile : null;
        final boolean wantWordAudio = wordAudioInclude.isChecked();
        final String spoken = Furigana.toSpeech(front);
        final int gen = ++saveGeneration;
        setSaving(true, R.string.mining_saving_check);

        runInBackground(() -> {
            AnkiExporter.Result check = exporter.precheck(front);
            main.post(() -> {
                if (closed || gen != saveGeneration) return;
                if (check.kind != AnkiExporter.Result.Kind.SUCCESS) {
                    finishSaveWithError(check);
                    return;
                }
                setSaving(true, R.string.mining_saving_clip);
                // Word TTS and the sentence clip run in parallel; the card is added once both finish.
                final SaveJoin join = new SaveJoin(gen, front, glossary, sentenceHtml, picture);
                if (wantWordAudio) {
                    speech.synthesizeToFile(spoken, new WordSpeech.FileCallback() {
                        @Override
                        public void onSpeechFile(@NonNull File file) {
                            join.wordAudio(file, null);
                        }

                        @Override
                        public void onSpeechError(@NonNull String message) {
                            join.wordAudio(null, message);
                        }
                    });
                } else {
                    join.wordAudio(null, null);
                }
                clip(gen, audioSource, start, end, join::sentenceAudio);
            });
        });
    }

    /** Waits for the word TTS file and the sentence clip, then adds the card. */
    private final class SaveJoin {
        final int gen;
        final String front;
        final String glossary;
        final String sentenceHtml;
        @Nullable
        final File picture;
        boolean wordDone;
        @Nullable
        File wordFile;
        @Nullable
        String wordError;
        @Nullable
        File clipFile;

        SaveJoin(int gen, String front, String glossary, String sentenceHtml, @Nullable File picture) {
            this.gen = gen;
            this.front = front;
            this.glossary = glossary;
            this.sentenceHtml = sentenceHtml;
            this.picture = picture;
        }

        void wordAudio(@Nullable File file, @Nullable String error) {
            if (closed || gen != saveGeneration) {
                deleteQuietly(file);
                return;
            }
            wordDone = true;
            wordFile = file;
            wordError = error;
            maybeAdd();
        }

        void sentenceAudio(File clip) {
            clipFile = clip;
            maybeAdd();
        }

        private void maybeAdd() {
            if (!wordDone || clipFile == null) return;
            MinedCard card = new MinedCard(front, glossary, sentenceHtml, Uri.fromFile(clipFile),
                    picture != null ? Uri.fromFile(picture) : null,
                    wordFile != null ? Uri.fromFile(wordFile) : null);
            addToAnki(gen, card, wordError, clipFile, wordFile);
        }
    }

    private static void deleteQuietly(@Nullable File f) {
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
    }

    private interface ClipDone {
        void onClip(File file);
    }

    /** The clipper itself retries once with a fresh URL if the stream URL was rejected. */
    private void clip(int gen, AudioClipper.AudioSource source, long start, long end, ClipDone done) {
        clipper.clip(activity, source, start, end, new AudioClipper.Callback() {
            @Override
            public void onClipReady(@NonNull File file, @NonNull Uri fileUri) {
                if (closed || gen != saveGeneration) return;
                setSaving(true, R.string.mining_saving_anki);
                done.onClip(file);
            }

            @Override
            public void onClipError(@NonNull String message, @Nullable Exception cause) {
                if (closed || gen != saveGeneration) return;
                Log.w(TAG, "Clip failed: " + message, cause);
                failSave(ui.getString(R.string.mining_clip_failed, message), false);
            }
        });
    }

    /**
     * Adds the card, then deletes the audio files: AnkiDroid has copied them into its own media
     * folder by then (or the add failed, and the next attempt records them again).
     */
    private void addToAnki(int gen, MinedCard card, @Nullable String wordError, File... audioFiles) {
        runInBackground(() -> {
            AnkiExporter.Result r = exporter.add(card);
            for (File f : audioFiles) deleteQuietly(f);
            main.post(() -> {
                if (closed || gen != saveGeneration) return;
                if (r.kind == AnkiExporter.Result.Kind.SUCCESS) {
                    String msg = wordError == null
                            ? ui.getString(R.string.mining_saved, card.front)
                            : ui.getString(R.string.mining_saved_no_word_audio, card.front, wordError);
                    Toast.makeText(activity, msg, wordError == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    saving = false;
                    close(true);
                } else {
                    finishSaveWithError(r);
                }
            });
        });
    }

    private void finishSaveWithError(AnkiExporter.Result r) {
        if (r.kind == AnkiExporter.Result.Kind.DUPLICATE) {
            failSave(ui.getString(R.string.mining_duplicate), false);
        } else {
            // precheck/add errors are AnkiDroid or settings problems.
            failSave(r.message, true);
        }
    }

    private void failSave(String message, boolean offerSettings) {
        setSaving(false, 0);
        setStatus(message, offerSettings);
    }

    private void setSaving(boolean on, int messageRes) {
        saving = on;
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        addButton.setEnabled(!on);
        prevButton.setEnabled(!on && cueIndex > 0);
        nextButton.setEnabled(!on && cueIndex < cues.size() - 1);
        if (on) setStatus(ui.getString(messageRes), false);
    }

    private void setStatus(String text, boolean offerSettings) {
        status.setText(text);
        if (offerSettings) {
            // The button is also reused for "Install voice"; restore its Settings role.
            settingsButton.setText(R.string.mining_settings);
            settingsButton.setOnClickListener(v -> listener.onOpenSettings());
        }
        settingsButton.setVisibility(offerSettings ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ word audio

    private void onSpeechStateKnown() {
        if (closed) return;
        if (speech.getState() != WordSpeech.State.READY) {
            // Default to off so saving doesn't silently fall back; the user can still tick it.
            wordAudioInclude.setChecked(false);
            showSpeechProblem();
        }
    }

    private void showSpeechProblem() {
        setStatus(ui.getString(R.string.mining_word_audio_unavailable, speech.describeProblem()), false);
        if (speech.getState() == WordSpeech.State.NO_JAPANESE) {
            settingsButton.setText(R.string.mining_install_voice);
            settingsButton.setVisibility(View.VISIBLE);
            settingsButton.setOnClickListener(v -> {
                try {
                    activity.startActivity(WordSpeech.isGoogleTtsDisabled(activity)
                            ? WordSpeech.enableGoogleTtsIntent() : WordSpeech.installVoiceIntent());
                } catch (RuntimeException e) {
                    Log.w(TAG, "No TTS install screen", e);
                }
            });
        }
    }

    // ------------------------------------------------------------------ picture

    private void capturePicture() {
        if (closed) return;
        final int gen = ++pictureGeneration;
        pictureRetake.setEnabled(false);
        player.grabFrame(new FrameGrabber.Callback() {
            @Override
            public void onFrame(@NonNull File file, @NonNull android.graphics.Bitmap bitmap) {
                if (closed || gen != pictureGeneration) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    return;
                }
                pictureRetake.setEnabled(true);
                setPicture(file, bitmap);
            }

            @Override
            public void onFrameError(@NonNull String message) {
                if (closed || gen != pictureGeneration) return;
                pictureRetake.setEnabled(true);
                Log.w(TAG, "Frame grab failed: " + message);
                setPicture(null, null);
                setStatus(ui.getString(R.string.mining_picture_failed, message), false);
            }
        });
    }

    /** Replaces the current picture, deleting the previous file (never sent to Anki). */
    private void setPicture(@Nullable File file, @Nullable android.graphics.Bitmap bitmap) {
        if (pictureFile != null && !pictureFile.equals(file)) {
            //noinspection ResultOfMethodCallIgnored
            pictureFile.delete();
        }
        pictureFile = file;
        pictureView.setImageBitmap(bitmap);
    }

    // ------------------------------------------------------------------ helpers

    private void runInBackground(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException ignored) {
            // Panel closed.
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    @RequiresApi(33)
    private static final class BackCompat {
        static Object register(Activity a, Runnable onBack) {
            OnBackInvokedCallback cb = onBack::run;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            return cb;
        }

        static void unregister(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) cb);
        }
    }
}

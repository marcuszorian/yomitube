package org.schabi.newpipe.mining;

import java.util.List;

/**
 * Formats a {@link DictEntry} for the Anki {@code Glossary} field.
 *
 * <p>The field is rendered as HTML, so text is escaped and senses are separated by {@code <br>}:
 * <pre>1. (n) sham battle; mock battle; dry run<br>2. (v1, vt) ...</pre>
 * At most {@link #MAX_SENSES} senses and {@link #MAX_GLOSSES} glosses per sense are kept; the POS
 * label is omitted when identical to the previous sense's. A single-sense entry has no number.
 */
public final class Glossary {
    public static final int MAX_SENSES = 4;
    public static final int MAX_GLOSSES = 6;
    public static final String SEPARATOR = "<br>";

    private Glossary() {}

    public static String format(DictEntry e) {
        if (e == null || e.senses.isEmpty()) return "";
        int n = Math.min(e.senses.size(), MAX_SENSES);
        StringBuilder sb = new StringBuilder();
        List<String> prevPos = null;
        for (int i = 0; i < n; i++) {
            DictEntry.Sense s = e.senses.get(i);
            if (i > 0) sb.append(SEPARATOR);
            if (e.senses.size() > 1) sb.append(i + 1).append(". ");
            if (!s.partsOfSpeech.isEmpty() && !s.partsOfSpeech.equals(prevPos)) {
                sb.append('(').append(escape(String.join(", ", s.partsOfSpeech))).append(") ");
            }
            prevPos = s.partsOfSpeech;
            int g = Math.min(s.glosses.size(), MAX_GLOSSES);
            for (int j = 0; j < g; j++) {
                if (j > 0) sb.append("; ");
                sb.append(escape(s.glosses.get(j)));
            }
        }
        return sb.toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}

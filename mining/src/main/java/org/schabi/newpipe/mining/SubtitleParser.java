package org.schabi.newpipe.mining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java subtitle parsing (no Android dependencies, unit-testable on the JVM).
 *
 * <p>Supported formats: SRT, WebVTT, YouTube srv3 ("timedtext format 3"), YouTube srv1
 * ({@code <transcript><text start dur>}) and TTML/DFXP.
 *
 * <p>All parsers return cues sorted by start time, with blank cues removed, formatting tags
 * stripped, entities decoded, multi-line text joined into one line and overlaps removed (a cue's
 * end is clipped to the next cue's start). This yields one "current sentence" at any instant,
 * which is what the mining panel needs.
 *
 * <p>YouTube auto-caption VTT uses "rolling" cues where each cue repeats the previously shown line
 * and adds a new one (plus 10 ms "snapshot" cues). When a VTT file contains YouTube's inline
 * word timestamps ({@code <00:00:01.234>} / {@code <c>}) it is treated as rolling: lines already
 * shown by the previous cue are dropped, so every line is emitted exactly once.
 */
public final class SubtitleParser {

    public static final String FORMAT_SRT = "srt";
    public static final String FORMAT_VTT = "vtt";
    public static final String FORMAT_SRV3 = "srv3";
    public static final String FORMAT_SRV1 = "srv1";
    public static final String FORMAT_TTML = "ttml";

    private SubtitleParser() {
    }

    /**
     * Parses subtitle content.
     *
     * @param formatHint a format name or file extension ("srt", "vtt", "srv3", "srv1", "ttml",
     *                   "xml", "dfxp"), a MIME type, or null to sniff from the content. If the hint
     *                   is unknown the content is sniffed.
     */
    public static List<Cue> parse(String content, String formatHint) {
        if (content == null) return new ArrayList<>();
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        String format = normalizeFormat(formatHint);
        if (format == null) format = sniff(content);
        switch (format) {
            case FORMAT_VTT:
                return parseVtt(content);
            case FORMAT_SRV3:
            case FORMAT_SRV1:
            case FORMAT_TTML:
                // The XML parser handles all three dialects; sniff the dialect from the content.
                return parseXml(content);
            case FORMAT_SRT:
            default:
                return parseSrt(content);
        }
    }

    static String normalizeFormat(String hint) {
        if (hint == null) return null;
        String h = hint.trim().toLowerCase(Locale.ROOT);
        int dot = h.lastIndexOf('.');
        if (dot >= 0) h = h.substring(dot + 1);
        if (h.contains("vtt")) return FORMAT_VTT;
        if (h.contains("srt") || h.contains("subrip")) return FORMAT_SRT;
        if (h.equals("srv3") || h.equals("srv2")) return FORMAT_SRV3;
        if (h.equals("srv1")) return FORMAT_SRV1;
        if (h.contains("ttml") || h.contains("dfxp") || h.contains("xml")) return FORMAT_TTML;
        return null;
    }

    static String sniff(String content) {
        String head = content.substring(0, Math.min(content.length(), 2000)).trim();
        if (head.startsWith("WEBVTT")) return FORMAT_VTT;
        if (head.startsWith("<")) {
            if (head.contains("<timedtext")) return FORMAT_SRV3;
            if (head.contains("<transcript")) return FORMAT_SRV1;
            return FORMAT_TTML;
        }
        return FORMAT_SRT;
    }

    // ---------------------------------------------------------------- SRT

    private static final Pattern SRT_TIMING = Pattern.compile(
            "(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");

    public static List<Cue> parseSrt(String content) {
        List<Cue> raw = new ArrayList<>();
        String[] lines = normalizeNewlines(content).split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher m = SRT_TIMING.matcher(lines[i]);
            if (!m.find()) {
                i++;
                continue;
            }
            long start = hmsMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = hmsMs(m.group(5), m.group(6), m.group(7), m.group(8));
            i++;
            List<String> text = new ArrayList<>();
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                // A timing line without a blank separator starts a new cue.
                if (SRT_TIMING.matcher(lines[i]).find()) break;
                text.add(cleanHtml(lines[i]));
                i++;
            }
            // Drop a trailing cue index that belongs to the next cue (missing blank line).
            if (i < lines.length && !text.isEmpty() && SRT_TIMING.matcher(lines[i]).find()
                    && text.get(text.size() - 1).matches("\\d+")) {
                text.remove(text.size() - 1);
            }
            addCue(raw, start, end, joinLines(text));
        }
        return finish(raw);
    }

    // ---------------------------------------------------------------- WebVTT

    private static final Pattern VTT_TIMING = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})");
    private static final Pattern VTT_INLINE_TIMESTAMP = Pattern.compile("<\\d+:\\d{2}(?::\\d{2})?\\.\\d{3}>");

    public static List<Cue> parseVtt(String content) {
        content = normalizeNewlines(content);
        boolean rolling = VTT_INLINE_TIMESTAMP.matcher(content).find() || content.contains("<c>")
                || content.contains("<c.");
        // Line-based: a timing line starts a cue; the payload runs until a truly empty line or the
        // next timing line. (YouTube auto-caption payloads contain whitespace-only lines, so
        // whitespace-only lines must NOT terminate a cue.) NOTE/STYLE/REGION blocks have no timing
        // line and are skipped naturally.
        String[] lines = content.split("\n", -1);
        List<Cue> raw = new ArrayList<>();
        List<String> prevLines = Collections.emptyList();
        int i = 0;
        while (i < lines.length) {
            Matcher m = VTT_TIMING.matcher(lines[i]);
            if (!lines[i].contains("-->") || !m.find()) {
                i++;
                continue;
            }
            long start = vttTimeMs(m.group(1));
            long end = vttTimeMs(m.group(2));
            i++;
            List<String> text = new ArrayList<>();
            while (i < lines.length && !lines[i].isEmpty() && !lines[i].contains("-->")) {
                String line = cleanHtml(lines[i]).trim();
                if (!line.isEmpty()) text.add(line);
                i++;
            }
            if (rolling) {
                List<String> shown = text;
                text = dropAlreadyShown(text, prevLines);
                prevLines = shown;
            }
            addCue(raw, start, end, joinLines(text));
        }
        return finish(raw);
    }

    /** Removes the leading lines of {@code lines} that were already displayed in {@code prev}. */
    static List<String> dropAlreadyShown(List<String> lines, List<String> prev) {
        int skip = 0;
        while (skip < lines.size() && prev.contains(lines.get(skip))) skip++;
        return lines.subList(skip, lines.size());
    }

    private static long vttTimeMs(String s) {
        String[] parts = s.replace(',', '.').split(":");
        String h = parts.length == 3 ? parts[0] : "0";
        String mm = parts[parts.length - 2];
        String[] sec = parts[parts.length - 1].split("\\.");
        return hmsMs(h, mm, sec[0], sec.length > 1 ? sec[1] : "0");
    }

    // ---------------------------------------------------------------- XML (srv3 / srv1 / TTML)

    private static final Pattern XML_P = Pattern.compile(
            "<((?:[\\w-]+:)?(?:p|text))\\b([^>]*?)(?:/>|>(.*?)</\\1\\s*>)", Pattern.DOTALL);
    private static final Pattern XML_ATTR = Pattern.compile(
            "([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern XML_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    /**
     * Parses YouTube srv3 ({@code <p t="ms" d="ms">}), srv1 ({@code <text start="s" dur="s">}) and
     * TTML ({@code <p begin end|dur>}).
     */
    public static List<Cue> parseXml(String content) {
        List<Cue> raw = new ArrayList<>();
        // Drop <head> (srv3 window definitions / TTML styling) so <p> inside it is never matched.
        String body = content.replaceAll("(?s)<head\\b.*?</head\\s*>", "");
        Matcher m = XML_P.matcher(body);
        while (m.find()) {
            String attrs = m.group(2);
            String inner = m.group(3);
            if (inner == null) continue;
            String t = attr(attrs, "t");
            String d = attr(attrs, "d");
            long start;
            long end;
            if (t != null) { // srv3: milliseconds
                start = parseLong(t);
                end = start + (d != null ? parseLong(d) : 0);
            } else if (attr(attrs, "start") != null) { // srv1: seconds
                start = secondsToMs(attr(attrs, "start"));
                String dur = attr(attrs, "dur");
                end = start + (dur != null ? secondsToMs(dur) : 0);
            } else { // TTML
                String begin = attr(attrs, "begin");
                if (begin == null) continue;
                start = ttmlTimeMs(begin);
                String e = attr(attrs, "end");
                String dur = attr(attrs, "dur");
                end = e != null ? ttmlTimeMs(e) : start + (dur != null ? ttmlTimeMs(dur) : 0);
            }
            if (start < 0) continue;
            String text = XML_BR.matcher(inner).replaceAll("\n");
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                String c = cleanHtml(line).trim();
                if (!c.isEmpty()) lines.add(c);
            }
            addCue(raw, start, end, joinLines(lines));
        }
        return finish(raw);
    }

    private static String attr(String attrs, String name) {
        Matcher m = XML_ATTR.matcher(attrs);
        while (m.find()) {
            String key = m.group(1);
            int colon = key.indexOf(':');
            if (colon >= 0) key = key.substring(colon + 1);
            if (key.equals(name)) return m.group(3) != null ? m.group(3) : m.group(4);
        }
        return null;
    }

    static long ttmlTimeMs(String s) {
        s = s.trim();
        try {
            if (s.endsWith("ms")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)));
            if (s.endsWith("s")) return secondsToMs(s.substring(0, s.length() - 1));
            if (s.endsWith("m")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 60000);
            if (s.endsWith("h")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 3600000);
            if (s.endsWith("t")) return -1; // tick-based times need ttp:tickRate; unsupported
            String[] p = s.split(":");
            if (p.length >= 3) {
                long ms = Long.parseLong(p[0]) * 3600000L + Long.parseLong(p[1]) * 60000L
                        + Math.round(Double.parseDouble(p[2]) * 1000);
                // HH:MM:SS:FF frames: assume 30 fps (rare in practice).
                if (p.length == 4) ms += Math.round(Double.parseDouble(p[3]) * 1000 / 30.0);
                return ms;
            }
            return secondsToMs(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long secondsToMs(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- SRT writing

    /** Serializes cues as SRT (used to feed fetched captions into ExoPlayer). */
    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            sb.append(n++).append('\n')
                    .append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
                    .append(c.text.replace("\r", "").replaceAll("\n\\s*\n", "\n")).append("\n\n");
        }
        return sb.toString();
    }

    static String srtTime(long ms) {
        if (ms < 0) ms = 0;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    // ---------------------------------------------------------------- shared helpers

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern RUBY_TEXT = Pattern.compile(
            "<(rt|rp)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASS_OVERRIDE = Pattern.compile("\\{\\\\[^}]*\\}"); // "}" escaped: Android's ICU regex rejects a bare one
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    /** Strips HTML/VTT tags and ASS override blocks, then decodes entities. */
    static String cleanHtml(String s) {
        s = RUBY_TEXT.matcher(s).replaceAll(""); // keep base text, drop furigana readings
        s = TAG.matcher(s).replaceAll("");
        s = ASS_OVERRIDE.matcher(s).replaceAll("");
        return decodeEntities(s);
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String e = m.group(1);
            String rep = null;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                rep = codePoint(e.substring(2), 16);
            } else if (e.startsWith("#")) {
                rep = codePoint(e.substring(1), 10);
            } else {
                switch (e) {
                    case "amp": rep = "&"; break;
                    case "lt": rep = "<"; break;
                    case "gt": rep = ">"; break;
                    case "quot": rep = "\""; break;
                    case "apos": rep = "'"; break;
                    case "nbsp": rep = " "; break;
                    case "lrm":
                    case "rlm":
                    case "zwj":
                    case "zwnj": rep = ""; break;
                    case "hellip": rep = "…"; break;
                    case "mdash": rep = "—"; break;
                    case "ndash": rep = "–"; break;
                    case "lsquo": rep = "‘"; break;
                    case "rsquo": rep = "’"; break;
                    case "ldquo": rep = "“"; break;
                    case "rdquo": rep = "”"; break;
                    default: break;
                }
            }
            sb.append(rep != null ? rep : m.group());
            last = m.end();
        }
        sb.append(s, last, s.length());
        return sb.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return new String(Character.toChars(cp));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Joins caption lines into one sentence. Lines are concatenated directly when the boundary is
     * between CJK characters (Japanese has no inter-word spaces), otherwise separated by a space.
     */
    static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.replace('\u00A0', ' ').trim().replaceAll("[ \\t]+", " ");
            if (line.isEmpty()) continue;
            if (sb.length() > 0) {
                char a = sb.charAt(sb.length() - 1);
                char b = line.charAt(0);
                if (!(isCjk(a) && isCjk(b))) sb.append(' ');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static void addCue(List<Cue> out, long start, long end, String text) {
        if (text == null || text.trim().isEmpty() || start < 0) return;
        out.add(new Cue(start, Math.max(end, start), text.trim()));
    }

    /**
     * Sorts cues, merges cues with identical text that touch/overlap (snapshot duplicates), and
     * clips each cue's end to the next cue's start so cues never overlap.
     */
    static List<Cue> finish(List<Cue> raw) {
        List<Cue> sorted = new ArrayList<>(raw);
        // Stable sort keeps file order for identical start times.
        Collections.sort(sorted, (x, y) -> Long.compare(x.startMs, y.startMs));
        List<Cue> merged = new ArrayList<>();
        for (Cue c : sorted) {
            if (!merged.isEmpty()) {
                Cue p = merged.get(merged.size() - 1);
                if (p.text.equals(c.text) && c.startMs <= p.endMs + 50) {
                    merged.set(merged.size() - 1, new Cue(p.startMs, Math.max(p.endMs, c.endMs), p.text));
                    continue;
                }
            }
            merged.add(c);
        }
        List<Cue> out = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            Cue c = merged.get(i);
            long end = c.endMs;
            if (i + 1 < merged.size()) {
                long nextStart = merged.get(i + 1).startMs;
                if (end > nextStart) end = nextStart;
            }
            if (end <= c.startMs && i + 1 < merged.size() && merged.get(i + 1).startMs == c.startMs) {
                // Two cues starting at the same instant: combine rather than produce a 0-length cue.
                Cue n = merged.get(i + 1);
                merged.set(i + 1, new Cue(c.startMs, Math.max(c.endMs, n.endMs),
                        joinLines(java.util.Arrays.asList(c.text, n.text))));
                continue;
            }
            out.add(new Cue(c.startMs, end, c.text));
        }
        return out;
    }

    private static long hmsMs(String h, String m, String s, String frac) {
        String f = (frac + "00").substring(0, 3);
        return Long.parseLong(h) * 3600000L + Long.parseLong(m) * 60000L
                + Long.parseLong(s) * 1000L + Long.parseLong(f);
    }

    private static String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }
}

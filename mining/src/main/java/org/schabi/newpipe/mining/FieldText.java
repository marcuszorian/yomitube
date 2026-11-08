package org.schabi.newpipe.mining;

/**
 * Converts between Anki field HTML and the plain text shown in the panel's edit boxes.
 * Only handles what {@link Glossary} produces ({@code <br>} and the four escaped entities), so a
 * glossary the user didn't touch round-trips unchanged. Pure logic, no Android dependencies.
 */
public final class FieldText {

    private FieldText() {
    }

    /** {@code <br>} → newline, entities decoded. */
    public static String htmlToPlain(String html) {
        if (html == null) return "";
        return html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    /** Escaped, newlines → {@code <br>}, surrounding whitespace trimmed. */
    public static String plainToHtml(String plain) {
        if (plain == null) return "";
        String trimmed = plain.trim().replace("\r\n", "\n");
        return Glossary.escape(trimmed).replace("\n", Glossary.SEPARATOR);
    }
}

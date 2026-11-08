package org.schabi.newpipe.mining;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Maps named values onto AnkiDroid's positional field array. Pure logic, no Android dependencies.
 */
public final class NoteFieldMapper {

    public static final String FRONT = "Front";
    public static final String GLOSSARY = "Glossary";
    public static final String SENTENCE = "Sentence";
    public static final String PICTURE = "Picture";
    public static final String AUDIO = "Audio";
    public static final String SENTENCE_AUDIO = "SentenceAudio";
    public static final String GRAPH = "Graph";
    public static final String HINT = "Hint";

    /**
     * Fields the note type must contain for export to work. Picture/Audio/Graph/Hint are optional;
     * Audio (word audio) is never filled.
     */
    public static final List<String> REQUIRED_FIELDS = Collections.unmodifiableList(
            Arrays.asList(FRONT, GLOSSARY, SENTENCE, SENTENCE_AUDIO));

    public static final class MissingFieldsException extends Exception {
        public final List<String> missing;

        public MissingFieldsException(List<String> missing) {
            super("Note type is missing required field" + (missing.size() > 1 ? "s" : "") + ": "
                    + String.join(", ", missing));
            this.missing = Collections.unmodifiableList(missing);
        }
    }

    private NoteFieldMapper() {
    }

    /** Returns the required field names not present in {@code modelFields} (empty if all present). */
    public static List<String> missingFields(String[] modelFields, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (indexOf(modelFields, name) < 0) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * Builds the positional field array for {@code modelFields}. Each value in {@code values}
     * is placed at the index of the model field with the same name (exact match first, then
     * case-insensitive/trimmed). Model fields without a value are set to "".
     *
     * @throws MissingFieldsException if any of {@code required} is not a field of the model
     */
    public static String[] buildFields(String[] modelFields, Map<String, String> values, List<String> required)
            throws MissingFieldsException {
        if (modelFields == null) {
            throw new MissingFieldsException(new ArrayList<>(required));
        }
        List<String> missing = missingFields(modelFields, required);
        if (!missing.isEmpty()) {
            throw new MissingFieldsException(missing);
        }
        String[] result = new String[modelFields.length];
        Arrays.fill(result, "");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int index = indexOf(modelFields, entry.getKey());
            if (index >= 0 && entry.getValue() != null) {
                result[index] = entry.getValue();
            }
        }
        return result;
    }

    static int indexOf(String[] modelFields, String name) {
        for (int i = 0; i < modelFields.length; i++) {
            if (name.equals(modelFields[i])) {
                return i;
            }
        }
        for (int i = 0; i < modelFields.length; i++) {
            if (modelFields[i] != null && name.trim().equalsIgnoreCase(modelFields[i].trim())) {
                return i;
            }
        }
        return -1;
    }
}

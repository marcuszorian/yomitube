package org.schabi.newpipe.mining;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class NoteFieldMapperTest {

    private static Map<String, String> values() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("Front", "漢字[かんじ]");
        v.put("Glossary", "kanji");
        v.put("Sentence", "漢字を書く。");
        v.put("Audio", "[sound:a.m4a]");
        v.put("SentenceAudio", "[sound:a.m4a]");
        return v;
    }

    @Test
    public void mapsUserFieldOrder() throws Exception {
        String[] model = {"Front", "Glossary", "Sentence", "Picture", "Audio", "SentenceAudio", "Graph", "Hint"};
        String[] out = NoteFieldMapper.buildFields(model, values(), NoteFieldMapper.REQUIRED_FIELDS);
        assertArrayEquals(new String[]{"漢字[かんじ]", "kanji", "漢字を書く。", "", "[sound:a.m4a]", "[sound:a.m4a]", "", ""}, out);
    }

    @Test
    public void mapsDifferentFieldOrder() throws Exception {
        String[] model = {"Hint", "SentenceAudio", "Sentence", "Front", "Extra", "Audio", "Glossary"};
        String[] out = NoteFieldMapper.buildFields(model, values(), NoteFieldMapper.REQUIRED_FIELDS);
        assertArrayEquals(new String[]{"", "[sound:a.m4a]", "漢字を書く。", "漢字[かんじ]", "", "[sound:a.m4a]", "kanji"}, out);
    }

    @Test
    public void caseInsensitiveFallback() throws Exception {
        String[] model = {"front", "GLOSSARY", " Sentence ", "audio", "sentenceaudio"};
        String[] out = NoteFieldMapper.buildFields(model, values(), NoteFieldMapper.REQUIRED_FIELDS);
        assertEquals("漢字[かんじ]", out[0]);
        assertEquals("kanji", out[1]);
        assertEquals("漢字を書く。", out[2]);
    }

    @Test
    public void exactMatchPreferredOverCaseInsensitive() throws Exception {
        String[] model = {"audio", "Audio", "Front", "Glossary", "Sentence", "SentenceAudio"};
        String[] out = NoteFieldMapper.buildFields(model, values(), NoteFieldMapper.REQUIRED_FIELDS);
        assertEquals("", out[0]);
        assertEquals("[sound:a.m4a]", out[1]);
    }

    @Test
    public void missingFieldsReportedByName() {
        String[] model = {"Front", "Back", "Audio"};
        try {
            NoteFieldMapper.buildFields(model, values(), NoteFieldMapper.REQUIRED_FIELDS);
            fail();
        } catch (NoteFieldMapper.MissingFieldsException e) {
            assertEquals(Arrays.asList("Glossary", "Sentence", "SentenceAudio"), e.missing);
            assertTrue(e.getMessage().contains("Glossary, Sentence, SentenceAudio"));
        }
    }

    @Test
    public void nullModelFieldsIsMissingEverything() {
        try {
            NoteFieldMapper.buildFields(null, values(), NoteFieldMapper.REQUIRED_FIELDS);
            fail();
        } catch (NoteFieldMapper.MissingFieldsException e) {
            assertEquals(NoteFieldMapper.REQUIRED_FIELDS, e.missing);
        }
    }

    @Test
    public void nullValuesBecomeEmpty() throws Exception {
        String[] model = {"Front", "Glossary", "Sentence", "Audio", "SentenceAudio"};
        Map<String, String> v = values();
        v.put("Glossary", null);
        String[] out = NoteFieldMapper.buildFields(model, v, NoteFieldMapper.REQUIRED_FIELDS);
        assertEquals("", out[1]);
    }
}

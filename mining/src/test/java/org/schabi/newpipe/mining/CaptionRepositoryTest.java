package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CaptionRepositoryTest {

    private static SubtitleTrackInfo t(String lang, boolean auto) {
        return new SubtitleTrackInfo(lang, lang + (auto ? " auto" : ""), auto, "u", "srv3");
    }

    @Test
    public void manualJapaneseBeatsAuto() {
        SubtitleTrackInfo auto = t("ja", true);
        SubtitleTrackInfo manual = t("ja", false);
        assertSame(manual, CaptionRepository.selectTrack(Arrays.asList(t("en", false), auto, manual)));
    }

    @Test
    public void regionalJapaneseCodesRecognised() {
        SubtitleTrackInfo manualJp = t("ja-JP", false);
        assertSame(manualJp, CaptionRepository.selectTrack(Arrays.asList(t("ja", true), manualJp)));
        assertTrue(SubtitleTrackInfo.isJapaneseCode("JA_jp"));
        assertFalse(SubtitleTrackInfo.isJapaneseCode("jv"));
        assertFalse(SubtitleTrackInfo.isJapaneseCode(null));
    }

    @Test
    public void autoJapaneseIsFallback() {
        SubtitleTrackInfo auto = t("ja", true);
        assertSame(auto, CaptionRepository.selectTrack(Arrays.asList(t("en", false), t("ko", false), auto)));
    }

    @Test
    public void noJapaneseGivesNull() {
        assertNull(CaptionRepository.selectTrack(Arrays.asList(t("en", false), t("en", true))));
        assertNull(CaptionRepository.selectTrack(Collections.emptyList()));
        assertNull(CaptionRepository.selectTrack(null));
    }

    @Test
    public void audioScoring() {
        long progressiveM4a = CaptionRepository.audioScore(true, true, true, 128);
        long progressiveWebmHigh = CaptionRepository.audioScore(true, true, false, 160);
        long dashM4a = CaptionRepository.audioScore(false, true, true, 256);
        long dubbedM4a = CaptionRepository.audioScore(true, false, true, 128);
        assertTrue(progressiveM4a > progressiveWebmHigh);
        assertTrue(progressiveWebmHigh > dashM4a);
        assertTrue(progressiveM4a > dubbedM4a);
        assertTrue(CaptionRepository.audioScore(true, true, true, 129) > progressiveM4a);
    }
}


package org.schabi.newpipe.mining;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FieldTextTest {

    @Test
    public void glossaryRoundTrips() {
        String html = "1. (n) sham battle; mock &quot;battle&quot;<br>2. (vs) A &amp; B &lt;x&gt;";
        String plain = FieldText.htmlToPlain(html);
        assertEquals("1. (n) sham battle; mock \"battle\"\n2. (vs) A & B <x>", plain);
        assertEquals(html, FieldText.plainToHtml(plain));
    }

    @Test
    public void userTextEscaped() {
        assertEquals("a &lt;b&gt;<br>c", FieldText.plainToHtml("  a <b>\r\nc \n"));
    }

    @Test
    public void nullsAreEmpty() {
        assertEquals("", FieldText.htmlToPlain(null));
        assertEquals("", FieldText.plainToHtml(null));
    }
}

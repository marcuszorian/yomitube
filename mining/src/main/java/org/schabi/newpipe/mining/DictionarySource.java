package org.schabi.newpipe.mining;

import java.util.List;

/** Exact-match Japanese→English lookup. Implemented by {@link Dictionary}; faked in unit tests. */
public interface DictionarySource {
    /**
     * Entries having {@code term} as an exact kanji or kana form, best first (common entries first).
     * Never null; empty when nothing matches.
     */
    List<DictEntry> lookup(String term);
}

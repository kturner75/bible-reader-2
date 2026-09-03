package com.readthekjv.model.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SermonNoteSummaryTest {

    private static final String LONG_BODY =
            "Feed the flock. Take the oversight. Be an example. Everything else in the meeting "
          + "hangs off one of these three, and the visitation rota is where it usually breaks "
          + "down in practice for us. The shepherd leads through, not around.";

    @Test
    void shortBodyIsReturnedWhole() {
        assertEquals("Just a short note.", SermonNoteSummary.snippetOf("Just a short note.", null));
    }

    @Test
    void whitespaceIsFlattened() {
        assertEquals("one two three", SermonNoteSummary.snippetOf("one\n\ntwo   \t three", null));
    }

    @Test
    void withoutAQueryTheLeadingTextIsUsed() {
        String s = SermonNoteSummary.snippetOf(LONG_BODY, null);
        assertTrue(s.startsWith("Feed the flock."), s);
        assertTrue(s.endsWith("…"));
        assertFalse(s.startsWith("…"));
    }

    @Test
    void aMatchLateInTheBodyPullsTheWindowToIt() {
        String s = SermonNoteSummary.snippetOf(LONG_BODY, "shepherd");
        assertTrue(s.toLowerCase().contains("shepherd"), s);
        assertTrue(s.startsWith("…"), "a windowed snippet should show it is not the start: " + s);
    }

    @Test
    void matchIsFoundCaseInsensitively() {
        assertTrue(SermonNoteSummary.snippetOf(LONG_BODY, "SHEPHERD").toLowerCase().contains("shepherd"));
    }

    @Test
    void aQueryThatDoesNotAppearInTheBodyFallsBackToLeadingText() {
        // Matched on the title or a scripture chip, not the body.
        String s = SermonNoteSummary.snippetOf(LONG_BODY, "habakkuk");
        assertTrue(s.startsWith("Feed the flock."), s);
    }

    @Test
    void snippetStaysWithinTheTwoLineBudget() {
        // 140 chars plus at most two ellipses.
        assertTrue(SermonNoteSummary.snippetOf(LONG_BODY, "shepherd").length() <= 142);
        assertTrue(SermonNoteSummary.snippetOf(LONG_BODY, null).length() <= 142);
    }

    @Test
    void windowedSnippetDoesNotStartMidWord() {
        String s = SermonNoteSummary.snippetOf(LONG_BODY, "shepherd");
        String firstWord = s.replaceFirst("^…", "").split(" ")[0];
        assertTrue(LONG_BODY.contains(" " + firstWord) || LONG_BODY.startsWith(firstWord),
                "snippet began mid-word: " + firstWord);
    }

    @Test
    void portableLinkTokensAreStrippedFromTheSnippet() {
        String body = "The shepherd leads through, not around. [v=14237-14242] Close with the house.";
        String snippet = SermonNoteSummary.snippetOf(body, null);
        assertFalse(snippet.contains("[v="), snippet);
        assertEquals("The shepherd leads through, not around. Close with the house.", snippet);
    }

    @Test
    void embedTokensAreStrippedToo() {
        assertEquals("Week one sets the tone. It ties together.",
                SermonNoteSummary.snippetOf("Week one sets the tone. [e=26136] It ties together.", null));
    }

    @Test
    void humanReferencesSurviveBecauseTheyReadAsProse() {
        assertEquals("Read [John 3:16] before the discussion.",
                SermonNoteSummary.snippetOf("Read [John 3:16] before the discussion.", null));
    }

    @Test
    void nullBodyIsSafe() {
        assertEquals("", SermonNoteSummary.snippetOf(null, "anything"));
    }
}

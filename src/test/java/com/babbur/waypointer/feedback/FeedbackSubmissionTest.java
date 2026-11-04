package com.babbur.waypointer.feedback;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackSubmissionTest {
    @Test void messageLimitUsesTheSameUtf16UnitsAsTheServer() {
        String maximum = "\uD83D\uDE00".repeat(5000);
        assertEquals(10_000, draft(maximum, true, "", "", true).message().length());
        assertThrows(IllegalArgumentException.class, () -> draft(maximum + "x", true, "", "", true));
        assertThrows(IllegalArgumentException.class, () -> draft(" \n\t", true, "", "", true));
    }

    @Test void anonymousDiscardsContactAndPreservesEitherSnapshotMode() {
        for (boolean anonymized : List.of(true, false)) {
            String report = anonymized ? "redacted report" : "full report: player=Example, x=123, é";
            var submission = draft("Bug", true, "private@example.com", report, anonymized);
            assertTrue(submission.anonymous());
            assertEquals("", submission.contact());
            assertEquals(report, submission.debugReport());
            assertEquals(anonymized, submission.anonymized());
        }
        assertEquals("full report", draft("Bug", false, "", "full report", false).debugReport());
    }

    @Test void absentSnapshotClearsAnonymizedFlag() {
        for (boolean anonymized : List.of(true, false)) {
            assertFalse(draft("Bug", true, "private@example.com", "", anonymized).anonymized());
            assertFalse(draft("Bug", true, "private@example.com", null, anonymized).anonymized());
        }
    }

    @Test void contactIsOptionalBoundedAndSingleLine() {
        assertEquals("", draft("Bug", false, "", "", false).contact());
        assertThrows(IllegalArgumentException.class, () -> draft("Bug", false, "x".repeat(201), "", false));
        assertThrows(IllegalArgumentException.class, () -> draft("Bug", false, "name\naddress", "", false));
    }

    @Test void reportMustBeBugOnlyAndBoundedInUtf8Bytes() {
        for (boolean anonymized : List.of(true, false)) {
            assertThrows(IllegalArgumentException.class, () -> new FeedbackSubmission("general", "Hello",
                    true, "", "report", anonymized, List.of()));
            String maximum = "é".repeat(FeedbackSubmission.MAX_REPORT_BYTES / 2);
            assertEquals(maximum, draft("Bug", true, "", maximum, anonymized).debugReport());
            assertThrows(IllegalArgumentException.class,
                    () -> draft("Bug", true, "", maximum + "é", anonymized));
        }
    }

    private static FeedbackSubmission draft(String message, boolean anonymous, String contact,
                                            String report, boolean anonymized) {
        return new FeedbackSubmission("bug", message, anonymous, contact, report, anonymized, List.of());
    }
}

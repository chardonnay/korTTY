package de.kortty.core;

/**
 * Translates a journal note into a language the user picks, through the journal's own AI seam:
 * the journal profile (or the default one), with internet access disabled — a note may quote
 * anything the session touched and must not reach a search tool.
 *
 * <p>Deliberately not routed through the snippet translation action: that one extracts comments
 * and string literals out of source code and returns plain prose unchanged.
 */
public final class SessionJournalNoteTranslationSupport {

    private SessionJournalNoteTranslationSupport() {
    }

    /**
     * Blocking; call from a background thread.
     *
     * @return the translated note, never blank
     * @throws IllegalStateException when no AI profile is available or the reply is unusable
     */
    public static String translate(
            SessionJournalAiSupport.AiInvoker invoker,
            String noteText,
            String targetLanguageCode) throws Exception {

        if (noteText == null || noteText.isBlank()) {
            throw new IllegalStateException("There is no note text to translate");
        }
        if (invoker == null || !invoker.isAvailable()) {
            throw new IllegalStateException("No AI profile is available for the session journal");
        }
        AiExecutionResult result = invoker.execute(
            SessionJournalPrompts.noteTranslationSystemPrompt(targetLanguageCode),
            SessionJournalPrompts.noteTranslationUserPrompt(noteText));
        if (result == null) {
            throw new IllegalStateException("The AI returned no reply");
        }
        String translation = SessionJournalAiSupport.parseTranslation(result.content());
        if (translation == null) {
            throw new IllegalStateException("The AI reply contained no usable translation");
        }
        // A truncated reply would silently store half a sentence as the user's own note.
        if (result.outputTruncated()) {
            throw new IllegalStateException("The AI reply was cut short");
        }
        return translation;
    }
}

package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Pins what the model is actually told about the prose inside returned code.
 *
 * <p>This is the contract a user feels: applying a Full-code-analysis result to an English script
 * from a German korTTY used to come back with every comment translated. The behaviour lives
 * entirely in prompt wording, so it is asserted on the prompt rather than inferred from a model's
 * answer.</p>
 */
class CodeTextLanguageContractTest {

    /** Every action whose result is inserted into the user's snippet as code. */
    private static final List<AiAction> CODE_RETURNING_ACTIONS = List.of(
        AiAction.APPLY_SNIPPET_IMPROVEMENTS,
        AiAction.APPLY_SNIPPET_SECURITY_FIXES,
        AiAction.IMPROVE_SNIPPET_CODE,
        AiAction.ASSIST_SNIPPET_CODE,
        AiAction.GENERATE_SNIPPET_ALTERNATIVES,
        AiAction.COMPLETE_SNIPPET_CODE,
        AiAction.GENERATE_SNIPPET_ONE_LINER);

    private static AiRequest request(AiAction action, CodeTextLanguage codeText) {
        AiRequest base = new AiRequest(action, "echo hi", null, "de", null, null, false,
            AiPromptPreset.GENERIC, null, null);
        return codeText != null ? base.withCodeTextLanguage(codeText) : base;
    }

    @Test
    void keepingTheScriptLanguageForbidsTranslationInEveryCodeAction() {
        for (AiAction action : CODE_RETURNING_ACTIONS) {
            String prompt = AiPromptBuilder.buildSystemPrompt(
                request(action, CodeTextLanguage.keep("en")))
                .toLowerCase(Locale.ROOT);

            assertWithMessage("%s must name the script's own language", action)
                .that(prompt).contains("language code en");
            assertWithMessage("%s must forbid translating the snippet's prose", action)
                .that(prompt).contains("do not translate the snippet's prose");
            assertWithMessage("%s must not ask for the old translate-as-needed behaviour", action)
                .that(prompt).doesNotContain("translate existing text within the returned replacement scope");
        }
    }

    /**
     * The interface language must still reach the summary. Report text the user reads inside korTTY
     * legitimately follows korTTY's own language even when the script's comments must not.
     */
    @Test
    void theSummaryStillFollowsTheInterfaceLanguage() {
        String prompt = AiPromptBuilder.buildSystemPrompt(
            request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, CodeTextLanguage.keep("en")));

        assertThat(prompt).contains("Write summary and every reason in language code de");
        assertThat(prompt).contains("language code en");
    }

    @Test
    void choosingALanguageDeliberatelyStillTranslates() {
        String prompt = AiPromptBuilder.buildSystemPrompt(
            request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, CodeTextLanguage.translateInto("fr")));

        assertThat(prompt).contains("must be in language code fr");
        assertThat(prompt).contains("Translate existing text within the returned replacement scope");
        assertThat(prompt).doesNotContain("Do not translate the snippet's prose");
    }

    /** Without a resolved contract nothing changes, so an un-migrated caller keeps working. */
    @Test
    void anAbsentContractLeavesThePreviousBehaviourInPlace() {
        String prompt = AiPromptBuilder.buildSystemPrompt(
            request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, null));

        assertThat(prompt).contains("must be in language code de");
        assertThat(prompt).contains("Translate existing text within the returned replacement scope");
    }

    /** A blank code is not a language; it must not reach the prompt as one. */
    @Test
    void aBlankLanguageCodeFallsBackRatherThanBeingSent() {
        for (String blank : new String[]{null, "", "  "}) {
            String prompt = AiPromptBuilder.buildSystemPrompt(
                request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, CodeTextLanguage.keep(blank)));
            assertThat(prompt).contains("language code de");
            assertThat(prompt).doesNotContain("language code null");
        }
    }

    /** The decorator is what puts the contract on the request; it must not drop or invent one. */
    @Test
    void theDecoratorStampsExactlyWhatItWasGiven() throws Exception {
        AiRequest[] seen = new AiRequest[1];
        AiService capture = new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                seen[0] = request;
                return null;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };

        CodeTextLanguage contract = CodeTextLanguage.keep("en");
        new CodeTextLanguageAiService(capture, () -> contract)
            .execute(request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, null));
        assertThat(seen[0].codeTextLanguage()).isEqualTo(contract);

        new CodeTextLanguageAiService(capture, () -> null)
            .execute(request(AiAction.APPLY_SNIPPET_IMPROVEMENTS, null));
        assertThat(seen[0].codeTextLanguage()).isNull();
    }

    /**
     * The migration: a user who never opened the picker had the interface language forced onto
     * their scripts. An unset preference must now mean "keep the script's language".
     */
    @Test
    void anUnsetPreferenceMeansKeepTheScriptLanguage() {
        for (String unset : new String[]{null, "", "   ", "auto", "AUTO"}) {
            assertWithMessage("%s must be treated as automatic", unset)
                .that(AiLanguageSupport.isAutomatic(unset)).isTrue();
        }
        for (String chosen : new String[]{"de", "en", "fr"}) {
            assertWithMessage("%s is a deliberate choice, not automatic", chosen)
                .that(AiLanguageSupport.isAutomatic(chosen)).isFalse();
        }
    }
}

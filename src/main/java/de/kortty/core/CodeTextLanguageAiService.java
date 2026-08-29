package de.kortty.core;

import java.util.function.Supplier;

/**
 * Stamps every snippet AI request with the contract for prose inside returned code.
 *
 * <p>Seven actions return code that lands in the user's snippet, and each builds its own request.
 * Threading the contract through all of them meant a new argument on seven long parameter lists and
 * nineteen call sites — and a future eighth action would silently miss it, which is precisely the
 * failure this feature exists to prevent. A decorator applied where the snippet editor resolves its
 * service covers every action, including ones not written yet.</p>
 *
 * <p>The value is read per request rather than captured: the user can change the language choice
 * while the editor is open, and the snippet's own content changes as they type.</p>
 *
 * <p>Deliberately narrow — it implements {@link AiService} and nothing else. It wraps a
 * freshly-created, editor-scoped service that is only ever executed, never introspected.</p>
 */
public final class CodeTextLanguageAiService implements AiService {

    private final AiService delegate;
    private final Supplier<CodeTextLanguage> codeTextLanguage;

    public CodeTextLanguageAiService(AiService delegate, Supplier<CodeTextLanguage> codeTextLanguage) {
        this.delegate = delegate;
        this.codeTextLanguage = codeTextLanguage;
    }

    /** The wrapped service, for callers that need the undecorated instance. */
    public AiService delegate() {
        return delegate;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        CodeTextLanguage language = codeTextLanguage != null ? codeTextLanguage.get() : null;
        if (request == null || language == null || !language.isUsable()) {
            // No resolved contract: leave the request untouched so the prompt keeps its previous
            // behaviour rather than inventing a language.
            return delegate.execute(request);
        }
        return delegate.execute(request.withCodeTextLanguage(language));
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }
}

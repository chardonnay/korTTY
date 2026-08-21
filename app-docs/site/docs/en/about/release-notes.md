# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.12.0

### Session journal: complete under load, smaller on disk

- **A server flood can no longer punch holes into the journal** — when output arrived faster than the capture log could persist it, up to 10,000 queued lines were silently dropped with nothing in the journal to show for it. The capture now applies backpressure instead: the terminal may briefly slow down under an extreme flood, but every line lands in the log. The log writer also batches its disk writes, so it keeps up with far more output in the first place.
- **Repeated output lines are coalesced** — progress loops, `tail -f` repeats and similar floods are stored syslog-style: the first occurrence immediately, follow-ups as one entry with a repeat count. The [journal page](../features/session-journal.md#storage-and-formats) shows such a run compactly as `line ×12`; copying the log reproduces the original lines in full, and the AI summarizer sees the count instead of thousands of identical lines.
- **Rotated log parts are now zstd-compressed** — finished capture-log parts compress to `.zst` instead of `.gz`, which is both faster and noticeably smaller on repetitive terminal output. Journals recorded by earlier versions keep their `.gz` parts and open, export and redact exactly as before — even mixed within one journal.
- **Log rotation is configurable per connection** — the Journal tab gained **Maximum size per log part (MB)** (default 25) and **Maximum rotated log parts** (default 20). Previously both were fixed, and a long noisy session simply stopped recording output after 20 parts. An enterprise policy can cap the part count with the new `max-log-parts` key.

### Session journal: AI search and Q&A

- **Ask the AI about a recorded session** — the viewer's new [AI Q&A panel](../features/session-journal.md#asking-the-ai-about-a-journal) answers questions like *"Were screenshots taken that show errors from this script?"* from what the journal already collected (summaries, screenshot analyses, notes) — the raw capture log is never sent to a model. When a question needs hard evidence, the model names literal search strings, korTTY's own streaming search scans the log, and only match counts plus a few sample lines go back for the final answer. Answers cite their sources: clicking a source scrolls the timeline to the entry, clicking a log-evidence line opens the log panel scrolled to the very line. Follow-up questions continue the conversation, and **Save as note** appends a Q&A pair to the timeline.
- **One question across all journals** — the manager's new [AI search](../features/session-journal.md#ai-search-across-all-journals) answers questions like *"In which journals did result_complex.pl exit with an error?"*: a fast local ranking over the journals' collected entries picks the candidates, one AI request writes the summary and selects the relevant journals, and the exact hits are materialized by the internal search. The hit tree jumps straight to the entry or log line, already-opened hits stay marked across searches and restarts, the table shows a sorted **Hits** column with a row highlight instead of filtering, and **Selection only** restricts the scope. Without a reachable model the search degrades to the pure text search with a notice. Optional **Semantic journal search** adds embedding-based ranking when a local embedding model is configured in the knowledge stores.
- **Journals now carry AI keywords** — the closing session summary extracts up to twelve verbatim search terms (hostnames, script and file names, error classes) into the journal metadata; the manager's filter matches them and shows them as clickable **keyword chips** under the filter field.
- **Catch up summaries** — the journal options can now run the summarizer over closed journals that were never summarized, one by one behind a cancellable progress dialog.
- **The manager's content search reads logs streaming** — **Search contents** no longer decompresses whole capture logs into memory; parts are scanned line by line, so searching across many large journals stays lightweight.
- **New policy key `ai-ask`** — an organization can forbid the on-demand AI over journal content (Q&A panel and cross-journal AI search) while keeping AI summaries.

### Security

- **Removed `net.i2p.crypto:eddsa`, an abandoned dependency with an unpatched signature-malleability flaw** (CVE-2020-36843) — Ed25519 SSH host keys and key-based authentication are now served by BouncyCastle, already part of korTTY, with no user-visible change in behavior.
- **A failed connection to a host on your local network now explains itself** — since macOS 15, a missing Local Network permission makes any local connection fail exactly like a powered-off host (`No route to host`), with nothing pointing at the actual cause. korTTY now adds a hint naming the permission when the failure and the network shape match, without ruling out a genuine outage.

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.

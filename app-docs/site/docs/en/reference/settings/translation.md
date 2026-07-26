---
title: Translation
---

# Translation

Configure dynamic translation of korTTY's user interface using external translation APIs. This tab allows you to select a translation provider, authenticate with its API, and generate language files to display the UI in your target language. Open via **Configuration → Global Settings → Translation**; stored in `~/.kortty/global-settings.xml`.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| System language | text | — | System locale | — |
| Translation API | dropdown | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate, Local AI text profile | Google Translate | `translationApiProvider` |
| API Key | text | — | — | `encryptedTranslationApiKey` |
| API URL (optional) | text | — | — (null = use provider default) | `translationApiUrl` |
| Test API Connection | button | — | — | — |
| Target language | dropdown | System locale and available locales (Locale objects) | System locale | — |
| Generate Language File | button | — | — | — |
| Generated languages | list | Available dynamically translated language files | — | — |
| Delete | button | (removes selected generated language file) | — | — |
| Regenerate outdated | button | (visible when outdated files exist) | — | — |

!!! note
    **API Key storage:** The API key is encrypted using your master password and stored securely in `global-settings.xml`. If the master password vault is locked, you will receive a warning and the key will be kept in the field until you can unlock the vault or set a master password in Settings.

!!! note
    **Generated languages:** The "Generated languages" list displays language files that have been created via the Generate Language File button. Each generated file corresponds to a dynamically translated UI in that target language. Use the Delete button to remove a language file, or use Regenerate outdated to update files created with an older app version to include newly added translation keys.

## Local translation

Choose **Local AI text profile** to translate through the embedded llama.cpp profile assigned to the Text/translation role in **AI > AI Manager > Local AI**. API URL and API Key are disabled for this provider because the authenticated loopback endpoint is managed by korTTY. The local model must return one strict JSON `translations` array with the same number and order of input strings; invalid output stops generation instead of silently misaligning UI labels.

!!! warning
    **Credentials:** External providers require their normal API key, except a LibreTranslate endpoint that is explicitly configured without one. Local AI requires no translation-provider key, but its GGUF model and llama.cpp runtime must be installed and the master-password vault must be unlocked when the selected AI profile needs any encrypted secret.

## Guide translation

A second, independent job below translates the bundled offline guide — the same site this page is part of — into the target language selected above, so the in-app **Help → Guide** window and its search can be read fully in that language rather than only the interface labels.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| AI profile | dropdown | "Default (text profile)" plus every configured AI profile | Default (text profile) | — |
| Estimate duration | button | — | — | — |
| Start translation | button | — | — | — |
| Cancel | button | (shown only while a translation is running) | — | — |
| Translated guides | list | Guide languages translated so far | — | — |
| Delete | button | (removes the selected translated guide, its assets and its search index) | — | — |

Unlike the interface strings, the guide is translated by an **AI profile**, not by the translation-provider dropdown above — a cloud translation API is not built to carry hundreds of pages of formatted documentation. Picking a profile in the **AI profile** dropdown here always uses that profile, whatever its connection mode; leaving it on **Default (text profile)** uses the profile assigned to the Text/translation role in AI Manager, and only if that profile runs an embedded (local) model.

Deleting a translated guide removes its whole directory under `~/.kortty/guide/<language>/` — the translated pages, the staged theme assets, its search index and its resume checkpoint — after a confirmation prompt.

### Why this runs in the background

Translating the guide is not a quick action: the bundled site holds roughly 5,300 distinct pieces of text once repeated navigation and headings are deduplicated, and depending on the model this can take anywhere from a few minutes to most of a day. Starting it does not block the Settings dialog or the rest of korTTY:

- The translation keeps running after you close Settings, switch tabs, or continue working in terminals.
- A small progress indicator — a bar, a percentage and an estimated time remaining — appears at the right end of korTTY's menu bar for as long as a guide translation is in progress, and disappears again once it finishes.
- **Cancel** stops the job at the next safe point rather than losing what has already been translated: progress is checkpointed to disk as it goes, so starting the same language again continues from where it left off instead of starting over.
- If you try to quit korTTY while a guide translation is running, a dialog offers to pause it and quit, or to keep korTTY open; choosing to pause leaves the checkpoint in place for the next run.
- After installing a korTTY update that changed the guide's content, and if you already have a translated guide for your language, korTTY offers once to bring it up to date. Because progress is checkpointed by the exact English text of each piece, updating only re-translates sentences that actually changed in the release — everything else is reused as-is.

### Why a reasoning model is a poor fit

Some AI models are built to "think out loud" before answering — writing an extended chain of reasoning into their reply before the actual output, a technique tuned for hard problems like math or coding. That reasoning is not part of the translation, but the model still has to generate it, and generating text is the slow part of running a local model. Measured on this project's own guide, a reasoning model produced about **4.4 completion tokens for every input token** — roughly 4,400 tokens of reasoning to translate 1,000 characters of prose. On identical hardware this was the difference between a translation run of about an hour and one of six hours or more.

If the **AI profile** you pick for guide translation is a reasoning model, korTTY warns you before starting or estimating, naming the model and letting you continue anyway or cancel. The check looks at the model's name (publishers of reasoning models advertise it there — "reasoning", "thinking", "R1", "QwQ", "o1" and similar are recognized), so it costs nothing to run and may occasionally miss a differently-named reasoning model, but it will not falsely warn about a plain instruct model.

**Suitable models** are instruction-tuned ("instruct") chat models without a reasoning/thinking mode — the kind normally used for translation, summarization or rewriting rather than multi-step problem solving. For an embedded local profile, look for MLX or GGUF builds whose name includes "instruct" and not "reasoning", "thinking" or "R1", for example a Qwen2.5-Instruct or Phi-4-mini-instruct build at a size your hardware can run comfortably (4-bit quantized 7-8B models are a practical default on Apple Silicon). Larger instruct models generally translate more fluently at the cost of speed; the duration estimate below lets you compare before committing to a full run.

### Estimating how long a full translation will take

**Estimate duration** measures the AI profile selected above against a real, representative sample of the guide's text and projects how long translating everything still outstanding would take, without committing to the full run:

1. It sends one small, untimed "warm-up" request first, so that a local model's one-time loading cost — which is paid once for an entire run, not per batch — is not mistaken for ongoing translation speed and multiplied across every batch of the projection.
2. It then translates one real, budget-sized batch of guide text and times it. This is a genuine translation, not a simulation: the sample is kept, so estimating first and then starting a full run does not repeat that work.
3. From that single timed batch, it computes two projections for the remaining text — one assuming cost scales with the number of requests, one assuming it scales with the amount of text — and reports the resulting range together with how long the remaining pages and text would take. Reporting a range rather than one number reflects that a single sample cannot fully separate a model's fixed per-request overhead from its per-character cost; the estimate keeps that uncertainty visible rather than hiding it behind a single overly precise number.

If the sample fails outright — the AI profile is unreachable, misconfigured, or produced nothing usable — the estimate reports that as a connection problem instead of a duration, so it is not mistaken for "translation will be instant."

!!! note
    The reasoning-model warning above appears for **Estimate duration** as well as **Start translation**, since running an estimate against a reasoning model already spends real time on it.

---
title: Local models with llama.cpp
---

# Local models with llama.cpp

korTTY can run GGUF language models directly through an integrated, model-specific `llama-server`. You do not need LM Studio, Ollama, or a cloud inference account for local chat, text, coding, translation, and embedding workloads.

Open **AI > AI Manager** and use these tabs:

- **Local Models** installs, imports, configures, starts, stops, and removes GGUF registrations.
- **Local AI** assigns profiles to the Text/translation and Coding roles, selects the RAG embedding model, stores an optional Hugging Face token, and records the llama.cpp runtime-update policy.
- **Profiles** creates an **Integrated llama.cpp** profile for an installed model and controls its prompt optimization preset.

![Local Models showing a GGUF download with size, progress, transfer rate, and remaining time](../assets/screenshots/ai/local-models.png)

!!! note "Storage and network access"
    Model inference stays on this computer. korTTY contacts Hugging Face only when you search for or download a model that you approve. Public repositories work without a token; an optional token for private or gated repositories is encrypted with the master password. Official builds can also fetch the separately signed model/prompt catalog over HTTPS in the background; no prompt, source document, or model weight is sent with that request.

## Quick setup assistant

Select **AI > AI Manager > Local Models > Setup assistant**. The six-step assistant covers privacy, detected system memory and backend guidance, optional role-specific recommendations, license and exact download-size review, verified installation, and a final readiness summary.

On **Choose models for optional roles**, enable any combination of **Text and translation**, **Coding**, and **RAG embeddings**. Every enabled slot has its own recommendation selector; disabled slots are left unchanged. Text and Coding may deliberately share one compatible recommendation, in which case korTTY downloads and registers that GGUF only once. Opening the assistant from a missing-embedding warning preselects only the RAG-embedding slot.

The built-in bootstrap catalog uses conservative RAM tiers:

| Detected memory | Text recommendation | Coding recommendation | RAG embeddings |
| --- | --- | --- | --- |
| Less than 16 GiB | Qwen3 1.7B, `Q4_K_M` | Qwen3 1.7B, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |
| 16–23 GiB | Qwen3 4B, `Q4_K_M` | Qwen2.5-Coder 7B Instruct, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |
| 24 GiB or more | Qwen3 8B, `Q4_K_M` | Qwen2.5-Coder 7B Instruct, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |

Recommendations are starting points, not hardware guarantees. The model table labels the estimated fit as **Comfortable**, **Possible**, **Too large**, or **Unknown**; context size and simultaneous models also affect memory use.

Before **Install** becomes available, korTTY loads each selected repository at the catalog's fixed revision in the background, verifies that the requested quantization exists, and displays the pinned repository, license, and exact combined GGUF download size. You must review and accept those values. A mutable repository revision or missing quantization stops the assistant before any download starts.

Runtime installation, GGUF downloads, verification, registration, and function tests run asynchronously so the JavaFX dialog remains responsive. The progress view names the current model and phase; **Cancel installation** stops at a safe boundary and retains resumable `.part` files, while **Retry** restarts a failed or cancelled run from the verified selections.

The Hub-reported context length remains visible for comparison, but a new local model starts with the conservative 4,096-token runtime context. Increase it later under **Configure** only after considering the additional RAM/VRAM use.

After every selected GGUF is downloaded and registered, the assistant sends a real local chat-completion request or, for an embedding-only model, a real embedding request through the installed runtime. Only after all tests pass does it atomically save the selected Text/Coding profile IDs and RAG embedding-model ID in `global-settings.xml`; it creates a reusable **Local: …** embedded profile when a selected chat model has none and makes the first such profile the default only when no default exists. A failed test does not produce a successful finish page or persist partially updated role assignments.

## Signed model and prompt catalog

korTTY always contains a small bootstrap catalog with the memory-tier recommendations and prompt-family detection needed for offline setup. All five bundled model recommendations carry a concrete 40-character Hugging Face commit, so even the bootstrap never resolves a mutable repository head when the assistant later fetches metadata or GGUF files. Official builds can update those data independently from the application through `model-prompt-catalog-v1.json` and its detached Ed25519 signature `model-prompt-catalog-v1.sig`.

On the first use of recommendations or automatic prompt detection, korTTY immediately loads the last signature-verified cache and performs at most one background refresh from the fixed HTTPS stable channel. The catalog can update recommended model IDs, fixed revisions, quantizations, roles, RAM thresholds and ordering, plus the mapping from model-name tokens to built-in prompt presets. It cannot inject arbitrary executable code or replace korTTY's built-in action, safety, JSON, or code-output contracts.

The exact catalog bytes are verified before strict schema-v1 parsing, and unknown schema fields are rejected. A failed download, invalid signature, or invalid catalog leaves the last valid cache untouched. If the application build has no valid catalog public trust root, korTTY performs no catalog network request, does not trust an existing cache, and uses only the built-in bootstrap.

Each catalog also carries a positive monotonic sequence. During refresh, korTTY rejects a signed catalog whose sequence is older than the last accepted cache or bootstrap, and rejects an equal-sequence catalog with a different version. A newly accepted catalog with the highest sequence must be written to the atomic cache before it becomes active; the protected promotion workflow separately requires every official sequence to be strictly greater than the sequence in the latest published release, preventing a correctly signed older catalog from replaying obsolete recommendations.

## Find and download GGUF models

The Hugging Face browser searches GGUF repositories and shows repository, architecture, available quantizations, license, selected-quantization size, context length, and a hardware estimate. The lightweight search response contains filenames but not dependable per-file sizes, so korTTY automatically selects the first result and shows **Loading…** while it retrieves exact metadata for that repository's immutable revision. When you select another row, korTTY repeats this lookup and then updates both **Size** and **Hardware estimate**. The chosen quantization remains selected during this refresh. Projector, quantization-matrix, and speculative-decoding helper GGUFs are excluded from the downloadable language-model choices. Results use the Hub's cursor pagination. **Load more** continues the same search without discarding earlier results. Select a repository and quantization, review its license and size, then choose **Download and install**.

korTTY installs only an immutable 40-character repository revision with exact GGUF metadata. After you confirm the license and download size, a fixed **Model download** panel at the bottom identifies the repository and quantization, the current GGUF file and multipart shard, transferred and total bytes, elapsed time, transfer rate, and estimated remaining time. Its full-width progress bar and **Pause**/**Resume** and **Cancel** controls remain available while you continue to review the manager. Downloads use `.part` files, free-space checks, HTTP `Range` and `If-Range`, SHA-256 verification, and multipart GGUF ordering. Cancellation, including closing the AI Manager during a transfer, keeps the partial data needed for a later resume. An unpinned repository or a missing file checksum is rejected instead of silently installing mutable content.

## Import existing GGUF files

Select **Import GGUF**, choose one or more `.gguf` files, and then choose one mode:

| Mode | Behavior |
| --- | --- |
| **Managed copy** | Copies the GGUF into korTTY's model directory using a temporary file and atomic activation where the filesystem supports it. |
| **External reference** | Registers the original path. Moving or deleting that file makes the model unavailable; removing the registration never deletes the external file. |

A compatible, verified `llama-server` runtime must also be installed. Runtime packages live separately under `~/.kortty/llm/runtime/` and are not part of the base application installer. When you download or import the first model, korTTY offers to install the matching signed stable runtime before continuing; you can also choose **Install runtime** in **Local Models**. The model manager does not accept an arbitrary executable as a replacement for the verified package.

## Configure and run models

Select an installed model and choose **Configure**. The available settings are deliberately typed and bounded; arbitrary server arguments are not accepted. Saving first asks the runtime manager to stop the model. If that model is processing a request, korTTY refuses the change and leaves both the running sidecar and persisted configuration untouched; retry after the request finishes.

| Setting | Values | Default |
| --- | --- | --- |
| Backend | Auto, CPU, Metal, Vulkan | Auto |
| Context size | 512–2,097,152 tokens | 4,096 |
| CPU threads | 0–1,024; `0` means automatic | Automatic |
| GPU layers | -1–10,000; `-1` means automatic | Automatic |
| Unload after | 1–1,440 minutes, or **Never** | 10 minutes |

The per-model backend describes how that model should run. In **AI Manager > Local AI**, **Preferred runtime backend** controls which signed native package is installed and updated: macOS offers Auto, CPU, and Metal; Windows/Linux offer Auto, CPU, and Vulkan. **Automatic (keep active backend)** preserves the already active backend during updates; for a first installation it selects Metal on macOS and CPU elsewhere.

Use multi-selection and **Start selected** to load several different models concurrently. Profiles that point to the same installed model and runtime configuration share one authenticated sidecar. The table reports `STOPPED`, `STARTING`, `LOADING`, `READY`, `BUSY`, `SLEEPING`, or `FAILED`.

If a selected Metal or Vulkan model requires a different package from the active runtime, korTTY offers to download, verify, and activate the matching signed package without interrupting current requests. Models that require incompatible GPU runtimes must be started separately with the matching preferred backend.

After the configured idle time, llama.cpp releases the model tensors from RAM/VRAM and korTTY marks the model as sleeping while retaining the lightweight process. The next request acquires a runtime lease and wakes the model automatically. **Stop selected** terminates only idle sidecars; an active generation is never interrupted by model removal or a normal stop request.

## Text, coding, translation, and embedding roles

In **AI Manager > Local AI**, choose separate profiles for **Text and translation** and **Coding**. Leaving either choice at **Use default AI profile** preserves the normal fallback. Terminal summarization, problem solving, questions, descriptions, and translation use the Text role; snippet generation, completion, analysis, security fixes, and workflow generation use the Coding role.

Profile resolution follows the most specific available choice: an explicitly selected profile, then a security-specific profile or connection profile where applicable, then the Text/Coding role, then the default profile. The same role mechanism works with embedded, remote HTTP, or local CLI profiles; a role does not force a local model.

Dynamic UI translation can use **Local AI text profile** under **Configuration > Global Settings > Translation**. This path sends the translation request to the assigned embedded Text profile and does not require a translation-provider API key.

The **RAG embedding model ID** identifies the installed local model used to vectorize knowledge-store documents and searches. Use a dedicated embedding GGUF rather than a chat model unless that model explicitly supports the embeddings route and the configured vector dimensions.

## Prompt optimization

Each AI profile has a **Prompt optimization** preset. **Auto (model detection)** uses the verified catalog's model-name mapping, whose bootstrap recognizes Qwen, DeepSeek, Mistral/Mixtral, Gemma, Phi, GPT-OSS, and Llama names; **Generic** adds no family-specific guidance. You can also force any family preset when a model name is unusual.

Presets add short compatibility instructions after korTTY's action contract and AI Skills while preserving the existing strict JSON, code-payload, and safety requirements. GGUF chat templates themselves remain llama.cpp's responsibility. The preset asks supported models to return only the requested final format and to keep reasoning traces out of JSON/code responses.

## Runtime isolation and updates

Every loaded configuration starts `llama-server` on `127.0.0.1` with a random port and a generated per-process API key. korTTY passes a fixed model path, removes inherited `LLAMA_ARG_*` and Hugging Face token overrides, and starts the pinned server with offline mode plus its web UI, agent, UI MCP proxy, and slot endpoint disabled. Chat and embeddings use authenticated OpenAI-compatible local routes.

The runtime build is independent of the application installer. `build.gradle.kts` pins the upstream llama.cpp tag, full commit, source-archive SHA-256, API-contract version, and korTTY package revision. Release CI builds CPU, Metal, and Vulkan variants for the supported operating-system/architecture matrix and starts every executable; the Linux x86_64 CPU reference package additionally exercises the complete authenticated server contract. Immutable descriptors are published only through an explicit human promotion job. CUDA is not a v1 backend.

The update format uses the detached Ed25519 signature `runtime-index-v1.sig` over the exact bytes of `runtime-index-v1.json`. Each entry binds the runtime ID, upstream tag and commit, API-contract version, minimum korTTY version, platform, architecture, backend, compressed size, SHA-256, HTTPS URL, executable path, and revocation status. A release build contains the public verification key; a missing or invalid trust root, index signature, package size, or package hash stops the operation before untrusted code is activated.

A package is installed beside the active version and checked locally with a bounded `llama-server --version` launch before activation. Activation and the atomic rebinding of registered models occur only while all local inference is idle. The candidate then remains **pending first launch** until a real GGUF-backed server reaches its authenticated API: success promotes it into the healthy history, while that first real startup failing restores the newest non-revoked previous runtime, rebinds affected models, removes the failed package, and restarts runtime management. The two newest healthy installations are retained, and a package staged during active work is retried after the requests finish. Release CI performs the deeper authenticated chat, embedding, JSON-schema, sleep/wake, and parallel-sidecar contract tests before publication.

A verified index withdrawal is enforced immediately for **Notify me** and **Install stable updates automatically** checks. korTTY first persists the revoked runtime/installation IDs in a durable denylist and writes a package-local quarantine marker, then clears the active pointer, stops its sidecars, removes revoked versions from rollback history, and replaces affected model bindings with a non-executable marker. Both the runtime installer and every new process launch consult those guards, so an interrupted cleanup or stale `models.xml` entry cannot restart the withdrawn executable. Local AI remains blocked until a compatible signed replacement is installed; the main window and Local Models status identify the revoked runtime and whether a verified replacement is available. **Off** performs no network check, so it cannot learn about a newly published withdrawal until the user explicitly checks or enables signed-index checks.

The **llama.cpp runtime updates** choice controls the automatic check started with korTTY and whenever the preference is saved:

| Policy | Startup behavior |
| --- | --- |
| **Off** | Performs no runtime-update network request. A non-revoked installed runtime remains usable, while every withdrawal already persisted locally stays enforced. |
| **Notify me** (default) | Verifies the signed stable index and shows a notification when a compatible package is available, without automatically installing it. A verified withdrawal is enforced immediately and blocks the active package. |
| **Install stable updates automatically** | Downloads, verifies, installs, and activates a compatible stable package, including a safe replacement for a withdrawn active version; active inference delays normal activation instead of being interrupted. |

The runtime row in **Local Models** shows the current update or installation state. Its action is **Install runtime** when no verified package is active and **Check/install runtime update** otherwise. This explicit action checks and installs the stable channel even when the stored automatic policy is **Off** or **Notify me**. Runtime candidates are discovered by a daily workflow, but promotion remains a deliberate, reviewed release action rather than adopting every upstream tag automatically.

## Files and backup behavior

| Path | Purpose | Included in a korTTY backup? |
| --- | --- | --- |
| `~/.kortty/global-settings.xml` | Embedded profiles, Text/Coding assignments, embedding model ID, runtime backend/update policy, encrypted Hugging Face token | Yes |
| `~/.kortty/llm/models.xml` | Local model registrations and typed runtime settings | Yes |
| `~/.kortty/llm/models/` | Managed GGUF weights | No; download or copy them again |
| `~/.kortty/llm/runtime/` | Regenerable llama.cpp packages and active-package metadata | No; reinstall a compatible package |
| `~/.kortty/llm/catalog/last-valid-catalog-v1.json` | Regenerable signature-verified model/prompt catalog cache | No; korTTY reverts to the bootstrap and refreshes it again |
| `~/.kortty/llm/run/` | Temporary sidecar keys and logs | No |

## Troubleshooting

**No llama.cpp runtime is installed**
: Open **AI > AI Manager > Local Models** and choose **Install runtime**, or accept the installation prompt when downloading or importing a model. korTTY downloads the matching signed stable package; the application installer intentionally does not contain a native runtime.

**A signed runtime check or installation fails**
: Confirm that this korTTY build contains the official runtime-channel public key, that HTTPS access to the stable index and package is available, and that the platform/backend combination is published. korTTY fails closed instead of bypassing a missing trust root, invalid signature, checksum mismatch, incompatible API contract, or runtime quarantine.

**The runtime is reported as revoked**
: Open **AI > AI Manager > Local Models** and install the offered verified replacement. Do not remove the package marker or edit `models.xml`: the durable denylist still blocks the installation, and revoked versions are deliberately ineligible for rollback or reinstallation. If no compatible replacement is listed, local AI stays unavailable until the stable channel publishes one for this platform/backend.

**A new runtime rolls back on its first model start**
: The lightweight `--version` check passed, but the first real GGUF-backed authenticated API start failed. korTTY restores the newest healthy non-revoked package when available and reports **Rolled back**; inspect the Local Models error, model/backend compatibility, and memory settings before trying the update again.

**A model remains in `FAILED`**
: Verify that the GGUF and executable still exist, that the executable is runnable, and that the chosen backend is available on this computer. Reduce context size or simultaneous models when system or GPU memory is insufficient.

**The setup function test fails after installation**
: The GGUF remains registered so you can inspect it. Confirm that the matching signed runtime backend is active, reduce the model context or GPU layers if memory is tight, and retry by starting the model. Embedding tests additionally require readable GGUF embedding-dimension metadata.

**A gated Hugging Face repository returns an authorization error**
: Accept the repository terms on Hugging Face, unlock the korTTY master-password vault, and save an authorized token under **AI Manager > Local AI**. The token is sent only to the trusted Hugging Face host, not to redirected storage hosts.

**A download cannot resume**
: If the repository's ETag or immutable file metadata changed, korTTY restarts that file instead of appending incompatible bytes. A checksum mismatch deletes the invalid partial file.

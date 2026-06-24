---
title: Translation
---

# Translation

Configure dynamic translation of korTTY's user interface using external translation APIs. This tab allows you to select a translation provider, authenticate with its API, and generate language files to display the UI in your target language. Open via **Configuration → Global Settings → Translation**; stored in `~/.kortty/global-settings.xml`.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| System language | text | — | System locale | — |
| Translation API | dropdown | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate | Google Translate | `translationApiProvider` |
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

!!! warning
    **API key required:** To test the connection or generate a language file, an API key for your chosen translation provider must be entered. The Test API Connection button validates that the provider and key are correct before attempting to generate a file.
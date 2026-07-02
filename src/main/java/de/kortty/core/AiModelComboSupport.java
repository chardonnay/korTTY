package de.kortty.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for building the AI model picker used by the AI Manager and the Settings dialog.
 *
 * <p>The item order is: the "Default" label, then the "Auto" label only for endpoints where an
 * automatic model can actually be detected (a local LM Studio-style server), then curated provider
 * suggestions from {@link AiCloudModelCatalog}, then any live-loaded model ids, then the current
 * custom value. "Auto" is deliberately withheld for remote cloud endpoints because
 * {@link AiServiceFactory} cannot auto-resolve a model there.
 */
public final class AiModelComboSupport {

    private AiModelComboSupport() {
    }

    /**
     * True when "Auto" (detect the loaded model) is meaningful for this endpoint, i.e. a local
     * LM Studio-style server whose loaded model can be queried. Delegates to the factory's
     * check so the picker agrees with what {@link AiServiceFactory#create} accepts at runtime
     * (the factory normalizes URLs like {@code http://127.0.0.1:1234/v1} first).
     */
    public static boolean supportsAutoModel(String apiUrl) {
        return AiServiceFactory.canAutoResolveLocalModel(apiUrl);
    }

    /**
     * Builds the ordered, de-duplicated list of model picker items for {@code apiUrl}.
     *
     * @param defaultLabel the localized "Default" label (added first when non-null)
     * @param autoLabel    the localized "Auto" label (added only when {@link #supportsAutoModel})
     * @param apiUrl       the configured endpoint URL (decides Auto availability and suggestions)
     * @param loadedModels model ids fetched live from the endpoint (may be null/empty)
     * @param currentValue the value currently in the editor, preserved if not already present
     */
    public static List<String> buildModelItems(
        String defaultLabel,
        String autoLabel,
        String apiUrl,
        List<String> loadedModels,
        String currentValue) {

        List<String> items = new ArrayList<>();
        if (defaultLabel != null) {
            items.add(defaultLabel);
        }
        if (autoLabel != null && supportsAutoModel(apiUrl)) {
            items.add(autoLabel);
        }
        for (String suggestion : AiCloudModelCatalog.suggestedModelsForUrl(apiUrl)) {
            if (suggestion != null && !suggestion.isBlank() && !items.contains(suggestion)) {
                items.add(suggestion);
            }
        }
        if (loadedModels != null) {
            for (String model : loadedModels) {
                if (model != null && !model.isBlank() && !items.contains(model)) {
                    items.add(model);
                }
            }
        }
        if (currentValue != null && !currentValue.isBlank() && !items.contains(currentValue)) {
            items.add(currentValue);
        }
        return items;
    }
}

package de.kortty.core;

import java.util.List;

/**
 * Optional hybrid classifier for selecting relevant AI skills by metadata only.
 */
@FunctionalInterface
public interface AiSkillRelevanceClassifier {

    List<String> classify(
        AiSkillRelevanceSelector.SelectionContext context,
        List<AiSkillRelevanceSelector.SkillMetadata> skills) throws Exception;
}

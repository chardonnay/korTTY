package de.kortty.ui;

import de.kortty.core.SnippetDiagramRejection;

/**
 * The few localized words that say why an AI diagram could not be used. The rejection reasons are
 * precise English sentences meant for the log ("Unsupported Mermaid syntax on line 7: …"); inside
 * a German notice they read as noise, so the notice names the category and the sentence moves to
 * the log and the notice's tooltip.
 */
final class SnippetDiagramFallbackText {

    private SnippetDiagramFallbackText() {
    }

    /** The short localized reason for a rejection reason from the diagram validation. */
    static String shortReason(String rejectionReason) {
        return I18n.get(switch (SnippetDiagramRejection.classify(rejectionReason)) {
            case NO_DIAGRAM -> "snippets.ai.diagram.rejection.noDiagram";
            case SYNTAX -> "snippets.ai.diagram.rejection.syntax";
            case STRUCTURE -> "snippets.ai.diagram.rejection.structure";
            case TOO_LARGE -> "snippets.ai.diagram.rejection.tooLarge";
            case UNSAFE -> "snippets.ai.diagram.rejection.unsafe";
        });
    }
}

package de.kortty.core;

import java.util.Objects;

/**
 * Builds prompts for terminal-selection AI actions.
 */
public final class AiPromptBuilder {

    private AiPromptBuilder() {
    }

    public static String buildSystemPrompt(AiRequest request) {
        String languageCode = request != null && request.responseLanguageCode() != null && !request.responseLanguageCode().isBlank()
            ? request.responseLanguageCode().trim()
            : "en";
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_METADATA) {
            return "You generate metadata for reusable code snippets. "
                + "Return exactly one JSON object with the keys fileName, description, and language. "
                + "The description must be written in language code " + languageCode + ". "
                + "Do not use Markdown or add explanations outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION) {
            return "You correct spelling and grammar for snippet descriptions. "
                + "Return only the corrected plain text in language code " + languageCode + ". "
                + "Do not include hidden reasoning, analysis, or <think> tags. "
                + "Do not use Markdown, quotes, bullets, or explanations.";
        }
        if (request != null && request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT) {
            return "You correct spelling and grammar only inside user-facing text segments extracted from source code. "
                + "Never modify code, identifiers, operators, keywords, syntax, delimiters, or control flow. "
                + "Return exactly one JSON object with an array field named segments. "
                + "Each array entry must contain only the corrected text for one segment in the same order as provided. "
                + "Use language code " + languageCode + " unless the provided snippet context clearly shows a different natural language for existing comments or user-facing strings. "
                + "Do not include hidden reasoning, analysis, <think> tags, explanations, or Markdown.";
        }
        if (request != null && request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT) {
            return "You translate only user-facing text segments extracted from source code. "
                + "Never modify code, identifiers, operators, keywords, syntax, delimiters, or control flow. "
                + "Return exactly one JSON object with an array field named segments. "
                + "Each array entry must contain only the translated text for one segment in the same order as provided. "
                + "Translate into language code " + languageCode + ". "
                + "Do not include hidden reasoning, analysis, <think> tags, explanations, or Markdown.";
        }
        if (request != null && request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION) {
            return "You write a concise technical description for a marked code selection. "
                + "Write in language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Summarize only relevant responsibilities, inputs, outputs, side effects, conditions, and risks. "
                + "Do not explain every single line. "
                + "Do not include hidden reasoning, analysis, or <think> tags. "
                + "Return plain text only without Markdown or bullet lists unless short plain-text paragraphs truly need them.";
        }
        if (request != null && request.action() == AiAction.DESCRIBE_SNIPPET_FULL) {
            return "You write a concise technical description for a full code snippet. "
                + "Write in language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Summarize only relevant blocks, flow, inputs, outputs, side effects, conditions, and risks. "
                + "Do not explain every single line. "
                + "Do not include hidden reasoning, analysis, or <think> tags. "
                + "Return plain text only without Markdown or bullet lists unless short plain-text paragraphs truly need them.";
        }
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES) {
            return "You generate alternative implementations for a selected code block. "
                + "Return exactly one JSON object with a solutions array. "
                + "Each solution entry must contain title, code, and optionally summary. "
                + "Keep the program code in the same programming language as the provided snippet. "
                + "If you include comments or user-facing strings, use language code " + languageCode + " unless the provided full snippet clearly shows another dominant natural language in comments or user-facing strings. "
                + "Do not include explanations outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.COMPLETE_SNIPPET_CODE) {
            return "You generate a short code completion at the current cursor position in a snippet editor. "
                + "Return exactly one JSON object with keys insertText and summary. "
                + "insertText must contain only the code that should be inserted at the cursor, not the full file. "
                + "Keep the code in the snippet language. Do not include Markdown or explanations outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.REVIEW_SNIPPET_CODE) {
            return "You review a code snippet or selected code region for likely errors and concrete improvements. "
                + "Return exactly one JSON object with a findings array. "
                + "Each finding must contain id, severity, title, detail, recommendation, and optionally line. "
                + "Write human-readable text in language code " + languageCode + ". "
                + "Do not rewrite code and do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.IMPROVE_SNIPPET_CODE) {
            return "You improve a selected code region in a snippet editor according to the requested theme. "
                + "Return exactly one JSON object with keys replacement and summary. "
                + "replacement must contain only the replacement code for the selected region. "
                + "Preserve behavior unless the user explicitly requests a behavior change. "
                + "Write summary in language code " + languageCode + ". "
                + "Do not nest this JSON object inside another JSON string. "
                + "Do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.ASSIST_SNIPPET_CODE) {
            return "You edit a complete code snippet according to the user's instruction and cursor context. "
                + "Return exactly one JSON object with keys replacement and summary. "
                + "replacement must contain the full updated snippet content, not a patch, not Markdown, and not only a selected region. "
                + "Use the cursor as the user's focal point, but update other locations when the instruction requires it. "
                + "Preserve existing behavior unless the user explicitly requests a behavior change. "
                + "Do not invent files, endpoints, configuration keys, schemas, secrets, versions, or external facts. "
                + "Write summary in language code " + languageCode + ". "
                + "Do not nest this JSON object inside another JSON string. "
                + "Do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.SECURITY_REVIEW_SNIPPET_CODE) {
            return "You perform a security review of the provided snippet. "
                + "Return exactly one JSON object with a findings array. "
                + "Each finding must contain id, severity, title, impact, and recommendation. "
                + "Only report issues supported by the provided code. If there are no findings, return an empty findings array. "
                + "Write human-readable text in language code " + languageCode + ". "
                + "Do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.APPLY_SNIPPET_SECURITY_FIXES) {
            return "You apply only the selected security findings to the provided snippet. "
                + "Return exactly one JSON object with keys replacement, summary and changes. "
                + "replacement must contain the full updated snippet content. "
                + "changes must be an array with one entry per edited region, each with keys finding (the "
                + "finding id it addresses), anchor (a single line copied verbatim from replacement that "
                + "locates the edited region) and reason (one short sentence explaining why this region changed). "
                + "Do not apply findings that were not selected. Preserve unrelated behavior and formatting where possible. "
                + "Write summary and every reason in language code " + languageCode + ". "
                + "Do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.ANALYZE_SNIPPET_CODE) {
            return "You analyze a code snippet in depth for a developer. "
                + "Return exactly one JSON object with keys summary, dependencies and improvements. "
                + "summary is a short plain-language explanation of what the script does. "
                + "dependencies is an array of external dependencies the script relies on (other scripts, "
                + "programs or services); each has id, name, kind (script|program|service), purpose and "
                + "suggestion (how to reduce or replace this dependency). "
                + "improvements is an array of concrete, individually-applicable improvements; each has id, "
                + "category (security|optimization|design), severity, title, detail, recommendation and optionally line. "
                + "Only report what the provided code supports; use empty arrays when nothing applies. "
                + "Write human-readable text in language code " + languageCode + ". "
                + "Do not rewrite code and do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.APPLY_SNIPPET_IMPROVEMENTS) {
            return "You apply only the selected improvements to the provided snippet. "
                + "Return exactly one JSON object with keys replacement, summary and changes. "
                + "replacement must contain the full updated snippet content. "
                + "changes must be an array with one entry per edited region, each with keys finding (the id "
                + "of the selected item it addresses), anchor (a single line copied verbatim from replacement "
                + "that locates the edited region) and reason (one short sentence explaining why this region changed). "
                + "Apply only the selected items; for a selected dependency, implement its reduce/replace suggestion. "
                + "Preserve unrelated behavior and formatting where possible. "
                + "Write summary and every reason in language code " + languageCode + ". "
                + "Do not include Markdown outside the JSON object.";
        }
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_ONE_LINER) {
            return "You convert a code snippet into a compact, pasteable one-line shell command. "
                + "Return exactly one JSON object with key command. "
                + "command must be a single line with no newline characters. "
                + "Use only the provided snippet content. "
                + "Do not use curl, wget, temporary downloads, external URLs, invented files, base64, heredocs, Markdown, or explanations. "
                + "Preserve the snippet behavior as closely as possible for the declared snippet language.";
        }
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_PLANTUML) {
            return "You generate a compact PlantUML diagram for the logical structure of a code snippet. "
                + "Return exactly one JSON object with keys title, plantUml, and codeReferences. "
                + "plantUml must start with @startuml and end with @enduml. "
                + "codeReferences must be an array of objects with label, startLine, and endLine. "
                + "Each codeReferences label must exactly match one visible PlantUML activity or decision label. "
                + "Create one codeReferences entry for every activity and decision in plantUml; exclude only start, stop, arrows, and merge nodes. "
                + "Line numbers must be 1-based and must refer only to lines visible in the provided line-numbered snippet. "
                + "For scripts and imperative snippets, use only a PlantUML activity diagram. "
                + "Use a small semantic HEX color palette to improve readability. "
                + "Allowed activity syntax is limited to start, :Action;, :Action; <<#RRGGBB>>, if (...) then (...) else (...) endif, and stop. "
                + "Do not use gradients or large style blocks. "
                + "Do not use component, package, class, object, actor, or usecase blocks for scripts. "
                + "Do not place raw variable names or shell commands as free text inside PlantUML blocks. "
                + "Do not include Markdown or explanations outside the JSON object.";
        }
        if (isConversationFollowUp(request)) {
            return "You are continuing an existing AI chat. "
            + "Answer in language code " + languageCode + ". "
                + "Follow the latest user request exactly. "
                + "Use earlier messages and the original terminal text only as context when they are relevant. "
                + "Do not let older instructions override the newest user question. "
                + "Use Markdown with short headings and concise, practical content. "
                + "If something is uncertain, say so explicitly.";
        }
        return "You are an assistant that analyzes terminal output. "
            + "Answer in language code " + languageCode + ". "
            + "Use Markdown with short headings and concise, practical content. "
            + "Do not invent facts that are not supported by the provided selection. "
            + "If something is uncertain, say so explicitly.";
    }

    public static String buildUserPrompt(AiRequest request) {
        if (request == null) {
            return "";
        }
        Objects.requireNonNull(request.action(), "request.action must not be null");
        if (isConversationFollowUp(request)) {
            return buildConversationFollowUpPrompt(request);
        }

        StringBuilder prompt = new StringBuilder();
        switch (request.action()) {
            case SUMMARIZE -> prompt.append(
                "Summarize the selected terminal text. Include: overview, key findings, and useful next steps if any.\n");
            case SOLVE_PROBLEM -> prompt.append(
                "Analyze the selected terminal output as an error/problem report. Include: likely cause, concrete fix steps, and safe verification commands.\n");
            case ASK -> prompt.append(
                "Answer the user's question or instruction about the selected terminal text. Follow the custom request exactly, stay grounded in the provided text, and be concise and practical.\n");
            case GENERATE_CHAT_TITLE -> prompt.append(
                "Generate a short, precise title for this AI chat.\n"
                    + "Return exactly one plain-text line.\n"
                    + "Do not use Markdown, bullets, numbering, or quotation marks.\n"
                    + "Keep it under 80 characters and describe the topic clearly.\n");
            case GENERATE_SNIPPET_METADATA -> prompt.append(
                "Generate metadata for a reusable script snippet.\n"
                    + "Return exactly one JSON object with these keys:\n"
                    + "- fileName: an ASCII file name with a suitable extension for the script language\n"
                    + "- description: one short, precise sentence for the snippet manager\n"
                    + "- language: the best matching snippet language identifier such as bash, python, perl, ruby, javascript, groovy, powershell, java, sql, json, yaml, xml, markdown, properties, html, dockerfile, or plain\n"
                    + "Use only letters, digits, dash, underscore, and dot in fileName.\n");
            case CORRECT_SNIPPET_DESCRIPTION -> prompt.append(
                "Correct spelling and grammar in the snippet description.\n"
                    + "Return only the corrected plain text.\n"
                    + "Do not include hidden reasoning, analysis, or <think> tags.\n"
                    + "Preserve the original meaning, commands, file names, code terms, and technical wording.\n");
            case CORRECT_SNIPPET_SELECTION_TEXT -> prompt.append(
                "Correct spelling and grammar only in the provided editable text segments.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"segments\": [ { \"text\": \"...\" } ] }\n"
                    + "Keep the same segment order.\n"
                    + "Do not include hidden reasoning, analysis, or <think> tags.\n"
                    + "Never rewrite or explain code.\n");
            case TRANSLATE_SNIPPET_SELECTION_TEXT -> prompt.append(
                "Translate only the provided editable text segments.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"segments\": [ { \"text\": \"...\" } ] }\n"
                    + "Keep the same segment order.\n"
                    + "Do not include hidden reasoning, analysis, or <think> tags.\n"
                    + "Never rewrite or explain code.\n");
            case DESCRIBE_SNIPPET_SELECTION -> prompt.append(
                "Describe the selected code region technically.\n"
                    + "Focus on the relevant responsibilities and behavior.\n"
                    + "Do not describe every line.\n");
            case DESCRIBE_SNIPPET_FULL -> prompt.append(
                "Describe the full snippet technically.\n"
                    + "Focus on central blocks, behavior, inputs, outputs, side effects, conditions, and risks.\n"
                    + "Do not describe every line.\n");
            case GENERATE_SNIPPET_ALTERNATIVES -> prompt.append(
                "Generate alternative solutions for the requested target scope. "
                    + "The target scope can be either a selected code region or the full snippet.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"solutions\": [ { \"title\": \"...\", \"code\": \"...\", \"summary\": \"...\" } ] }\n"
                    + "Each solution code must replace exactly the target scope.\n"
                    + "Do not include explanations outside the JSON object.\n");
            case COMPLETE_SNIPPET_CODE -> prompt.append(
                "Generate a concise completion at the cursor position.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"insertText\": \"...\", \"summary\": \"...\" }\n"
                    + "Return only text that should be inserted at the cursor.\n");
            case REVIEW_SNIPPET_CODE -> prompt.append(
                "Review the provided snippet context for likely errors and useful improvements.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"findings\": [ { \"id\": \"R1\", \"severity\": \"medium\", \"title\": \"...\", \"detail\": \"...\", \"recommendation\": \"...\", \"line\": 1 } ] }\n"
                    + "If there are no findings, return { \"findings\": [] }.\n");
            case IMPROVE_SNIPPET_CODE -> prompt.append(
                "Improve the selected code region according to the requested theme.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"replacement\": \"...\", \"summary\": \"...\" }\n"
                    + "The replacement must replace only the selected region.\n");
            case ASSIST_SNIPPET_CODE -> prompt.append(
                "Apply the user's instruction to the complete snippet.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"replacement\": \"...\", \"summary\": \"...\" }\n"
                    + "replacement must be the full updated snippet content.\n"
                    + "Use the cursor metadata as the user's focal point and make wider changes only when required by the instruction.\n"
                    + "Do not invent files, endpoints, configuration keys, schemas, secrets, versions, or external facts.\n");
            case SECURITY_REVIEW_SNIPPET_CODE -> prompt.append(
                "Review the snippet for security issues.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"findings\": [ { \"id\": \"S1\", \"severity\": \"high\", \"title\": \"...\", \"impact\": \"...\", \"recommendation\": \"...\" } ] }\n"
                    + "Only report issues supported by the provided code.\n");
            case APPLY_SNIPPET_SECURITY_FIXES -> prompt.append(
                "Apply only the selected security findings to the full snippet.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"replacement\": \"...\", \"summary\": \"...\", "
                    + "\"changes\": [ { \"finding\": \"S1\", \"anchor\": \"<verbatim line from replacement>\", \"reason\": \"...\" } ] }\n"
                    + "The replacement must be the full updated snippet content. "
                    + "Add one changes entry per edited region; anchor must be a line copied verbatim from replacement.\n");
            case ANALYZE_SNIPPET_CODE -> prompt.append(
                "Analyze the provided snippet in depth.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"summary\": \"...\", "
                    + "\"dependencies\": [ { \"id\": \"D1\", \"name\": \"curl\", \"kind\": \"program\", \"purpose\": \"...\", \"suggestion\": \"...\" } ], "
                    + "\"improvements\": [ { \"id\": \"SEC-1\", \"category\": \"security\", \"severity\": \"high\", \"title\": \"...\", \"detail\": \"...\", \"recommendation\": \"...\", \"line\": 1 } ] }\n"
                    + "summary explains what the script does. Each dependency lists an external script/program/service and a suggestion to reduce or replace it.\n"
                    + "Use category values security, optimization or design for improvements. Return empty arrays (and an empty summary) when nothing applies.\n");
            case APPLY_SNIPPET_IMPROVEMENTS -> prompt.append(
                "Apply only the selected improvements to the full snippet.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"replacement\": \"...\", \"summary\": \"...\", "
                    + "\"changes\": [ { \"finding\": \"SEC-1\", \"anchor\": \"<verbatim line from replacement>\", \"reason\": \"...\" } ] }\n"
                    + "The replacement must be the full updated snippet content. For a selected dependency, implement its reduce/replace suggestion.\n"
                    + "Add one changes entry per edited region; anchor must be a line copied verbatim from replacement.\n");
            case GENERATE_SNIPPET_ONE_LINER -> prompt.append(
                "Convert the snippet into a compact one-liner command.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"command\": \"...\" }\n"
                    + "The command must be a single line that can be pasted into a shell.\n"
                    + "Do not use base64, heredocs, curl, wget, temporary downloads, or external URLs.\n"
                    + "Do not invent files, endpoints, placeholders, or network locations.\n"
                    + "Prefer readable shell separators, interpreter -e/-c flags, and safe quoting.\n");
            case GENERATE_SNIPPET_PLANTUML -> prompt.append(
                "Generate a compact PlantUML logical-structure diagram for the snippet.\n"
                    + "For bash/shell or other imperative snippets, use a simple activity diagram only. Valid example syntax:\n"
                    + "@startuml\\nstart\\n:Read configuration; <<#EAF7EF>>\\n:Run main command; <<#EAF4FF>>\\nif (command succeeds?) then (yes)\\n  :Handle success; <<#EAF7EF>>\\nelse (no)\\n  :Handle failure; <<#FDECEC>>\\nendif\\nstop\\n@enduml\n"
                    + "Use HEX colors sparingly to distinguish setup, main work, success, and failure paths. "
                    + "Action lines may use :Action label; <<#RRGGBB>> syntax.\n"
                    + "Do not use gradients or large style blocks.\n"
                    + "Do not use component/package/class/object/actor/usecase blocks for script variables or commands.\n"
                    + "Do not put raw variable declarations or shell commands as standalone PlantUML lines.\n"
                    + "Each executable step must be an activity line like :Create backup archive;.\n"
                    + "Return exactly one JSON object with this shape:\n"
                    + "{ \"title\": \"...\", \"plantUml\": \"@startuml\\n...\\n@enduml\", \"codeReferences\": [ { \"label\": \"Create backup archive\", \"startLine\": 12, \"endLine\": 14 } ] }\n"
                    + "Each codeReferences label must exactly match a visible activity label or decision text in plantUml.\n"
                    + "Create a codeReferences entry for every visible activity and decision; exclude only start, stop, arrows, and merge nodes.\n"
                    + "Use only 1-based line numbers from the line-numbered snippet context.\n");
        }
        if (request.action() == AiAction.ASSIST_SNIPPET_CODE) {
            prompt.append("Treat the provided full snippet as the editable source of truth.\n");
        } else {
            prompt.append("Treat the selected text as the primary source of truth.\n");
        }
        if (request.connectionDisplayName() != null && !request.connectionDisplayName().isBlank()) {
            prompt.append("Connection: ").append(request.connectionDisplayName().trim()).append("\n");
        }
        if ((request.action() == AiAction.ASK
            || request.action() == AiAction.GENERATE_CHAT_TITLE
            || request.action() == AiAction.GENERATE_SNIPPET_METADATA)
            && request.conversationContext() != null
            && !request.conversationContext().isBlank()) {
            prompt.append("Conversation so far:\n")
                .append(toSafeTextCodeBlock(request.conversationContext()))
                .append("\n");
        }
        if (request.action() == AiAction.GENERATE_SNIPPET_METADATA && request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("Detected script language: ")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.action() == AiAction.ASK && request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("User request:\n")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION) {
            if (request.conversationContext() != null && !request.conversationContext().isBlank()) {
                prompt.append("Detected script language: ")
                    .append(request.conversationContext().trim())
                    .append("\n");
            }
            if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
                prompt.append("Description to correct:\n")
                    .append(request.userPrompt().trim())
                    .append("\n");
            }
        }
        if (request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION
            || request.action() == AiAction.DESCRIBE_SNIPPET_FULL
            || request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES
            || request.action() == AiAction.COMPLETE_SNIPPET_CODE
            || request.action() == AiAction.REVIEW_SNIPPET_CODE
            || request.action() == AiAction.ANALYZE_SNIPPET_CODE
            || request.action() == AiAction.APPLY_SNIPPET_IMPROVEMENTS
            || request.action() == AiAction.IMPROVE_SNIPPET_CODE
            || request.action() == AiAction.ASSIST_SNIPPET_CODE
            || request.action() == AiAction.SECURITY_REVIEW_SNIPPET_CODE
            || request.action() == AiAction.APPLY_SNIPPET_SECURITY_FIXES
            || request.action() == AiAction.GENERATE_SNIPPET_ONE_LINER
            || request.action() == AiAction.GENERATE_SNIPPET_PLANTUML) {
            if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
                prompt.append("Additional user instructions:\n")
                    .append(request.userPrompt().trim())
                    .append("\n");
            }
            if (request.conversationContext() != null && !request.conversationContext().isBlank()) {
                prompt.append("Snippet context:\n")
                    .append(request.conversationContext().trim())
                    .append("\n");
            }
        }
        if (request.action() == AiAction.GENERATE_CHAT_TITLE) {
            prompt.append("Focus on what the user and AI discussed, not on generic phrasing.\n");
        }
        boolean usesScriptContext = request.action() == AiAction.CORRECT_SNIPPET_DESCRIPTION
            || request.action() == AiAction.CORRECT_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT
            || request.action() == AiAction.DESCRIBE_SNIPPET_SELECTION
            || request.action() == AiAction.DESCRIBE_SNIPPET_FULL
            || request.action() == AiAction.GENERATE_SNIPPET_ALTERNATIVES
            || request.action() == AiAction.COMPLETE_SNIPPET_CODE
            || request.action() == AiAction.REVIEW_SNIPPET_CODE
            || request.action() == AiAction.IMPROVE_SNIPPET_CODE
            || request.action() == AiAction.ASSIST_SNIPPET_CODE
            || request.action() == AiAction.SECURITY_REVIEW_SNIPPET_CODE
            || request.action() == AiAction.APPLY_SNIPPET_SECURITY_FIXES
            || request.action() == AiAction.GENERATE_SNIPPET_ONE_LINER
            || request.action() == AiAction.GENERATE_SNIPPET_PLANTUML;
        prompt.append(request.action() == AiAction.ASSIST_SNIPPET_CODE
                ? "Full script content to update:\n"
                : usesScriptContext
                    ? "Script content for context only:\n"
                    : "Selected terminal text:\n")
            .append(toSafeTextCodeBlock(request.selectedText()));
        return prompt.toString();
    }

    private static boolean isConversationFollowUp(AiRequest request) {
        return request != null
            && request.action() == AiAction.ASK
            && request.conversationContext() != null
            && !request.conversationContext().isBlank();
    }

    private static String buildConversationFollowUpPrompt(AiRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Continue the existing AI chat.\n");
        if (request.connectionDisplayName() != null && !request.connectionDisplayName().isBlank()) {
            prompt.append("Connection: ").append(request.connectionDisplayName().trim()).append("\n");
        }
        prompt.append("Conversation so far:\n")
            .append(toSafeTextCodeBlock(request.conversationContext()))
            .append("\n");
        if (request.userPrompt() != null && !request.userPrompt().isBlank()) {
            prompt.append("Latest user message:\n")
                .append(request.userPrompt().trim())
                .append("\n");
        }
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            prompt.append("Original terminal text for background context only:\n")
                .append(toSafeTextCodeBlock(request.selectedText()));
        }
        return prompt.toString();
    }

    static String toSafeTextCodeBlock(String text) {
        String content = text != null ? text : "";
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        return fence + "text\n" + content + "\n" + fence;
    }
}

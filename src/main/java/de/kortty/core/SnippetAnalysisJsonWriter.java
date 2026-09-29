package de.kortty.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisReport.ApplyResult;
import de.kortty.core.SnippetAnalysisReport.Change;
import de.kortty.core.SnippetAnalysisReport.DeltaItem;
import de.kortty.core.SnippetAnalysisReport.Dependency;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.Header;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The report as JSON for other tools. The tree is built explicitly — no reflection — so renaming a
 * Java component can never silently change the format; {@link #SCHEMA} names the version. Times
 * are ISO-8601 instants, enums are stable lower-case ids.
 */
final class SnippetAnalysisJsonWriter {

    static final String SCHEMA = "kortty.snippetAnalysisReport/1";

    private SnippetAnalysisJsonWriter() {
    }

    static String render(SnippetAnalysisReport report, ExportOptions options) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("kind", report.kind().name());
        root.addProperty("exportedAt", options.exportedAt().toString());
        root.addProperty("locale", options.locale().toLanguageTag());
        root.add("header", header(report.header()));
        root.addProperty("summary", report.summary());

        JsonArray findings = new JsonArray();
        for (Finding finding : report.findings()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", finding.id());
            item.addProperty("section", finding.displayCategory());
            item.addProperty("category", finding.category());
            item.addProperty("severity", finding.severity().name().toLowerCase(Locale.ROOT));
            item.addProperty("severityLabel", finding.severityLabel());
            item.addProperty("title", finding.title());
            item.addProperty("detail", finding.detail());
            item.addProperty("recommendation", finding.recommendation());
            if (finding.line() != null) {
                item.addProperty("line", finding.line());
            } else {
                item.add("line", JsonNull.INSTANCE);
            }
            item.addProperty("selected", finding.selected());
            if (finding.status() != null) {
                item.addProperty("status", finding.status().id());
            }
            item.add("changes", changes(finding.changes()));
            if (!finding.hunkIndexes().isEmpty()) {
                JsonArray hunks = new JsonArray();
                finding.hunkIndexes().forEach(hunks::add);
                item.add("hunks", hunks);
            }
            findings.add(item);
        }
        root.add("findings", findings);

        JsonArray dependencies = new JsonArray();
        for (Dependency dependency : report.dependencies()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", dependency.id());
            item.addProperty("name", dependency.name());
            item.addProperty("kind", dependency.kind());
            item.addProperty("purpose", dependency.purpose());
            item.addProperty("suggestion", dependency.suggestion());
            item.addProperty("selected", dependency.selected());
            if (dependency.status() != null) {
                item.addProperty("status", dependency.status().id());
            }
            item.add("changes", changes(dependency.changes()));
            dependencies.add(item);
        }
        root.add("dependencies", dependencies);

        if (report.diagram() != null) {
            JsonObject diagram = new JsonObject();
            diagram.addProperty("type", report.diagram().type() != null ? report.diagram().type().id() : "");
            diagram.addProperty("fallback", report.diagram().fallback());
            diagram.addProperty("mermaid", report.diagram().mermaidSource());
            root.add("diagram", diagram);
        } else {
            root.add("diagram", JsonNull.INSTANCE);
        }
        root.add("apply", report.apply() != null ? apply(report.apply()) : JsonNull.INSTANCE);
        root.add("verification", report.verification() != null ? verification(report.verification()) : JsonNull.INSTANCE);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(root)
            + "\n";
    }

    private static JsonObject header(Header header) {
        JsonObject object = new JsonObject();
        object.addProperty("scriptName", header.scriptName());
        object.addProperty("snippetId", header.snippetId());
        object.addProperty("scriptLanguage", header.scriptLanguage());
        object.addProperty("aiProfile", header.aiProfileName());
        object.addProperty("aiModel", header.aiModel());
        JsonArray skills = new JsonArray();
        header.includedSkills().forEach(skills::add);
        object.add("skills", skills);
        object.addProperty("analysisLanguage", header.analysisLanguageCode());
        object.addProperty("codeTextLanguage", header.codeTextLanguageCode());
        addInstant(object, "analysedAt", header.analysedAt());
        addInstant(object, "appliedAt", header.appliedAt());
        object.addProperty("sourceSha256", header.sourceSha256());
        object.addProperty("stale", header.stale());
        object.addProperty("runNumber", header.runNumber());
        object.addProperty("runCount", header.runCount());
        return object;
    }

    private static JsonObject apply(ApplyResult apply) {
        JsonObject object = new JsonObject();
        object.addProperty("outcome", apply.outcome().name().toLowerCase(Locale.ROOT));
        object.addProperty("partial", apply.partial());
        addInstant(object, "appliedAt", apply.appliedAt());
        addInstant(object, "decidedAt", apply.decidedAt());
        object.addProperty("profile", apply.profileName());
        object.addProperty("elapsedSeconds", apply.elapsedSeconds());
        if (apply.usage() != null) {
            JsonObject usage = new JsonObject();
            usage.addProperty("prompt", apply.usage().promptTokens());
            usage.addProperty("completion", apply.usage().completionTokens());
            usage.addProperty("total", apply.usage().totalTokens());
            usage.addProperty("cachedPrompt", apply.usage().cachedPromptTokens());
            object.add("tokens", usage);
        } else {
            object.add("tokens", JsonNull.INSTANCE);
        }
        object.addProperty("retries", apply.retries());
        object.addProperty("completedItems", apply.completedItems());
        object.addProperty("totalItems", apply.totalItems());
        object.add("hardening", strings(apply.hardeningLabels()));
        object.add("inputHardening", strings(apply.inputHardeningLabels()));
        object.addProperty("header", apply.headerName());
        object.addProperty("migration", apply.migrationLabel());
        object.addProperty("aiSummary", apply.aiSummary());
        object.add("changes", changes(apply.changes()));
        TextLineDiff.Result diff = apply.diff();
        if (diff != null) {
            JsonObject diffObject = new JsonObject();
            diffObject.addProperty("added", diff.added());
            diffObject.addProperty("removed", diff.removed());
            diffObject.addProperty("oldLines", diff.oldLineCount());
            diffObject.addProperty("newLines", diff.newLineCount());
            diffObject.addProperty("tooLarge", diff.tooLarge());
            JsonArray hunks = new JsonArray();
            for (int index = 0; index < diff.hunks().size(); index++) {
                TextLineDiff.Hunk hunk = diff.hunks().get(index);
                JsonObject hunkObject = new JsonObject();
                hunkObject.addProperty("header", hunk.header());
                hunkObject.add("findings", strings(apply.findingIdsOfHunk(index)));
                JsonArray lines = new JsonArray();
                for (TextLineDiff.Line line : hunk.lines()) {
                    lines.add(switch (line.kind()) {
                        case ADDED -> "+";
                        case REMOVED -> "-";
                        case CONTEXT -> " ";
                    } + line.text());
                }
                hunkObject.add("lines", lines);
                hunks.add(hunkObject);
            }
            diffObject.add("hunks", hunks);
            object.add("diff", diffObject);
        } else {
            object.add("diff", JsonNull.INSTANCE);
        }
        return object;
    }

    private static JsonObject verification(SnippetAnalysisReport.Verification verification) {
        JsonObject object = new JsonObject();
        addInstant(object, "verifiedAt", verification.verifiedAt());
        object.addProperty("method", "title-similarity");
        object.add("resolved", deltas(verification.resolved()));
        object.add("new", deltas(verification.introduced()));
        object.add("persisting", deltas(verification.persisting()));
        return object;
    }

    private static JsonArray deltas(List<DeltaItem> items) {
        JsonArray array = new JsonArray();
        for (DeltaItem item : items) {
            JsonObject object = new JsonObject();
            object.addProperty("id", item.id());
            if (!item.previousId().isBlank()) {
                object.addProperty("previousId", item.previousId());
            }
            object.addProperty("title", item.title());
            object.addProperty("severity", item.severity().name().toLowerCase(Locale.ROOT));
            object.addProperty("section", item.displayCategory());
            array.add(object);
        }
        return array;
    }

    private static JsonArray changes(List<Change> changes) {
        JsonArray array = new JsonArray();
        for (Change change : changes) {
            JsonObject object = new JsonObject();
            object.addProperty("finding", change.findingId());
            object.addProperty("anchor", change.anchor());
            object.addProperty("reason", change.reason());
            array.add(object);
        }
        return array;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void addInstant(JsonObject object, String name, Instant instant) {
        if (instant != null) {
            object.addProperty(name, instant.toString());
        } else {
            object.add(name, JsonNull.INSTANCE);
        }
    }
}

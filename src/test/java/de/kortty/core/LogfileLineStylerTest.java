package de.kortty.core;

import de.kortty.core.LogfileLineStyler.Kind;
import de.kortty.core.LogfileLineStyler.Span;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.HighlightBuiltinSets;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class LogfileLineStylerTest {

    private static final long GENEROUS_DEADLINE_NANOS = 5_000_000_000L;

    @Test
    void linkSpansFindUrlsPathsAndMailAddressesWithTheirWholeCopyText() {
        String text = "see https://example.com/a?b=1 and /var/log/app.log then me@example.org.";

        List<Span> spans = LogfileLineStyler.linkSpans(text);

        assertThat(spans).hasSize(3);
        Span url = spans.get(0);
        assertThat(url.kind()).isEqualTo(Kind.LINK);
        assertThat(url.start()).isEqualTo(text.indexOf("https://"));
        assertThat(url.end()).isEqualTo(text.indexOf("https://") + "https://example.com/a?b=1".length());
        assertThat(url.copyText()).isEqualTo("https://example.com/a?b=1");
        assertThat(url.ruleIndex()).isEqualTo(-1);

        Span path = spans.get(1);
        assertThat(path.kind()).isEqualTo(Kind.PATH);
        assertThat(path.start()).isEqualTo(text.indexOf("/var/log"));
        assertThat(path.copyText()).isEqualTo("/var/log/app.log");

        Span mail = spans.get(2);
        assertThat(mail.kind()).isEqualTo(Kind.LINK);
        assertThat(mail.start()).isEqualTo(text.indexOf("me@example.org"));
        assertThat(mail.copyText()).isEqualTo("me@example.org");
    }

    @Test
    void linkSpansOfEmptyOrNullTextAreEmpty() {
        assertThat(LogfileLineStyler.linkSpans("")).isEmpty();
        assertThat(LogfileLineStyler.linkSpans(null)).isEmpty();
        assertThat(LogfileLineStyler.linkSpans("nothing to see here")).isEmpty();
    }

    @Test
    void keywordSpansFollowTheOwnerOfEachRun() {
        CompiledHighlightSet errors = CompiledHighlightSet.compile(
            HighlightBuiltinSets.byId(HighlightBuiltinSets.ERRORS));

        List<Span> spans = LogfileLineStyler.keywordSpans("ERROR: failed", errors,
            System.nanoTime() + GENEROUS_DEADLINE_NANOS);

        assertThat(spans).containsExactly(
            new Span(0, 5, Kind.KEYWORD, 0, null),
            new Span(7, 13, Kind.KEYWORD, 0, null)).inOrder();
    }

    @Test
    void keywordSpansAreEmptyWhenTheMatcherRanOutOfTime() {
        CompiledHighlightSet errors = CompiledHighlightSet.compile(
            HighlightBuiltinSets.byId(HighlightBuiltinSets.ERRORS));

        // A deadline already past: the rules after the cut-off were never asked, so no partial set.
        assertThat(LogfileLineStyler.keywordSpans("ERROR: failed", errors, System.nanoTime() - 1_000_000_000L))
            .isEmpty();
    }

    @Test
    void keywordSpansOfTheEmptySetAndEmptyTextAreEmpty() {
        assertThat(LogfileLineStyler.keywordSpans("ERROR", CompiledHighlightSet.NONE,
            System.nanoTime() + GENEROUS_DEADLINE_NANOS)).isEmpty();
        assertThat(LogfileLineStyler.keywordSpans("", CompiledHighlightSet.NONE, 0L)).isEmpty();
    }

    @Test
    void findSpansMarkEveryOccurrenceAndTheCurrentOneApart() {
        List<Span> spans = LogfileLineStyler.findSpans("abc Hello hello", "hello", 4);

        assertThat(spans).containsExactly(
            new Span(4, 9, Kind.FIND_CURRENT, -1, null),
            new Span(10, 15, Kind.FIND, -1, null)).inOrder();
    }

    @Test
    void findSpansWithoutAnOccurrenceOrNeedleAreEmpty() {
        assertThat(LogfileLineStyler.findSpans("abc", "zzz", 0)).isEmpty();
        assertThat(LogfileLineStyler.findSpans("abc", "", 0)).isEmpty();
        assertThat(LogfileLineStyler.findSpans(null, "a", 0)).isEmpty();
    }

    @Test
    void findSpansCountOverlappingOccurrencesLikeTheSearchDoes() {
        List<Span> spans = LogfileLineStyler.findSpans("aaa", "aa", -1);

        assertThat(spans).containsExactly(
            new Span(0, 2, Kind.FIND, -1, null),
            new Span(1, 3, Kind.FIND, -1, null)).inOrder();
    }

    @Test
    void mergeLetsTheHigherPriorityLayerCutTheOthers() {
        List<Span> keyword = List.of(new Span(0, 10, Kind.KEYWORD, 0, null));
        List<Span> link = List.of(new Span(4, 8, Kind.PATH, -1, "pathtoken"));
        List<Span> find = List.of(new Span(6, 12, Kind.FIND, -1, null));
        List<Span> current = List.of(new Span(6, 7, Kind.FIND_CURRENT, -1, null));

        List<Span> merged = LogfileLineStyler.merge(keyword, link, find, current);

        assertThat(merged).containsExactly(
            new Span(0, 4, Kind.KEYWORD, 0, null),
            new Span(4, 6, Kind.PATH, -1, "pathtoken"),
            new Span(6, 7, Kind.FIND_CURRENT, -1, null),
            new Span(7, 12, Kind.FIND, -1, null)).inOrder();
    }

    @Test
    void aLinkCutByAFindKeepsItsWholeCopyText() {
        List<Span> link = List.of(new Span(4, 20, Kind.LINK, -1, "https://whole.example/path"));
        List<Span> find = List.of(new Span(10, 12, Kind.FIND, -1, null));

        List<Span> merged = LogfileLineStyler.merge(link, find);

        assertThat(merged).containsExactly(
            new Span(4, 10, Kind.LINK, -1, "https://whole.example/path"),
            new Span(10, 12, Kind.FIND, -1, null),
            new Span(12, 20, Kind.LINK, -1, "https://whole.example/path")).inOrder();
    }

    @Test
    void mergeOfNothingIsEmpty() {
        assertThat(LogfileLineStyler.merge()).isEmpty();
        assertThat(LogfileLineStyler.merge(List.of(), null)).isEmpty();
    }

    @Test
    void mergeOfRandomLayersIsSortedDisjointAndKeepsTheRightKindPerCharacter() {
        Random random = new Random(20260804L);
        Kind[] kinds = Kind.values();
        for (int round = 0; round < 200; round++) {
            List<Span> all = new ArrayList<>();
            List<Span> layerA = new ArrayList<>();
            List<Span> layerB = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(12); i++) {
                int start = random.nextInt(120);
                int end = start + 1 + random.nextInt(30);
                Kind kind = kinds[random.nextInt(kinds.length)];
                Span span = new Span(start, end, kind, kind == Kind.KEYWORD ? i : -1,
                    kind == Kind.LINK || kind == Kind.PATH ? "t" + i : null);
                (random.nextBoolean() ? layerA : layerB).add(span);
                all.add(span);
            }

            List<Span> merged = LogfileLineStyler.merge(layerA, layerB);

            for (int i = 1; i < merged.size(); i++) {
                assertWithMessage("round %s sorted and disjoint", round)
                    .that(merged.get(i).start()).isAtLeast(merged.get(i - 1).end());
            }
            for (int position = 0; position < 150; position++) {
                int best = -1;
                for (Span span : all) {
                    if (position >= span.start() && position < span.end()) {
                        best = Math.max(best, rank(span.kind()));
                    }
                }
                Span owner = null;
                for (Span span : merged) {
                    if (position >= span.start() && position < span.end()) {
                        owner = span;
                    }
                }
                if (best < 0) {
                    assertWithMessage("round %s position %s uncovered", round, position).that(owner).isNull();
                } else {
                    assertWithMessage("round %s position %s covered", round, position).that(owner).isNotNull();
                    assertWithMessage("round %s position %s rank", round, position)
                        .that(rank(owner.kind())).isEqualTo(best);
                }
            }
        }
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case FIND_CURRENT -> 4;
            case FIND -> 3;
            case LINK, PATH -> 2;
            case KEYWORD -> 1;
        };
    }
}

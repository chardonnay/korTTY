package de.kortty.core;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;

class TextDiffTest {

    @Test
    void identicalTextsHaveNoDiff() {
        TextDiff.Result result = TextDiff.unified("a", "b", "x\ny\n", "x\r\ny", 3);

        assertThat(result.unified()).isEmpty();
        assertThat(result.stats().identical()).isTrue();
    }

    @Test
    void aChangedLineIsAHunkWithContext() {
        String left = "#!/bin/bash\nset -e\nPORT=8080\nexec server --port \"$PORT\"\n";
        String right = "#!/bin/bash\nset -euo pipefail\nPORT=8080\nexec server --port \"$PORT\"\n";

        TextDiff.Result result = TextDiff.unified("local/run.sh", "server/run.sh", left, right, 3);

        assertThat(result.unified()).isEqualTo("""
            --- local/run.sh
            +++ server/run.sh
            @@ -1,4 +1,4 @@
             #!/bin/bash
            -set -e
            +set -euo pipefail
             PORT=8080
             exec server --port "$PORT"
            """);
        assertThat(result.stats()).isEqualTo(new TextDiff.Stats(1, 1));
        assertThat(result.simplified()).isFalse();
    }

    @Test
    void distantChangesGetTheirOwnHunks() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            lines.add("line " + i);
        }
        String left = String.join("\n", lines);
        lines.set(1, "LINE 2");
        lines.add(18, "inserted");
        String right = String.join("\n", lines);

        String unified = TextDiff.unified("a", "b", left, right, 2).unified();

        assertThat(unified).contains("@@ -1,4 +1,4 @@\n line 1\n-line 2\n+LINE 2\n line 3\n line 4\n");
        assertThat(unified).contains("@@ -17,4 +17,5 @@\n line 17\n line 18\n+inserted\n line 19\n line 20\n");
    }

    @Test
    void emptySidesAreCountedFromZero() {
        assertThat(TextDiff.unified("a", "b", "", "one\ntwo", 3).unified())
            .isEqualTo("--- a\n+++ b\n@@ -0,0 +1,2 @@\n+one\n+two\n");
        assertThat(TextDiff.unified("a", "b", "only", "", 3).unified())
            .isEqualTo("--- a\n+++ b\n@@ -1 +0,0 @@\n-only\n");
    }

    @Test
    void theDiffTurnsTheLeftTextIntoTheRightOne() {
        Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            List<String> left = new ArrayList<>();
            int size = random.nextInt(40);
            for (int i = 0; i < size; i++) {
                left.add("l" + random.nextInt(8));
            }
            List<String> right = new ArrayList<>(left);
            int edits = random.nextInt(10);
            for (int e = 0; e < edits; e++) {
                int choice = random.nextInt(3);
                if (choice == 0 || right.isEmpty()) {
                    right.add(right.isEmpty() ? 0 : random.nextInt(right.size() + 1), "n" + random.nextInt(8));
                } else if (choice == 1) {
                    right.remove(random.nextInt(right.size()));
                } else {
                    right.set(random.nextInt(right.size()), "c" + random.nextInt(8));
                }
            }
            TextDiff.Result result = TextDiff.unified("a", "b", String.join("\n", left), String.join("\n", right),
                random.nextInt(4));

            assertThat(apply(left, result.unified())).isEqualTo(right);
            long added = result.unified().lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
            long removed = result.unified().lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
            assertThat(result.stats()).isEqualTo(new TextDiff.Stats((int) added, (int) removed));
        }
    }

    @Test
    void unrelatedTextsBeyondTheLimitAreReplacedAsAWhole() {
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < 2_500; i++) {
            left.append("a").append(i).append('\n');
            right.append("b").append(i).append('\n');
        }

        TextDiff.Result result = TextDiff.unified("a", "b", left.toString(), right.toString(), 3);

        assertThat(result.simplified()).isTrue();
        assertThat(result.stats()).isEqualTo(new TextDiff.Stats(2_500, 2_500));
    }

    /** Applies a unified diff the way patch does, checking every context and removed line. */
    private static List<String> apply(List<String> left, String unified) {
        List<String> out = new ArrayList<>();
        int consumed = 0;
        for (String line : unified.lines().toList()) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                continue;
            }
            if (line.startsWith("@@")) {
                String leftRange = line.split(" ")[1].substring(1);
                int start = Integer.parseInt(leftRange.split(",")[0]);
                int count = leftRange.contains(",") ? Integer.parseInt(leftRange.split(",")[1]) : 1;
                int target = count == 0 ? start : start - 1;
                while (consumed < target) {
                    out.add(left.get(consumed++));
                }
                continue;
            }
            char kind = line.charAt(0);
            String text = line.substring(1);
            if (kind == ' ') {
                assertThat(left.get(consumed)).isEqualTo(text);
                out.add(left.get(consumed++));
            } else if (kind == '-') {
                assertThat(left.get(consumed)).isEqualTo(text);
                consumed++;
            } else if (kind == '+') {
                out.add(text);
            }
        }
        while (consumed < left.size()) {
            out.add(left.get(consumed++));
        }
        return out;
    }
}

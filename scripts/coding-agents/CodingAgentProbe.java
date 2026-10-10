import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.AgentRuleRepository;
import de.kortty.codingagent.CodingAgentDetector;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.LocalProcessInspector;
import de.kortty.codingagent.ScreenSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs korTTY's real coding-agent detection for scripts/coding-agents/check.py. Launched as a
 * single-file program on korTTY's main runtime classpath:
 *
 * <pre>
 *   java -cp &lt;classpath&gt; CodingAgentProbe.java process &lt;pid&gt;
 *   java -cp &lt;classpath&gt; CodingAgentProbe.java classify &lt;KIND&gt; &lt;frame-dir&gt;
 *   java -cp &lt;classpath&gt; CodingAgentProbe.java serve &lt;KIND&gt;   (frame paths on stdin)
 * </pre>
 *
 * {@code process} prints the agent process found below {@code pid} (or {@code null});
 * {@code classify} prints one JSON object per {@code *.txt} frame of {@code frame-dir}. A frame file
 * starts with a {@code #! alt=<true|false>} line followed by the screen rows. Output is JSON lines
 * only, so the caller can parse it without a library; the bundled rules are used, never the user's
 * overrides (a throwaway config directory is passed to the repository).
 */
public class CodingAgentProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("process")) {
            Optional<AgentProcess> process = new LocalProcessInspector().findAgentProcess(Long.parseLong(args[1]));
            System.out.println(process
                .map(p -> "{\"pid\":" + p.pid() + ",\"kind\":\"" + p.kind().name() + "\",\"command\":" + quote(p.command()) + "}")
                .orElse("null"));
            return;
        }
        if (args.length == 2 && args[0].equals("serve")) {
            // One frame path per stdin line, one JSON result per stdout line, until EOF.
            CodingAgentKind kind = CodingAgentKind.valueOf(args[1]);
            CodingAgentDetector detector = new CodingAgentDetector(
                new AgentRuleRepository(Files.createTempDirectory("kortty-coding-agent-probe")));
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                System.out.println(classifyFrame(detector, kind, Path.of(line.trim())));
                System.out.flush();
            }
            return;
        }
        if (args.length == 3 && args[0].equals("classify")) {
            CodingAgentKind kind = CodingAgentKind.valueOf(args[1]);
            Path configDir = Files.createTempDirectory("kortty-coding-agent-probe");
            CodingAgentDetector detector = new CodingAgentDetector(new AgentRuleRepository(configDir));
            List<Path> frames;
            try (Stream<Path> files = Files.list(Path.of(args[2]))) {
                frames = files.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
            }
            for (Path frame : frames) {
                System.out.println(classifyFrame(detector, kind, frame));
            }
            return;
        }
        System.err.println("usage: CodingAgentProbe process <pid> | classify <KIND> <frame-dir>");
        System.exit(2);
    }

    private static String classifyFrame(CodingAgentDetector detector, CodingAgentKind kind, Path frame)
            throws java.io.IOException {
        List<String> lines = new ArrayList<>(List.of(Files.readString(frame).split("\n", -1)));
        boolean alternate = !lines.isEmpty() && lines.get(0).trim().equals("#! alt=true");
        if (!lines.isEmpty() && lines.get(0).startsWith("#! ")) {
            lines.remove(0);
        }
        int width = lines.stream().mapToInt(String::length).max().orElse(0);
        DetectionResult result = detector.classify(kind, ScreenSnapshot.ofLines(lines, width, null, alternate));
        long nonEmpty = lines.stream().filter(l -> !l.isBlank()).count();
        return "{\"frame\":" + quote(frame.getFileName().toString())
            + ",\"state\":\"" + result.state().name() + "\""
            + ",\"rule\":" + (result.matchedRuleId() == null ? "null" : quote(result.matchedRuleId()))
            + ",\"nonEmptyLines\":" + nonEmpty + "}";
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}

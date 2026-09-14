package de.kortty.codingagent;

/** Screen -&gt; state classification; implemented by CodingAgentDetector, replaced by lambdas in monitor tests. */
@FunctionalInterface
public interface ScreenClassifier {
    DetectionResult classify(CodingAgentKind kind, ScreenSnapshot snapshot);
}

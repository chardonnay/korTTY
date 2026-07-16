package de.kortty.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic 800-token chunks with a 120-token overlap and PDF-page citations. */
public final class RagChunker {
    public static final int DEFAULT_MAX_TOKENS = 800;
    public static final int DEFAULT_OVERLAP_TOKENS = 120;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}_]+|[\\p{N}]+|[^\\s]");

    private final int maxTokens;
    private final int overlapTokens;

    public RagChunker() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }

    public RagChunker(int maxTokens, int overlapTokens) {
        if (maxTokens < 1 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("Require maxTokens > overlapTokens >= 0");
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    public List<RagChunk> chunk(RagDocument document) {
        List<RagChunk> chunks = new ArrayList<>();
        String text = document.text();
        int segmentStart = 0;
        int page = 1;
        int chunkIndex = 0;
        while (segmentStart <= text.length()) {
            int pageBreak = text.indexOf('\f', segmentStart);
            int segmentEnd = pageBreak >= 0 ? pageBreak : text.length();
            chunkIndex = chunkSegment(document, text, segmentStart, segmentEnd, page, chunkIndex, chunks);
            if (pageBreak < 0) {
                break;
            }
            segmentStart = pageBreak + 1;
            page++;
        }
        return List.copyOf(chunks);
    }

    private int chunkSegment(
        RagDocument document,
        String fullText,
        int segmentStart,
        int segmentEnd,
        int page,
        int nextIndex,
        List<RagChunk> output
    ) {
        String segment = fullText.substring(segmentStart, segmentEnd);
        Matcher matcher = TOKEN.matcher(segment);
        List<TokenSpan> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(new TokenSpan(segmentStart + matcher.start(), segmentStart + matcher.end()));
        }
        if (tokens.isEmpty()) {
            return nextIndex;
        }
        int tokenStart = 0;
        while (tokenStart < tokens.size()) {
            int tokenEndExclusive = Math.min(tokens.size(), tokenStart + maxTokens);
            int startOffset = tokens.get(tokenStart).start();
            int endOffset = tokens.get(tokenEndExclusive - 1).end();
            String chunkText = fullText.substring(startOffset, endOffset).strip();
            if (!chunkText.isEmpty()) {
                Map<String, String> metadata = document.format().equals(".pdf")
                    ? Map.of("page", Integer.toString(page), "format", document.format())
                    : Map.of("format", document.format());
                String id = stableId(document, nextIndex);
                output.add(new RagChunk(id, document.sourceId(), document.relativePath(), document.sha256(),
                    nextIndex, startOffset, endOffset, chunkText, metadata));
                nextIndex++;
            }
            if (tokenEndExclusive == tokens.size()) {
                break;
            }
            tokenStart = tokenEndExclusive - overlapTokens;
        }
        return nextIndex;
    }

    private static String stableId(RagDocument document, int index) {
        String value = document.sourceId() + '\0' + document.relativePath() + '\0'
            + document.sha256() + '\0' + index;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record TokenSpan(int start, int end) { }
}

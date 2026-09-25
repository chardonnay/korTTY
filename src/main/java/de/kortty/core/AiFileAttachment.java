package de.kortty.core;

import java.util.Objects;

/**
 * A text file attached to an AI chat request — typically the file whose name the user selected in
 * the terminal before invoking an AI action from the context menu. The content has already been
 * validated as UTF-8 text (see {@link RemoteTextFileSelectionSupport#decodeUtf8TextFile}); binary
 * files never become attachments.
 *
 * @param fileName   the plain file name as selected in the terminal
 * @param sourcePath the resolved path the content was read from (remote SFTP path or local path)
 * @param content    the decoded text content
 */
public record AiFileAttachment(String fileName, String sourcePath, String content) {

    public AiFileAttachment {
        fileName = Objects.requireNonNull(fileName, "fileName");
        sourcePath = sourcePath != null ? sourcePath : fileName;
        content = content != null ? content : "";
    }

    /** Number of characters the attachment adds to the request. */
    public int length() {
        return content.length();
    }

    /**
     * True when the attachment plus the selected terminal text stay within the profile's selection
     * limit, i.e. the model context can still take the request.
     */
    public boolean fitsWithin(String selectedText, int maxChars) {
        int selectedLength = selectedText != null ? selectedText.length() : 0;
        return (long) selectedLength + content.length() <= maxChars;
    }
}

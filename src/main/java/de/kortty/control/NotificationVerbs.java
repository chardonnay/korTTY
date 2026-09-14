package de.kortty.control;

import com.google.gson.JsonObject;
import de.kortty.codingagent.desktop.DesktopNotifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * {@code notification.show}.
 *
 * <p>The title is prefixed {@code korTTY · } so a script cannot forge a system notification that
 * looks as though it came from somewhere else, and the verb is rate-limited per connection.
 * {@code DesktopNotifier.notify} is documented any-thread and fire-and-forget, so it is called
 * directly rather than marshalled; {@code CodingAgentNotificationCoordinator} is deliberately
 * bypassed because it only implements the BLOCKED/DONE settle policy.
 *
 * <p>Any thread, never the JavaFX application thread.
 */
final class NotificationVerbs {

    /** The prefix every API-raised notification carries. */
    private static final String TITLE_PREFIX = "korTTY · ";

    /** The largest title a caller may supply, before the prefix. */
    private static final int MAX_TITLE_CHARS = 80;

    private NotificationVerbs() {
    }

    /**
     * Registers the notification surface.
     *
     * @param builder the table being assembled
     * @param notifier the desktop notifier, or null when the platform has none
     * @param clockMillis the wall clock that drives the rate limit
     */
    static void register(MethodRegistry.Builder builder, DesktopNotifier notifier,
                         LongSupplier clockMillis) {
        Map<String, Long> lastShown = new ConcurrentHashMap<>();

        builder.register(new MethodSpec("notification.show",
                "Raises one desktop notification, titled so it can never be mistaken for another"
                    + " application's.",
                List.of(new ParamSpec("title", "string", true, null,
                        "At most " + MAX_TITLE_CHARS + " characters; prefixed with 'korTTY · '."),
                    new ParamSpec("body", "string", true, null,
                        "At most " + DesktopNotifier.MAX_BODY_CHARS + " characters.")),
                "{shown, supported}",
                List.of(ControlErrorCode.INVALID_PARAMS, ControlErrorCode.BUSY,
                    ControlErrorCode.UNSUPPORTED),
                false, false,
                "kortty-cli notify --title <s> --body <s>",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"notification.show\","
                    + "\"params\":{\"title\":\"Build\",\"body\":\"green\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"shown\":true,\"supported\":true}}"),
            (session, params) -> {
                if (notifier == null) {
                    throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                        "This platform has no desktop notifier",
                        Map.of("reason", "no_desktop_notifier"));
                }
                String title = ControlJson.requireString(params, "title");
                String body = ControlJson.requireString(params, "body");
                if (title.isBlank() && body.isBlank()) {
                    throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                        "A notification needs a title or a body",
                        Map.of("param", "title", "known", List.of("title", "body")));
                }
                rateLimit(lastShown, clockMillis, session.connectionId());
                notifier.notify(TITLE_PREFIX + clamp(title, MAX_TITLE_CHARS),
                    clamp(body, DesktopNotifier.MAX_BODY_CHARS));
                JsonObject result = new JsonObject();
                result.addProperty("shown", true);
                result.addProperty("supported", notifier.isSupported());
                return result;
            });
    }

    private static void rateLimit(Map<String, Long> lastShown, LongSupplier clockMillis,
                                  String connectionId) throws ControlApiException {
        String key = connectionId == null ? "" : connectionId;
        long now = clockMillis.getAsLong();
        Long previous = lastShown.get(key);
        if (previous != null && now - previous < ControlApiProtocol.NOTIFY_MIN_INTERVAL_MILLIS) {
            throw new ControlApiException(ControlErrorCode.BUSY,
                "Only one notification every " + ControlApiProtocol.NOTIFY_MIN_INTERVAL_MILLIS
                    + " ms per connection",
                Map.of("retry_after_millis",
                    ControlApiProtocol.NOTIFY_MIN_INTERVAL_MILLIS - (now - previous)));
        }
        lastShown.put(key, now);
    }

    private static String clamp(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }
}

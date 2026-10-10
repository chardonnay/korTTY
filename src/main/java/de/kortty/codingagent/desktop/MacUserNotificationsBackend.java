package de.kortty.codingagent.desktop;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * macOS notifications through the User Notifications framework ({@code UNUserNotificationCenter}),
 * called in-process through JNA and the Objective-C runtime, as {@code MacPowerManagementBackend}
 * already does. Unlike {@code osascript} the notification is korTTY's own — its name and icon in
 * Notification Center, its own entry in System Settings → Notifications — and a click on it
 * activates korTTY and runs the notification's click action.
 *
 * <p>The framework only works in a process with a bundle identifier, that is from the
 * {@code korTTY.app} bundle ({@link DesktopNotifierBackends#isMacAppBundle}); outside it, it throws an
 * Objective-C exception that would take the JVM down, so the bundle identifier is checked before the
 * framework is touched. Native set-up happens lazily on the first notification; if it fails, or until
 * the user has answered the one-time permission prompt, notifications go to the {@code osascript}
 * fallback. Once the user has said no, korTTY shows none: that is their choice for korTTY, made in
 * System Settings.
 *
 * <p>The delegate class ({@code KorTTYNotificationDelegate}) is created at run time with two methods:
 * {@code willPresentNotification} lets banners show while korTTY is the active application (the
 * notifier only asks for one when the pane is not in view), and {@code didReceiveNotificationResponse}
 * looks the clicked notification's identifier up and runs its action on the main thread, which is the
 * JavaFX thread. JNA callbacks and the global blocks handed to the framework are kept reachable for
 * the life of the process.
 */
final class MacUserNotificationsBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(MacUserNotificationsBackend.class);

    static final String DELEGATE_CLASS = "KorTTYNotificationDelegate";
    static final String IDENTIFIER_PREFIX = "kortty-";
    /** At most this many notifications keep their click action; older ones lose it. */
    static final int MAX_TRACKED_NOTIFICATIONS = 64;
    /** How long a notification waits for the permission answer before it goes to the fallback. */
    static final long FIRST_ANSWER_WAIT_MILLIS = 1_000L;

    // UNAuthorizationOptionSound | UNAuthorizationOptionAlert.
    private static final long AUTHORIZATION_OPTIONS = 2L | 4L;
    // UNNotificationPresentationOptionAlert | List | Banner: shown even while korTTY is active.
    private static final long PRESENTATION_OPTIONS = 4L | 8L | 16L;
    private static final String DISMISS_ACTION = "com.apple.UNNotificationDismissActionIdentifier";
    private static final int BLOCK_IS_GLOBAL = 1 << 28;
    private static final String USER_NOTIFICATIONS_FRAMEWORK =
        "/System/Library/Frameworks/UserNotifications.framework/UserNotifications";

    /** What the user said to korTTY's notification permission prompt, as far as korTTY knows. */
    enum Authorization {
        /** Not asked yet, or the answer has not arrived: the fallback shows the notification. */
        PENDING,
        GRANTED,
        /** The user does not want korTTY's notifications: none is shown. */
        DENIED
    }

    /** Where one notification goes. */
    enum Route {
        NATIVE,
        FALLBACK,
        NONE
    }

    private final DesktopNotifierBackend fallback;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicBoolean deniedLogged = new AtomicBoolean();
    private final AtomicLong counter = new AtomicLong();
    private final LinkedHashMap<String, Runnable> actions = new LinkedHashMap<>();
    private final Object answerLock = new Object();
    private volatile Authorization authorization = Authorization.PENDING;
    private volatile boolean nativeFailed;
    private ObjC objc;
    private Pointer center;

    MacUserNotificationsBackend(DesktopNotifierBackend fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** The fallback backend (for the selection tests). */
    DesktopNotifierBackend fallback() {
        return fallback;
    }

    /**
     * Pure routing: the framework when the user allowed korTTY's notifications, nothing when they
     * refused, the fallback while the answer is pending or the framework cannot be used.
     */
    static Route route(boolean nativeUsable, Authorization authorization) {
        if (!nativeUsable) {
            return Route.FALLBACK;
        }
        return switch (authorization) {
            case GRANTED -> Route.NATIVE;
            case DENIED -> Route.NONE;
            case PENDING -> Route.FALLBACK;
        };
    }

    @Override
    public boolean isSupported() {
        return !nativeFailed || fallback.isSupported();
    }

    @Override
    public boolean supportsActivation() {
        return !nativeFailed;
    }

    @Override
    public void notify(String title, String body) throws Exception {
        notify(title, body, null);
    }

    @Override
    public void notify(String title, String body, Runnable onActivate) throws Exception {
        boolean usable = ensureNative();
        if (usable) {
            requestAuthorization();
            awaitFirstAnswer();
        }
        switch (route(usable, authorization)) {
            case NATIVE -> {
                try {
                    post(title, body, onActivate);
                } catch (Throwable t) {
                    disableNative("Could not post a notification through the User Notifications framework", t);
                    fallback.notify(title, body, onActivate);
                }
            }
            case NONE -> {
                if (deniedLogged.compareAndSet(false, true)) {
                    logger.info("Notifications for korTTY are turned off in System Settings → Notifications");
                }
            }
            case FALLBACK -> fallback.notify(title, body, onActivate);
        }
    }

    @Override
    public void close() {
        fallback.close();
    }

    /** Called by the delegate when a notification was clicked; runs its action once. */
    void activated(String identifier, String actionIdentifier) {
        if (identifier == null || DISMISS_ACTION.equals(actionIdentifier)) {
            return;
        }
        Runnable action;
        synchronized (actions) {
            action = actions.remove(identifier);
        }
        if (action != null) {
            action.run();
        }
    }

    /** Remembers {@code onActivate} under {@code identifier}, forgetting the oldest beyond the limit. */
    void remember(String identifier, Runnable onActivate) {
        synchronized (actions) {
            actions.put(identifier, onActivate);
            Iterator<String> oldest = actions.keySet().iterator();
            while (actions.size() > MAX_TRACKED_NOTIFICATIONS && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** How many notifications still have a click action (for the tests). */
    int trackedCount() {
        synchronized (actions) {
            return actions.size();
        }
    }

    void authorizationAnswered(boolean granted, String reason) {
        Authorization previous = authorization;
        synchronized (answerLock) {
            authorization = granted ? Authorization.GRANTED : Authorization.DENIED;
            answerLock.notifyAll();
        }
        if (granted) {
            deniedLogged.set(false);
        } else if (previous != Authorization.DENIED) {
            logger.debug("Notification authorization not granted{}", reason == null ? "" : ": " + reason);
        }
    }

    // ---- native ---------------------------------------------------------------------------------

    /** Sets the framework up once; false when it cannot be used in this process. */
    private synchronized boolean ensureNative() {
        if (nativeFailed) {
            return false;
        }
        if (center != null) {
            return true;
        }
        try {
            ObjC runtime = new ObjC();
            Pointer pool = runtime.poolPush();
            try {
                Pointer bundle = runtime.send(runtime.cls("NSBundle"), "mainBundle");
                if (isNull(bundle) || isNull(runtime.send(bundle, "bundleIdentifier"))) {
                    disableNative("korTTY runs without a bundle identifier", null);
                    return false;
                }
                NativeLibrary.getInstance(USER_NOTIFICATIONS_FRAMEWORK);
                Pointer centerClass = runtime.cls("UNUserNotificationCenter");
                if (isNull(centerClass)) {
                    disableNative("the User Notifications framework is not available", null);
                    return false;
                }
                Pointer current = runtime.send(centerClass, "currentNotificationCenter");
                if (isNull(current)) {
                    disableNative("UNUserNotificationCenter returned no notification center", null);
                    return false;
                }
                Pointer delegate = runtime.send(runtime.send(runtime.delegateClass(this), "alloc"), "init");
                runtime.send(current, "setDelegate:", delegate);
                objc = runtime;
                center = current;
                return true;
            } finally {
                runtime.poolPop(pool);
            }
        } catch (Throwable t) {
            disableNative("Could not set up the User Notifications framework", t);
            return false;
        }
    }

    /** Asks for permission; the system prompts only the first time and answers at once afterwards. */
    /**
     * While the permission is still pending, waits up to {@link #FIRST_ANSWER_WAIT_MILLIS} for the
     * answer: once the user has decided, the system answers within milliseconds, and the
     * notification should not go to the fallback just because the answer is still on its way. A
     * first prompt the user has not answered yet ends the wait. Notifier executor only.
     */
    private void awaitFirstAnswer() {
        long deadline = System.nanoTime() + FIRST_ANSWER_WAIT_MILLIS * 1_000_000L;
        synchronized (answerLock) {
            while (authorization == Authorization.PENDING) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) {
                    return;
                }
                try {
                    answerLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void requestAuthorization() {
        try {
            objc.send(center, "requestAuthorizationWithOptions:completionHandler:", AUTHORIZATION_OPTIONS,
                objc.authorizationBlock());
        } catch (Throwable t) {
            logger.debug("Could not request notification authorization: {}", t.toString());
        }
    }

    private void post(String title, String body, Runnable onActivate) {
        String identifier = IDENTIFIER_PREFIX + ProcessHandle.current().pid() + "-" + counter.incrementAndGet();
        Pointer pool = objc.poolPush();
        try {
            Pointer content = objc.send(objc.send(objc.cls("UNMutableNotificationContent"), "alloc"), "init");
            try {
                objc.send(content, "setTitle:", objc.string(title));
                objc.send(content, "setBody:", objc.string(body));
                Pointer request = objc.send(objc.cls("UNNotificationRequest"),
                    "requestWithIdentifier:content:trigger:", objc.string(identifier), content, Pointer.NULL);
                if (onActivate != null) {
                    remember(identifier, onActivate);
                }
                objc.send(center, "addNotificationRequest:withCompletionHandler:", request, Pointer.NULL);
            } finally {
                objc.send(content, "release");
            }
        } finally {
            objc.poolPop(pool);
        }
    }

    private void disableNative(String reason, Throwable cause) {
        nativeFailed = true;
        if (failureLogged.compareAndSet(false, true)) {
            if (cause == null) {
                logger.info("{}; macOS notifications go through osascript", reason);
            } else {
                logger.warn("{}; macOS notifications go through osascript", reason, cause);
            }
        }
    }

    private static boolean isNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0L;
    }

    /** {@code -userNotificationCenter:willPresentNotification:withCompletionHandler:} and the response twin. */
    public interface DelegateMethod extends Callback {
        void invoke(Pointer self, Pointer cmd, Pointer center, Pointer argument, Pointer completionHandler);
    }

    /** The invoke function of the authorization block: {@code ^(BOOL granted, NSError *error)}. */
    public interface AuthorizationInvoke extends Callback {
        void invoke(Pointer block, byte granted, Pointer error);
    }

    /**
     * The fraction of the Objective-C runtime this backend needs. Callbacks and blocks are static:
     * the delegate class is registered once per process and the framework may call them at any time.
     */
    private static final class ObjC {

        private static final Object CLASS_LOCK = new Object();
        private static DelegateMethod willPresent;
        private static DelegateMethod didReceive;
        private static AuthorizationInvoke authorizationInvoke;
        private static Memory authorizationBlock;
        private static Memory blockDescriptor;
        private static volatile MacUserNotificationsBackend owner;

        private final NativeLibrary runtime = NativeLibrary.getInstance("objc");
        private final Function getClass = runtime.getFunction("objc_getClass");
        private final Function registerName = runtime.getFunction("sel_registerName");
        private final Function msgSend = runtime.getFunction("objc_msgSend");
        private final Function poolPush = runtime.getFunction("objc_autoreleasePoolPush");
        private final Function poolPop = runtime.getFunction("objc_autoreleasePoolPop");

        Pointer cls(String name) {
            return getClass.invokePointer(new Object[] {name});
        }

        Pointer send(Pointer receiver, String selector, Object... arguments) {
            Object[] invocation = new Object[arguments.length + 2];
            invocation[0] = receiver;
            invocation[1] = registerName.invokePointer(new Object[] {selector});
            System.arraycopy(arguments, 0, invocation, 2, arguments.length);
            return msgSend.invokePointer(invocation);
        }

        Pointer poolPush() {
            return poolPush.invokePointer(new Object[0]);
        }

        void poolPop(Pointer pool) {
            poolPop.invokeVoid(new Object[] {pool});
        }

        /** An autoreleased {@code NSString} with {@code text} (UTF-8, never null). */
        Pointer string(String text) {
            byte[] utf8 = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            Memory bytes = new Memory(utf8.length + 1L);
            bytes.write(0, utf8, 0, utf8.length);
            bytes.setByte(utf8.length, (byte) 0);
            return send(cls("NSString"), "stringWithUTF8String:", bytes);
        }

        /** The Java text of an {@code NSString}, or {@code null}. */
        String text(Pointer nsString) {
            if (isNull(nsString)) {
                return null;
            }
            Pointer utf8 = send(nsString, "UTF8String");
            return isNull(utf8) ? null : utf8.getString(0, StandardCharsets.UTF_8.name());
        }

        /**
         * The delegate class, registered with the runtime on first use; later backends of the same
         * process (there is one per application run) take over the callbacks.
         */
        Pointer delegateClass(MacUserNotificationsBackend backend) {
            synchronized (CLASS_LOCK) {
                owner = backend;
                Pointer existing = cls(DELEGATE_CLASS);
                if (!isNull(existing)) {
                    return existing;
                }
                Function allocateClassPair = runtime.getFunction("objc_allocateClassPair");
                Function addMethod = runtime.getFunction("class_addMethod");
                Function addProtocol = runtime.getFunction("class_addProtocol");
                Function getProtocol = runtime.getFunction("objc_getProtocol");
                Function registerClassPair = runtime.getFunction("objc_registerClassPair");

                Pointer type = allocateClassPair.invokePointer(new Object[] {cls("NSObject"), DELEGATE_CLASS, 0L});
                if (isNull(type)) {
                    throw new IllegalStateException("objc_allocateClassPair failed");
                }
                willPresent = (self, cmd, center, notification, completion) -> {
                    try {
                        callBlock(completion, PRESENTATION_OPTIONS);
                    } catch (Throwable t) {
                        logger.debug("willPresentNotification failed: {}", t.toString());
                    }
                };
                didReceive = (self, cmd, center, response, completion) -> {
                    try {
                        MacUserNotificationsBackend current = owner;
                        if (current != null && !isNull(response)) {
                            String action = text(send(response, "actionIdentifier"));
                            Pointer request = send(send(response, "notification"), "request");
                            String identifier = text(send(request, "identifier"));
                            logger.debug("Notification response {} for {}", action, identifier);
                            current.activated(identifier, action);
                        }
                    } catch (Throwable t) {
                        logger.debug("didReceiveNotificationResponse failed: {}", t.toString());
                    } finally {
                        try {
                            callBlock(completion);
                        } catch (Throwable t) {
                            logger.debug("Notification response completion failed: {}", t.toString());
                        }
                    }
                };
                addMethod.invokeInt(new Object[] {type,
                    registerName.invokePointer(new Object[] {
                        "userNotificationCenter:willPresentNotification:withCompletionHandler:"}),
                    willPresent, "v@:@@@?"});
                addMethod.invokeInt(new Object[] {type,
                    registerName.invokePointer(new Object[] {
                        "userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:"}),
                    didReceive, "v@:@@@?"});
                Pointer protocol = getProtocol.invokePointer(new Object[] {"UNUserNotificationCenterDelegate"});
                if (!isNull(protocol)) {
                    addProtocol.invokeInt(new Object[] {type, protocol});
                }
                registerClassPair.invokeVoid(new Object[] {type});
                return type;
            }
        }

        /**
         * The completion handler for {@code requestAuthorization}: a global block (it lives as long as
         * the process, so the framework's copy of it is the block itself) whose invoke function reports
         * the user's answer to the current backend.
         */
        Pointer authorizationBlock() {
            synchronized (CLASS_LOCK) {
                if (authorizationBlock == null) {
                    Pointer globalBlockClass = NativeLibrary.getInstance("System")
                        .getGlobalVariableAddress("_NSConcreteGlobalBlock");
                    authorizationInvoke = (block, granted, error) -> {
                        try {
                            MacUserNotificationsBackend current = owner;
                            if (current != null) {
                                String reason = granted == 0 && !isNull(error)
                                    ? text(send(error, "localizedDescription")) : null;
                                current.authorizationAnswered(granted != 0, reason);
                            }
                        } catch (Throwable t) {
                            logger.debug("Notification authorization callback failed: {}", t.toString());
                        }
                    };
                    Memory descriptor = new Memory(16);
                    descriptor.setLong(0, 0L);
                    descriptor.setLong(8, 32L);
                    Memory block = new Memory(32);
                    block.setPointer(0, globalBlockClass);
                    block.setInt(8, BLOCK_IS_GLOBAL);
                    block.setInt(12, 0);
                    block.setPointer(16, CallbackReference.getFunctionPointer(authorizationInvoke));
                    block.setPointer(24, descriptor);
                    blockDescriptor = descriptor;
                    authorizationBlock = block;
                }
                return authorizationBlock;
            }
        }

        /** Calls an Objective-C block: its invoke function sits at offset 16 and takes the block first. */
        private static void callBlock(Pointer block, Object... arguments) {
            if (isNull(block)) {
                return;
            }
            Object[] invocation = new Object[arguments.length + 1];
            invocation[0] = block;
            System.arraycopy(arguments, 0, invocation, 1, arguments.length);
            Function.getFunction(block.getPointer(16)).invokeVoid(invocation);
        }
    }
}

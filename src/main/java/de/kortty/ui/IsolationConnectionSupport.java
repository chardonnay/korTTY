package de.kortty.ui;

import de.kortty.isolation.ConnectionGroupIsolation;
import de.kortty.isolation.IsolationLevel;
import de.kortty.isolation.IsolationSettings;
import de.kortty.model.ConnectionProtocol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The session isolation rows of the connection editor: how far the connection's sessions are isolated,
 * the strict terminal mode and whether they are incognito. Only the choices and their round trip to
 * {@code ServerConnection} live here, so they are unit-tested without the JavaFX toolkit;
 * {@code ConnectionEditDialog} lays out the controls.
 *
 * <p>The isolation dropdown offers <b>Use the default</b> (stored as {@code null}), naming what the
 * connection then gets and from where (its folder or Settings), followed by the levels the organization's
 * minimum still allows.
 */
final class IsolationConnectionSupport {

    static final String SECTION_KEY = "connEdit.isolation.section";
    static final String LEVEL_LABEL_KEY = "connEdit.isolation.level";
    static final String LEVEL_TOOLTIP_KEY = "connEdit.isolation.level.tooltip";
    /** The entry that follows Settings; {0} names its level. */
    static final String DEFAULT_GLOBAL_KEY = "connEdit.isolation.default.global";
    /** The entry that follows a folder; {0} names the folder, {1} its level. */
    static final String DEFAULT_FOLDER_KEY = "connEdit.isolation.default.folder";
    /** Hint when the chosen level is more than the protocol can get in this version; {0} is the level it gets. */
    static final String UNSUPPORTED_KEY = "connEdit.isolation.unsupported";
    /** Hint when a sandbox is chosen but this computer has none; {0} says why. */
    static final String SANDBOX_UNAVAILABLE_KEY = "connEdit.isolation.sandboxUnavailable";
    static final String TEAMWORK_KEY = "connEdit.isolation.teamwork";
    static final String STRICT_LABEL_KEY = "connEdit.isolation.strict";
    static final String STRICT_TOOLTIP_KEY = "connEdit.isolation.strict.tooltip";
    static final String STRICT_AUTO_KEY = "connEdit.isolation.strict.auto";
    static final String STRICT_ON_KEY = "connEdit.isolation.strict.on";
    static final String STRICT_OFF_KEY = "connEdit.isolation.strict.off";
    static final String INCOGNITO_KEY = "connEdit.isolation.incognito";
    static final String INCOGNITO_TOOLTIP_KEY = "connEdit.isolation.incognito.tooltip";
    static final String INCOGNITO_DENIED_KEY = "connEdit.isolation.incognito.denied";

    static final List<String> KEYS = List.of(SECTION_KEY, LEVEL_LABEL_KEY, LEVEL_TOOLTIP_KEY, DEFAULT_GLOBAL_KEY,
        DEFAULT_FOLDER_KEY, UNSUPPORTED_KEY, SANDBOX_UNAVAILABLE_KEY, TEAMWORK_KEY, STRICT_LABEL_KEY,
        STRICT_TOOLTIP_KEY, STRICT_AUTO_KEY, STRICT_ON_KEY, STRICT_OFF_KEY, INCOGNITO_KEY, INCOGNITO_TOOLTIP_KEY,
        INCOGNITO_DENIED_KEY, "isolation.level.none", "isolation.level.process", "isolation.level.sandbox");

    private IsolationConnectionSupport() {
    }

    /**
     * One entry of the isolation dropdown.
     *
     * @param storedValue what {@code ServerConnection.setIsolationLevel} stores; null follows the folder or Settings
     * @param label       what the dropdown shows
     */
    record LevelChoice(@Nullable IsolationLevel storedValue, @NotNull String label) {

        LevelChoice {
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One entry of the strict terminal mode dropdown.
     *
     * @param storedValue true, false, or null for automatic (on in a sandbox)
     */
    record StrictChoice(@Nullable Boolean storedValue, @NotNull String label) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** The name of a level, as Settings and the dropdowns show it. */
    static String levelName(@NotNull IsolationLevel level) {
        return I18n.get("isolation.level." + level.id());
    }

    /**
     * The isolation dropdown's entries: Use the default, naming what that gives, then every level at or
     * above {@code floor}.
     *
     * @param globalLevel  the level of Settings
     * @param group        the connection's folder, or null
     * @param levelOfGroup the stored level of a folder by its key
     * @param floor        the organization's minimum, or null
     */
    static List<LevelChoice> levelChoices(@Nullable IsolationLevel globalLevel, @Nullable String group,
                                          @Nullable Function<String, IsolationLevel> levelOfGroup,
                                          @Nullable IsolationLevel floor) {
        IsolationLevel global = globalLevel != null ? globalLevel : IsolationLevel.DEFAULT;
        ConnectionGroupIsolation.Inherited folder = ConnectionGroupIsolation.inherited(group, levelOfGroup);
        String defaultLabel;
        if (folder != null) {
            IsolationLevel shown = IsolationLevel.mostRestrictive(folder.level(), floor);
            defaultLabel = I18n.get(DEFAULT_FOLDER_KEY, folder.groupPath(), levelName(shown));
        } else {
            defaultLabel = I18n.get(DEFAULT_GLOBAL_KEY, levelName(IsolationLevel.mostRestrictive(global, floor)));
        }
        List<LevelChoice> choices = new ArrayList<>();
        choices.add(new LevelChoice(null, defaultLabel));
        for (IsolationLevel level : IsolationLevel.atLeastLevels(floor)) {
            choices.add(new LevelChoice(level, levelName(level)));
        }
        return choices;
    }

    /** The entry that shows {@code stored}, or Use the default when there is none for it. */
    static LevelChoice selectedLevel(@NotNull List<LevelChoice> choices, @Nullable IsolationLevel stored) {
        for (LevelChoice choice : choices) {
            if (choice.storedValue() == stored) {
                return choice;
            }
        }
        return choices.getFirst();
    }

    /** The level to store for {@code choice}; null (also for no choice) follows the folder or Settings. */
    static @Nullable IsolationLevel storedLevel(@Nullable LevelChoice choice) {
        return choice != null ? choice.storedValue() : null;
    }

    /** The strict terminal mode dropdown's entries: Automatic, On, Off. */
    static List<StrictChoice> strictChoices() {
        return List.of(new StrictChoice(null, I18n.get(STRICT_AUTO_KEY)),
            new StrictChoice(Boolean.TRUE, I18n.get(STRICT_ON_KEY)),
            new StrictChoice(Boolean.FALSE, I18n.get(STRICT_OFF_KEY)));
    }

    /** The entry that shows {@code stored}. */
    static StrictChoice selectedStrict(@NotNull List<StrictChoice> choices, @Nullable Boolean stored) {
        for (StrictChoice choice : choices) {
            if (Objects.equals(choice.storedValue(), stored)) {
                return choice;
            }
        }
        return choices.getFirst();
    }

    /**
     * The hint under the isolation dropdown for {@code effective}, the level the connection ends up with:
     * that its protocol cannot get it yet, or that this computer has no sandbox; null when neither applies.
     *
     * @param sandboxUnavailableReason why this computer has no sandbox, or null when it has one (or the
     *                                 self-test has not run yet)
     */
    static @Nullable String hint(@Nullable ConnectionProtocol protocol, @NotNull IsolationLevel effective,
                                 @Nullable String sandboxUnavailableReason) {
        boolean workers = de.kortty.core.worker.SessionWorkerProcess.available();
        IsolationLevel supported = IsolationSettings.strongestSupported(protocol, workers, workers);
        if (effective.ordinal() > supported.ordinal()) {
            return I18n.get(UNSUPPORTED_KEY, levelName(supported));
        }
        if (effective == IsolationLevel.SANDBOX && sandboxUnavailableReason != null) {
            return I18n.get(SANDBOX_UNAVAILABLE_KEY, sandboxUnavailableReason);
        }
        return null;
    }
}

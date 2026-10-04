---
title: Shell-Integration
---

# Shell-Integration

Mit Shell-Integration teilt die Shell korTTY mit, wo jeder Prompt beginnt, wo der Befehl beginnt, den Sie eingeben, wo seine Ausgabe beginnt und wie der Befehl geendet hat. Das geschieht mit unsichtbaren OSC-133-Markierungen, denselben, die auch iTerm2, WezTerm, kitty und VS Code auswerten; ein paar Zeilen in der Startdatei der Shell fügen sie hinzu. korTTY nutzt sie, um mit einer Taste zwischen den Prompts einer langen Sitzung zu springen, um auszuwählen oder zu kopieren, was der letzte Befehl ausgegeben hat, um in der Seitenleiste mit den Befehls-Zeitstempeln anzuzeigen, wie jeder Befehl geendet hat und wie lange er lief, und um Ihnen mitzuteilen, wenn ein langer Befehl in einem Tab endet, den Sie gerade nicht ansehen. In einer SSH-Sitzung zeigen sie korTTY außerdem, dass die Shell an ihrem Prompt steht, worauf die `agent`-Befehle des KI-Agenten und die Rückfrage vor dem Schließen eines beschäftigten Tabs angewiesen sind.

Ohne die Markierungen ändert sich nichts: Die Tasten erreichen die Shell wie bisher, und das Terminal sieht aus und verhält sich wie immer.

## Zwischen Prompts springen

| Befehl | Tasten | Wo |
| --- | --- | --- |
| **Vorheriger Prompt** | ++cmd+shift+up++ auf macOS, ++ctrl+shift+up++ unter Windows und Linux | Menü *Bearbeiten* und das Rechtsklickmenü des Terminals |
| **Nächster Prompt** | ++cmd+shift+down++ auf macOS, ++ctrl+shift+down++ unter Windows und Linux | Menü *Bearbeiten* und das Rechtsklickmenü des Terminals |

Ein Sprung scrollt den Bereich so, dass der Prompt oben in der Ansicht steht, mit dem Befehl und seiner Ausgabe darunter.

- Ganz unten im Scrollback springt **Vorheriger Prompt** zum Prompt des Befehls vor dem, den Sie gerade eingeben, und jeder weitere Tastendruck geht einen Befehl weiter zurück.
- Nachdem Sie von Hand gescrollt haben, beginnt ein Sprung bei der obersten sichtbaren Zeile: **Vorheriger Prompt** springt zum nächstgelegenen Prompt darüber und **Nächster Prompt** zum nächstgelegenen darunter, sodass ein Prompt, den Sie bereits sehen, nicht übersprungen wird.
- Ein Prompt auf dem letzten Bildschirm kann nicht nach oben rücken, weil die Ansicht nicht über das Ende hinaus scrollen kann. Die Ansicht bleibt dann, wo sie ist, und der nächste Tastendruck macht bei diesem Prompt weiter.
- **Nächster Prompt** nach dem letzten Prompt kehrt ans Ende zurück. **Vorheriger Prompt** oberhalb des ersten Prompts tut nichts.
- Die Tasten wirken in dem Bereich, der den Tastaturfokus hat, und jeder Bereich eines geteilten Tabs hat seine eigenen Markierungen. *Bearbeiten → Vorheriger Prompt* und *Bearbeiten → Nächster Prompt* wirken im fokussierten Bereich des ausgewählten Tabs, das Rechtsklickmenü in dem Bereich, auf den Sie mit der rechten Maustaste geklickt haben. Wenn das Menü *Bearbeiten* nicht springen kann, nennt die Statusleiste den Grund.

Die Tasten gehören nur dann korTTY, wenn der Bereich Prompt-Markierungen hat und seinen normalen Bildschirm zeigt. In einem Bereich ohne Markierungen und während ein Vollbildprogramm wie `vim`, `less`, `htop` oder `tmux` läuft, erreichen sie das Programm genau wie bisher: unter Windows und Linux als `ESC [ 1 ; 6 A` und `ESC [ 1 ; 6 B`. Bei eingeschaltetem [Broadcast-Modus](terminal.md#broadcast-modus), oder wenn der Bereich an [Multi-Exec](terminal.md#multi-exec) teilnimmt, wirkt ein Sprung nur in dem Bereich, in dem Sie die Taste gedrückt haben; geht die Taste stattdessen an das Programm, geht sie unter Windows und Linux wie jede andere Pfeiltaste auch an die anderen Bereiche. Windows Terminal und GNOME Terminal scrollen mit ++ctrl+shift+up++ und ++ctrl+shift+down++ um eine Zeile; in korTTY sind das ++ctrl+up++ und ++ctrl+down++ (++cmd+up++ und ++cmd+down++ auf macOS).

### Das Rechtsklickmenü

Ein Bereich mit Prompt-Markierungen hat in seinem Rechtsklickmenü **Vorheriger Prompt**, **Nächster Prompt**, **Letzte Ausgabe auswählen** und **Letzte Ausgabe kopieren**, alle ausgegraut, solange ein Vollbildprogramm läuft, die letzten beiden außerdem, bis ein Befehl beendet ist. Ein Bereich, dessen Shell keine Markierungen sendet, zeigt stattdessen **Shell-Integration einrichten…**, das das Fenster mit den unter [Einrichten](#setting-it-up) beschriebenen Snippets öffnet. Keiner dieser Einträge erscheint, solange die Shell-Integration ausgeschaltet ist, oder bei einer Verbindung, deren Terminalemulation die Markierungen nicht übertragen kann (Wyse, TeleVideo, HP, IBM 3270 und 5250, PETSCII).

## Ausgabe eines Befehls auswählen und kopieren

| Befehl | Wo |
| --- | --- |
| **Letzte Ausgabe auswählen** | Menü *Bearbeiten* und das Rechtsklickmenü des Terminals |
| **Letzte Ausgabe kopieren** | Menü *Bearbeiten* und das Rechtsklickmenü des Terminals |

**Letzte Ausgabe auswählen** wählt alles aus, was der letzte Befehl ausgegeben hat, als hätten Sie mit der Maus darüber gezogen: *Bearbeiten → Kopieren* kopiert es dann, und mit **Markierung automatisch in Zwischenablage kopieren** in *Einstellungen → Terminal* wird es sofort kopiert. **Letzte Ausgabe kopieren** legt die Ausgabe direkt in die Zwischenablage und lässt Ihre bestehende Auswahl unverändert. Keiner der beiden Befehle hat ein eigenes Tastenkürzel.

- Die Ausgabe reicht von der Zeile unter der Befehlszeile bis dorthin, wo der nächste Prompt beginnt; Prompt und Befehlszeile gehören nicht dazu. Die Kopie hat am Ende keinen Zeilenumbruch, und Leerzeilen am Ende werden weggelassen. Eine Ausgabe ohne Zeilenumbruch am Ende, etwa die von `printf done`, endet dort, wo der nächste Prompt in derselben Zeile beginnt.
- Der letzte Befehl ist der zuletzt beendete. Solange ein Befehl wie `tail -f` noch läuft, nehmen beide die Ausgabe des Befehls davor, und ein Prompt, an dem Sie ++enter++ ohne Befehl gedrückt haben, zählt nicht.
- *Bearbeiten → Letzte Ausgabe auswählen* und *Bearbeiten → Letzte Ausgabe kopieren* wirken im fokussierten Bereich des ausgewählten Tabs, das Rechtsklickmenü in dem Bereich, auf den Sie mit der rechten Maustaste geklickt haben. Die Statusleiste meldet, was ausgewählt oder kopiert wurde, oder warum nichts: In dem Bereich ist noch kein Befehl beendet, der Befehl hat nichts ausgegeben, im Bereich läuft ein Vollbildprogramm, oder die Shell sendet keine Markierungen.
- Wenn die Ausgabe länger ist, als der Scrollback fasst, sind ihre ersten Zeilen bereits verschwunden: Beide nehmen dann den Rest, ab der ältesten Zeile im Scrollback, und die Statusleiste meldet, dass der Anfang fehlt. Erhöhen Sie **Zurückscrollen** in *Einstellungen → Terminal*, um längere Ausgaben vollständig zu behalten.
- Kopien laufen über die Zwischenablage von korTTY, sodass der [interne Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie sie innerhalb von korTTY hält.

Beide benötigen die Markierungen für den Beginn der Ausgabe und das Ende des Befehls (OSC 133 C und D), die die Snippets von korTTY senden. Eine auf andere Weise eingerichtete Shell, die nur ihre Prompts markiert, erhält Prompt-Sprünge, aber die Statusleiste meldet dann, dass kein Befehl beendet ist.

## Exit-Status und Laufzeit

Die Seitenleiste mit den Befehls-Zeitstempeln links im Terminal zeigt, wie jeder von der Shell markierte Befehl geendet hat. Die Seitenleiste ist standardmäßig ausgeschaltet: Schalten Sie sie mit *Ansicht → Befehls-Zeitstempel anzeigen* (++cmd+shift+t++ auf macOS, ++ctrl+shift+t++ unter Windows und Linux) oder **Befehls-Zeitstempel anzeigen** im Rechtsklickmenü des Terminals ein, oder für jedes neue Terminal mit **Befehls-Zeitstempel anzeigen** in *Einstellungen → Terminal*. korTTY merkt sich die Exit-Status auch bei ausgeblendeter Seitenleiste, sodass sie beim Einschalten für die bereits gelaufenen Befehle erscheinen.

| Zeichen | Bedeutung |
| --- | --- |
| ✓ in Grün | Der Befehl endete mit Exit-Status 0 |
| ✗ in Rot | Der Befehl endete mit einem anderen Exit-Status, zum Beispiel 1 bei einem Fehler oder 130 nach ++ctrl+c++ |
| … | Der Befehl läuft noch; das Zeichen steht in der ersten Zeile seiner Ausgabe |

- Das Zeichen eines beendeten Befehls steht in der Zeile, in der er endete, also dort, wo die Shell den nächsten Prompt ausgibt. Die Uhrzeit in dieser Zeile ist der Zeitpunkt, zu dem der Befehl endete, und darüber, neben dem Datum, ersetzt die Laufzeit des Befehls die Zeit seit der vorherigen Markierung: `10/04 12s`, `10/04 1:05` oder `10/04 <1s` für einen Befehl, der weniger als eine Sekunde dauerte. Die Laufzeit zählt vom Start des Befehls bis zu seinem Ende, ohne die Zeit, die Sie zum Eintippen gebraucht haben.
- Wenn Sie auf die Zeile zeigen, erscheinen Datum und Uhrzeit, die **Laufzeit** und der **Exit-Status**; zeigen Sie auf das … eines laufenden Befehls, sehen Sie, wie lange er schon läuft.
- Ohne Shell-Integration hält die Seitenleiste einen Befehl für beendet, sobald eine halbe Sekunde lang keine neue Ausgabe kommt; ein Befehl, der pausiert, bevor er mehr ausgibt, gilt so zu früh als beendet. Bei einem von der Shell markierten Befehl wartet korTTY stattdessen auf die Markierung der Shell. Befehle, die die Shell nicht markiert, etwa solche, die Sie in ein Programm oder in eine von der ersten aus gestartete zweite SSH-Sitzung eingeben, behalten die Schätzung nach einer halben Sekunde.
- Jedes Zeichen hat neben seiner Farbe eine eigene Form, sodass Sie die Zeichen unterscheiden können, ohne Grün und Rot unterscheiden zu müssen. Eine Shell, die keinen Exit-Status meldet, zeigt die Laufzeit ohne Zeichen.
- Solange die Shell-Integration ausgeschaltet ist, zeigt die Seitenleiste keine Zeichen und wieder die Zeit seit der vorherigen Markierung.

Die Seitenleiste hat eine feste Breite: Bei einer sehr großen Terminalschrift können eine lange Laufzeit oder das Zeichen an ihrem rechten Rand abgeschnitten werden.

## Benachrichtigung, wenn ein langer Befehl endet

Wenn ein Befehl, der mindestens 30 Sekunden lief, in einem Tab endet, den Sie gerade nicht ansehen, markiert korTTY den Tab mit 🔔 und zeigt eine Desktop-Benachrichtigung mit dem Namen des Tabs, dem Exit-Status und der Laufzeit, zum Beispiel *Befehl fehlgeschlagen (Exit-Status 1) nach 2 Min. 14 Sek.* Den Befehl selbst zeigt sie nie. *Einstellungen → Terminal → Benachrichtigungen* schaltet die Benachrichtigung aus oder ändert die 30 Sekunden; Einzelheiten unter [Lange laufende Befehle](terminal-notifications.md#lange-laufende-befehle).

## Einrichten { #setting-it-up }

Fügen Sie das Snippet für Ihre Shell auf jedem Computer, dessen Shell markiert werden soll, in ihre Startdatei ein: auf jedem Server, mit dem Sie sich per SSH verbinden, und auf Ihrem eigenen Computer für lokale Shell-Tabs. korTTY ändert nie Dateien auf einem Server und tippt nie etwas in eine SSH-Sitzung, um das einzurichten. Öffnen Sie nach dem Hinzufügen des Snippets eine neue Shell oder verbinden Sie den Tab neu.

Für einen lokalen Shell-Tab mit bash, zsh oder fish kann korTTY das auch für Sie erledigen, ohne ein Snippet in Ihren Startdateien: siehe [Automatisch zu lokalen Shells hinzufügen](#local-shells).

**Shell-Integration einrichten…** öffnet ein Fenster mit den Snippets: aus dem Rechtsklickmenü eines Terminals, dessen Shell keine Markierungen sendet, oder über die gleichnamige Schaltfläche in *Einstellungen → Terminal → Shell-Integration*, die auch bei ausgeschalteter Einstellung funktioniert. Es hat je einen Tab für bash, zsh und fish, jeweils mit dem Ort, an den das Snippet gehört, und dem Snippet selbst, demselben Text wie unten.

![Das Fenster Shell-Integration einrichten auf seinem Tab bash, mit dem Snippet, seinem Speicherort und der Schaltfläche Kopieren](../assets/screenshots/main/shell-integration-setup.png)

- **Kopieren** legt das Snippet des ausgewählten Tabs in die Zwischenablage, bereit zum Einfügen in die Startdatei in einem Editor auf dem Server. Stattdessen an einem Shell-Prompt eingefügt, markiert es nur diese Shell, bis sie beendet wird. Die Kopie läuft über die Zwischenablage von korTTY, sodass das Snippet mit dem [internen Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie innerhalb von korTTY bleibt, und das Fenster weist darauf hin: Fügen Sie es dann in ein Terminal oder einen Editor von korTTY ein. Sie können auch einen Teil des Textes auswählen und diesen kopieren.
- Das Fenster öffnet sich mit der zuletzt gewählten Shell, beim ersten Mal mit bash. Aus dem Rechtsklickmenü eines lokalen Shell-Tabs, in dem bash, zsh oder fish läuft, öffnet es sich stattdessen mit dieser Shell; korTTY kann nicht erkennen, welche Shell auf einem Server läuft, wählen Sie die Shell für eine SSH-Verbindung daher selbst.
- Das Fenster blockiert den Rest von korTTY nicht und kann daher geöffnet bleiben, während Sie das Snippet in ein Terminal einfügen. Es gibt nur eines: Erneutes Öffnen holt es in den Vordergrund. **Shell-Integration in der Anleitung** an seinem unteren Rand öffnet diese Seite.
- Eine Zeile unter seiner Einleitung verweist auf **Shell-Integration automatisch hinzufügen**, mit dem korTTY das Snippet selbst zu einer lokalen bash, zsh oder fish hinzufügt; siehe [Automatisch zu lokalen Shells hinzufügen](#local-shells).

Jedes Snippet:

- läuft nur in einer interaktiven Shell, deren `TERM` nicht `dumb` ist, sodass `scp`, `rsync` und Skripte, die die Startdatei lesen, nicht betroffen sind;
- wird nur einmal geladen, auch wenn die Startdatei zweimal gelesen wird;
- behält Ihren Prompt und die `PROMPT_COMMAND`-, `precmd`- oder Event-Hooks bei, die Sie bereits haben;
- meldet außerdem mit OSC 7 das Arbeitsverzeichnis der Shell, das korTTY als aktuelles Verzeichnis der Sitzung verwendet, etwa für relative Dateipfade und **Im Snippet-Editor öffnen**.

Um zu prüfen, ob es funktioniert, führen Sie ein paar Befehle aus und drücken ++cmd+shift+up++ (++ctrl+shift+up++ unter Windows und Linux), oder klicken Sie mit der rechten Maustaste auf das Terminal: **Vorheriger Prompt** anstelle von **Shell-Integration einrichten…** bedeutet, dass die Markierungen ankommen.

### bash

Für bash 4.4 oder neuer; in einer älteren bash, etwa der bash 3.2, die macOS als `/bin/bash` mitliefert, tut es nichts. Speichern Sie es auf dem Server als `~/.kortty-shell-integration.bash` und fügen Sie `source ~/.kortty-shell-integration.bash` als letzte Zeile von `~/.bashrc` hinzu, oder fügen Sie es am Ende von `~/.bashrc` ein. Es muss nach allem anderen stehen, was `PROMPT_COMMAND` oder `PS1` setzt, etwa starship, oh-my-bash oder liquidprompt.

```bash
# korTTY shell integration for bash 4.4 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Add it at the end of ~/.bashrc, or save it as ~/.kortty-shell-integration.bash and add
#   source ~/.kortty-shell-integration.bash
# at the end of ~/.bashrc. It does nothing in a non-interactive shell, with TERM=dumb, in an older
# bash, or when it was already loaded.
if [[ $- == *i* && ${TERM:-dumb} != dumb && -z ${__kortty_si_loaded-} ]] &&
    (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )); then
    __kortty_si_loaded=1

    # OSC 7: the working directory as a file: URL, percent-encoded byte by byte.
    __kortty_si_cwd() {
        local LC_ALL=C path=$PWD url= char hex i
        for (( i = 0; i < ${#path}; i++ )); do
            char=${path:i:1}
            if [[ $char == [-/._~A-Za-z0-9] ]]; then
                url+=$char
            else
                printf -v hex '%02X' "'$char"
                url+=%${hex: -2}
            fi
        done
        printf '\e]7;file://%s%s\a' "${HOSTNAME-}" "$url"
    }

    # Runs first in PROMPT_COMMAND, while $? still holds the command's exit status: D, then OSC 7.
    # It returns that status again, so the PROMPT_COMMAND hooks after it still see it.
    __kortty_si_precmd() {
        local exit_code=$?
        printf '\e]133;D;%s\a' "$exit_code"
        __kortty_si_cwd
        return "$exit_code"
    }

    # Runs last in PROMPT_COMMAND: A at the start of the prompt and B at its end, put back whenever
    # a theme rewrote PS1. It leaves $? as it found it, for a prompt that shows it.
    __kortty_si_prompt() {
        local exit_code=$?
        [[ $PS1 == *'\e]133;A\a'* ]] || PS1='\[\e]133;A\a\]'$PS1
        [[ $PS1 == *'\e]133;B\a'* ]] || PS1=$PS1'\[\e]133;B\a\]'
        return "$exit_code"
    }

    # C: bash prints PS0 after it read a command line, right before the command runs.
    [[ ${PS0-} == *'\e]133;C\a'* ]] || PS0=${PS0-}'\e]133;C\a'

    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a'* ]]; then
        PROMPT_COMMAND=(__kortty_si_precmd "${PROMPT_COMMAND[@]}" __kortty_si_prompt)
    else
        PROMPT_COMMAND=__kortty_si_precmd${PROMPT_COMMAND:+$'\n'$PROMPT_COMMAND}$'\n'__kortty_si_prompt
    fi
fi
```

### zsh

Für zsh 5.1 oder neuer. Speichern Sie es als `~/.kortty-shell-integration.zsh` und fügen Sie `source ~/.kortty-shell-integration.zsh` als letzte Zeile von `~/.zshrc` hinzu, oder fügen Sie es am Ende von `~/.zshrc` ein, nach oh-my-zsh, prezto oder starship. Mit powerlevel10k setzen Sie stattdessen `POWERLEVEL9K_TERM_SHELL_INTEGRATION=true` in `~/.p10k.zsh`: powerlevel10k sendet die Markierungen dann selbst.

```zsh
# korTTY shell integration for zsh 5.1 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Add it at the end of ~/.zshrc, or save it as ~/.kortty-shell-integration.zsh and add
#   source ~/.kortty-shell-integration.zsh
# at the end of ~/.zshrc. It does nothing in a non-interactive shell, with TERM=dumb, or when it
# was already loaded.
if [[ -o interactive && ${TERM:-dumb} != dumb && -z ${__kortty_si_loaded-} ]]; then
    typeset -g __kortty_si_loaded=1

    # OSC 7: the working directory as a file: URL, percent-encoded byte by byte.
    __kortty_si_cwd() {
        emulate -L zsh
        setopt no_multibyte
        local LC_ALL=C
        local url= char
        local -i i
        for (( i = 1; i <= ${#PWD}; i++ )); do
            char=${PWD[i]}
            if [[ $char == [-/._~A-Za-z0-9] ]]; then
                url+=$char
            else
                # The byte's value as two hex digits, from zsh's own arithmetic.
                url+=%${(l:2::0:)$(( [##16] #char ))}
            fi
        done
        printf '\e]7;file://%s%s\a' "${HOST-}" "$url"
    }

    # The first precmd hook, while $? still holds the command's exit status: D, then OSC 7.
    __kortty_si_precmd() {
        local exit_code=$?
        printf '\e]133;D;%s\a' "$exit_code"
        __kortty_si_cwd
    }

    # The last precmd hook: A at the start of the prompt and B at its end, put back whenever a theme
    # rewrote PS1.
    __kortty_si_prompt() {
        [[ $PS1 == *$'\e]133;A\a'* ]] || PS1=$'%{\e]133;A\a%}'$PS1
        [[ $PS1 == *$'\e]133;B\a'* ]] || PS1=$PS1$'%{\e]133;B\a%}'
    }

    # C: zsh runs the preexec hooks after it read a command line, right before the command runs.
    __kortty_si_preexec() {
        printf '\e]133;C\a'
    }

    typeset -ga precmd_functions preexec_functions
    precmd_functions=(__kortty_si_precmd ${precmd_functions[@]} __kortty_si_prompt)
    preexec_functions+=(__kortty_si_preexec)
fi
```

### fish

Für fish 3.1 oder neuer. Speichern Sie es als `~/.config/fish/conf.d/kortty.fish`; fish liest diesen Ordner von selbst.

```fish
# korTTY shell integration for fish 3.1 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Save it as ~/.config/fish/conf.d/kortty.fish. It does nothing in a non-interactive shell, with
# TERM=dumb, or when it was already loaded.
if status is-interactive; and test "$TERM" != dumb; and not set -q __kortty_si_loaded
    set -g __kortty_si_loaded 1

    # Before every prompt: OSC 7 with the working directory as a file: URL, then A.
    function __kortty_si_prompt_start --on-event fish_prompt
        printf '\e]7;file://%s%s\a' $hostname (string escape --style=url -- $PWD)
        printf '\e]133;A\a'
    end

    # C right before a command runs, D with its exit status right after it.
    function __kortty_si_preexec --on-event fish_preexec
        printf '\e]133;C\a'
    end
    function __kortty_si_postexec --on-event fish_postexec
        printf '\e]133;D;%s\a' $status
    end

    # B at the end of the prompt.
    if functions -q fish_prompt; and not functions -q __kortty_si_original_prompt
        functions -c fish_prompt __kortty_si_original_prompt
        function fish_prompt
            __kortty_si_original_prompt
            printf '\e]133;B\a'
        end
    end
end
```

## Automatisch zu lokalen Shells hinzufügen { #local-shells }

Für einen lokalen Shell-Tab kann korTTY die Shell-Integration selbst hinzufügen, ohne ein Snippet in Ihren Startdateien. Bearbeiten Sie die Verbindung und kreuzen Sie auf ihrem Tab **Verbindung** unter **Shell-Integration**, unterhalb des Startverzeichnisses, **Shell-Integration automatisch hinzufügen** an. Die Option ist für jede Verbindung ausgeschaltet, bis Sie sie ankreuzen, und wirkt, sobald sich der Tab das nächste Mal verbindet oder neu verbindet, auch für jeden Bereich, den Sie vom Tab abteilen.

korTTY startet dann die Shell mit einer eigenen Startdatei, einem Wrapper, der zunächst die Startdateien ausführt, die die Shell ohnehin gelesen hätte, und anschließend das gleiche Snippet wie oben:

| Shell | Wie korTTY sie startet | Was der Wrapper zuerst ausführt |
| --- | --- | --- |
| bash | mit `--rcfile`, das den Wrapper nennt | `~/.bashrc`; bei einer Login-bash (`-l` oder `--login`, wie die Auswahlen Git Bash und Cygwin sie starten) `/etc/profile` und die erste von `~/.bash_profile`, `~/.bash_login` und `~/.profile` |
| zsh | mit `ZDOTDIR`, das den Ordner des Wrappers nennt | Ihre `.zshenv`, `.zprofile` und `.zshrc` aus Ihrem eigenen `ZDOTDIR` oder Ihrem Home-Verzeichnis, auch wenn Ihre `.zshenv` `ZDOTDIR` verlegt; danach gehört `ZDOTDIR` wieder Ihnen, sodass zsh Ihre `.zlogin` selbst liest und später gestartete Shells den Wrapper nie sehen |
| fish | mit `--init-command`, das fish nach seiner eigenen Konfiguration ausführt | `config.fish` und `conf.d`, wie immer |

- Ihre Startdateien werden nie geändert. Der Wrapper liegt in `~/.kortty/shell-integration`, nur für Sie lesbar, in einem eigenen Ordner pro Shell, den korTTY löscht, wenn der Tab geschlossen wird oder die Shell endet; einen Ordner, den ein Absturz zurückgelassen hat, löscht korTTY, sobald er einen Tag alt ist, wenn es nach einem Neustart zum ersten Mal eine Shell auf diese Weise startet.
- Eine Startdatei, die das Snippet von korTTY bereits enthält, schadet nicht: Das Snippet wird nur einmal geladen.
- Die Versionsgrenzen des Snippets selbst gelten weiterhin: Die bash 3.2, die macOS als `/bin/bash` mitliefert, startet mit dem Wrapper, erhält aber keine Markierungen; wählen Sie daher eine neuere bash, etwa eine aus Homebrew, oder zsh.
- zsh liest seine systemweite `/etc/zshenv` (`/etc/zsh/zshenv` bei einigen Linux-Distributionen) vor dem Wrapper. Setzt diese Datei `ZDOTDIR` selbst, liest zsh den Wrapper nie und startet wie bisher; verschieben Sie die `ZDOTDIR`-Zeile in `~/.zshenv`, oder fügen Sie das Snippet von Hand in Ihre `.zshrc` ein.
- Es funktioniert für bash, zsh und fish, die mit keinen anderen Optionen als `-i`, `-l` und `--login` gestartet werden. Mit jeder anderen Option, etwa `-c`, `--norc` oder einem Skript, mit einer anderen Shell oder mit einer Shell, die über ein anderes Programm wie `env` oder `tmux` gestartet wird, startet die Shell genau so, wie Sie sie eingerichtet haben, und die Zeile unter dem Kontrollkästchen sagt, warum. Unter Windows muss die Shell mit ihrem vollständigen Pfad angegeben sein, wie die Auswahlen Git Bash und Cygwin sie angeben; PowerShell, cmd.exe und WSL können es nicht erhalten.
- bash ignoriert `--rcfile` in einer Login-Shell, daher wird eine Login-bash als interaktive bash gestartet, deren Wrapper die Login-Dateien liest: Sie gilt dann nicht als Login-Shell und liest beim Beenden `~/.bash_logout` nicht.
- Läuft korTTY aus seinem Flatpak-Paket, wird der Wrapper dorthin geschrieben, wo der Host ihn lesen kann, und an die Shell übergeben, die `flatpak-spawn --host` startet. Eine Verbindung ohne eigenen Shell-Befehl startet Ihre Login-Shell auf dem Host, und der Host wählt den Wrapper für bash, zsh oder fish.
- Es benötigt **Befehlsmarkierungen von Shells mit eingerichteter Shell-Integration nutzen (OSC 133)** in *Einstellungen → Terminal*: Solange das ausgeschaltet ist, startet die Shell unverändert, und die Zeile unter dem Kontrollkästchen sagt das.
- korTTY tut dies nie für SSH- oder Mosh-Verbindungen und tippt nie etwas in eine Remote-Sitzung: Auf einem Server kommt das Snippet in die Startdatei, wie unter [Einrichten](#setting-it-up) beschrieben. Eine über [Teamarbeit](teamwork.md) geteilte Verbindung erhält es ebenfalls nie, was auch immer die gemeinsame Datei sagt, und ein Export lässt die Auswahl weg, sodass es immer nur auf dem Computer eingeschaltet wird, auf dem die Shell läuft.

## Einschränkungen

- **tmux und screen** reichen die Markierungen nicht weiter, daher hat eine Shell darin keine. Starten Sie die Shell außerhalb von tmux, oder richten Sie die eigene Prompt-Navigation von tmux ein.
- **Mosh**-Verbindungen übertragen sie nie: mosh-server zeichnet den Bildschirm selbst und verwirft die Markierungen.
- **Lokale Windows-Shells**: Die Snippets decken bash, zsh und fish ab, nicht `cmd.exe` oder PowerShell, und ob Windows die Markierungen einer Shell wie Git Bash oder WSL weiterreicht, hängt von der Windows-Version ab.
- **Größenänderung**: Wenn eine Breitenänderung lange Zeilen neu umbricht, kann ein Sprung bei Prompts oberhalb des neu umbrochenen Textes einige Zeilen danebenliegen, die Ausgabe, die **Letzte Ausgabe auswählen** und **Letzte Ausgabe kopieren** nehmen, kann um ebenso viele Zeilen verschoben sein, und ebenso die Exit-Status-Zeichen in der Zeitstempel-Seitenleiste, wie deren Zeitstempel.
- **Verschachteltes ssh**: OSC 7 nennt einen Host, und der erste Host, den eine Sitzung meldet, wird zum eigenen Host dieser Sitzung. Wenn Sie vom ersten Server per ssh zu einem zweiten weitergehen, auf dem das Snippet ebenfalls eingerichtet ist, verschieben die Meldungen des zweiten Servers das Verzeichnis der Sitzung nicht mehr: korTTY behält das Verzeichnis des ersten Servers bei und behandelt den Bereich als anderswo laufend, sodass [abgelegte Dateien](terminal.md#text-ablegen) nicht kopiert werden und die [Seitenleiste Remote-Dateien](terminal.md#seitenleiste-remote-dateien) pausiert, bis sich der erste Server wieder meldet oder der Tab neu verbindet. Zwei Namen, die sich erst nach dem ersten Punkt unterscheiden, etwa `web01` und `web01.example.com`, gelten als derselbe Host, sodass ein zweiter Server mit demselben Kurznamen nicht unterschieden wird. Hat der erste Server noch kein Verzeichnis gemeldet, bevor Sie weitergehen, gibt es keinen eigenen Host zum Vergleich: Dann wird das Verzeichnis des zweiten Servers übernommen, aber ein eingetipptes `ssh` markiert den Bereich trotzdem als anderswo laufend.
- **Gefälschte Markierungen**: Jedes Programm kann OSC-133-Markierungen ausgeben. Das Schlimmste, was eine gefälschte Markierung bewirken kann, ist, einen Sprung in die falsche Zeile zu lenken, **Letzte Ausgabe auswählen** und **Letzte Ausgabe kopieren** andere Zeilen des Bereichs als die Ausgabe des letzten Befehls nehmen zu lassen, einen falschen Exit-Status, eine falsche Laufzeit oder Endzeit in der Zeitstempel-Seitenleiste anzuzeigen oder den Tab zu markieren und eine Benachrichtigung *Befehl beendet* mit einem erfundenen Exit-Status anzuzeigen. Der Text der Benachrichtigung stammt von korTTY selbst, sodass eine gefälschte Markierung keine eigenen Worte hineinbringen kann, und die Laufzeit misst korTTY, sodass höchstens eine solche Benachrichtigung pro Tab alle 10 Sekunden möglich ist.

Die Markierungen und die Exit-Status existieren nur im Arbeitsspeicher von korTTY, solange der Bereich geöffnet ist: Sie werden nicht mit einem [Projekt](projects.md) gespeichert, die Ausgabe eines wiederhergestellten Projekts hat keine, und sie verlassen nie Ihren Computer. Die Zeitpunkte, zu denen Befehle endeten, sind Zeitstempel wie alle anderen, die ein Projekt mit den übrigen Zeitstempeln der Seitenleiste speichert.

## Ausschalten

*Einstellungen → Terminal → Shell-Integration* hat **Befehlsmarkierungen von Shells mit eingerichteter Shell-Integration nutzen (OSC 133)**, standardmäßig eingeschaltet. Ausgeschaltet ignoriert korTTY die Markierungen: Die Prompt-Tasten erreichen das Programm, das Rechtsklickmenü zeigt keine Einträge der Shell-Integration, die Zeitstempel-Seitenleiste zeigt keine Exit-Status, kein langer Befehl markiert seinen Tab oder löst eine Benachrichtigung aus, und korTTY erkennt allein am Text des Prompts, dass eine SSH-Sitzung an ihrem Prompt steht, für die `agent`-Befehle des KI-Agenten und für die Rückfrage vor dem Schließen eines beschäftigten Tabs. Diese eine Einstellung deckt jede Verwendung der Markierungen ab; *Einstellungen → KI* hat keine eigene Option mehr dafür. Die Änderung gilt für offene Tabs, sobald Sie speichern. Die Markierungen bleiben in jedem Fall unsichtbar, weil das Terminal OSC-133-Sequenzen nie anzeigt. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md#hinweise).

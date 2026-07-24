---
title: Fenster
---

# Fenster

Auf dieser Registerkarte werden das Verhalten der Fenstergeometrie, die Beibehaltung des Dashboard-Status und die Sichtbarkeit der Menüleiste konfiguriert. Öffnen über **Konfiguration → Globale Einstellungen → Fenster**; in `~/.kortty/global-settings.xml` gespeichert.

![Window settings tab](../../assets/screenshots/settings/window.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Fenstergeometrie merken | umschalten | — | Auf | `rememberWindowGeometry` |
| Dashboard-Status merken | umschalten | — | Auf | `rememberDashboardState` |
| Werkzeugfenster als Registerkarten öffnen | umschalten | – | Aus | `openToolWindowsAsTabs` |
| Feste Fenstergeometrie verwenden | umschalten | — | Aus | `useFixedWindowGeometry` |
| Breite: | Nummer | 400–4000 | — | `fixedWindowGeometry.width` |
| Höhe: | Nummer | 300–3000 | — | `fixedWindowGeometry.height` |
| X-Position: | Nummer | 0–5000 | — | `fixedWindowGeometry.x` |
| Y-Position: | Nummer | 0–3000 | — | `fixedWindowGeometry.y` |

!!! note
    Wenn **Feste Fenstergeometrie verwenden** aktiviert ist, hat sie Vorrang vor **Fenstergeometrie speichern**. Das Fenster wird immer an der angegebenen Position und Größe geöffnet und ignoriert alle zuvor gespeicherten Geometrien.

!!! note
    Die Einstellung **Dashboard-Status merken** behält bei, ob das Dashboard-Panel beim letzten Schließen der Anwendung geöffnet oder geschlossen war, und stellt diesen Status beim nächsten Start wieder her.

!!! note
    Wenn **Toolfenster als Registerkarten öffnen** aktiviert ist, werden Verwaltungstools (Snippets, JobScheduler, AI Manager, gespeicherte Chats, Verwaltung von Anmeldeinformationen/GPG/SSH-Schlüsseln, Videomanager, Teamwork-Einstellungen, Terminaleffekte) als Registerkarten im Hauptfenster und nicht in separaten Fenstern geöffnet. Die Registerkarte wird in dem Fenster geöffnet, dessen Menü Sie verwendet haben. Wenn also mehrere Hauptfenster geöffnet sind, verfügt jedes Fenster über eigene Werkzeugregisterkarten. Beim erneuten Öffnen eines Tools wird die vorhandene Registerkarte fokussiert. Der Snippet-Editor und die AI-Code-Analyse öffnen jedes Mal einen neuen Tab. Die Einstellung wird beim nächsten Öffnen eines Tools wirksam.

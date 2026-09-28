---
title: Fenster
---

# Fenster

Auf dieser Registerkarte werden das Verhalten der Fenstergeometrie, die Beibehaltung des Dashboard-Status und die Sichtbarkeit der Menüleiste konfiguriert. Öffnen über **Konfiguration → Globale Einstellungen → Fenster**; in `~/.kortty/global-settings.xml` gespeichert.

![Window settings tab](../../assets/screenshots/settings/window.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Fenstergeometrie merken | umschalten | — | Ein | `rememberWindowGeometry` |
| Dashboard-Status merken | umschalten | – | Ein | `rememberDashboardState` |
| Toolfenster als Registerkarten öffnen | umschalten | – | Aus | `openToolWindowsAsTabs` |
| Feste Fenstergeometrie verwenden | umschalten | – | Aus | `useFixedWindowGeometry` |
| Breite: | Nummer | 400–4000 | – | `fixedWindowGeometry.width` |
| Höhe: | Nummer | 300–3000 | – | `fixedWindowGeometry.height` |
| X Position: | Nummer | 0–5000 | — | `fixedWindowGeometry.x` |
| Y-Position: | Nummer | 0–3000 | – | `fixedWindowGeometry.y` |

Wenn **Fenstergeometrie merken** aktiviert ist, speichert KorTTY die Position und Größe jedes vom Benutzer veränderbaren Anwendungsfensters und benannten Dialogs separat. Beim erneuten Öffnen eines Fensters wird die für diesen Fenstertyp ausgewählte Geometrie wiederhergestellt. Wenn der vorherige Monitor nicht mehr angeschlossen ist, verschiebt KorTTY ihn zurück auf einen verfügbaren Bildschirm. Unter macOS werden die gespeicherten Grenzen eines Hauptfensters erneut angewendet, nachdem die native einheitliche Titelleiste bereit ist, sodass das System die wiederhergestellte Position beim Öffnen nicht verschieben kann. Eine geänderte Schriftartenskalierung der Benutzeroberfläche behält die gespeicherte Position bei, lässt das Fenster jedoch eine neue Größe berechnen, sodass übersetzte oder vergrößerte Beschriftungen weiterhin passen. Kurzlebige Bestätigungen und Fortschrittsmeldungen behalten ihre vom Inhalt abgeleitete Größe.

!!! note
    Wenn **Feste Fenstergeometrie verwenden** aktiviert ist, hat sie Vorrang vor **Fenstergeometrie speichern** für Hauptfenster des Terminals. Dialoge verwenden weiterhin ihre eigene gespeicherte Geometrie.

!!! note
    Die Einstellung **Dashboard-Status merken** behält bei, ob das Dashboard-Panel beim letzten Schließen der Anwendung geöffnet oder geschlossen war, und stellt diesen Status beim nächsten Start wieder her.

!!! note
    Mit **Tool-Fenster als Tabs öffnen** aktiviert, öffnen sich Verwaltungstools (Snippet-Manager, JobScheduler, KI-Manager, Gespeicherte Chats, Sitzungsjournale, Credential/GPG/SSH-Schlüsselverwaltung, Videoverwaltung, Teamwork-Einstellungen, Terminal-Effekte) als Tabs im Hauptfenster anstelle separater Fenster. Der Tab öffnet sich in dem Fenster, dessen Menü Sie benutzt haben; bei mehreren offenen Hauptfenstern sammelt jedes Fenster seine eigenen Tool-Tabs. Das erneute Öffnen eines Tools fokussiert den bereits vorhandenen Tab. Der Snippet-Manager hat einen Tab pro Hauptfenster und öffnet die von Ihnen bearbeiteten Snippets als Tabs innerhalb des Managers; ein aus einem anderen Ort geöffneter Snippet-Editor (z. B. der SFTP-Manager, der Dateibrowser oder das Terminal) sowie der Sitzungsjournal-Viewer öffnen jedes Mal einen neuen Hauptfenster-Tab, aber ein bereits geöffneter Snippet-Editor wird in den Vordergrund gebracht statt zweimal zu öffnen. Die Vollständige Code-Analyse ist ein Seitenpanel im Snippet-Editor, kein eigener Tab. Die Einstellung tritt beim nächsten Öffnen eines Tools in Kraft.

    Ein als Registerkarte gehostetes Werkzeug verfügt über keine separate Fenstergeometrie. Seine verfügbare Größe richtet sich nach dem Hauptfenster und seiner gespeicherten Hauptfenstergeometrie.

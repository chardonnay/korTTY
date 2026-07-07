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
| Feste Fenstergeometrie verwenden | umschalten | — | Aus | `useFixedWindowGeometry` |
| Breite: | Nummer | 400–4000 | — | `fixedWindowGeometry.width` |
| Höhe: | Nummer | 300–3000 | — | `fixedWindowGeometry.height` |
| X-Position: | Nummer | 0–5000 | — | `fixedWindowGeometry.x` |
| Y-Position: | Nummer | 0–3000 | — | `fixedWindowGeometry.y` |

!!! Notiz
    Wenn **Feste Fenstergeometrie verwenden** aktiviert ist, hat sie Vorrang vor **Fenstergeometrie speichern**. Das Fenster wird immer an der angegebenen Position und Größe geöffnet und ignoriert alle zuvor gespeicherten Geometrien.

!!! Notiz
    Die Einstellung **Dashboard-Status merken** behält bei, ob das Dashboard-Panel beim letzten Schließen der Anwendung geöffnet oder geschlossen war, und stellt diesen Status beim nächsten Start wieder her.

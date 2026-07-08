---
title: Sprache
---

# Sprache

Konfigurieren Sie die Sprache der Benutzeroberfläche für korTTY. Unterstützt 8 integrierte Sprachen sowie automatische Erkennung basierend auf Ihren Systemeinstellungen. Öffnen über **Konfiguration → Globale Einstellungen → Sprache**; in `~/.kortty/global-settings.xml` gespeichert.

![Language settings tab](../../assets/screenshots/settings/language.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Wählen Sie Sprache: | Dropdown | Automatische Erkennung (Systemsprache), Englisch, Deutsch, Italienisch, Spanisch, Portugiesisch, Französisch, Kroatisch, Niederländisch | Automatische Erkennung (Systemsprache) | `language` |

!!! Notiz
    Sprachänderungen werden nach dem Neustart der Anwendung wirksam. Die Einstellung wird beim nächsten Start von korTTY auf die Beschriftungen, Menüs und Dialoge der Benutzeroberfläche angewendet.

!!! Hinweis „Datums- und Zahlenformate“
    Die installierte Anwendung bündelt Java-Gebietsschemadaten nur für die 8 unterstützten Oberflächensprachen. Läuft Ihr Betriebssystem in einem Gebietsschema außerhalb dieser Liste, werden Datums- und Zahlenangaben nach englischen Konventionen formatiert.
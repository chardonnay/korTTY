---
title: Sprache
---

# Sprache

Configure the user interface language for korTTY. Supports 8 built-in languages plus automatic detection based on your system settings. Open via **Configuration → Global Settings → Language**; stored in `~/.kortty/global-settings.xml`.

![Language settings tab](../../assets/screenshots/settings/language.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Wählen Sie Sprache: | Dropdown | Automatische Erkennung (Systemsprache), Englisch, Deutsch, Italienisch, Spanisch, Portugiesisch, Französisch, Kroatisch, Niederländisch | Automatische Erkennung (Systemsprache) | `language` |

!!! note
    Sprachänderungen werden nach dem Neustart der Anwendung wirksam. Die Einstellung wird beim nächsten Start von korTTY auf die Beschriftungen, Menüs und Dialoge der Benutzeroberfläche angewendet.

!!! note "Datums- und Zahlenformate"
    Die installierte Anwendung bündelt Java-Gebietsschemadaten nur für die 8 unterstützten Oberflächensprachen. Läuft Ihr Betriebssystem in einem Gebietsschema außerhalb dieser Liste, werden Datums- und Zahlenangaben nach englischen Konventionen formatiert.
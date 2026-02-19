package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Supported translation API providers for dynamic i18n language file generation.
 */
@XmlType(name = "translationApiProvider")
@XmlEnum
public enum TranslationApiProvider {
    @XmlEnumValue("GOOGLE_TRANSLATE") GOOGLE_TRANSLATE,
    @XmlEnumValue("DEEPL") DEEPL,
    @XmlEnumValue("LIBRETRANSLATE") LIBRETRANSLATE,
    @XmlEnumValue("MICROSOFT") MICROSOFT,
    @XmlEnumValue("YANDEX") YANDEX
}

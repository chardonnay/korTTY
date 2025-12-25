package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * SSH authentication methods.
 */
@XmlEnum
public enum AuthMethod {
    @XmlEnumValue("PASSWORD")
    PASSWORD,
    
    @XmlEnumValue("PUBLIC_KEY")
    PUBLIC_KEY,
    
    @XmlEnumValue("KEYBOARD_INTERACTIVE")
    KEYBOARD_INTERACTIVE
}

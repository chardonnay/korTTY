package de.kortty.model;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** JAXB adapter persisting {@link OffsetDateTime} as ISO-8601 with offset (e.g. 2026-08-03T14:15:02.123+02:00). */
public class IsoOffsetDateTimeAdapter extends XmlAdapter<String, OffsetDateTime> {

    @Override
    public OffsetDateTime unmarshal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @Override
    public String marshal(OffsetDateTime value) {
        return value != null ? value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
    }
}

package de.kortty.persistence;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JAXB adapter for LocalDateTime serialization.
 */
public class LocalDateTimeAdapter extends XmlAdapter<String, LocalDateTime> {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
    @Override
    public LocalDateTime unmarshal(String value) throws Exception {
        return value != null ? LocalDateTime.parse(value, FORMATTER) : null;
    }
    
    @Override
    public String marshal(LocalDateTime value) throws Exception {
        return value != null ? value.format(FORMATTER) : null;
    }
}

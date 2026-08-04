package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;

/**
 * One mark drawn on a journal screenshot: a freehand stroke, a rectangle, or a text label.
 *
 * <p>Coordinates are in the <b>image's own pixel space</b>, never in screen or canvas units, so an
 * annotation survives every zoom level, a different display scale and a re-export.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalAnnotation")
public class SessionJournalAnnotation {

    /** The default marker colour; the editor offers others but starts here. */
    public static final String DEFAULT_COLOR = "#e11d48";

    public static final double DEFAULT_STROKE_WIDTH = 6.0;
    public static final double DEFAULT_FONT_SIZE = 28.0;

    @XmlEnum
    public enum Kind {
        /** Freehand stroke; {@link #points} holds x,y pairs. */
        PEN,
        /** Rectangle; {@link #points} holds exactly x, y, width, height. */
        BOX,
        /**
         * Rectangle whose contents are coarsened into blocks until they cannot be read — for
         * hiding a value without cutting the surrounding context away. Same geometry as
         * {@link #BOX}; {@link #strokeWidth} controls how coarse the blocks are.
         */
        PIXELATE,
        /** Text label; {@link #points} holds the baseline anchor x, y. */
        TEXT
    }

    @XmlElement
    private Kind kind = Kind.PEN;

    /** {@code #rrggbb}; anything unparsable falls back to {@link #DEFAULT_COLOR} when drawn. */
    @XmlElement
    private String color = DEFAULT_COLOR;

    @XmlElement
    private double strokeWidth = DEFAULT_STROKE_WIDTH;

    @XmlElement
    private double fontSize = DEFAULT_FONT_SIZE;

    @XmlElement
    private String text;

    @XmlElementWrapper(name = "points")
    @XmlElement(name = "v")
    private List<Double> points = new ArrayList<>();

    public SessionJournalAnnotation() {
    }

    public SessionJournalAnnotation(Kind kind, String color, double strokeWidth) {
        this.kind = kind != null ? kind : Kind.PEN;
        setColor(color);
        this.strokeWidth = strokeWidth > 0 ? strokeWidth : DEFAULT_STROKE_WIDTH;
    }

    public SessionJournalAnnotation(SessionJournalAnnotation other) {
        if (other != null) {
            this.kind = other.kind;
            this.color = other.color;
            this.strokeWidth = other.strokeWidth;
            this.fontSize = other.fontSize;
            this.text = other.text;
            this.points = new ArrayList<>(other.points != null ? other.points : List.of());
        }
    }

    public Kind getKind() {
        return kind != null ? kind : Kind.PEN;
    }

    public void setKind(Kind kind) {
        this.kind = kind != null ? kind : Kind.PEN;
    }

    public String getColor() {
        return color != null && !color.isBlank() ? color : DEFAULT_COLOR;
    }

    public void setColor(String color) {
        String trimmed = color != null ? color.trim() : "";
        this.color = trimmed.isEmpty() ? DEFAULT_COLOR : trimmed;
    }

    public double getStrokeWidth() {
        return strokeWidth > 0 ? strokeWidth : DEFAULT_STROKE_WIDTH;
    }

    public void setStrokeWidth(double strokeWidth) {
        this.strokeWidth = strokeWidth > 0 ? strokeWidth : DEFAULT_STROKE_WIDTH;
    }

    public double getFontSize() {
        return fontSize > 0 ? fontSize : DEFAULT_FONT_SIZE;
    }

    public void setFontSize(double fontSize) {
        this.fontSize = fontSize > 0 ? fontSize : DEFAULT_FONT_SIZE;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        String trimmed = text != null ? text.trim() : "";
        this.text = trimmed.isEmpty() ? null : trimmed;
    }

    public List<Double> getPoints() {
        if (points == null) {
            points = new ArrayList<>();
        }
        return points;
    }

    public void setPoints(List<Double> points) {
        this.points = points != null ? new ArrayList<>(points) : new ArrayList<>();
    }

    public void addPoint(double x, double y) {
        getPoints().add(x);
        getPoints().add(y);
    }

    /** True when the annotation carries enough geometry to be drawn at all. */
    public boolean isDrawable() {
        List<Double> values = getPoints();
        return switch (getKind()) {
            case PEN -> values.size() >= 4 && values.size() % 2 == 0;
            case BOX, PIXELATE -> values.size() >= 4
                && Math.abs(values.get(2)) >= 1 && Math.abs(values.get(3)) >= 1;
            case TEXT -> values.size() >= 2 && text != null && !text.isBlank();
        };
    }

    /** Edge length of one block for {@link Kind#PIXELATE}, derived from the chosen width. */
    public int blockSize() {
        return (int) Math.max(8, Math.round(getStrokeWidth() * 2));
    }

    /**
     * A short token that changes whenever the marks do. The rendered PNG keeps its file name, so
     * without this the browser would keep showing the copy it already cached.
     */
    public static String versionToken(List<SessionJournalAnnotation> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        long hash = 17;
        for (SessionJournalAnnotation annotation : annotations) {
            hash = hash * 31 + annotation.getKind().ordinal();
            hash = hash * 31 + annotation.getColor().hashCode();
            hash = hash * 31 + Double.hashCode(annotation.getStrokeWidth());
            hash = hash * 31 + Double.hashCode(annotation.getFontSize());
            hash = hash * 31 + (annotation.getText() != null ? annotation.getText().hashCode() : 0);
            for (Double value : annotation.getPoints()) {
                hash = hash * 31 + Double.hashCode(value);
            }
        }
        return Long.toHexString(hash & 0xffffffffL);
    }
}

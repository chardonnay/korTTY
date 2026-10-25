package de.kortty.ui;

import de.kortty.ui.ConnectionMapPdfWriter.Labels;
import de.kortty.ui.ConnectionMapPdfWriter.MapNode;
import de.kortty.ui.ConnectionMapPdfWriter.Request;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The map's PDF: the requested orientation, every node, zone and legend label in the text, the logo and
 * the badge as images, and a large map that continues on further pages without losing a node.
 */
class ConnectionMapPdfWriterTest {

    private static final Labels LABELS = new Labels("This computer", "Jump route", "Tunnel", "Teamwork",
        "External jump servers", "No folder");

    /** The names and labels that the demo map must show on its page. */
    private static final List<String> DEMO_TEXTS = List.of(
        "Connection map", "Production", "Monitoring", "Staging", "Teamwork",
        "Bastion", "Web 01", "DB 01", "Grafana", "Staging API", "Windows box", "Shared DB", "Laptop",
        "gate.example.net:2200", "This computer", "External jump servers", "No folder",
        "Jump route", "Tunnel", "Teamwork");

    @Test
    void landscapeAndPortraitPagesHoldTheMapWithItsTextAndImages() throws IOException {
        Path landscape = Files.createTempFile("connection-map-landscape-", ".pdf");
        Path portrait = Files.createTempFile("connection-map-portrait-", ".pdf");
        try {
            ConnectionMapPdfWriter.write(landscape, demoRequest(true));
            ConnectionMapPdfWriter.write(portrait, demoRequest(false));

            try (PDDocument document = Loader.loadPDF(landscape.toFile())) {
                PDRectangle box = document.getPage(0).getMediaBox();
                assertWithMessage("landscape page is wider than it is high").that(box.getWidth())
                    .isGreaterThan(box.getHeight());
                assertWithMessage("logo and badge are images on page 1").that(imageCount(document.getPage(0)))
                    .isAtLeast(2);
                assertDemoText(new PDFTextStripper().getText(document));
            }
            try (PDDocument document = Loader.loadPDF(portrait.toFile())) {
                PDRectangle box = document.getPage(0).getMediaBox();
                assertWithMessage("portrait page is higher than it is wide").that(box.getHeight())
                    .isGreaterThan(box.getWidth());
                assertWithMessage("logo and badge are images on page 1").that(imageCount(document.getPage(0)))
                    .isAtLeast(2);
                assertDemoText(new PDFTextStripper().getText(document));
            }
        } finally {
            Files.deleteIfExists(landscape);
            Files.deleteIfExists(portrait);
        }
    }

    @Test
    void aLargeMapContinuesOnFurtherPagesAndKeepsEveryNode() throws IOException {
        Path landscape = Files.createTempFile("connection-map-large-landscape-", ".pdf");
        Path portrait = Files.createTempFile("connection-map-large-portrait-", ".pdf");
        try {
            LargeMap map = largeMap();
            ConnectionMapPdfWriter.write(landscape, map.request(true));
            ConnectionMapPdfWriter.write(portrait, map.request(false));

            for (Path file : List.of(landscape, portrait)) {
                try (PDDocument document = Loader.loadPDF(file.toFile())) {
                    assertWithMessage(file.getFileName() + " continues on further pages")
                        .that(document.getNumberOfPages()).isGreaterThan(1);
                    String text = new PDFTextStripper().getText(document);
                    for (String name : map.names()) {
                        assertWithMessage(file.getFileName() + " shows " + name).that(text).contains(name);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(landscape);
            Files.deleteIfExists(portrait);
        }
    }

    private static void assertDemoText(String text) {
        for (String expected : DEMO_TEXTS) {
            assertWithMessage("page text contains " + expected).that(text).contains(expected);
        }
    }

    private static int imageCount(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject) {
                count++;
            }
        }
        return count;
    }

    /** Production (with a saved and an external jump server), monitoring, a tunnel, staging with a badge, teamwork and a folder-less node. */
    private static Request demoRequest(boolean landscape) {
        BufferedImage badge = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < badge.getWidth(); x++) {
            for (int y = 0; y < badge.getHeight(); y++) {
                badge.setRGB(x, y, 0xFF2563EB);
            }
        }
        List<ConnectionMapLayout.Entry> entries = List.of(
            new ConnectionMapLayout.Entry("bastion", "Production", "bastion.example.com", 2222, null, 0, null),
            new ConnectionMapLayout.Entry("web01", "Production", "web01.prod.example", 22,
                "bastion.example.com", 2222, null),
            new ConnectionMapLayout.Entry("db01", "Production", "db01.prod.example", 22,
                "gate.example.net", 2200, null),
            new ConnectionMapLayout.Entry("grafana", "Monitoring", "grafana.internal", 3000, null, 0, null),
            new ConnectionMapLayout.Entry("staging-api", "Staging", "api.staging.example", 22, null, 0,
                "L 8443 -> api.staging.example:443"),
            new ConnectionMapLayout.Entry("winbox", "Staging", "win.staging.example", 3389, null, 0, null),
            new ConnectionMapLayout.Entry("shared", "Teamwork", "shared-db.example", 22, null, 0, null),
            new ConnectionMapLayout.Entry("laptop", null, "localhost", 22, null, 0, null));
        Map<String, MapNode> nodes = new LinkedHashMap<>();
        nodes.put("bastion", new MapNode("Bastion", "bastion.example.com:2222", false, null));
        nodes.put("web01", new MapNode("Web 01", "web01.prod.example", false, null));
        nodes.put("db01", new MapNode("DB 01", "db01.prod.example", false, null));
        nodes.put("grafana", new MapNode("Grafana", "grafana.internal:3000", false, null));
        nodes.put("staging-api", new MapNode("Staging API", "api.staging.example", false, null));
        nodes.put("winbox", new MapNode("Windows box", "win.staging.example:3389", false, badge));
        nodes.put("shared", new MapNode("Shared DB", "shared-db.example", true, null));
        nodes.put("laptop", new MapNode("Laptop", "localhost", false, null));
        Map<String, String> colors = Map.of(
            "Production", "#2563EB", "Monitoring", "#16A34A", "Staging", "#F59E0B", "Teamwork", "#9333EA");
        return new Request("Connection map", "8 connections, 4 folders", landscape, entries, nodes,
            key -> key, colors::get, LABELS);
    }

    /** Thirty folders of five connections each, with jump routes, a tunnel and a teamwork node in each folder. */
    private static LargeMap largeMap() {
        List<ConnectionMapLayout.Entry> entries = new ArrayList<>();
        Map<String, MapNode> nodes = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int folder = 1; folder <= 30; folder++) {
            for (int connection = 1; connection <= 5; connection++) {
                String id = "c" + folder + "-" + connection;
                String name = String.format("srv-%02d-%d", folder, connection);
                String jump = connection == 1 ? "gate.example.net" : null;
                String tunnel = connection == 5 ? "L 5432 -> db.example:5432" : null;
                entries.add(new ConnectionMapLayout.Entry(id, String.format("Folder %02d", folder),
                    name + ".example", 22, jump, jump != null ? 2200 : 0, tunnel));
                nodes.put(id, new MapNode(name, name + ".example", connection == 3, null));
                names.add(name);
            }
        }
        return new LargeMap(entries, nodes, names);
    }

    private record LargeMap(List<ConnectionMapLayout.Entry> entries, Map<String, MapNode> nodes, List<String> names) {

        Request request(boolean landscape) {
            return new Request("Large connection map", "150 connections in 30 folders", landscape, entries, nodes,
                key -> key, key -> null, LABELS);
        }
    }
}

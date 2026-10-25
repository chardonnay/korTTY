package de.kortty.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Places the connection map without JavaFX: one zone per folder, the folder's connections in a
 * small grid inside it, this computer on the left, and the edges for jump servers and tunnels.
 * Zones are packed into shelves no wider than the given width. A jump server that is saved as a
 * connection of its own is that connection's node; any other one gets a node of its own in the
 * zone {@link #EXTERNAL_ZONE}.
 */
final class ConnectionMapLayout {

    static final double NODE_WIDTH = 176;
    static final double NODE_HEIGHT = 44;
    static final double NODE_GAP_X = 40;
    static final double NODE_GAP_Y = 22;
    static final double ZONE_PADDING = 14;
    static final double ZONE_TITLE = 28;
    static final double ZONE_GAP = 34;
    static final double ORIGIN_X = 270;
    static final double ORIGIN_Y = 24;
    static final double ME_X = 28;
    /** The node of this computer, where every jump route and tunnel starts. */
    static final String ME = "\u0000me";
    /** The zone of jump servers that are not saved as connections. */
    static final String EXTERNAL_ZONE = "\u0000external";

    /**
     * One connection on the map.
     *
     * @param group    the folder path, empty for none
     * @param jumpHost the enabled jump server's host, or null
     * @param tunnel   a short description of the enabled tunnels, or null without any
     */
    record Entry(String id, String group, String host, int port, String jumpHost, int jumpPort, String tunnel) { }

    record Zone(String key, double x, double y, double width, double height) { }

    /** A node; {@code external} nodes are jump servers without a saved connection, {@code id} {@code jump:host:port}. */
    record Node(String id, String zone, double x, double y, boolean external, String label) {
        double centerX() { return x + NODE_WIDTH / 2; }
        double centerY() { return y + NODE_HEIGHT / 2; }
    }

    enum EdgeKind { JUMP, TUNNEL }

    record Edge(String from, String to, EdgeKind kind, String label) { }

    record Result(List<Zone> zones, List<Node> nodes, List<Edge> edges, double width, double height) {
        Node node(String id) {
            for (Node node : nodes) {
                if (node.id().equals(id)) {
                    return node;
                }
            }
            return null;
        }

        /** The zone whose area holds the point, or null. */
        Zone zoneAt(double x, double y) {
            for (Zone zone : zones) {
                if (x >= zone.x() && x <= zone.x() + zone.width() && y >= zone.y() && y <= zone.y() + zone.height()) {
                    return zone;
                }
            }
            return null;
        }
    }

    private ConnectionMapLayout() { }

    static Result layout(List<Entry> entries, double maxWidth) {
        double limit = Math.max(ORIGIN_X + zoneWidth(1) + 20, maxWidth);
        Map<String, List<String>> members = new LinkedHashMap<>();
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> groupKey(a.group()).compareToIgnoreCase(groupKey(b.group())));
        for (Entry entry : sorted) {
            members.computeIfAbsent(groupKey(entry.group()), key -> new ArrayList<>()).add(entry.id());
        }

        List<Edge> edges = new ArrayList<>();
        Map<String, String> externalLabels = new LinkedHashMap<>();
        Set<String> meToJump = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.jumpHost() != null && !entry.jumpHost().isBlank()) {
                String jump = savedJump(entries, entry);
                if (jump == null) {
                    jump = "jump:" + entry.jumpHost().trim().toLowerCase(Locale.ROOT) + ":" + entry.jumpPort();
                    externalLabels.putIfAbsent(jump, entry.jumpHost().trim()
                        + (entry.jumpPort() > 0 && entry.jumpPort() != 22 ? ":" + entry.jumpPort() : ""));
                }
                if (meToJump.add(jump)) {
                    edges.add(new Edge(ME, jump, EdgeKind.JUMP, null));
                }
                edges.add(new Edge(jump, entry.id(), EdgeKind.JUMP, null));
            }
            if (entry.tunnel() != null) {
                edges.add(new Edge(ME, entry.id(), EdgeKind.TUNNEL, entry.tunnel()));
            }
        }
        if (!externalLabels.isEmpty()) {
            members.put(EXTERNAL_ZONE, new ArrayList<>(externalLabels.keySet()));
        }
        // Zones with jump routes and tunnels come first, next to this computer, so their lines stay short.
        Map<String, String> zoneOf = new LinkedHashMap<>();
        members.forEach((zone, ids) -> ids.forEach(id -> zoneOf.put(id, zone)));
        Map<String, Integer> links = new LinkedHashMap<>();
        for (Edge edge : edges) {
            for (String end : List.of(edge.from(), edge.to())) {
                String zone = zoneOf.get(end);
                if (zone != null) {
                    links.merge(zone, 1, Integer::sum);
                }
            }
        }
        List<String> order = new ArrayList<>(members.keySet());
        order.sort((a, b) -> Integer.compare(links.getOrDefault(b, 0), links.getOrDefault(a, 0)));
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String zone : order) {
            ordered.put(zone, members.get(zone));
        }
        members = ordered;

        List<Zone> zones = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        double x = ORIGIN_X;
        double y = ORIGIN_Y;
        double shelfHeight = 0;
        double width = ORIGIN_X;
        for (Map.Entry<String, List<String>> zoneMembers : members.entrySet()) {
            int count = zoneMembers.getValue().size();
            int columns = columns(count);
            int rows = (count + columns - 1) / columns;
            double zoneWidth = zoneWidth(columns);
            double zoneHeight = ZONE_TITLE + ZONE_PADDING * 2 + rows * NODE_HEIGHT + (rows - 1) * NODE_GAP_Y;
            if (x > ORIGIN_X && x + zoneWidth > limit) {
                x = ORIGIN_X;
                y += shelfHeight + ZONE_GAP;
                shelfHeight = 0;
            }
            Zone zone = new Zone(zoneMembers.getKey(), x, y, zoneWidth, zoneHeight);
            zones.add(zone);
            for (int i = 0; i < count; i++) {
                String id = zoneMembers.getValue().get(i);
                double nodeX = x + ZONE_PADDING + (i % columns) * (NODE_WIDTH + NODE_GAP_X);
                double nodeY = y + ZONE_TITLE + ZONE_PADDING + (i / columns) * (NODE_HEIGHT + NODE_GAP_Y);
                boolean external = EXTERNAL_ZONE.equals(zone.key());
                nodes.add(new Node(id, zone.key(), nodeX, nodeY, external, external ? externalLabels.get(id) : null));
            }
            x += zoneWidth + ZONE_GAP;
            width = Math.max(width, x);
            shelfHeight = Math.max(shelfHeight, zoneHeight);
        }
        nodes.add(0, new Node(ME, null, ME_X, ORIGIN_Y + ZONE_TITLE + ZONE_PADDING, false, null));
        double height = y + shelfHeight + ORIGIN_Y;
        return new Result(zones, nodes, edges, Math.max(width, ORIGIN_X + NODE_WIDTH), Math.max(height, 200));
    }

    /** The id of the saved connection that is {@code entry}'s jump server: same host, preferably the same port. */
    static String savedJump(List<Entry> entries, Entry entry) {
        String host = entry.jumpHost().trim();
        String hostOnly = null;
        for (Entry candidate : entries) {
            if (candidate == entry || candidate.host() == null || !candidate.host().trim().equalsIgnoreCase(host)) {
                continue;
            }
            if (candidate.port() == entry.jumpPort()) {
                return candidate.id();
            }
            if (hostOnly == null) {
                hostOnly = candidate.id();
            }
        }
        return hostOnly;
    }

    static int columns(int count) {
        return Math.max(1, Math.min(3, (int) Math.ceil(Math.sqrt(count))));
    }

    private static double zoneWidth(int columns) {
        return ZONE_PADDING * 2 + columns * NODE_WIDTH + (columns - 1) * NODE_GAP_X;
    }

    private static String groupKey(String group) {
        return group != null ? group.trim() : "";
    }
}

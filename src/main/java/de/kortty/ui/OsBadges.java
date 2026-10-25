package de.kortty.ui;

import java.util.List;
import java.util.Optional;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * Operating-system badges a server can carry in the Connection Manager. The marks are stylised vector shapes
 * drawn for korTTY in the vendors' brand colours, not the vendors' own artwork. Every badge starts with a
 * rounded chip that fills the 24-unit box, so the mark reads on dark and light surfaces alike. Each layer is
 * SVG path data filled with the even-odd rule: a layer may cut holes with extra subpaths, while shapes that
 * overlap and must merge belong in separate layers of the same colour. Ids are stored in users' files and
 * never change.
 */
final class OsBadges {

    enum Family { WINDOWS, APPLE, LINUX, UNIX, BSD, MAINFRAME }

    /** One filled shape: SVG path data in a 24x24 box, filled with fill (#RRGGBB or #RRGGBBAA). */
    record Layer(String path, String fill) { }

    record Badge(String id, String name, Family family, List<Layer> layers) { }

    private static final String CHIP = "M5 0 H19 A5 5 0 0 1 24 5 V19 A5 5 0 0 1 19 24 H5 A5 5 0 0 1 0 19 V5 A5 5 0 0 1 5 0 Z";

    private static final List<Badge> BADGES = List.of(
            // Windows XP: the four-colour waving flag.
            badge("windows-xp", "Windows XP", Family.WINDOWS, chip("#FFFFFF"),
                    layer("M3.5 4.5 Q7.5 2.5 11.4 4.6 V10.4 Q7.5 8.3 3.5 10.6 Z", "#F25022"),
                    layer("M12.6 4.6 Q16.5 2.5 20.5 4.5 V10.6 Q16.5 8.3 12.6 10.4 Z", "#7FBA00"),
                    layer("M3.5 11.6 Q7.5 9.4 11.4 11.5 V19.5 Q7.5 17.4 3.5 19.6 Z", "#00A4EF"),
                    layer("M12.6 11.5 Q16.5 9.3 20.5 11.6 V19.6 Q16.5 17.4 12.6 19.5 Z", "#FFB900")),
            // Windows 10: a light-blue window seen in perspective, its panes sheared.
            badge("windows-10", "Windows 10", Family.WINDOWS, chip("#FFFFFF"),
                    layer("M3.5 5 L11.3 4.1 V11.4 L3.5 12.3 Z M12.7 3.9 L20.5 3 V10.3 L12.7 11.2 Z"
                            + " M3.5 13.7 L11.3 12.8 V20.1 L3.5 21 Z M12.7 12.6 L20.5 11.7 V19 L12.7 19.9 Z", "#29A8E0")),
            // Windows 11: four flat blue squares.
            badge("windows-11", "Windows 11", Family.WINDOWS, chip("#FFFFFF"),
                    layer("M3.2 3.2 H11.2 V11.2 H3.2 Z M12.8 3.2 H20.8 V11.2 H12.8 Z"
                            + " M3.2 12.8 H11.2 V20.8 H3.2 Z M12.8 12.8 H20.8 V20.8 H12.8 Z", "#0078D4")),
            // Windows Server: the Windows tile beside a three-bay server rack with status lights.
            badge("windows-server", "Windows Server", Family.WINDOWS, chip("#FFFFFF"),
                    layer("M2.8 2.8 H7.3 V7.3 H2.8 Z M7.7 2.8 H12.2 V7.3 H7.7 Z"
                            + " M2.8 7.7 H7.3 V12.2 H2.8 Z M7.7 7.7 H12.2 V12.2 H7.7 Z", "#0078D4"),
                    layer("M13.6 2.8 H21.2 V8.4 H13.6 Z M13.6 9.2 H21.2 V14.8 H13.6 Z M13.6 15.6 H21.2 V21.2 H13.6 Z",
                            "#334155"),
                    layer("M17.45 5.6 A0.95 0.95 0 1 0 19.35 5.6 A0.95 0.95 0 1 0 17.45 5.6 Z"
                            + " M17.45 12 A0.95 0.95 0 1 0 19.35 12 A0.95 0.95 0 1 0 17.45 12 Z"
                            + " M17.45 18.4 A0.95 0.95 0 1 0 19.35 18.4 A0.95 0.95 0 1 0 17.45 18.4 Z", "#22C55E")),
            // macOS: the bitten apple with its leaf.
            badge("macos", "macOS", Family.APPLE, chip("#FFFFFF"),
                    layer("M12 7.9 C13.1 7 14.5 6.6 15.9 6.7 C17.3 6.8 18.6 7.5 19.4 8.7 A3.9 3.9 0 0 0 20.5 15.7"
                            + " C19.9 17.5 18.9 19.3 17.6 20.5 C16.8 21.2 15.9 21.5 15 21.2 C14 20.9 13.1 20.6 12 20.6"
                            + " C10.9 20.6 10 20.9 9 21.2 C8.1 21.5 7.2 21.2 6.4 20.5 C4.6 18.9 3.6 16 3.6 13.3"
                            + " C3.6 9.7 5.8 6.8 8.6 6.7 C9.9 6.6 11 7.2 12 7.9 Z"
                            + " M12.2 6 C12.1 4.1 13.5 2.4 15.6 2.2 C15.8 4.2 14.3 5.9 12.2 6 Z", "#1D1D1F")),
            // Linux: Tux, the penguin, black with a white belly, yellow beak and feet.
            badge("linux", "Linux", Family.LINUX, chip("#FFFFFF"),
                    layer("M12 3 C8.6 3 7.6 5.9 7.6 8.8 C7.6 10.9 6.4 12.9 5.6 15.2 C4.6 18.1 6.4 21 12 21"
                            + " C17.6 21 19.4 18.1 18.4 15.2 C17.6 12.9 16.4 10.9 16.4 8.8 C16.4 5.9 15.4 3 12 3 Z",
                            "#111111"),
                    layer("M12 9.6 C10.2 9.6 9.6 12 9.6 14.2 C9.6 17.6 10.6 19.8 12 19.8 C13.4 19.8 14.4 17.6 14.4 14.2"
                            + " C14.4 12 13.8 9.6 12 9.6 Z", "#FFFFFF"),
                    layer("M8.9 6.9 A1.4 1.7 0 1 0 11.7 6.9 A1.4 1.7 0 1 0 8.9 6.9 Z"
                            + " M12.3 6.9 A1.4 1.7 0 1 0 15.1 6.9 A1.4 1.7 0 1 0 12.3 6.9 Z", "#FFFFFF"),
                    layer("M9.8 7.2 A0.7 0.7 0 1 0 11.2 7.2 A0.7 0.7 0 1 0 9.8 7.2 Z"
                            + " M12.8 7.2 A0.7 0.7 0 1 0 14.2 7.2 A0.7 0.7 0 1 0 12.8 7.2 Z", "#111111"),
                    layer("M10.2 9.4 H13.8 L12 11.5 Z M9.8 20.2 Q8 22.2 4.2 21.6 Q3.6 21.5 4.4 20.6 Q6.2 19.4 9.8 19.6 Z"
                            + " M14.2 20.2 Q16 22.2 19.8 21.6 Q20.4 21.5 19.6 20.6 Q17.8 19.4 14.2 19.6 Z", "#FFB300")),
            // Red Hat: the red fedora with its white band.
            badge("redhat", "Red Hat", Family.LINUX, chip("#FFFFFF"),
                    layer("M7.5 13.2 V8.4 Q7.5 4.4 10.4 4.4 H11.3 Q12 6.2 12.7 4.4 H13.6 Q16.5 4.4 16.5 8.4 V13.2 Z",
                            "#EE0000"),
                    layer("M2.4 14.2 Q2.4 12.2 12 12.2 Q21.6 12.2 21.6 14.2 Q21.6 16.3 12 16.5 Q2.4 16.3 2.4 14.2 Z",
                            "#EE0000"),
                    layer("M7.5 10.2 H16.5 V11.3 H7.5 Z", "#FFFFFF")),
            // Rocky Linux: a green disc with a mountain notch cut out of it.
            badge("rocky", "Rocky Linux", Family.LINUX, chip("#FFFFFF"),
                    layer("M3 12 A9 9 0 1 0 21 12 A9 9 0 1 0 3 12 Z M6.6 16.6 L10.4 10.9 L12.6 13.9 L14.1 11.8 L17.4 16.6 Z",
                            "#10B981")),
            // AlmaLinux: a gold A on a deep blue disc.
            badge("almalinux", "AlmaLinux", Family.LINUX, chip("#FFFFFF"),
                    layer("M3 12 A9 9 0 1 0 21 12 A9 9 0 1 0 3 12 Z", "#0F4266"),
                    layer("M12 5.6 L17.2 17.8 H14.9 L12 10.6 L9.1 17.8 H6.8 Z", "#FDBA12")),
            // CentOS: the four-colour pinwheel ring.
            badge("centos", "CentOS", Family.LINUX, chip("#FFFFFF"),
                    layer("M18.79 6.1 A9 9 0 0 1 18.79 17.9 L15.25 14.82 A4.3 4.3 0 0 0 15.25 9.18 Z", "#932279"),
                    layer("M17.9 18.79 A9 9 0 0 1 6.1 18.79 L9.18 15.25 A4.3 4.3 0 0 0 14.82 15.25 Z", "#EFA624"),
                    layer("M5.21 17.9 A9 9 0 0 1 5.21 6.1 L8.75 9.18 A4.3 4.3 0 0 0 8.75 14.82 Z", "#9CCD2A"),
                    layer("M6.1 5.21 A9 9 0 0 1 17.9 5.21 L14.82 8.75 A4.3 4.3 0 0 0 9.18 8.75 Z", "#262577")),
            // Fedora: the white lowercase f in its dark navy bubble.
            badge("fedora", "Fedora", Family.LINUX, chip("#FFFFFF"),
                    layer("M14.33 20.69 A9 9 0 1 1 19.79 16.5 L12 12 Z", "#294172"),
                    layer("M10.2 19.2 V11.6 H7.4 V9.2 H10.2 V7.6 Q10.2 4.6 13.4 4.6 H16.6 V7 H14 Q12.6 7 12.6 8.4 V9.2"
                            + " H16.4 V11.6 H12.6 V19.2 Z", "#FFFFFF")),
            // Debian: the red open swirl.
            badge("debian", "Debian", Family.LINUX, chip("#FFFFFF"),
                    layer("M16.88 3.56 L18.18 4.64 L19.26 5.91 L20.08 7.33 L20.64 8.86 L20.92 10.43 L20.92 12 L20.65 13.52"
                            + " L20.12 14.96 L19.36 16.25 L18.41 17.38 L17.29 18.3 L16.04 19 L14.72 19.47 L13.36 19.69 L12 19.67"
                            + " L10.69 19.42 L9.47 18.95 L8.37 18.28 L7.43 17.45 L6.66 16.48 L6.08 15.42 L5.71 14.29 L5.54 13.14"
                            + " L5.58 12 L5.81 10.91 L6.23 9.9 L6.8 9 L7.51 8.23 L8.32 7.61 L9.21 7.16 L10.14 6.88 L11.08 6.77"
                            + " L12 6.83 L12.87 7.04 L13.67 7.4 L14.38 7.88 L14.97 8.46 L15.43 9.12 L15.76 9.83 L15.95 10.56"
                            + " L14.07 11.25 L14 10.84 L13.86 10.44 L13.63 10.06 L13.32 9.71 L12.94 9.41 L12.5 9.18 L12 9.02"
                            + " L11.46 8.96 L10.91 8.99 L10.34 9.13 L9.8 9.38 L9.29 9.73 L8.84 10.18 L8.47 10.71 L8.19 11.33"
                            + " L8.02 12 L7.97 12.71 L8.05 13.44 L8.26 14.16 L8.61 14.84 L9.08 15.47 L9.68 16.02 L10.37 16.47"
                            + " L11.15 16.8 L12 16.98 L12.88 17.02 L13.78 16.89 L14.66 16.6 L15.49 16.16 L16.24 15.56 L16.89 14.82"
                            + " L17.41 13.97 L17.78 13.02 L17.98 12 L18 10.94 L17.83 9.88 L17.47 8.84 L16.92 7.87 L16.2 6.99"
                            + " L15.33 6.24 Z", "#D70A53")),
            // Ubuntu: the orange circle of friends, three white figures joined by a ring.
            badge("ubuntu", "Ubuntu", Family.LINUX, chip("#E95420"),
                    layer("M14.88 4.86 A7.7 7.7 0 0 1 19.63 13.07 L17.45 12.77 A5.5 5.5 0 0 0 14.06 6.9 Z"
                            + " M16.74 18.07 A7.7 7.7 0 0 1 7.26 18.07 L8.61 16.33 A5.5 5.5 0 0 0 15.39 16.33 Z"
                            + " M4.37 13.07 A7.7 7.7 0 0 1 9.12 4.86 L9.94 6.9 A5.5 5.5 0 0 0 6.55 12.77 Z", "#FFFFFF"),
                    layer("M9.6 5.4 A2.4 2.4 0 1 0 14.4 5.4 A2.4 2.4 0 1 0 9.6 5.4 Z"
                            + " M15.32 15.3 A2.4 2.4 0 1 0 20.12 15.3 A2.4 2.4 0 1 0 15.32 15.3 Z"
                            + " M3.88 15.3 A2.4 2.4 0 1 0 8.68 15.3 A2.4 2.4 0 1 0 3.88 15.3 Z", "#FFFFFF")),
            // Linux Mint: the white lm on the mint-green chip, the l bending into the line the m stands on.
            badge("linux-mint", "Linux Mint", Family.LINUX, chip("#87CF3E"),
                    layer("M4.5 4.5 H6.5 V16 A1.5 1.5 0 0 0 8 17.5 H8.7 V11.7 A3.2 3.2 0 0 1 14.1 9.38"
                            + " A3.2 3.2 0 0 1 19.5 11.7 V17.5 A2 2 0 0 1 17.5 19.5 H8 A3.5 3.5 0 0 1 4.5 16 Z"
                            + " M10.7 17.5 V11.7 A1.2 1.2 0 0 1 13.1 11.7 V17.5 Z"
                            + " M15.1 17.5 V11.7 A1.2 1.2 0 0 1 17.5 11.7 V17.5 Z", "#FFFFFF")),
            // Arch Linux: the blue arch-shaped A.
            badge("arch", "Arch Linux", Family.LINUX, chip("#FFFFFF"),
                    layer("M12 2.8 L21.2 21.2 H16.6 L12 11.8 L7.4 21.2 H2.8 Z", "#1793D1")),
            // SUSE: the green Geeko chameleon with its eye.
            badge("suse", "SUSE", Family.LINUX, chip("#FFFFFF"),
                    layer("M3 10 Q4.2 6.6 8 5.6 Q11.8 5 12.2 8.4 Q12.4 10.5 14.4 11.8 Q17.8 13.6 19.6 11 Q21.4 8 19.2 6.8"
                            + " Q17.2 5.8 17.6 9.2 Q18.2 12.8 14.6 14.6 Q12.8 15.6 12.8 17.8 Q12.6 19.8 10.2 19.4 Q9.2 18.8 9.6 16.4"
                            + " Q9.8 14.4 6.2 13.4 Q3.6 12.6 3 10 Z", "#73BA25"),
                    layer("M6.3 8.4 A1.3 1.3 0 1 0 8.9 8.4 A1.3 1.3 0 1 0 6.3 8.4 Z", "#FFFFFF"),
                    layer("M6.85 8.4 A0.55 0.55 0 1 0 7.95 8.4 A0.55 0.55 0 1 0 6.85 8.4 Z", "#111111")),
            // ChromeOS: the Chrome wheel, red, yellow and green around the blue centre.
            badge("chromeos", "ChromeOS", Family.LINUX, chip("#FFFFFF"),
                    layer("M4.38 7.21 A9 9 0 0 1 19.96 7.8 L12 7.8 A4.2 4.2 0 0 0 8.36 14.1 Z", "#EA4335"),
                    layer("M19.96 7.8 A9 9 0 0 1 11.66 20.99 L15.64 14.1 A4.2 4.2 0 0 0 12 7.8 Z", "#FBBC04"),
                    layer("M11.66 20.99 A9 9 0 0 1 4.38 7.21 L8.36 14.1 A4.2 4.2 0 0 0 15.64 14.1 Z", "#34A853"),
                    layer("M8.7 12 A3.3 3.3 0 1 0 15.3 12 A3.3 3.3 0 1 0 8.7 12 Z", "#1A73E8")),
            // Oracle Solaris: the orange sun with its rays.
            badge("solaris", "Oracle Solaris", Family.UNIX, chip("#FFFFFF"),
                    layer("M7.4 12 A4.6 4.6 0 1 0 16.6 12 A4.6 4.6 0 1 0 7.4 12 Z", "#F26E21"),
                    layer("M13.45 5.7 L12 2.8 L10.55 5.7 Z M17.48 8.57 L18.51 5.49 L15.43 6.52 Z"
                            + " M18.3 13.45 L21.2 12 L18.3 10.55 Z M15.43 17.48 L18.51 18.51 L17.48 15.43 Z"
                            + " M10.55 18.3 L12 21.2 L13.45 18.3 Z M6.52 15.43 L5.49 18.51 L8.57 17.48 Z"
                            + " M5.7 10.55 L2.8 12 L5.7 13.45 Z M8.57 6.52 L5.49 5.49 L6.52 8.57 Z", "#F26E21")),
            // IBM AIX: the blue blocky AIX letters.
            badge("aix", "IBM AIX", Family.UNIX, chip("#FFFFFF"),
                    layer("M2.2 16 L5.4 8 H7.4 L10.6 16 H8.6 L7.9 14.2 H4.9 L4.2 16 Z M5.6 12.8 H7.2 L6.4 9.6 Z"
                            + " M12.2 8 H14.4 V16 H12.2 Z"
                            + " M15.6 8 H17.9 L18.7 10.2 L19.5 8 H21.8 L19.2 12 L21.8 16 H19.5 L18.7 13.8 L17.9 16 H15.6 L18.2 12 Z",
                            "#1F70C1")),
            // HP-UX: the white lowercase hp on the HP blue disc.
            badge("hp-ux", "HP-UX", Family.UNIX, chip("#FFFFFF"),
                    layer("M3 12 A9 9 0 1 0 21 12 A9 9 0 1 0 3 12 Z", "#0096D6"),
                    layer("M4.8 6.8 H6.8 V11 Q8 9.4 9.9 9.4 Q12.1 9.4 12.1 11.8 V16.8 H10.1 V12.1 Q10.1 11.2 9.2 11.2"
                            + " Q6.8 11.2 6.8 13.4 V16.8 H4.8 Z"
                            + " M13.2 9.6 H16.6 Q20.2 9.6 20.2 13.2 Q20.2 16.8 16.6 16.8 H15.2 V20.2 H13.2 Z"
                            + " M15.2 11.6 V14.8 H16.6 Q18.2 14.8 18.2 13.2 Q18.2 11.6 16.6 11.6 Z", "#FFFFFF")),
            // DEC: three maroon letter boxes, each with its white letter (D, E, C).
            badge("dec", "DEC", Family.UNIX, chip("#FFFFFF"),
                    layer("M2.4 7 H8.6 V17 H2.4 Z M4.4 9.2 H5.6 Q7 9.2 7 12 Q7 14.8 5.6 14.8 H4.4 Z"
                            + " M9.2 7 H14.8 V17 H9.2 Z M10.2 9.2 H13.6 V10.5 H11.6 V11.6 H13.2 V12.9 H11.6 V13.5 H13.6 V14.8 H10.2 Z"
                            + " M15.2 7 H21.6 V17 H15.2 Z M20.47 10.26 A2.7 2.7 0 1 0 20.47 13.74 L19.47 12.9 A1.4 1.4 0 1 1 19.47 11.1 Z",
                            "#800000")),
            // FreeBSD: the red beastie sphere with its two horns.
            badge("freebsd", "FreeBSD", Family.BSD, chip("#FFFFFF"),
                    layer("M4.5 13.5 A7.5 7.5 0 1 0 19.5 13.5 A7.5 7.5 0 1 0 4.5 13.5 Z", "#AB2B28"),
                    layer("M6.6 9.6 C4.2 8.2 3.4 5.4 3.9 2.9 C6.3 4.1 8.6 6.2 9.6 8.4 Z"
                            + " M17.4 9.6 C19.8 8.2 20.6 5.4 20.1 2.9 C17.7 4.1 15.4 6.2 14.4 8.4 Z", "#AB2B28")),
            // OpenBSD: the yellow pufferfish, Puffy.
            badge("openbsd", "OpenBSD", Family.BSD, chip("#FFFFFF"),
                    layer("M4.2 12 C4.2 8.2 7.4 5.6 12 5.6 C16.6 5.6 19.4 8.4 19.4 12 C19.4 15.6 16.6 18.4 12 18.4"
                            + " C7.4 18.4 4.2 15.8 4.2 12 Z M18.6 12 L22.2 8.4 V15.6 Z M10.2 6.2 L12.2 2.8 L13.8 6.4 Z"
                            + " M10.4 17.6 L12 20.8 L13.6 17.6 Z", "#F4CB1F"),
                    layer("M7.2 10.2 A1.8 1.8 0 1 0 10.8 10.2 A1.8 1.8 0 1 0 7.2 10.2 Z", "#FFFFFF"),
                    layer("M7.9 10.2 A1 1 0 1 0 9.9 10.2 A1 1 0 1 0 7.9 10.2 Z", "#1F1A0A")),
            // IBM z/OS: white z/OS lettering on the IBM blue chip.
            badge("zos", "IBM z/OS", Family.MAINFRAME, chip("#0F62FE"),
                    layer("M3.1 10.6 H7 V11.9 L4.85 14.3 H7 V15.6 H3.1 V14.3 L5.25 11.9 H3.1 Z"
                            + " M7.5 15.6 L9.25 8.4 H10.6 L8.85 15.6 Z"
                            + " M13.2 8.4 H13.7 A2.2 2.2 0 0 1 15.9 10.6 V13.4 A2.2 2.2 0 0 1 13.7 15.6 H13.2"
                            + " A2.2 2.2 0 0 1 11 13.4 V10.6 A2.2 2.2 0 0 1 13.2 8.4 Z"
                            + " M13.2 9.8 H13.7 A0.8 0.8 0 0 1 14.5 10.6 V13.4 A0.8 0.8 0 0 1 13.7 14.2 H13.2"
                            + " A0.8 0.8 0 0 1 12.4 13.4 V10.6 A0.8 0.8 0 0 1 13.2 9.8 Z"
                            + " M18.1 8.4 H20.9 V9.8 H17.9 V11.3 H19.3 A1.6 1.6 0 0 1 20.9 12.9 V14 A1.6 1.6 0 0 1 19.3 15.6"
                            + " H16.5 V14.2 H19.5 V12.7 H18.1 A1.6 1.6 0 0 1 16.5 11.1 V10 A1.6 1.6 0 0 1 18.1 8.4 Z",
                            "#FFFFFF")));

    private OsBadges() { }

    private static Badge badge(String id, String name, Family family, Layer... layers) {
        return new Badge(id, name, family, List.of(layers));
    }

    private static Layer chip(String fill) {
        return new Layer(CHIP, fill);
    }

    private static Layer layer(String path, String fill) {
        return new Layer(path, fill);
    }

    /** Every built-in badge in listing order: Windows, Apple, Linux, Unix, BSD, mainframe. */
    static List<Badge> builtIn() {
        return BADGES;
    }

    /** The built-in badge with this id, if one exists. */
    static Optional<Badge> builtIn(String id) {
        return BADGES.stream().filter(badge -> badge.id().equals(id)).findFirst();
    }

    /**
     * The badge in a {@code size} x {@code size} box: the 24-unit art is scaled to fit, the box has fixed
     * bounds and ignores the mouse. FX thread only.
     */
    static Node node(Badge badge, double size) {
        Group art = new Group();
        for (Layer layer : badge.layers()) {
            SVGPath shape = new SVGPath();
            shape.setContent(layer.path());
            shape.setFill(Color.web(layer.fill()));
            shape.setFillRule(FillRule.EVEN_ODD);
            shape.setStroke(null);
            art.getChildren().add(shape);
        }
        // Invisible fixed bounds keep the badge optically aligned without stretching its paths.
        Rectangle bounds = new Rectangle(24, 24);
        bounds.setFill(Color.TRANSPARENT);
        Group drawing = new Group(bounds, art);
        drawing.getTransforms().add(new Scale(size / 24, size / 24, 0, 0));
        // The wrapper exposes the transformed bounds to StackPane's centering calculation.
        StackPane canvas = new StackPane(new Group(drawing));
        canvas.setMinSize(size, size);
        canvas.setPrefSize(size, size);
        canvas.setMaxSize(size, size);
        canvas.setMouseTransparent(true);
        return canvas;
    }
}

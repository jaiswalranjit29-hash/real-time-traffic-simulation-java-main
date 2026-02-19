// ===================== MapVisualisation.java =====================
package org.example;

// Import files: Swing GUI core package, used to build map panel, event listeners, and graphics drawing.
import javax.swing.*;
// Import files: XML parsing core packages, used to parse SUMO .net.xml road network configuration.
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

// Import files: AWT graphics core package, used for 2D rendering, coordinate transforms, and mouse handling.
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
// Import files: File IO package, used to read .net.xml/.sumocfg config files.
import java.io.File;
// Import files: Java collections, used to store network geometry, TLS positions, and vehicle data
// (thread-safe collections are important here).
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// Import files: W3C DOM XML parsing package for reading XML nodes and attributes.
import org.w3c.dom.*;

/**
 * Class: MapVisualisation
 * Access modifier: final -> cannot be inherited.
 * Design intent: this is the visualization core; no extension needed; prevents inheritance-related complexity.
 *
 * Purpose: Core map visualization class for SUMO traffic simulation.
 *
 * Responsibilities:
 * 1) Parse SUMO .net.xml network file to extract map bounds, road geometry, and traffic light (TLS) positions.
 * 2) Provide a MapPanel with zoom/pan/rotate interactions and thread-safe vehicle updates.
 * 3) Convert world coordinates (meters) to screen coordinates (pixels), draw roads/TLS/vehicles.
 * 4) Provide a TrendChartPanel that displays avg wait time, throughput, and congestion in real time.
 * 5) Provide helper utilities such as rendering components to images.
 */

/**
 * Module Introduction:
 * (1) Role: Core visualization module for SUMO traffic simulation system.
 * (2) Primary functions:
 *  - Parse SUMO .net.xml to extract map boundaries, roads, and traffic signal locations.
 *  - Control the user interaction panel (zoom/pan/rotate).
 *  - Rely on LiveConnectionSumo for real-time data and integrate with GUI interactions.
 */
public final class MapVisualisation {

    // Private constructor: prevent instantiation (utility class design).
    private MapVisualisation() {}

    // ===================== Core interface definitions (Interfaces) =====================
    // Filter contract (GUI filter will implement this)
    /**
     * Interface: Filter
     * Purpose: Defines a contract for vehicle filtering rules (supports Open/Closed principle).
     * Scenario: GUI checkbox selection (show cars/trucks/buses) uses this interface to decide what to draw.
     * Typical usage: GUI provides an implementation and passes it into MapPanel.
     */
    /*
     * Filter Interface: Control vehicle visibility by type and speed.
     */
    public interface Filter {
        /**
         * Filter method: decide whether a vehicle of given type/speed is allowed to be drawn.
         * @param type vehicle type (Main.TYPE_CAR/TYPE_TRUCK/TYPE_BUS)
         * @param speedMps vehicle speed (m/s)
         * @return true = draw, false = skip
         */
        boolean allows(String type, double speedMps);
    }

    // ===================== Internal data model (static inner classes) =====================
    /**
     * Static inner class: Bounds
     * Purpose: Stores map bounds (min/max X/Y) and provides a sanity check.
     * Design: Immutable (final fields) -> thread-safe and safe from mutation.
     */
    /*
     * Bounds: stores map boundaries and can check whether they are valid.
     */
    public static class Bounds {
        final double minX, minY, maxX, maxY; // Map boundary coordinates in world coordinates (meters)
        Bounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
        }
        // Check whether bounds are valid (max > min)
        boolean sane() { return maxX > minX && maxY > minY; }
    }

    /**
     * Static inner class: RoadGeom
     * Purpose: Encapsulates a single lane geometry; cache it to avoid re-parsing XML repeatedly.
     * Fields:
     * - edgeId: road edge ID
     * - laneIndex: lane index
     * - internal: whether internal edge (junction connector)
     * - laneWidth: lane width (meters)
     * - xy: lane shape coordinates (x1,y1,x2,y2,...)
     *
     * Key flow:
     * initBoundsFromFiles() -> readConvBoundaryFromNet() -> loadRoadGeometriesFromNet() -> loadTlsPositionsFromNet()
     */
    /*
     * RoadGeom: stores road info including road ID, lane index, width, and coordinates.
     */
    public static class RoadGeom {
        final String edgeId;
        final int laneIndex;
        final boolean internal;
        final float laneWidth;
        final double[] xy;
        RoadGeom(String edgeId, int laneIndex, boolean internal, float laneWidth, double[] xy) {
            this.edgeId = edgeId;
            this.laneIndex = laneIndex;
            this.internal = internal;
            this.laneWidth = laneWidth;
            this.xy = xy;
        }
    }

    // ===================== Global static caches (thread-safety) =====================
    // Map bounds cache (volatile ensures visibility across threads).
    private static volatile Bounds NET_BOUNDS = null;
    // Fallback bounds used if no valid bounds can be parsed.
    private static volatile Bounds FALLBACK_BOUNDS = new Bounds(-100, -100, 100, 100);

    // Drawing flags: internal edges / all lanes / internal connectors
    private static final boolean DRAW_INTERNAL_EDGES = true;
    private static final boolean DRAW_ALL_LANES = true;
    private static final boolean DRAW_INTERNAL_CONNECTORS = DRAW_INTERNAL_EDGES;

    // Road drawing style constants: thickness multiplier, minimum pixel width, lane markings
    private static final double ROAD_THICKNESS_MULT = 1.25;
    private static final double ROAD_MIN_PX = 6.0;
    private static final boolean DRAW_LANE_MARKINGS = true;

    // Global cache: road geometries (volatile for cross-thread visibility).
    private static volatile java.util.List<RoadGeom> ROAD_GEOMS = java.util.Collections.emptyList();
    // Global cache: TLS positions (key=TLS ID, value=world coordinates).
    private static volatile Map<String, Point2D.Double> TLS_POSITIONS = java.util.Collections.emptyMap();
    // Global cache: TLS labels (key=TLS ID, value=simplified label like t1/t2).
    private static volatile Map<String, String> TLS_LABELS = java.util.Collections.emptyMap();

    // ===================== Public getters =====================
    // Get active bounds (prefer parsed bounds; otherwise fallback).
    public static Bounds getActiveBounds() {
        Bounds b = NET_BOUNDS;
        return (b != null && b.sane()) ? b : FALLBACK_BOUNDS;
    }

    // Get road geometry list (visibility guaranteed by volatile).
    public static List<RoadGeom> getRoadGeoms() { return ROAD_GEOMS; }

    // Get TLS positions.
    public static Map<String, Point2D.Double> getTlsPositions() { return TLS_POSITIONS; }

    // Get TLS labels.
    public static Map<String, String> getTlsLabels() { return TLS_LABELS; }

    // ===================== Core initialization =====================
    /**
     * Initialize map bounds and geometry data.
     * Flow:
     * 1) Read net.xml path from sumocfg.
     * 2) Parse net.xml convBoundary to get bounds.
     * 3) Load road geometries and TLS positions, build simplified TLS labels.
     *
     * Error handling: catch all exceptions, log them, and keep fallback bounds to avoid crashing.
     */
    /*
     * English: Entry method for map data initialization; parses SUMO configuration files and coordinates
     * the loading of boundary, road, and traffic light data.
     */
    public static void initBoundsFromFiles() {
        try {
            // Step 1: read net.xml path from sumocfg config file
            String netPath = readNetFileFromSumocfg(Main.SUMOCFG_PATH);
            if (netPath == null || netPath.isBlank()) netPath = "final.net.xml";
            File netFile = resolveRelativeToSumocfg(netPath);

            // Step 2: parse convBoundary from net.xml to get map bounds
            Bounds b = readConvBoundaryFromNet(netFile);
            if (b != null && b.sane()) {
                // Add 3% padding so elements are not drawn right at the border.
                NET_BOUNDS = addPadding(b, 0.03);
                Logging.LOG.info("Map bounds loaded: " + netFile.getPath());

                // Step 3: load road geometry, TLS positions, and TLS labels
                ROAD_GEOMS = loadRoadGeometriesFromNet(netFile);
                TLS_POSITIONS = loadTlsPositionsFromNet(netFile);
                TLS_LABELS = buildTlsLabels(TLS_POSITIONS.keySet());
            } else {
                Logging.LOG.warning("convBoundary not found; using fallback bounds.");
            }
        } catch (Exception e) {
            // Catch all exceptions; log with stack trace; fallback remains active.
            Logging.LOG.log(java.util.logging.Level.WARNING, "Bounds init failed; using fallback.", e);
        }
    }

    // Add padding around bounds to avoid drawing right on the edge.
    private static Bounds addPadding(Bounds b, double frac) {
        double dx = (b.maxX - b.minX) * frac;
        double dy = (b.maxY - b.minY) * frac;
        return new Bounds(b.minX - dx, b.minY - dy, b.maxX + dx, b.maxY + dy);
    }

    /**
     * Parse net.xml "location" node and extract convBoundary.
     * XML security hardening: disable external DTD loading and entity expansion.
     */
    /*
     * Extracts map boundary coordinates from .net.xml and checks validity.
     */
    private static Bounds readConvBoundaryFromNet(File netFile) {
        try {
            if (netFile == null || !netFile.exists()) return null;

            // XML parser factory configuration (security hardening)
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(netFile);
            doc.getDocumentElement().normalize();

            // Read convBoundary attribute from the "location" node
            NodeList locs = doc.getElementsByTagName("location");
            if (locs.getLength() == 0) return null;

            Element loc = (Element) locs.item(0);
            String cb = loc.getAttribute("convBoundary");
            if (cb == null || cb.isBlank()) return null;

            // Parse coordinate string (minX,minY,maxX,maxY)
            String[] p = cb.split(",");
            if (p.length != 4) return null;

            double minX = Double.parseDouble(p[0].trim());
            double minY = Double.parseDouble(p[1].trim());
            double maxX = Double.parseDouble(p[2].trim());
            double maxY = Double.parseDouble(p[3].trim());
            return new Bounds(minX, minY, maxX, maxY);
        } catch (Exception ex) {
            // If XML parsing fails, log and return null
            Logging.LOG.log(java.util.logging.Level.WARNING, "Failed reading convBoundary from net.xml", ex);
            return null;
        }
    }

    /**
     * Parse .net.xml file to extract lane geometry (shapes).
     * SUMO provides coordinates, but drawing needs conversion from
     * World Coordinates (meters) to Screen Coordinates (pixels).
     */
    /*
     * Extract lane information (ID, width, coordinates) from .net.xml and store it into RoadGeom objects
     * to avoid repeated parsing.
     */
    private static java.util.List<RoadGeom> loadRoadGeometriesFromNet(File netFile) {
        java.util.List<RoadGeom> out = new java.util.ArrayList<>();
        try {
            if (netFile == null || !netFile.exists()) return out;

            // XML parsing security configuration
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setExpandEntityReferences(false);

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(netFile);
            doc.getDocumentElement().normalize();

            // Iterate all "edge" nodes
            NodeList edges = doc.getElementsByTagName("edge");
            for (int i = 0; i < edges.getLength(); i++) {
                Node n = edges.item(i);
                if (!(n instanceof Element)) continue;
                Element edge = (Element) n;

                String id = edge.getAttribute("id");
                String fn = edge.getAttribute("function");
                boolean internal = (id != null && id.startsWith(":")) || "internal".equalsIgnoreCase(fn);
                if (internal && !DRAW_INTERNAL_EDGES) continue;

                // Iterate all "lane" nodes under the edge
                NodeList lanes = edge.getElementsByTagName("lane");
                for (int j = 0; j < lanes.getLength(); j++) {
                    Node ln = lanes.item(j);
                    if (!(ln instanceof Element)) continue;
                    Element lane = (Element) ln;

                    // If DRAW_ALL_LANES is false, only draw lane index 0
                    if (!DRAW_ALL_LANES) {
                        String idxStr = lane.getAttribute("index");
                        if (idxStr != null && !idxStr.isBlank() && !"0".equals(idxStr.trim())) continue;
                    }

                    // Read lane shape coordinate string
                    String shape = lane.getAttribute("shape");
                    if (shape == null || shape.isBlank()) continue;

                    // Read lane width (default 3.2m)
                    float w = 3.2f;
                    try {
                        String wStr = lane.getAttribute("width");
                        if (wStr != null && !wStr.isBlank()) w = Float.parseFloat(wStr.trim());
                    } catch (Exception ignore) {
                        // Ignore parsing errors and keep default width
                    }

                    // Parse shape to double array
                    double[] xy = parseShape(shape);

                    // Read lane index (default 0)
                    int laneIndex = 0;
                    try {
                        String idxStr2 = lane.getAttribute("index");
                        if (idxStr2 != null && !idxStr2.isBlank()) laneIndex = Integer.parseInt(idxStr2.trim());
                    } catch (Exception ignore) {
                        // Ignore parsing errors and keep default index
                    }

                    // Require at least 2 points (4 numbers)
                    if (xy.length >= 4) out.add(new RoadGeom(id, laneIndex, internal, w, xy));
                }
            }

            Logging.LOG.info("Road geometry loaded: " + out.size() + " lane-shapes from " + netFile.getPath());
        } catch (Exception e) {
            // Continue without roads if parsing fails.
            Logging.LOG.log(java.util.logging.Level.WARNING, "Road geometry load failed; continuing without roads.", e);
        }

        // Storing RoadGeom allows efficient repainting in paintComponent without re-parsing.
        return out;
    }

    /**
     * Load traffic light (TLS) positions from net.xml.
     * Logic:
     * 1) Read junction coordinates.
     * 2) Build edge -> to-junction mapping.
     * 3) Associate TLS IDs to junctions via connection/tlLogic nodes.
     * 4) Map TLS ID to junction position.
     */
    /*
     * Obtain traffic light positions and map them to corresponding junction coordinates for map display.
     */
    private static Map<String, Point2D.Double> loadTlsPositionsFromNet(File netFile) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(netFile);
            doc.getDocumentElement().normalize();

            // Step 1: read all junction coordinates
            Map<String, Point2D.Double> junctionPos = new HashMap<>();
            NodeList junctions = doc.getElementsByTagName("junction");
            for (int i = 0; i < junctions.getLength(); i++) {
                Node n = junctions.item(i);
                if (!(n instanceof Element)) continue;
                Element j = (Element) n;
                String id = j.getAttribute("id");
                String xs = j.getAttribute("x");
                String ys = j.getAttribute("y");
                if (id == null || id.isBlank() || xs == null || ys == null) continue;
                try {
                    double x = Double.parseDouble(xs.trim());
                    double y = Double.parseDouble(ys.trim());
                    junctionPos.put(id.trim(), new Point2D.Double(x, y));
                } catch (Exception ignore) {
                    // Skip invalid coordinates
                }
            }

            // Step 2: build edge -> "to junction" mapping
            Map<String, String> edgeToNode = new HashMap<>();
            NodeList edges = doc.getElementsByTagName("edge");
            for (int i = 0; i < edges.getLength(); i++) {
                Node n = edges.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String id = e.getAttribute("id");
                if (id == null || id.isBlank()) continue;

                // Skip internal edges
                if (id.startsWith(":")) continue;
                String func = e.getAttribute("function");
                if (func != null && func.equalsIgnoreCase("internal")) continue;

                String to = e.getAttribute("to");
                if (to != null && !to.isBlank()) edgeToNode.put(id.trim(), to.trim());
            }

            // Step 3: associate TLS -> junction via connection nodes
            Map<String, String> tlsToJunction = new LinkedHashMap<>();
            NodeList conns = doc.getElementsByTagName("connection");
            for (int i = 0; i < conns.getLength(); i++) {
                Node n = conns.item(i);
                if (!(n instanceof Element)) continue;
                Element c = (Element) n;
                String tl = c.getAttribute("tl");
                if (tl == null || tl.isBlank()) continue;
                tl = tl.trim();

                String fromEdge = c.getAttribute("from");
                if (fromEdge != null && !fromEdge.isBlank()) {
                    String jId = edgeToNode.get(fromEdge.trim());
                    if (jId != null && !jId.isBlank() && !tlsToJunction.containsKey(tl)) {
                        tlsToJunction.put(tl, jId);
                    }
                }
            }

            // Step 4: also include tlLogic IDs (in case connection mapping is incomplete)
            NodeList tlLogics = doc.getElementsByTagName("tlLogic");
            for (int i = 0; i < tlLogics.getLength(); i++) {
                Node n = tlLogics.item(i);
                if (!(n instanceof Element)) continue;
                Element tl = (Element) n;
                String id = tl.getAttribute("id");
                if (id != null && !id.isBlank() && !tlsToJunction.containsKey(id.trim())) {
                    tlsToJunction.put(id.trim(), id.trim());
                }
            }

            // Step 5: map TLS IDs to coordinates
            Map<String, Point2D.Double> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : tlsToJunction.entrySet()) {
                String tlsId = e.getKey();
                String jId = e.getValue();
                Point2D.Double p = junctionPos.get(jId);
                if (p == null) p = junctionPos.get(tlsId);
                if (p != null) out.put(tlsId, p);
            }

            return out;
        } catch (Exception ex) {
            // On parsing errors, log and return empty map.
            Logging.LOG.log(java.util.logging.Level.WARNING,
                    "Failed to load TLS positions from net.xml: " + netFile.getPath(), ex);
            return java.util.Collections.emptyMap();
        }
    }

    // Build simplified TLS labels (t1/t2/t3...) to improve UI readability.
    private static Map<String, String> buildTlsLabels(Iterable<String> tlsIds) {
        ArrayList<String> ids = new ArrayList<>();
        for (String id : tlsIds) if (id != null && !id.isBlank()) ids.add(id.trim());
        ids.sort(String::compareTo); // Sort IDs so labels remain stable
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) out.put(ids.get(i), "t" + (i + 1));
        return out;
    }

    // Parse SUMO shape string (x1,y1 x2,y2 x3,y3...) into a double array.
    private static double[] parseShape(String shape) {
        String[] pts = shape.trim().split("\\s+");
        java.util.List<Double> coords = new java.util.ArrayList<>(pts.length * 2);
        for (String pt : pts) {
            String[] xy = pt.split(",");
            if (xy.length != 2) continue;
            try {
                coords.add(Double.parseDouble(xy[0]));
                coords.add(Double.parseDouble(xy[1]));
            } catch (Exception ignore) {
                // Skip invalid coordinate pair
            }
        }
        double[] arr = new double[coords.size()];
        for (int i = 0; i < coords.size(); i++) arr[i] = coords.get(i);
        return arr;
    }

    // ===================== SUMOCFG parsing =====================
    // Read net-file "value" attribute (network file path) from sumocfg.
    private static String readNetFileFromSumocfg(String sumocfgPath) {
        try {
            File f = new File(sumocfgPath);
            if (!f.exists()) return null;

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(f);
            doc.getDocumentElement().normalize();

            NodeList list = doc.getElementsByTagName("net-file");
            if (list.getLength() > 0) {
                Element e = (Element) list.item(0);
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (Exception ex) {
            // If parsing fails, log and return null
            Logging.LOG.log(java.util.logging.Level.WARNING, "Failed reading net-file from sumocfg", ex);
        }
        return null;
    }

    // Resolve relative paths: prefer the directory containing sumocfg; otherwise current working directory.
    private static File resolveRelativeToSumocfg(String pathMaybeRelative) {
        File f = new File(pathMaybeRelative);
        if (f.exists()) return f;

        File cfg = new File(Main.SUMOCFG_PATH);
        File baseDir = cfg.getParentFile();
        if (baseDir == null) baseDir = new File(".");
        File alt = new File(baseDir, pathMaybeRelative);
        if (alt.exists()) return alt;

        return f;
    }

    // ===================== Core inner class: MapPanel (interactive map panel) =====================
    /**
     * Static inner class: MapPanel
     * Inheritance: extends JPanel (Swing panel) to use Swing lifecycle and painting system.
     *
     * Features:
     * 1) Zoom, pan, rotate interactions (mouse: left drag pan, right drag rotate, wheel zoom,
     *    double-click or press R to reset).
     * 2) Thread-safe vehicle data updates via ConcurrentHashMap.
     * 3) Coordinate conversion (world -> screen), drawing background/roads/TLS/vehicles.
     * 4) Vehicle filtering via Filter interface.
     */
    public static class MapPanel extends JPanel {

        // Vehicle data caches (thread-safe maps prevent ConcurrentModificationException during repaint)
        private final Map<String, Point2D.Double> vehicles = new ConcurrentHashMap<>(); // vehicleId -> position
        private final Map<String, String> vehicleTypes = new ConcurrentHashMap<>();     // vehicleId -> type
        private final Map<String, Double> vehicleSpeeds = new ConcurrentHashMap<>();   // vehicleId -> speed
        private final Filter filter; // Vehicle filter rules

        // View transform state: zoom, rotation (radians), pan (x/y in pixels)
        private double viewZoom = 1.0;
        private double viewRotationRad = 0.0;
        private double viewPanX = 0.0;
        private double viewPanY = 0.0;

        // Mouse interaction state: last mouse position, pan/rotate dragging flags
        private Point lastMouse = null;
        private boolean draggingPan = false;
        private boolean draggingRotate = false;

        /*
         * MapPanel: one of the most important classes because it enables user interaction with the panel.
         */
        // Constructor: pass filter and install interaction listeners.
        public MapPanel(Filter filter) {
            this.filter = filter;
            setOpaque(true);
            setFocusable(true);
            installMapInteraction();
        }

        /**
         * Install map interaction listeners.
         * Design:
         * 1) Use MouseAdapter to handle press/release/drag/wheel/click.
         * 2) Key binding: press R to reset view.
         * 3) After each interaction call repaint() to trigger paintComponent.
         *
         * Thread-safety note: all view state changes happen on the EDT (Swing event thread).
         */
        private void installMapInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    lastMouse = e.getPoint();
                    draggingPan = SwingUtilities.isLeftMouseButton(e);     // left button -> pan
                    draggingRotate = SwingUtilities.isRightMouseButton(e); // right button -> rotate
                }
                @Override public void mouseReleased(MouseEvent e) {
                    lastMouse = null;
                    draggingPan = false;
                    draggingRotate = false;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (lastMouse == null) { lastMouse = e.getPoint(); return; }
                    int dx = e.getX() - lastMouse.x;
                    int dy = e.getY() - lastMouse.y;

                    if (draggingPan) {
                        viewPanX += dx;
                        viewPanY += dy;
                        repaint(); // repaint after panning
                    } else if (draggingRotate) {
                        viewRotationRad += dx * 0.01;
                        repaint(); // repaint after rotating
                    }
                    lastMouse = e.getPoint();
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2) resetView(); // double-click resets view
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    requestFocusInWindow();
                    double wheel = e.getPreciseWheelRotation();

                    if (e.isShiftDown()) {
                        // Shift + wheel -> rotate
                        viewRotationRad += (-wheel) * 0.10;
                        repaint();
                        return;
                    }

                    // Wheel zoom: zoom around cursor position (more natural for user)
                    Point pFinal = e.getPoint();
                    Point2D.Double pBase = inverseViewTransform(pFinal.x, pFinal.y);

                    double zoomFactor = Math.pow(1.12, -wheel);
                    double newZoom = clamp(viewZoom * zoomFactor, 0.20, 12.0); // clamp zoom range

                    viewZoom = newZoom;
                    solvePanForFixedBasePoint(pBase.x, pBase.y, pFinal.x, pFinal.y);
                    repaint(); // repaint after zooming
                }
            };

            // Register mouse listeners
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);

            // Key binding: press R to reset view.
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "resetView");
            getActionMap().put("resetView", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { resetView(); }
            });
        }

        // Reset view state (zoom=1, rotation=0, pan=0).
        private void resetView() {
            viewZoom = 1.0;
            viewRotationRad = 0.0;
            viewPanX = 0.0;
            viewPanY = 0.0;
            repaint();
        }

        // Clamp values to a given range.
        private static double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        // Apply view transform (zoom + rotation + pan) to screen coordinates.
        private Point2D.Double applyViewTransform(double x, double y) {
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;

            double bx = x - cx;
            double by = y - cy;

            double cos = Math.cos(viewRotationRad);
            double sin = Math.sin(viewRotationRad);

            double sx = bx * viewZoom;
            double sy = by * viewZoom;

            double rx = sx * cos - sy * sin;
            double ry = sx * sin + sy * cos;

            return new Point2D.Double(cx + rx + viewPanX, cy + ry + viewPanY);
        }

        // Inverse view transform: convert screen coordinate back to base screen coordinate
        // (used when computing zoom around cursor).
        private Point2D.Double inverseViewTransform(double sx, double sy) {
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;

            double dx = sx - cx - viewPanX;
            double dy = sy - cy - viewPanY;

            double cos = Math.cos(viewRotationRad);
            double sin = Math.sin(viewRotationRad);

            double rbx = dx * cos + dy * sin;
            double rby = -dx * sin + dy * cos;

            double bx = rbx / Math.max(1e-9, viewZoom);
            double by = rby / Math.max(1e-9, viewZoom);

            return new Point2D.Double(cx + bx, cy + by);
        }

        // Compute pan so that the same base point stays under the cursor after zoom.
        private void solvePanForFixedBasePoint(double baseX, double baseY, double finalX, double finalY) {
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;

            double bx = baseX - cx;
            double by = baseY - cy;

            double cos = Math.cos(viewRotationRad);
            double sin = Math.sin(viewRotationRad);

            double sx = bx * viewZoom;
            double sy = by * viewZoom;

            double rx = sx * cos - sy * sin;
            double ry = sx * sin + sy * cos;

            viewPanX = finalX - cx - rx;
            viewPanY = finalY - cy - ry;
        }

        /**
         * Thread-safe vehicle updates.
         * Design: clear then batch put, to avoid partial updates and reduce flicker.
         */
        public void updateVehicles(Map<String, Point2D.Double> newPos,
                                   Map<String, String> newTypes,
                                   Map<String, Double> newSpeeds) {
            vehicles.clear(); vehicleTypes.clear(); vehicleSpeeds.clear();
            if (newPos != null) vehicles.putAll(newPos);
            if (newTypes != null) vehicleTypes.putAll(newTypes);
            if (newSpeeds != null) vehicleSpeeds.putAll(newSpeeds);
            repaint(); // repaint after data update
        }

        // Compute current scale based on bounds and panel size.
        private double currentScale(Bounds b) {
            double panelW = Math.max(1, getWidth());
            double panelH = Math.max(1, getHeight());
            double worldW = Math.max(1e-9, b.maxX - b.minX);
            double worldH = Math.max(1e-9, b.maxY - b.minY);
            return Math.min(panelW / worldW, panelH / worldH) * viewZoom;
        }

        // Convert world coordinates to screen coordinates (including view transform).
        private Point worldToScreen(double wx, double wy, Bounds b) {
            Point2D.Double base = baseWorldToScreen(wx, wy, b);
            Point2D.Double v = applyViewTransform(base.x, base.y);
            return new Point((int) Math.round(v.x), (int) Math.round(v.y));
        }

        // Base world->screen conversion (fit-to-panel only, no view transform).
        private Point2D.Double baseWorldToScreen(double wx, double wy, Bounds b) {
            double panelW = Math.max(1, getWidth());
            double panelH = Math.max(1, getHeight());

            double worldW = Math.max(1e-9, b.maxX - b.minX);
            double worldH = Math.max(1e-9, b.maxY - b.minY);

            double fit = Math.min(panelW / worldW, panelH / worldH);
            double contentW = worldW * fit;
            double contentH = worldH * fit;

            double xPad = (panelW - contentW) / 2.0;
            double yPad = (panelH - contentH) / 2.0;

            double sx = xPad + (wx - b.minX) * fit;
            double sy = yPad + (wy - b.minY) * fit;

            // SUMO Y-axis is up, screen Y-axis is down, so flip Y.
            double screenY = panelH - sy;
            return new Point2D.Double(sx, screenY);
        }

        // Build a Path2D for a lane.
        private Path2D.Double buildPath(double[] xy, Bounds b) {
            Path2D.Double path = new Path2D.Double();
            Point p0 = worldToScreen(xy[0], xy[1], b);
            path.moveTo(p0.x, p0.y);
            for (int k = 2; k < xy.length; k += 2) {
                Point pk = worldToScreen(xy[k], xy[k + 1], b);
                path.lineTo(pk.x, pk.y);
            }
            return path;
        }

        // Draw background (light gray).
        private void drawBackground(Graphics2D g2) {
            g2.setColor(new Color(0xF3F4F6));
            g2.fillRect(0, 0, getWidth(), getHeight());
        }

        // Draw roads in layers: outline -> shoulder -> surface -> markings (better visual depth).
        private void drawRoads(Graphics2D g2, Bounds b) {
            if (ROAD_GEOMS == null || ROAD_GEOMS.isEmpty()) return;

            double sc = currentScale(b);

            // Road colors: outline, road, internal road, shoulder, lane marking
            Color outline = new Color(0x0B0F14);
            Color road = new Color(0x111827);
            Color roadInternal = new Color(0x1F2937);
            Color shoulder = new Color(0x2A2F36);
            Color marking = new Color(255,255,255,180);

            // Step 1: draw road outline
            for (RoadGeom rg : ROAD_GEOMS) {
                if (!DRAW_INTERNAL_CONNECTORS && rg.internal) continue;
                if (rg.xy.length < 4) continue;

                float lanePx = (float) Math.max(ROAD_MIN_PX, rg.laneWidth * sc * ROAD_THICKNESS_MULT);
                if (rg.internal) lanePx = Math.max(3.0f, lanePx * 0.70f);

                Path2D path = buildPath(rg.xy, b);
                g2.setStroke(new BasicStroke(lanePx + 6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(outline);
                g2.draw(path);
            }

            // Step 2: draw shoulder
            for (RoadGeom rg : ROAD_GEOMS) {
                if (!DRAW_INTERNAL_CONNECTORS && rg.internal) continue;
                if (rg.xy.length < 4) continue;

                float lanePx = (float) Math.max(ROAD_MIN_PX, rg.laneWidth * sc * ROAD_THICKNESS_MULT);
                if (rg.internal) lanePx = Math.max(3.0f, lanePx * 0.70f);

                Path2D path = buildPath(rg.xy, b);
                g2.setStroke(new BasicStroke(lanePx + 2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(shoulder);
                g2.draw(path);
            }

            // Step 3: draw road surface
            for (RoadGeom rg : ROAD_GEOMS) {
                if (!DRAW_INTERNAL_CONNECTORS && rg.internal) continue;
                if (rg.xy.length < 4) continue;

                float lanePx = (float) Math.max(ROAD_MIN_PX, rg.laneWidth * sc * ROAD_THICKNESS_MULT);
                if (rg.internal) lanePx = Math.max(3.0f, lanePx * 0.70f);

                Path2D path = buildPath(rg.xy, b);
                g2.setStroke(new BasicStroke(lanePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(rg.internal ? roadInternal : road);
                g2.draw(path);
            }

            // Step 4: draw lane markings (only lane index 0, non-internal)
            if (DRAW_LANE_MARKINGS) {
                for (RoadGeom rg : ROAD_GEOMS) {
                    if (rg.internal) continue;
                    if (rg.laneIndex != 0) continue;
                    if (rg.xy.length < 4) continue;

                    float lanePx = (float) Math.max(ROAD_MIN_PX, rg.laneWidth * sc * ROAD_THICKNESS_MULT);
                    Path2D path = buildPath(rg.xy, b);

                    float markW = Math.max(1.5f, lanePx * 0.10f);
                    float dashA = Math.max(12f, lanePx * 1.4f);
                    float dashB = Math.max(10f, lanePx * 1.1f);
                    float[] dash = new float[]{dashA, dashB}; // dashed line style

                    g2.setStroke(new BasicStroke(markW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
                    g2.setColor(marking);
                    g2.draw(path);
                }
            }
        }

        // Draw traffic light markers (circle marker + simplified label).
        private void drawTlsMarkers(Graphics2D g2, Bounds b) {
            Map<String, Point2D.Double> pos = TLS_POSITIONS;
            if (pos == null || pos.isEmpty()) return;

            Font oldF = g2.getFont();
            Font f = oldF.deriveFont(Font.BOLD, 12f);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();

            for (Map.Entry<String, Point2D.Double> e : pos.entrySet()) {
                String tlsId = e.getKey();
                Point2D.Double p = e.getValue();
                if (p == null) continue;

                Point s = worldToScreen(p.x, p.y, b);
                int sx = s.x, sy = s.y;

                String tag = TLS_LABELS != null ? TLS_LABELS.getOrDefault(tlsId, tlsId) : tlsId;

                // White circular background
                g2.setColor(new Color(255, 255, 255, 220));
                g2.fillOval(sx - 5, sy - 5, 10, 10);
                // Dark border
                g2.setColor(new Color(17, 24, 39, 220));
                g2.drawOval(sx - 5, sy - 5, 10, 10);

                // Label background (rounded rectangle)
                int tw = fm.stringWidth(tag);
                int th = fm.getAscent();
                int padX = 6, padY = 3;
                int bx = sx + 8;
                int by = sy - th - 2;

                g2.setColor(new Color(255, 255, 255, 200));
                g2.fillRoundRect(bx, by, tw + padX * 2, th + padY * 2, 10, 10);
                g2.setColor(new Color(17, 24, 39, 220));
                g2.drawRoundRect(bx, by, tw + padX * 2, th + padY * 2, 10, 10);

                // Label text
                g2.drawString(tag, bx + padX, by + padY + th - 2);
            }

            g2.setFont(oldF); // restore font
        }

        // Draw a vehicle (different type -> different shape).
        private void drawVehicle(Graphics2D g2, int sx, int sy, String type) {
            // Draw vehicle shadow (adds depth)
            g2.setColor(new Color(0,0,0,70));
            g2.fillOval(sx - 8, sy + 2, 16, 8);

            if (Main.TYPE_CAR.equals(type)) {
                // Draw car (orange)
                g2.setColor(new Color(0xFB923C));
                g2.fillRoundRect(sx - 7, sy - 5, 14, 10, 6, 6);
                // Wheels
                g2.setColor(new Color(0x111827));
                g2.fillOval(sx - 6, sy + 4, 4, 4);
                g2.fillOval(sx + 2, sy + 4, 4, 4);
            } else if (Main.TYPE_TRUCK.equals(type)) {
                // Draw truck (gray + yellow cargo)
                g2.setColor(new Color(0x94A3B8));
                g2.fillRoundRect(sx - 12, sy - 6, 24, 12, 4, 4);
                g2.setColor(new Color(0xFDE68A));
                g2.fillRoundRect(sx + 2, sy - 6, 10, 12, 3, 3);
                // Wheels
                g2.setColor(new Color(0x111827));
                g2.fillOval(sx - 10, sy + 5, 4, 4);
                g2.fillOval(sx + 6, sy + 5, 4, 4);
            } else {
                // Draw bus (yellow)
                g2.setColor(new Color(0xFACC15));
                g2.fillRoundRect(sx - 14, sy - 6, 28, 12, 6, 6);
                // Wheels
                g2.setColor(new Color(0x111827));
                g2.fillOval(sx - 12, sy + 5, 4, 4);
                g2.fillOval(sx + 8, sy + 5, 4, 4);
            }
        }

        /**
         * The paint loop.
         * Swing uses double-buffering by default, so we draw to the Graphics object.
         * Draw order: background -> roads -> TLS -> vehicles (ensures correct layering).
         */
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            // Anti-aliasing makes lines and vehicles smoother (less jagged).
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Bounds b = getActiveBounds();

            // Draw order matters.
            drawBackground(g2);
            drawRoads(g2, b);
            drawTlsMarkers(g2, b);

            // Draw vehicles from live data (ConcurrentHashMap prevents crashes if data updates during drawing).
            for (Map.Entry<String, Point2D.Double> e : vehicles.entrySet()) {
                String id = e.getKey();
                Point2D.Double p = e.getValue();
                if (p == null) continue;

                String type = vehicleTypes.getOrDefault(id, Main.TYPE_CAR);
                double sp = vehicleSpeeds.getOrDefault(id, 0.0);

                // Apply filter (e.g., if "Show Cars" is unchecked, skip drawing cars).
                if (filter != null && !filter.allows(type, sp)) continue;

                // Convert world coordinates (meters) to screen coordinates (pixels).
                Point s = worldToScreen(p.x, p.y, b);
                drawVehicle(g2, s.x, s.y, type);
            }
        }
    }

    // ===================== Trend chart panel =====================
    /**
     * Static inner class: TrendChartPanel
     * Purpose: Draw live trend charts for avg wait time, throughput, and congestion.
     * Thread-safety: uses synchronized blocks when adding/reading data.
     */
    /*
     * TrendChartPanel:
     * Displays current traffic status via line charts:
     * average wait time, throughput, congestion index.
     */
    public static class TrendChartPanel extends JPanel {
        // Trend data caches: avg wait time (s), throughput (v/h), congestion index
        private final java.util.List<Double> avgWaitSec = new ArrayList<>();
        private final java.util.List<Double> throughputVph = new ArrayList<>();
        private final java.util.List<Double> congestion = new ArrayList<>();
        private final int maxPoints; // max number of points (remove oldest if exceeded)

        // Constructor: set max points and panel size/background.
        public TrendChartPanel(int maxPoints) {
            this.maxPoints = Math.max(30, maxPoints);
            setPreferredSize(new Dimension(320, 150));
            setMinimumSize(new Dimension(320, 150));
            setOpaque(true);
            setBackground(new Color(0x0B1220));
        }

        /**
         * Add a trend sample (thread-safe).
         * @param avgWait average waiting time
         * @param thr throughput
         * @param cong congestion index
         */
        public void addSample(double avgWait, double thr, double cong) {
            synchronized (this) { // lock ensures atomic add/read behavior
                push(avgWaitSec, avgWait);
                push(throughputVph, thr);
                push(congestion, cong);
            }
            repaint(); // repaint after adding data
        }

        // Push value, keep sliding window length <= maxPoints.
        private void push(java.util.List<Double> series, double v) {
            series.add(v);
            while (series.size() > maxPoints) series.remove(0);
        }

        // Draw the trend chart.
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(getBackground());
            g2.fillRect(0,0,w,h);

            int pad = 10;
            int chartX = pad, chartY = pad, chartW = w - 2*pad, chartH = h - 2*pad;

            // Draw chart border
            g2.setColor(new Color(255,255,255,40));
            g2.drawRoundRect(chartX, chartY, chartW, chartH, 10, 10);

            // Snapshot data (avoid updating while drawing)
            java.util.List<Double> a, t, c;
            synchronized (this) {
                a = new ArrayList<>(avgWaitSec);
                t = new ArrayList<>(throughputVph);
                c = new ArrayList<>(congestion);
            }

            // Show message if not enough data
            if (a.size() < 2) {
                g2.setColor(new Color(255,255,255,120));
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.drawString("Live Trends (need data)...", chartX + 10, chartY + 18);
                return;
            }

            // Draw three series
            drawSeries(g2, a, chartX, chartY, chartW, chartH, new Color(0x22C55E), "AvgWait(s)");
            drawSeries(g2, t, chartX, chartY, chartW, chartH, new Color(0x3B82F6), "VPH");
            drawSeries(g2, c, chartX, chartY, chartW, chartH, new Color(0xEF4444), "Cong");
        }

        // Draw a single series line.
        private void drawSeries(Graphics2D g2, java.util.List<Double> series,
                                int x, int y, int w, int h, Color col, String name) {

            // Find min/max for scaling.
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : series) {
                if (Double.isNaN(v) || Double.isInfinite(v)) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (!(min < Double.POSITIVE_INFINITY) || !(max > Double.NEGATIVE_INFINITY)) return;
            if (Math.abs(max - min) < 1e-9) { max = min + 1.0; } // avoid division by zero

            int n = series.size();
            double dx = (n <= 1) ? 1 : (w - 6) / (double)(n - 1);

            // Line style
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 170));

            // Polyline
            int prevX = x + 3;
            int prevY = y + h - 3 - (int) Math.round((series.get(0) - min) / (max - min) * (h - 6));

            for (int i = 1; i < n; i++) {
                double v = series.get(i);
                int cx = x + 3 + (int)Math.round(i * dx);
                int cy = y + h - 3 - (int) Math.round((v - min) / (max - min) * (h - 6));
                g2.drawLine(prevX, prevY, cx, cy);
                prevX = cx; prevY = cy;
            }

            // Legend text
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 200));
            int yOff = name.equals("AvgWait(s)") ? 0 : name.equals("VPH") ? 14 : 28;
            g2.drawString(name, x + 12, y + 18 + yOff);
        }
    }

    // ===================== Utility: render component to image =====================
    /**
     * Render a Swing component to a BufferedImage.
     * Use case: export screenshot, save current map view, include in PDF.
     *
     * @param comp component to render
     * @param fallbackW fallback width if component size is invalid
     * @param fallbackH fallback height if component size is invalid
     * @return rendered image
     */
    /*
     * Export functionality: users can export the current view as an image or PDF.
     */
    public static BufferedImage renderComponentToImage(JComponent comp, int fallbackW, int fallbackH) {
        int w = comp.getWidth();
        int h = comp.getHeight();

        if (w <= 0 || h <= 0) {
            w = fallbackW;
            h = fallbackH;
            comp.setSize(w, h);
            comp.doLayout();
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);
            // Print everything on the component (including child components)
            comp.printAll(g2);
        } finally {
            // Release graphics resources (avoid memory leaks)
            g2.dispose();
        }
        return img;
    }
}

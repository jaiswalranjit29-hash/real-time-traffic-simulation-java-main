// ===================== LiveConnectionSumo.java =====================
package org.example;

import org.eclipse.sumo.libtraci.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

// Use Java to connect to the SUMO simulation (TraCI/libtraci) and read vehicle data in real time.
public class LiveConnectionSumo implements Runnable {

    // The UI must not be blocked. The simulation loop has to run in a separate thread;
    // otherwise the Swing interface will freeze and become unresponsive.

    private final JFrame owner;
    private final MapVisualisation.MapPanel mapPanel;
    private final MapVisualisation.TrendChartPanel trendChart;

    private final JLabel activeVehiclesLabel;
    private final JLabel visibleVehiclesLabel;
    private final JLabel byTypeLabel;

    private final JLabel avgWaitLabel;
    private final JLabel congestionLabel;
    private final JLabel throughputLabel;
    private final JLabel meanSpeedLabel;

    private final JLabel tlStateLabel;

    private final JComboBox<VehicleInjection.RouteDef> routeCombo;
    private final JComboBox<TrafficControl.TlsItem> tlCombo;

    private final TrafficControl trafficControl;

    private final Runnable onStopped;

    // volatile ensures the simulation thread can immediately see changes made by the UI thread.
    private volatile boolean started = false;
    private volatile boolean running = true;
    private volatile int sleepMs = 50;
    private volatile int latestSpeedFactorUi = 1;

    private int injectedCounter = 0;

    // ===================== AUTO REROUTE OFF =====================
    private static final boolean AUTO_REROUTE_ENABLED = false;

    // ===================== Export-data log =====================
    public static class MetricRow {
        // Stores per-sample metrics used for UI visualization, analysis, and export.
        final String exportLocalTime;
        final double simTime;
        final int activeVehicles;
        final int stoppedVehicles;
        final double congestionIndex;
        final double avgWaitSeconds;
        final double meanSpeedMps;
        final double throughputVph;
        final int speedFactorUi;

        final int visibleVehicles;
        final int visibleCars;
        final int visibleTrucks;
        final int visibleBuses;

        final boolean ruleBasedEnabled;

        MetricRow(String exportLocalTime, double simTime, int activeVehicles, int stoppedVehicles,
                  double congestionIndex, double avgWaitSeconds, double meanSpeedMps,
                  double throughputVph, int speedFactorUi,
                  int visibleVehicles, int visibleCars, int visibleTrucks, int visibleBuses,
                  boolean ruleBasedEnabled) {
            this.exportLocalTime = exportLocalTime;
            this.simTime = simTime;
            this.activeVehicles = activeVehicles;
            this.stoppedVehicles = stoppedVehicles;
            this.congestionIndex = congestionIndex;
            this.avgWaitSeconds = avgWaitSeconds;
            this.meanSpeedMps = meanSpeedMps;
            this.throughputVph = throughputVph;
            this.speedFactorUi = speedFactorUi;
            this.visibleVehicles = visibleVehicles;
            this.visibleCars = visibleCars;
            this.visibleTrucks = visibleTrucks;
            this.visibleBuses = visibleBuses;
            this.ruleBasedEnabled = ruleBasedEnabled;
        }
    }

    // Stores all MetricRow entries; synchronizedList is used because run() writes while export reads,
    // preventing concurrency issues.
    private final List<MetricRow> metricsLog = Collections.synchronizedList(new ArrayList<>());

    private final Deque<Double> arrivalTimes = new ArrayDeque<>();
    private final Set<String> prevVehicleIds = new HashSet<>();

    private double lastLoggedSimTime = -1.0;
    private static final double LOG_EVERY_SIM_SECONDS = 0.5;

    // Filter state source (GUI provides values)
    private final GUI.VehicleFilter filter;

    public LiveConnectionSumo(
            JFrame owner,
            MapVisualisation.MapPanel mapPanel,
            MapVisualisation.TrendChartPanel trendChart,
            GUI.VehicleFilter filter,
            JLabel activeVehiclesLabel,
            JLabel visibleVehiclesLabel,
            JLabel byTypeLabel,
            JLabel avgWaitLabel,
            JLabel congestionLabel,
            JLabel throughputLabel,
            JLabel meanSpeedLabel,
            JLabel tlStateLabel,
            JComboBox<VehicleInjection.RouteDef> routeCombo,
            JComboBox<TrafficControl.TlsItem> tlCombo,
            TrafficControl trafficControl,
            Runnable onStopped
    ) {
        this.owner = owner;
        this.mapPanel = mapPanel;
        this.trendChart = trendChart;
        this.filter = filter;

        this.activeVehiclesLabel = activeVehiclesLabel;
        this.visibleVehiclesLabel = visibleVehiclesLabel;
        this.byTypeLabel = byTypeLabel;

        this.avgWaitLabel = avgWaitLabel;
        this.congestionLabel = congestionLabel;
        this.throughputLabel = throughputLabel;
        this.meanSpeedLabel = meanSpeedLabel;

        this.tlStateLabel = tlStateLabel;

        this.routeCombo = routeCombo;
        this.tlCombo = tlCombo;
        this.trafficControl = trafficControl;

        this.onStopped = onStopped;

        new Thread(this, "SUMO-Simulation-Thread").start();
    }

    // Turn on started so run() will actually step the simulation.
    public void startSimulation() {
        started = true;
        Logging.LOG.info("Simulation START pressed.");
    }

    public void setSpeedDelay(int ms, int speedFactorUi) {
        sleepMs = Math.max(1, ms);
        latestSpeedFactorUi = speedFactorUi;
    }

    // Turn off running so the while loop in run() exits and the simulation thread shuts down cleanly.
    public void stopSimulation() {
        running = false;
        Logging.LOG.info("Simulation STOP pressed.");
    }

    // Routing file override for manual injection only
    private String buildRoutesFileWithVTypesOnly() throws IOException {
        File tmp = File.createTempFile("manual_only_", ".rou.xml");
        tmp.deleteOnExit();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<routes xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            pw.println("        xsi:noNamespaceSchemaLocation=\"http://sumo.dlr.de/xsd/routes_file.xsd\">");
            pw.println("  <!-- Manual injection only (no trips/vehicles here) -->");
            pw.println("  <vType id=\"car\"   vClass=\"passenger\" length=\"5.0\"  accel=\"2.6\" decel=\"4.5\" maxSpeed=\"33\"/>");
            pw.println("  <vType id=\"truck\" vClass=\"truck\"     length=\"8.0\"  accel=\"1.3\" decel=\"4.0\" maxSpeed=\"25\"/>");
            pw.println("  <vType id=\"bus\"   vClass=\"bus\"       length=\"12.0\" accel=\"1.1\" decel=\"4.0\" maxSpeed=\"22\"/>");
            pw.println("</routes>");
        }
        Logging.LOG.info("Temp manual routes file: " + tmp.getAbsolutePath());
        return tmp.getAbsolutePath();
    }

    // Version compatibility: support different libtraci method names across versions.
    private double safeVehicleWaitingTimeSec(String vehId) {
        try {
            Method m = Vehicle.class.getMethod("getWaitingTime", String.class);
            Object o = m.invoke(null, vehId);
            if (o instanceof Number) return ((Number) o).doubleValue();
        } catch (Exception ignore) {}

        try {
            Method m = Vehicle.class.getMethod("getAccumulatedWaitingTime", String.class);
            Object o = m.invoke(null, vehId);
            if (o instanceof Number) return ((Number) o).doubleValue();
        } catch (Exception ignore) {}

        return Double.NaN;
    }

    private double computeAvgWaitSeconds(StringVector vIds) {
        if (vIds == null || vIds.size() == 0) return 0.0;

        double sum = 0.0;
        int cnt = 0;
        for (int i = 0; i < vIds.size(); i++) {
            String id = vIds.get(i);
            double w = safeVehicleWaitingTimeSec(id);
            if (!Double.isNaN(w) && !Double.isInfinite(w)) {
                sum += w;
                cnt++;
            }
        }
        if (cnt == 0) return -1.0;
        return sum / cnt;
    }

    private double computeMeanSpeed(StringVector vIds) {
        if (vIds == null || vIds.size() == 0) return 0.0;
        double sum = 0.0;
        int cnt = 0;
        for (int i = 0; i < vIds.size(); i++) {
            try {
                sum += Vehicle.getSpeed(vIds.get(i));
                cnt++;
            } catch (Exception ignore) {}
        }
        return cnt == 0 ? 0.0 : (sum / cnt);
    }

    private int countStopped(StringVector vIds) {
        int stopped = 0;
        for (int i = 0; i < vIds.size(); i++) {
            try {
                if (Vehicle.getSpeed(vIds.get(i)) < 0.1) stopped++;
            } catch (Exception ignore) {}
        }
        return stopped;
    }

    private int recordArrivalsAndReturnCount(double simTime, Set<String> currentIdsSet) {
        try {
            Method m = Simulation.class.getMethod("getArrivedIDList");
            Object o = m.invoke(null);
            if (o instanceof StringVector) {
                StringVector arrived = (StringVector) o;
                int n = arrived.size();
                for (int i = 0; i < n; i++) arrivalTimes.addLast(simTime);
                return n;
            }
        } catch (Exception ignore) {}

        int arrived = 0;
        for (String oldId : prevVehicleIds) {
            if (!currentIdsSet.contains(oldId)) {
                arrived++;
                arrivalTimes.addLast(simTime);
            }
        }
        return arrived;
    }

    private void trimThroughputWindow(double simTime) {
        while (!arrivalTimes.isEmpty()) {
            double t = arrivalTimes.peekFirst();
            if ((simTime - t) > Main.THROUGHPUT_WINDOW_SEC) arrivalTimes.removeFirst();
            else break;
        }
    }

    private double computeThroughputVph() {
        double windowHours = Main.THROUGHPUT_WINDOW_SEC / 3600.0;
        if (windowHours <= 1e-9) return 0.0;
        return arrivalTimes.size() / windowHours;
    }

    // ===================== Simulation main loop =====================
    @Override public void run() {
        try {
            // Load native C++ libraries for SUMO.
            Simulation.preloadLibraries();

            // Generate a temporary route file containing only vTypes, and store its path in manualRou.
            String manualRou = buildRoutesFileWithVTypesOnly();

            // Build the command line arguments to start SUMO in GUI mode.
            StringVector cmd = new StringVector();
            cmd.add("sumo-gui");

            // Add -c and the config file path to start SUMO with the chosen .sumocfg.
            cmd.add("-c"); cmd.add(Main.SUMOCFG_PATH);
            cmd.add("--route-files"); cmd.add(manualRou);
            cmd.add("--start");
            cmd.add("--quit-on-end");

            Logging.LOG.info("Starting SUMO: " + cmd);

            // TraCI connection happens here (libtraci start is a static call).
            Simulation.start(cmd);

            Logging.LOG.info("SUMO started.");

            // Rebuild UI dropdown options.
            trafficControl.rebuildTrafficLightDropdown();
            VehicleInjection.rebuildAllowedRoutesAndDropdown(routeCombo);

            // As long as running is true, keep stepping the simulation, collecting data, and updating the UI.
            while (running) {
                if (!started) {
                    // Idle wait to reduce CPU usage when paused.
                    Thread.sleep(50);
                    continue;
                }

                // Advance simulation by one step.
                Simulation.step();
                double simTime = Simulation.getCurrentTime();

                // Apply logic: every step, let trafficControl apply per-step control logic
                // (e.g., change lights, keep manual overrides, etc.).
                trafficControl.applyPerStep(simTime);

                // Auto reroute disabled
                if (AUTO_REROUTE_ENABLED) {
                }

                // Throttle simulation speed to be viewable by human eye.
                Thread.sleep(sleepMs);

                // ---- vehicle metrics ----
                // DATA COLLECTION PHASE
                StringVector vIds = Vehicle.getIDList();
                int active = vIds.size();

                Set<String> currentIdSet = new HashSet<>(Math.max(16, active * 2));
                for (int i = 0; i < vIds.size(); i++) currentIdSet.add(vIds.get(i));

                // Record “newly appeared/arrived” events as base data for throughput statistics.
                recordArrivalsAndReturnCount(simTime, currentIdSet);
                // Trim arrival log based on the configured throughput window (seconds).
                trimThroughputWindow(simTime);
                // Compute throughput in vehicles per hour.
                double throughputVph = computeThroughputVph();

                prevVehicleIds.clear();
                prevVehicleIds.addAll(currentIdSet);

                int stopped = countStopped(vIds);

                // Congestion index = proportion of stopped vehicles; avoid division by zero if no vehicles.
                double congestion = active > 0 ? (double) stopped / active : 0.0;

                // Average waiting time (seconds) for display and trend chart.
                double avgWaitSec = computeAvgWaitSeconds(vIds);
                double meanSpeed = computeMeanSpeed(vIds);

                // Create position cache: vehicleId -> (x,y), used by mapPanel for drawing vehicles.
                Map<String, java.awt.geom.Point2D.Double> positions = new HashMap<>();
                Map<String, String> types = new HashMap<>();
                Map<String, Double> speeds = new HashMap<>();

                int visibleCount = 0, visCar = 0, visTruck = 0, visBus = 0;

                // Iterate over vehicles: read position/type/speed and store them.
                for (int i = 0; i < vIds.size(); i++) {
                    String id = vIds.get(i);

                    TraCIPosition pos = Vehicle.getPosition(id);

                    // Store coordinates in positions; the UI renderer only needs positions for drawing.
                    positions.put(id, new java.awt.geom.Point2D.Double(pos.getX(), pos.getY()));

                    String type;
                    if (id.startsWith("car_")) type = Main.TYPE_CAR;
                    else if (id.startsWith("truck_")) type = Main.TYPE_TRUCK;
                    else if (id.startsWith("bus_")) type = Main.TYPE_BUS;
                    else type = Main.TYPE_CAR;

                    types.put(id, type);

                    double sp = 0.0; // default speed is 0
                    try { sp = Vehicle.getSpeed(id); } catch (Exception ignore) {}
                    speeds.put(id, sp);

                    // Filter check: should this vehicle be “visible/countable” under current filter settings?
                    if (filter.allows(type, sp)) {
                        visibleCount++;
                        if (Main.TYPE_CAR.equals(type)) visCar++;
                        else if (Main.TYPE_TRUCK.equals(type)) visTruck++;
                        else if (Main.TYPE_BUS.equals(type)) visBus++;
                    }
                }

                if (lastLoggedSimTime < 0 || (simTime - lastLoggedSimTime) >= LOG_EVERY_SIM_SECONDS) {
                    lastLoggedSimTime = simTime;

                    // Add one metrics row (raw data for CSV/PDF export).
                    metricsLog.add(new MetricRow(
                            Logging.nowTag(), simTime, active, stopped, congestion,
                            avgWaitSec, meanSpeed, throughputVph, latestSpeedFactorUi,
                            visibleCount, visCar, visTruck, visBus,
                            trafficControl.isRuleBasedTlsEnabled()
                    ));

                    // Add current data to the trend chart data source.
                    trendChart.addSample(
                            avgWaitSec < 0 ? 0.0 : avgWaitSec,
                            throughputVph,
                            congestion
                    );
                }

                // VISUALIZATION UPDATE
                // Capture "final" variables to pass a snapshot of data to the UI.
                final Map<String, java.awt.geom.Point2D.Double> positionsF = positions;
                final Map<String, String> typesF = types;
                final Map<String, Double> speedsF = speeds;

                final int activeF = active;
                final int stoppedF = stopped;
                final double congestionF = congestion;
                final double avgWaitSecF = avgWaitSec;
                final double throughputVphF = throughputVph;
                final double meanSpeedF = meanSpeed;

                final int visibleF = visibleCount;
                final int visCarF = visCar, visTruckF = visTruck, visBusF = visBus;

                final String tlsStateF = trafficControl.buildTlsStatusString();

                // Run UI updates on Swing's Event Dispatch Thread.
                SwingUtilities.invokeLater(() -> {
                    // Vehicle snapshot: this is the data source used for map rendering.
                    mapPanel.updateVehicles(positionsF, typesF, speedsF);

                    // Update text labels...
                    activeVehiclesLabel.setText("Active Vehicles (all): " + activeF);
                    visibleVehiclesLabel.setText("Visible Vehicles (filtered): " + visibleF);
                    byTypeLabel.setText("By Type: car=" + visCarF + " truck=" + visTruckF + " bus=" + visBusF);

                    if (avgWaitSecF >= 0) {
                        // If waiting time is valid, display it normally.
                        avgWaitLabel.setText(String.format(Locale.US,
                                "Avg Wait Time: %.1f s (%.2f min)", avgWaitSecF, avgWaitSecF / 60.0));
                    } else {
                        // If waiting-time API is unavailable, display an alternative metric (stopped ratio).
                        double ratio = activeF > 0 ? (stoppedF / (double) activeF) : 0.0;
                        avgWaitLabel.setText(String.format(Locale.US,
                                "Avg Wait Time: N/A (API) | stopped ratio=%.2f", ratio));
                    }

                    // Update congestion label
                    congestionLabel.setText(String.format(Locale.US,
                            "Congestion Index: %.2f (stopped=%d)", congestionF, stoppedF));

                    throughputLabel.setText(String.format(Locale.US,
                            "Throughput: %.1f v/h (last %.0f s)", throughputVphF, Main.THROUGHPUT_WINDOW_SEC));

                    meanSpeedLabel.setText(String.format(Locale.US,
                            "Mean Speed: %.2f m/s", meanSpeedF));

                    tlStateLabel.setText(tlsStateF);
                });
            }

            // Clean shutdown ensures SUMO process doesn't hang in the background.
            try { Simulation.close(); } catch (Exception ignored) {}
            if (onStopped != null) SwingUtilities.invokeLater(onStopped);

        } catch (Exception ex) {
            Logging.LOG.log(java.util.logging.Level.SEVERE, "Simulation thread crashed", ex);
            // Even on crash, invoke the stop callback so the UI does not stay stuck in “running” state.
            if (onStopped != null) SwingUtilities.invokeLater(onStopped);
        }
    }

    // csvEscape ensures CSV compliance by quoting fields containing commas, quotes, or newlines,
    // and escaping internal quotes using the standard double-quote doubling rule.
    // ===================== Export CSV =====================
    private static String csvEscape(String s) {
        // Check if the string needs quoting.
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String t = s.replace("\"", "\"\"");
        return needs ? ("\"" + t + "\"") : t;
    }

    public void exportMetricsCsv(Component parent, String selectedRouteName) {
        // Let the user choose a save location (show a save dialog).
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save metrics CSV");
        chooser.setSelectedFile(new File("traffic_metrics.csv"));

        int res = chooser.showSaveDialog(parent);
        if (res != JFileChooser.APPROVE_OPTION) return;

        // Get the selected file name and enforce .csv suffix.
        File file = chooser.getSelectedFile();
        if (file == null) return;

        String path = file.getAbsolutePath();
        // If the user forgot ".csv", append it automatically.
        if (!path.toLowerCase(Locale.ROOT).endsWith(".csv")) file = new File(path + ".csv");

        // Take a snapshot before exporting (avoid data changing during export).
        List<MetricRow> snap;
        // metricsLog is continuously appended in the run() thread; export reads it here.
        synchronized (metricsLog) { snap = new ArrayList<>(metricsLog); }

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            pw.println("export_local_time,sim_time,active_vehicles,stopped_vehicles,congestion_index,avg_wait_seconds,mean_speed_mps,throughput_vph,speed_factor_ui,selected_route,visible_vehicles,visible_cars,visible_trucks,visible_buses,min_speed_filter_mps,rule_based_enabled");
            for (MetricRow r : snap) {
                // Write each MetricRow line-by-line
                pw.print(csvEscape(r.exportLocalTime)); pw.print(",");
                pw.print(String.format(Locale.US, "%.2f", r.simTime)); pw.print(",");
                pw.print(r.activeVehicles); pw.print(",");
                pw.print(r.stoppedVehicles); pw.print(",");
                pw.print(String.format(Locale.US, "%.4f", r.congestionIndex)); pw.print(",");
                pw.print(String.format(Locale.US, "%.3f", r.avgWaitSeconds)); pw.print(",");
                pw.print(String.format(Locale.US, "%.3f", r.meanSpeedMps)); pw.print(",");
                pw.print(String.format(Locale.US, "%.2f", r.throughputVph)); pw.print(",");
                pw.print(r.speedFactorUi); pw.print(",");
                pw.print(csvEscape(selectedRouteName)); pw.print(",");
                pw.print(r.visibleVehicles); pw.print(",");
                pw.print(r.visibleCars); pw.print(",");
                pw.print(r.visibleTrucks); pw.print(",");
                pw.print(r.visibleBuses); pw.print(",");
                // Use Locale.US to prevent decimal comma locales from breaking CSV columns.
                pw.print(String.format(Locale.US, "%.2f", filter.minSpeedMps)); pw.print(",");
                pw.println(r.ruleBasedEnabled ? "1" : "0");
            }
        } catch (Exception ex) {
            Logging.LOG.log(java.util.logging.Level.SEVERE, "Export CSV failed", ex);
            JOptionPane.showMessageDialog(parent, "CSV export failed:\n" + ex.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JOptionPane.showMessageDialog(parent,
                "Exported " + snap.size() + " rows to:\n" + file.getAbsolutePath(),
                "Export Data", JOptionPane.INFORMATION_MESSAGE);
    }

    // ===================== Export PDF (Summary) =====================
    public void exportSummaryPdf(JFrame parent, String selectedRouteName, BufferedImage chartImage) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save summary PDF");
        chooser.setSelectedFile(new File("traffic_summary.pdf"));

        int res = chooser.showSaveDialog(parent);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        if (file == null) return;

        String path = file.getAbsolutePath();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".pdf")) file = new File(path + ".pdf");

        List<MetricRow> snap;
        synchronized (metricsLog) { snap = new ArrayList<>(metricsLog); }
        MetricRow last = snap.isEmpty() ? null : snap.get(snap.size() - 1);

        List<String> lines = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        lines.add("Export time: " + now);
        lines.add("Selected scenario: " + (selectedRouteName == null ? "" : selectedRouteName));
        lines.add("Rule-based TLS enabled: " + trafficControl.isRuleBasedTlsEnabled());
        lines.add("Branch split: A=30%, B=40%, C=20%, D=10% (long routes, same destination)");

        lines.add("Filters: cars=" + filter.showCars +
                ", trucks=" + filter.showTrucks +
                ", buses=" + filter.showBuses +
                ", minSpeed(m/s)=" + String.format(Locale.US, "%.2f", filter.minSpeedMps));

        if (last == null) {
            lines.add("Metrics: (no data collected yet)");
        } else {
            lines.add("Sim time(s): " + String.format(Locale.US, "%.2f", last.simTime));
            lines.add("Active vehicles: " + last.activeVehicles + " | stopped: " + last.stoppedVehicles);
            lines.add("Congestion index: " + String.format(Locale.US, "%.4f", last.congestionIndex));
            lines.add("Avg wait(s): " + String.format(Locale.US, "%.3f", last.avgWaitSeconds));
            lines.add("Mean speed(m/s): " + String.format(Locale.US, "%.3f", last.meanSpeedMps));
            lines.add("Throughput(vph): " + String.format(Locale.US, "%.2f", last.throughputVph));
            lines.add("Speed factor(UI): " + last.speedFactorUi);
            lines.add("Visible vehicles: " + last.visibleVehicles +
                    " (cars " + last.visibleCars + ", trucks " + last.visibleTrucks + ", buses " + last.visibleBuses + ")");
        }

        try {
            SimplePdfWriter.writeOnePageReport(
                    file,
                    "Traffic Simulation - Summary Report",
                    lines,
                    chartImage
            );
        } catch (Exception ex) {
            Logging.LOG.log(java.util.logging.Level.SEVERE, "Export PDF failed", ex);
            JOptionPane.showMessageDialog(parent, "PDF export failed:\n" + ex.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JOptionPane.showMessageDialog(parent,
                "Exported summary PDF to:\n" + file.getAbsolutePath(),
                "Export PDF", JOptionPane.INFORMATION_MESSAGE);
    }

    // ===================== Simple PDF Writer =====================
    static class SimplePdfWriter {

        private static byte[] toJpegBytes(BufferedImage image) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }

        private static String pdfEscape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        }

        public static void writeOnePageReport(File file, String title, List<String> lines, BufferedImage chartImage) throws IOException {
            final int pageW = 595;
            final int pageH = 842;

            byte[] jpg = null;
            int imgW = 0, imgH = 0;
            if (chartImage != null) {
                imgW = chartImage.getWidth();
                imgH = chartImage.getHeight();
                jpg = toJpegBytes(chartImage);
            }

            final float margin = 48f;
            final float fontSizeTitle = 16f;
            final float fontSize = 11f;

            float imgMaxW = pageW - 2 * margin;
            float imgMaxH = 260f;

            float drawImgW = 0, drawImgH = 0;
            if (jpg != null && imgW > 0 && imgH > 0) {
                float sx = imgMaxW / imgW;
                float sy = imgMaxH / imgH;
                float s = Math.min(sx, sy);
                drawImgW = imgW * s;
                drawImgH = imgH * s;
            }

            float y = pageH - margin;

            StringBuilder content = new StringBuilder();

            content.append("BT\n");
            content.append("/F1 ").append(fontSizeTitle).append(" Tf\n");
            content.append(margin).append(" ").append(y).append(" Td\n");
            content.append("(").append(pdfEscape(title)).append(") Tj\n");
            content.append("ET\n");

            y -= 28f;

            content.append("BT\n");
            content.append("/F1 ").append(fontSize).append(" Tf\n");
            content.append(margin).append(" ").append(y).append(" Td\n");

            for (String line : lines) {
                content.append("(").append(pdfEscape(line)).append(") Tj\n");
                content.append("0 -14 Td\n");
            }
            content.append("ET\n");

            if (jpg != null) {
                float imgX = margin;
                float imgY = margin;

                content.append("q\n");
                content.append(drawImgW).append(" 0 0 ").append(drawImgH).append(" ")
                        .append(imgX).append(" ").append(imgY).append(" cm\n");
                content.append("/Im1 Do\n");
                content.append("Q\n");
            }

            byte[] contentBytes = content.toString().getBytes("ISO-8859-1");

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);

            class ObjWriter {
                void obj(int id, byte[] objBody) throws IOException {
                    offsets.add(body.size());
                    body.write((id + " 0 obj\n").getBytes("ISO-8859-1"));
                    body.write(objBody);
                    body.write("\nendobj\n".getBytes("ISO-8859-1"));
                }
            }
            ObjWriter w = new ObjWriter();

            w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>".getBytes("ISO-8859-1"));
            w.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes("ISO-8859-1"));

            String pageObj =
                    "<< /Type /Page /Parent 2 0 R " +
                            "/MediaBox [0 0 " + pageW + " " + pageH + "] " +
                            "/Resources << /Font << /F1 5 0 R >> " +
                            (jpg != null ? "/XObject << /Im1 6 0 R >> " : "") +
                            ">> " +
                            "/Contents 4 0 R >>";
            w.obj(3, pageObj.getBytes("ISO-8859-1"));

            ByteArrayOutputStream contentObj = new ByteArrayOutputStream();
            contentObj.write(("<< /Length " + contentBytes.length + " >>\nstream\n").getBytes("ISO-8859-1"));
            contentObj.write(contentBytes);
            contentObj.write("\nendstream".getBytes("ISO-8859-1"));
            w.obj(4, contentObj.toByteArray());

            w.obj(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".getBytes("ISO-8859-1"));

            if (jpg != null) {
                ByteArrayOutputStream imgObj = new ByteArrayOutputStream();
                imgObj.write((
                        "<< /Type /XObject /Subtype /Image " +
                                "/Width " + imgW + " /Height " + imgH + " " +
                                "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                                "/Filter /DCTDecode " +
                                "/Length " + jpg.length + " >>\nstream\n"
                ).getBytes("ISO-8859-1"));
                imgObj.write(jpg);
                imgObj.write("\nendstream".getBytes("ISO-8859-1"));
                w.obj(6, imgObj.toByteArray());
            }

            int xrefPos = body.size();
            StringBuilder xref = new StringBuilder();
            int size = offsets.size();

            xref.append("xref\n");
            xref.append("0 ").append(size).append("\n");
            xref.append(String.format(Locale.US, "%010d 65535 f \n", 0));
            for (int i = 1; i < size; i++) {
                xref.append(String.format(Locale.US, "%010d 00000 n \n", offsets.get(i)));
            }

            String trailer =
                    "trailer\n" +
                            "<< /Size " + size + " /Root 1 0 R >>\n" +
                            "startxref\n" +
                            xrefPos + "\n" +
                            "%%EOF\n";

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write("%PDF-1.4\n".getBytes("ISO-8859-1"));
                fos.write(body.toByteArray());
                fos.write(xref.toString().getBytes("ISO-8859-1"));
                fos.write(trailer.getBytes("ISO-8859-1"));
            }
        }
    }
}

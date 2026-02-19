// ===================== Main.java (same functionality + reduced Hinglish comments) =====================
package org.example;

import javax.swing.*;
import java.io.File;

public class Main {

    // Vehicle type constants (used across app)
    public static final String TYPE_CAR = "car";
    public static final String TYPE_TRUCK = "truck";
    public static final String TYPE_BUS = "bus";

    // SUMO config must be present in working directory
    public static final String SUMOCFG_PATH = "sumo/final.sumocfg";

    // Feature toggle (currently OFF)
    public static final boolean DROP_SECOND_ROUTE = false;

    // Throughput window (last 5 minutes)
    public static final double THROUGHPUT_WINDOW_SEC = 300.0;

    public static void main(String[] args) {
        // 1) Basic setup check (config exists/readable)
        if (!projectIsReady()) return;

        // 2) Boot log
        bootLog();

        // 3) Load data needed before GUI
        initData();

        // 4) Launch UI
        GUI.launch();
    }

    // ===================== Startup checks =====================
    private static boolean projectIsReady() {
        try {
            validateSumoConfig();
            return true;
        } catch (Exception ex) {
            showStartupError(ex.getMessage());
            return false;
        }
    }

    private static void bootLog() {
        Logging.LOG.info("App boot @ " + Logging.nowTag());
    }

    private static void initData() {
        // Load routes/trips for injection + prepare map bounds for rendering
        VehicleInjection.loadTripRoutesFromRou();
        MapVisualisation.initBoundsFromFiles();
    }

    // Validate SUMO config file presence + readability
    private static void validateSumoConfig() throws Exception {
        File cfg = new File(SUMOCFG_PATH);

        if (!cfg.exists()) {
            throw new Exception(
                    "Missing SUMO config file: '" + SUMOCFG_PATH + "'. " +
                            "Place it next to the program (working directory: " + new File(".").getAbsolutePath() + ")"
            );
        }

        if (!cfg.isFile() || !cfg.canRead()) {
            throw new Exception("SUMO config file is not readable: " + cfg.getAbsolutePath());
        }
    }

    // Show startup error in log + popup
    private static void showStartupError(String message) {
        Logging.LOG.severe("Project setup error: " + message);

        JOptionPane.showMessageDialog(
                null,
                message,
                "Project setup error",
                JOptionPane.ERROR_MESSAGE
        );
    }
}
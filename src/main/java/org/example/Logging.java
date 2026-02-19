// ===================== Logging.java (same functionality + reduced Hinglish comments) =====================
package org.example;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

public final class Logging {

    // Global logger (use anywhere: Logging.LOG.info(...))
    public static final Logger LOG = Logger.getLogger("TrafficSim");

    // Class load होते ही init() run
    static { init(); }

    // Utility class => no objects
    private Logging() {}

    // ===================== Setup / Init =====================
    private static void init() {
        try {
            // stderr + stdout same stream (debug easy)
            System.setErr(System.out);

            // Log line format (SimpleFormatter reads this property)
            System.setProperty(
                    "java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT | %4$-7s | %2$s | %3$s | %5$s%6$s%n"
            );

            // Avoid duplicate logs from parent handlers
            LOG.setUseParentHandlers(false);

            // Logger accepts all; handlers decide what to print/store
            LOG.setLevel(Level.ALL);

            // Console: INFO+
            LOG.addHandler(createConsoleHandler());

            // File: ALL (incl. debug)
            LOG.addHandler(createFileHandler());

            LOG.info("Logger ready (console + traffic_sim.log).");

        } catch (Exception e) {
            // If logging setup fails, at least print something
            System.out.println("Logging init failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===================== Handlers =====================

    private static Handler createConsoleHandler() {
        ConsoleHandler h = new ConsoleHandler();
        h.setLevel(Level.INFO);                 // console me INFO/WARNING/SEVERE
        h.setFormatter(new SimpleFormatter());  // uses property format above
        return h;
    }

    private static Handler createFileHandler() throws Exception {
        // filename, maxSize, rotateCount, append
        FileHandler h = new FileHandler("traffic_sim.log", 1_000_000, 3, true);
        h.setLevel(Level.ALL);                  // file me sab capture
        h.setFormatter(new SimpleFormatter());  // same format
        return h;
    }

    // ===================== Utility =====================
    public static String nowTag() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
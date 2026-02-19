// ===================== TrafficControl.java (refactored + reduced Hinglish comments, same behavior) =====================
package org.example;

import org.eclipse.sumo.libtraci.*;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrafficControl {

    // ComboBox me TLS ko readable tarike se show karne ke liye item model
    public static class TlsItem {
        public final String id;   // real SUMO TLS id
        public final String tag;  // human-friendly label

        public TlsItem(String id, String tag) {
            this.id = id;
            this.tag = tag;
        }

        // ComboBox display text
        @Override public String toString() {
            if (tag == null || tag.isBlank() || tag.equals(id)) return id;
            return tag + "  (" + id + ")";
        }
    }

    // GUI refs (dropdown + status label)
    private final JComboBox<TlsItem> tlComboRef;
    private final JLabel tlStateLabel;

    // Cached TLS ids (rule-based scanning ke liye)
    private volatile List<String> tlsIdsCached = new ArrayList<>();
    private volatile String selectedTlsId = null;

    // Manual forcing modes
    public enum ManualTlsMode { NONE, FORCE_RED, FORCE_GREEN }

    // Shared maps: GUI thread + simulation thread touch kar sakte
    private final ConcurrentHashMap<String, ManualTlsMode> manualTlsMode = new ConcurrentHashMap<>();
    private final Map<String, String> manualOriginalPrograms = new ConcurrentHashMap<>();

    // Rule-based enable flag
    private volatile boolean ruleBasedTlsEnabled = false;

    // Rule timings (sim seconds)
    private static final double RULE_STOP_SEC = 6.0;
    private static final double RULE_GO_SEC   = 12.0;

    private enum RulePhase { AUTO, HOLD_RED, HOLD_GREEN }

    // Per-TLS rule state (phase + next switch time)
    private static class RuleState {
        volatile RulePhase phase = RulePhase.AUTO;
        volatile double untilSimTime = -1.0;
    }

    private final ConcurrentHashMap<String, RuleState> ruleStates = new ConcurrentHashMap<>();
    private final Map<String, String> ruleOriginalPrograms = new ConcurrentHashMap<>();
    private final Set<String> ruleTouchedTls = ConcurrentHashMap.newKeySet();

    public TrafficControl(JComboBox<TlsItem> tlComboRef, JLabel tlStateLabel) {
        this.tlComboRef = tlComboRef;
        this.tlStateLabel = tlStateLabel;
    }

    public boolean isRuleBasedTlsEnabled() { return ruleBasedTlsEnabled; }
    public void setSelectedTls(String tlsId) { this.selectedTlsId = tlsId; }

    // Checkbox ON/OFF: OFF => rule-based se touched TLS restore
    public void setRuleBasedTlsEnabled(boolean enabled) {
        this.ruleBasedTlsEnabled = enabled;
        if (!enabled) restoreRuleBasedToAuto();

        SwingUtilities.invokeLater(() ->
                tlStateLabel.setText(enabled ? "Rule-based TLS: ON" : "Rule-based TLS: OFF")
        );
    }

    // SUMO se TLS ids read karke dropdown rebuild
    public void rebuildTrafficLightDropdown() {
        try {
            List<String> ids = readTlsIdsFromSumo();
            tlsIdsCached = new ArrayList<>(ids);

            SwingUtilities.invokeLater(() -> fillDropdown(ids));

        } catch (Exception ex) {
            Logging.LOG.log(java.util.logging.Level.WARNING, "Failed to read traffic light IDs", ex);
            SwingUtilities.invokeLater(() -> {
                tlComboRef.removeAllItems();
                tlComboRef.setEnabled(false);
                tlStateLabel.setText("TL State: error");
            });
        }
    }

    private List<String> readTlsIdsFromSumo() {
        StringVector ids = TrafficLight.getIDList();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) list.add(ids.get(i));
        return list;
    }

    private void fillDropdown(List<String> ids) {
        tlComboRef.removeAllItems();

        // MapVisualisation labels: id -> nice name
        Map<String, String> labels = MapVisualisation.getTlsLabels();

        for (String id : ids) {
            String tag = (labels != null) ? labels.getOrDefault(id, id) : id;
            tlComboRef.addItem(new TlsItem(id, tag));
        }

        tlComboRef.setEnabled(!ids.isEmpty());

        if (!ids.isEmpty()) {
            tlComboRef.setSelectedIndex(0);
            selectedTlsId = ids.get(0);
            tlStateLabel.setText("TL State: ready (" + ids.size() + ")");
        } else {
            selectedTlsId = null;
            tlStateLabel.setText("TL State: none");
        }
    }

    // Har simulation step: pehle rule-based, phir manual override (manual always wins)
    public void applyPerStep(double simTime) {
        applyRuleBasedTls(simTime);
        applyManualOverrideIfNeeded();
    }

    public void forceTrafficLightRed(String tlsId) {
        forceManual(tlsId, ManualTlsMode.FORCE_RED, 'r', "RED");
    }

    public void forceTrafficLightGreen(String tlsId) {
        forceManual(tlsId, ManualTlsMode.FORCE_GREEN, 'G', "GREEN");
    }

    private void forceManual(String tlsId, ManualTlsMode mode, char stateChar, String humanName) {
        try {
            rememberOriginalProgramIfNeeded(manualOriginalPrograms, tlsId);

            manualTlsMode.put(tlsId, mode);
            setTlsAll(tlsId, stateChar);

            SwingUtilities.invokeLater(() ->
                    tlStateLabel.setText("TL " + tlsId + " forced " + humanName + " (persistent)")
            );

        } catch (Exception ex) {
            Logging.LOG.log(java.util.logging.Level.WARNING, "forceManual failed for " + tlsId, ex);
            SwingUtilities.invokeLater(() ->
                    tlStateLabel.setText("TL " + tlsId + " error (" + humanName.toLowerCase(Locale.ROOT) + ")")
            );
        }
    }

    // Manual forced TLS reset => original program restore
    public void resetAllForcedTrafficLights() {
        int ok = 0, fail = 0;
        List<String> ids = new ArrayList<>(manualTlsMode.keySet());

        for (String tlsId : ids) {
            try {
                String prog = safeProgramId(manualOriginalPrograms.get(tlsId));
                TrafficLight.setProgram(tlsId, prog);
                try { TrafficLight.setPhaseDuration(tlsId, 0.0); } catch (Exception ignore) {}

                manualTlsMode.put(tlsId, ManualTlsMode.NONE);
                ok++;

            } catch (Exception ex) {
                fail++;
                Logging.LOG.log(java.util.logging.Level.WARNING, "Reset failed for TLS " + tlsId, ex);
            }
        }

        manualTlsMode.entrySet().removeIf(e -> e.getValue() == ManualTlsMode.NONE);
        manualOriginalPrograms.clear();

        final int okF = ok, failF = fail;
        SwingUtilities.invokeLater(() -> {
            if (okF > 0 && failF == 0) tlStateLabel.setText("TL Reset: all back to NORMAL (" + okF + ")");
            else if (okF > 0) tlStateLabel.setText("TL Reset: normal=" + okF + ", failed=" + failF);
            else tlStateLabel.setText("TL Reset: nothing to reset");
        });
    }

    // Har step pe manual state re-apply (SUMO program override na kar de)
    private void applyManualOverrideIfNeeded() {
        for (Map.Entry<String, ManualTlsMode> e : manualTlsMode.entrySet()) {
            String tlsId = e.getKey();
            ManualTlsMode mode = e.getValue();
            if (mode == null || mode == ManualTlsMode.NONE) continue;

            if (mode == ManualTlsMode.FORCE_RED) {
                setTlsAll(tlsId, 'r');
            } else if (mode == ManualTlsMode.FORCE_GREEN) {
                setTlsAll(tlsId, 'G');
            }
        }
    }

    // Rule-based: demand (stopped vehicles) > 0 => red/green hold cycle
    private void applyRuleBasedTls(double simTime) {
        if (!ruleBasedTlsEnabled) return;
        if (tlsIdsCached == null || tlsIdsCached.isEmpty()) return;

        for (String tlsId : tlsIdsCached) {
            if (tlsId == null || tlsId.isBlank()) continue;

            // Manual priority
            ManualTlsMode mm = manualTlsMode.getOrDefault(tlsId, ManualTlsMode.NONE);
            if (mm != ManualTlsMode.NONE) continue;

            int demand = haltingVehiclesNearTls(tlsId);
            RuleState rs = ruleStates.computeIfAbsent(tlsId, k -> new RuleState());

            // No demand => back to AUTO program
            if (demand <= 0) {
                if (rs.phase != RulePhase.AUTO) {
                    restoreTlsProgram(tlsId, ruleOriginalPrograms);
                    rs.phase = RulePhase.AUTO;
                    rs.untilSimTime = -1.0;
                }
                continue;
            }

            // Demand present => start/continue cycle
            rememberOriginalProgramIfNeeded(ruleOriginalPrograms, tlsId);
            ruleTouchedTls.add(tlsId);

            if (rs.phase == RulePhase.AUTO) {
                rs.phase = RulePhase.HOLD_RED;
                rs.untilSimTime = simTime + RULE_STOP_SEC;
                setTlsAll(tlsId, 'r');
                continue;
            }

            if (rs.untilSimTime > 0 && simTime >= rs.untilSimTime) {
                if (rs.phase == RulePhase.HOLD_RED) {
                    rs.phase = RulePhase.HOLD_GREEN;
                    rs.untilSimTime = simTime + RULE_GO_SEC;
                    setTlsAll(tlsId, 'G');
                } else if (rs.phase == RulePhase.HOLD_GREEN) {
                    rs.phase = RulePhase.HOLD_RED;
                    rs.untilSimTime = simTime + RULE_STOP_SEC;
                    setTlsAll(tlsId, 'r');
                }
            }
        }
    }

    private void restoreRuleBasedToAuto() {
        for (String tlsId : new ArrayList<>(ruleTouchedTls)) {
            restoreTlsProgram(tlsId, ruleOriginalPrograms);
        }
        ruleTouchedTls.clear();
        ruleOriginalPrograms.clear();
        ruleStates.clear();
    }

    private void restoreTlsProgram(String tlsId, Map<String, String> programMap) {
        try {
            String prog = safeProgramId(programMap.get(tlsId));
            TrafficLight.setProgram(tlsId, prog);
            try { TrafficLight.setPhaseDuration(tlsId, 0.0); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
    }

    // Demand estimate: controlled lanes me halting vehicles sum
    private int haltingVehiclesNearTls(String tlsId) {
        StringVector lanes = safeGetControlledLanes(tlsId);
        if (lanes == null || lanes.size() == 0) return 0;

        int sum = 0;
        int any = 0;

        for (int i = 0; i < lanes.size(); i++) {
            String laneId = lanes.get(i);

            Integer h = tryInvokeInt(Lane.class, "getLastStepHaltingNumber",
                    new Class<?>[]{String.class}, new Object[]{laneId});
            if (h != null) { sum += h; any++; continue; }

            Integer v = tryInvokeInt(Lane.class, "getLastStepVehicleNumber",
                    new Class<?>[]{String.class}, new Object[]{laneId});
            if (v != null) { sum += v; any++; }
        }

        return (any == 0) ? 0 : sum;
    }

    // Some SUMO libs may miss method => reflection try
    private StringVector safeGetControlledLanes(String tlsId) {
        try {
            Method m = TrafficLight.class.getMethod("getControlledLanes", String.class);
            Object o = m.invoke(null, tlsId);
            if (o instanceof StringVector) return (StringVector) o;
        } catch (Exception ignore) {}
        return null;
    }

    private static Integer tryInvokeInt(Class<?> clazz, String methodName, Class<?>[] sig, Object[] args) {
        try {
            Method m = clazz.getMethod(methodName, sig);
            Object o = m.invoke(null, args);
            if (o instanceof Number) return ((Number) o).intValue();
        } catch (Exception ignored) {}
        return null;
    }

    // Force TLS state by repeating char for each signal position
    private void setTlsAll(String tlsId, char c) {
        try {
            String current = TrafficLight.getRedYellowGreenState(tlsId);
            if (current == null) return;

            int n = current.length();
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append(c);

            TrafficLight.setRedYellowGreenState(tlsId, sb.toString());
        } catch (Exception ignore) {}
    }

    // Remember original program only once (fallback "0")
    private void rememberOriginalProgramIfNeeded(Map<String, String> store, String tlsId) {
        if (tlsId == null) return;
        if (store.containsKey(tlsId)) return;

        try {
            String prog = TrafficLight.getProgram(tlsId);
            store.put(tlsId, safeProgramId(prog));
        } catch (Exception ex) {
            store.put(tlsId, "0");
        }
    }

    private String safeProgramId(String prog) {
        if (prog == null || prog.isBlank()) return "0";
        return prog;
    }

    // UI label status string (HTML for multiline)
    public String buildTlsStatusString() {
        String tlsShow = selectedTlsId;

        if (tlsShow == null || tlsShow.isBlank()) {
            return ruleBasedTlsEnabled ? "TL: none | RULE=ON" : "TL: none";
        }

        try {
            String ry = TrafficLight.getRedYellowGreenState(tlsShow);

            ManualTlsMode mm = manualTlsMode.getOrDefault(tlsShow, ManualTlsMode.NONE);
            String mmTxt =
                    (mm == ManualTlsMode.NONE) ? "AUTO" :
                            (mm == ManualTlsMode.FORCE_RED ? "FORCED RED" : "FORCED GREEN");

            String rbTxt = ruleBasedTlsEnabled ? "RULE=ON" : "RULE=OFF";

            return "<html>"
                    + "TL: " + tlsShow
                    + "<br>"
                    + ry + " | " + mmTxt + " | " + rbTxt
                    + "</html>";

        } catch (Exception ignore) {
            return "<html>TL: " + tlsShow + "<br>(read error)</html>";
        }
    }
}
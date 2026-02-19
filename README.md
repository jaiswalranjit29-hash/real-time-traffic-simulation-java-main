
# Real-time Traffic Simulation (Java + SUMO)

A Java application that controls a SUMO traffic simulation in real time via TraCI/TraaS.
It provides a GUI to start/stop the simulation, inject vehicles, control traffic lights, and monitor basic metrics.
The project also includes logging and (optional) export functionality depending on the implementation.

---

## Features
- Live SUMO connection (TraCI/TraaS)
- GUI for interaction (start/stop, controls)
- Vehicle injection
- Traffic light control
- Logging of simulation events
- Export of results (CSV/PDF depending on implementation)

---

## Requirements
- Java 17 (or the version configured in `pom.xml`)
- Maven
- SUMO installed and accessible on your system (e.g., `sumo-gui` runs)
- TraCI/TraaS dependencies configured in `pom.xml`

---

## Project Structure
- `src/main/java/org/example/` — Java source code (Maven layout)
- `sumo/` — SUMO configuration files (`final.sumocfg`, `final.net.xml`, `final.rou.xml`)
- `pom.xml` — Maven build configuration
- `milestoneREADME.md` — Milestone 3 submission notes / documentation

---

## How to Run
1. Install SUMO and verify it runs (e.g., `sumo-gui` works).
2. Build the project:
   ```bash
   mvn clean package


# Real-time Traffic Simulation (Java + SUMO)

A Java application that controls a SUMO traffic simulation in real time via TraCI/TraaS.  
It provides a GUI to start/stop the simulation, inject vehicles, control traffic lights, and monitor basic metrics.  
The project also includes logging and (optional) export functionality depending on the implementation.

---

## Features

- Live SUMO connection (TraCI/TraaS / libtraci)
- GUI for interaction (start/stop, controls)
- Vehicle injection
- Traffic light control
- Logging of simulation events
- Export of results (CSV/PDF depending on implementation)

---

## Requirements (What you need installed)

### Required
- **Java 17** (or the version configured in `pom.xml`)
- **Maven**
- **SUMO** installed and usable from the command line (so `sumo-gui` works)

### Recommended
- Use a SUMO version close to the SUMO bindings used in `pom.xml` (reduces native/library mismatch issues).

---

## Project Structure

- `src/main/java/org/example/` — Java source code (Maven layout)
- `sumo/` — SUMO configuration files (`final.sumocfg`, `final.net.xml`, `final.rou.xml`)
- `pom.xml` — Maven build configuration
- `milestoneREADME.md` — Milestone 3 submission notes / documentation

---

## How to Run (Step-by-step for new users)

### Step 1 — Download / Open the project
You should have a folder like this:

- `pom.xml`
- `src/`
- `sumo/`

> IMPORTANT: All commands below must be run from the **project root folder** (the folder containing `pom.xml`).

---

### Step 2 — Open a terminal in the project root

#### Windows
- Open the project folder in File Explorer
- Click the address bar and type `cmd` → press Enter  
  (or Shift + Right Click inside the folder → “Open PowerShell window here”)

#### macOS
- Right-click the project folder → Services → “New Terminal at Folder”  
  (or open Terminal and `cd` into the folder)

#### Linux
- Right-click in the folder → “Open Terminal Here”  
  (or open Terminal and `cd` into the folder)

---

### Step 3 — Verify installs (this confirms setup is correct)

Run these:

```bash
java -version
mvn -v
sumo-gui --version

Expected:

    java -version shows 17.x

    mvn -v prints Maven version

    sumo-gui --version prints SUMO version

If any of these fail, go to the Troubleshooting section below.
Step 4 — Build (downloads dependencies automatically)

mvn clean compile

First time may take a few minutes because Maven downloads libraries.
Step 5 — Run the app (recommended method)

mvn exec:java -Dexec.mainClass="org.example.Main"

What should happen:

    The Java GUI window opens

    When you start simulation from GUI, SUMO GUI should start using:

        sumo/final.sumocfg

Step 6 — What to do in the GUI (basic usage)

    Click Start / Connect (wording may differ)

    SUMO should open

    Use GUI buttons to:

        Start/stop simulation

        Inject vehicles

        Control traffic lights

        View basic metrics/logs

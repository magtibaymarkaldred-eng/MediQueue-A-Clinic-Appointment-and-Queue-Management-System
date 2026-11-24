
# Project Overview

**Project:** MediQueue - Clinic Appointment & Queue System  
**Technology:** Java (JDK 17+) backend on the JDK's built-in HTTP server; HTML, CSS and JavaScript frontend; local text-file storage  
**Application type:** Web application (the original Java Swing desktop version is still included)

MediQueue provides separate sign-in roles for clinic staff and patients. It includes appointment and queue workflows, doctor scheduling, and local data persistence. The web version runs with the JDK alone, without Tomcat, Maven, a database server or third-party libraries.

## Architecture

- **Browser:** `web/index.html`, `web/app.css`, `web/app.js` draw every screen and talk to the server.
- **Web server and JSON API:** `src/MediQueueWeb.java` and `src/Json.java`.
- **Clinic rules:** `src/MediQueueService.java`, moved out of the desktop app's button handlers.
- **Data and storage:** `src/MediQueueData.java`, unchanged from the desktop version.
- **Desktop app:** `src/MediQueue.java`, unchanged.

## Build verification

The source is compiled with `javac --release 17` and packaged into `MediQueueWeb.jar` (web) and `MediQueue.jar` (desktop).

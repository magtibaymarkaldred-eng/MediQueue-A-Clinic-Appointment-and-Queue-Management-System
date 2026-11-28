
# MediQueue Web - Clinic Appointment & Queue System

MediQueue, now running in a web browser. The Java code is the backend: a small web server that comes with the JDK answers the browser, the clinic rules are the same ones the desktop app uses, and the data is saved to the same two text files. No Tomcat, Maven, database or third-party libraries are needed, only the JDK.

## Quick start

1. Install **JDK 17 or newer**. Check it in a terminal with `java -version`.
2. Open a terminal in this folder. In VS Code: **Terminal > New Terminal**.
3. Start the server with `java -jar MediQueueWeb.jar`. On Windows you can also double-click `run.bat` (on macOS/Linux: `sh run.sh`).
4. Open **http://localhost:8080** in your browser.
5. To stop the server, press **Ctrl + C** in the terminal.

## Sign-in accounts

| Role | Username | Password |
|---|---|---|
| Admin (clinic staff) | `aldred` | `aldred06` |
| Patient | `tiffany` | `tiffany012` |
| Patient | `aj` | `aj32` |
| Patient | `stephen` | `glovo456` |

Patients can also create their own account with **New patient? Register**. These demo accounts are written in the source code, so this is a school/demo project, not a production login system.

## How it works

```text
Browser                         Java server (MediQueueWeb.jar)       Saved files
-------                         ------------------------------       -----------
web/index.html                  MediQueueWeb.java                    receives each request
web/app.css    --- request ---> |                                   and checks who is signed in
web/app.js     <---- JSON -----  v
                                MediQueueService.java                 clinic rules: book, cancel,
                                  |                                   call next patient, ...
                                  v
                                MediQueueData.java                   lists + saving
                                  |                                      |
                                  +--------------------------------------+
                                                                         |
                                                                         +--> mediqueue-data.txt
                                                                         +--> mediqueue-users.txt
```

1. The browser loads the page (`index.html`, `app.css`, `app.js`) from the server.
2. `app.js` asks the server for `/api/state`: everything the signed-in user's screens need. Then it draws the page.
3. When you click a button, such as **Book Appointment**, `app.js` sends the form to the server, for example to `/api/book`.
4. `MediQueueService` checks the same rules as the desktop app, and `MediQueueData` saves the change to the text files.
5. The server answers with a message (the pop-up at the bottom of the screen) and the updated data, and the page redraws.

## Project files

| File | What it does | Where it came from |
|---|---|---|
| `src/MediQueueData.java` | Data models (appointments, queue, patients, schedules, accounts), password hashing, saving and loading the text files | Desktop project, **unchanged** |
| `src/MediQueueService.java` | The clinic rules: sign in, register, reset password, book, cancel, confirm, queue, patients, schedules. Same checks and messages as the desktop app | Moved out of the desktop button handlers (`doLogin`, `doRegister`, `book`, ...) |
| `src/MediQueueWeb.java` | The web server: sends the web pages, answers `/api/...` requests with JSON, remembers who is signed in | New |
| `src/Json.java` | Turns Java maps and lists into JSON text for the browser | New |
| `src/MediQueue.java` | The original Swing desktop app | Desktop project, **unchanged** |
| `web/index.html`, `web/app.css`, `web/app.js`, `web/favicon.svg` | The screens in the browser, with the desktop app's colours and layout | New |
| `build.bat`, `build.sh` | Build both `.jar` files | Updated |
| `run.bat`, `run.sh` | Start the web version | New |
| `MediQueueWeb.jar`, `MediQueue.jar` | Ready-to-run web and desktop versions | Built from the files above |

## Building after you change something

- **Changed a `.java` file?** Stop the server, run `build.bat` (Windows) or `sh build.sh` (macOS/Linux), then start it again.
- **Changed a file in `web/`?** Just refresh the browser. When you start the server from this folder, it reads the `web` folder directly. (Started from anywhere else, it uses the copy packed inside the `.jar`.)

Or compile by hand:

```bash
javac --release 17 -encoding UTF-8 -d build src/MediQueueData.java src/MediQueueService.java src/MediQueueWeb.java src/Json.java src/MediQueue.java
java -cp build mediqueue.MediQueueWeb
```

## Options

- **Another port:** `java -jar MediQueueWeb.jar 9090`, then open http://localhost:9090
- **Open it on your phone** (same Wi-Fi): `java -jar MediQueueWeb.jar --lan`. The server prints the address to type on the phone, such as `http://192.168.1.5:8080`. Windows may ask to let Java through the firewall; allow it on private networks. Anyone on that network can then open the site, so use made-up data only.

## The API (the addresses the page uses)

| Address | Who | What it does |
|---|---|---|
| `GET /api/state` | anyone | The signed-in user's screen data (`null` when nobody is signed in) |
| `POST /api/login`, `/api/register`, `/api/reset`, `/api/logout` | anyone | Sign in, create a patient account, reset a password, sign out |
| `POST /api/book` | patient | Book an appointment (`doctor`, `date`, `time`) |
| `POST /api/cancel` | patient (own) or staff (any) | Cancel an appointment (`id`) |
| `POST /api/confirm` | staff | Confirm a scheduled appointment (`id`) |
| `POST /api/queue/next`, `/api/queue/call`, `/api/queue/complete` | staff | Call the next patient, call a patient, finish a consultation |
| `POST /api/patients/save`, `/api/patients/delete` | staff | Add, edit or delete a patient record |
| `POST /api/schedules/add`, `/api/schedules/toggle`, `/api/schedules/remove` | staff | Manage doctor schedules |

POST requests are sent as forms (`name=value&...`) with the header `X-MediQueue: 1`. Answers are JSON, for example `{"ok":true,"message":"Appointment confirmed.","state":{...}}` or `{"error":"That slot is already booked. Pick another time."}`.

## What is different from the desktop version

Same features, rules and messages. A few things changed because anyone can open a website:

- Every choice (doctor, date, time slot) is checked on the server, not only limited by the drop-down lists.
- In the queue, patients see their own full name and other patients as "Juan C." Staff still see full names.
- Registering with the exact name of an existing clinic record requires the contact number on that record. Without this, anyone could register as "Juan Dela Cruz" and see his appointments.
- The queue screens update by themselves every 15 seconds, so patients see the queue move when staff call the next patient.
- A sign-in lasts 12 hours, or until the server restarts. Resetting a password signs that account out everywhere.

## The desktop version is still included

`java -jar MediQueue.jar` starts the original Swing app. It uses the same data files, so you can switch between the two, but do not run both at the same time: each one keeps its own copy of the data in memory, and the last one to save wins.

## Troubleshooting

- **`'java' is not recognized`**: the JDK is not installed, or not on your PATH.
- **`'jar' is not recognized` while building**: the Oracle installer puts only `java` and `javac` on your PATH. Add the JDK's `bin` folder (for example `C:\Program Files\Java\jdk-26\bin\`) to PATH, then open a new terminal.
- **"Port 8080 is already in use"**: another program (or another copy of MediQueue Web) is using it. Close it, or run `java -jar MediQueueWeb.jar 8081`.
- **The build fails on Windows because a file is in use**: stop the server first, then build.
- **The browser shows an old version of a page**: press **Ctrl + F5**.

## Limitations

- The demo accounts above get their passwords reset every time the server starts (see `loadAccounts()` in `MediQueueData.java`).
- No HTTPS. That is fine on your own computer; on a shared network, other people could read the traffic.
- There is no limit on repeated sign-in or password-reset attempts.
- As in the desktop version, nothing adds new patients to the queue; it starts with the four sample patients.
- All data is kept in two text files: fine for a demo, not for a real clinic.

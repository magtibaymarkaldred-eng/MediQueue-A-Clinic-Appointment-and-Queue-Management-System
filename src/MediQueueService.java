package mediqueue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import mediqueue.MediQueueData.Account;
import mediqueue.MediQueueData.Appt;
import mediqueue.MediQueueData.Doctor;
import mediqueue.MediQueueData.Pat;
import mediqueue.MediQueueData.QEntry;
import mediqueue.MediQueueData.Sched;

import static mediqueue.Json.obj;
import static mediqueue.MediQueueData.*;

/**
 * MediQueue's clinic rules, taken out of the desktop screens so the web server can use them.
 *
 * In the desktop app (MediQueue.java) these rules sit inside button handlers such as doLogin(), doRegister()
 * and book(). Here every rule is a plain method: it returns a result, or throws a UserError carrying the same
 * message the desktop app shows. The data itself still lives in MediQueueData (the lists and the two text
 * files), which is used without any changes.
 *
 * Only one request may touch the data at a time, so callers hold the lock on MediQueueData.class
 * (MediQueueWeb does this for every request).
 */
final class MediQueueService {
    private MediQueueService() { }

    /** A problem the user can fix, such as a wrong password or a time slot that is taken. Its message is shown on screen. */
    static final class UserError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;   // the HTTP status code sent to the browser (400 = bad input, 403 = not allowed, ...)
        UserError(String message) { this(400, message); }
        UserError(int status, String message) { super(message); this.status = status; }
    }

    static final String FEE = "₱100.00";
    static final int DAYS_AHEAD = 60;   // the desktop date lists offer the next 60 days
    static final List<String> SLOT_CHOICES = Arrays.asList("9:00 AM - 12:00 PM", "9:00 AM - 3:00 PM", "10:00 AM - 2:00 PM", "1:00 PM - 5:00 PM");
    static final List<String> GENDERS = Arrays.asList("Female", "Male", "Other");
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final String UNKNOWN_USER_SALT = newSalt();

 // =====================================================================
    //  ACCOUNTS  (desktop: doLogin, doRegister, doReset, startSession)
    // =====================================================================

    static Account login(String username, String password, String role) {
        Account a = ACCOUNTS.get(username.trim().toLowerCase());
        // Hash the password even for an unknown username, so a wrong username takes as long as a wrong password.
        String attempt = password.isEmpty() ? "" : hash(password, a != null ? a.salt : UNKNOWN_USER_SALT);
        boolean ok = a != null && a.role.equals(role) && !password.isEmpty() && sameHash(a.hash, attempt);
        if (!ok) throw new UserError("Invalid username or password.");
        startSession(a);
        return a;
    }

    static Account register(String nameIn, String contactIn, String userIn, String pw, String pw2) {
        String name = clean(nameIn, 60), contact = clean(contactIn, 20), user = userIn.trim();
        String problem = null;
        if (name.length() < 2) problem = "Please enter your full name.";
        else if (!contact.matches("[0-9+\\-\\s]{7,15}")) problem = "Enter a valid contact number (7-15 digits).";
        else if (!user.matches("[A-Za-z0-9_.]{3,20}")) problem = "Username: 3-20 letters, numbers, . or _";
        else if (pw.length() < 6) problem = "Password must be at least 6 characters.";
        else if (!pw.equals(pw2)) problem = "Passwords do not match.";
        else if (ACCOUNTS.containsKey(user.toLowerCase())) problem = "That username is already taken.";
        else for (Account x : ACCOUNTS.values()) if (x.name.equalsIgnoreCase(name)) problem = "A patient with that name is already registered.";
        // Web only: the clinic may already have a record with this exact name (added by staff). Anyone can open a
        // website, so the contact number on that record must match before the new account is linked to it.
        Pat record = recordOf(name);
        if (problem == null && record != null && !digits(record.contact).equals(digits(contact)))
            problem = "The clinic already has a record with that name. Use the contact number on file.";
        if (problem != null) throw new UserError(problem);
        Account a = makeAccount(user, "patient", name, contact, pw);
        ACCOUNTS.put(user.toLowerCase(), a);
        saveAccounts();
        startSession(a);
        return a;
    }

    
/** Create a new Admin account. Only an existing Admin can do this. */
static Account createAdmin(
        Account requester,
        String nameIn,
        String contactIn,
        String userIn,
        String pw,
        String pw2) {

    // Verify that the person creating the account is an Admin.
    if (requester == null || !isAdmin(requester)) {
        throw new UserError(403, "Only clinic staff can create Admin accounts.");
    }

    String name = clean(nameIn, 60);
    String contact = clean(contactIn, 20);
    String user = userIn.trim();

    String problem = null;

    if (name.length() < 2) {
        problem = "Please enter the Admin's full name.";
    } else if (!contact.isEmpty()
            && !contact.matches("[0-9+\\-\\s]{7,15}")) {
        problem = "Enter a valid contact number.";
    } else if (!user.matches("[A-Za-z0-9_.]{3,20}")) {
        problem = "Username: 3-20 letters, numbers, . or _";
    } else if (pw.length() < 6) {
        problem = "Password must be at least 6 characters.";
    } else if (!pw.equals(pw2)) {
        problem = "Passwords do not match.";
    } else if (ACCOUNTS.containsKey(user.toLowerCase())) {
        problem = "That username is already taken.";
    }

    if (problem != null) {
        throw new UserError(problem);
    }

    // Generate a salted password hash and create the Admin account.
    Account admin = makeAccount(
        user,
        "admin",
        name,
        contact,
        pw
    );

    ACCOUNTS.put(user.toLowerCase(), admin);

    // Save the new Admin account.
    saveAccounts();

    return admin;
}


    /** Desktop doReset(): a patient proves who they are with the contact number they registered with. */
    static Account resetPassword(String userIn, String contactIn, String pw, String pw2) {
        Account a = ACCOUNTS.get(userIn.trim().toLowerCase());
        if (a == null || !a.role.equals("patient") || a.contact.isEmpty() || !digits(a.contact).equals(digits(contactIn)))
            throw new UserError("We could not verify that username and number.");
        if (pw.length() < 6) throw new UserError("Password must be at least 6 characters.");
        if (!pw.equals(pw2)) throw new UserError("Passwords do not match.");
        Account updated = makeAccount(a.username, a.role, a.name, a.contact, pw);
        ACCOUNTS.put(a.username.toLowerCase(), updated);
        saveAccounts();
        return updated;
    }

    /** Desktop startSession(): a patient's first sign-in also creates their clinic record. */
    static void startSession(Account a) {
        if (a.role.equals("patient") && recordOf(a.name) == null) {
            PATS.add(pat(nextPatId(), a.name, "", "", a.contact, "-"));
            saveData();
        }
    }
 // =====================================================================
    //  PATIENT ACTIONS  (desktop: book, cancelDialog)
    // =====================================================================

    static Appt book(Account who, String doctorName, String dateText, String timeText) {
        Doctor d = doctor(doctorName);
        if (d == null) throw new UserError("Please choose a doctor.");
        // The desktop lists only allowed these choices; a website must check them because anyone can send anything.
        LocalDate date = toDate(dateText);
        if (!withinNextDays(date)) throw new UserError("Please choose a date from the list.");
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) throw new UserError("The clinic is closed on Sundays.");
        LocalTime time = toTime(timeText);
        if (time == null || !allSlots().contains(time)) throw new UserError("Please choose a time slot from the list.");
        // The same three checks as the desktop book() method.
        if (date.equals(LocalDate.now()) && !time.isAfter(LocalTime.now())) throw new UserError("That time has already passed today.");
        for (Appt a : APPTS)
            if (a.doc.equals(d.name) && a.date.equals(date) && a.time.equals(time) && !a.status.equals("Cancelled"))
                throw new UserError("That slot is already booked. Pick another time.");
        for (Sched s : SCHEDS)
            if (s.doc.equals(d.name) && s.date.equals(date) && !s.ok) throw new UserError(d.name + " is not available on that date.");
        Appt a = appt(who.name, d.name, date, time.toString(), "Scheduled");
        APPTS.add(a);
        saveData();
        return a;
    }

    static Appt cancel(Account who, String id) {
    Appt a = findAppt(id);

    if (a == null || !(isAdmin(who) || a.patient.equals(who.name))) {
        throw new UserError(404, "Appointment not found.");
    }

    if (!live(a)) {
        throw new UserError("This appointment can no longer be cancelled.");
    }

    a.status = "Cancelled";

    // Update the matching queue entry.
    for (QEntry q : QUEUE) {
        if (q.name.equals(a.patient)
                && q.doc.equals(a.doc)
                && q.date.equals(a.date)
                && q.time.equals(a.time)) {
            q.status = "Cancelled";
        }
    }

    saveData();
    return a;
}
// =====================================================================
    //  CLINIC STAFF ACTIONS  (desktop: wAppMgmt, wQueueMgmt, patientDialog, wSchedMgmt, scheduleDialog)
    // =====================================================================

    static Appt confirm(String id) {
        Appt a = findAppt(id);
        if (a == null) throw new UserError(404, "Appointment not found.");
        if (!a.status.equals("Scheduled") && !a.status.equals("Upcoming")) throw new UserError("Only scheduled appointments can be confirmed.");
        a.status = "Confirmed";
        saveData();
        return a;
    }

       
    /** Returns today's appointments in appointment-time order, excluding cancelled appointments. */
    private static List<Appt> todayQueue() {
        List<Appt> result = new ArrayList<>();

        for (Appt a : sortedAppts(APPTS)) {
            if (a.date.equals(LocalDate.now())
                    && !a.status.equalsIgnoreCase("Cancelled")) {
                result.add(a);
            }
        }

        return result;
    }

    /** Appointment statuses that are allowed to be called. */
    private static boolean canBeCalled(Appt a) {
        return a.status.equalsIgnoreCase("Scheduled")
                || a.status.equalsIgnoreCase("Upcoming")
                || a.status.equalsIgnoreCase("Confirmed")
                || a.status.equalsIgnoreCase("Waiting")
                || a.status.equalsIgnoreCase("Next");
    }

    /** Checks whether this doctor already has a patient in consultation today. */
    private static boolean doctorIsBusy(String doctorName) {
        for (Appt a : APPTS) {
            if (a.date.equals(LocalDate.now())
                    && a.doc.equalsIgnoreCase(doctorName)
                    && a.status.equalsIgnoreCase("In Consultation")) {
                return true;
            }
        }

        return false;
    }

    /** Calls the earliest eligible patient whose doctor is available. */
    
static QEntry callNext() {
    Appt next = null;

    for (Appt a : todayQueue()) {
        if (canBeCalled(a) && !doctorIsBusy(a.doc)) {
            next = a;
            break;
        }
    }

    if (next == null) {
        throw new UserError("No available patients are waiting.");
    }

    next.status = "In Consultation";
    saveData();

    return new QEntry(next.patient, next.doc, next.status, next.date, next.time);
}

static QEntry callPatient(int index, String name) {
    Appt a = queueAppointment(index, name);

    if (!canBeCalled(a)) {
        throw new UserError(a.patient + " cannot be called right now.");
    }

    if (doctorIsBusy(a.doc)) {
        throw new UserError(
            a.doc + " already has a patient in consultation."
        );
    }

    Appt next = null;

    for (Appt candidate : todayQueue()) {
        if (canBeCalled(candidate) && !doctorIsBusy(candidate.doc)) {
            next = candidate;
            break;
        }
    }

    if (next == null) {
        throw new UserError("No available patients are waiting.");
    }

    if (next != a) {
        throw new UserError(
            "Please call the next available patient first: "
            + next.patient + "."
        );
    }

    a.status = "In Consultation";
    saveData();

    return new QEntry(a.patient, a.doc, a.status, a.date, a.time);
}


    /** Marks the selected appointment as completed. */
static QEntry completePatient(int index, String name) {
    Appt a = queueAppointment(index, name);

    if (!a.status.equalsIgnoreCase("In Consultation")) {
        throw new UserError(a.patient + " is not in consultation.");
    }

    a.status = "Completed";

    // Update the matching queue entry.
    for (QEntry q : QUEUE) {
        if (q.name.equals(a.patient)
                && q.doc.equals(a.doc)
                && q.date.equals(a.date)
                && q.time.equals(a.time)) {
            q.status = "Completed";
        }
    }

    saveData();

    return new QEntry(a.patient, a.doc, a.status, a.date, a.time);
}

    /** Validates the queue row index and patient name. */
    private static Appt queueAppointment(int index, String name) {
        List<Appt> rows = todayQueue();

        if (index < 0 || index >= rows.size()) {
            throw new UserError("The queue has changed. Please refresh and try again.");
        }

        Appt a = rows.get(index);

        if (!a.patient.equals(name)) {
            throw new UserError("The queue has changed. Please refresh and try again.");
        }

        return a;
    }

    /** Converts appointment statuses into queue display statuses. */
    private static String queueDisplayStatus(Appt a) {
        if (a.status.equalsIgnoreCase("Scheduled")
                || a.status.equalsIgnoreCase("Upcoming")
                || a.status.equalsIgnoreCase("Confirmed")) {
            return "Waiting";
        }

        return a.status;
    }
    

    /** Add Patient (when id is empty) or Edit Patient, with the same checks as the desktop patient dialog. */
    static Pat savePatient(String id, String nameIn, String ageIn, String gender, String contactIn) {
        String n = clean(nameIn, 60), c = clean(contactIn, 20), a = ageIn.trim();
        if (n.isEmpty()) throw new UserError("Please enter the patient's name.");
        if (!a.matches("\\d{1,3}") || Integer.parseInt(a) > 120) throw new UserError("Please enter a valid age (0-120).");
        if (!c.matches("[0-9+\\-\\s]{7,15}")) throw new UserError("Please enter a valid contact number.");
        if (!GENDERS.contains(gender)) throw new UserError("Please choose a gender.");
        Pat p;
        if (id.isEmpty()) {
            p = pat(nextPatId(), n, a, "", c, "-");
            PATS.add(p);
        } else {
            p = findPat(id);
            if (p == null) throw new UserError(404, "Patient not found.");
        }
        p.name = n;
        p.age = a;
        p.contact = c;
        p.gender = gender;
        saveData();
        return p;
    }

    static Pat deletePatient(String id) {
        Pat p = findPat(id);
        if (p == null) throw new UserError(404, "Patient not found.");
        PATS.remove(p);
        saveData();
        return p;
    }

    static Sched addSchedule(String doctorName, String dateText, String slots) {
        Doctor d = doctor(doctorName);
        if (d == null) throw new UserError("Please choose a doctor.");
        LocalDate date = toDate(dateText);
        if (!withinNextDays(date)) throw new UserError("Please choose a date from the list.");
        if (!SLOT_CHOICES.contains(slots)) throw new UserError("Please choose an available time.");
        Sched s = sched(d.name, date, slots, true);
        SCHEDS.add(s);
        saveData();
        return s;
    }

    static Sched toggleSchedule(int id) {
        Sched s = findSched(id);
        s.ok = !s.ok;
        saveData();
        return s;
    }

    static Sched removeSchedule(int id) {
        Sched s = findSched(id);
        SCHEDS.remove(s);
        saveData();
        return s;
    }
 // =====================================================================
    //  SCREEN DATA  (desktop: the w...() and stat() methods that fill each page)
    // =====================================================================

    /** Everything the signed-in user's pages need. The web server sends this to the browser as JSON. */
    static Map<String, Object> stateFor(Account who) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        Map<String, Object> s = obj(
                "user", obj("name", who.name, "firstName", firstName(who.name), "username", who.username,
                            "role", who.role, "roleLabel", isAdmin(who) ? "Clinic Staff" : "Patient"),
                "now", obj("date", today.toString(), "dateLabel", today.format(DATE_FMT), "time", now.format(CLOCK), "hour", now.getHour()),
                "fee", FEE,
                "doctors", doctorList());
        if (isAdmin(who)) addStaffData(s, today);
        else addPatientData(s, who, today);
        return s;
    }

    private static void addPatientData(Map<String, Object> s, Account who, LocalDate today) {
        List<Appt> mine = appointmentsOf(who.name);
        Appt upcoming = null;
        for (Appt a : mine) if (live(a) && !a.date.isBefore(today)) { upcoming = a; break; }
        Pat record = recordOf(who.name);
        int myNumber = queueNumberOf(who.name);

        List<Appt> queue = todayQueue();
List<Object> rows = new ArrayList<>();

for (int i = 0; i < queue.size(); i++) {
    Appt a = queue.get(i);
    boolean mineRow = a.patient.equals(who.name);

    rows.add(obj(
        "no", i + 1,
        "name", mineRow ? a.patient : shortName(a.patient),
        "doctor", a.doc,
        "time", a.time.toString(),
        "timeLabel", a.time.format(TIME_FMT),
        "status", queueDisplayStatus(a),
        "mine", mineRow
    ));
}

        List<Object> doctorChoices = new ArrayList<>(), dates = new ArrayList<>(), times = new ArrayList<>();
        for (Doctor d : DOCTORS) doctorChoices.add(obj("value", d.name, "label", d.name + " - " + d.spec));
        for (int i = 0; i < DAYS_AHEAD; i++) {
            LocalDate d = today.plusDays(i);
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(obj("value", d.toString(), "label", d.format(DAY_FMT)));
        }
        for (LocalTime t : allSlots()) times.add(obj("value", t.toString(), "label", t.format(TIME_FMT)));

        s.put("profile", obj("name", who.name, "patientId", record == null ? "-" : record.id,
                             "contact", record == null || record.contact.isEmpty() ? "-" : record.contact));
        s.put("appointments", appointmentList(mine));
        s.put("upcoming", upcoming == null ? null
                : obj("date", upcoming.date.toString(), "dateLabel", upcoming.date.format(DATE_FMT), "doctor", upcoming.doc));
        s.put("queue", obj("myNumber", myNumber, "rows", rows));
        s.put("booking", obj("doctors", doctorChoices, "dates", dates, "times", times));
    }

    private static void addStaffData(Map<String, Object> s, LocalDate today) {
        
List<Appt> queue = todayQueue();

int current = 0;
int waiting = 0;
int inConsultation = 0;
int completed = 0;

for (int i = 0; i < queue.size(); i++) {
    Appt a = queue.get(i);

    if (a.status.equalsIgnoreCase("In Consultation")) {
        current = i + 1;
        inConsultation++;
    } else if (canBeCalled(a)) {
        waiting++;
    } else if (a.status.equalsIgnoreCase("Completed")) {
        completed++;
    }
}

int onDuty = 0;

for (Doctor d : DOCTORS) {
    for (Sched x : SCHEDS) {
        if (x.doc.equals(d.name) && x.date.equals(today) && x.ok) {
            onDuty++;
            break;
        }
    }
}

int active = 0;
int cancelled = 0;

for (Appt a : APPTS) {
    if (live(a)) active++;
    if (a.status.equalsIgnoreCase("Cancelled")) cancelled++;
}

s.put("stats", obj(
    "appointments", APPTS.size(),
    "patients", PATS.size(),
    "currentQueue", current,
    "onDuty", onDuty,
    "doctorCount", DOCTORS.length
));

s.put("summary", obj(
    "active", active,
    "inQueue", waiting,
    "completed", completed,
    "cancelled", cancelled
));

s.put("counters", obj(
    "waiting", waiting,
    "inConsultation", inConsultation,
    "completed", completed
));

s.put("appointments", appointmentList(sortedAppts(APPTS)));

        List<Object> rows = new ArrayList<>();

for (int i = 0; i < queue.size(); i++) {
    Appt a = queue.get(i);

    rows.add(obj(
        "index", i,
        "no", String.format("Q%03d", i + 1),
        "name", a.patient,
        "doctor", a.doc,
        "status", queueDisplayStatus(a)
    ));
}

s.put("queue", obj("rows", rows));
        List<Object> patients = new ArrayList<>();
        for (Pat p : PATS)
            patients.add(obj("id", p.id, "name", p.name, "age", p.age, "gender", p.gender, "contact", p.contact, "visit", p.visit));
        s.put("patients", patients);

        List<Sched> byDate = new ArrayList<>(SCHEDS);
        byDate.sort(Comparator.comparing((Sched x) -> x.date));
        List<Object> schedules = new ArrayList<>();
        for (Sched x : byDate)
            schedules.add(obj("id", x.id, "doctor", x.doc, "date", x.date.toString(), "dateLabel", x.date.format(DATE_FMT),
                              "day", x.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), "slots", x.slots, "ok", x.ok));
        s.put("schedules", schedules);

        List<Object> dates = new ArrayList<>();
        for (int i = 0; i < DAYS_AHEAD; i++) {
            LocalDate d = today.plusDays(i);
            dates.add(obj("value", d.toString(), "label", d.format(DAY_FMT)));
        }
        s.put("scheduleOptions", obj("dates", dates, "slots", SLOT_CHOICES));
    }

    private static List<Object> appointmentList(List<Appt> appts) {
        List<Object> out = new ArrayList<>();
        for (Appt a : appts)
            out.add(obj("id", a.id, "patient", a.patient, "doctor", a.doc, "spec", spec(a.doc),
                        "date", a.date.toString(), "dateLabel", a.date.format(DATE_FMT),
                        "month", a.date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH),
                        "day", String.format("%02d", a.date.getDayOfMonth()),
                        "time", a.time.toString(), "timeLabel", a.time.format(TIME_FMT), "status", a.status,
                        "live", live(a), "canConfirm", a.status.equals("Scheduled") || a.status.equals("Upcoming")));
        return out;
    }

    private static List<Object> doctorList() {
        List<Object> out = new ArrayList<>();
        for (Doctor d : DOCTORS) out.add(obj("name", d.name, "spec", d.spec, "hours", d.hours));
        return out;
    }
 // =====================================================================
    //  SMALL HELPERS
    // =====================================================================

    static boolean isAdmin(Account a) { return a.role.equals("admin"); }
    static String firstName(String name) { return name.split(" ")[0]; }
    static String digits(String s) { return s.replaceAll("[^0-9+]", ""); }

    /** "Juan Dela Cruz" becomes "Juan C." */
    static String shortName(String name) {
        String[] w = name.trim().split("\\s+");
        return w.length < 2 ? w[0] : w[0] + " " + w[w.length - 1].charAt(0) + ".";
    }

    static Doctor doctor(String name) {
        for (Doctor d : DOCTORS) if (d.name.equals(name)) return d;
        return null;
    }

    static Appt findAppt(String id) {
        for (Appt a : APPTS) if (a.id.equals(id)) return a;
        return null;
    }

    static Pat findPat(String id) {
        for (Pat p : PATS) if (p.id.equals(id)) return p;
        return null;
    }

    static Sched findSched(int id) {
        for (Sched s : SCHEDS) if (s.id == id) return s;
        throw new UserError(404, "Schedule not found.");
    }

    /** The clinic record for a name (the last match, like the desktop Profile page). */
    static Pat recordOf(String name) {
        Pat found = null;
        for (Pat p : PATS) if (p.name.equals(name)) found = p;
        return found;
    }

    static List<Appt> appointmentsOf(String name) {
        List<Appt> out = new ArrayList<>();
        for (Appt a : sortedAppts(APPTS)) if (a.patient.equals(name)) out.add(a);
        return out;
    }

    static int queueNumberOf(String name) {
    List<Appt> queue = todayQueue();

    for (int i = 0; i < queue.size(); i++) {
        if (queue.get(i).patient.equals(name)) {
            return i + 1;
        }
    }

    return 0;
}
    /** True for today and the next 59 days, the same range the desktop date lists show. */
    static boolean withinNextDays(LocalDate d) {
        LocalDate today = LocalDate.now();
        return d != null && !d.isBefore(today) && d.isBefore(today.plusDays(DAYS_AHEAD));
    }

    static LocalDate toDate(String s) {
        try { return LocalDate.parse(s); } catch (RuntimeException e) { return null; }
    }

    static LocalTime toTime(String s) {
        try { return LocalTime.parse(s); } catch (RuntimeException e) { return null; }
    }

    static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return -1; }
    } 
}
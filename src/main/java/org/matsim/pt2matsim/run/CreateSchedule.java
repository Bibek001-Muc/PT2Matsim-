package org.matsim.pt2matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt2matsim.gtfs.GtfsConverter;
import org.matsim.pt2matsim.tools.ScheduleTools;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 2: read the harmonized GTFS feed and write a complete
 * <b>7-day</b> MATSim transit schedule + matching vehicles file.
 *
 * <p>Pipeline (all in this single class):</p>
 * <ol>
 *   <li>Unzip {@code ../gtfs_merge/output/munich_merged.gtfs.zip} to a
 *       working folder. pt2matsim's {@link Gtfs2TransitSchedule} requires
 *       a folder, not a zip.</li>
 *   <li>Run {@link Gtfs2TransitSchedule#run} with
 *       {@link GtfsConverter#ALL_SERVICE_IDS}. This emits a single
 *       virtual-day schedule (because MATSim's TransitSchedule v2 format
 *       has no day-of-week concept; pt2matsim emits one departure per
 *       GTFS trip_id at its native time, regardless of how many days the
 *       trip's service_id covers).</li>
 *   <li>Read the merged GTFS {@code calendar.txt} +
 *       {@code calendar_dates.txt} to build {@code trip_id -> active
 *       weekdays}. Honours the weekly monday..sunday mask and per-date
 *       exceptions ({@code exception_type} 1 / 2). Mon=0 ... Sun=6.</li>
 *   <li><b>Expand</b> the schedule and vehicles in-memory: for each
 *       Departure replace it with one replica per active weekday,
 *       setting {@code departureTime = original + weekday * 86400 s}
 *       and assigning a fresh per-day vehicleId
 *       ({@code <orig>_d<wd>}). Replica Vehicles inherit the original's
 *       VehicleType. Original Departures and now-unreferenced Vehicles
 *       are removed.</li>
 *   <li>Write the expanded schedule to {@code output/MunichSchedule.xml}
 *       (and the expanded vehicles to {@code output/MunichVehicles.xml}).
 *       The 1-day intermediate is kept at
 *       {@code output/MunichSchedule_1day.xml} for debugging.</li>
 * </ol>
 *
 * <p>Output time range spans 0-168 h (with overflow up to about 172 h
 * for Sunday-night trips that continue past Monday 00:00). MATSim
 * handles &gt;24 h times natively.</p>
 *
 * <p>Result: ~2.5-3x as many departures as the 1-day schedule
 * (depending on how many MVV trips have weekday-only T0 service vs.
 * weekend T2/T3 service). Verified on the Nov 6-12 2023 merged feed:
 * 75,415 -&gt; 214,406 departures, Mon-Sun all populated.</p>
 */
public class CreateSchedule {

    /** Path to the harmonized GTFS zip produced by gtfs_merge. */
    public static final String GTFS_ZIP = "../gtfs_merge/output/munich_merged.gtfs.zip";

    /** Where the zip is unpacked before being read by Gtfs2TransitSchedule. */
    public static final String GTFS_UNPACKED_DIR = "output/gtfs_unpacked";

    /** 1-day intermediate emitted by pt2matsim (kept for debugging). */
    public static final String SCHEDULE_1DAY = "output/MunichSchedule_1day.xml";
    public static final String VEHICLES_1DAY = "output/MunichVehicles_1day.xml";

    /** Final 7-day outputs consumed by MapSchedule2Network. */
    public static final String SCHEDULE_OUT = "output/MunichSchedule.xml";
    public static final String VEHICLES_OUT = "output/MunichVehicles.xml";

    /** Trailing "_HH:MM:SS" suffix that pt2matsim appends to departure ids. */
    private static final Pattern TIME_SUFFIX =
            Pattern.compile("_\\d{2}:\\d{2}:\\d{2}$");

    private static final DateTimeFormatter YYYYMMDD =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** GTFS calendar.txt column order Mon..Sun -> 0..6. */
    private static final String[] WEEKDAY_COLS = {
            "monday", "tuesday", "wednesday", "thursday",
            "friday", "saturday", "sunday"
    };

    public static void main(String[] args) throws IOException {
        long t0 = System.currentTimeMillis();

        Path zip = Paths.get(GTFS_ZIP).toAbsolutePath().normalize();
        if (!Files.exists(zip)) {
            throw new IllegalArgumentException(
                    "GTFS zip not found: " + zip
                            + "\n  Run gtfs_merge first, or update GTFS_ZIP path.");
        }
        Path unpacked = Paths.get(GTFS_UNPACKED_DIR).toAbsolutePath().normalize();
        unzipIfStale(zip, unpacked);
        Files.createDirectories(Paths.get("output"));

        // -----------------------------------------------------------------
        // 1. Run pt2matsim's GTFS converter to produce the 1-day intermediate
        // -----------------------------------------------------------------
        System.out.println("[CreateSchedule] GTFS folder       : " + unpacked);
        System.out.println("[CreateSchedule] sample day        : ALL_SERVICE_IDS");
        System.out.println("[CreateSchedule] CRS               : DHDN_GK4");
        System.out.println("[CreateSchedule] 1-day schedule    : " + SCHEDULE_1DAY);
        System.out.println("[CreateSchedule] 1-day vehicles    : " + VEHICLES_1DAY);

        Gtfs2TransitSchedule.run(
                unpacked.toString(),
                GtfsConverter.ALL_SERVICE_IDS,
                TransformationFactory.DHDN_GK4,
                SCHEDULE_1DAY,
                VEHICLES_1DAY,
                null
        );
        System.out.println("[CreateSchedule] Gtfs2TransitSchedule done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");

        // -----------------------------------------------------------------
        // 2. Build trip_id -> active weekdays from the merged GTFS
        // -----------------------------------------------------------------
        Map<String, Set<Integer>> tripDays = loadTripToActiveWeekdays(unpacked);
        System.out.println("[CreateSchedule] trip->weekdays map: "
                + tripDays.size() + " trips");

        // -----------------------------------------------------------------
        // 3. Expand the schedule + vehicles in memory
        // -----------------------------------------------------------------
        expandSchedule(SCHEDULE_1DAY, VEHICLES_1DAY,
                SCHEDULE_OUT, VEHICLES_OUT, tripDays);

        System.out.println("[CreateSchedule] 7-day schedule    : " + SCHEDULE_OUT);
        System.out.println("[CreateSchedule] 7-day vehicles    : " + VEHICLES_OUT);
        System.out.println("[CreateSchedule] total time        : "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    // =====================================================================
    //  Unzip the merged GTFS
    // =====================================================================
    private static void unzipIfStale(Path zip, Path dest) throws IOException {
        boolean fresh = Files.isDirectory(dest)
                && Files.getLastModifiedTime(dest).toMillis()
                   >= Files.getLastModifiedTime(zip).toMillis();
        if (fresh) {
            System.out.println("[CreateSchedule] reusing unpacked feed at " + dest);
            return;
        }
        Files.createDirectories(dest);
        System.out.println("[CreateSchedule] unzipping " + zip + " -> " + dest);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("zip-slip detected: " + e.getName());
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e);
                     BufferedOutputStream bo = new BufferedOutputStream(
                             new FileOutputStream(out.toFile()))) {
                    in.transferTo(bo);
                }
            }
        }
        Files.setLastModifiedTime(dest, Files.getLastModifiedTime(zip));
    }

    // =====================================================================
    //  trip_id -> active weekdays  (from calendar.txt + calendar_dates.txt)
    // =====================================================================
    private static Map<String, Set<Integer>> loadTripToActiveWeekdays(Path unpacked)
            throws IOException {
        List<Map<String, String>> trips = readCsv(unpacked.resolve("trips.txt"));
        List<Map<String, String>> cal   = readCsv(unpacked.resolve("calendar.txt"));
        List<Map<String, String>> cd    = readCsv(unpacked.resolve("calendar_dates.txt"));

        // service_id -> set of active weekday indices (0=Mon..6=Sun)
        Map<String, Set<Integer>> svcDays = new HashMap<>();
        for (Map<String, String> row : cal) {
            String sid = row.get("service_id");
            Set<Integer> days = new HashSet<>();
            for (int wd = 0; wd < 7; wd++) {
                if ("1".equals(strip(row.get(WEEKDAY_COLS[wd])))) {
                    days.add(wd);
                }
            }
            if (!days.isEmpty()) {
                svcDays.merge(sid, days, (a, b) -> { a.addAll(b); return a; });
            }
        }

        // Determine the merged-feed's clip window (used to scope exceptions).
        LocalDate winStart = LocalDate.MIN, winEnd = LocalDate.MAX;
        if (!cal.isEmpty()) {
            try {
                winStart = LocalDate.parse(cal.get(0).get("start_date"), YYYYMMDD);
                winEnd   = LocalDate.parse(cal.get(0).get("end_date"),   YYYYMMDD);
            } catch (Exception ignore) { /* leave wide-open */ }
        }

        for (Map<String, String> row : cd) {
            String sid = row.get("service_id");
            String dateStr = row.get("date");
            String ex = strip(row.get("exception_type"));
            LocalDate d;
            try {
                d = LocalDate.parse(dateStr, YYYYMMDD);
            } catch (Exception e) {
                continue;
            }
            if (d.isBefore(winStart) || d.isAfter(winEnd)) continue;
            int wd = d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
            Set<Integer> days = svcDays.computeIfAbsent(sid, k -> new HashSet<>());
            if ("1".equals(ex))      days.add(wd);
            else if ("2".equals(ex)) days.remove(wd);
        }

        // Project to trip_id
        Map<String, Set<Integer>> out = new HashMap<>(trips.size() * 2);
        for (Map<String, String> row : trips) {
            String tid = row.get("trip_id");
            String sid = row.get("service_id");
            Set<Integer> days = svcDays.get(sid);
            if (tid != null && days != null && !days.isEmpty()) {
                out.put(tid, days);
            }
        }
        return out;
    }

    private static List<Map<String, String>> readCsv(Path file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!Files.exists(file)) return rows;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) return rows;
            if (headerLine.length() > 0 && headerLine.charAt(0) == '﻿') {
                headerLine = headerLine.substring(1);
            }
            String[] header = splitCsv(headerLine);
            String line;
            while ((line = br.readLine()) != null) {
                String[] fields = splitCsv(line);
                Map<String, String> row = new HashMap<>(header.length * 2);
                for (int i = 0; i < header.length && i < fields.length; i++) {
                    row.put(header[i].trim(), fields[i]);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Minimal RFC-4180 split (handles double-quoted fields with commas). */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else {
                        inQuote = false;
                    }
                } else cur.append(c);
            } else {
                if (c == ',') {
                    out.add(cur.toString()); cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuote = true;
                } else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^\"|\"$", "");
    }

    // =====================================================================
    //  Schedule + Vehicles expansion (MATSim API, in-memory)
    // =====================================================================
    private static void expandSchedule(String inSchedule, String inVehicles,
                                       String outSchedule, String outVehicles,
                                       Map<String, Set<Integer>> tripDays) {
        System.out.println("[expand] reading schedule " + inSchedule);
        TransitSchedule schedule = ScheduleTools.readTransitSchedule(inSchedule);
        Vehicles vehicles = ScheduleTools.readVehicles(inVehicles);

        TransitScheduleFactory sf = schedule.getFactory();
        VehiclesFactory vf = vehicles.getFactory();

        long inDep = 0, outDep = 0, fallback = 0;
        TreeMap<Integer, Long> perDay = new TreeMap<>();
        Set<Id<Vehicle>> origVehiclesUsed = new HashSet<>();
        Set<Id<Vehicle>> newVehicleIds = new LinkedHashSet<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                List<Departure> originals = new ArrayList<>(route.getDepartures().values());
                for (Departure orig : originals) {
                    inDep++;
                    String depId = orig.getId().toString();
                    String tripId = extractTripId(depId);

                    Set<Integer> days = tripDays.get(tripId);
                    if (days == null) {
                        // Try the full dep_id (rare, but pt2matsim may already
                        // strip the time suffix on some routes).
                        days = tripDays.get(depId);
                    }
                    if (days == null || days.isEmpty()) {
                        fallback++;
                        days = new HashSet<>(Arrays.asList(0)); // emit on Monday
                    }

                    Id<Vehicle> origVehId = orig.getVehicleId();
                    Vehicle origVeh = vehicles.getVehicles().get(origVehId);
                    origVehiclesUsed.add(origVehId);

                    // Remove the original 1-day departure first; we will add
                    // weekday-suffixed replicas below.
                    route.removeDeparture(orig);

                    for (int wd : days) {
                        double newTime = orig.getDepartureTime() + wd * 86400.0;
                        Id<Departure> newDepId = Id.create(depId + "_d" + wd,
                                Departure.class);
                        Id<Vehicle> newVehId = Id.create(origVehId.toString() + "_d" + wd,
                                Vehicle.class);
                        Departure d = sf.createDeparture(newDepId, newTime);
                        d.setVehicleId(newVehId);
                        route.addDeparture(d);

                        if (origVeh != null && !vehicles.getVehicles().containsKey(newVehId)) {
                            vehicles.addVehicle(vf.createVehicle(newVehId, origVeh.getType()));
                            newVehicleIds.add(newVehId);
                        }

                        outDep++;
                        perDay.merge(wd, 1L, Long::sum);
                    }
                }
            }
        }

        // Drop now-unreferenced original vehicles (each was replaced by replicas).
        for (Id<Vehicle> vid : origVehiclesUsed) {
            if (vehicles.getVehicles().containsKey(vid)) {
                vehicles.removeVehicle(vid);
            }
        }

        ScheduleTools.writeTransitSchedule(schedule, outSchedule);
        ScheduleTools.writeVehicles(vehicles, outVehicles);

        System.out.println("[expand] departures: in=" + inDep + "  out=" + outDep
                + "  expansion=" + String.format(java.util.Locale.ROOT, "%.2fx",
                outDep / (double) Math.max(inDep, 1))
                + "  fallback-to-Mon=" + fallback);
        StringBuilder perDayStr = new StringBuilder();
        String[] label = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (Map.Entry<Integer, Long> e : perDay.entrySet()) {
            int wd = e.getKey();
            String name = wd >= 0 && wd < 7 ? label[wd] : "d" + wd;
            perDayStr.append(' ').append(name).append('=').append(e.getValue());
        }
        System.out.println("[expand] per weekday:" + perDayStr);
        System.out.println("[expand] vehicles in file: " + vehicles.getVehicles().size()
                + " (added " + newVehicleIds.size() + " replicas, "
                + "removed " + origVehiclesUsed.size() + " originals)");
    }

    /**
     * pt2matsim writes a Departure id as {@code <trip_id>_<HH:MM:SS>}.
     * Strip the trailing time suffix exactly once to recover the GTFS
     * trip_id. {@code HH:MM:SS} contains no underscores, so the
     * {@code lastIndexOf('_')} call is unambiguous even when {@code trip_id}
     * itself has underscores (which is common for namespaced ids like
     * {@code mvv_19T0#10_1234}).
     */
    private static String extractTripId(String depId) {
        if (TIME_SUFFIX.matcher(depId).find()) {
            int u = depId.lastIndexOf('_');
            if (u > 0) return depId.substring(0, u);
        }
        return depId;
    }
}

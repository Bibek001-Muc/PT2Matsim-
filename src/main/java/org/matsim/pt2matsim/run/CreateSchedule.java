package org.matsim.pt2matsim.run;

import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.gtfs.GtfsConverter;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 2: read the harmonized GTFS feed and write an unmapped MATSim
 * transit schedule + a default vehicles file.
 *
 * <p>Three things fixed compared to the original version of this class:</p>
 * <ol>
 *   <li>Uses the corrected merged feed
 *       {@code ../gtfs_merge/output/munich_merged.gtfs.zip}, not the raw
 *       {@code gesamt_gtfs (1).zip}. The merged feed already has cross-
 *       feed station dedup (restricted to mvv<->mvg only — DB rail
 *       parents preserved), boundary clipping, and zone tagging.</li>
 *   <li>pt2matsim's {@code Gtfs2TransitSchedule} needs a <em>folder</em>
 *       of unpacked GTFS .txt files (not a .zip). This class transparently
 *       unzips the merged feed to {@code output/gtfs_unpacked/} the first
 *       time it runs and re-uses that folder on subsequent runs.</li>
 *   <li>Sample-day parameter changed from {@code DAY_WITH_MOST_SERVICES}
 *       (single weekday) to {@code ALL_SERVICE_IDS} so every trip in the
 *       merged feed's Mon-Sun service window is retained. The output
 *       schedule then carries the full 7-day operational picture.</li>
 * </ol>
 */
public class CreateSchedule {

    /** Path to the harmonized GTFS zip produced by gtfs_merge. */
    public static final String GTFS_ZIP = "../gtfs_merge/output/munich_merged.gtfs.zip";

    /** Where the zip is unpacked before being read by Gtfs2TransitSchedule. */
    public static final String GTFS_UNPACKED_DIR = "output/gtfs_unpacked";

    public static final String SCHEDULE_OUT = "output/MunichSchedule.xml";
    public static final String VEHICLES_OUT = "output/MunichVehicles.xml";

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

        System.out.println("[CreateSchedule] GTFS folder : " + unpacked);
        System.out.println("[CreateSchedule] sample day  : ALL_SERVICE_IDS (full week)");
        System.out.println("[CreateSchedule] CRS         : DHDN_GK4");
        System.out.println("[CreateSchedule] schedule    : " + SCHEDULE_OUT);
        System.out.println("[CreateSchedule] vehicles    : " + VEHICLES_OUT);

        Gtfs2TransitSchedule.run(
                unpacked.toString(),
                GtfsConverter.ALL_SERVICE_IDS,
                TransformationFactory.DHDN_GK4,
                SCHEDULE_OUT,
                VEHICLES_OUT,
                null
        );

        System.out.println("[CreateSchedule] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    /**
     * Unzip {@code zip} into {@code dest} if {@code dest} does not exist
     * or is older than the zip. Idempotent — re-running the pipeline is
     * cheap. Includes a basic zip-slip guard.
     */
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
}

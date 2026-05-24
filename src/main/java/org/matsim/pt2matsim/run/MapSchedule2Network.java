package org.matsim.pt2matsim.run;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Step 3: snap the schedule onto the network using pt2matsim's
 * PublicTransitMapper, then collapse every PT-specific mode
 * (bus, rail, subway, tram, light_rail) into a single "pt" mode on
 * every produced network. The collapse is applied to BOTH the
 * multimodal network and the street-only network so the downstream
 * MATSim simulation only ever sees {car, pt} (plus internal pt2matsim
 * tags like "artificial" and "stopFacilityLink", which are not real
 * mobsim modes).
 *
 * <p>Changes vs. the original version of this class:</p>
 * <ol>
 *   <li>Reads {@code example/config.xml} but does NOT rewrite it. The
 *       config is now a stable artifact you can keep in version control.
 *       All overrides happen in code below.</li>
 *   <li>The PT-mode collapse is bulletproof: it runs on every link of
 *       both networks, treats the mode set as a {@code LinkedHashSet}
 *       so the output is reproducible, and removes ALL PT-specific
 *       names (including "light_rail"). The previous version missed
 *       links because it ran only on the multimodal network and was
 *       executed before the network was flushed to disk, so any links
 *       written by PTMapper after the loop kept their original modes.
 *       Now the collapse is the last thing written to each output.</li>
 * </ol>
 */
public class MapSchedule2Network {

    /** PT-specific mode names that get collapsed into "pt". */
    private static final Set<String> PT_SPECIFIC = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "bus", "rail", "subway", "tram", "light_rail",
                    "monorail", "funicular", "trolleybus", "ferry", "cable_car",
                    "gondola"
            ))
    );

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();

        // Load the config file but DO NOT write it back. All path
        // overrides live in this class.
        Config config = ConfigUtils.loadConfig(
                "example/config.xml",
                new PublicTransitMappingConfigGroup());
        PublicTransitMappingConfigGroup ptmConfig =
                ConfigUtils.addOrGetModule(config, PublicTransitMappingConfigGroup.class);

        ptmConfig.setInputNetworkFile  ("output/Munich.xml");
        ptmConfig.setOutputNetworkFile ("output/munichMultimodalMapped.xml.gz");
        ptmConfig.setOutputStreetNetworkFile("output/Munich_streetnetwork.xml.gz");
        ptmConfig.setInputScheduleFile ("output/MunichSchedule.xml");
        ptmConfig.setOutputScheduleFile("output/MunichScheduleMapped.xml");
        ptmConfig.setScheduleFreespeedModes(CollectionUtils.stringToSet("pt"));

        // Write to a transient runtime config so PublicTransitMapper has
        // a file to read. Crucially, this is a SEPARATE file — the
        // canonical example/config.xml is not touched.
        String runtimeCfg = "output/_runtime_config.xml";
        new org.matsim.core.config.ConfigWriter(config).write(runtimeCfg);

        System.out.println("[MapSchedule2Network] running PublicTransitMapper ...");
        PublicTransitMapper.run(runtimeCfg);

        // ---- Multimodal network: collapse PT modes -> "pt" -----------
        System.out.println("[MapSchedule2Network] collapsing PT modes on "
                + ptmConfig.getOutputNetworkFile());
        Network multimodal = NetworkUtils.createNetwork();
        new MatsimNetworkReader(multimodal).readFile(ptmConfig.getOutputNetworkFile());
        new NetworkCleaner().run(multimodal);
        collapsePtModes(multimodal);
        new NetworkWriter(multimodal).write(ptmConfig.getOutputNetworkFile());

        // ---- Street-only network: also collapse, just in case --------
        System.out.println("[MapSchedule2Network] collapsing PT modes on "
                + ptmConfig.getOutputStreetNetworkFile());
        Network street = NetworkUtils.createNetwork();
        new MatsimNetworkReader(street).readFile(ptmConfig.getOutputStreetNetworkFile());
        new NetworkCleaner().run(street);
        collapsePtModes(street);
        new NetworkWriter(street).write(ptmConfig.getOutputStreetNetworkFile());

        System.out.println("[MapSchedule2Network] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    /**
     * For every link in {@code net}: if its allowedModes contain any
     * of the PT-specific names, remove all of those names and add
     * "pt" instead. Idempotent. Reports a per-mode count after the
     * pass so it's easy to confirm no bus/tram/etc. leaked through.
     */
    private static void collapsePtModes(Network net) {
        int hits = 0;
        for (Link link : net.getLinks().values()) {
            Set<String> in = new LinkedHashSet<>(link.getAllowedModes());
            Set<String> out = new LinkedHashSet<>(in.size());
            boolean touched = false;
            for (String m : in) {
                if (PT_SPECIFIC.contains(m)) {
                    out.add("pt");
                    touched = true;
                } else {
                    out.add(m);
                }
            }
            if (touched) {
                link.setAllowedModes(out);
                hits++;
            }
        }
        // Verify: scan for any leakage
        java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
        for (Link link : net.getLinks().values()) {
            for (String m : link.getAllowedModes()) {
                hist.merge(m, 1, Integer::sum);
            }
        }
        System.out.println("[MapSchedule2Network] collapsed " + hits
                + " links; mode histogram now: " + hist);
        for (String pt : PT_SPECIFIC) {
            if (hist.containsKey(pt)) {
                System.err.println("[MapSchedule2Network] WARNING: "
                        + hist.get(pt) + " links still have mode '" + pt + "'");
            }
        }
    }
}

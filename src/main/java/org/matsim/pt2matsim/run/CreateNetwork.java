package org.matsim.pt2matsim.run;

import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * Step 1: convert the Oberbayern OSM extract to a multimodal MATSim
 * network. CRS = DHDN_GK4 (matches CreateSchedule).
 *
 * <p>The OSM file lives in the sibling pt2matsim clone under
 * {@code ../pt2matsim/input/}. If you keep the OSM somewhere else,
 * either move it or change the path below.</p>
 */
public class CreateNetwork {

    /** OSM input. Relative to the project working directory. */
    public static final String OSM_INPUT = "../pt2matsim/input/oberbayern-260511.osm.gz";

    /** Output multimodal network (consumed by MapSchedule2Network). */
    public static final String NETWORK_OUTPUT = "output/Munich.xml";

    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        System.out.println("[CreateNetwork] OSM input  : " + OSM_INPUT);
        System.out.println("[CreateNetwork] Network out: " + NETWORK_OUTPUT);
        System.out.println("[CreateNetwork] CRS        : DHDN_GK4");

        Osm2MultimodalNetwork.run(OSM_INPUT, NETWORK_OUTPUT, TransformationFactory.DHDN_GK4);

        System.out.println("[CreateNetwork] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }
}

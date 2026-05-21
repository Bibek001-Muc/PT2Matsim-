package org.matsim.pt2matsim.run;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class

MapSchedule2Network {

    public static void main(String[] args) {
        //Create a mapping config:
        //If you want to see the default config, please uncomment and run the code below
        //CreateDefaultPTMapperConfig.main(new String[]{ "example/berlin/defaultConfig.xml"});

        // Open the mapping config and set the parameters to the required values
        // (usually done manually by opening the config with a simple editor)
        Config config = ConfigUtils.loadConfig(
                "example/config.xml",
                new PublicTransitMappingConfigGroup());
        PublicTransitMappingConfigGroup ptmConfig = ConfigUtils.addOrGetModule(config, PublicTransitMappingConfigGroup.class);

        ptmConfig.setInputNetworkFile("input/Munich.xml");
        ptmConfig.setOutputNetworkFile("output/munichMultimodalMapped.xml.gz");
        ptmConfig.setOutputStreetNetworkFile("output/Munich_streetnetwork.xml.gz");
        ptmConfig.setInputScheduleFile("output/MunichSchedule.xml");
        ptmConfig.setOutputScheduleFile("output/MunichScheduleMapped.xml");
        ptmConfig.setScheduleFreespeedModes(CollectionUtils.stringToSet("pt"));
        // Save the mapping config
        // (usually done manually)
        new ConfigWriter(config).write("example/config.xml");
        PublicTransitMapper.run("example/config.xml");

        Network multimodalNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(multimodalNetwork).readFile(ptmConfig.getOutputNetworkFile());
        new NetworkCleaner().run(multimodalNetwork);
        Set<String> ptSpecific = new HashSet<>(Arrays.asList("bus", "rail", "subway", "tram", "light_rail"));
        for (Link link : multimodalNetwork.getLinks().values()) {
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            if (modes.removeAll(ptSpecific)) {
                modes.add("pt");
                link.setAllowedModes(modes);
            }
        }
        new NetworkWriter(multimodalNetwork).write(ptmConfig.getOutputNetworkFile());

        Network streetNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(streetNetwork).readFile(ptmConfig.getOutputStreetNetworkFile());
        new NetworkCleaner().run(streetNetwork);
        new NetworkWriter(streetNetwork).write(ptmConfig.getOutputStreetNetworkFile());
    }
}

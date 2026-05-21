package org.matsim.pt2matsim.run;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.run.CreateDefaultPTMapperConfig;
import org.matsim.pt2matsim.run.PublicTransitMapper;

public class MapSchedule2Network {

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
        ptmConfig.setScheduleFreespeedModes(CollectionUtils.stringToSet("rail, light_rail"));
        // Save the mapping config
        // (usually done manually)
        new ConfigWriter(config).write("example/config.xml");
        PublicTransitMapper.run("example/config.xml");
    }
}

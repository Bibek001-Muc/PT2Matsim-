package org.matsim.pt2matsim.run;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.gtfs.GtfsConverter;
import org.matsim.pt2matsim.run.Gtfs2TransitSchedule;

public class CreateSchedule {

    public static void main(String[] args) {
        Gtfs2TransitSchedule.run("input/gesamt_gtfs (1).zip",
                GtfsConverter.DAY_WITH_MOST_SERVICES,
                TransformationFactory.DHDN_GK4,
                "output/MunichSchedule.xml",
                "output/MunichVehicles.xml",
                null);
    }
}

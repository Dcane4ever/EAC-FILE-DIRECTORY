package ph.edu.eac.filedirectory.taxonomy;

import java.util.Map;

/**
 * Maps a department code to a Lucide icon name for display (approval queue,
 * home page school cards, etc.). Purely a presentation lookup - not stored
 * data - so a department without a specific mapping still renders sensibly
 * via the "building" fallback rather than breaking.
 */
public final class DepartmentIcons {

    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("ENGR", "cpu"),
            Map.entry("SAS", "university"),
            Map.entry("GEN", "book-open"),
            Map.entry("NURS", "heart-pulse"),
            Map.entry("NUTR", "apple"),
            Map.entry("BUS", "landmark"),
            Map.entry("CRIM", "shield"),
            Map.entry("DENT", "smile"),
            Map.entry("HOSP", "concierge-bell"),
            Map.entry("MED", "microscope"),
            Map.entry("SOM", "stethoscope"),
            Map.entry("PHAR", "pill"),
            Map.entry("PHYS", "activity"),
            Map.entry("RAD", "scan"),
            Map.entry("EDUC", "graduation-cap"),
            Map.entry("MIDW", "baby"),
            Map.entry("GS", "school"),
            Map.entry("ETEEAP", "file-check")
    );

    private DepartmentIcons() {
    }

    public static String forCode(String code) {
        return BY_CODE.getOrDefault(code, "building");
    }
}

// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.validation.tests;

import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;

public class Mtb extends Test {

    /**
     * mtb:scale:uphill tag present but incline tag missing
     */
    protected static final int MTB_SCALE_UPHILL_NO_INCLINE = 3601;

    protected static final String KEY_MTB_SCALE_UPHILL = "mtb:scale:uphill";

    protected static final String KEY_INCLINE = "incline";

    public Mtb() {
        super(tr("MTB"),
                tr("Validates the correct tagging of mountain bike trails."));
    }

    @Override
    public void visit(Way w) {
        String uphillValue = w.get(KEY_MTB_SCALE_UPHILL);

        if (uphillValue != null) {
            String inclineValue = w.get(KEY_INCLINE);

            if (inclineValue == null) {
                errors.add(
                        new TestError(
                                this,
                                Severity.WARNING,
                                tr("Incomple MTB uphill tagging"),
                                tr("The way has the {0} tag but lacks the {1} tag", KEY_MTB_SCALE_UPHILL, KEY_INCLINE),
                                String.format("The way has the {0} tag but lacks the {1} tag", KEY_MTB_SCALE_UPHILL, KEY_INCLINE),
                                MTB_SCALE_UPHILL_NO_INCLINE,
                                w));
            }
        }
    }

    @Override
    public boolean isPrimitiveUsable(OsmPrimitive p) {
        return p.isUsable() && p instanceof Way;
    }
}

// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.layer.markerlayer;

import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.border.BevelBorder;
import javax.swing.border.Border;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.tools.template_engine.TemplateEngineDataProvider;

/**
 * Marker class with button look-and-feel.
 *
 * @author Frederik Ramm
 *
 */
public class ButtonMarker extends Marker {

    private Rectangle buttonRectangle;

    public ButtonMarker(LatLon ll, String buttonImage, MarkerLayer parentLayer, double time, double offset) {
        super(ll, "", buttonImage, parentLayer, time, offset);
        buttonRectangle = new Rectangle(0, 0, symbol.getIconWidth(), symbol.getIconHeight());
    }

    public ButtonMarker(LatLon ll, TemplateEngineDataProvider dataProvider, String buttonImage, MarkerLayer parentLayer, double time,
            double offset) {
        super(ll, dataProvider, buttonImage, parentLayer, time, offset);
        buttonRectangle = new Rectangle(0, 0, symbol.getIconWidth(), symbol.getIconHeight());
    }

    @Override public boolean containsPoint(Point p) {
        Point screen = Main.map.mapView.getPoint(getEastNorth());
        buttonRectangle.setLocation(screen.x+4, screen.y+2);
        return buttonRectangle.contains(p);
    }

    @Override public void paint(Graphics g, MapView mv, boolean mousePressed, boolean showTextOrIcon) {
        if (!showTextOrIcon) {
            super.paint(g, mv, mousePressed, showTextOrIcon);
            return;
        }
        Point screen = mv.getPoint(getEastNorth());
        buttonRectangle.setLocation(screen.x+4, screen.y+2);
        paintIcon(mv, g, screen.x+4, screen.y+2);
        Border b;
        Point mousePosition = mv.getMousePosition();

        // mouse is inside the window
        if (mousePosition != null && mousePressed && containsPoint(mousePosition)) {
            b = BorderFactory.createBevelBorder(BevelBorder.LOWERED);
        } else {
            b = BorderFactory.createBevelBorder(BevelBorder.RAISED);
        }
        Insets inset = b.getBorderInsets(mv);
        Rectangle r = new Rectangle(buttonRectangle);
        r.grow((inset.top+inset.bottom)/2, (inset.left+inset.right)/2);
        b.paintBorder(mv, g, r.x, r.y, r.width, r.height);

        String labelText = getText();
        if ((labelText != null) && showTextOrIcon && Main.pref.getBoolean("marker.buttonlabels", true)) {
            g.drawString(labelText, screen.x+4, screen.y+2);
        }
    }
}

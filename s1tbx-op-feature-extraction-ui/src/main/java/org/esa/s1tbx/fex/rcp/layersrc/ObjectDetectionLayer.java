/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.fex.rcp.layersrc;

import com.bc.ceres.binding.PropertySet;
import com.bc.ceres.glayer.Layer;
import com.bc.ceres.glayer.LayerTypeRegistry;
import com.bc.ceres.grender.Rendering;
import com.bc.ceres.grender.Viewport;
import org.esa.s1tbx.dat.layers.ScreenPixelConverter;
import org.esa.s1tbx.fex.gpf.oceantools.ObjectDiscriminationOp;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.framework.datamodel.Band;
import org.esa.snap.framework.datamodel.GeoCoding;
import org.esa.snap.framework.datamodel.GeoPos;
import org.esa.snap.framework.datamodel.MetadataElement;
import org.esa.snap.framework.datamodel.PixelPos;
import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.datamodel.RasterDataNode;
import org.esa.snap.framework.dataop.downloadable.XMLSupport;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows a detected object
 */
public class ObjectDetectionLayer extends Layer {

    private final Product product;
    private final Band band;

    private final List<ObjectDiscriminationOp.ShipRecord> targetList = new ArrayList<>(200);
    private double rangeSpacing;
    private double azimuthSpacing;
    private final static float lineThickness = 2.0f;
    private final static double border = 5.0;

    public ObjectDetectionLayer(PropertySet configuration) {
        super(LayerTypeRegistry.getLayerType(ObjectDetectionLayerType.class.getName()), configuration);
        setName("Object Detection");
        product = (Product) configuration.getValue("product");
        band = (Band) configuration.getValue("band");

        getPixelSize();

        LoadTargets(getTargetFile(product));
    }

    private void getPixelSize() {
        final MetadataElement absMetadata = AbstractMetadata.getAbstractedMetadata(product);
        if (absMetadata != null) {
            rangeSpacing = absMetadata.getAttributeDouble(AbstractMetadata.range_spacing, 0);
            azimuthSpacing = absMetadata.getAttributeDouble(AbstractMetadata.azimuth_spacing, 0);
        }
    }

    public static File getTargetFile(final Product product) {
        final MetadataElement absMetadata = AbstractMetadata.getAbstractedMetadata(product);
        if (absMetadata != null) {
            final String shipFilePath = absMetadata.getAttributeString(AbstractMetadata.target_report_file, null);
            if (shipFilePath != null) {
                final File file = new File(shipFilePath);
                if (file.exists())
                    return file;
            }
        }
        return null;
    }

    private void LoadTargets(final File file) {
        if (file == null)
            return;

        Document doc;
        try {
            doc = XMLSupport.LoadXML(file.getAbsolutePath());
        } catch (IOException e) {
            return;
        }

        targetList.clear();

        final Element root = doc.getRootElement();

        final List children = root.getContent();
        for (Object aChild : children) {
            if (aChild instanceof Element) {
                final Element targetsDetectedElem = (Element) aChild;
                if (targetsDetectedElem.getName().equals("targetsDetected")) {
                    final Attribute attrib = targetsDetectedElem.getAttribute("bandName");
                    if (attrib != null && band.getName().equalsIgnoreCase(attrib.getValue())) {
                        final List content = targetsDetectedElem.getContent();
                        for (Object det : content) {
                            if (det instanceof Element) {
                                final Element targetElem = (Element) det;
                                if (targetElem.getName().equals("target")) {
                                    final Attribute lat = targetElem.getAttribute("lat");
                                    if (lat == null) continue;
                                    final Attribute lon = targetElem.getAttribute("lon");
                                    if (lon == null) continue;
                                    final Attribute width = targetElem.getAttribute("width");
                                    if (width == null) continue;
                                    final Attribute length = targetElem.getAttribute("length");
                                    if (length == null) continue;
                                    final Attribute intensity = targetElem.getAttribute("intensity");
                                    if (intensity == null) continue;

                                    targetList.add(new ObjectDiscriminationOp.ShipRecord(
                                            Double.parseDouble(lat.getValue()),
                                            Double.parseDouble(lon.getValue()),
                                            (Double.parseDouble(width.getValue()) / rangeSpacing) + border,
                                            (Double.parseDouble(length.getValue()) / azimuthSpacing) + border,
                                            Double.parseDouble(intensity.getValue())));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void renderLayer(Rendering rendering) {

        if (band == null || targetList.isEmpty())
            return;

        final Viewport vp = rendering.getViewport();
        final RasterDataNode raster = product.getRasterDataNode(product.getBandAt(0).getName());
        final ScreenPixelConverter screenPixel = new ScreenPixelConverter(vp, raster);

        if (!screenPixel.withInBounds()) {
            return;
        }

        final GeoCoding geoCoding = product.getGeoCoding();
        final GeoPos geo = new GeoPos();
        final PixelPos pix = new PixelPos();

        final Graphics2D graphics = rendering.getGraphics();
        graphics.setStroke(new BasicStroke(lineThickness));
        graphics.setColor(Color.RED);

        final double[] ipts = new double[4];
        final double[] vpts = new double[4];

        final DecimalFormat frmt = new DecimalFormat("0.00");
        for (ObjectDiscriminationOp.ShipRecord target : targetList) {
            geo.setLocation(target.lat, target.lon);
            geoCoding.getPixelPos(geo, pix);
            final double halfWidth = target.width / 2.0;
            final double halfHeight = target.length / 2.0;

            ipts[0] = pix.getX() - halfWidth;
            ipts[1] = pix.getY() - halfHeight;
            ipts[2] = ipts[0] + target.width;
            ipts[3] = ipts[1] + target.length;

            screenPixel.pixelToScreen(ipts, vpts);

            final double w = vpts[2] - vpts[0];
            final double h = vpts[3] - vpts[1];
            final Ellipse2D.Double circle = new Ellipse2D.Double(vpts[0], vpts[1], w, h);
            graphics.draw(circle);

            final double targetWidthInMeter = (target.width - border) * rangeSpacing;
            final double targetlengthInMeter = (target.length - border) * azimuthSpacing;
            final double size = Math.sqrt(targetWidthInMeter * targetWidthInMeter + targetlengthInMeter * targetlengthInMeter);
            graphics.drawString(frmt.format(size) + "m", (int) vpts[0], (int) vpts[1]);
        }
    }
}

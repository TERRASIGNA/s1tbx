/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package org.esa.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jexp.ParseException;
import com.bc.jexp.Parser;
import com.bc.jexp.Term;
import com.bc.jexp.WritableNamespace;
import com.bc.jexp.impl.ParserImpl;
import org.apache.commons.collections.map.HashedMap;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.dataop.barithm.BandArithmetic;
import org.esa.beam.framework.dataop.barithm.RasterDataSymbol;
import org.esa.beam.framework.dataop.maptransf.Datum;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.datamodel.OrbitStateVector;
import org.esa.snap.datamodel.Unit;
import org.esa.snap.eo.Constants;
import org.esa.snap.gpf.InputProductValidator;
import org.esa.snap.gpf.OperatorUtils;
import org.esa.snap.gpf.ReaderUtils;
import org.esa.snap.gpf.TileIndex;

import java.awt.*;
import java.util.*;
import java.util.List;

import static org.esa.nest.dataio.sentinel1.Sentinel1Level1Directory.getListInEvenlySpacedGrid;

/**
 * Merges Sentinel-1 slice products
 */
@OperatorMetadata(alias = "SliceAssembly",
        category = "SAR Processing/Sentinel-1",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Merges Sentinel-1 slice products")
public final class SliceAssemblyOp extends Operator {

    @SourceProducts
    private Product[] sourceProducts;
    @TargetProduct
    private Product targetProduct;

    // Only bands whose polarization is selected will be in the output product.
    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    private MetadataElement absRoot = null;

    // The slice products will be in order in the array: 1st (top) slice is the 1st element in the array followed by
    // 2nd slice and so on.
    private Product[] sliceProducts;
    private Map<Band, BandLines[]> bandLineMap = new HashMap<>();

    // This is the raster width and height of the target product
    private int targetWidth = 0, targetHeight = 0;

    // Map a swath such as "IW1" to the assembled image height and width.
    // For GRD, use "" for swath.
    // height is 1st element. width is 2nd element.
    private Map<String, int[]> swathAssembledImageDimMap = new HashMap<>();

    // Map a product and swath such as "IW1" to the image height and width.
    // For GRD, use "" for swath.
    // height is 1st element. width is 2nd element.
    private Map<Product, Map<String, int[]> > sliceSwathImageDimMap = new HashMap<>();

    private Map<String, TiePointGeoCoding> swathGeocodingMap = new HashMap<>();

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SliceAssemblyOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.beam.framework.datamodel.Product} annotated with the
     * {@link org.esa.beam.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.beam.framework.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            for(Product srcProduct : sourceProducts) {
                final InputProductValidator validator = new InputProductValidator(srcProduct);
                validator.checkIfSentinel1Product();
                validator.checkProductType(new String[]{"SLC", "GRD"});
                validator.checkAcquisitionMode(new String[]{"SM","IW","EW"});
            }

            sliceProducts = determineSliceProducts();

            absRoot = AbstractMetadata.getAbstractedMetadata(sliceProducts[0]);

            if (selectedPolarisations == null || selectedPolarisations.length == 0) {
                final Sentinel1Utils su = new Sentinel1Utils(sliceProducts[0]);
                selectedPolarisations = su.getPolarizations();
            }

            checkSlantRangeTimes();

            createTargetProduct();

            updateTargetProductMetadata();

            determineBandStartEndTimes();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private Product[] determineSliceProducts() throws Exception {
        if(sourceProducts.length < 2) {
            throw new Exception("Slice assembly requires at least two consecutive slice products");
        }

        final TreeMap<Integer, Product> productSet = new TreeMap<>();
        for(Product srcProduct : sourceProducts) {
            final MetadataElement origMetaRoot = AbstractMetadata.getOriginalProductMetadata(srcProduct);
            final MetadataElement generalProductInformation = getGeneralProductInformation(origMetaRoot);
            if(!isSliceProduct(generalProductInformation)) {
                throw new Exception(srcProduct.getName() +" is not a slice product");
            }

            final int totalSlices = generalProductInformation.getAttributeInt("totalSlices");
            final int sliceNumber = generalProductInformation.getAttributeInt("sliceNumber");
            //System.out.println("SliceAssemblyOp.determineSliceProducts: totalSlices = " + totalSlices + "; slice product name = " + srcProduct.getName() + "; prod type = " + srcProduct.getProductType() + "; sliceNumber = " + sliceNumber);

            productSet.put(sliceNumber, srcProduct);
        }

        //check if consecutive
        Integer prev = productSet.firstKey();
        // Note that "The set's iterator returns the keys in ascending order".
        for(Integer i : productSet.keySet()) {
            if(!i.equals(prev)) {
                if(!prev.equals(i-1)) {
                    throw new Exception("Products are not consecutive slices");
                }
                prev = i;
            }
        }

        // Note that "If productSet makes any guarantees as to what order its elements
        // are returned by its iterator, toArray() must return the elements in
        // the same order".
        return productSet.values().toArray(new Product[productSet.size()]);
    }

    private static MetadataElement getGeneralProductInformation(final MetadataElement origMetaRoot) {
        final MetadataElement XFDU = origMetaRoot.getElement("XFDU");
        final MetadataElement metadataSection = XFDU.getElement("metadataSection");

        final MetadataElement metadataObject = findElementByID(metadataSection, "ID", "generalProductInformation");
        final MetadataElement metadataWrap = metadataObject.getElement("metadataWrap");
        final MetadataElement xmlData = metadataWrap.getElement("xmlData");
        MetadataElement generalProductInformation = xmlData.getElement("generalProductInformation");
        if (generalProductInformation == null)
            generalProductInformation = xmlData.getElement("standAloneProductInformation");
        return generalProductInformation;
    }

    private static boolean isSliceProduct(final MetadataElement generalProductInformation) {
        final String sliceProductFlag = generalProductInformation.getAttributeString("sliceProductFlag");
        return sliceProductFlag.equals("true");
    }

    private static MetadataElement findElementByID(final MetadataElement metadataSection, final String tag, final String id) {
        final MetadataElement[] metadataObjectList = metadataSection.getElements();

        for (MetadataElement metadataObject : metadataObjectList) {
            final String attrib = metadataObject.getAttributeString(tag, null);
            if (attrib.equals(id)) {
                return metadataObject;
            }
        }
        return null;
    }

    private String extractSwathIdentifier(final String mdsName) {
        // Includes possibly the "-".
        // E.g., it can be "iw-" or "iw1"
        return mdsName.substring(4, 7);
    }

    private String getSlantRangeTime(final Product product, final String sss) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        String slantRangeTime = "";

        for (MetadataElement e : annotationElems) {
            if (extractSwathIdentifier(e.getName()).equals(sss)) {
                MetadataElement prod = e.getElement("product");
                MetadataElement imgAnno = prod.getElement("imageAnnotation");
                MetadataElement imgInfo = imgAnno.getElement("imageInformation");
                slantRangeTime = imgInfo.getAttributeString("slantRangeTime");
                break;
            }
        }

        //System.out.println("return slant range time for " + sss + " = " + slantRangeTime);
        return slantRangeTime;
    }

    private void checkSlantRangeTimes() {

        final Product firstSliceProduct = sliceProducts[0];
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(firstSliceProduct);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        // There is some redundancy here.
        // E.g. we will check for both
        // s1a-iw-grd-vh-20140920t050131-20140920t050156-002471-002aec-002.xml and
        // s1a-iw-grd-vv-20140920t050131-20140920t050156-002471-002aec-001.xml
        for (MetadataElement e : annotationElems) {
            final String sss = extractSwathIdentifier(e.getName());
            final String slantRangeTime = getSlantRangeTime(firstSliceProduct, sss);
            //System.out.println("Check slant range time for " + e.getName() + " " + sss + " = " + slantRangeTime);
            for (int i = 1; i < sliceProducts.length; i++) {
                if (!slantRangeTime.equals(getSlantRangeTime(sliceProducts[i], sss))) {
                     throw new OperatorException("Slant range time don't agree: " + i + " " + sss);
                }
            }
        }
    }

    private ArrayList<String> getSwaths(final Product product) {

        ArrayList<String> swaths = new ArrayList<>();

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        for (MetadataElement e : annotationElems) {
            String sss = extractSwathIdentifier(e.getName()).toUpperCase();
            if (sss.endsWith("-")) {
                sss = sss.substring(0, sss.length()-1);
            }
            if (!swaths.contains(sss)) {
                swaths.add(sss);
            }
        }

        return  swaths;
    }

    private void getSwathDim(final Product product, final String swath, final int[] dim) {

        // dim[0] is height; dim[1] is width

        final Band[] bands = product.getBands();

        for (Band b : bands) {
            if (b.getName().contains(swath)) {
                dim[0] = b.getSceneRasterHeight();
                dim[1] = b.getSceneRasterWidth();
                break;
            }
        }
    }

    private void computeTargetWidthAndHeight() {

        // In GRD products, all the bands will have the same width and height, so the width and height of the
        // product are equal to the width and height of the bands. E.g a GRD product will have these bands:
        // - Amplitude_VH
        // - Amplitude_VV
        // They will have the same width and height.
        //
        // In SLC products, each band belongs to a swath and bands belonging to the same swath will have the
        // same width and height. E.g. a SLC product will have these bands:
        //
        // IW1: w x h = 22553 x 14850
        // - i_IW1_VH
        // - q_IW1_VH
        // - i_IW1_VV
        // - q_IW1_VV
        //
        // IW2: w x h = 26326 x 15363
        // - i_IW2_VH
        // - q_IW2_VH
        // - i_IW2_VV
        // - q_IW2_VV
        //
        // IW3: w x h = 25321 x 15534
        // - i_IW3_VH
        // - q_IW3_VH
        // - i_IW3_VV
        // - q_IW3_VV
        //
        // We assume for such a SLC slice product, the scene raster width and height will be the maximum
        // among the swaths, so product scene raster width = 26326 and product scene raster height = 15534.
        //
        // Say 1st slice:
        //  IW1: w x h = 22553 x 14850
        //  IW2: w x h = 26326 x 15363
        //  IW3: w x h = 25321 x 15534
        //  product width = 26326 and height = 15534
        //
        // 2nd slice:
        //  IW1: w x h = 22571 x 14850
        //  IW2: w x h = 26351 x 15363
        //  IW3: w x h = 25350 x 15543
        // product width = 26351 and height = 15543
        //
        // Assemble the 2 slices:
        //  IW1: w x h = 22571 x 29700
        //  IW2: w x h = 26351 x 30726
        //  IW3: w x h = 25350 x 31077
        // product width = 26351 and height = 31077

        final String productType = sliceProducts[0].getProductType();

        if (productType.equals("GRD")) {

            for (Product srcProduct : sliceProducts) {

                if (targetWidth < srcProduct.getSceneRasterWidth())
                    targetWidth = srcProduct.getSceneRasterWidth();
                targetHeight += srcProduct.getSceneRasterHeight();

                final Map<String, int[]> tmp = new HashMap<>();
                tmp.put("", new int[]{srcProduct.getSceneRasterHeight(), srcProduct.getSceneRasterWidth()});
                sliceSwathImageDimMap.put(srcProduct, tmp);
            }

            swathAssembledImageDimMap.put("", new int[] {targetHeight, targetWidth});

        } else {

            final ArrayList<String> swaths = getSwaths(sliceProducts[0]);
            final Map<String, Integer> swathHeight = new HashMap<>();
            final Map<String, Integer> swathWidth = new HashMap<>();

            for (String swath : swaths) {
                swathHeight.put(swath, 0);
                swathWidth.put(swath, 0);
            }

            for (Product srcProduct : sliceProducts) {

                for (String swath : swaths) {

                    final int[] dim = new int[2];
                    getSwathDim(srcProduct, swath, dim);

                    if (swathWidth.get(swath) < dim[1]) {
                        swathWidth.replace(swath, dim[1]);
                    }
                    swathHeight.replace(swath, swathHeight.get(swath) + dim[0]);


                    if (sliceSwathImageDimMap.containsKey(srcProduct)) {
                        final Map<String, int[]> tmp = sliceSwathImageDimMap.get(srcProduct);
                        tmp.put(swath, dim);
                    } else {
                        final Map<String, int[]> tmp = new HashMap<>();
                        tmp.put(swath, dim);
                        sliceSwathImageDimMap.put(srcProduct, tmp);
                    }
                }
            }

            for (String swath : swaths) {
                swathAssembledImageDimMap.put(swath, new int[] {swathHeight.get(swath), swathWidth.get(swath)});
                if (targetWidth < swathWidth.get(swath))
                    targetWidth = swathWidth.get(swath);
                if (targetHeight < swathHeight.get(swath))
                    targetHeight = swathHeight.get(swath);
            }
        }
    }

    private void computeTargetBandWidthAndHeight(final String bandName, final Dimension dim) throws OperatorException {

        // See comments in computeTargetWidthAndHeight().
        // For band width, we take the max for that band among all slice products.

        for (Product srcProduct : sliceProducts) {
            final Band srcBand = srcProduct.getBand(bandName);
            if(srcBand == null) {
                throw new OperatorException(bandName +" not found in product "+srcProduct.getName());
            }

            dim.setSize(Math.max(dim.width, srcBand.getRasterWidth()), dim.height + srcBand.getRasterHeight());
        }
    }

    private void createTargetProduct() {
        computeTargetWidthAndHeight();

        final Product firstSliceProduct = sliceProducts[0];
        final Product lastSliceProduct = sliceProducts[sliceProducts.length-1];
        targetProduct = new Product(firstSliceProduct.getName(), firstSliceProduct.getProductType(), targetWidth, targetHeight);

        // We are creating each target band based on the source band in the first slice product only.
        final Band[] sourceBands = firstSliceProduct.getBands();
        for (Band srcBand : sourceBands) {
            boolean selectedPol = false;
            for (String pol : selectedPolarisations) {
                if (srcBand.getName().contains(pol))
                    selectedPol = true;
            }
            if(!selectedPol)
                continue;

            if (srcBand instanceof VirtualBand) {
                final VirtualBand sourceBand = (VirtualBand) srcBand;
                int destWidth = 0;
                int destHeight = 0;

                final Term term = createTerm(sourceBand.getExpression(), sliceProducts);
                final RasterDataSymbol[] refRasterDataSymbols = BandArithmetic.getRefRasterDataSymbols(term);
                for (RasterDataSymbol symbol : refRasterDataSymbols) {
                    String name = symbol.getName();
                    final Band trgBand = targetProduct.getBand(name);
                    if(trgBand != null) {
                        destWidth = trgBand.getRasterWidth();
                        destHeight = trgBand.getRasterHeight();
                        break;
                    }
                }

                final VirtualBand targetBand = new VirtualBand(sourceBand.getName(), sourceBand.getDataType(),
                        destWidth, destHeight, sourceBand.getExpression());

                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                targetProduct.addBand(targetBand);
            } else {
                final Dimension dim = new Dimension(0, 0);
                computeTargetBandWidthAndHeight(srcBand.getName(), dim);
                final Band newBand = new Band(srcBand.getName(), srcBand.getDataType(), dim.width, dim.height);
                ProductUtils.copyRasterDataNodeProperties(srcBand, newBand);

                targetProduct.addBand(newBand);
            }
        }

        ProductUtils.copyMetadata(firstSliceProduct, targetProduct);
        ProductUtils.copyFlagCodings(firstSliceProduct, targetProduct);
        ProductUtils.copyMasks(firstSliceProduct, targetProduct);
        ProductUtils.copyVectorData(firstSliceProduct, targetProduct);
        ProductUtils.copyIndexCodings(firstSliceProduct, targetProduct);
        targetProduct.setStartTime(firstSliceProduct.getStartTime());
        targetProduct.setEndTime(lastSliceProduct.getEndTime());
        targetProduct.setDescription(firstSliceProduct.getDescription());

        final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        if (productType.equals("GRD")) {
            createTiePointGrids("");
        } else {
            final ArrayList<String> swaths = getSwaths(firstSliceProduct);
            for (String swath : swaths) {
                createTiePointGrids(swath);
            }
            createLatLonTiePointGridsForSLC();
        }

        addGeocoding();
    }

    private Term createTerm(final String expression, final Product[] availableProducts) {
        WritableNamespace namespace = BandArithmetic.createDefaultNamespace(availableProducts, 0);
        final Term term;
        try {
            Parser parser = new ParserImpl(namespace, false);
            term = parser.parse(expression);
        } catch (ParseException e) {
            throw new OperatorException("Could not parse expression: " + expression, e);
        }
        return term;
    }

    private MetadataElement[] getGeoGridForSwath(final Product product, final String sss) {

        // For GRD products, use "" for sss.

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotationElem = origProdRoot.getElement("annotation");

        final MetadataElement[] images = annotationElem.getElements();

        MetadataElement imgElem = null;
        if (sss.equals("")) {
            // This is GRD product, same grid for all bands, so just take the 1st one
            imgElem = annotationElem.getElementAt(0);
        } else {
            for (MetadataElement e : images) {
                if (extractSwathIdentifier(e.getName()).equals(sss.toLowerCase())) {
                    imgElem = e;
                    break;
                }
            }
        }

        if (imgElem == null) {
            throw new OperatorException("Cannot locate geolocation grid in metadata for " + sss);
        }

        final MetadataElement productElem = imgElem.getElement("product");
        final MetadataElement geolocationGrid = productElem.getElement("geolocationGrid");
        final MetadataElement geolocationGridPointList = geolocationGrid.getElement("geolocationGridPointList");
        return geolocationGridPointList.getElements();
    }

    private void createTiePointGrids(final String swath) {

        //System.out.println("SliceAssemblyOp.createTiePointGrids: " + swath);

        // For GRD, use "" for swath. For SLC, it should be something like "IW1".

        // One geolocationGridPointList for one swath.

        // geolocationGridPointList has a count and a list of geolocationGridPoint(s).
        // These geolocationGridPoint(s) should form an MxN grid, M = number of rows and N = number of columns.
        // Each geolocationGridPoint contains line (i.e. row) and pixel (i.e. column).
        // The horizontal or vertical spacing may not be even.
        // But from line to line, the pixels are in the same location.
        // E.g A 4x6 grid for an image of width 24 and height 17:
        // 1st row: line = 0;	pixels =	0	5	10	15	20	23
        // 2nd row: line = 6;	pixels = 	0	5	10	15	20	23
        // 3rd row: line = 12;	pixels = 	0	5	10	15	20	23
        // 4th row: line = 16;  pixels = 	0	5	10	15	20	23
        // According to Table 6-88 of Product Specs v2.9, each geolocationGridPoint is a point within the image.
        // So we cannot have a point with line 17 or pixels 24.
        //
        // We assume that from slice to slice, M may differ but N must be the same. However, the pixel spacing
        // from slice to slice may not be the same.
        // So the next slice to the example above can be a 5x6 grid for an image of width 30 and height 20:
        // 1st row: line = 0;	pixels =	0	6	12	18	24	29
        // 2nd row: line = 5;	pixels = 	0	6	12	18	24	29
        // 3rd row: line = 10;	pixels = 	0	6	12	18	24	29
        // 4th row: line = 15;  pixels = 	0	6	12	18	24	29
        // 5th row: line = 19;  pixels = 	0	6	12	18	24	29
        //
        // We concatenate the two geolocationGridPointList(s) together to form a 9x6 grid for an image of width 30 and
        // height 37:
        // 1st row: line = 0;	pixels =	0	5	10	15	20	23
        // 2nd row: line = 6;	pixels = 	0	5	10	15	20	23
        // 3rd row: line = 12;	pixels = 	0	5	10	15	20	23
        // 4th row: line = 16;  pixels = 	0	5	10	15	20	23
        // 5th row: line = 17;	pixels =	0	6	12	18	24	29
        // 6th row: line = 22;	pixels = 	0	6	12	18	24	29
        // 7th row: line = 27;	pixels = 	0	6	12	18	24	29
        // 8th row: line = 32;  pixels = 	0	6	12	18	24	29
        // 9th row: line = 36;  pixels = 	0	6	12	18	24	29

        int geoGridLen = 0;
        final ArrayList<MetadataElement[]> geoGrids = new ArrayList<>();
        int i = 0;
        for (Product product : sliceProducts) {
            MetadataElement[]  geoGrid = getGeoGridForSwath(product, swath);
            geoGridLen += geoGrid.length;
            geoGrids.add(i, geoGrid);
            i++;
        }

        //System.out.println("geoGridLen = " + geoGridLen);

        final double[] latList = new double[geoGridLen];
        final double[] lngList = new double[geoGridLen];
        final double[] incidenceAngleList = new double[geoGridLen];
        final double[] elevAngleList = new double[geoGridLen];
        final double[] rangeTimeList = new double[geoGridLen];
        final int[] x = new int[geoGridLen];
        final int[] y = new int[geoGridLen];

        final int[] gridWidths = new int[sliceProducts.length];
        final int[] gridHeights = new int[sliceProducts.length];

        for (int j = 0; j < sliceProducts.length; j++) {
            gridWidths[j] = 0;
            gridHeights[j] = 0;
        }

        int gridHeight = 0;

        i = 0;
        int ptsInPrvSlices = 0;
        int heightOffset = 0;
        for (int j = 0; j < sliceProducts.length; j++) {

            if (j > 0) {
                heightOffset += sliceSwathImageDimMap.get(sliceProducts[j-1]).get(swath)[0];
            }

            final MetadataElement[] geoGrid = geoGrids.get(j);

            for (MetadataElement ggPoint : geoGrid) {
                latList[i] = ggPoint.getAttributeDouble("latitude", 0);
                lngList[i] = ggPoint.getAttributeDouble("longitude", 0);
                incidenceAngleList[i] = ggPoint.getAttributeDouble("incidenceAngle", 0);
                elevAngleList[i] = ggPoint.getAttributeDouble("elevationAngle", 0);
                rangeTimeList[i] = ggPoint.getAttributeDouble("slantRangeTime", 0) * Constants.oneBillion; // s to ns

                x[i] = (int) ggPoint.getAttributeDouble("pixel", 0);
                if (x[i] == 0) {
                    // This means we are at the start of a new line
                    // gridWidths[j] will be updated to 0 at the 1st line.
                    // It will be updated to the correct value at the 2nd line.
                    if (gridWidths[j] == 0)
                        gridWidths[j] = i - ptsInPrvSlices;
                    ++gridHeights[j];
                }

                y[i] = (int) ggPoint.getAttributeDouble("line", 0) + heightOffset;

                ++i;
            }

            ptsInPrvSlices = i;

            gridHeight += gridHeights[j];
        }

        final int gridWidth = gridWidths[0];

        // We assume here that all the slice products will have the same width in the geolocation grid.
        // That is the same number of points in each line.
        for (int w : gridWidths) {
            if (w != gridWidth) {
                throw new OperatorException("geolocation grids have different widths among slice products");
            }
        }

        //System.out.println("gridWidth = " + gridWidth + " gridHeight = " + gridHeight);

        final int newGridWidth = gridWidth;
        final int newGridHeight = gridHeight;
        if (geoGridLen != (newGridWidth * newGridHeight)) {
            throw new OperatorException("wrong number of geolocation grid points");
        }
        final float[] newLatList = new float[newGridWidth * newGridHeight];
        final float[] newLonList = new float[newGridWidth * newGridHeight];
        final float[] newIncList = new float[newGridWidth * newGridHeight];
        final float[] newElevList = new float[newGridWidth * newGridHeight];
        final float[] newslrtList = new float[newGridWidth * newGridHeight];

        final int[] dim = swathAssembledImageDimMap.get(swath);
        final int sceneRasterWidth = dim[1];
        final int sceneRasterHeight = dim[0];

        //System.out.println("swath = " + swath + " width = " + sceneRasterWidth + " height = " + sceneRasterHeight);

        final double subSamplingX = (double) sceneRasterWidth / (newGridWidth - 1);
        final double subSamplingY = (double) sceneRasterHeight / (newGridHeight - 1);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, latList,
                newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLatList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, lngList,
                newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLonList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, incidenceAngleList,
                newGridWidth, newGridHeight, subSamplingX, subSamplingY, newIncList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, elevAngleList,
                newGridWidth, newGridHeight, subSamplingX, subSamplingY, newElevList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, rangeTimeList,
                newGridWidth, newGridHeight, subSamplingX, subSamplingY, newslrtList);

        final String prefix = swath.equals("") ? swath : swath + "_";

        final TiePointGrid latGrid = new TiePointGrid(prefix + OperatorUtils.TPG_LATITUDE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLatList);
        latGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(latGrid);

        final TiePointGrid lonGrid = new TiePointGrid(prefix +OperatorUtils.TPG_LONGITUDE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLonList, TiePointGrid.DISCONT_AT_180);
        lonGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(lonGrid);

        final TiePointGrid incidentAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_INCIDENT_ANGLE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newIncList);
        incidentAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(incidentAngleGrid);

        final TiePointGrid elevAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_ELEVATION_ANGLE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newElevList);
        elevAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(elevAngleGrid);

        final TiePointGrid slantRangeGrid = new TiePointGrid(prefix + OperatorUtils.TPG_SLANT_RANGE_TIME,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newslrtList);
        slantRangeGrid.setUnit(Unit.NANOSECONDS);
        targetProduct.addTiePointGrid(slantRangeGrid);

        if (!swath.equals("")) {
            // This is SLC
            final String swathNum = swath.substring(swath.length() - 1, swath.length());
            if (swathNum.equals("1") || swathNum.equals(Integer.toString(swathAssembledImageDimMap.size()))) {
                final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid, Datum.WGS_84);
                swathGeocodingMap.put(swath, tpGeoCoding);
            }
        }

        //System.out.println("SliceAssemblyOp.createTiePointGrids: DONE " + swath);
    }

    private void createLatLonTiePointGridsForSLC() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sliceProducts[0]);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        final String firstSwath = acquisitionMode + "1";
        final String lastSwath = acquisitionMode + Integer.toString(swathAssembledImageDimMap.size());

        final GeoCoding firstSWBandGeoCoding = swathGeocodingMap.get(firstSwath);
        final int firstSWBandHeight = swathAssembledImageDimMap.get(firstSwath)[0];

        final GeoCoding lastSWBandGeoCoding = swathGeocodingMap.get(lastSwath);
        final int lastSWBandWidth = swathAssembledImageDimMap.get(lastSwath)[1];
        final int lastSWBandHeight = swathAssembledImageDimMap.get(lastSwath)[0];

        final PixelPos ulPix = new PixelPos(0, 0);
        final PixelPos llPix = new PixelPos(0, firstSWBandHeight - 1);
        final GeoPos ulGeo = new GeoPos();
        final GeoPos llGeo = new GeoPos();
        firstSWBandGeoCoding.getGeoPos(ulPix, ulGeo);
        firstSWBandGeoCoding.getGeoPos(llPix, llGeo);

        final PixelPos urPix = new PixelPos(lastSWBandWidth - 1, 0);
        final PixelPos lrPix = new PixelPos(lastSWBandWidth - 1, lastSWBandHeight - 1);
        final GeoPos urGeo = new GeoPos();
        final GeoPos lrGeo = new GeoPos();
        lastSWBandGeoCoding.getGeoPos(urPix, urGeo);
        lastSWBandGeoCoding.getGeoPos(lrPix, lrGeo);

        final float[] latCorners = {(float)ulGeo.getLat(), (float)urGeo.getLat(), (float)llGeo.getLat(), (float)lrGeo.getLat()};
        final float[] lonCorners = {(float)ulGeo.getLon(), (float)urGeo.getLon(), (float)llGeo.getLon(), (float)lrGeo.getLon()};

        ReaderUtils.addGeoCoding(targetProduct, latCorners, lonCorners);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, ulGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, ulGeo.getLon());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, urGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, urGeo.getLon());

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, llGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, llGeo.getLon());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lrGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lrGeo.getLon());
    }

    private void addGeocoding() {
        final TiePointGrid latGrid = targetProduct.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
        final TiePointGrid lonGrid = targetProduct.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid, Datum.WGS_84);
        targetProduct.setGeoCoding(tpGeoCoding);
    }

    private String extractImageNumber(final String filename) {

        final int dotIdx = filename.indexOf('.');
        return filename.substring(dotIdx-3, dotIdx);
    }

    private ProductData getStopTime(final Product product, final String imageNum) {

        //System.out.println("getStopTime for " + product.getName() + " imageNum = " + imageNum);

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement calibration = origProdRoot.getElement("calibration");
        final MetadataElement[] calibrationElems = calibration.getElements();

        ProductData data = null;

        for (MetadataElement e : calibrationElems) {

            //System.out.println("getStopTime: " + e.getName());

            if (extractImageNumber(e.getName()).equals(imageNum)) {

                final MetadataElement calib = e.getElement("calibration");
                final MetadataElement adsHeader = calib.getElement("adsHeader");
                final MetadataAttribute stopTime = adsHeader.getAttribute("stopTime");
                //System.out.println("getStopTime: " + stopTime.getData().toString());

                data = stopTime.getData();
            }

        }

        return data;
    }

    MetadataElement getCalibrationOrNoiseVectorList(final Product product, final String imageNum, final String dataName) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement data = origProdRoot.getElement(dataName);
        final MetadataElement[] elems = data.getElements();

        MetadataElement vectorList = null;

        for (MetadataElement e : elems) {

            if (extractImageNumber(e.getName()).equals(imageNum)) {

                final MetadataElement dat = e.getElement(dataName);
                vectorList = dat.getElement(dataName + "VectorList");
            }

        }

        return vectorList;
    }

    private int getCalibrationOrNoisePixelCount(final Product product, final String imageNum, final int[] pixelSpacing, final String dataName) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement calibration = origProdRoot.getElement(dataName);
        final MetadataElement[] calibrationElems = calibration.getElements();

        int pixelCount = 0;

        for (MetadataElement e : calibrationElems) {
            //System.out.println("getCalibrationOrNoisePixelCount: " + e.getName());
            if (extractImageNumber(e.getName()).equals(imageNum)) {
                final MetadataElement calib = e.getElement(dataName);
                final MetadataElement calibVectorList = calib.getElement(dataName+"VectorList");
                final MetadataElement firstCalibVector = calibVectorList.getElementAt(0);
                final MetadataElement pixel = firstCalibVector.getElement("pixel");

                final MetadataAttribute count = pixel.getAttribute("count");
                //System.out.println("getCalibrationOrNoisePixelCount: " + count.getData().toString());
                pixelCount = Integer.parseInt(count.getData().getElemString());

                final MetadataAttribute pixels = pixel.getAttribute("pixel");
                final String pixelsStr = pixels.getData().getElemString();
                final String[] pixelsArrayOfStr = pixelsStr.split(" ");
                final int pixel0 = Integer.parseInt(pixelsArrayOfStr[0]);
                final int pixel1 = Integer.parseInt(pixelsArrayOfStr[1]);
                //System.out.println("pixel0 = " + pixel0 + " pixel1 = " + pixel1);
                pixelSpacing[0] = pixel1 - pixel0;
            }
        }

        return pixelCount;
    }

    private void concatenateVectors(final MetadataElement targetVectorList,
                                               final MetadataElement sliceVectorList,
                                               final int startVectorIdx,
                                               final int lineOffset) {

        int idx = Integer.parseInt(targetVectorList.getAttribute("count").getData().getElemString());
        final int numSliceLines = Integer.parseInt(sliceVectorList.getAttribute("count").getData().getElemString());

        for (int i = startVectorIdx; i < numSliceLines; i++) {

            MetadataElement v = sliceVectorList.getElementAt(i);
            MetadataElement newV = v.createDeepClone();
            final int newLine = Integer.parseInt(v.getAttributeString("line")) + lineOffset;
            newV.setAttributeString("line", Integer.toString(newLine));
            targetVectorList.addElementAt(newV, idx);
            idx++;
        }

        targetVectorList.setAttributeString("count", Integer.toString(idx));
    }

    private void updateCalibrationOrNoise(final String dataName) {

        // dataName should be "calibration" or "noise"

        final Product lastSliceProduct = sliceProducts[sliceProducts.length-1];

        // The calibration or noise data in metadata in targetProduct at this point is copied from the 1st slice.
        // So we need to concatenate the vectors from the slices to target.

        // TODO: We should make the short vectors longer so that all vectors have "same" columns.
        // TODO: E.g., a vector from top slice (width = 20055) has pixels:
        // TODO: 0 40 80 ... 20040 20056
        // TODO: a vector from the bottom slice (width = 20086) has pixels:
        // TODO: 0 40 80 ... 20040 20080 20086
        // TODO: Assembled product has width 20086 and we need to use extrapolation to extend the vectors from the top
        // TODO: slice to have pixels:
        // TODO: 0 40 80 ... 20040 20080 20086

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final MetadataElement targetData = targetOrigProdRoot.getElement(dataName);
        final MetadataElement[] targetDataElems = targetData.getElements();

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement target : targetDataElems) {

            boolean isSelected = false;
            for (String pol : selectedPolarisations) {
                if (target.getName().toUpperCase().contains(pol)) {
                    isSelected = true;
                    break;
                }
            }

            if (!isSelected) {
                //System.out.println("remove " + dataName + " for " + target.getName());
                targetData.removeElement(target);
                continue;
            }

            //System.out.println("update " + dataName + " for " + target.getName());

            final String imageNum = extractImageNumber(target.getName());
            final MetadataElement targetDat = target.getElement(dataName);

            // Update stopTime in adsHeader
            final MetadataElement targetADSHeader = targetDat.getElement("adsHeader");
            final ProductData lastSliceStopTime = getStopTime(lastSliceProduct, imageNum);
            AbstractMetadata.setAttribute(targetADSHeader, "stopTime", lastSliceStopTime.getElemString());

            // Update (calibration|noise)VectorList

            final MetadataElement targetVectorList = targetDat.getElement(dataName+"VectorList");

            int numLines = Integer.parseInt(targetVectorList.getAttribute("count").getData().getElemString());
            final int[] pixelSpacing = new int[1];
            int numPixels = getCalibrationOrNoisePixelCount(targetProduct, imageNum, pixelSpacing, dataName);
            int height = sliceProducts[0].getSceneRasterHeight();
            //System.out.println("initial numLines = " + numLines + " numPixels = " + numPixels + " height = " + height);

            // Loop through 2nd to last slice products in order and concatenate the (calibration|noise) vectors from each
            // slice to the bottom of target
            for (int i = 1; i < sliceProducts.length; i++) {

                final Product sliceProduct = sliceProducts[i];
                final int[] slicePixelSpacing = new int[1];
                final int sliceNumPixels = getCalibrationOrNoisePixelCount(sliceProduct, imageNum, slicePixelSpacing, dataName);

                if (pixelSpacing[0] != slicePixelSpacing[0]) {
                    throw new OperatorException("slice products have different pixel spacing in " + dataName + " vectors: "+ i + " " + pixelSpacing[0] + " " + slicePixelSpacing[0]);
                }

                final MetadataElement targetLastVector = targetVectorList.getElementAt(targetVectorList.getNumElements()-1);
                int targetLastVectorLine = Integer.parseInt(targetLastVector.getAttributeString("line"));
                //System.out.println("targetLastVectorLine = " + targetLastVectorLine + " sliceNumPixels = " + sliceNumPixels);

                final MetadataElement sliceVectorList = getCalibrationOrNoiseVectorList(sliceProduct, imageNum, dataName);
                final int sliceNumLines = Integer.parseInt(sliceVectorList.getAttribute("count").getData().getElemString());

                final int topSliceWidth = sliceProducts[i-1].getSceneRasterWidth();
                final int bottomSliceWidth = sliceProducts[i].getSceneRasterWidth();

                int numLinesRemoved = 0;

                if (targetLastVectorLine == height-1) {

                    concatenateVectors(targetVectorList, sliceVectorList, 0, height);

                } else if (numPixels <= sliceNumPixels && topSliceWidth <= bottomSliceWidth) {

                    // Remove excess calibration vectors from target and keep all calibration vectors from bottom
                    // slice

                    int j;
                    for (j = numLines-1; j >= 0; j--) {
                        MetadataElement targetCalibVector = targetVectorList.getElementAt(j);
                        final int targetCalibVectorLine = Integer.parseInt(targetCalibVector.getAttributeString("line"));
                        if (targetCalibVectorLine >= height) {
                            targetVectorList.removeElement(targetCalibVector);
                        } else {
                            break;
                        }
                    }

                    numLinesRemoved = numLines - j - 1;
                    // Must update targetCalibVectorList count before calling concatenateCalibrationVectors()
                    targetVectorList.setAttributeString("count", Integer.toString(numLines - numLinesRemoved));
                    concatenateVectors(targetVectorList, sliceVectorList, 0, height);

                } else {

                    int j;

                    // Remove excess calibration vectors at the bottom in target metadata.
                    // "numLines" is thee current number of calibration vectors in the target metadata (with i slices assembled).
                    // "height" is the height of the image after assembling i slices.
                    // Say, height is 975 and there are calibration vectors at line 950, 1000, 1050, 1100. We can remove
                    // those for lines 1050 and 1100. There are slice products with such excess calibration vectors.
                    // They need to be removed before we concatenate the calibration vectors from the next slice.
                    for (j = numLines-1; j >= 0; j--) {
                        MetadataElement targetCalibVector = targetVectorList.getElementAt(j);
                        final int targetCalibVectorLine = Integer.parseInt(targetCalibVector.getAttributeString("line"));
                        if (targetCalibVectorLine < height-1) {
                            // We need one calibration vector whose line is >= height-1 (height-1 is last line of image)
                            // so the j+1 is the last calibration vector we need to keep
                            break;
                        }
                        targetLastVectorLine = targetCalibVectorLine;
                    }
                    // Want to keep j+1 as last calibration vector, start removing at j+2.
                    for (int k = j+2; k < numLines; k++) {
                        MetadataElement targetCalibVector = targetVectorList.getElementAt(k);
                        targetVectorList.removeElement(targetCalibVector);
                    }
                    targetVectorList.setAttributeString("count", Integer.toString(j+2));
                    numLinesRemoved = numLines - (j+2);

                    // Since the slice we are concatenating to target is smaller in width, we want to skip the
                    // starting calibration vectors whose lines are <= the line of the last target calibration vector.
                    // "sliceNumLines" is the number of calibration vectors in the slice.
                    // Note that the line of each slice calibration vector has zero offset, so we have to add "height"
                    // to it before comparing with target.
                    for (j = 0; j < sliceNumLines; j++) {
                        final MetadataElement sliceCalibVector = sliceVectorList.getElementAt(j);
                        final int sliceCalibVectorLine = Integer.parseInt(sliceCalibVector.getAttributeString("line"));
                        if (sliceCalibVectorLine + height > targetLastVectorLine) {
                            // j is the first one we want to keep
                            break;
                        }
                    }
                    numLinesRemoved += j;

                    concatenateVectors(targetVectorList, sliceVectorList, j, height);
                }

                numLines += (sliceNumLines - numLinesRemoved);
                targetVectorList.setAttributeString("count", Integer.toString(numLines));
                numPixels = sliceNumPixels;
                height += sliceProduct.getSceneRasterHeight();
            }
        }
    }

    private String getProductLastLineUtcTime(final Product product, final String imageNum) {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement targetData = targetOrigProdRoot.getElement("annotation");

        final MetadataElement[] targetDataElems = targetData.getElements();

        for (MetadataElement target : targetDataElems) {

            if (extractImageNumber(target.getName()).equals(imageNum)) {
                final MetadataElement productElem = target.getElement("product");
                final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
                final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");
                return imageInformationElem.getAttributeString("productLastLineUtcTime");
            }
        }

        return "";
    }

    private void updateImageInformation() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final MetadataElement targetData = targetOrigProdRoot.getElement("annotation");
        final MetadataElement[] targetDataElems = targetData.getElements();

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement target : targetDataElems) {

            boolean isSelected = false;
            for (String pol : selectedPolarisations) {
                if (target.getName().toUpperCase().contains(pol)) {
                    isSelected = true;
                    break;
                }
            }

            if (!isSelected) {
                //System.out.println("remove " + dataName + " for " + target.getName());
                targetData.removeElement(target);
                continue;
            }

            final MetadataElement productElem = target.getElement("product");
            final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
            final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");

            imageInformationElem.setAttributeString("productLastLineUtcTime",
                    getProductLastLineUtcTime(sliceProducts[sliceProducts.length-1],
                            extractImageNumber(target.getName())));
            imageInformationElem.setAttributeString("numberOfSamples", Integer.toString(targetProduct.getSceneRasterWidth()));
            imageInformationElem.setAttributeString("numberOfLines", Integer.toString(targetProduct.getSceneRasterHeight()));
        }
    }

    private void updateTargetProductMetadata() throws Exception {

        // All the metadata has been copied from the 1st slice product to the assembled target product.
        // Now we want to update the metadata that should not be "included", but "merged" or concatenated".

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final Product firstSliceProduct = sliceProducts[0];
        final Product lastSliceProduct = sliceProducts[sliceProducts.length-1];
        final MetadataElement absFirst = AbstractMetadata.getAbstractedMetadata(firstSliceProduct);
        final MetadataElement absLast = AbstractMetadata.getAbstractedMetadata(lastSliceProduct);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_line_time,
                absFirst.getAttributeUTC(AbstractMetadata.first_line_time));
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_line_time,
                absLast.getAttributeUTC(AbstractMetadata.last_line_time));

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetWidth);

        for(Band band : targetProduct.getBands()) {
            MetadataElement bandMeta = AbstractMetadata.getBandAbsMetadata(absTgt, band);

            AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.first_line_time,
                    absFirst.getAttributeUTC(AbstractMetadata.first_line_time));
            AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.last_line_time,
                    absLast.getAttributeUTC(AbstractMetadata.last_line_time));

            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, band.getRasterHeight());
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, band.getRasterWidth());
        }

        final List<OrbitStateVector> orbVectorList = new ArrayList<>();
        final List<AbstractMetadata.SRGRCoefficientList> srgrList = new ArrayList<>();
        final List<AbstractMetadata.DopplerCentroidCoefficientList> dopList = new ArrayList<>();
        for(Product srcProduct : sliceProducts) {
            final MetadataElement absSrc = AbstractMetadata.getAbstractedMetadata(srcProduct);

            // update orbit state vectors
            final OrbitStateVector[] orbs = AbstractMetadata.getOrbitStateVectors(absSrc);
            orbVectorList.addAll(Arrays.asList(orbs));

            // update srgr coeffs
            final AbstractMetadata.SRGRCoefficientList[] srgr = AbstractMetadata.getSRGRCoefficients(absSrc);
            srgrList.addAll(Arrays.asList(srgr));

            // update Doppler centroid coeffs
            final AbstractMetadata.DopplerCentroidCoefficientList[] dop = AbstractMetadata.getDopplerCentroidCoefficients(absSrc);
            dopList.addAll(Arrays.asList(dop));
        }

        AbstractMetadata.setOrbitStateVectors(absTgt, orbVectorList.toArray(new OrbitStateVector[orbVectorList.size()]));
        AbstractMetadata.setSRGRCoefficients(absTgt, srgrList.toArray(new AbstractMetadata.SRGRCoefficientList[srgrList.size()]));
        AbstractMetadata.setDopplerCentroidCoefficients(absTgt, dopList.toArray(new AbstractMetadata.DopplerCentroidCoefficientList[dopList.size()]));

        updateCalibrationOrNoise("calibration");
        updateCalibrationOrNoise("noise");

        updateImageInformation();

        //System.out.println("DONE updateTargetProductMetadata");
    }

    private void determineBandStartEndTimes() {
        for(Band targetBand : targetProduct.getBands()) {
            final List<BandLines> bandLineList = new ArrayList<>(sliceProducts.length);
            int height = 0;
            for (Product srcProduct : sliceProducts) {
                final Band srcBand = srcProduct.getBand(targetBand.getName());
                int start = height;
                height += srcBand.getRasterHeight();
                int end = height;
                bandLineList.add(new BandLines(srcBand, start, end));
            }
            final BandLines[] lines = bandLineList.toArray(new BandLines[bandLineList.size()]);
            bandLineMap.put(targetBand, lines);
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancellation requests.
     * @throws org.esa.beam.framework.gpf.OperatorException If an error occurs during computation of the target raster.
     */
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        try {
            final Rectangle targetTileRectangle = targetTile.getRectangle();
            final int tx0 = targetTileRectangle.x;
            final int ty0 = targetTileRectangle.y;
            final int maxY = ty0 + targetTileRectangle.height;
            final int maxX = tx0 + targetTileRectangle.width;

            final BandLines[] lines = bandLineMap.get(targetBand);
            final ProductData trgData = targetTile.getDataBuffer();

            final TileIndex trgIndex = new TileIndex(targetTile);
            final Rectangle srcRect = new Rectangle();

            //System.out.println("Do band = " + targetBand.getName() + ": tx0 = " + tx0 + " ty0 = " + ty0 + " maxX = " + maxX + " maxY = " + maxY);

            BandLines line = lines[0];
            for(int y=ty0; y < maxY; ++y) {

                boolean validLine = y >= line.start && y < line.end;
                if(!validLine) {
                    for(BandLines l : lines) {
                        if(y >= l.start && y < l.end) {
                            line = l;
                            validLine = true;
                            break;
                        }
                    }
                    if(!validLine) {
                        // should never get here
                        throw new OperatorException("line "+y+" not found in slice products");
                    }
                }

                final int yy = y-line.start;
                srcRect.setBounds(targetTileRectangle.x, yy, targetTileRectangle.width, 1);
                final Tile sourceRaster = getSourceTile(line.band, srcRect);
                final ProductData srcData = sourceRaster.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(sourceRaster);
                trgIndex.calculateStride(y);
                srcIndex.calculateStride(yy);

                for (int x = tx0; x < maxX; ++x) {
                    trgData.setElemDoubleAt(trgIndex.getIndex(x), srcData.getElemDoubleAt(srcIndex.getIndex(x)));
                }
            }

            //System.out.println("DONE band = " + targetBand.getName() + ": tx0 = " + tx0 + " ty0 = " + ty0 + " maxX = " + maxX + " maxY = " + maxY);
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    private static class BandLines {
        final int start;
        final int end;
        final Band band;
        BandLines(final Band band, final int s, final int e) {
            this.band = band;
            this.start = s;
            this.end = e;
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator()
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SliceAssemblyOp.class);
        }
    }
}
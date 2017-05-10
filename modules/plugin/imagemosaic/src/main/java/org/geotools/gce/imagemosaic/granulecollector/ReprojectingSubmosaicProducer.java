/*
 * GeoTools - The Open Source Java GIS Toolkit
 * http://geotools.org
 *
 * (C) 2016, Open Source Geospatial Foundation (OSGeo)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package org.geotools.gce.imagemosaic.granulecollector;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import javax.media.jai.Interpolation;
import javax.media.jai.InterpolationNearest;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.ROI;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.processing.Operations;
import org.geotools.factory.Hints;
import org.geotools.gce.imagemosaic.GranuleDescriptor;
import org.geotools.gce.imagemosaic.MergeBehavior;
import org.geotools.gce.imagemosaic.MosaicElement;
import org.geotools.gce.imagemosaic.Mosaicker;
import org.geotools.gce.imagemosaic.RasterLayerRequest;
import org.geotools.gce.imagemosaic.RasterLayerResponse;
import org.geotools.gce.imagemosaic.RasterManager;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.image.ImageWorker;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.matrix.XAffineTransform;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.resources.coverage.CoverageUtilities;
import org.geotools.resources.image.ImageUtilities;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.referencing.operation.TransformException;

import it.geosolutions.jaiext.range.NoDataContainer;

/**
 * SubmosaicProducer that can handle reprojecting its contents into the target mosaic CRS. This
 * works by grouping together everything with a like CRS (and like SortBy property if supplied) and
 * mosaicking them separately before forming a final mosaic.
 *
 * This relies on the SortBy including CRS as a final SortBy clause
 */
class ReprojectingSubmosaicProducer extends BaseSubmosaicProducer {

    private final boolean dryRun;

    private final RenderingHints renderingHints;

    // operations factory to use for resampling
    private final Operations operations;

    private CoordinateReferenceSystem targetCRS;

    private List<CRSBoundMosaicProducer> perMosaicProducers = new ArrayList<>();

    private CRSBoundMosaicProducer currentSubmosaicProducer;

    ReprojectingSubmosaicProducer(RasterLayerRequest request, RasterLayerResponse response,
            RasterManager rasterManager, boolean dryRun) {
        super(response, dryRun);
        this.targetCRS = rasterManager.getConfiguration().getCrs();
        this.dryRun = dryRun;

        Hints hints = rasterManager.getHints();
        this.renderingHints = createRenderingHints(hints, request);
        this.operations = new Operations(renderingHints);
    }

    private static RenderingHints createRenderingHints(Hints hints, RasterLayerRequest request) {
        RenderingHints renderHints = new RenderingHints(null);
        if (request.getInterpolation() != null) {
            renderHints.put(JAI.KEY_INTERPOLATION, request.getInterpolation());
        }

        return renderHints;
    }

    @Override
    public boolean accept(GranuleDescriptor granuleDescriptor) {
        // we have a current CRS group, either it matches or we need to create a new one
        boolean accepted = currentSubmosaicProducer != null
                && currentSubmosaicProducer.accept(granuleDescriptor);
        if (!accepted) {
            // either we have no producer, or the granule was rejected by the current one,
            // presumably because its CRS didn't match, we need to create a new one because we've moved on to the next
            CoordinateReferenceSystem targetCRS = granuleDescriptor.getGranuleEnvelope()
                    .getCoordinateReferenceSystem();
            try {
                RasterLayerResponse transformedResponse = rasterLayerResponse
                        .reprojectTo(granuleDescriptor);
                this.currentSubmosaicProducer = new CRSBoundMosaicProducer(transformedResponse,
                        dryRun, targetCRS, granuleDescriptor);
                perMosaicProducers.add(currentSubmosaicProducer);
                accepted = currentSubmosaicProducer.acceptGranule(granuleDescriptor);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to setup CRS specific sub-mosaic", e);
            }

        }
        return accepted;
    }

    protected static CoordinateReferenceSystem getCRS(String granuleCRSCode) throws FactoryException {
        return CRS.decode(granuleCRSCode);
    }

    @Override
    public List<MosaicElement> createMosaic() throws IOException {

        List<MosaicElement> mosaicInputs = new ArrayList<>();
        for (CRSBoundMosaicProducer mosaicProducer : this.perMosaicProducers) {
            List<MosaicElement> mosaicElement = mosaicProducer.createMosaic();
            this.hasAlpha = mosaicProducer.hasAlpha();
            try {
                for (MosaicElement e : mosaicElement) {
                    MosaicElement reprojectedMosaicElement = this.reprojectMosaicElement(e,
                            mosaicProducer);
                    mosaicInputs.add(reprojectedMosaicElement);
                }
            } catch (FactoryException e) {
                throw new IllegalStateException(e);
            }
        }

        return mosaicInputs;
    }

    private MosaicElement reprojectMosaicElement(MosaicElement mosaicElement,
            CRSBoundMosaicProducer mosaicProducer) throws FactoryException {

        if (!CRS.equalsIgnoreMetadata(targetCRS, mosaicProducer.getCrs())) {
            GridCoverageFactory factory = new GridCoverageFactory(null);

            ReferencedEnvelope submosaicBBOX = computeSubmosaicBoundingBox(mosaicProducer,
                    mosaicElement);
            GridCoverage2D submosaicCoverage = factory.create("submosaic",
                    mosaicElement.getSource(), submosaicBBOX);
            GridCoverage2D resampledCoverage = (GridCoverage2D) operations
                    .resample(submosaicCoverage, targetCRS);

            RenderedImage resampledImage = positionInOutputMosaic(resampledCoverage);

            PlanarImage alphaBand = resampledImage.getColorModel().hasAlpha()
                    ? new ImageWorker(resampledImage).retainLastBand().getPlanarImage() : null;

            Object property = resampledImage.getProperty("ROI");
            ROI overallROI = (property instanceof ROI) ? (ROI) property : null;
            return new MosaicElement(alphaBand, overallROI, resampledImage,
                    mosaicElement.getPamDataset());
        } else {
            return mosaicElement;
        }
    }

    /**
     * Computes the sub-mosaic spatial extend based on the image size and the target grid to world transformation
     * 
     * @param mosaicProducer
     * @param image
     * @return
     * @throws FactoryException
     */
    private ReferencedEnvelope computeSubmosaicBoundingBox(CRSBoundMosaicProducer mosaicProducer,
            MosaicElement mosaicElement) throws FactoryException {
        RenderedImage image = mosaicElement.getSource();
        MathTransform2D tx = mosaicProducer.rasterLayerResponse.getFinalGridToWorldCorner();
        double[] mosaicked = new double[] { image.getMinX(), image.getMinY(),
                image.getMinX() + image.getWidth(), image.getMinY() + image.getHeight() };
        try {
            tx.transform(mosaicked, 0, mosaicked, 0, 2);
        } catch (TransformException e) {
            throw new FactoryException(e);
        }
        ReferencedEnvelope submosaicBBOX = new ReferencedEnvelope(mosaicked[0], mosaicked[2],
                mosaicked[1], mosaicked[3], mosaicProducer.getCrs());
        return submosaicBBOX;
    }

    /**
     * Given a coverage in the mosaic target CRS generates an RenderedImage properly positioned
     * in the mosaic output raster space
     * 
     * @param resampledCoverage
     * @return
     */
    private RenderedImage positionInOutputMosaic(GridCoverage2D resampledCoverage) {
        RenderedImage image = resampledCoverage.getRenderedImage();
        
        // now create the overall transform
        final AffineTransform finalRaster2Model = new AffineTransform((AffineTransform2D) resampledCoverage.getGridGeometry().getGridToCRS());
        finalRaster2Model.concatenate(CoverageUtilities.CENTER_TO_CORNER);

        // keep into account translation factors to place this tile
        AffineTransform finalWorldToGridCorner = (AffineTransform) rasterLayerResponse.getFinalWorldToGridCorner();
        finalRaster2Model.preConcatenate(finalWorldToGridCorner);
        RasterLayerRequest request = rasterLayerResponse.getRequest();
        final Interpolation interpolation = request.getInterpolation();

        // paranoiac check to avoid that JAI freaks out when computing its internal layouT on images that are too small
        Rectangle2D finalLayout = ImageUtilities.layoutHelper(image,
                (float) finalRaster2Model.getScaleX(), (float) finalRaster2Model.getScaleY(),
                (float) finalRaster2Model.getTranslateX(),
                (float) finalRaster2Model.getTranslateY(), interpolation);
        if (finalLayout.isEmpty()) {
            if (LOGGER.isLoggable(java.util.logging.Level.INFO))
                LOGGER.info("Unable to create a granuleDescriptor " + this.toString()
                        + " due to jai scale bug creating a null source area");
            return null;
        }

        // apply the affine transform conserving indexed color model
        final RenderingHints localHints = new RenderingHints(JAI.KEY_REPLACE_INDEX_COLOR_MODEL,
                interpolation instanceof InterpolationNearest ? Boolean.FALSE : Boolean.TRUE);
        if (XAffineTransform.isIdentity(finalRaster2Model,
                CoverageUtilities.AFFINE_IDENTITY_EPS)) {
            return image;
        } else {
            ImageWorker iw = new ImageWorker(image);
            iw.setRenderingHints(localHints);
            iw.affine(finalRaster2Model, interpolation, request.getBackgroundValues());
            RenderedImage renderedImage = iw.getRenderedImage();
            // Propagate NoData
            if (iw.getNoData() != null) {
                PlanarImage t = PlanarImage.wrapRenderedImage(renderedImage);
                t.setProperty(NoDataContainer.GC_NODATA, new NoDataContainer(iw.getNoData()));
                renderedImage = t;
            }
            return renderedImage;
        }
    }

    /**
     * This submosaic producer takes a CRS and then only accepts granules that match that CRS.
     *
     */
    private static class CRSBoundMosaicProducer extends BaseSubmosaicProducer {

        private final CoordinateReferenceSystem crs;

        public CRSBoundMosaicProducer(RasterLayerResponse rasterLayerResponse, boolean dryRun, CoordinateReferenceSystem targetCRS,
                GranuleDescriptor templateDescriptor) {
            super(rasterLayerResponse, dryRun);
            this.crs = targetCRS;

            // always accept the template granule descriptor
            super.accept(templateDescriptor);
        }

        @Override
        public List<MosaicElement> createMosaic() throws IOException {
            final MosaicElement mosaic = (new Mosaicker(this.rasterLayerResponse,
                    collectGranules(), MergeBehavior.FLAT)).createMosaic(false, true);
            if (mosaic == null) {
                return Collections.emptyList();
            } else {
                return Collections.singletonList(mosaic);
            }
        }

        @Override
        public boolean accept(GranuleDescriptor granuleDescriptor) {
            //make sure the CRSs match
            boolean shouldAccept = false;

            //need to check that the granule matches CRS
            CoordinateReferenceSystem granuleCRS = granuleDescriptor.getGranuleEnvelope().getCoordinateReferenceSystem();
            shouldAccept = CRS.equalsIgnoreMetadata(granuleCRS, this.crs);

            return shouldAccept && super.accept(granuleDescriptor);
        }

        public CoordinateReferenceSystem getCrs() {
            return crs;
        }
    }
    
}

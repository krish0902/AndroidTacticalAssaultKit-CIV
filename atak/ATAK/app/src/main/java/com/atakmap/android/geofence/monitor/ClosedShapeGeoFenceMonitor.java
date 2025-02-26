
package com.atakmap.android.geofence.monitor;

import android.util.Pair;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.geofence.alert.GeoFenceAlerting;
import com.atakmap.android.geofence.data.GeoFence;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.spatial.SpatialCalculator;
import android.database.sqlite.SQLiteException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class ClosedShapeGeoFenceMonitor extends GeoFenceMonitor {

    private static final String TAG = "ClosedShapeGeoFenceMonitor";
    private static final double METERS_THRESHOLD_TO_UPDATE_POINT = 2;

    private final SpatialCalculator _spatialCalc;

    /**
     * Handle for the _shape in the _spatialCalc
     */
    private long _fenceHandle;

    /**
     * Cache shape so we don't have to constantly cache
     */
    private final DrawingShape _shape;

    /**
     * Map UID to tracking info (last location, and _spatialCalc handle)
     *
     * Cache last point of each monitored shape, used to determine if point in _spatialCalc
     * needs to be updated
     */
    private final Map<String, Pair<GeoPoint, Long>> _lastPoints;

    /**
     * ctor
     *
     * @param view the map view
     * @param spatialCalc the spatial calculator
     * @param fence the geofence to be used
     * @param item shape with at least 3 points
     */
    public ClosedShapeGeoFenceMonitor(MapView view,
            SpatialCalculator spatialCalc, GeoFence fence, DrawingShape item) {
        super(view, fence, item);
        _shape = item;
        _spatialCalc = spatialCalc;
        _lastPoints = new HashMap<>();

        if (item.getNumPoints() >= 3) {
            //get points and insert first point as last point
            List<GeoPoint> points = new ArrayList<>(
                    Arrays.asList(item.getPoints()));
            points.add(points.get(0));
            //insert shape into spatial calculator

            try {
                synchronized (_spatialCalc) {
                    _fenceHandle = _spatialCalc
                            .createPolygon(points);
                }
            } catch (SQLiteException err) {
                Log.e(TAG,
                        "something very BAD happened when building the monitor "
                                + item,
                        err);
                _fenceHandle = -1;
            }
        } else {
            Log.w(TAG, "Shape does not have enough points to create fence: "
                    + item.getNumPoints());
            _fenceHandle = -1;
        }

        if (_fenceHandle <= 0) {
            Log.w(TAG,
                    "Unable to insert shape: "
                            + ATAKUtilities.getDisplayName(
                                    ATAKUtilities.findAssocShape(_shape)));
        } else {
            _shape.addOnPointsChangedListener(_shapeChanged);
        }
    }

    @Override
    public boolean isValid() {
        if (_fenceHandle <= 0)
            return false;

        return super.isValid();
    }

    @Override
    public void dispose() {
        super.dispose();
        if (_shape != null && _shapeChanged != null) {
            _shape.removeOnPointsChangedListener(_shapeChanged);
        }
    }

    private final Shape.OnPointsChangedListener _shapeChanged = new Shape.OnPointsChangedListener() {
        @Override
        public void onPointsChanged(Shape shape) {
            if (_fenceHandle <= 0) {
                Log.w(TAG,
                        "Unable to update shape: "
                                + ATAKUtilities.getDisplayName(
                                        ATAKUtilities.findAssocShape(_shape)));
                return;
            }

            if (_shape.isBulkOperation())
                // Shape is busy processing - wait until finished
                return;

            if (!_shape.isClosed() || _shape.getNumPoints() < 3) {
                //mark as invalid
                _fenceHandle = -1;
                Log.w(TAG,
                        "Unable to update un-closed shape: "
                                + ATAKUtilities.getDisplayName(
                                        ATAKUtilities.findAssocShape(_shape))
                                + ", " + _shape.getNumPoints());
                return;
            }

            Log.d(TAG,
                    "Updating shape points: "
                            + ATAKUtilities.getDisplayName(
                                    ATAKUtilities.findAssocShape(_shape))
                            + ", handle=" + _fenceHandle);

            //TODO synchronized...?
            //get points and insert first point as last point
            List<GeoPoint> points = new ArrayList<>(
                    Arrays.asList(_shape.getPoints()));
            points.add(points.get(0));
            //update shape in spatial calculator

            synchronized (_spatialCalc) {
                _spatialCalc.updatePolygon(_fenceHandle, points);
            }
        }
    };

    @Override
    public synchronized boolean removeItem(PointMapItem item) {
        //remove point from _spatialCalc
        //TODO is this necessary? doesn't it just get deleted when ATAK restarts anyway?
        //if so, why waste time here?

        try {
            Pair<GeoPoint, Long> lastPoint = _lastPoints.get(item.getUID());
            if (lastPoint != null) {
                //long s2 = android.os.SystemClock.elapsedRealtime();
                synchronized (_spatialCalc) {
                    _spatialCalc.deleteGeometry(lastPoint.second);
                }
                //long deleteTally = (android.os.SystemClock.elapsedRealtime() - s2);

                //Log.d(TAG, "Checked " + toString() + ", delete calls took: " + (deleteTally / 1000D));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove item from spatial calc", e);
        }

        return super.removeItem(item);
    }

    @Override
    protected List<GeoFenceAlerting.Alert> check(GeoFence.Trigger trigger,
            List<PointMapItem> items, boolean bCheckPrevious) {
        List<GeoFenceAlerting.Alert> ret = new ArrayList<>();

        if (FileSystemUtils.isEmpty(items)) {
            Log.w(TAG, "No items to track: " + toString());
            return ret;
        }

        if (_fenceHandle < 1) {
            Log.w(TAG, "Failed to insert Polygon into SpatialCalculator: "
                    + toString());
            return ret;
        }

        PointMapItem centerMarker = _shape.getAnchorItem();

        //long start = android.os.SystemClock.elapsedRealtime();
        //long containsTally = 0, insertTally = 0, updateTally=0, s2 = 0;

        //get timestamp once, for efficiency, even though we get each points location at slightly future times...
        long timestamp = new CoordinatedTime().getMilliseconds();

        //sync on 'this' for use of _uidsLastState
        synchronized (_uidsLastState) {
            for (PointMapItem item : items) {
                try {
                    if (centerMarker == item)
                        continue;

                    if (item == null) {
                        Log.w(TAG, "check point item not valid");
                        continue;
                    }

                    GeoPoint point = item.getPoint();
                    if (point == null) {
                        Log.w(TAG, "check item point item not valid");
                        continue;
                    }

                    if (checkDismissed(item)) {
                        continue;
                    }

                    Pair<GeoPoint, Long> lastPoint = _lastPoints.get(item
                            .getUID());
                    long pointHandle = -1;
                    if (lastPoint == null) {
                        //no last point, insert
                        //Log.d(TAG, "Inserting point: " + item.getUID());
                        //s2 = android.os.SystemClock.elapsedRealtime();
                        synchronized (_spatialCalc) {
                            pointHandle = _spatialCalc.createPoint(point);
                        }
                        //insertTally += (android.os.SystemClock.elapsedRealtime() - s2);

                        if (pointHandle < 1) {
                            Log.w(TAG,
                                    "Failed to insert point into SpatialCalculator");
                            continue;
                        }

                        _lastPoints.put(item.getUID(),
                                new Pair<>(point, pointHandle));
                    } else {
                        //see if map item has moved
                        pointHandle = lastPoint.second;
                        double moved = GeoCalculations
                                .distanceTo(lastPoint.first, point);
                        if (moved >= METERS_THRESHOLD_TO_UPDATE_POINT) {
                            //Log.d(TAG, "Updating point: " + item.getUID());
                            //s2 = android.os.SystemClock.elapsedRealtime();
                            synchronized (_spatialCalc) {
                                _spatialCalc.updatePoint(pointHandle, point);
                            }
                            _lastPoints
                                    .put(item.getUID(),
                                            new Pair<>(point,
                                                    pointHandle));
                            //updateTally += (android.os.SystemClock.elapsedRealtime() - s2);
                        } else {
                            //no -op just use current location
                            //Log.d(TAG, "Reusing point: " + item.getUID());
                        }
                    }

                    if (pointHandle < 1) {
                        Log.w(TAG,
                                "Failed to insert or update point into SpatialCalculator");
                        continue;
                    }

                    //let SpatialCalculator do it's magic
                    //s2 = android.os.SystemClock.elapsedRealtime();
                    boolean bInside;
                    synchronized (_spatialCalc) {
                        bInside = inElevationRange(point) && _spatialCalc
                                .contains(_fenceHandle, pointHandle);
                    }
                    //containsTally += (android.os.SystemClock.elapsedRealtime() - s2);

                    //now check if we should alert based on trigger
                    if (bCheckPrevious) {
                        //get previous state
                        boolean bPreviouslyInside = getLastState(item.getUID());

                        if (checkTrigger(trigger, bInside, bPreviouslyInside)) {
                            //                        Log.d(TAG, "ALERT Point " + item.getUID()
                            //                                + " for fence: " + toString());
                            ret.add(new GeoFenceAlerting.Alert(this, item,
                                    timestamp, new GeoPoint(point), bInside));
                        }

                        //only update state if bCheckPrevious
                        _uidsLastState.put(item.getUID(), bInside);
                    } else {
                        if (checkTrigger(trigger, bInside)) {
                            //                        Log.d(TAG, "ALERT Point " + item.getUID()
                            //                                + " for fence: " + toString());
                            ret.add(new GeoFenceAlerting.Alert(this, item,
                                    timestamp, new GeoPoint(point), bInside));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error while monitoring", e);
                }
            } //end items loop
        }

        //        long stop = android.os.SystemClock.elapsedRealtime();
        //        Log.d(TAG, "Checked " + toString() + " monitor in seconds: "
        //                + ((double) stop - start) / 1000D
        //                + ", insert calls took: " + (insertTally / 1000D)
        //                + ", update calls took: " + (updateTally / 1000D)
        //                + ", contains calls took: " + (containsTally / 1000D)
        //                + ", delete calls took: " + (deleteTally / 1000D));

        return ret;
    }

    @Override
    public GeoPointMetaData getCenter() {
        return _shape.getCenter();
    }

    //return the distance of the furthest point from center
    @Override
    public double getfurthestPointRange() {
        double furthestRangeKM = 0;
        for (GeoPoint point : _shape.getPoints()) {
            double distance = GeoCalculations
                    .distanceTo(_shape.getCenter().get(), point);
            if (distance > furthestRangeKM) {
                furthestRangeKM = distance;
            }
        }
        return furthestRangeKM;
    }
}

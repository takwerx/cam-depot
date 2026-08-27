package com.atakmap.android.camdepot.model;

/**
 * One camera: the static half from {@code static/<ST>.json.gz}, the moving half
 * from {@code dynamic/<ST>.json.gz}, merged on {@code id}.
 *
 * <p>Deliberately mutable in its dynamic fields. A refresh updates the existing
 * object rather than replacing it, because the map marker and the sensor FOV cone
 * are bound to identity — recreating cameras every minute is what orphans a
 * {@link com.atakmap.android.maps.SensorFOV} and makes the cone silently stop
 * following. See PLAN-CamDepot-v0.1.md, "Sensor FOV".
 */
public final class Camera {

    // ---- static: set once, never reassigned -------------------------------
    public final String id;
    public final String name;
    public final String host;
    public final double lat;
    public final double lon;
    public final String county;
    public final String state;
    public final String provider;
    public final String sponsor;
    /** True if the camera can be steered. Fire lookouts are; DOT and FAA are not. */
    public final boolean ptz;
    /** True for the wildfire lookout networks, false for DOT and FAA cameras. */
    public final boolean fire;
    /**
     * HLS playlist ATAK's video player can open, or empty.
     *
     * <p>Only Caltrans publishes these, and only for about three quarters of its
     * cameras. Everything else in the catalog is stills, which libVLC will not play —
     * so this is what separates a camera that can stream from one that cannot.
     */
    public final String stream;
    /**
     * The agency's own current-frame URL, where the agency publishes one.
     *
     * <p>Caltrans serves a stable per-camera JPEG and refreshes it every 15 s. Going
     * direct replaces a second-hand copy on ALERT West's CDN that had to be located
     * by filename and date path, and which reached operators hours stale or showing
     * "down for construction". Empty for cameras with no such URL, which fall back to
     * the CDN path.
     */
    public final String still;

    // ---- dynamic: rewritten on every refresh ------------------------------
    /** Pan in degrees, true north. NaN when the camera does not report one. */
    public double pan = Double.NaN;
    /** Tilt in degrees; negative looks down. */
    public double tilt = Double.NaN;
    /** Horizontal field of view in degrees. NaN when unreported. */
    public double fov = Double.NaN;
    public boolean offline;
    /** Current image filename; empty until the images shard is loaded. */
    public String image = "";

    public Camera(String id, String name, String host, double lat, double lon,
            String county, String state, String provider, String sponsor,
            boolean ptz, boolean fire, String stream, String still) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.lat = lat;
        this.lon = lon;
        this.county = county;
        this.state = state;
        this.provider = provider;
        this.sponsor = sponsor;
        this.ptz = ptz;
        this.fire = fire;
        this.stream = stream == null ? "" : stream;
        this.still = still == null ? "" : still;
    }

    /** Human label: "Keller Peak 1" rather than "Keller_Peak_1". */
    public String label() {
        return name == null ? id : name.replace('_', ' ');
    }

    public boolean hasStream() {
        return !stream.isEmpty();
    }

    /** True when there is enough to draw a sensor cone. */
    public boolean hasFov() {
        return !Double.isNaN(pan) && !Double.isNaN(fov) && fov > 0;
    }

    /** Great-circle distance in meters, for the radius filters. */
    public double metersFrom(double fromLat, double fromLon) {
        final double R = 6371008.8;
        final double p1 = Math.toRadians(fromLat), p2 = Math.toRadians(lat);
        final double dp = p2 - p1;
        final double dl = Math.toRadians(lon - fromLon);
        final double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}

package com.atakmap.android.camdepot.data;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

/**
 * Distances in whatever units the operator has already told ATAK they want.
 *
 * <p>ATAK keeps this in {@code rab_rng_units_pref} (Range &amp; Bearing range units),
 * and the stored value <em>is</em> the {@link Span} type constant — so it maps
 * straight through with no translation table:
 *
 * <pre>
 *   "0" -&gt; Span.ENGLISH (feet / miles)     &lt;- ATAK's default
 *   "1" -&gt; Span.METRIC  (metres / km)
 *   "2" -&gt; Span.NM      (nautical miles)
 * </pre>
 *
 * <p>Note that 0 is <em>English</em>, not metric. Assuming the obvious ordering gets
 * it exactly backwards, which is how this plugin originally showed kilometres to an
 * operator whose ATAK was set to miles.
 *
 * <p>The preference is read on each call rather than cached: it can change in ATAK's
 * settings while the panel is open, and a stale unit is worse than a cheap lookup.
 */
public final class Units {

    private Units() {
    }

    /** @return one of {@link Span#ENGLISH}, {@link Span#METRIC}, {@link Span#NM} */
    public static int type() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            // A malformed preference must not stop the panel drawing.
        }
        return Span.ENGLISH;
    }

    /** Format a distance in metres the way ATAK would, e.g. "12.4 mi" or "20 km". */
    public static String format(double metres) {
        try {
            return SpanUtilities.formatType(type(), metres, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(metres) + " m";
        }
    }

    /** The large unit the operator thinks in: miles, kilometres or nautical miles. */
    public static Span bigSpan() {
        switch (type()) {
            case Span.METRIC:
                return Span.KILOMETER;
            case Span.NM:
                return Span.NAUTICALMILE;
            default:
                return Span.MILE;
        }
    }

    public static String bigLabel() {
        switch (type()) {
            case Span.METRIC:
                return "km";
            case Span.NM:
                return "NM";
            default:
                return "mi";
        }
    }

    /**
     * Format a distance in the operator's <em>large</em> unit, always.
     *
     * <p>{@link #format} lets ATAK pick the unit, which is right for a readout but
     * wrong for a fixed list: 800 m comes out as "2624 ft" because it sits under
     * ATAK's feet-to-miles threshold, so a menu of preset distances ends up mixing
     * feet and miles and reads as noise. This pins the unit so the list is
     * comparable top to bottom.
     */
    public static String formatBig(double metres) {
        final double n = SpanUtilities.convert(metres, Span.METER, bigSpan());
        final String num = (n < 10 && n != Math.floor(n))
                ? String.format(java.util.Locale.US, "%.1f", n)
                : String.format(java.util.Locale.US, "%.0f", n);
        return num + " " + bigLabel();
    }

    /** Convert a count of {@link #bigSpan()} units into metres. */
    public static double bigToMetres(double n) {
        try {
            return SpanUtilities.convert(n, bigSpan(), Span.METER);
        } catch (RuntimeException e) {
            return n * 1609.344;
        }
    }
}

ATAK Plugin — Cam Depot


_________________________________________________________________
PURPOSE AND CAPABILITIES

Public traffic and wildfire cameras on the ATAK map. About 38,000 of them across
54 states and provinces, from state departments of transportation, the FAA, and
the wildfire lookout networks, browsed from a side panel and drawn on the map.

Answers "what does it look like there right now" without leaving ATAK. An
operator filters to an area, brings the cameras in, taps one for its latest
picture, and where the agency publishes a stream, plays it as live video in
ATAK's own player.

Capabilities:

  - About 38,000 cameras, refreshed from the publishing agencies, with roughly
    9,000 carrying a playable video stream.
  - Filter by state, provider, county, distance from your position or from a
    point on the map, and by whether a camera streams, is a still, or belongs to
    a wildfire network. Each filter states what it will cost before it is used.
  - "On screen only" narrows the list to the current map view and follows it as
    the map moves, the way ATAK's Overlay Manager does.
  - Search by camera name across every state at once.
  - Favorites, kept on the device and spanning states, pinned above the ordinary
    list rather than replacing it.
  - Current still image per camera, fetched straight from the agency by the
    device, typically less than a minute old.
  - Live video where the agency publishes a stream, played by ATAK. Streams are
    verified as serving before they are published, so a camera that offers video
    was serving video when the catalog was built.
  - Bearing line with its azimuth for cameras that report where they point,
    following steerable cameras as they slew.
  - Operator-set zoom threshold controlling when cameras draw, set by example
    from the current view or from presets described in plain terms.
  - Counties assigned from Census boundaries rather than from the camera feeds,
    so county filtering works on every camera in the United States.

Data is collected by a publisher that polls each agency once and serves a single
catalog, so a device never fetches from twenty-five agencies itself. Camera
imagery and video are fetched by the device directly from the agency and do not
pass through any takwerx service.

A step-by-step user guide with screenshots lives at docs/USER_GUIDE.md in the
repository, with the images under docs/screenshots/ (both excluded from the
source submission zip).

_________________________________________________________________
STATUS

Release candidate. Version 0.4.

Exercised on hardware: Samsung Galaxy XCover Pro (ATAK-CIV 5.8.0.3). Panel,
filters, favorites, camera imagery, live video, bearing lines and the zoom
threshold have been run against the live catalog, as a release build with
proguard enabled rather than a debug build only.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/camdepot/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only.

  The plugin fetches a camera catalog over HTTPS, polls a small delta feed for
  camera bearings and online status while the panel is open, and fetches camera
  imagery and video directly from the publishing agency. Every request the
  plugin makes is HTTPS; the HTTP client refuses a non-HTTPS request, and any
  camera whose published stream or image URL is not HTTPS is rejected rather
  than offered.

  Live video is served over HTTPS on TCP 10443 for the wildfire networks, because
  ATAK's player cannot read a multipart stream over HTTP/2 and that listener is
  HTTP/1.1 only. Agency-hosted streams are HTTPS on TCP 443.

  No inbound ports. No listening sockets. No traffic to or from the TAK server;
  no CoT is generated or consumed.

  The plugin is inert without a network connection. It stores no camera imagery
  and keeps nothing on the device except the operator's own settings and
  favorites list.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.8.
  A network connection. The plugin holds no offline camera data; a camera
  picture is only meaningful when it is current.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware, no
  sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

  The user manual is written in typst under docs/user_manual/ and compiled into
  assets/usermanual.pdf by gradle/typst.gradle when ATAK_CI=1. The built PDF is
  not committed.

_________________________________________________________________
DEVELOPER NOTES

  The catalog is built off-device and published as static, gzipped, per-state
  shards. A device fetches the shards for the state it is showing rather than a
  national file: one state is tens of kilobytes where the upstream aggregate is
  megabytes, and a tablet has no business fetching the latter.

  The catalog is split by how fast each part changes. Names, positions and URLs
  are static and cached hard. Pan, tilt and online status move continuously and
  come from a small delta feed keyed to a sequence number, so a poll that finds
  nothing changed answers in tens of bytes and a bearing on the map can follow a
  camera as it slews. A camera's current image filename is asked for when the
  operator opens that camera, because it is only wanted then and it is stale
  within a minute otherwise.

  Everything published to a device is treated as untrusted. The catalog is built
  from about twenty-five third-party government APIs, so state codes are
  validated before they name a file and media URLs are validated as HTTPS both
  when the catalog is built and again on the device before a URL is handed to
  ATAK's player.

  Upstream failures are assumed rather than hoped against. Stream liveness is
  cached across publisher runs and a host that answers nothing on a sample is
  treated as an outage rather than as an inventory of dead cameras, so an
  agency's bad afternoon does not silently delete its video from the catalog.
  A publisher run that would lose more than a set share of any state's cameras
  refuses to publish, because that is what an upstream failure looks like from
  downstream and nothing else does.

  Markers are created once and mutated, never deleted and re-added. ATAK's
  sensor field-of-view watches its parent marker and detaches when that marker
  leaves its group, so a refresh that recreates markers orphans every cone.
  Marker creation is also spread across frames with a yield between batches, and
  video entries are registered in one batch when the queue drains: each
  registration broadcasts a hierarchy refresh, and doing that per batch rebuilds
  ATAK's Overlay Manager enough times to stop the application responding.

#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Cam Depot",
   plugin-version: "1.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Cam Depot puts public traffic and wildfire cameras on the ATAK map. Roughly
38,000 of them, across 54 states and provinces, from state departments of
transportation, the FAA, and the wildfire lookout networks.

You pick an area, filter down to what you want, and bring it in. Tap a camera
for its latest picture; where the agency publishes a stream, play it as live
video inside ATAK.

Nothing here needs an account or a key. The cameras are public; Cam Depot finds
them, keeps them current, and puts them where you are looking.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("1.png", width: 100%)
][
  Open it from the ATAK toolbar. The icon sits with the other tools; tapping it
  again closes the panel, so it is one tap on and one tap off when you want the
  map to yourself.

  The first thing it does is load the catalog, then the cameras for the state it
  is showing. A line at the top of the panel says so while it works.
]
]

#tak-slide[
= Finding cameras

The panel is the control. The map shows exactly what the panel selects, never
the whole catalog.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("2.png", width: 100%)
][
  *State* picks the state or province, and *Provider* narrows to one agency
  within it.

  *County* narrows further still, and takes as many counties as you want at
  once.

  *On screen only* narrows the list to whatever is in the current map view.
]
]

#tak-slide[
== State, provider, county

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("3.png", width: 100%)

  Fifty-four states and provinces, listed alphabetically.
][
  #image("4.png", width: 100%)

  Each agency says how many cameras it has *before* you pick it.
][
  #image("5.png", width: 100%)

  Counties tick on and off. Pick as many as the job covers.
]

#v(6pt)
Counties come from Census boundaries rather than from the camera feeds, so they
work on every camera in the United States, not only the ones whose agency
happened to publish a county.
]

#tak-slide[
== A filter says what it costs

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("6.png", width: 100%)
][
  Nothing in this panel makes you guess. The county button counts what you
  picked, and the line above counts what is left: three counties of California,
  and 193 cameras of 4,644.

  Every filter behaves this way, so you can see what one will cost before you
  use it and what it cost afterwards.
]
]

#tak-slide[
== Following the map

*On screen only* limits the list to the cameras inside the current map view, and
keeps following as you pan and zoom. Move the map and the list moves with it.

#v(6pt)
#image("20.jpg", width: 74%)

#v(4pt)
The count on the control and the count in the status line are the same number as
what is drawn on the map -- twenty-two here. This is the fastest way to answer
"what is near this spot": put the spot on screen, and the panel is already the
answer. It follows the map rather than your own position, so it works just as
well for an area you are not standing in.

Leave it off and the list stays the whole state, however far the map wanders.
]

#tak-slide[
= Find a camera by name

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("7.png", width: 76%)
][
  Typing in the search box searches *every state at once*, not only the one in
  the dropdown. Here "pitts" finds a wildfire lookout in California and a
  highway camera in Kansas together, 1,300 miles apart.

  Camera names are the agency's own -- "I-40/75 \@ West Hills", "Keller
  Peak 1" -- so a road number, a landmark or a peak name will usually find it.

  *Clear* sits beside the box and lights up only while something is in it, so a
  live Clear button is the panel telling you a search is still filtering the
  list.
]
]

#tak-slide[
== Reading a row

Each row is one camera. The colored dot is what kind it is -- orange for a
wildfire lookout, blue for a department of transportation or FAA camera -- and
the legend above the list says so.

Under the name: the state, the agency, the county, and how far away it is.
A small #sym.triangle.filled.r *live* marks a camera that streams.

*Go to* moves the map to that camera and zooms in close enough to see the road
it is watching. Tapping the row itself opens the camera.

Below the list the panel says how many cameras matched, how many are drawn on
the map, and, if the map is not showing all of them, why. A view that is trimmed
says so rather than looking complete.
]

#tak-slide[
= Choosing what to show

#toolbox.side-by-side(columns: (12fr,))[
  #image("8.png", width: 100%)
]

#v(4pt)
*Video*, *Still* and *Fire* each carry their own count for the state you are in,
so you can see what a filter will leave you before you tick it. A filter with
nothing behind it is disabled rather than left to be discovered.

]

#tak-slide[
= Distance from a point

Everything within a set distance, and nothing beyond it.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", width: 100%)
][
  The slider runs to 50 in whatever units ATAK is set to, and the label above it
  always reads the current distance and the point it is measured from.

  *Measuring from* rotates between your own position and the center of the map,
  and says which one is in force rather than only offering to change it.

  *Use this extent* takes the radius from what is on screen, so "everything I am
  looking at" is one tap rather than a guess at a number.

  *Presets* offers Off, 2, 5, 10, 25 and 50, and names the one in force. Drag the
  slider to something in between and it reads *Presets* again, because no preset
  is active any more.
]
]

#tak-slide[
// Deliberately bold text rather than a heading. The template builds its Contents
// page from #outline(), and a heading on THIS slide makes that page appear
// carrying a single stray entry -- checked by demoting it, and by trying both
// heading levels. Not worth fighting the template for a subheading.
*It follows, and it is remembered*

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("9a.png", width: 92%)
][
  Measured from *My Location* the circle travels with you, so a radius set at the
  start of a drive still means the same thing an hour later.

  Measured from *Map Center* it follows the map. Pan somewhere else and the list
  is about where you are now looking.

  Both the distance and the point it is measured from survive restarting ATAK,
  along with the rest of the panel.

  Distances follow whatever units ATAK is set to.
]
]

#tak-slide[
= Favorites

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("12.png", width: 100%)
][
  The star at the end of each row marks a camera as a favorite. Favorites are
  kept on the device and survive restarting ATAK.

  The same star appears in the camera's own pane, so you can mark one while you
  are looking at it.
]
]

#tak-slide[
== Favorites First

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12a.png", width: 100%)

  Tapping *Favorites* turns the button gold and pins them.
][
  #image("12b.png", width: 100%)

  They sit in their own section, with the whole state still below.
]

#v(6pt)
Favorites are not tied to a state. A lookout in California and a highway camera
in Ontario sit in the same list. Favorites First keeps the handful of cameras
you actually watch within reach without giving up the rest of the catalog.

#v(6pt)
*While Favorites First is on, a favorite ignores every filter.* State, county,
radius and the Show boxes all pass it by, and it stays on the map whatever the
panel says -- which is the point: a camera you starred does not vanish because of
a setting you left on an hour ago. The status line says so. Turn Favorites First
off and they become ordinary cameras again, filtered like everything else.
]

#tak-slide[
= Opening a camera

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("13.png", width: 100%)
][
  Tapping a camera, in the list or on the map, opens it in a side pane so the
  map stays live behind it.

  The pane names the camera, then says the agency, whether it is online, whether
  it steers, and the county -- then shows the latest picture. *Refresh* fetches
  a new frame; while one is on its way the pane says
  "Fetching the latest picture#sym.dots.h" rather than sitting blank, because a
  slow agency and a broken one look identical otherwise.

  Pictures are current to within about a minute on most networks. Some agencies
  publish on their own cycle: British Columbia refreshes its whole province
  every few minutes, so a picture there can be a few minutes old even when
  everything is working.

  A few cameras publish live video and no picture at all -- all of Maryland
  works this way. Those say so, and point you at the video button.
]
]

#tak-slide[
= Live video

Where the agency publishes a stream, *Live video* plays it in ATAK's own player.

Not every camera has one. A camera that only publishes pictures says
"Video (still only)" rather than offering a button that cannot work. Of the
38,000 cameras in the catalog, about 10,500 stream.

Streams are checked before they are published, so a camera that offers video was
serving video when the catalog was built. Agencies do have outages; when one
does, its cameras keep their video button rather than silently losing it, and
they come back when the agency does.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.jpg", width: 100%)
][
  ATAK's own player, with its snapshot and record controls. The overlay across
  the picture is the agency's, not ours.

  Closing the video returns you to the map. The toolbar icon reopens the panel.
]
]

#tak-slide[
= Two kinds of camera on the map

The map itself says which cameras stream and which only take pictures, so you
can tell before you open one.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("21.jpg", width: 92%)

  *Streams.* ATAK's own camcorder.
][
  #image("22.jpg", width: 92%)

  *Stills only.* A plain camera.
]

#v(6pt)
The list says the same thing a second way: a streaming camera carries a small
#sym.triangle.filled.r *live* under its name, and its pane offers
*#sym.triangle.filled.r Live video* where a stills camera offers
*Video (still only)*.
]

#tak-slide[
= Bearings

Cameras that report which way they are pointing can draw a bearing line on the
map, labeled with its azimuth. *Show bearing* turns it on, and the marker turns
orange while it is on. On a steerable camera the line follows the camera as it
moves, updating every few seconds.

#v(6pt)
#image("16.jpg", width: 74%)

#v(4pt)
The pane and the map give the same number: *Bearing 355°T* beside the camera's
name, `355°T` on the line itself.

#v(6pt)
A bearing stays on when you pan away from its camera and comes back with it, so
looking at something else does not put it away.

#v(4pt)
#image("16b.png", width: 74%)

#v(4pt)
Bearings go on one camera at a time, so the panel has one control to put them all
away. It carries the count, is greyed out when there are none, and clears lines on
cameras that are currently off screen as well as the ones in front of you.
]

#tak-slide[
== What the line is, and is not

The line is the *direction* the camera points. It is not how wide it sees.

A camera's pane reports both -- *Bearing 355°T   Field of View 62.8°* -- but only
the bearing is drawn. A field of view is a wedge, and sixteen hundred overlapping
wedges is a map you cannot read, so Cam Depot draws the direction and leaves the
width as a number.

#v(6pt)
Opening a stills camera that reports a bearing shows its line automatically for
as long as the picture is open, then puts it away again. That answers "what am I
looking at" at the moment you are asking it.

A camera that publishes no bearing says "No bearing reported" rather than
drawing a line that would be a guess.

#v(6pt)
One thing worth knowing: on the wildfire networks the bearing is ahead of the
picture. The agency reports where a camera is pointing immediately and publishes
its image twenty to thirty seconds later. The line is the truth; the picture is
the recent past.
]

#tak-slide[
= Drawing on the map

Thirty-eight thousand markers would be unreadable, so Cam Depot draws cameras
only when you are zoomed in past a threshold you choose.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("10a.png", width: 100%)

  #v(4pt)
  *Use this zoom* takes whatever you are looking at right now and makes it the
  threshold -- you set it by example rather than by picking a number.
][
  #image("10.png", width: 100%)

  #v(4pt)
  *Presets* offers a few starting points described in plain terms, from a city
  block to a region.
]
]

#tak-slide[
== The panel always says what the map is doing

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("17.jpg", width: 78%)
][
  #image("17a.png", width: 100%)

  #v(6pt)
  Which it is doing: drawn at this scale or closer, what the scale bar reads
  now, and whether anything is currently hidden.

  If more cameras match than can be drawn legibly, the panel says how many were
  left off and to zoom in. It will not quietly show you part of the picture.
]
]

#tak-slide[
= Keeping up to date

The catalog changes: agencies are added, cameras come and go, and stream
addresses move. *Sync* re-reads the catalog and reloads the state from scratch,
so a catalog that changed while the plugin was running is picked up without
restarting ATAK.

Use it when a camera you expect is missing, or when video that worked yesterday
does not connect today. It takes a few seconds.
]

#tak-slide[
= Where the cameras come from

Cam Depot does not talk to twenty agencies from your device. One service
collects from them, checks what it collected, and publishes a single catalog
that every device reads. That keeps a tablet off the hook for a six-megabyte
download, and keeps one polite poller in front of each agency instead of
hundreds.

Camera pictures and video are fetched straight from the agency by your device.
They do not travel through anything of ours.

The catalog covers the wildfire lookout networks, the FAA weather cameras, and
the departments of transportation of most states and several Canadian
provinces. Coverage grows as agencies are added.
]

#tak-slide[
= This guide, on the device

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19.png", width: 100%)

  #v(4pt)
  ATAK's *Settings* #sym.arrow.r *Tool Preferences* lists Cam Depot with the
  other tools.
][
  #image("19a.png", width: 100%)

  #v(4pt)
  *Plugin Documentation* opens this guide, so it is on the device with you and
  needs no network.
]
]

#tak-slide[
= What it needs

- *Network* -- outbound HTTPS on port 443 for the catalog, and whatever port the
  agency serves its own pictures and video on. No inbound ports, and no TAK
  server involvement.

- *Nothing else* -- no account, no key, no configuration. It does not read or
  transmit position, callsign, or any other ATAK data. Favorites and your filter
  settings are kept on the device.
]

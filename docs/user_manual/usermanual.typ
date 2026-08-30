#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Cam Depot",
   plugin-version: "0.4",
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
]

#tak-slide[
= Opening it

Open Cam Depot from the ATAK toolbar. The icon sits with the other tools.

Tapping the icon again closes the panel, so it is one tap on and one tap off
when you want the map to yourself.

The first thing it does is load the catalog, then the cameras for the state it
is showing. While it is working, a green line at the top of the panel says so.
When that line turns back into an ordinary count, the list is ready.
]

#tak-slide[
= Finding cameras

The panel is the control. The map shows exactly what the panel selects, never
the whole catalog.

== Where

*State* picks the state or province. Fifty-four of them, listed alphabetically.

*Provider* narrows to one agency. Each entry says how many cameras it has before
you pick it, so you can see what a filter will cost.

*County* narrows further. Counties come from Census boundaries rather than from
the camera feeds, so they work on every camera in the United States, not only
the ones whose agency happened to publish a county.

*On screen only* narrows the list to whatever is in the current map view, and
follows the map as you pan and zoom. This is the fastest way to answer "what is
near this spot" -- move the map, and the list keeps up.
]

#tak-slide[
== Distance from a point

The radius slider limits the list to cameras within a set distance. By default
that is measured from your own position; *From me* switches it to measure from
the centre of the map instead, which is what you want when you are planning
somewhere you are not standing.

Distances follow whatever units ATAK is set to.

== Find a camera by name

Typing in the search box searches every state at once, not only the one in the
dropdown. Camera names are the agency's own -- "I-40/75 \@ West Hills",
"Keller Peak 1" -- so a road number, a landmark or a peak name will usually find
it.
]

#tak-slide[
= The list

Each row is one camera: its name, the state, the agency, the county, and how far
away it is.

The coloured dot says what kind it is. Orange is a wildfire lookout; blue is a
department of transportation or FAA camera. The legend sits above the list.

*Go to* moves the map to that camera and zooms in close enough to see the road
it is watching. Tapping the row itself opens the camera.

Below the list the panel tells you three things: how many cameras matched, how
many are drawn on the map, and, if the map is not showing all of them, why. A
view that is trimmed says so rather than looking complete.
]

#tak-slide[
= Favorites

The star at the end of each row marks a camera as a favorite. Favorites are kept
on the device and survive restarting ATAK.

Favorites are not tied to a state. A lookout in California and a highway camera
in Ontario sit in the same list, and *Favorites First* pins them all to the top
of the panel with every other camera still below them. It keeps the handful of
cameras you actually watch within reach without giving up the rest of the
catalog.

The same star appears in the camera's own pane, so you can mark one while you
are looking at it.
]

#tak-slide[
= Opening a camera

Tapping a camera, in the list or on the map, opens it in a side pane so the map
stays live behind it.

The pane shows the agency, whether the camera is online, whether it steers, the
county, and the latest picture. *Refresh* fetches a new frame.

Pictures are current to within about a minute on most networks. Some agencies
publish on their own cycle: British Columbia refreshes its whole province every
few minutes, so a picture there can be a few minutes old even when everything is
working.

A few cameras publish live video and no picture at all -- all of Maryland works
this way. Those say so, and point you at the video button.
]

#tak-slide[
= Live video

Where the agency publishes a stream, *Live video* plays it in ATAK's own player.

Not every camera has one. A camera that only publishes pictures says
"Video (still only)" rather than offering a button that cannot work. Of the
38,000 cameras in the catalog, about 9,000 stream.

Streams are checked before they are published, so a camera that offers video was
serving video when the catalog was built. Agencies do have outages; when one
does, its cameras keep their video button rather than silently losing it, and
they come back when the agency does.

Closing the video returns you to the map. The toolbar icon reopens the panel.
]

#tak-slide[
= Bearings

Cameras that report which way they are pointing can draw a bearing line on the
map, labelled with its azimuth.

*Show bearing* turns it on, and the marker turns orange while it is on. On a
steerable camera the line follows the camera as it moves, updating every few
seconds.

Opening a stills camera that reports a bearing shows its line automatically for
as long as the picture is open, then puts it away again. That answers "what am I
looking at" at the moment you are asking it.

A camera that publishes no bearing says "No bearing reported" rather than
drawing a line that would be a guess.

One thing worth knowing: on the wildfire networks the bearing is ahead of the
picture. The agency reports where a camera is pointing immediately and publishes
its image twenty to thirty seconds later. The line is the truth; the picture is
the recent past.
]

#tak-slide[
= Drawing on the map

Thirty-eight thousand markers would be unreadable, so Cam Depot draws cameras
only when you are zoomed in past a threshold you choose.

*Use this zoom* takes whatever you are looking at right now and makes it the
threshold -- you set it by example rather than by picking a number. *Presets*
offers a few starting points described in plain terms, from a city block to a
region, expressed as what the scale bar would read.

The panel always says which it is doing: drawn at this scale or closer, what the
scale bar reads now, and whether anything is currently hidden.

If more cameras match than can be drawn legibly, the panel says how many were
left off and to zoom in. It will not quietly show you part of the picture.
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

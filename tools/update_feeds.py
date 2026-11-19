#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Cam Depot — an ATAK plugin
"""
update_feeds.py — Harvest and update Alabama (ALDOT & Baron Critical Weather Institute)
and Tennessee (TDOT SmartWay) camera feeds for the Cam Depot ATAK plugin.

Outputs:
  - app/src/main/assets/feeds/AL.json
  - app/src/main/assets/feeds/TN.json
  - app/src/main/assets/feeds/catalog.json
  - Optional: --export-shards <dir> (outputs gzipped static shards for CDN hosting)
"""

import argparse
import gzip
import json
import math
import os
import re
import ssl
import sys
import urllib.request
from datetime import datetime, timezone

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) CamDepot/2.0'
}

def create_ssl_context():
    return ssl._create_unverified_context()

def fetch_json(url):
    ctx = create_ssl_context()
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, context=ctx, timeout=15) as res:
        return json.loads(res.read().decode('utf-8'))

def fetch_gzipped_json(url):
    ctx = create_ssl_context()
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, context=ctx, timeout=15) as res:
        decompressed = gzip.decompress(res.read())
        return json.loads(decompressed.decode('utf-8'))

def check_url_status(url, timeout=4):
    ctx = create_ssl_context()
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as res:
            return res.getcode() == 200
    except Exception:
        return False

# ---------------------------------------------------------------------------
# ALDOT (Alabama Department of Transportation / ALGO Traffic)
# ---------------------------------------------------------------------------
def harvest_aldot():
    """
    Fetch ALDOT cameras from ALGO Traffic API.
    Note: ALDOT's Wowza HLS streams are protected with Apple FairPlay DRM (SAMPLE-AES),
    which cannot be decrypted by ATAK's libVLC player (resulting in gray/garbled video).
    Therefore, ALDOT cameras are registered as still cameras using their official,
    unencrypted high-resolution snapshot JPEG endpoints.
    """
    print("Harvesting ALDOT cameras from api.algotraffic.com...")
    url = "https://api.algotraffic.com/v4/Cameras"
    raw_cams = fetch_json(url)
    results = []
    for c in raw_cams:
        loc = c.get('location') or {}
        lat = loc.get('latitude') or 0.0
        lon = loc.get('longitude') or 0.0
        co = (loc.get('county') or 'Unknown').strip()
        route = str(loc.get('displayRouteDesignator') or '').strip()
        cross = str(loc.get('displayCrossStreet') or '').strip()

        if route and cross:
            name = f"{route} at {cross}"
        elif route:
            name = f"{route} (ALDOT Cam {c.get('id')})"
        else:
            name = f"ALDOT Cam {c.get('id')}"

        city = str(loc.get('city') or '').strip()
        region = str(c.get('responsibleRegion') or 'ALDOT').strip()
        host = f"{city} ({region})" if city else region

        still = (c.get('snapshotImageUrl') or '').strip()
        if still and not still.startswith('https://'):
            still = ''

        results.append({
            "id": f"aldot-{c.get('id')}",
            "n": name,
            "h": host,
            "lat": round(lat, 5),
            "lon": round(lon, 5),
            "co": co,
            "st": "AL",
            "pr": "ALDOT",
            "sp": "ALDOT / ALGO Traffic",
            "ptz": 0,
            "fire": 0,
            "stream": "",  # Set to empty to avoid encrypted FairPlay DRM scramble in libVLC
            "still": still
        })

    print(f"  Harvested {len(results)} ALDOT cameras (configured with clear snapshot feeds).")
    return results

# ---------------------------------------------------------------------------
# Baron Critical Weather Institute (WeatherNet / ALMESO Network)
# ---------------------------------------------------------------------------
def harvest_baron_weathernet(aldot_reference_cams):
    """
    Fetch Baron Critical Weather Institute observation cameras from the official
    WeatherNet / ALMESO network portal (cams.almeso.net).
    """
    print("Harvesting Baron Critical Weather Institute cameras from cams.almeso.net...")
    url = "https://cams.almeso.net/cgi-bin/getcameraconf"
    try:
        conf = fetch_json(url)
    except Exception as e:
        print(f"  Warning: Could not fetch cams.almeso.net config: {e}")
        return []

    features = conf.get('features', [])
    results = []

    def find_nearest_county(lat, lon):
        best_dist = 999999
        best_co = "Madison"
        for ac in aldot_reference_cams:
            d = math.hypot(ac['lat'] - lat, ac['lon'] - lon)
            if d < best_dist:
                best_dist = d
                best_co = ac['co']
        return best_co

    for f in features:
        props = f.get('properties', {})
        if props.get('disabled', False):
            continue

        coords = f.get('geometry', {}).get('coordinates', [])
        try:
            lon = float(coords[0])
            lat = float(coords[1])
        except (ValueError, IndexError):
            continue

        raw_name = props.get('name', '').strip()
        if not raw_name:
            continue

        src = props.get('src', '')
        stream = ""
        still = ""

        # Determine feed URL
        if 'playstill.html' in src:
            m = re.search(r'src=(//[^&]+)', src)
            if m:
                still = 'https:' + m.group(1)
        elif 'play.html' in src:
            m = re.search(r'name=([^&]+)', src)
            cname = m.group(1) if m else raw_name
            # Baron streams on cams.almeso.net are unencrypted HLS
            stream = f"https://cams.almeso.net/streams/{cname}/index.m3u8"
        elif src.startswith('http'):
            if src.endswith('.m3u8'):
                stream = src
            else:
                still = src

        if not stream and not still:
            continue

        # Format display name
        clean_name = raw_name.replace('_', ' ').title()
        if not clean_name.lower().startswith('bcwi'):
            display_name = f"BCWI WeatherCam — {clean_name}"
        else:
            display_name = clean_name

        slug = re.sub(r'[^a-zA-Z0-9]', '-', raw_name).lower().strip('-')
        cam_id = f"bcwi-{slug}"
        county = find_nearest_county(lat, lon)

        results.append({
            "id": cam_id,
            "n": display_name,
            "h": "Baron Critical Weather Institute",
            "lat": round(lat, 5),
            "lon": round(lon, 5),
            "co": county,
            "st": "AL",
            "pr": "BCWI",
            "sp": "Baron Critical Weather Institute / WeatherNet",
            "ptz": 0,
            "fire": 0,
            "stream": stream,
            "still": still
        })

    print(f"  Harvested {len(results)} active Baron Critical Weather Institute cameras.")
    return results

# ---------------------------------------------------------------------------
# FAA Weather Cameras (Alabama)
# ---------------------------------------------------------------------------
def harvest_al_faa():
    """Fetch baseline FAA weather cameras in Alabama from takwerx public shard."""
    print("Fetching baseline FAA cameras in Alabama...")
    try:
        url = "https://mapdepot.takwerx.org/camdepot/static/AL.json.gz"
        items = fetch_gzipped_json(url)
        faa_cams = [item for item in items if item.get('pr') == 'FAA']
        print(f"  Retained {len(faa_cams)} FAA cameras.")
        return faa_cams
    except Exception as e:
        print(f"  Warning: Could not fetch upstream FAA cameras: {e}")
        return []

# ---------------------------------------------------------------------------
# TDOT SmartWay (Tennessee)
# ---------------------------------------------------------------------------
def harvest_tdot():
    """Fetch Tennessee TDOT SmartWay cameras from takwerx public shard."""
    print("Fetching TDOT cameras for Tennessee...")
    url = "https://mapdepot.takwerx.org/camdepot/static/TN.json.gz"
    items = fetch_gzipped_json(url)
    print(f"  Fetched {len(items)} TDOT cameras.")
    return items

def main():
    parser = argparse.ArgumentParser(description="Update Cam Depot feeds for AL and TN")
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="Root of cam-depot repository")
    parser.add_argument("--export-shards", default=None,
                        help="Optional directory to export .json.gz shards for static web server")
    args = parser.parse_args()

    feeds_dir = os.path.join(args.repo_root, "app", "src", "main", "assets", "feeds")
    os.makedirs(feeds_dir, exist_ok=True)

    # 1. Harvest ALDOT Feeds
    aldot_cams = harvest_aldot()

    # 2. Harvest Baron Critical Weather Institute Feeds
    bcwi_cams = harvest_baron_weathernet(aldot_cams)

    # 3. Harvest Baseline FAA Feeds
    faa_cams = harvest_al_faa()

    al_cameras = aldot_cams + bcwi_cams + faa_cams

    # 4. Harvest Tennessee TDOT Feeds
    tn_cameras = harvest_tdot()

    # 5. Build Catalog Metadata
    al_counties = sorted(list({c['co'] for c in al_cameras if c.get('co') and c['co'] != 'Unknown'}))
    tn_counties = sorted(list({c['co'] for c in tn_cameras if c.get('co') and c['co'] != 'Unknown'}))

    al_providers = {}
    for c in al_cameras:
        pr = c.get('pr', 'ALDOT')
        al_providers[pr] = al_providers.get(pr, 0) + 1

    tn_providers = {}
    for c in tn_cameras:
        pr = c.get('pr', 'TDOT')
        tn_providers[pr] = tn_providers.get(pr, 0) + 1

    catalog = {
        "format": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "Cam Depot Feeds (ALDOT, TDOT, Baron Critical Weather Institute, FAA)",
        "counts": {
            "cameras": len(al_cameras) + len(tn_cameras),
            "fire": 0,
            "offline": 0,
            "streams": sum(1 for c in al_cameras + tn_cameras if c.get('stream'))
        },
        "states": [
            {
                "st": "AL",
                "cameras": len(al_cameras),
                "fire": 0,
                "counties": al_counties
            },
            {
                "st": "TN",
                "cameras": len(tn_cameras),
                "fire": 0,
                "counties": tn_counties
            }
        ],
        "providers": [
            {
                "id": "ALDOT",
                "cameras": al_providers.get("ALDOT", 0),
                "fire": 0,
                "states": ["AL"]
            },
            {
                "id": "BCWI",
                "cameras": al_providers.get("BCWI", 0),
                "fire": 0,
                "states": ["AL"]
            },
            {
                "id": "FAA",
                "cameras": al_providers.get("FAA", 0),
                "fire": 0,
                "states": ["AL"]
            },
            {
                "id": "TDOT",
                "cameras": tn_providers.get("TDOT", 0),
                "fire": 0,
                "states": ["TN"]
            }
        ]
    }

    # 6. Save to app/src/main/assets/feeds/
    al_path = os.path.join(feeds_dir, "AL.json")
    with open(al_path, "w", encoding="utf-8") as f:
        json.dump(al_cameras, f, indent=2)
    print(f"Wrote {len(al_cameras)} cameras to {al_path}")

    tn_path = os.path.join(feeds_dir, "TN.json")
    with open(tn_path, "w", encoding="utf-8") as f:
        json.dump(tn_cameras, f, indent=2)
    print(f"Wrote {len(tn_cameras)} cameras to {tn_path}")

    catalog_path = os.path.join(feeds_dir, "catalog.json")
    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2)
    print(f"Wrote catalog metadata to {catalog_path}")

    # 7. Export static shards if requested
    if args.export_shards:
        out_static = os.path.join(args.export_shards, "static")
        os.makedirs(out_static, exist_ok=True)

        with open(os.path.join(args.export_shards, "catalog.json"), "w", encoding="utf-8") as f:
            json.dump(catalog, f, indent=2)

        for st_code, st_data in [("AL", al_cameras), ("TN", tn_cameras)]:
            gz_path = os.path.join(out_static, f"{st_code}.json.gz")
            with gzip.open(gz_path, "wt", encoding="utf-8") as gf:
                json.dump(st_data, gf)
            print(f"Exported shard: {gz_path}")

    print("\nSummary:")
    print(f"  AL Total Cameras: {len(al_cameras)} (ALDOT: {al_providers.get('ALDOT', 0)}, BCWI: {al_providers.get('BCWI', 0)}, FAA: {al_providers.get('FAA', 0)})")
    print(f"  TN Total Cameras: {len(tn_cameras)} (TDOT: {tn_providers.get('TDOT', 0)})")
    print("Done!")

if __name__ == "__main__":
    main()

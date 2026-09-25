#!/usr/bin/env python3
"""Writes a SideStore/AltStore source (source.json) for one build of the app. Used by the GitHub
workflow; the source is published on each release, so this URL always serves the newest one:
https://github.com/<owner>/<repo>/releases/latest/download/source.json

usage: make-source.py <repo "owner/name"> <tag> <version> <build> <out.json>   (run where the .ipa files are)
"""
import datetime
import json
import os
import sys

repo, tag, version, build, out = sys.argv[1:6]
owner = repo.split("/")[0]
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
raw = f"https://raw.githubusercontent.com/{repo}/main/ios"
notes = f"Build {build}. See https://github.com/{repo}/commits/main for changes."

APPS = [
    {
        "ipa": "HY300.ipa",
        "name": "HY300",
        # Same bundle ID as the earlier "Keystone" app, so SideStore updates that install.
        "bundleIdentifier": "io.github.hy300keystone.calibrate",
        "subtitle": "Phone remote and keystone calibration for the HY300",
        "description": "For the Magcubic HY300 projector. Remote: a trackpad with a real mouse pointer, your phone's "
                       "keyboard, and Back, Home, volume and media keys, over Wi-Fi. Keystone: measures the picture on "
                       "the wall in 3D (LiDAR where available) and adjusts the projector until it's rectangular. Pair "
                       "once by scanning the QR code the projector shows in a corner.",
        "icon": f"{raw}/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "privacy": {
            "NSCameraUsageDescription": "The camera scans the projector's pairing QR code and measures the projected picture on the wall.",
            "NSLocalNetworkUsageDescription": "Controls the projector and adjusts its keystone over your Wi-Fi.",
        },
    },
]

apps = []
for a in APPS:
    url = f"https://github.com/{repo}/releases/download/{tag}/{a['ipa']}"
    size = os.path.getsize(a["ipa"])
    apps.append({
        "name": a["name"],
        "bundleIdentifier": a["bundleIdentifier"],
        "developerName": owner,
        "subtitle": a["subtitle"],
        "localizedDescription": a["description"],
        "iconURL": a["icon"],
        "tintColor": "3DDC6F",
        "category": "utilities",
        "screenshots": [],
        "versions": [{
            "version": version, "buildVersion": build, "date": now, "localizedDescription": notes,
            "downloadURL": url, "size": size, "minOSVersion": "17.0",
        }],
        "appPermissions": {"entitlements": [], "privacy": a["privacy"]},
        # Older SideStore/AltStore versions read these top-level fields instead of "versions".
        "version": version, "versionDate": now, "versionDescription": notes, "downloadURL": url, "size": size,
    })

source = {
    "name": "HY300 Keystone",
    "identifier": f"io.github.{owner.lower()}.hy300-keystone",
    "subtitle": "Apps for the Magcubic HY300 projector",
    "description": "Keystone calibration and a phone remote for the Magcubic HY300 projector.",
    "iconURL": APPS[0]["icon"],
    "website": f"https://github.com/{repo}",
    "tintColor": "3DDC6F",
    "apps": apps,
    "news": [],
}
with open(out, "w") as f:
    json.dump(source, f, indent=2)
for a in apps:
    print(f"{a['name']} {version} ({a['size']} bytes) -> {a['downloadURL']}")

#!/usr/bin/env python3
"""Writes a SideStore/AltStore source (source.json) listing both apps of one build. Used by the GitHub
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
        "ipa": "KeystoneLiDAR.ipa",
        "name": "Keystone",
        "bundleIdentifier": "io.github.hy300keystone.calibrate",
        "subtitle": "Camera/LiDAR keystone calibration for the HY300",
        "description": "Calibrates the keystone of a Magcubic HY300 projector. Point the phone at the QR code the "
                       "projector app shows, then at the green picture: the app measures the picture on the wall in 3D "
                       "(LiDAR where available) and adjusts the projector until the picture is rectangular.",
        "icon": f"{raw}/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "privacy": {
            "NSCameraUsageDescription": "The camera finds the projected picture and measures it on the wall.",
            "NSLocalNetworkUsageDescription": "Talks to the projector on your Wi-Fi to adjust its keystone.",
        },
    },
    {
        "ipa": "HY300Remote.ipa",
        "name": "HY300 Remote",
        "bundleIdentifier": "io.github.hy300keystone.remote",
        "subtitle": "Use your phone as the projector's mouse and keyboard",
        "description": "Controls a Magcubic HY300 projector over Wi-Fi: a trackpad with a real mouse pointer, your "
                       "phone's keyboard for typing, and Back, Home, volume and media keys. Pair once by scanning the "
                       "QR code the projector shows in a corner when it starts.",
        "icon": f"{raw}/Remote/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "privacy": {
            "NSCameraUsageDescription": "The camera scans the pairing QR code shown by the projector.",
            "NSLocalNetworkUsageDescription": "Controls the projector on your Wi-Fi.",
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

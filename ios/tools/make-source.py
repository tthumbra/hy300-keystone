#!/usr/bin/env python3
"""Writes a SideStore/AltStore source (source.json) for one built .ipa. Used by the GitHub workflow;
the source is published on each release, so this URL always serves the newest one:
https://github.com/<owner>/<repo>/releases/latest/download/source.json

usage: make-source.py <ipa> <version> <build> <download-url> <repo "owner/name"> <out.json>
"""
import datetime
import json
import os
import sys

ipa, version, build, download_url, repo, out = sys.argv[1:7]
owner = repo.split("/")[0]
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
size = os.path.getsize(ipa)
icon = f"https://raw.githubusercontent.com/{repo}/main/ios/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
notes = f"Build {build}. See https://github.com/{repo}/commits/main for changes."
description = (
    "Calibrates the keystone of a Magcubic HY300 projector. Point the phone at the QR code the "
    "projector app shows, then at the green picture: the app measures the picture on the wall in 3D "
    "(LiDAR where available) and adjusts the projector until the picture is rectangular."
)

app = {
    "name": "Keystone",
    "bundleIdentifier": "io.github.hy300keystone.calibrate",
    "developerName": owner,
    "subtitle": "Camera/LiDAR keystone calibration for the HY300",
    "localizedDescription": description,
    "iconURL": icon,
    "tintColor": "3DDC6F",
    "category": "utilities",
    "screenshots": [],
    "versions": [{
        "version": version,
        "buildVersion": build,
        "date": now,
        "localizedDescription": notes,
        "downloadURL": download_url,
        "size": size,
        "minOSVersion": "17.0",
    }],
    "appPermissions": {
        "entitlements": [],
        "privacy": {
            "NSCameraUsageDescription": "The camera finds the projected picture and measures it on the wall.",
            "NSLocalNetworkUsageDescription": "Talks to the projector on your Wi-Fi to adjust its keystone.",
        },
    },
    # Older SideStore/AltStore versions read these top-level fields instead of "versions".
    "version": version,
    "versionDate": now,
    "versionDescription": notes,
    "downloadURL": download_url,
    "size": size,
}
source = {
    "name": "HY300 Keystone",
    "identifier": f"io.github.{owner.lower()}.hy300-keystone",
    "subtitle": "Keystone calibration for the Magcubic HY300 projector",
    "description": description,
    "iconURL": icon,
    "website": f"https://github.com/{repo}",
    "tintColor": "3DDC6F",
    "apps": [app],
    "news": [],
}
with open(out, "w") as f:
    json.dump(source, f, indent=2)
print(f"wrote {out}: {app['name']} {version} ({size} bytes) -> {download_url}")

#!/usr/bin/env python3
"""Resolve a build's display version and Android upgrade identity once per run."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
import re
from zoneinfo import ZoneInfo

BASE_VERSION = re.compile(
    r"(?P<core>(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"
    r"(?P<suffix>-(?:beta|rc)\.[1-9][0-9]*)?"
)
EPOCH = datetime(2020, 1, 1, tzinfo=timezone.utc)
MAX_VERSION_CODE = 2_100_000_000
MAX_VERSION_COMPONENT = 2_147_483_647


def resolve_version(base_version: str, channel: str, now: datetime | None = None) -> dict[str, str | int]:
    """Append Beijing MMddHH before prerelease labels; code counts UTC seconds."""
    match = BASE_VERSION.fullmatch(base_version)
    if not match:
        raise ValueError("base version must be major.minor.patch, optionally followed by -beta.N or -rc.N")
    if any(len(part) > 10 or int(part) > MAX_VERSION_COMPONENT for part in match["core"].split(".")):
        raise ValueError("base version components must not exceed a signed 32-bit integer")
    if channel not in {"dev", "release"}:
        raise ValueError("channel must be dev or release")
    moment = now if now is not None else datetime.now(timezone.utc)
    if moment.tzinfo is None or moment.utcoffset() is None:
        raise ValueError("build time must include a timezone offset")
    utc = moment.astimezone(timezone.utc)
    code = (utc - EPOCH) // timedelta(seconds=1)
    if not 0 < code <= MAX_VERSION_CODE:
        raise ValueError("build time is outside the Android versionCode range")
    stamp = moment.astimezone(ZoneInfo("Asia/Shanghai")).strftime("%m%d%H")
    prefix, patch = match["core"].rsplit(".", 1)
    numeric_version = f"{prefix}.{int(patch + stamp)}"
    if any(len(part) > 10 or int(part) > MAX_VERSION_COMPONENT for part in numeric_version.split(".")):
        raise ValueError("timestamped version components must not exceed a signed 32-bit integer")
    version = numeric_version + (match["suffix"] or "")
    if channel == "dev":
        version += "-dev"
    return {"version": version, "version_code": code}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-version", default="0.1.2")
    parser.add_argument("--channel", choices=("dev", "release"), required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--now", help="Reproducible ISO 8601 build time with timezone")
    args = parser.parse_args()
    try:
        moment = datetime.fromisoformat(args.now.replace("Z", "+00:00")) if args.now else None
        result = resolve_version(args.base_version, args.channel, moment)
    except (ValueError, OverflowError) as error:
        parser.error(str(error))
    output = "".join(f"{key}={value}\n" for key, value in result.items())
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as destination:
            destination.write(output)
    print(output, end="")


if __name__ == "__main__":
    main()

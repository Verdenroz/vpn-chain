#!/usr/bin/env python3
"""Builds the bundled IPv4 location tables from the pinned DB-IP Lite city CSV.

    curl -O https://download.db-ip.com/free/dbip-city-lite-2026-10.csv.gz
    python3 -I core/geoip/tools/build_geoip.py dbip-city-lite-2026-10.csv.gz

geoip_ranges.bin: sorted 8-byte records, big-endian start IP, ASCII country
code, big-endian city id (0 = country only). geoip_cities.tsv: one line per
city id from 1, "lat<TAB>lon<TAB>name". The output is deterministic.
"""
import csv
import gzip
import hashlib
import math
import struct
import sys
from collections import defaultdict
from pathlib import Path

SHA256 = "d0a4d9446c1e5b826b95f99ed3b3ce151b9844df0044fe223a636cde26c75f2a"
OUT = Path(__file__).resolve().parents[1] / "src/commonMain/composeResources/files"

MIN_ADDRESSES = 1 << 22  # a city this big is drawn at its own spot
PER_COUNTRY = 3  # largest cities kept in every country, however small
SNAP_KM = 600  # a smaller place folds into a kept city this close, else the country
MIN_RANGE = 1 << 10  # a smaller block takes the city of the block before it in the same country


def km(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


def ip4(text):
    a, b, c, d = map(int, text.split("."))
    return a << 24 | b << 16 | c << 8 | d


def read_rows(path):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        for start, end, _, cc, _, city, lat, lon in csv.reader(f):
            if ":" in start:
                continue
            yield ip4(start), ip4(end), cc, city, float(lat), float(lon)


def main(path):
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != SHA256:
        sys.exit("checksum mismatch: not the pinned DB-IP Lite edition")

    rows = list(read_rows(path))
    weight = defaultdict(int)
    sums = defaultdict(lambda: [0.0, 0.0])
    for start, end, cc, city, lat, lon in rows:
        size = end - start + 1
        key = (cc, city)
        weight[key] += size
        sums[key][0] += lat * size
        sums[key][1] += lon * size
    centre = {k: (sums[k][0] / weight[k], sums[k][1] / weight[k]) for k in weight if k[1]}

    by_country = defaultdict(list)
    for key in centre:
        by_country[key[0]].append(key)
    kept = set()
    for keys in by_country.values():
        keys.sort(key=lambda k: (-weight[k], k[1]))
        kept.update(keys[:PER_COUNTRY])
        kept.update(k for k in keys if weight[k] >= MIN_ADDRESSES)

    ids = {key: i + 1 for i, key in enumerate(sorted(kept))}
    target = {}
    for key, at in centre.items():
        if key in ids:
            target[key] = key
            continue
        near = min(
            (k for k in by_country[key[0]] if k in ids),
            key=lambda k: (km(at, centre[k]), k),
            default=None,
        )
        target[key] = near if near and km(at, centre[near]) <= SNAP_KM else None

    records = []
    for start, end, cc, city, _, _ in sorted(rows):
        if end - start + 1 < MIN_RANGE and records and records[-1][1] == cc:
            continue
        hit = target.get((cc, city))
        record = (cc, ids[hit] if hit else 0)
        if not records or records[-1][1:] != record:
            records.append((start, *record))

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "geoip_ranges.bin").write_bytes(
        b"".join(struct.pack(">I2sH", start, cc.encode("ascii"), city) for start, cc, city in records)
    )
    lines = [f"{centre[k][0]:.2f}\t{centre[k][1]:.2f}\t{k[1]}\n" for k in sorted(ids, key=ids.get)]
    (OUT / "geoip_cities.tsv").write_text("".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1])

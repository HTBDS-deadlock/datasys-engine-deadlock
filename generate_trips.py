#!/usr/bin/env python3
"""Generates the trips dataset deterministically.

Same --rows and --seed always give a byte-identical file, so any teammate can
regenerate the data and rerun the experiment. Rows are ordered by distance.

Columns (no header unless --header): city,distance,price
  city     one of a fixed list of cities
  distance integer kilometres, uniform in 1..1000
  price    5 + 0.9 * distance + up to 20 noise, two decimals

Usage:
  python generate_trips.py                       # 2,000,000 rows, seed 42
  python generate_trips.py --rows 1000 --seed 7 --out /tmp/small.csv
"""
import argparse
import hashlib
import os
import random

CITIES = [
    "Copenhagen", "Aarhus", "Odense", "Aalborg", "Esbjerg", "Randers",
    "Kolding", "Horsens", "Vejle", "Roskilde", "Herning", "Silkeborg",
    "Naestved", "Fredericia", "Viborg", "Koge",
]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rows", type=int, default=2_000_000)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="generated/tripz.csv")
    ap.add_argument("--header", action="store_true", help="write a 'city,distance,price' header line")
    args = ap.parse_args()

    rng = random.Random(args.seed)

    # Only random() and integer/float arithmetic are used so the output does not
    # depend on Python version or platform.
    distances = [int(rng.random() * 1000) + 1 for _ in range(args.rows)]
    distances.sort()

    out_dir = os.path.dirname(args.out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    below_100 = 0
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        if args.header:
            f.write("city,distance,price\n")
        for d in distances:
            city = CITIES[int(rng.random() * len(CITIES))]
            price = 5 + d * 0.9 + rng.random() * 20
            f.write(f"{city},{d},{price:.2f}\n")
            if d < 100:
                below_100 += 1

    sha = hashlib.sha256()
    with open(args.out, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            sha.update(chunk)

    print(f"wrote {args.rows} rows to {args.out}")
    print(f"seed={args.seed} rows_with_distance_below_100={below_100}")
    print(f"sha256={sha.hexdigest()}")


if __name__ == "__main__":
    main()

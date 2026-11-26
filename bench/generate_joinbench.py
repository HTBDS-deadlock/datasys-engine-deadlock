#!/usr/bin/env python3
"""Generates the join-order benchmark dataset (exercise 12) deterministically.

Same arguments and seed always give byte-identical files.

Schema (CSV, no header, matching bench/load.sql):
  customers (c_name STRING, c_city STRING)                         dimension
  orders    (o_orderkey LONG, o_custname STRING, o_quantity LONG)  fact
  shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE)   fact

Design choices that matter for the estimates:
  - c_name is unique: cust0000 .. cust0999.
  - Cities are assigned round-robin (customer i lives in city i % cities), so
    an equality filter on c_city keeps exactly customers / cities rows (1%).
  - Every customer has exactly --per-customer orders and shipments; the foreign
    keys are shuffled, so each partition sees (almost) every customer and the
    per-partition NDV is close to the table's.

Usage:
  python bench/generate_joinbench.py                      # 1,000 customers, 100 per customer, seed 42
  python bench/generate_joinbench.py --customers 500 --per-customer 50 --out-dir generated/small
"""
import argparse
import hashlib
import os
import random


def sha256_of(path):
    sha = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def write_fact(path, rng, names, per_customer, value_fn):
    # Each customer exactly per_customer times, then shuffled.
    keys = [name for name in names for _ in range(per_customer)]
    rng.shuffle(keys)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for i, name in enumerate(keys):
            f.write(f"{i},{name},{value_fn(rng)}\n")
    return len(keys)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--customers", type=int, default=1000)
    ap.add_argument("--cities", type=int, default=100)
    ap.add_argument("--per-customer", type=int, default=100, help="orders and shipments per customer")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out-dir", default="generated/joinbench")
    args = ap.parse_args()

    if args.customers % args.cities != 0:
        ap.error("--customers must be a multiple of --cities, so every city has the same number of customers")

    rng = random.Random(args.seed)
    os.makedirs(args.out_dir, exist_ok=True)

    names = [f"cust{i:04d}" for i in range(args.customers)]

    customers_csv = os.path.join(args.out_dir, "customers.csv")
    with open(customers_csv, "w", encoding="utf-8", newline="\n") as f:
        for i, name in enumerate(names):
            f.write(f"{name},city{i % args.cities:03d}\n")

    orders_csv = os.path.join(args.out_dir, "orders.csv")
    n_orders = write_fact(orders_csv, rng, names, args.per_customer,
                          lambda r: int(r.random() * 10) + 1)          # o_quantity 1..10

    shipments_csv = os.path.join(args.out_dir, "shipments.csv")
    n_shipments = write_fact(shipments_csv, rng, names, args.per_customer,
                             lambda r: f"{0.5 + r.random() * 49.5:.2f}")  # s_weight 0.50..50.00

    per_city = args.customers // args.cities
    print(f"seed={args.seed} customers={args.customers} cities={args.cities} per_customer={args.per_customer}")
    print(f"customers.csv  {args.customers} rows  sha256={sha256_of(customers_csv)}")
    print(f"orders.csv     {n_orders} rows  sha256={sha256_of(orders_csv)}")
    print(f"shipments.csv  {n_shipments} rows  sha256={sha256_of(shipments_csv)}")
    print("expected sizes for c_city = 'city042':")
    print(f"  customers after filter        {per_city}")
    print(f"  min: customers x shipments    {per_city * args.per_customer}")
    print(f"  max: orders x shipments       {args.customers * args.per_customer ** 2}")
    print(f"  final result (any order)      {per_city * args.per_customer ** 2}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Summarize a gpu-log.ps1 CSV: mean/max GPU utilization, clocks and power. Usage: summarize-gpu.py file.csv"""
import csv, statistics, sys

rows = list(csv.reader(open(sys.argv[1], newline="")))
head = [h.strip() for h in rows[0]]
data = [[c.strip().split(" ")[0] for c in r] for r in rows[1:] if r]
for col in head[1:]:
    i = head.index(col)
    vals = []
    for r in data:
        try:
            vals.append(float(r[i]))
        except (ValueError, IndexError):
            pass
    if vals:
        print(f"{col:32s} mean {statistics.mean(vals):8.1f}   max {max(vals):8.1f}")

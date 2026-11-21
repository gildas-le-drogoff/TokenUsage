#!/usr/bin/env python3
"""Prints a Markdown coverage summary from a Kover XML report (no external service)."""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/kover/reportDebug.xml"
root = ET.parse(path).getroot()

print("## Couverture (Kover, debug)")
print()
print("| Métrique | Couvert | Total | % |")
print("|---|---:|---:|---:|")
for counter in root.findall("counter"):
    missed = int(counter.get("missed"))
    covered = int(counter.get("covered"))
    total = missed + covered
    pct = 100.0 * covered / total if total else 0.0
    print(f"| {counter.get('type').title()} | {covered} | {total} | {pct:.1f} % |")

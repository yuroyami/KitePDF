#!/usr/bin/env python3
"""Sums the JUnit XML reports of a test run per module and platform (#942).

Writes a Markdown table to the job summary ($GITHUB_STEP_SUMMARY, or stdout), keeps the counts as
JSON for the next run, and, given the counts of an earlier run, marks each module whose skipped
count rose or whose test count fell. It never fails the run: the numbers are for reading.

    scripts/test_summary.py --reports build/reports --label jvm --out counts.json [--previous old.json]
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def counts(reports: Path) -> dict:
    """{ "module/platform": {"tests", "failures", "errors", "skipped"} } from every TEST-*.xml under reports."""
    out: dict = {}
    for xml in sorted(reports.rglob("TEST-*.xml")):
        rel = xml.relative_to(reports).parts
        key = "/".join(rel[:2]) if len(rel) >= 3 else (rel[0] if len(rel) == 2 else "(root)")
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        c = out.setdefault(key, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
        for s in suites:
            for name in c:
                try:
                    c[name] += int(float(s.get(name, "0")))
                except ValueError:
                    pass
    return out


def table(label: str, now: dict, before: dict | None) -> str:
    lines = [f"### Test counts: {label}", "", "| Module | Tests | Failures | Errors | Skipped | Change |", "| --- | ---: | ---: | ---: | ---: | --- |"]
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for key in sorted(now):
        c = now[key]
        for name in total:
            total[name] += c[name]
        change = ""
        if before is not None and key in before:
            b = before[key]
            notes = []
            if c["skipped"] > b["skipped"]:
                notes.append(f"skipped {b['skipped']} to {c['skipped']}")
            if c["tests"] < b["tests"]:
                notes.append(f"tests {b['tests']} to {c['tests']}")
            change = ":warning: " + ", ".join(notes) if notes else ""
        elif before is not None:
            change = "new"
        lines.append(f"| {key} | {c['tests']} | {c['failures']} | {c['errors']} | {c['skipped']} | {change} |")
    if before is not None:
        for key in sorted(set(before) - set(now)):
            lines.append(f"| {key} | 0 | 0 | 0 | 0 | :warning: no longer reported (had {before[key]['tests']} tests) |")
    lines.append(f"| **Total** | **{total['tests']}** | **{total['failures']}** | **{total['errors']}** | **{total['skipped']}** | |")
    return "\n".join(lines) + "\n"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--reports", default="build/reports", type=Path)
    p.add_argument("--label", default="tests")
    p.add_argument("--out", type=Path)
    p.add_argument("--previous", type=Path)
    a = p.parse_args()
    now = counts(a.reports)
    before = None
    if a.previous and a.previous.is_file():
        try:
            before = json.loads(a.previous.read_text())
        except (OSError, ValueError):
            before = None
    text = table(a.label, now, before)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    else:
        sys.stdout.write(text)
    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(now, indent=1, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

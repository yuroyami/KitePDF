#!/usr/bin/env python3
"""Inspects the KitePDF artifacts of one version in a Maven repository before users get them (#732).

For each published module it reads the JVM jars and the Android AARs and checks:

- every class file is Java 11 bytecode or older, the `jvm.release` of common.module-template.yaml
  (#683);
- the jar, or a jar inside the AAR (classes.jar, or libs/*.jar as the Kotlin Toolchain writes),
  carries META-INF/LICENSE and META-INF/NOTICE (#703);
- an AAR's AndroidManifest.xml declares the minSdk its module.yaml asks for, or the template's
  (#721).

Usage: scripts/inspect_artifacts.py --version 0.13.0 [--repo ~/.m2/repository]
Exits with 1 and names each problem when one is found, and with 2 when no artifact is there.
"""
import argparse
import io
import pathlib
import re
import struct
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
GROUP = "io/github/yuroyami"
MAX_CLASS_MAJOR = 55  # Java 11


def template_min_sdk():
    text = (ROOT / "library.module-template.yaml").read_text()
    m = re.search(r"^\s+minSdk:\s*(\d+)", text, re.M)
    return int(m.group(1)) if m else None


def module_min_sdk(module, default):
    path = ROOT / module / "module.yaml"
    if not path.exists():
        return default
    m = re.search(r"^\s+minSdk:\s*(\d+)", path.read_text(), re.M)
    return int(m.group(1)) if m else default


def check_jar(name, data, problems, need_notices=True):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        worst = 0
        for entry in names:
            if entry.endswith(".class") and not entry.startswith("META-INF/versions/"):
                head = z.read(entry)[:8]
                if len(head) == 8 and head[:4] == b"\xca\xfe\xba\xbe":
                    worst = max(worst, struct.unpack(">H", head[6:8])[0])
        if worst > MAX_CLASS_MAJOR:
            problems.append(f"{name}: class files of Java {worst - 44}, newer than Java {MAX_CLASS_MAJOR - 44}")
        if need_notices:
            for notice in ("META-INF/LICENSE", "META-INF/NOTICE"):
                if notice not in names:
                    problems.append(f"{name}: no {notice}")
        return worst


def min_sdk_of(manifest):
    # Binary XML in an APK, plain XML in an AAR.
    m = re.search(rb'minSdkVersion="(\d+)"', manifest)
    return int(m.group(1)) if m else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--repo", default=str(pathlib.Path.home() / ".m2/repository"))
    args = parser.parse_args()
    base = pathlib.Path(args.repo).expanduser() / GROUP
    default_sdk = template_min_sdk()
    problems = []
    checked = 0
    for module_dir in sorted(base.glob("kitepdf*")):
        version_dir = module_dir / args.version
        if not version_dir.is_dir():
            continue
        artifact = module_dir.name
        module = re.sub(r"-(jvm|android)$", "", artifact)
        for jar in sorted(version_dir.glob(f"{artifact}-{args.version}.jar")):
            if artifact.endswith("-jvm"):
                check_jar(jar.name, jar.read_bytes(), problems)
                checked += 1
        for aar in sorted(version_dir.glob(f"{artifact}-{args.version}.aar")):
            checked += 1
            with zipfile.ZipFile(aar) as z:
                names = set(z.namelist())
                # The Kotlin Toolchain keeps an AAR's classes in libs/<artifact>-jvm.jar, other builds in classes.jar.
                inner = sorted(n for n in names if n == "classes.jar" or (n.startswith("libs/") and n.endswith(".jar")))
                if not inner:
                    problems.append(f"{aar.name}: no classes.jar and no jar under libs/")
                has_license = "META-INF/LICENSE" in names
                has_notice = "META-INF/NOTICE" in names
                for n in inner:
                    data = z.read(n)
                    check_jar(f"{aar.name}!{n}", data, problems, need_notices=False)
                    with zipfile.ZipFile(io.BytesIO(data)) as c:
                        entries = set(c.namelist())
                    has_license = has_license or "META-INF/LICENSE" in entries
                    has_notice = has_notice or "META-INF/NOTICE" in entries
                if not has_license:
                    problems.append(f"{aar.name}: no META-INF/LICENSE")
                if not has_notice:
                    problems.append(f"{aar.name}: no META-INF/NOTICE")
                want = module_min_sdk(module, default_sdk)
                got = min_sdk_of(z.read("AndroidManifest.xml")) if "AndroidManifest.xml" in names else None
                if want is not None and got != want:
                    problems.append(f"{aar.name}: minSdk {got}, but {module}/module.yaml asks for {want}")
    if checked == 0:
        print(f"no KitePDF {args.version} jar or AAR under {base}", file=sys.stderr)
        return 2
    for p in problems:
        print(f"::error::{p}")
    print(f"{checked} artifacts of KitePDF {args.version}, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

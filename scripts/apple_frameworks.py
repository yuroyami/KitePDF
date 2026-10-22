#!/usr/bin/env python3
"""Link the Apple frameworks and XCFrameworks from the klibs that `./kotlin build` writes.

The Kotlin Toolchain compiles a library to klibs but links no Apple framework. This script runs the
toolchain's own Kotlin/Native compiler on those klibs, as the Kotlin Gradle plugin does:

  debug frameworks   <module>/build/bin/<platform>/debugFramework/<Name>.framework
  release frameworks <module>/build/bin/<platform>/releaseFramework/<Name>.framework
  XCFrameworks       <module>/build/XCFrameworks/<debug|release>/<Name>.xcframework

Run `./kotlin build -p <platform> ...` first, for every platform you link.
Put this file in `scripts/` of the repo. It finds the repo root from its own path.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")

# Framework name -> module, the libraries whose API the framework re-exports to Swift, and its platforms.
# These are the dynamic iOS frameworks that the Gradle build linked. An export entry is a module name or a
# Maven artifact name without the platform suffix.
IOS = ["iosArm64", "iosSimulatorArm64", "iosX64"]
FRAMEWORKS = {
    "KitePDFBundle": {"module": "kitepdf", "export": [], "platforms": IOS},
    "KitePDFCore": {"module": "kitepdf-core", "export": [], "platforms": IOS},
    "KitePDF": {"module": "kitepdf-pdf", "export": [], "platforms": IOS},
    "KitePDFEpub": {"module": "kitepdf-epub", "export": [], "platforms": IOS},
    "KitePDFCbz": {"module": "kitepdf-cbz", "export": [], "platforms": IOS},
    "KitePDFXps": {"module": "kitepdf-xps", "export": [], "platforms": IOS},
    "KitePDFSvg": {"module": "kitepdf-svg", "export": [], "platforms": IOS},
    "KitePDFJavaScript": {"module": "kitepdf-javascript", "export": [], "platforms": IOS},
    "KitePDFCompose": {"module": "kitepdf-compose-viewer", "export": [], "platforms": ["iosArm64", "iosSimulatorArm64"]},
    "KitePDFNativeRenderer": {"module": "kitepdf-native-renderer", "export": [], "platforms": IOS},
}
# The file that holds settings.kotlin.version.
KOTLIN_VERSION_FILE = "common.module-template.yaml"
# Toolchain platform -> Kotlin/Native target, and the XCFramework slice that lipo merges it into.
PLATFORMS = {
    "iosArm64": ("ios_arm64", "ios"),
    "iosSimulatorArm64": ("ios_simulator_arm64", "ios-simulator"),
    "iosX64": ("ios_x64", "ios-simulator"),
    "macosArm64": ("macos_arm64", "macos"),
    "tvosArm64": ("tvos_arm64", "tvos"),
    "tvosSimulatorArm64": ("tvos_simulator_arm64", "tvos-simulator"),
    "watchosArm32": ("watchos_arm32", "watchos"),
    "watchosArm64": ("watchos_arm64", "watchos"),
    "watchosDeviceArm64": ("watchos_device_arm64", "watchos"),
    "watchosSimulatorArm64": ("watchos_simulator_arm64", "watchos-simulator"),
}


def run(*args, capture=False):
    print("Running " + " ".join(str(a) for a in args[:4]) + (" ..." if len(args) > 4 else ""), flush=True)
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def kotlin_version():
    text = (ROOT / KOTLIN_VERSION_FILE).read_text()
    return re.search(r"(?m)^\s+version:\s*([0-9][^\s#]*)\s*(?:#.*)?$", text.split("kotlin:", 1)[1]).group(1)


def konanc(version):
    found = sorted(d for d in (CACHE / "extract.cache").glob(f"*kotlin-native-prebuilt-{version}-macos-*") if d.is_dir())
    if not found:
        raise RuntimeError(f"No Kotlin/Native {version} in {CACHE}/extract.cache. Run `./kotlin build -p iosArm64` first.")
    return found[-1] / "bin/konanc"


def module_klib(module, platform):
    task = platform[0].upper() + platform[1:]
    klib = ROOT / f"build/tasks/_{module}_compile{task}Debug/{module}.klib"
    if not klib.exists():
        raise RuntimeError(f"No klib at {klib}. Run `./kotlin build -p {platform}` first.")
    return klib


def dependency_klibs(module, platform):
    """Klibs of the module's dependencies on one platform, as `./kotlin show dependencies` resolves them."""
    tree = run("./kotlin", "show", "dependencies", "-m", module, "-p", platform, capture=True)
    klibs = {}
    for name in re.findall(r"─── Module (\S+)", tree):
        if name != module:
            klibs[name] = module_klib(name, platform)
    suffix = platform.lower()
    # "a -> b" means the build resolved version b. A folder can hold cinterop klibs next to the main one.
    pattern = rf"([\w.\-]+):([\w.\-]+)-{suffix}:([\w.\-]+)(?: -> ([\w.\-]+))?"
    for group, artifact, declared, resolved in re.findall(pattern, tree):
        version = resolved or declared
        jar_dir = CACHE / ".m2.cache" / group.replace(".", "/") / f"{artifact}-{suffix}" / version
        files = sorted(jar_dir.glob("*.klib"))
        if not files:
            raise RuntimeError(f"No klib for {group}:{artifact}-{suffix}:{version} in {jar_dir}")
        for klib in files:
            name = klib.name.removesuffix(".klib").removeprefix(f"{artifact}-{suffix}-{version}")
            klibs[artifact + name] = klib
    return klibs


def link(name, spec, platform, build_type, compiler):
    module = spec["module"]
    target, _ = PLATFORMS[platform]
    out_dir = ROOT / f"{module}/build/bin/{platform}/{build_type}Framework"
    shutil.rmtree(out_dir / f"{name}.framework", ignore_errors=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    deps = dependency_klibs(module, platform)
    missing = [e for e in spec["export"] if e not in deps]
    if missing:
        raise RuntimeError(f"{name} exports {missing}, which {module} does not depend on for {platform}")
    args = [compiler, "-produce", "framework", "-target", target, "-o", out_dir / name,
            f"-Xinclude={module_klib(module, platform)}", "-Xexport-kdoc"]
    for library in deps.values():
        args += ["-library", library]
    args += [f"-Xexport-library={deps[e]}" for e in spec["export"]]
    args += ["-g"] if build_type == "debug" else ["-opt"]
    run(*args)
    return out_dir / f"{name}.framework"


def fat_framework(frameworks, destination):
    """Merges the frameworks of one slice, such as iosSimulatorArm64 and iosX64, with lipo."""
    first = frameworks[0]
    shutil.rmtree(destination, ignore_errors=True)
    shutil.copytree(first, destination, symlinks=True)
    if len(frameworks) == 1:
        return destination
    binary = first.name.removesuffix(".framework")
    headers = {(f / "Headers" / f"{binary}.h").read_text() for f in frameworks}
    if len(headers) != 1:
        raise RuntimeError(f"The {binary} headers differ between {[f.parent.parent.name for f in frameworks]}")
    # macOS frameworks keep the binary under Versions/A; the others keep it at the root.
    relative = Path(os.path.relpath(os.path.realpath(first / binary), first))
    run("lipo", "-create", *[f / relative for f in frameworks], "-output", destination / relative)
    return destination


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platforms", default=",".join(PLATFORMS), help="comma-separated toolchain platforms")
    parser.add_argument("--build-type", choices=("debug", "release"), default="release")
    parser.add_argument("--no-xcframework", action="store_true", help="only link the per-platform frameworks")
    parser.add_argument("--framework", action="append", choices=sorted(FRAMEWORKS),
                        help="link one framework; repeat it for more (default: all)")
    args = parser.parse_args()
    platforms = [p for p in args.platforms.split(",") if p]
    compiler = konanc(kotlin_version())
    for name, spec in FRAMEWORKS.items():
        if args.framework and name not in args.framework:
            continue
        linked = {p: link(name, spec, p, args.build_type, compiler) for p in platforms if p in spec["platforms"]}
        if not linked:
            continue
        if args.no_xcframework:
            continue
        slices = {}
        for platform, framework in linked.items():
            slices.setdefault(PLATFORMS[platform][1], []).append(framework)
        work = ROOT / f"{spec['module']}/build/XCFrameworks/{args.build_type}/slices"
        shutil.rmtree(work, ignore_errors=True)
        merged = [fat_framework(fs, work / slice_name / f"{name}.framework") for slice_name, fs in slices.items()]
        output = ROOT / f"{spec['module']}/build/XCFrameworks/{args.build_type}/{name}.xcframework"
        shutil.rmtree(output, ignore_errors=True)
        command = ["xcodebuild", "-create-xcframework"]
        for framework in merged:
            command += ["-framework", framework]
        run(*command, "-output", output)
        shutil.rmtree(work)
        print(f"Wrote {output.relative_to(ROOT)}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)

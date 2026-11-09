"""Tests scripts/inspect_artifacts.py on a small Maven repository built here (#732)."""
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parent / "inspect_artifacts.py"


def class_file(major):
    return b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big") + b"\x00" * 16


def jar(major, notices=True):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("io/github/yuroyami/kitepdf/A.class", class_file(major))
        if notices:
            z.writestr("META-INF/LICENSE", "Apache-2.0")
            z.writestr("META-INF/NOTICE", "KitePDF")
    return out.getvalue()


def aar(min_sdk, major=55, inner="classes.jar", notices=True):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("AndroidManifest.xml", f'<manifest><uses-sdk android:minSdkVersion="{min_sdk}"/></manifest>')
        z.writestr(inner, jar(major, notices))
    return out.getvalue()


class InspectArtifactsTest(unittest.TestCase):
    def run_on(self, files):
        with tempfile.TemporaryDirectory() as repo:
            for path, data in files.items():
                p = pathlib.Path(repo, "io/github/yuroyami", path)
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(data)
            r = subprocess.run([sys.executable, str(SCRIPT), "--version", "9.9.9", "--repo", repo], capture_output=True, text=True)
            return r.returncode, r.stdout + r.stderr

    def test_good_artifacts_pass(self):
        code, out = self.run_on({
            "kitepdf-core-jvm/9.9.9/kitepdf-core-jvm-9.9.9.jar": jar(55),
            "kitepdf-core-android/9.9.9/kitepdf-core-android-9.9.9.aar": aar(21),
            "kitepdf-skia-renderer-android/9.9.9/kitepdf-skia-renderer-android-9.9.9.aar": aar(24),
        })
        self.assertEqual(0, code, out)

    def test_java_21_classes_fail(self):
        code, out = self.run_on({"kitepdf-core-jvm/9.9.9/kitepdf-core-jvm-9.9.9.jar": jar(65)})
        self.assertEqual(1, code)
        self.assertIn("Java 21", out)

    def test_a_missing_notice_fails(self):
        code, out = self.run_on({"kitepdf-core-jvm/9.9.9/kitepdf-core-jvm-9.9.9.jar": jar(55, notices=False)})
        self.assertEqual(1, code)
        self.assertIn("META-INF/NOTICE", out)

    def test_a_wrong_min_sdk_fails(self):
        code, out = self.run_on({"kitepdf-skia-renderer-android/9.9.9/kitepdf-skia-renderer-android-9.9.9.aar": aar(21)})
        self.assertEqual(1, code)
        self.assertIn("asks for 24", out)

    def test_the_toolchain_aar_layout_is_read(self):
        # Classes in libs/<artifact>-jvm.jar, as the Kotlin Toolchain writes an AAR.
        good = aar(21, inner="libs/kitepdf-core-jvm.jar")
        self.assertEqual(0, self.run_on({"kitepdf-core-android/9.9.9/kitepdf-core-android-9.9.9.aar": good})[0])
        code, out = self.run_on({"kitepdf-core-android/9.9.9/kitepdf-core-android-9.9.9.aar": aar(21, major=65, inner="libs/kitepdf-core-jvm.jar", notices=False)})
        self.assertEqual(1, code)
        self.assertIn("Java 21", out)
        self.assertIn("META-INF/NOTICE", out)

    def test_no_artifacts_is_an_error(self):
        code, _ = self.run_on({})
        self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()

"""Offline regression checks for the public-corpus provisioning boundary."""

import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.request

import corpus


class Response(io.BytesIO):
    headers = {}

    def geturl(self):
        return "https://example.org/pinned.pdf"


class CorpusToolTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / "cache"
        self.payload = b"%PDF-1.7\nfixture\n%%EOF\n"
        self.entry = {
            "id": "licensed-fixture", "path": "corpus/pdf/licensed-fixture.pdf",
            "sha256": hashlib.sha256(self.payload).hexdigest(), "bytes": len(self.payload),
            "title": "Test fixture", "creators": "Test author", "license": "CC0-1.0",
            "source_url": "https://example.org/source", "download_url": "https://example.org/pinned.pdf",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "license_evidence": "Fixture authored for this test", "attribution": "Test author",
            "upstream_revision": "test-only", "coverage_tags": ["test"],
        }

    def manifest(self, entries):
        path = self.root / "manifest.json"
        path.write_text(json.dumps({"schema_version": 1, "entries": entries}))
        return path

    def put(self, path, payload=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.payload if payload is None else payload)

    def test_manifest_rejects_duplicates_unsafe_paths_and_unreviewed_licences(self):
        corpus.load_manifest(self.manifest([self.entry]), self.root)
        for change in [
            {"path": "corpus/pdf/../../escape.pdf"}, {"license": "all-rights-reserved"},
            {"bytes": corpus.MAX_FILE_BYTES + 1}, {"download_url": "http://example.org/a.pdf"},
            {"sha256": "bad"}, {"license_evidence": ""},
            {"path": "corpus/pdf/./licensed-fixture.pdf"}, {"coverage_tags": [None]},
        ]:
            with self.subTest(change=change), self.assertRaises(corpus.CorpusError):
                corpus.load_manifest(self.manifest([{**self.entry, **change}]), self.root)
        with self.assertRaises(corpus.CorpusError):
            corpus.load_manifest(self.manifest([self.entry, self.entry]), self.root)

    def test_offline_cache_hit_populates_exact_bytes_without_network(self):
        self.put(self.cache / self.entry["sha256"])
        def forbidden(*args, **kwargs):
            self.fail("offline fetch attempted the network")
        corpus.fetch_entry(self.entry, self.root, self.cache, offline=True, opener=forbidden)
        self.assertEqual(self.payload, (self.root / self.entry["path"]).read_bytes())

    def test_offline_cache_miss_and_corruption_fail_closed(self):
        with self.assertRaisesRegex(corpus.CorpusError, "offline cache miss"):
            corpus.fetch_entry(self.entry, self.root, self.cache, offline=True)
        self.put(self.cache / self.entry["sha256"], self.payload.replace(b"fixture", b"changed"))
        with self.assertRaisesRegex(corpus.CorpusError, "SHA-256 mismatch"):
            corpus.fetch_entry(self.entry, self.root, self.cache, offline=True)
        self.assertFalse((self.root / self.entry["path"]).exists())

    def test_existing_altered_document_is_not_overwritten(self):
        target = self.root / self.entry["path"]
        self.put(target, b"private unrelated bytes")
        self.put(self.cache / self.entry["sha256"])
        with self.assertRaises(corpus.CorpusError):
            corpus.fetch_entry(self.entry, self.root, self.cache, offline=True)
        self.assertEqual(b"private unrelated bytes", target.read_bytes())

    def test_download_checks_exact_hash_size_and_cleans_partial_files(self):
        for payload in [self.payload[:-1], self.payload + b"x", self.payload.replace(b"fixture", b"changed")]:
            with self.subTest(payload=payload), self.assertRaises(corpus.CorpusError):
                corpus.download(self.entry, self.cache, opener=lambda *a, **k: Response(payload))
            self.assertEqual([], list(self.cache.iterdir()))
        stored = corpus.download(self.entry, self.cache, opener=lambda *a, **k: Response(self.payload))
        self.assertEqual(self.payload, stored.read_bytes())

    def test_document_created_during_download_is_not_overwritten(self):
        target = self.root / self.entry["path"]
        def concurrent_document(*args, **kwargs):
            self.put(target, b"private document")
            return Response(self.payload)
        with self.assertRaises(corpus.CorpusError):
            corpus.fetch_entry(self.entry, self.root, self.cache, opener=concurrent_document)
        self.assertEqual(b"private document", target.read_bytes())

    def test_symlink_destination_is_rejected(self):
        outside = self.root / "outside"
        self.put(outside)
        target = self.root / self.entry["path"]
        target.parent.mkdir(parents=True)
        target.symlink_to(outside)
        with self.assertRaisesRegex(corpus.CorpusError, "symlinks"):
            corpus.fetch_entry(self.entry, self.root, self.cache, offline=True)

    def test_redirects_are_validated_before_opening_destination(self):
        handler = corpus.HttpsRedirectHandler()
        request = urllib.request.Request(self.entry["download_url"])
        for destination in ("http://example.org/file.pdf", "ftp://example.org/file.pdf"):
            with self.subTest(destination=destination), self.assertRaises(corpus.CorpusError):
                handler.redirect_request(request, None, 302, "Found", {}, destination)
        redirect = handler.redirect_request(request, None, 302, "Found", {}, "https://example.org/other.pdf")
        self.assertEqual("https://example.org/other.pdf", redirect.full_url)

    def test_receipt_does_not_overwrite_symlink_target(self):
        outside = self.root / "outside"
        self.put(outside, b"private document")
        receipt = self.root / "corpus/receipt.json"
        receipt.parent.mkdir()
        receipt.symlink_to(outside)
        with self.assertRaisesRegex(corpus.CorpusError, "symlinks"):
            corpus.write_receipt(self.root, "hash", [self.entry])
        self.assertEqual(b"private document", outside.read_bytes())

    def test_verify_requires_every_manifest_document(self):
        manifest = self.manifest([self.entry])
        self.assertEqual(1, corpus.main(["verify", "--manifest", str(manifest), "--root", str(self.root)]))
        self.put(self.root / self.entry["path"])
        self.assertEqual(0, corpus.main(["verify", "--manifest", str(manifest), "--root", str(self.root)]))
        receipt = json.loads((self.root / "corpus/receipt.json").read_text())
        self.assertEqual(self.entry["sha256"], receipt["verified"][0]["sha256"])


class PublicInventoryTest(unittest.TestCase):
    def test_publication_coverage_does_not_shrink_below_issue_624(self):
        entries = corpus.load_manifest(corpus.ROOT / "corpus/manifest.json", corpus.ROOT)
        # Only independently published works count here; feature samples and generated
        # fixtures cannot meet the real-publication target by multiplying tiny files.
        publications = [e for e in entries if e.get("publication_kind") == "third-party-publication"]
        pdfs = [e for e in publications if e["path"].endswith(".pdf")]
        books = [e for e in publications if e["path"].endswith(".epub")]
        self.assertGreaterEqual(len(pdfs), 25)
        self.assertGreaterEqual(sum(min(e.get("pages", 0), 6) for e in pdfs), 150)
        self.assertGreaterEqual(len(books), 30)


if __name__ == "__main__":
    unittest.main()

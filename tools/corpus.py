#!/usr/bin/env python3
"""Fetch or verify the licensed public corpus without network access during tests (#194)."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
ALLOWED_LICENSES = {
    "CC0-1.0", "CC-BY-3.0", "CC-BY-4.0", "CC-BY-SA-2.5", "CC-BY-SA-3.0",
    "CC-BY-SA-4.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "W3C-20150513",
    "W3C-20230101", "LicenseRef-W3C-Software-and-Document", "Apache-2.0", "OFL-1.1",
}


class CorpusError(Exception):
    """A failed intake or verification, never a silently skipped document."""


def https_url(value):
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError as error:
        raise CorpusError(f"invalid URL: {value!r}") from error
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise CorpusError(f"expected a public HTTPS URL: {value!r}")
    return value


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        https_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_https(request, timeout):
    return urllib.request.build_opener(HttpsRedirectHandler()).open(request, timeout=timeout)


def destination(root, relative):
    path = PurePosixPath(relative)
    if (path.is_absolute() or path.as_posix() != relative or "\\" in relative or ".." in path.parts
            or len(path.parts) != 3 or path.parts[0] != "corpus"
            or path.parts[1] not in ("pdf", "epub")
            or path.suffix != "." + path.parts[1]):
        raise CorpusError(f"unsafe corpus path: {relative!r}")
    target = root / relative
    for part in (target, *target.parents):
        if part == root.parent:
            break
        if part.is_symlink():
            raise CorpusError(f"corpus paths must not follow symlinks: {part}")
    return target


def load_manifest(path, root):
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise CorpusError(f"cannot read manifest {path}: {error}") from error
    if not isinstance(manifest, dict):
        raise CorpusError("manifest must be a JSON object")
    entries = manifest.get("entries")
    if type(manifest.get("schema_version")) is not int or manifest["schema_version"] != 1 or not isinstance(entries, list) or not entries:
        raise CorpusError("manifest needs schema_version 1 and a nonempty entries list")
    seen = {key: set() for key in ("id", "path", "sha256")}
    total = 0
    for entry in entries:
        if not isinstance(entry, dict):
            raise CorpusError("each manifest entry must be an object")
        for key in ("id", "path", "sha256", "title", "creators", "source_url", "download_url",
                    "license", "license_url", "attribution", "license_evidence", "upstream_revision"):
            if not isinstance(entry.get(key), str) or not entry[key].strip():
                raise CorpusError(f"entry {entry.get('id', '?')} needs a nonempty {key}")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,95}", entry["id"]):
            raise CorpusError(f"invalid id: {entry['id']}")
        if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise CorpusError(f"invalid SHA-256: {entry['id']}")
        for key in seen:
            value = entry[key].casefold()
            if value in seen[key]:
                raise CorpusError(f"duplicate {key}: {entry[key]}")
            seen[key].add(value)
        destination(root, entry["path"])
        if any(license_id not in ALLOWED_LICENSES for license_id in entry["license"].split(" AND ")):
            raise CorpusError(f"licence needs an explicit intake review: {entry['id']}: {entry['license']}")
        for key in ("source_url", "download_url", "license_url"):
            https_url(entry[key])
        size = entry.get("bytes")
        if type(size) is not int or not 0 < size <= MAX_FILE_BYTES:
            raise CorpusError(f"invalid or oversized byte count: {entry['id']}")
        total += size
        if (not isinstance(entry.get("coverage_tags"), list) or not entry["coverage_tags"]
                or any(not isinstance(tag, str) or not tag.strip() for tag in entry["coverage_tags"])):
            raise CorpusError(f"missing coverage tags: {entry['id']}")
    if total > MAX_TOTAL_BYTES:
        raise CorpusError(f"manifest exceeds the {MAX_TOTAL_BYTES}-byte intake budget")
    return sorted(entries, key=lambda entry: entry["id"])


def verify_file(path, entry):
    if path.is_symlink() or not path.is_file():
        raise CorpusError(f"missing regular file: {path}")
    if path.stat().st_size != entry["bytes"]:
        raise CorpusError(f"size mismatch: {entry['id']}: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != entry["sha256"]:
        raise CorpusError(f"SHA-256 mismatch: {entry['id']}: {path}")
    if entry["path"].endswith(".pdf"):
        with path.open("rb") as stream:
            if b"%PDF-" not in stream.read(1024):
                raise CorpusError(f"not a PDF: {entry['id']}")
    else:
        try:
            with zipfile.ZipFile(path) as book:
                info = book.getinfo("mimetype")
                if info.file_size > 128 or book.read(info) != b"application/epub+zip":
                    raise CorpusError(f"not an EPUB package: {entry['id']}")
        except (OSError, KeyError, zipfile.BadZipFile) as error:
            raise CorpusError(f"invalid EPUB: {entry['id']}: {error}") from error


def atomic_copy(source, target, entry, preserve_existing=False):
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".corpus-", dir=target.parent)
    os.close(descriptor)
    temporary = Path(name)
    try:
        shutil.copyfile(source, temporary)
        verify_file(temporary, entry)
        if preserve_existing:
            try:
                os.link(temporary, target)
            except FileExistsError:
                verify_file(target, entry)
        else:
            os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def download(entry, cache, opener=open_https):
    request = urllib.request.Request(https_url(entry["download_url"]), headers={
        "User-Agent": "KitePDF-public-corpus/1.0", "Accept-Encoding": "identity",
    })
    cache.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".download-", dir=cache)
    temporary = Path(name)
    started = time.monotonic()
    try:
        with os.fdopen(descriptor, "wb") as output, opener(request, timeout=30) as response:
            https_url(response.geturl())
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > entry["bytes"]:
                raise CorpusError(f"oversized response: {entry['id']}")
            received = 0
            read_chunk = getattr(response, "read1", response.read)
            while True:
                chunk = read_chunk(min(64 * 1024, entry["bytes"] - received + 1))
                if not chunk:
                    break
                received += len(chunk)
                if received > entry["bytes"] or time.monotonic() - started > 180:
                    raise CorpusError(f"download exceeded its size or time budget: {entry['id']}")
                output.write(chunk)
        verify_file(temporary, entry)
        stored = cache / entry["sha256"]
        os.replace(temporary, stored)
        return stored
    except (OSError, ValueError) as error:
        raise CorpusError(f"download failed: {entry['id']}: {error}") from error
    finally:
        temporary.unlink(missing_ok=True)


def fetch_entry(entry, root, cache, offline=False, opener=open_https):
    target = destination(root, entry["path"])
    stored = cache / entry["sha256"]
    if target.exists():
        # Never overwrite an unrelated local drop-in or silently bless altered bytes.
        verify_file(target, entry)
        if not stored.exists():
            atomic_copy(target, stored, entry)
        else:
            verify_file(stored, entry)
        return
    if stored.exists():
        verify_file(stored, entry)
    elif offline:
        raise CorpusError(f"offline cache miss: {entry['id']}; run tools/corpus.py fetch online first")
    else:
        stored = download(entry, cache, opener)
    atomic_copy(stored, target, entry, preserve_existing=True)


def write_receipt(root, manifest_hash, entries):
    receipt = root / "corpus/receipt.json"
    if receipt.is_symlink() or receipt.parent.is_symlink():
        raise CorpusError(f"receipt paths must not follow symlinks: {receipt}")
    receipt.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".receipt-", dir=receipt.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump({
                "manifest_sha256": manifest_hash,
                "verified": [{key: entry[key] for key in ("id", "path", "sha256", "bytes")} for entry in entries],
            }, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, receipt)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("fetch", "verify", "list"))
    parser.add_argument("--manifest", type=Path, default=ROOT / "corpus/manifest.json")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/kitepdf/public-corpus")
    parser.add_argument("--offline", action="store_true", help="fetch only from the verified local cache")
    args = parser.parse_args(argv)
    try:
        entries = load_manifest(args.manifest, args.root)
        for entry in entries:
            if args.command == "fetch":
                fetch_entry(entry, args.root, args.cache_dir, args.offline)
            elif args.command == "verify":
                verify_file(destination(args.root, entry["path"]), entry)
            print(f"{entry['id']}\t{entry['bytes']}\t{entry['license']}\t{entry['path']}")
        total = sum(entry["bytes"] for entry in entries)
        manifest_hash = hashlib.sha256(args.manifest.read_bytes()).hexdigest()
        print(f"{args.command}: {len(entries)} documents, {total} bytes, manifest SHA-256 {manifest_hash}")
        if args.command != "list":
            write_receipt(args.root, manifest_hash, entries)
        return 0
    except CorpusError as error:
        print(f"corpus: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

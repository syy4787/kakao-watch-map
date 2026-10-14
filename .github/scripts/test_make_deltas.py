#!/usr/bin/env python3
"""make-deltas.py 시험 — python3 .github/scripts/test_make_deltas.py"""
import hashlib
import importlib.util
import io
import json
import os
import random
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("make_deltas", os.path.join(HERE, "make-deltas.py"))
md = importlib.util.module_from_spec(spec)
spec.loader.exec_module(md)


def make_zip(path, entries):
    """entries = [(이름, 바이트, 압축?)] 순서대로"""
    with zipfile.ZipFile(path, "w") as z:
        for name, data, deflate in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED
            z.writestr(info, data)


def noise(n, seed):
    r = random.Random(seed)
    return bytes(r.getrandbits(8) for _ in range(n))


class MakeDeltasTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.engine = noise(400_000, 1)          # 그대로인 큰 라이브러리 (저장)
        self.assets = noise(150_000, 2) * 2      # 그대로인 압축 항목
        self.old = os.path.join(self.dir, "old.apk")
        self.new = os.path.join(self.dir, "new.apk")
        make_zip(self.old, [("AndroidManifest.xml", b"<m v=1/>" * 50, True), ("classes.dex", noise(60_000, 3), True),
                            ("lib/armeabi-v7a/libxul.so", self.engine, False), ("assets/omni.ja", self.assets, True)])
        # 새 것: 코드가 바뀌고, 항목 순서·이름 길이가 달라지고, 새 항목이 생김
        make_zip(self.new, [("AndroidManifest.xml", b"<m v=2/>" * 50, True), ("assets/omni.ja", self.assets, True),
                            ("classes.dex", noise(61_000, 4), True), ("res/new.xml", b"<x/>", False),
                            ("lib/armeabi-v7a/libxul.so", self.engine, False)])

    def test_rebuilds_the_new_file_exactly_and_is_small(self):
        patch = md.make_patch(self.old, self.new)
        rebuilt = md.apply_patch(self.old, patch)
        with open(self.new, "rb") as f:
            self.assertEqual(f.read(), rebuilt)
        self.assertLess(len(patch), os.path.getsize(self.new) * 0.3, "그대로인 엔진·자산은 복사로")

    def test_a_broken_patch_is_refused(self):
        patch = md.make_patch(self.old, self.new)
        with self.assertRaises(md.BadPatch):
            md.apply_patch(self.old, b"NOTDELTA" + patch[8:])
        with self.assertRaises(md.BadPatch):
            md.apply_patch(self.old, patch[: len(patch) // 2])
        # 다른 이전 파일에 적용하면 결과가 달라 거부
        other = os.path.join(self.dir, "other.apk")
        make_zip(other, [("lib/armeabi-v7a/libxul.so", noise(400_000, 9), False)])
        with self.assertRaises(md.BadPatch):
            md.apply_patch(other, patch)

    def test_cli_writes_patches_and_lists_them(self):
        out = os.path.join(self.dir, "out")
        buf = io.StringIO()
        with redirect_stdout(buf):
            md.main(["--new", self.new, "--old", f"123={self.old}", "--old", "122=/없는/파일.apk",
                     "--out-dir", out, "--prefix", "app-armeabi-v7a", "--url-base", "https://example.test/v124"])
        listed = json.loads(buf.getvalue())
        self.assertEqual(1, len(listed))
        item = listed[0]
        self.assertEqual(123, item["fromVersion"])
        self.assertEqual("https://example.test/v124/app-armeabi-v7a-from-v123.kwdelta", item["url"])
        with open(self.old, "rb") as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), item["from"])
        path = os.path.join(out, "app-armeabi-v7a-from-v123.kwdelta")
        with open(path, "rb") as f:
            data = f.read()
        self.assertEqual(item["size"], len(data))
        self.assertEqual(item["sha256"], hashlib.sha256(data).hexdigest())

    def test_no_patch_when_nothing_is_shared(self):
        unrelated = os.path.join(self.dir, "unrelated.apk")
        make_zip(unrelated, [("x", noise(300_000, 7), False)])
        out = os.path.join(self.dir, "out2")
        buf = io.StringIO()
        with redirect_stdout(buf):
            md.main(["--new", self.new, "--old", f"100={unrelated}", "--out-dir", out, "--prefix", "p", "--url-base", "u"])
        self.assertEqual([], json.loads(buf.getvalue()))


if __name__ == "__main__":
    unittest.main()

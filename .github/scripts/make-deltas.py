#!/usr/bin/env python3
"""
작은 업데이트(.kwdelta) 만들기 — 워치에 깔린 이전 버전 APK 로 새 APK 를 똑같이 다시 만드는 패치.

새 APK 의 대부분(재생 엔진 libxul.so·omni.ja 등 약 100MB)은 이전 버전과 바이트까지 같다. 그래서 zip 항목마다
(이름·CRC·압축 크기·압축 방식·원래 크기)가 같고 데이터 바이트도 같으면 '이전 APK 의 이 범위를 복사'로 적고,
나머지(바뀐 classes.dex, 항목 머리글, 중앙 디렉터리, 서명 블록)만 그대로 싣는다. 실측: v123→v124 는 104.5MB 대신 약 3.8MB.

형식 (빅엔디언): b"KWDELTA1" 다음에 zlib 스트림 하나 —
  'C' u64 오프셋 u32 길이      이전 APK 에서 복사
  'L' u32 길이 + 바이트         그대로
  'E' u64 전체 길이 + SHA-256   끝 (워치는 다시 만든 파일이 이것과 update.json 의 SHA-256 둘 다와 맞아야 설치)
워치 쪽: app/src/main/java/com/mywatch/kakaomap/update/DeltaPatch.kt

사용:
  make-deltas.py --new NEW.apk --old 123=OLD123.apk --old 122=OLD122.apk \
      --out-dir DIR --prefix kakao-watch-map-armeabi-v7a --url-base https://…/download/v124
  → DIR/<prefix>-from-v123.kwdelta … 를 만들고, update.json 에 넣을 목록(JSON)을 출력한다.
  만든 패치는 이 스크립트가 직접 적용해 새 APK 와 SHA-256 이 같은지 확인한다 (틀린 패치는 올리지 않는다).
"""
import argparse
import hashlib
import json
import os
import struct
import sys
import zipfile
import zlib

MAGIC = b"KWDELTA1"
LOCAL_HEADER = b"PK\x03\x04"


def entry_ranges(path):
    """zip 항목마다 (열쇠, 데이터 시작, 데이터 길이). 열쇠 = (이름, CRC, 압축 크기, 압축 방식, 원래 크기)"""
    out = []
    with zipfile.ZipFile(path) as z, open(path, "rb") as f:
        for info in z.infolist():
            f.seek(info.header_offset)
            head = f.read(30)
            if len(head) < 30 or head[:4] != LOCAL_HEADER:
                raise ValueError(f"{path}: {info.filename} 의 머리글이 이상해요")
            name_len, extra_len = struct.unpack("<HH", head[26:30])
            start = info.header_offset + 30 + name_len + extra_len
            key = (info.filename, info.CRC, info.compress_size, info.compress_type, info.file_size)
            out.append((key, start, info.compress_size))
    return out


def plan(old_path, new_path, new_data):
    """새 파일을 처음부터 끝까지 덮는 기록 목록: ('C', 이전 오프셋, 길이) 또는 ('L', 새 시작, 새 끝)"""
    old = {}
    for key, start, length in entry_ranges(old_path):
        old.setdefault(key, (start, length))
    ops = []
    pos = 0
    with open(old_path, "rb") as fo:
        for key, start, length in sorted(entry_ranges(new_path), key=lambda t: t[1]):
            if length == 0 or key not in old or start < pos:
                continue
            ostart, olength = old[key]
            fo.seek(ostart)
            if fo.read(olength) != new_data[start:start + length]:
                continue
            if start > pos:
                ops.append(("L", pos, start))
            # 이전 것과 이어지는 복사는 하나로
            if ops and ops[-1][0] == "C" and ops[-1][1] + ops[-1][2] == ostart and ops[-1][2] + length < 0xFFFFFFFF:
                ops[-1] = ("C", ops[-1][1], ops[-1][2] + length)
            else:
                ops.append(("C", ostart, length))
            pos = start + length
    if pos < len(new_data):
        ops.append(("L", pos, len(new_data)))
    return ops


def make_patch(old_path, new_path):
    with open(new_path, "rb") as f:
        new_data = f.read()
    comp = zlib.compressobj(9)
    parts = [MAGIC]
    for op in plan(old_path, new_path, new_data):
        if op[0] == "C":
            parts.append(comp.compress(b"C" + struct.pack(">QI", op[1], op[2])))
        else:
            chunk = new_data[op[1]:op[2]]
            # 한 기록은 4GB 미만 — APK 는 그보다 훨씬 작다
            parts.append(comp.compress(b"L" + struct.pack(">I", len(chunk))))
            parts.append(comp.compress(chunk))
    parts.append(comp.compress(b"E" + struct.pack(">Q", len(new_data)) + hashlib.sha256(new_data).digest()))
    parts.append(comp.flush())
    return b"".join(parts)


class BadPatch(Exception):
    pass


def apply_patch(old_path, patch):
    """워치와 같은 방법으로 패치를 적용해 새 파일 바이트를 돌려준다 (틀리면 BadPatch)"""
    if patch[:8] != MAGIC:
        raise BadPatch("형식이 달라요")
    try:
        body = zlib.decompress(patch[8:])
    except zlib.error as e:
        raise BadPatch(f"압축이 깨졌어요: {e}")
    with open(old_path, "rb") as f:
        old = f.read()
    out = bytearray()
    i = 0
    while True:
        if i >= len(body):
            raise BadPatch("끝 표시가 없어요")
        op = body[i:i + 1]
        i += 1
        if op == b"C":
            off, length = struct.unpack(">QI", body[i:i + 12])
            i += 12
            if off + length > len(old):
                raise BadPatch("이전 파일 밖을 복사하래요")
            out += old[off:off + length]
        elif op == b"L":
            (length,) = struct.unpack(">I", body[i:i + 4])
            i += 4
            if i + length > len(body):
                raise BadPatch("잘렸어요")
            out += body[i:i + length]
            i += length
        elif op == b"E":
            (total,) = struct.unpack(">Q", body[i:i + 8])
            sha = body[i + 8:i + 40]
            if total != len(out) or hashlib.sha256(out).digest() != sha:
                raise BadPatch("다시 만든 파일이 달라요")
            return bytes(out)
        else:
            raise BadPatch(f"모르는 기록 {op!r}")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--new", required=True)
    p.add_argument("--old", action="append", default=[], help="버전=경로 (여러 번)")
    p.add_argument("--out-dir", required=True)
    p.add_argument("--prefix", required=True)
    p.add_argument("--url-base", required=True)
    p.add_argument("--max-ratio", type=float, default=0.6, help="새 APK 의 이 비율보다 큰 패치는 만들지 않음")
    a = p.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=True)
    new_sha = sha256_file(a.new)
    new_size = os.path.getsize(a.new)
    made = []
    for spec in a.old:
        version, _, path = spec.partition("=")
        if not version.isdigit() or not os.path.isfile(path):
            print(f"건너뜀: {spec}", file=sys.stderr)
            continue
        try:
            patch = make_patch(path, a.new)
            rebuilt = apply_patch(path, patch)
        except (ValueError, zipfile.BadZipFile, BadPatch) as e:
            print(f"v{version}: 만들지 못함 — {e}", file=sys.stderr)
            continue
        if hashlib.sha256(rebuilt).hexdigest() != new_sha:
            print(f"v{version}: 다시 만든 파일이 새 APK 와 달라요 — 올리지 않음", file=sys.stderr)
            continue
        if len(patch) > a.max_ratio * new_size:
            print(f"v{version}: 패치가 {len(patch)} 바이트 — 새 APK({new_size})와 거의 같아 만들지 않음", file=sys.stderr)
            continue
        name = f"{a.prefix}-from-v{version}.kwdelta"
        with open(os.path.join(a.out_dir, name), "wb") as f:
            f.write(patch)
        made.append({
            "from": sha256_file(path),
            "fromVersion": int(version),
            "url": f"{a.url_base}/{name}",
            "sha256": hashlib.sha256(patch).hexdigest(),
            "size": len(patch),
        })
        print(f"v{version} → {name}: {len(patch):,} 바이트 (새 APK {new_size:,})", file=sys.stderr)
    json.dump(made, sys.stdout)
    print()


if __name__ == "__main__":
    main()

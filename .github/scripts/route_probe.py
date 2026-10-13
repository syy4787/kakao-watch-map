#!/usr/bin/env python3
"""(임시) 카카오 길찾기 API 가 실제로 어떤 경로·시간을 주는지 — 장소 이름·좌표는 출력하지 않는다."""
import json, math, os, sys, urllib.parse, urllib.request

KEY = os.environ["KAKAO_KEY"]
BASE = "https://dapi.kakao.com"

def call(path, params):
    req = urllib.request.Request(BASE + path + "?" + urllib.parse.urlencode(params), headers={"Authorization": "KakaoAK " + KEY})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return {"_http": e.code, "_body": e.read().decode("utf-8", "replace")[:300]}

def place(q):
    docs = call("/v2/local/search/keyword.json", {"query": q, "size": 5}).get("documents", [])
    return docs[0] if docs else None

o, d = place(sys.argv[1]), place(sys.argv[2])
if not o or not d:
    print("장소 검색 실패", bool(o), bool(d)); sys.exit(1)
lat1, lng1, lat2, lng2 = float(o["y"]), float(o["x"]), float(d["y"]), float(d["x"])
km = 6371 * 2 * math.asin(math.sqrt(math.sin(math.radians(lat2 - lat1) / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(math.radians(lng2 - lng1) / 2) ** 2))
print(f"출발·도착 찾음 — 직선 {km:.2f}km")
params = {"start_x": o["x"], "start_y": o["y"], "end_x": d["x"], "end_y": d["y"], "s_name": "출발", "e_name": "도착"}

tr = call("/v2/routing/publictraffic", params)
print("publictraffic status:", tr.get("status"), tr.get("_http", ""), tr.get("_body", "")[:200])
routes = tr.get("routes") or []
if routes:
    print("route properties keys:", sorted((routes[0].get("properties") or {}).keys()))
    st0 = (routes[0].get("steps") or [{}])[0].get("properties") or {}
    print("step properties keys:", sorted(st0.keys()))
for i, r in enumerate(routes):
    p = r.get("properties") or {}
    steps = []
    gap = p.get("totalTime", 0)
    for s in r.get("steps") or []:
        sp = s.get("properties") or {}
        gap -= sp.get("time", 0)
        veh = ",".join(v.get("name", "") for v in sp.get("vehicles") or [])
        steps.append(f"{sp.get('type')} {sp.get('time', 0) / 60:.1f}분 {sp.get('distance', 0)}m" + (f" [{veh}]" if veh else ""))
    extra = {k: v for k, v in p.items() if k not in ("totalTime", "totalDistance", "transfers", "type", "fare")}
    print(f"#{i} {p.get('type')} 총 {p.get('totalTime', 0) / 60:.1f}분 거리 {p.get('totalDistance')}m 환승 {p.get('transfers')} (단계 합과 차이 {gap / 60:.1f}분) {extra if extra else ''}")
    print("    " + " | ".join(steps))

for mode, path in (("도보", "/v2/routing/walk"), ("자전거", "/v2/routing/bicycle")):
    w = call(path, params)
    rs = w.get("routes") or []
    if rs:
        p = rs[0].get("properties") or {}
        print(f"{mode}: {p.get('totalTime', 0) / 60:.1f}분 {p.get('totalDistance')}m (status {w.get('status')})")
    else:
        print(f"{mode}: 없음", w.get("status"), w.get("_http", ""), str(w.get("_body", ""))[:200])

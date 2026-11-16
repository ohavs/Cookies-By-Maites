"""Daily price fetch: chain transparency files -> Firestore.

Only barcodes listed in Firestore `watch/*` are kept. Each chain is fetched
independently; a failing chain keeps yesterday's prices.

Env:
  FIREBASE_PROJECT   project id
  FIREBASE_TOKEN     firebase-tools refresh token (CI secret)
  BARCODES           optional comma list (skips `watch`, for local tests)
  DRY_RUN=1          print instead of writing
"""
import gzip
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
CITIES = json.load(open(os.path.join(HERE, "cities.json"), encoding="utf-8"))
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) CookiesByMaites-prices/1.0"}


def get(url, timeout=60, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
                return r.read()
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(2 * (i + 1))


def maybe_gunzip(b):
    return gzip.decompress(b) if b[:2] == b"\x1f\x8b" else b


def text(el, tag):
    v = el.find(tag)
    return (v.text or "").strip() if v is not None and v.text else ""


def city_name(raw):
    raw = " ".join((raw or "").split())
    if raw.isdigit():
        return CITIES.get(str(int(raw)), "")
    return raw


# ---------- chains ----------

class Shufersal:
    key, name = "shufersal", "שופרסל"
    base = "https://prices.shufersal.co.il/FileObject/UpdateCategory"

    def _links(self, cat):
        out, page = [], 1
        while True:
            html = get(f"{self.base}?catID={cat}&storeId=0&page={page}").decode("utf-8", "replace")
            links = [l.replace("&amp;", "&") for l in re.findall(r'href="(https://pricesprodpublic[^"]+)"', html)]
            out += links
            if not links or f"page={page + 1}" not in html:
                return out
            page += 1

    def stores(self):
        root = ET.fromstring(maybe_gunzip(get(self._links(5)[0])).decode("utf-8-sig").encode())
        res = {}
        for s in root.iter("Store"):
            sid = str(int(text(s, "StoreID")))
            res[sid] = {"n": text(s, "StoreName"), "c": city_name(text(s, "City")), "a": text(s, "Address")}
        return res

    def price_files(self):
        latest = {}
        for u in self._links(2):
            m = re.search(r"PriceFull\d+-(\d+)-(\d+)-(\d{8}-?\d+)", u)
            if m:
                k = int(m.group(2))
                if k not in latest or m.group(3) > latest[k][0]:
                    latest[k] = (m.group(3), u)
        return [u for _, u in latest.values()]


CHAINS = [Shufersal()]


def parse_prices(blob, watched):
    """-> (storeId, {barcode: (agorot, name)})"""
    store, found = None, {}
    for _, el in ET.iterparse(io.BytesIO(maybe_gunzip(blob)), events=("end",)):
        tag = el.tag
        if tag == "StoreID" or tag == "StoreId":
            store = str(int(el.text.strip()))
        elif tag in ("Item", "Product"):
            code = text(el, "ItemCode").lstrip("0")
            if code in watched and text(el, "bIsWeighted") != "1":
                try:
                    found[code] = (round(float(text(el, "ItemPrice")) * 100), text(el, "ItemName"))
                except ValueError:
                    pass
            el.clear()
    return store, found


def fetch_chain(chain, watched):
    stores = chain.stores()
    files = chain.price_files()
    print(f"[{chain.key}] {len(stores)} stores, {len(files)} price files", flush=True)
    prices, names = {}, {}

    def one(u):
        try:
            return parse_prices(get(u, timeout=120), watched)
        except Exception as e:
            print(f"[{chain.key}] skip {u[:90]}: {e}", flush=True)
            return None, {}

    with ThreadPoolExecutor(16) as ex:
        for store, found in ex.map(one, files):
            if not store:
                continue
            for code, (agorot, nm) in found.items():
                prices.setdefault(code, {})[f"{chain.key}_{store}"] = agorot
                names.setdefault(code, nm)
    return stores, prices, names


# ---------- firestore REST ----------

class Firestore:
    def __init__(self, project, refresh_token):
        self.root = f"projects/{project}/databases/(default)/documents"
        self.url = f"https://firestore.googleapis.com/v1/{self.root}"
        # Public OAuth client of firebase-tools (same one `firebase login:ci` issues tokens for).
        body = urllib.parse.urlencode({
            "client_id": "563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com",
            "client_secret": "j9iVZfS8kkCEFUPaAeJV0sAi",
            "refresh_token": refresh_token, "grant_type": "refresh_token",
        }).encode()
        with urllib.request.urlopen("https://oauth2.googleapis.com/token", body, timeout=30) as r:
            self.token = json.load(r)["access_token"]

    def _req(self, method, url, body=None):
        req = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
                                     data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)

    def list_ids(self, coll):
        ids, tok = [], ""
        while True:
            q = f"{self.url}/{coll}?pageSize=300&mask.fieldPaths=at" + (f"&pageToken={tok}" if tok else "")
            r = self._req("GET", q)
            ids += [d["name"].rsplit("/", 1)[1] for d in r.get("documents", [])]
            tok = r.get("nextPageToken")
            if not tok:
                return ids

    def get_many(self, coll, ids):
        if not ids:
            return {}
        r = self._req("POST", f"https://firestore.googleapis.com/v1/{self.root.rsplit('/documents', 1)[0]}/documents:batchGet",
                      {"documents": [f"{self.root}/{coll}/{i}" for i in ids]})
        return {x["found"]["name"].rsplit("/", 1)[1]: decode_fields(x["found"].get("fields", {})) for x in r if "found" in x}

    def write_many(self, docs):
        """docs: [(path, dict)] — full overwrite."""
        for i in range(0, len(docs), 400):
            writes = [{"update": {"name": f"{self.root}/{p}", "fields": encode_fields(d)}} for p, d in docs[i:i + 400]]
            self._req("POST", f"https://firestore.googleapis.com/v1/{self.root.rsplit('/documents', 1)[0]}/documents:commit", {"writes": writes})


def encode(v):
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    if isinstance(v, dict):
        return {"mapValue": {"fields": encode_fields(v)}}
    return {"stringValue": str(v)}


def encode_fields(d):
    return {k: encode(v) for k, v in d.items()}


def decode(v):
    if "integerValue" in v:
        return int(v["integerValue"])
    if "mapValue" in v:
        return decode_fields(v["mapValue"].get("fields", {}))
    return next(iter(v.values()))


def decode_fields(f):
    return {k: decode(v) for k, v in f.items()}


def main():
    dry = os.environ.get("DRY_RUN") == "1"
    fs = None if dry else Firestore(os.environ["FIREBASE_PROJECT"], os.environ["FIREBASE_TOKEN"])
    if os.environ.get("BARCODES"):
        watched = {b.strip().lstrip("0") for b in os.environ["BARCODES"].split(",") if b.strip()}
    else:
        watched = {b.lstrip("0") for b in fs.list_ids("watch") if b.isdigit()}
    print(f"watching {len(watched)} barcodes", flush=True)

    now = int(time.time() * 1000)
    ok, store_docs, all_prices, all_names = [], [], {}, {}
    for ch in CHAINS:
        try:
            stores, prices, names = fetch_chain(ch, watched)
            ok.append(ch.key)
            store_docs.append((f"stores/{ch.key}", {"name": ch.name, "at": now, "s": stores}))
            for code, m in prices.items():
                all_prices.setdefault(code, {}).update(m)
            for code, nm in names.items():
                all_names.setdefault(code, nm)
        except Exception as e:
            print(f"::warning::{ch.key} failed: {e}", flush=True)

    if dry:
        for code, m in all_prices.items():
            print(code, all_names.get(code), len(m), "stores, min", min(m.values()) / 100)
        print("ok chains:", ok)
        return

    # Keep yesterday's prices for chains that failed today.
    old = {}
    ids = sorted(watched)
    for i in range(0, len(ids), 100):
        old.update(fs.get_many("prices", ids[i:i + 100]))
    docs = list(store_docs)
    for code in ids:
        prev = old.get(code, {})
        kept = {k: v for k, v in prev.get("p", {}).items() if k.split("_", 1)[0] not in ok}
        kept.update(all_prices.get(code, {}))
        docs.append((f"prices/{code}", {"name": all_names.get(code) or prev.get("name", ""), "at": now, "p": kept}))
    fs.write_many(docs)
    print(f"wrote {len(docs)} docs; chains ok: {ok}", flush=True)
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()

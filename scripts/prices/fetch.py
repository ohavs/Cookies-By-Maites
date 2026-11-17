"""Daily price fetch: chain transparency files -> Firestore.

Only barcodes listed in Firestore `watch/*` are kept. Each chain is fetched
independently; a failing chain keeps yesterday's prices.

Env:
  FIREBASE_PROJECT   project id
  FIREBASE_TOKEN     firebase-tools refresh token (CI secret)
  BARCODES           optional comma list (skips `watch`, for local tests)
  DRY_RUN=1          print instead of writing

Also writes catalog.tsv.gz (every packaged item seen in any chain:
code, name without size, brand, amount, unit G/M/U, store count, typical
shelf price per chain "chain:agorot,...") — the app searches it by name, so
products can get prices without scanning a barcode.
"""
import gzip
import http.cookiejar
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import threading
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


def _ckey(s):
    s = s.replace("קריית", "קרית").replace("תקוה", "תקווה")
    return " ".join(re.sub(r"[^\wא-ת ]", " ", s).split())


CITY_ALIASES = {"תא": "תל אביב - יפו", "תל אביב": "תל אביב - יפו", "יפו": "תל אביב - יפו",
                "בש": "באר שבע", "פת": "פתח תקווה", "ראשלצ": "ראשון לציון", "ראשון": "ראשון לציון",
                "מודיעין": "מודיעין-מכבים-רעות", "דלית אל כרמל": "דאלית אל-כרמל", "מישור אדומים": "מעלה אדומים",
                # Localities whose names are also common words / street names.
                "ים": "", "גדול": "", "אזור": "", "רחוב": "", "כרמל": "", "אלון": "", "מבקיעים": "",
                "עקרון": "", "שקמים": "", "הדרים": ""}
CITY_KEYS = None


def guess_city(*texts):
    """City from a store name / address when the chain leaves City empty (Yochananof)."""
    global CITY_KEYS
    if CITY_KEYS is None:
        CITY_KEYS = {}
        for name in CITIES.values():
            for v in {name, name.split(" - ")[0], name.split("-")[0]}:
                k = _ckey(v)
                if len(k) >= 3:
                    CITY_KEYS.setdefault(k, name)
        for a, name in CITY_ALIASES.items():
            CITY_KEYS[_ckey(a) if len(a) > 2 else a] = name
    for t in texts:
        k = " " + _ckey((t or "").replace('"', "").replace("'", "")) + " "
        best = ""
        for key, name in CITY_KEYS.items():
            if name and len(key) > len(best) and f" {key} " in k:
                best = key
        if best:
            return CITY_KEYS[best]
    return ""


def fill_cities(stores):
    for st in stores.values():
        if not st["c"]:
            st["c"] = guess_city(st["n"], st["a"])
    return stores


SIZE_RE = re.compile(
    r"\d+(?:[.,]\d+)?\s*(?:[*xX×]\s*\d+(?:[.,]\d+)?\s*)?"
    r"(?:גרם|גר'?|ג'?|ק\"ג|קג|קילו|מ\"ל|מל|ליטר|ל'|יח'|יחידות|יח)(?![א-ת])"
    r"(?:\s*[*xX×]\s*\d+)?")


def short_name(name, amount=0):
    """'פופקו בטעם חמאה ודבש80גר' -> 'פופקו בטעם חמאה ודבש' (size is a separate column)."""
    out = re.sub(r"^\s*מבצע\s*\*?", "", name)
    out = " ".join(SIZE_RE.sub(" ", out).split()).strip(" -,*")
    if amount and re.search(r"\s%g$" % amount, out):
        out = out.rsplit(" ", 1)[0]
    return out if len(out) >= 2 else name


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


class PublishedPrices:
    """url.publishedprices.co.il (Cerberus): public username, empty password."""
    base = "https://url.publishedprices.co.il"

    def __init__(self, key, name, user):
        self.key, self.name, self.user = key, name, user
        self.op = None

    def _open(self, url, data=None, timeout=60):
        req = urllib.request.Request(url, headers=UA, data=urllib.parse.urlencode(data).encode() if data else None)
        with self.op.open(req, timeout=timeout) as r:
            return r.read()

    def _csrf(self, html):
        return re.search(rb'name="csrftoken" content="([^"]+)"', html).group(1).decode()

    def _login(self):
        if self.op:
            return
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        tok = self._csrf(self._open(f"{self.base}/login"))
        self._open(f"{self.base}/login/user", {"username": self.user, "password": "", "Submit": "Sign in", "csrftoken": tok})
        self.tok = self._csrf(self._open(f"{self.base}/file"))

    def _list(self, prefix):
        self._login()
        d = json.loads(self._open(f"{self.base}/file/json/dir", {
            "sEcho": "1", "iDisplayStart": "0", "iDisplayLength": "100000",
            "sSearch": prefix, "cd": "/", "csrftoken": self.tok}))
        return [x["name"] for x in d.get("aaData", []) if x["name"].startswith(prefix)]

    def get(self, name, timeout=120, tries=3):
        for i in range(tries):
            try:
                return self._open(f"{self.base}/file/d/{urllib.parse.quote(name)}", timeout=timeout)
            except Exception:
                if i == tries - 1:
                    raise
                time.sleep(2 * (i + 1))

    def stores(self):
        names = sorted(n for n in self._list("Stores") if re.search(r"\d{8}", n))
        b = maybe_gunzip(self.get(names[-1]))
        if b[:2] in (b"\xff\xfe", b"\xfe\xff"):
            b = b.decode("utf-16").encode()
        root = ET.fromstring(b.decode("utf-8-sig").encode())
        res = {}
        for s in root.iter():
            if s.tag.lower() == "store":
                sid = text(s, "StoreID") or text(s, "StoreId")
                if sid.isdigit():
                    res[str(int(sid))] = {"n": text(s, "StoreName"), "c": city_name(text(s, "City")), "a": text(s, "Address")}
        return res

    def price_files(self):
        latest = {}
        for n in self._list("PriceFull"):
            m = re.search(r"PriceFull\d+-(\d+)-(\d+)-(\d{8}-?\d+)", n)
            if m:
                k = int(m.group(2))
                if k not in latest or m.group(3) > latest[k][0]:
                    latest[k] = (m.group(3), n)
        return [n for _, n in latest.values()]


CHAINS = [
    Shufersal(),
    PublishedPrices("ramilevy", "רמי לוי", "RamiLevi"),
    PublishedPrices("yochananof", "יוחננוף", "yohananof"),
    PublishedPrices("osherad", "אושר עד", "osherad"),
]


CATALOG = {}          # code -> [name, brand, amount, unit, stores, {chain: {agorot: count}}]
CATALOG_LOCK = threading.Lock()


def base_amount(qty, unit):
    """("500", "גרם") -> (500, "G"); kg/l -> x1000; units -> "U"."""
    try:
        q = float(qty)
    except ValueError:
        return 0, ""
    u = unit.replace('"', "").replace("'", "").replace("״", "").replace(" ", "")
    if q <= 0 or not u:
        return 0, ""
    if u.startswith("קג") or u.startswith("קילו") or u == "קג":
        return q * 1000, "G"
    if u.startswith("גר") or u == "ג":
        return q, "G"
    if u.startswith("מל") or u.startswith("מיליל") or u.startswith("מ\"ל"):
        return q, "M"
    if u.startswith("ליטר") or u == "ל":
        return q * 1000, "M"
    if u.startswith("יח"):
        return q, "U"
    return 0, ""


def add_catalog(local, chain):
    with CATALOG_LOCK:
        for code, row in local.items():
            row, price = row[:4], row[4]
            cur = CATALOG.get(code)
            if cur is None:
                cur = CATALOG[code] = row + [1, {}]
            else:
                cur[4] += 1
                if len(row[0]) > len(cur[0]):
                    cur[0] = row[0]
                if not cur[1] and row[1]:
                    cur[1] = row[1]
                if not cur[3] and row[3]:
                    cur[2], cur[3] = row[2], row[3]
            if price:
                h = cur[5].setdefault(chain, {})
                h[price] = h.get(price, 0) + 1


def write_catalog(path="catalog.tsv.gz"):
    clean = lambda v: str(v).replace("\t", " ").replace("\n", " ").strip()
    rows = sorted(CATALOG.items(), key=lambda kv: -kv[1][4])
    with gzip.open(path, "wt", encoding="utf-8") as f:
        for code, (name, brand, amount, unit, n, hist) in rows:
            amt = ("%g" % amount) if unit else ""
            # Most common shelf price per chain (not the min, which is often a one-store sale).
            pr = ",".join(f"{ch}:{max(h.items(), key=lambda kv: (kv[1], -kv[0]))[0]}" for ch, h in sorted(hist.items()))
            f.write(f"{code}\t{clean(short_name(name, amount if unit else 0))}\t{clean(brand)}\t{amt}\t{unit}\t{n}\t{pr}\n")
    print(f"catalog: {len(rows)} items -> {path}", flush=True)


def parse_prices(blob, watched, chain=""):
    """-> (storeId, {barcode: (agorot, name)}); also feeds CATALOG."""
    store, found, local = None, {}, {}
    for _, el in ET.iterparse(io.BytesIO(maybe_gunzip(blob)), events=("end",)):
        tag = el.tag
        if tag == "StoreID" or tag == "StoreId":
            store = str(int(el.text.strip()))
        elif tag in ("Item", "Product"):
            code = text(el, "ItemCode").lstrip("0")
            weighted = text(el, "bIsWeighted") == "1"
            if not weighted and len(code) >= 7 and code.isdigit() and code not in local:
                nm = text(el, "ItemName") or text(el, "ItemNm")
                if nm:
                    amount, unit = base_amount(text(el, "Quantity"), text(el, "UnitQty"))
                    try:
                        pr = round(float(text(el, "ItemPrice")) * 100)
                    except ValueError:
                        pr = 0
                    local[code] = [nm, text(el, "ManufacturerName"), amount, unit, pr if 0 < pr < 1_000_000 else 0]
            if code in watched and not weighted:
                try:
                    found[code] = (round(float(text(el, "ItemPrice")) * 100), text(el, "ItemName"))
                except ValueError:
                    pass
            el.clear()
    add_catalog(local, chain)
    return store, found


def fetch_chain(chain, watched):
    stores = fill_cities(chain.stores())
    files = chain.price_files()
    print(f"[{chain.key}] {len(stores)} stores, {len(files)} price files", flush=True)
    if not files:
        raise RuntimeError("no price files published today")
    prices, names = {}, {}

    def one(u):
        try:
            return parse_prices((chain.get if hasattr(chain, "get") else get)(u, timeout=120), watched, chain.key)
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

    if CATALOG:
        write_catalog()

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

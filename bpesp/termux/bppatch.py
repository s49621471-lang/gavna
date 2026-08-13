#!/usr/bin/env python3
"""
Patch a BLOCKPOST APK with the ESP overlay. Pure Python, no Java, no root.

    python bppatch.py "BLOCKPOST SERVER_1.00f4.apk"

Needs only `cryptography` for the signature:

    pkg install python
    pip install cryptography

The APK is a third of a gigabyte, so nothing is recompressed. Original local
entries are copied as one contiguous byte range and the new ones are appended;
entries that are dropped are simply left out of the rebuilt central directory,
which is the only index Android reads. That turns the job into a copy plus a
few kilobytes rather than a repack.
"""

import os, shutil, struct, sys, time, zlib

HERE = os.path.dirname(os.path.abspath(__file__))

OLD_APP = "androidx.multidex.MultiDexApplication"
NEW_APP = "com.skullcapstudios.esp.EspOverlayApp"

EOCD_SIG, CD_SIG, LFH_SIG = b"PK\x05\x06", b"PK\x01\x02", b"PK\x03\x04"
APK_SIG_MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
ALGO_RSA_SHA256 = 0x0103


# ---------------------------------------------------------------- zip reading
def find_eocd(buf):
    start = max(0, len(buf) - 66000)
    idx = buf.rfind(EOCD_SIG, start)
    if idx < 0:
        raise SystemExit("not a zip: no end-of-central-directory record")
    count = struct.unpack_from("<H", buf, idx + 10)[0]
    cd_size, cd_off = struct.unpack_from("<II", buf, idx + 12)
    return idx, count, cd_size, cd_off


def read_central(buf, cd_off, cd_size):
    """Yields one dict per central-directory entry, in order."""
    out, p, end = [], cd_off, cd_off + cd_size
    while p < end:
        if buf[p:p + 4] != CD_SIG:
            break
        (ver, vneed, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen, clen, disk, iattr, eattr, lho) = struct.unpack_from("<6H3I5H2I", buf, p + 4)
        name = buf[p + 46: p + 46 + nlen]
        extra = buf[p + 46 + nlen: p + 46 + nlen + elen]
        total = 46 + nlen + elen + clen
        out.append(dict(name=name, flags=flags, method=method, mtime=mtime, mdate=mdate,
                        crc=crc, csize=csize, usize=usize, extra=extra, lho=lho,
                        eattr=eattr, iattr=iattr, ver=ver, vneed=vneed))
        p += total
    return out


def local_header_len(buf, off):
    nlen, elen = struct.unpack_from("<HH", buf, off + 26)
    return 30 + nlen + elen


# ---------------------------------------------------------------- zip writing
def dos_time():
    t = time.localtime()
    return ((t.tm_hour << 11) | (t.tm_min << 5) | (t.tm_sec // 2),
            ((t.tm_year - 1980) << 9) | (t.tm_mon << 5) | t.tm_mday)


def make_entry(name, data, store=False):
    """Returns (local header + payload, central-directory fields)."""
    mtime, mdate = dos_time()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    if store:
        method, payload = 0, data
    else:
        method, payload = 8, zlib.compress(data, 6)[2:-4]   # raw deflate
    lfh = LFH_SIG + struct.pack("<5H3I2H", 20, 0, method, mtime, mdate,
                                crc, len(payload), len(data), len(name), 0) + name
    return lfh + payload, dict(name=name, flags=0, method=method, mtime=mtime, mdate=mdate,
                               crc=crc, csize=len(payload), usize=len(data), extra=b"",
                               eattr=0, iattr=0, ver=20, vneed=20)


def central_record(e, lho):
    return (CD_SIG + struct.pack("<6H3I5H2I", e["ver"], e["vneed"], e["flags"], e["method"],
                                 e["mtime"], e["mdate"], e["crc"], e["csize"], e["usize"],
                                 len(e["name"]), len(e["extra"]), 0, 0, e["iattr"],
                                 e["eattr"], lho)
            + e["name"] + e["extra"])


# ---------------------------------------------------------------- signing (v2)
def chunked_digest(sections):
    """APK Signature Scheme v2 digest over 1 MiB chunks of each section."""
    from hashlib import sha256
    CHUNK = 1024 * 1024
    digests, count = [], 0
    for sec in sections:
        for off in range(0, len(sec), CHUNK):
            piece = sec[off:off + CHUNK]
            h = sha256()
            h.update(b"\xa5" + struct.pack("<I", len(piece)) + piece)
            digests.append(h.digest())
            count += 1
    top = sha256()
    top.update(b"\x5a" + struct.pack("<I", count) + b"".join(digests))
    return top.digest()


def lp(b):                      # length-prefixed
    return struct.pack("<I", len(b)) + b


def sign_v2(body, cd, eocd, key, cert_der):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    # The EOCD is digested with its central-directory offset pointing at where
    # the signing block will begin, which is exactly where the body ends.
    eocd_fixed = bytearray(eocd)
    struct.pack_into("<I", eocd_fixed, 16, len(body))

    digest = chunked_digest([body, cd, bytes(eocd_fixed)])

    signed_data = (lp(lp(struct.pack("<I", ALGO_RSA_SHA256) + lp(digest)))
                   + lp(lp(cert_der))
                   + lp(b""))
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    pub = key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)

    signer = lp(signed_data) + lp(lp(struct.pack("<I", ALGO_RSA_SHA256) + lp(sig))) + lp(pub)
    v2 = lp(lp(signer))

    pair = struct.pack("<Q", 4 + len(v2)) + struct.pack("<I", V2_ID) + v2
    # Pad so the whole block lands on a 4096 boundary, as apksigner does.
    block_len = 8 + len(pair) + 8 + 16
    pad = (-(len(body) + block_len)) % 4096
    if pad:
        if pad < 12:
            pad += 4096
        pair += struct.pack("<Q", 4 + pad - 12) + struct.pack("<I", 0x42726577) + b"\0" * (pad - 12)
    size = len(pair) + 8 + 16
    return struct.pack("<Q", size) + pair + struct.pack("<Q", size) + APK_SIG_MAGIC


def load_key():
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PrivateFormat, NoEncryption, load_pem_private_key)
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes
    import datetime

    kp, cp = os.path.join(HERE, "esp.key.pem"), os.path.join(HERE, "esp.cert.der")
    if os.path.exists(kp) and os.path.exists(cp):
        with open(kp, "rb") as f:
            key = load_pem_private_key(f.read(), None)
        with open(cp, "rb") as f:
            return key, f.read()

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "bpesp")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365 * 30))
            .sign(key, hashes.SHA256()))
    der = cert.public_bytes(Encoding.DER)
    with open(kp, "wb") as f:
        f.write(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    with open(cp, "wb") as f:
        f.write(der)
    print("    generated a signing key in", HERE)
    return key, der


# ---------------------------------------------------------------- main
def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__.strip())
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(src)[0] + "-esp.apk"

    so = os.path.join(HERE, "libesp.so")
    dex = os.path.join(HERE, "payload.dex")
    for f in (src, so, dex):
        if not os.path.exists(f):
            raise SystemExit("missing: " + f)

    print("[1/5] reading")
    with open(src, "rb") as f:
        buf = f.read()
    eocd_off, _, cd_size, cd_off = find_eocd(buf)
    entries = read_central(buf, cd_off, cd_size)
    print("      %d entries, %.0f MB" % (len(entries), len(buf) / 1e6))

    names = {e["name"] for e in entries}
    slot = 2
    while ("classes%d.dex" % slot).encode() in names:
        slot += 1
    dex_name = ("classes%d.dex" % slot).encode()
    print("[2/5] payload dex -> %s" % dex_name.decode())

    # AndroidManifest: same-length class name, so the binary XML string pool is
    # untouched apart from those bytes.
    print("[3/5] manifest")
    man = None
    for e in entries:
        if e["name"] == b"AndroidManifest.xml":
            h = local_header_len(buf, e["lho"])
            raw = buf[e["lho"] + h: e["lho"] + h + e["csize"]]
            data = raw if e["method"] == 0 else zlib.decompress(raw, -15)
            a, b = OLD_APP.encode("utf-16-le"), NEW_APP.encode("utf-16-le")
            if data.count(b):
                print("      already patched")
            elif data.count(a) == 1:
                data = data.replace(a, b)
                print("      application class ->", NEW_APP)
            else:
                raise SystemExit("expected one %r in the manifest, found %d"
                                 % (OLD_APP, data.count(a)))
            man = data
            break
    if man is None:
        raise SystemExit("no AndroidManifest.xml")

    print("[4/5] repacking")
    body = bytearray(buf[:cd_off])          # every original local entry, verbatim
    new_cd, added = [], []

    def append(name, data, store=False):
        lfh, meta = make_entry(name, data, store)
        added.append((meta, len(body)))
        body.extend(lfh)

    append(b"AndroidManifest.xml", man)
    with open(dex, "rb") as f:
        append(dex_name, f.read())
    with open(so, "rb") as f:
        append(b"lib/arm64-v8a/libesp.so", f.read())

    drop = (b"META-INF/MANIFEST.MF",)
    for e in entries:
        n = e["name"]
        if n in drop or n.startswith(b"META-INF/") and n.endswith((b".SF", b".RSA", b".DSA")):
            continue                         # left in place, just unindexed
        if n == b"AndroidManifest.xml":
            continue                         # superseded by the appended copy
        new_cd.append(central_record(e, e["lho"]))
    for meta, off in added:
        new_cd.append(central_record(meta, off))

    cd = b"".join(new_cd)
    eocd = (EOCD_SIG + struct.pack("<4H2IH", 0, 0, len(new_cd), len(new_cd),
                                   len(cd), len(body), 0))

    print("[5/5] signing")
    key, cert = load_key()
    block = sign_v2(bytes(body), cd, eocd, key, cert)
    eocd = bytearray(eocd)
    struct.pack_into("<I", eocd, 16, len(body) + len(block))

    tmp = dst + ".part"
    with open(tmp, "wb") as f:
        f.write(body)
        f.write(block)
        f.write(cd)
        f.write(eocd)
    shutil.move(tmp, dst)
    print("done -> %s  (%.0f MB)" % (dst, os.path.getsize(dst) / 1e6))


if __name__ == "__main__":
    main()

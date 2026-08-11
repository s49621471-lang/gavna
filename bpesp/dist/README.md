# dist — split APK

`BLOCKPOST-ESP.apk` is 307 MB, past GitHub's 100 MB per-file limit, so it is
stored here as seven `.partNN` chunks. Concatenating them in order reproduces
the signed APK byte for byte.

    md5  ec8cb4c8b8793783c5a18f73c0cf7cf1  BLOCKPOST-ESP.apk

## Termux — fetch and rejoin

Downloads the parts straight from raw.githubusercontent.com, no clone:

```sh
pkg install -y curl coreutils
B=https://raw.githubusercontent.com/s49621471-lang/gavna/claude/chat-session-wsivnr/bpesp/dist
for i in 00 01 02 03 04 05 06; do
  curl -fL# -o BLOCKPOST-ESP.apk.part$i $B/BLOCKPOST-ESP.apk.part$i
done
cat BLOCKPOST-ESP.apk.part?? > BLOCKPOST-ESP.apk && rm BLOCKPOST-ESP.apk.part??
md5sum BLOCKPOST-ESP.apk     # ec8cb4c8b8793783c5a18f73c0cf7cf1
```

Then install it:

```sh
termux-open BLOCKPOST-ESP.apk
```

Uninstall the store build first — the signing key is different, so it will not
upgrade in place.

## Already cloned the repo

```sh
cd bpesp/dist
cat BLOCKPOST-ESP.apk.part?? > BLOCKPOST-ESP.apk
sha256sum -c SHA256SUMS      # verifies the parts
md5sum BLOCKPOST-ESP.apk     # verifies the join
```

`cat ...part??` is deliberate: `part*` would also sweep up `SHA256SUMS` on some
shells if the glob is widened, and `??` pins the two-digit ordering that
`split -d -a 2` produced.

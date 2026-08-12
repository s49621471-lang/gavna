# dist — split APK

`BLOCKPOST-ESP.apk` is 344 MB (game 1.00f4), past GitHub's 100 MB per-file
limit, so it is stored here as eight `.partNN` chunks. Concatenating them in
order reproduces the signed APK byte for byte.

    md5  66f90cb975eee31dcb69a1defdbf9b03  BLOCKPOST-ESP.apk

## Termux — fetch and rejoin

Downloads the parts straight from raw.githubusercontent.com, no clone:

```sh
pkg install -y curl coreutils
B=https://raw.githubusercontent.com/s49621471-lang/gavna/claude/chat-session-wsivnr/bpesp/dist
for i in 00 01 02 03 04 05 06 07; do
  curl -fL# -o BLOCKPOST-ESP.apk.part$i $B/BLOCKPOST-ESP.apk.part$i
done
cat BLOCKPOST-ESP.apk.part?? > BLOCKPOST-ESP.apk && rm BLOCKPOST-ESP.apk.part??
md5sum BLOCKPOST-ESP.apk     # 66f90cb975eee31dcb69a1defdbf9b03
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

## Diagnostic log

The build writes a full discovery trace to

    /sdcard/Android/data/com.skullcapstudios.bps/files/bpesp.log

No permission and no root needed. Launch the game, join a match, wait ~15s,
then pull it:

```sh
cp /sdcard/Android/data/com.skullcapstudios.bps/files/bpesp.log .
```

It records which assemblies were reachable, every plausible entity class with
its field composition, a complete field dump (name, offset, type) of the one it
picked, the container search, camera resolution, and a per-3-second frame trace
with raw values for the first entities and a breakdown of why any were skipped.

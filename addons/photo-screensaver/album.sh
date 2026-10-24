#!/usr/bin/env bash
# Point the photo screensaver at a shared Google Photos album: from then on the TV fetches the day's pictures
# itself (taken on today's date in earlier years, up to 30), so ADB can be closed again. Also makes Photos the
# TV's screensaver, remembering the previous one for sync.sh --off. Install the app first with build-and-install.sh.
#
# Usage: TV_HOST=192.168.1.50 addons/photo-screensaver/album.sh https://photos.app.goo.gl/XXXX
#        TV_HOST=192.168.1.50 addons/photo-screensaver/album.sh --off     # stop, album pictures removed
set -euo pipefail
cd "$(dirname "$0")/../../tools"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 <album link>}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=com.gathin.photodream
URI=content://$PKG.files
TMP=/data/local/tmp/cyt-album.txt
BEFORE=/data/local/tmp/cyt-screensaver-before
tv() { "$PY" -I tv.py "$@"; }
LINK="${1:?give the share link of the album, e.g. https://photos.app.goo.gl/XXXX}"

tv "pm path $PKG; true" | grep -q package: \
  || { echo "the photo screensaver is not on the TV: run addons/photo-screensaver/build-and-install.sh first" >&2; exit 1; }

if [ "$LINK" = --off ]; then
  tv "content delete --uri $URI/album.txt; for f in \$(ls /sdcard/Android/data/$PKG/files/pictures 2>/dev/null | grep '^album-'); do content delete --uri $URI/pictures/\$f; done; cmd jobscheduler cancel $PKG 1" >/dev/null
  echo "Album off; its pictures are removed."
  exit 0
fi

SHARE_LINK='^https://(photos[.]app[.]goo[.]gl|photos[.]google[.]com)/[A-Za-z0-9_/?=.-]+$'
[[ "$LINK" =~ $SHARE_LINK ]] \
  || { echo "not a Google Photos share link: $LINK" >&2; exit 1; }
tv "printf '%s' '$LINK' > $TMP; content write --uri $URI/album.txt < $TMP; rm -f $TMP" >/dev/null
tv "[ -f $BEFORE ] || { settings get secure screensaver_components; settings get secure screensaver_enabled; } > $BEFORE; settings put secure screensaver_components $PKG/.PhotoDream; settings put secure screensaver_enabled 1" >/dev/null
# Opening the slideshow once starts the first fetch and schedules the daily one.
tv "am start -n $PKG/.SlideshowActivity >/dev/null" >/dev/null
echo "Album set. The first pictures arrive within a minute; Back leaves the slideshow."

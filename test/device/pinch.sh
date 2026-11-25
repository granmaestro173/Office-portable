#!/system/bin/sh
# Two-finger pinch on the device touchscreen with raw multi-touch events.
#   pinch.sh <cx> <cy> <start_gap> <end_gap> [steps]     (screen pixels)
# end_gap > start_gap zooms in.
CX=$1; CY=$2; G0=$3; G1=$4; STEPS=${5:-12}
# The emulator has one multi-touch device per possible display; use the one
# for the main display ("..._multi_touch_1" or the first one listed by name).
LIST=$(getevent -pl 2>/dev/null | awk '/add device/{d=$4} /name:/{n=$2} /ABS_MT_POSITION_X/{print d, n}')
echo "$LIST" | sed 's/^/  touch device: /'
DEV=$(echo "$LIST" | awk '$2 ~ /multi_touch_1"$/ || $2 ~ /multi_touch"$/ {print $1; exit}')
[ -z "$DEV" ] && DEV=$(echo "$LIST" | head -1 | awk '{print $1}')
[ -z "$DEV" ] && { echo "no multi-touch device"; exit 1; }
MAXX=$(getevent -pl "$DEV" | grep ABS_MT_POSITION_X | sed 's/.*max \([0-9]*\).*/\1/')
MAXY=$(getevent -pl "$DEV" | grep ABS_MT_POSITION_Y | sed 's/.*max \([0-9]*\).*/\1/')
CAPS=$(getevent -pl "$DEV")
has() { echo "$CAPS" | grep -q "$1"; }
SIZE=$(wm size | tail -1 | sed 's/.*: //')
W=${SIZE%x*}; H=${SIZE#*x}
tx() { echo $(( $1 * (MAXX + 1) / W )); }
ty() { echo $(( $1 * (MAXY + 1) / H )); }
e() { sendevent "$DEV" "$@"; }
# Contact details: with a pressure axis, Android treats pressure 0 as hovering.
touch_extra() {
  has ABS_MT_PRESSURE && e 3 58 80
  has ABS_MT_TOUCH_MAJOR && e 3 48 8
  return 0
}
Y=$(ty "$CY")
half=$((G0 / 2))
if [ "$G0" = 0 ]; then
  # One finger: a vertical swipe of G1 pixels upward (control for raw input).
  e 3 47 0; e 3 57 700; e 3 53 $(tx "$CX"); e 3 54 "$Y"; touch_extra
  e 1 330 1; e 0 0 0
  i=1
  while [ $i -le "$STEPS" ]; do
    e 3 47 0; e 3 54 $(ty $((CY - G1 * i / STEPS))); e 0 0 0
    i=$((i + 1))
  done
  e 3 47 0; e 3 57 -1; e 1 330 0; e 0 0 0
  echo "swipe on $DEV"
  exit 0
fi
e 3 47 0; e 3 57 701; e 3 53 $(tx $((CX - half))); e 3 54 "$Y"; touch_extra
e 3 47 1; e 3 57 702; e 3 53 $(tx $((CX + half))); e 3 54 "$Y"; touch_extra
e 1 330 1; e 0 0 0
i=1
while [ $i -le "$STEPS" ]; do
  g=$((G0 + (G1 - G0) * i / STEPS)); half=$((g / 2))
  e 3 47 0; e 3 53 $(tx $((CX - half)))
  e 3 47 1; e 3 53 $(tx $((CX + half)))
  e 0 0 0
  i=$((i + 1))
done
e 3 47 0; e 3 57 -1; e 3 47 1; e 3 57 -1; e 1 330 0; e 0 0 0
echo "pinch on $DEV ($W x $H, touch $MAXX x $MAXY)"
echo "$CAPS" | grep -E "ABS_MT|BTN_TOUCH|INPUT_PROP" | sed "s/^/  cap: /"

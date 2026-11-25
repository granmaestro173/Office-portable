#!/system/bin/sh
# Two-finger pinch on the device touchscreen with raw multi-touch events.
#   pinch.sh <cx> <cy> <start_gap> <end_gap> [steps]     (screen pixels)
# end_gap > start_gap zooms in.
CX=$1; CY=$2; G0=$3; G1=$4; STEPS=${5:-12}
DEV=$(getevent -pl 2>/dev/null | awk '/add device/{d=$4} /ABS_MT_POSITION_X/{print d; exit}')
[ -z "$DEV" ] && { echo "no multi-touch device"; exit 1; }
MAXX=$(getevent -pl "$DEV" | grep ABS_MT_POSITION_X | sed 's/.*max \([0-9]*\).*/\1/')
MAXY=$(getevent -pl "$DEV" | grep ABS_MT_POSITION_Y | sed 's/.*max \([0-9]*\).*/\1/')
SIZE=$(wm size | tail -1 | sed 's/.*: //')
W=${SIZE%x*}; H=${SIZE#*x}
tx() { echo $(( $1 * (MAXX + 1) / W )); }
ty() { echo $(( $1 * (MAXY + 1) / H )); }
e() { sendevent "$DEV" "$@"; }
Y=$(ty "$CY")
half=$((G0 / 2))
e 3 47 0; e 3 57 701; e 3 53 $(tx $((CX - half))); e 3 54 "$Y"
e 3 47 1; e 3 57 702; e 3 53 $(tx $((CX + half))); e 3 54 "$Y"
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

#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK, walks through the main screens
# and saves screenshots to ./screens. Usage: test/device/ui-check.sh <apk>
set -u
APK=$1
PKG=com.granmaestro.officeportable
OUT=screens
mkdir -p "$OUT"
D=/sdcard/Android/data/$PKG/files

shot() { sleep "${2:-0}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
view() { # file mime activity
  adb shell am start -W -a android.intent.action.VIEW -t "$2" -d "file://$D/$1" -n "$PKG/$3" >/dev/null
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }

adb shell dumpsys webviewupdate | grep -i "current webview package" | tee "$OUT/webview.txt"
adb install -r -g "$APK" || exit 1
adb shell cmd uimode night no
adb shell wm size | tee -a "$OUT/webview.txt"

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
shot 01-home-empty 6

adb shell mkdir -p "$D"
adb push test/fixtures/. "$D/" >/dev/null
adb push test/device/pinch.sh /data/local/tmp/pinch.sh >/dev/null
adb shell chmod 755 /data/local/tmp/pinch.sh

view report.docx application/vnd.openxmlformats-officedocument.wordprocessingml.document .EditorActivity
shot 02-docx 75
back

view budget.xlsx application/vnd.openxmlformats-officedocument.spreadsheetml.sheet .EditorActivity
shot 03-xlsx 60
back

PPTX=application/vnd.openxmlformats-officedocument.presentationml.presentation
view deck.pptx $PPTX .EditorActivity
shot 04-pptx 75
SIZE=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); W=${SIZE%x*}; H=${SIZE#*x}
CX=$((W / 2)); CY=$((H * 56 / 100))
# One-finger swipe up: next slide.
adb shell input swipe $CX $((H * 70 / 100)) $CX $((H * 45 / 100)) 300
shot 05-pptx-swiped 4
# Back to slide 1, select the title box, then pinch on it (used to rotate it).
adb shell input swipe $CX $((H * 45 / 100)) $CX $((H * 70 / 100)) 300
sleep 3
adb shell input tap $CX $CY
shot 06-pptx-selected 3
adb shell /data/local/tmp/pinch.sh $CX $CY 200 700 14
shot 07-pptx-pinched 4
# Two-finger pinch back out.
adb shell /data/local/tmp/pinch.sh $CX $CY 700 250 14
shot 08-pptx-pinched-out 4
back

view guide.pdf application/pdf .PdfViewerActivity
shot 09-pdf 10
back

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
shot 10-home-recent 4
adb shell cmd uimode night yes
sleep 3
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
shot 11-home-dark 4
view report.docx application/vnd.openxmlformats-officedocument.wordprocessingml.document .EditorActivity
shot 12-docx-dark 60
back
adb shell cmd uimode night no

adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell input swipe $CX $((H * 85 / 100)) $CX $((H * 25 / 100)) 300
shot 13-launcher 4

adb logcat -d > "$OUT/logcat.txt"
grep -iE "AndroidRuntime|FATAL|officeportable|chromium.*(Uncaught|Error)" "$OUT/logcat.txt" | tail -200 > "$OUT/logcat-errors.txt" || true
ls -la "$OUT"

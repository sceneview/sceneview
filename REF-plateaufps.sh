# usage: plateaufps.sh label [apk]  — Create's Plateau on the 4a: ArmvWorld log + SurfaceFlinger on the stage layer
export ANDROID_SERIAL=09291JEC223501
P=com.gorisse.thomas.arcamera.dev; S=$(dirname "$0")
L1=$1; [ -n "$2" ] && [ "$2" != "-" ] && adb install -r "$2" | tail -1; shift; shift
adb shell am force-stop $P; adb logcat -c
adb shell am start -n $P/com.gorisse.thomas.armv.MainActivity "$@" >/dev/null
python3 -c "import time; time.sleep(25)"
for k in 1 2 3; do
  L=$(adb shell dumpsys SurfaceFlinger --list | grep -i "SurfaceView" | grep "$P" | grep -i blast | tail -1)
  adb shell "dumpsys SurfaceFlinger --latency '$L'" > $S/lat-$L1-$k.txt
  python3 -I - $S/lat-$L1-$k.txt <<'PY'
import sys
rows=[l.split() for l in open(sys.argv[1]).read().strip().splitlines()[1:]]
t=sorted(int(r[1]) for r in rows if len(r)==3 and 0<int(r[1])<9e18)
print(f" SF {(len(t)-1)/((t[-1]-t[0])/1e9):.1f} fps over {len(t)} frames" if len(t)>2 else " SF none")
PY
  python3 -c "import time; time.sleep(4)"
done
adb logcat -d -s ArmvWorld | grep "fps=" | tail -3
adb exec-out screencap -p > $S/plateau-$L1.png
echo "$L1 done; $(adb shell dumpsys thermalservice | grep 'Thermal Status' | head -1)"

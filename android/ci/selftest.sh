#!/usr/bin/env bash
# Автотест в эмуляторе: установить APK, положить модель, запустить самопроверку, собрать логи и скриншоты.
set -u
PKG=com.tegg54751.offlinestudyai
ACT=com.offlinestudy.ai.MainActivity
APK=app/build/outputs/apk/release/app-release.apk
OUT=selftest-out
EXT=/sdcard/Android/data/$PKG/files
mkdir -p $OUT

adb install -r -g $APK || { echo "install failed"; exit 1; }
adb logcat -c
# Первый запуск создаёт папку приложения
adb shell am start -W -n $PKG/$ACT
sleep 8
adb exec-out screencap -p > $OUT/01_first_launch.png
# Прокручиваем главный экран вниз — проверить нижние плашки
WH=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${WH%x*}; H=${WH#*x}
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/5)) 600; sleep 2
adb exec-out screencap -p > $OUT/02_home_scrolled.png
adb shell am force-stop $PKG

adb shell mkdir -p $EXT
echo "Pushing model..."
adb push model.gguf $EXT/model.gguf
adb shell ls -l $EXT

adb shell am start -W -n $PKG/$ACT --ez selftest true
for i in $(seq 1 300); do
  sleep 10
  if [ $((i % 6)) -eq 0 ]; then adb exec-out screencap -p > $OUT/shot_$(printf %03d $i).png; fi
  if adb shell "[ -f $EXT/selftest_done ] && echo yes" | grep -q yes; then echo "selftest done after $((i*10))s"; break; fi
  if ! adb shell pidof $PKG > /dev/null; then echo "APP NOT RUNNING (crash?)"; break; fi
done
sleep 2
adb exec-out screencap -p > $OUT/99_final.png
adb pull $EXT/selftest.json $OUT/selftest.json || echo '{"error":"no selftest.json"}' > $OUT/selftest.json
adb logcat -d > $OUT/logcat-full.txt
grep -E "IndexSelfTest|AndroidRuntime|FATAL|llama|OfflineStudyAI|DEBUG|libc" $OUT/logcat-full.txt > $OUT/logcat.txt || true
ls -la $OUT
exit 0

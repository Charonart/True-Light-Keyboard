@echo off
:: 1. Biên dịch APK Debug
call gradlew assembleDebug
:: 2. Cài đặt đè trực tiếp lên máy
adb install -r app\build\outputs\apk\debug\app-debug.apk
:: 3. Kích hoạt và đặt True Light làm bàn phím mặc định ngay lập tức
adb shell ime enable com.truelight.ime/.TrueLightIME
adb shell ime set com.truelight.ime/.TrueLightIME
echo === READY TO TEST ===
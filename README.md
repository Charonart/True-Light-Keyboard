# True Light - Bộ Gõ Bàn Phím Android Siêu Nhẹ (Ultra-Lightweight IME)

## 1. Giới thiệu tổng quan
True Light là bộ gõ bàn phím tiếng Việt và ký tự siêu tối ưu cho hệ điều hành Android, được thiết kế theo triết lý tối giản tài nguyên:
- **Dung lượng APK**: < 1.5 MB.
- **RAM hoạt động**: < 20 MB trong mọi kịch bản.
- **Độ trễ phản hồi**: < 15ms (Zero garbage collection latency).
- **100% Kotlin/Java thuần**: Không sử dụng AndroidX, không EmojiCompat, không thư viện ngoài.
- **Bảo mật tuyệt đối**: 0 quyền Internet (`android.permission.INTERNET` hoàn toàn không tồn tại trong `AndroidManifest.xml`).

---

## 2. Kiến trúc kỹ thuật cốt lõi

### 2.1. Đồ họa Canvas duy nhất (Single Canvas View - Zero-Allocation onDraw)
- Toàn bộ giao diện bàn phím được vẽ trực tiếp trên một `View` duy nhất (`KeyboardCanvasView`).
- **Zero Allocation**: Tất cả `Paint`, `RectF`, `Path`, font metrics đều được khởi tạo và cấu hình tĩnh trước (`KeyboardTheme`).
- Không phát sinh đối tượng mới trong hàm `onDraw(Canvas)` -> Không kích hoạt Garbage Collector (GC), triệt tiêu giật lag khung hình.

### 2.2. Xử lý InputConnection bất đồng bộ (InputConnectionBroker)
Giải quyết triệt để 4 vấn đề kinh điển của bàn phím Android:
1. **Thanh địa chỉ Chrome & Trình duyệt Web**:
   - Mọi thao tác đều được bọc trong cặp `ic.beginBatchEdit()` và `ic.endBatchEdit()`.
   - Phím Space: Luôn gọi `ic.finishComposingText()` chốt từ trước khi gọi `ic.commitText( , 1)`. Điều này ngăn chặn hoàn toàn lỗi lặp ký tự (ví dụ `tim` thành `ttimm`) hoặc nuốt mất âm cuối khi gõ nhanh trên Chromium.
2. **Game & Nền tảng lai (Flutter, React Native, Unity)**:
   - Khi `EditorInfo.inputType == EditorInfo.TYPE_NULL` (0) hoặc `setComposingText` trả về false: Bàn phím tự động chuyển sang chế độ **Direct Key Dispatch**, gửi `KeyEvent` trực tiếp (`KEYCODE_...`) hoặc `commitText` không qua composing region.
   - Tuyệt đối không can thiệp lệch offset composing region để tránh hiện tượng con trỏ nhảy về đầu dòng.
3. **App Chat (Zalo, Messenger, Telegram) - Xử lý phím Enter**:
   - Phân tích `EditorInfo.imeOptions`: Trích xuất `actionId` (`IME_ACTION_SEND`, `IME_ACTION_SEARCH`, `IME_ACTION_GO`, `IME_ACTION_DONE`, `IME_ACTION_NEXT`).
   - Kiểm tra `TYPE_TEXT_FLAG_MULTI_LINE` và `IME_FLAG_NO_ENTER_ACTION`. Nếu là ô nhập nhiều dòng không có action cụ thể: Gửi ký tự xuống dòng `\n`.
   - Hỗ trợ tổ hợp **Shift + Enter**: Luôn luôn là xuống dòng (`\n`), phục vụ nhu cầu soạn tin nhắn nhiều dòng trên Zalo, Facebook Messenger và Telegram.
4. **Bàn phím số Numpad to rõ**:
   - Tự động nhận diện trường số qua `TYPE_CLASS_NUMBER`, `TYPE_CLASS_PHONE`, `TYPE_CLASS_DATETIME` và chuyển sang layout Numpad với phím bấm to, rõ ràng.

### 2.3. Cỗ máy trạng thái hữu hạn Telex (TelexFsmEngine)
- Sử dụng mảng ký tự tĩnh `CharArray(64)` và ma trận nguyên âm `VietnameseCharMap`.
- Đặt dấu thanh chuẩn quy cách mới: `hòa`, `hòe`, `thúy`, `chuối`, `đường`.
- Xử lý hoàn tác / thoát dấu: Gõ lại phím dấu để thoát dấu (`toas` -> `toá`, gõ tiếp `s` -> `toas`).
- Phím `z` xóa dấu tức thì (`báo` + `z` -> `bao`).

### 2.4. Native Emoji Canvas Panel
- Sử dụng bảng mã tĩnh Unicode 15.0 phân bổ theo 9 danh mục chuẩn: Biểu cảm, Con người, Động vật, Ẩm thực, Du lịch, Hoạt động, Đồ vật, Biểu tượng, Cờ.
- Render trực tiếp bằng font hệ thống `/system/fonts/NotoColorEmoji.ttf`.
- Quản lý 30 emoji gần đây (`EmojiHistoryManager`) lưu qua SharedPreferences.
- Vẽ trực tiếp trên cùng Canvas View, hỗ trợ cuộn mượt không cấp phát bộ nhớ.

---

## 3. Cấu trúc thư mục dự án
```
D:\1._Project\True_Light\
├── build.gradle
├── settings.gradle
├── gradle.properties
├── README.md
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/
        ├── test/java/com/truelight/ime/engine/
        │   └── TelexFsmEngineTest.kt
        └── main/
            ├── AndroidManifest.xml
            ├── res/
            │   ├── drawable/
            │   │   └── ic_launcher.xml
            │   ├── values/
            │   │   ├── colors.xml
            │   │   ├── strings.xml
            │   │   └── styles.xml
            │   └── xml/
            │       └── method.xml
            └── java/com/truelight/ime/
                ├── TrueLightIME.kt
                ├── bridge/
                │   └── InputConnectionBroker.kt
                ├── engine/
                │   ├── TelexFsmEngine.kt
                │   └── VietnameseCharMap.kt
                ├── emoji/
                │   ├── EmojiCanvasPanel.kt
                │   ├── EmojiData.kt
                │   └── EmojiHistoryManager.kt
                └── ui/
                    ├── KeyModel.kt
                    ├── KeyboardCanvasView.kt
                    ├── KeyboardLayout.kt
                    └── theme/
                        └── KeyboardTheme.kt
```

---

## 4. Hướng dẫn đóng gói và triển khai
Dự án có thể build trực tiếp bằng Android Studio hoặc dòng lệnh:
```bash
./gradlew assembleRelease
```
File APK xuất ra tại: `app/build/outputs/apk/release/app-release.apk` với dung lượng dự kiến < 1.0 MB.

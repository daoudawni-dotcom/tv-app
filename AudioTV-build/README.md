# مكتبتي الصوتية — نموذج أولي 0.1
افتح مجلد AudioTV في Android Studio، باستخدام JDK 17 وAndroid SDK 35 وGradle 8.9 (من إعدادات Gradle أو تثبيت محلي). اسم الحزمة com.audiotv.player. اختر Build > Build APK(s). المشروع لا يتضمن Gradle wrapper أو APK مبنياً.

اختبار الشاشة: ثبّت APK، افتح التطبيق، اختر إضافة ملفات MP3 من USB أو التخزين، اختر ملفاً بالريموت، جرّب التشغيل والتوقف والتالي. جرّب زر أمر صوتي ثم زر المايك الفعلي. أمثلة: شغل [جزء من اسم الملف]، التالي، توقف، كمل، قدم خمس دقائق.

التعرف الصوتي يستخدم خدمة النظام وقد يتطلب الإنترنت. زر المايك قد يكون محجوزاً للنظام. منتقي الملفات قد لا يكون مثبتاً في بعض الشاشات المعدلة. لا يدعم هذا النموذج التشغيل المضمون في الخلفية أو فهرسة المجلدات آلياً. حفظ موضع كل ملف عند التوقف أو الانتقال؛ لا توجد خدمة تشغيل مستمرة. لم يُبنَ أو يُختبر على جهاز فعلي في بيئة الإنشاء.

## 0.2 — Mic diagnostics
Open فحص المايك. Test system/direct/on-device speech and five raw audio sources, one at a time. Raw capture shows peak signal and routed input; it does not transcribe or store recordings. Select a working speech mode and press اعتماد الوضع to use it for voice commands. Key test captures only events delivered to the activity; Back exits. System-reserved keys may not be visible. Local speech requires Android 12+ and installed compatible recognition service/language. No APK or device testing performed.

## Build APK
Windows: install Android Studio, install Android SDK Platform 35 through SDK Manager, accept SDK licenses, then run BUILD-APK.bat. Internet is required for the first build. The resulting file is AudioTV-v02-test.apk. Alternatively open the project in Android Studio and use Build APK(s).
GitHub: push the contents of this folder (including .github) into a repository and run Actions > Build Android APK. Download the AudioTV-debug artifact after a successful build.
This package has not been compiled or device-tested in the current environment.

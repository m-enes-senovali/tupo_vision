# Tupo Vision TR

**Ray-Ban Meta akıllı gözlükler için erişilebilir, Türkçe odaklı ve çevrimdışı destekli açık kaynak Android yapay zeka yardımcısı.**

Bu depo, [KeonyRus/tupo_vision](https://github.com/KeonyRus/tupo_vision) projesinin MIT lisanslı fork'udur. Resmi Meta AI uygulamasını telefondan kaldırmadan yan yana çalışır; gözlük kamerasını kullanarak doğrudan ChatGPT aboneliğinizle (OAuth PKCE) veya Gemini Live WebSocket API ile konuşmanıza, internetsiz ortamlarda ise gömülü Google ML Kit ile yerel metin (OCR) ve barkod/QR okuma yapmanıza olanak tanır.

Bu fork'un temel odak noktaları; **Jieshuo ve TalkBack ekran okuyucu uyumu**, **48 kHz A2DP ses kalitesinin korunması**, **düşük gecikme** ve **görme engelli kullanıcılar için günlük yaşam profilleridir**.

> **Durum ve Test Kapsamı:** Orijinal taban Ray-Ban Meta Gen 1 üzerinde geliştirilmiştir. Bu fork, **Ray-Ban Meta Gen 3** ve **Redmi Note 9 Pro (Android 12 / MIUI)** hedef alınarak genişletilmiştir. `46` JVM / MockWebServer / Robolectric / Compose erişilebilirlik testi ve `Android 12 (API 31)` emülatöründe `4` enstrümantasyon testi (`Espresso AccessibilityChecks`, gömülü ML Kit OCR ve `MockDeviceKit` sanal gözlük akışı dahil) doğrulanmıştır. Fiziksel gözlük üzerinde canlı Bluetooth A2DP/SCO ses kabul testleri devam etmektedir. Meta, OpenAI veya Google ile resmi bir bağı yoktur.

---

## Öne Çıkan Yenilikler (Fork Katkıları)

- **Çift Ses Profili (HD A2DP vs. Gözlük Mikrofonu SCO):**
  - Varsayılan `HD_A2DP` profilinde telefon `AudioManager.MODE_NORMAL` durumunda kalır; Gemini Live, ChatGPT TTS ve Jieshuo/TalkBack sesi 48 kHz A2DP medya kalitesinde akmaya devam eder (`VOICE_RECOGNITION` + `350 ms` `echoTailGuardMs` yankı koruması ile).
  - Telefon çantadayken kullanım için `GLASSES_SCO` profili korunmuştur; BT mikrofonu bulunamazsa ses bozulmadan güvenli biçimde `HD_A2DP` moduna geri düşer.
- **Ekran Okuyucu Dostu Sessiz Oturum ve PCM Earconlar:**
  - Aktif görüşme sırasında faz değişikliklerinin (`Dinliyor`, `Düşünüyor`, `Konuşuyor`) ekran okuyucu tarafından otomatik okunup asistanın sesini bastırması engellenir (`quietSessionAccessibility`); kullanıcı isterse alana dokunup durumu yine okuyabilir.
  - Sert `ToneGenerator` bip sesleri yerine serileştirilmiş, sıfir DC sapmalı kosinüs zarflı PCM earconlar (`180 ms` A2DP uyanış tamponlu) kullanılır.
  - Tüm arayüzde sıfır emoji, semantik `heading()`, `Role.RadioButton` / `Role.Switch`, `mergeDescendants` ve `stateDescription` uygulanmıştır.
- **Uygulamaya Gömülü Çevrimdışı ML Kit (OCR + Barkod/QR) ve Emniyet Ağı:**
  - `com.google.mlkit:text-recognition:16.0.1` ve `com.google.mlkit:barcode-scanning:17.3.0` modelleri APK içine gömülüdür; internet gerekmeden çalışır.
  - Çok sütunlu fiş ve kutularda satırların karışmaması için koordinat bazlı blok sıralaması (`orderOcrBlocks`) ve harici ağ çağrısı yapmayan Türkçe barkod/QR seslendirmesi içerir.
  - ChatGPT modunda ağ bağlantısı koptuğunda (`IOException`) otomatik olarak yerel ML Kit + TTS okumasına düşen emniyet ağına sahiptir.
- **4 Bağlamsal Uzman Profil ve Yerel Komutlar:**
  - `Hızlı Tarama`, `Belge, Fiş ve İlaç`, `Market ve Etiket`, `Mekansal Yürüyüş ve Engel` profilleri ana ekrandan, ChatGPT yerel sesli komutlarıyla (`"Belge moduna geç"`, `"Çevrimdışı oku"`, `"Tekrar oku"`) veya Gemini Live `Function Calling` araçlarıyla (`switch_profile`, `scan_local`, `capture_hd_photo`, `end_conversation`) yönetilebilir.
- **Ağ ve Gecikme İyileştirmeleri:**
  - `Responses.kt` HTTP/2 bağlantı havuzlu `OkHttp 4.12.0` SSE istemcisine taşınmıştır; `ChatSpeedProfile.FAST` varsayılan yapılmıştır.
  - `GeminiLive.kt` kurulumunda seçilebilir ses (`Kore`, `Zephyr`, `Orus`, `Aoede`), `VadSensitivity` (`FAST`, `BALANCED`, `PATIENT`), `thinkingBudget` ve 6 kademeli geri düşüş merdiveni bulunur.
- **Sanal Gözlük Debug Paneli (`MockDeviceKit`):**
  - Yalnızca `debug` derlemesinde bulunan `MockBridge.kt`, fiziksel gözlük olmadan örnek Türkçe ilaç etiketi fotoğrafı, arka kamera bağlama ve capture tuşu simülasyonu sunar.

---

## Gereksinimler

- **Meta AI** uygulamasıyla eşleştirilmiş **Ray-Ban Meta** akıllı gözlük (veya geliştirici testleri için `debug` derlemesinde `MockDeviceKit`)
- **Android 12 (API 31)** veya üzeri Android telefon
- Asistan için aşağıdakilerden biri:
  - **ChatGPT Go, Plus veya Pro** aboneliği (API anahtarı gerekmez)
  - veya [aistudio.google.com](https://aistudio.google.com) üzerinden alınmış **Gemini API anahtarı**

---

## Kurulum ve İlk Bağlantı

1. **Meta AI Geliştirici Modunu Açın:** Gözlük açık ve bağlıyken Meta AI uygulamasında **Ayarlar > Uygulama Bilgileri** ekranına girin, **Uygulama Sürümü** satırına art arda 5 kez dokunun, **Geliştirici Modu** anahtarını açıp **Etkinleştir** deyin. Aynı ekranda varsa **Device Access Toolkit** yanındaki **Yükle** düğmesine basın.
2. **Tupo Vision TR APK'sını Kurun:** [Releases](../../releases/latest) sayfasından APK'yı indirin (`com.senovali.tupovision` kimliğiyle orijinal `tupo_vision` ile yan yana kurulabilir).
3. **İzin ve Bağlantı:** Uygulamayı açıp Bluetooth/mikrofon izinlerini verin, **Meta AI Üzerinden Gözlüğü Bağla** düğmesine dokunup onaylayın.
4. **Asistan Seçimi:** ChatGPT aboneliğinizle giriş yapın veya **Canlı Ses Modu (Gemini Live)** anahtarını açıp Gemini API anahtarınızı kaydedin.

---

## Derleme ve Test Altyapısı

Projede 4 katmanlı otomatik doğrulama altyapısı kuruludur (JDK 17 ve Android SDK 36 gerektirir):

```bash
# 1. Tum JVM, MockWebServer SSE/WebSocket, Robolectric (API 31 & 35) ve Compose Erisilebilirlik testleri (46 test)
./gradlew testDebugUnitTest

# 2. Debug, Release APK ve AndroidTest paketlerinin derlenmesi
./gradlew assembleDebug assembleRelease assembleDebugAndroidTest

# 3. Bagli Android cihaz veya API 31 emulator uzerinde Espresso AccessibilityChecks + canli ML Kit + MockDeviceKit testleri (4 test)
./gradlew connectedDebugAndroidTest
```

### Önemli Not: İmzalama Anahtarı

Üretim APK'ları yerel veya GitHub Actions gizli anahtarları (`SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`) ile imzalanır. Fork'larda bu gizli anahtarlar bulunmadığından derlemeler geçici debug anahtarıyla imzalanır; farklı anahtarlarla imzalanan APK'lar birbirinin üzerine doğrudan güncellenemez (önce eskisini kaldırmak gerekir).

---

## English Summary & Contributing

**Tupo Vision TR** is an accessible, open-source fork of [KeonyRus/tupo_vision](https://github.com/KeonyRus/tupo_vision) tailored for blind and low-vision users using Ray-Ban Meta smart glasses (with full Turkish and TalkBack/Jieshuo screen reader optimization).

Key additions in this fork:
- **Dual Audio Profile (`HD_A2DP` vs. `GLASSES_SCO`):** Keeps `AudioManager.MODE_NORMAL` + `USAGE_ASSISTANT` + `VOICE_RECOGNITION` by default so screen readers and assistant audio remain at 48 kHz A2DP quality instead of dropping to narrowband HFP/SCO.
- **Screen Reader Quiet Mode & Zero-DC PCM Earcons:** Suppresses disruptive `liveRegion` chatter during active sessions while keeping full semantic descriptions, `Role.RadioButton`/`Role.Switch`, and zero-emoji UI.
- **Bundled Offline ML Kit (OCR + Barcode/QR):** On-device `text-recognition:16.0.1` and `barcode-scanning:17.3.0` with multi-column `orderOcrBlocks` sorting and an automatic offline fallback when network `IOException` occurs.
- **4 Contextual Profiles & Voice Commands:** `QUICK`, `DOCUMENT`, `MARKET`, `WALK` profiles switchable via UI, local voice commands, or Gemini Live `functionDeclarations` (`switch_profile`, `scan_local`).
- **4-Tier Test Suite:** `46` JVM/MockWebServer/Robolectric/Compose accessibility unit tests + `4` `connectedDebugAndroidTest` tests (`Espresso AccessibilityChecks`, bundled ML Kit `.so`, `MockDeviceKit`, real API 31 `AudioManager`).

Contributions, accessibility improvements, and PRs are welcome on `main` or feature branches.

- **AI Coding Agents & Developer Guide:** See [AGENTS.md](AGENTS.md) (universal standard for Codex / Antigravity / Claude), [CLAUDE.md](CLAUDE.md), [GEMINI.md](GEMINI.md), and [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Lisans / License

Bu depo **3 ayrı lisans katmanına** tabidir:

1. **Kaynak Kod (`MIT License`):** Bu depodaki Kotlin ve kaynak kodları [LICENSE](LICENSE) dosyası kapsamında MIT lisanslıdır (`Copyright (c) 2026 KeonyRus`, `Copyright (c) 2026 Muhammet Enes Senovali`).
2. **Meta Wearables Device Access Toolkit (`mwdat`):** Açık kaynak değildir; **Meta Wearables Developer Terms** ve **Acceptable Use Policy** kapsamındadır. Depoda yalnızca Maven bağımlılık referansları yer alır; mağaza dağıtımı için Meta Wearables Developer Center kaydı/onayı gerekir.
3. **Google ML Kit (`text-recognition`, `barcode-scanning`):** İkili SDK ve gömülü modeller **ML Kit Terms of Service / Google APIs Terms of Service** kapsamındadır; tersine mühendislikle model çıkarılması yasaktır.

# Katkı Rehberi / Contributing to Tupo Vision TR

Katkılarınız için teşekkürler. Bu rehber hem insan geliştiriciler hem de AI kodlama asistanları (`Codex`, `Claude Code`, `Antigravity`) ile yapılan katkılar için geçerlidir.

## 1. Başlamadan Önce

- Proje mimarisini, değişmez kuralları (ses ayrımı, TalkBack/Jieshuo erişilebilirliği, çevrimdışı ML Kit sınırları ve test komutlarını) öğrenmek için önce [AGENTS.md](AGENTS.md) dosyasını okuyun.
- `main` dalı kararlı ve CI tarafından derlenen daldır. Değişikliklerinizi yeni bir özellik/hata dalında (`feature/...` veya `fix/...`) geliştirip `main` dalına Pull Request (PR) açın.

## 2. Zorunlu Kalite Kapısı

PR açmadan önce aşağıdaki komutların hatasız geçtiğinden emin olun (JDK 17 ve Android SDK 36 gerekir):

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleRelease assembleDebugAndroidTest
```

Eğer fiziksel cihazınız veya API 31 emülatörünüz bağlıysa enstrümantasyon testlerini de çalıştırın:

```bash
./gradlew connectedDebugAndroidTest
```

## 3. Erişilebilirlik ve Güvenlik Kuralları

- **Sıfır Emoji:** Arayüz metinlerinde, içerik açıklamalarında (`contentDescription`) ve günlük mesajlarında emoji kullanmayın.
- **Ekran Okuyucu Uyumu:** Tüm yeni buton, anahtar ve seçim gruplarında `Role`, `mergeDescendants`, `stateDescription` veya `contentDescription` sağlayın; aktif görüşme sırasında otomatik `liveRegion` çakışması yaratmayın.
- **Gizlilik:** Hiçbir commit'e API anahtarı, token, `.keystore`/`.p12` imzalama dosyası veya kişisel cihaz bilgisi eklemeyin.

## 4. Lisans Uyumu

- Gönderdiğiniz yeni kaynak kod katkılarının [LICENSE](LICENSE) dosyası kapsamındaki **MIT Lisansı** ile uyumlu olması gerekir.
- Meta Wearables DAT SDK (`mwdat`) ve Google ML Kit ikili bağımlılıkları sırasıyla Meta Wearables Developer Terms ve Google ML Kit Terms of Service kapsamındadır; repoya tescilli ikili/model dosyası eklemeyin.

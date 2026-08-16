# Yarış Radar (Android)

Türkiye için TJK günlük yarış programını canlı okuyup koşuları listeler; at bazında AGF, HP, kilo, jokey, son form ve oranlardan açıklanabilir bir skor üretir. Her koşuda favori, rakip, sürpriz ve dar/dengeli/güvenli kupon gösterir. Ayrıca saha/padok notu eklenebilir.

## Çalıştırma
1. Android Studio'da klasörü açın.
2. JDK 17 veya 21 seçin.
3. Gradle senkronizasyonu sonrası `app` modülünü çalıştırın.
4. Debug APK: **Build > Build APK(s)**.

## Veri
- Resmî yarış verisi: TJK günlük yarış programı web sayfası.
- Uygulama, üçüncü taraf yorum sitelerinin kullanım şartlarını ihlal edecek toplu scraping yapmaz. “Web yorumlarını ara” düğmesi ilgili koşu için web aramasını açar.
- Tahmin skoru bir bahis garantisi değildir; açıklanabilir bir önceliklendirme algoritmasıdır.

## Sonraki üretim adımı
Gerçek yorumcu konsensüsü için sunucu tarafında izinli API/search entegrasyonu eklenmelidir. `Predictor` ve `TjkRepository` ayrı tutulduğu için kolayca genişletilebilir.

## GitHub Actions APK build
Every push to `main` triggers `.github/workflows/build-apk.yml`.
When the workflow finishes, download the artifact named `horsai-debug-apk`; it contains `app-debug.apk`.

# HorsAI 3.2 Final

Türkiye at yarışları için mobil analiz arayüzü.

## 3.2 güvenilirlik düzeltmeleri
- TJK'ya onlarca paralel istek atmak yerine önce günün aktif Türkiye hipodromlarını keşfeder.
- Resmi TJK YarisSever / Kurumsal / map sayfalarında otomatik fallback kullanır.
- OkHttp timeout, retry ve HTTP doğrulaması eklendi.
- TJK tablo başlıkları isimle eşlenir; sabit kolon indexi kullanılmaz.
- At adı hücresindeki ekipman açıklamalarını ada karıştırmaz.
- Yarış saatleri ve “sıradaki yarış” hesabı Europe/Istanbul saat diliminde yapılır.
- Java/Kotlin JVM 17 uyumluluğu sabitlendi.
- Ana ekranda sıradaki yarış + hızlı erişim, şehir filtreleri ve kupon özetleri korunur.

> Not: TJK veya üçüncü taraf kaynak tamamen erişilemezse hiçbir istemci yüzde 100 canlı veri garantisi veremez. Uygulama bu durumda kontrollü hata ekranı gösterir ve yeniden denemeye izin verir.

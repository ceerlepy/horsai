# Two Horse 5.0 FINAL

Final consolidated build.

- Cache-first race program and cache-first expert signals; live refresh runs in background.
- Expert source resilience: longer network timeouts, OkHttp connection retry, two HTTP attempts, alternate user agents, redirects, multi-URL candidates, link discovery and same-day cache fallback.
- Source diagnostics distinguish reachable sites from sites whose current race section could not be validated.
- Seven expert sources remain configured: HorseTurk, Banko Tahminler, Yıldızlı Bülten, Liderform, Yarış Dergisi, Ganyan Canavarı, Puanlı Altılı Bülten.
- Safer race-section matching scores multiple candidate sections instead of blindly taking the first matching race number.
- Expert display separates Support, Strong, ⭐ Favorite/Banko, Surprise and Negative signals.
- Confidence weights: AGF 30, Expert 25, Form 15, HP 10, Market 10, Weight 5, Saha 5. Missing metrics are reweighted, not zeroed.
- Responsive layout, system back navigation, daily immutable pre-race history, finished-race hiding and countdown timers preserved.
- TJK last-three-video links remain date-sorted and non-runner entries are excluded.
- Two Horse logo alignment adjusted.

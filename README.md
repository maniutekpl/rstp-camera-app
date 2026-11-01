# Domowe kamery

Prosta aplikacja na Androida do podglądu kamer monitoringu przez RTSP (silnik LibVLC).
Działa z kamerami w tej samej sieci Wi-Fi co telefon; sprawdzona z Dahua i Reolink.

## Instalacja

1. Na telefonie otwórz stronę
   [najnowszej wersji](https://github.com/maniutekpl/rstp-camera-app/releases/latest)
   i pobierz plik `app-arm64-v8a-release.apk` (sekcja **Assets**). Pasuje do prawie
   każdego telefonu. Jeśli instalacja się nie uda („aplikacja nie jest zgodna”), masz
   starszy telefon — pobierz `app-armeabi-v7a-release.apk`.
2. Otwórz pobrany plik. Telefon zapyta, czy zezwolić przeglądarce na instalowanie aplikacji
   z nieznanych źródeł — zezwól (można to potem wyłączyć w ustawieniach).
3. Jeśli Google Play Protect ostrzeże o nieznanym twórcy, wybierz **Więcej szczegółów →
   Zainstaluj mimo to**. Aplikacja nie jest w Sklepie Play, stąd to ostrzeżenie.
4. Aktualizacja: pobierz nowszy plik APK i zainstaluj go tak samo. Zapisane kamery zostaną.

Wymagany Android 7.0 lub nowszy.

## Jak używać

- **Szukaj kamer** — znajduje kamery w sieci Wi-Fi przez ONVIF. Po wybraniu kamery i wpisaniu
  loginu i hasła aplikacja sama pobiera adresy strumieni. ONVIF musi być włączony w kamerze:
  w Dahua zwykle jest, w Hikvision często trzeba go włączyć i dodać użytkownika ONVIF,
  w Reolink jest w ustawieniach serwera (kamery na baterię go nie mają).
  Jeśli kamera nie odpowie, aplikacja pokaże przyczynę i pozwoli użyć typowych adresów.
  Rejestrator (NVR/DVR) pokazuje listę kanałów do wybrania (jeszcze nieprzetestowane).
- **Dodaj kamerę** — ręcznie: nazwa i adres `rtsp://…` (format niżej).
- **Edytuj** — zmiana lub usunięcie kamery. **Zmień kolejność** — strzałki ▲▼.
- **Podgląd** — dotknij kamery. Dotknij obrazu albo obróć telefon, aby włączyć pełny ekran.
- **Wszystkie kamery** — siatka z obrazem z każdej kamery (bez dźwięku). Używa lżejszego
  „adresu podglądu”, a gdy ten nie działa, przełącza się na główny strumień.
- Gdy kamera zgubi połączenie albo obraz się zatrzyma, aplikacja sama łączy się ponownie.
- Lista kamer (razem z hasłami) jest zaszyfrowana na telefonie i nie trafia do kopii zapasowej w chmurze.

### Adresy RTSP

Format: `rtsp://login:hasło@192.168.1.50:554/ścieżka`. Ścieżka zależy od producenta:

| Producent | Główny strumień | Lżejszy (podgląd) |
|-----------|-----------------|-------------------|
| Hikvision | `/Streaming/Channels/101` | `/Streaming/Channels/102` |
| Dahua | `/cam/realmonitor?channel=1&subtype=0` | `/cam/realmonitor?channel=1&subtype=1` |
| Reolink | `/h264Preview_01_main` | `/h264Preview_01_sub` |

Jeśli hasło zawiera znaki `@`, `:` albo `/`, trzeba je zakodować (`@` → `%40`).
Przy wyszukiwaniu kamer aplikacja robi to sama.

## Budowanie ze źródeł

Wymagania: Android Studio (zawiera Android SDK i Javę) i telefon z włączonym debugowaniem USB.

1. W Android Studio: **File → Open** i wskaż folder z tym projektem.
2. Poczekaj, aż skończy się „Gradle sync” (za pierwszym razem pobiera sporo plików).
3. Podłącz telefon kablem USB i zaakceptuj na nim zapytanie o debugowanie.
4. Wybierz telefon na liście urządzeń u góry i naciśnij zielony przycisk **Run**.

Wersję do udostępnienia buduje się przez **Build → Generate Signed App Bundle or APK → APK**.
Powstają dwa pliki, po jednym na typ procesora (`app-arm64-v8a-release.apk`,
`app-armeabi-v7a-release.apk`); do wydania na GitHubie dołącz oba.
Emulatory x86 nie są obsługiwane — testuj na telefonie.
Plik klucza (`.jks`) trzymaj poza repozytorium — każda kolejna wersja musi być podpisana tym samym kluczem.

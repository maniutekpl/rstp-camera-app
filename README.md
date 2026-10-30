# Domowe kamery

Prosta aplikacja na Androida do podglądu kamer monitoringu przez RTSP (silnik LibVLC).

**Etap 2:** lista kamer zapisana na telefonie. Kamery dodajesz przyciskiem „Dodaj kamerę”
(nazwa + adres `rtsp://…`), edytujesz i usuwasz przyciskiem „Edytuj”, a dotknięcie kamery otwiera podgląd. Kolejność zmienisz przyciskiem „Zmień kolejność” (strzałki ▲▼).
W podglądzie dotknij obrazu albo obróć telefon, aby włączyć pełny ekran.
Przycisk „Szukaj kamer” znajduje kamery w sieci Wi-Fi przez ONVIF (musi być włączony w kamerze;
w Hikvision często trzeba go włączyć i dodać użytkownika ONVIF). Po wybraniu kamery i wpisaniu
loginu i hasła aplikacja pobiera z kamery przez ONVIF prawdziwe adresy strumieni (główny i lżejszy
do podglądu). Jeśli kamera nie odpowie, aplikacja pokazuje przyczynę i pozwala użyć typowych
adresów dla Dahua, Hikvision i Reolink. Rejestrator (NVR/DVR) pokazuje listę kanałów do wybrania; każdy staje się
osobną kamerą (nieprzetestowane na prawdziwym rejestratorze). Gdy lżejszy strumień w siatce nie działa, kafelek przełącza się na główny.
Gdy kamera zgubi połączenie albo obraz się zatrzyma, aplikacja sama łączy się ponownie
(po 2, 5, 10, a potem co 30 sekund).
Przycisk „Wszystkie kamery” pokazuje siatkę z obrazem z każdej kamery (bez dźwięku).
Do siatki warto podać w kamerze lżejszy „adres podglądu” (np. Hikvision `/Streaming/Channels/102`,
Dahua `subtype=1`), bo kilka strumieni w pełnej jakości naraz mocno obciąża telefon.
Lista kamer (razem z hasłami) jest zaszyfrowana kluczem z Android Keystore i nie trafia do kopii zapasowej w chmurze.

## Wymagania

- Android Studio (zawiera Android SDK i Javę)
- Telefon z Androidem 7.0 lub nowszym, z włączonym debugowaniem USB
- Telefon i kamery w tej samej sieci Wi-Fi

## Uruchomienie

1. W Android Studio: **File → Open** i wskaż folder z tym projektem.
2. Poczekaj, aż skończy się „Gradle sync” (za pierwszym razem pobiera sporo plików).
3. Podłącz telefon kablem USB i zaakceptuj na nim zapytanie o debugowanie.
4. Wybierz telefon na liście urządzeń u góry i naciśnij zielony przycisk **Run**.

## Typowe adresy RTSP

Format: `rtsp://login:hasło@192.168.1.50:554/ścieżka`. Ścieżka zależy od producenta,
np. Hikvision `/Streaming/Channels/101`, Dahua `/cam/realmonitor?channel=1&subtype=0`.
Jeśli hasło zawiera znaki `@`, `:` albo `/`, trzeba je zakodować (`@` → `%40`).

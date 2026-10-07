# Prüfergebnisse am 7. Oktober 2026

## Lokal nachgewiesen

Umgebung: Windows AMD64, heruntergeladenes offizielles OpenJDK **27 GA**, Maven 3.9.16.

- `RUN_BROWSER_TESTS=true mvn --strict-checksums clean verify`: **erfolgreich**.
  42 Fach-/REST-/Worker-/Scheduler-Tests und neun Integrationsprüfungen erfolgreich;
  der externe Live-Test ist in diesem Lauf bewusst nicht aktiviert.
- Tatsächlich gestartetes Chromium **153.0.8010.12**, Playwright 1.63.0.
- Lokaler HTTPS-Testserver auf **IPv6 `[::1]`**: Chromium-CDP bestätigt IPv6.
  UTF-8-Suche, JavaScript-Preisdaten, Startseiten-Cookie, Redirects und Suchliste geprüft.
- Passende, fehlende und widersprechende BGG-ID, Parserfehler, Teilresultat,
  HTTP-200-Challenge und per JavaScript auflösende HTTP-403-Challenge geprüft.
- Generische Loading-Seiten ohne bekannte Challenge-Texte auf Startseite und Suche
  werden bis zum tatsächlichen erwarteten Inhalt abgewartet, einschließlich Weiterleitung
  von einer Such-Loading-Seite direkt auf die Detailseite. Browser- und Gesamttimeout
  sind standardmäßig 45 Sekunden. Der tägliche Scythe-Check ist standardmäßig aktiviert;
  dessen Uhrzeit 08:00 Europe/Berlin, Verhalten bei Zeitumstellung, Abschaltbarkeit und
  ausschließlich live belegter verfügbarer Preis als Erfolg wurden geprüft.
- Fremder HTTPS-Redirect wird vor dem externen Dokumentabruf per CDP blockiert.
- Tatsächliche, neu vom Test gestartete Chromium-Prozesse beendet; anschließender
  Abruf mit begrenztem Browser-/Sitzungsneustart erfolgreich.
- Monatsgrenzen einschließlich Februar/Schaltjahr und exaktem Ablauf geprüft.
  Persistenz nach vollständigem Spring-Neustart, getrennte IDs, getrennte vollständige/
  teilweise Snapshots und unveränderte Erhebungszeiten nach Fehler geprüft.
- Beschädigte H2-Datei verhindert den Start und bleibt bytegenau erhalten.
- REST: 400, 200 Live/Cache/Teilresultat/NOT_FOUND/SKIPPED, 503, `Retry-After`,
  `Cache-Control: no-store`, abgelaufene Preise fehlen, Quellfehler trotz Fallback sichtbar.
- OpenAPI-3.1-Dokumentation mit lokal gebündelter Swagger-UI und Bootstrap-WebJars:
  Dokumentationsroute und Assets per REST geprüft; tatsächliches Chromium lädt beide
  Endpunkte ohne externe UI-Ressourcen oder JavaScript-Fehler. „Try it out“ erreicht
  den Status-Endpunkt mit HTTP 200. Smartphone-Ansicht mit 390 Pixeln ohne horizontales
  Seitenüberlaufen geprüft.
- Queue-Ablehnung, Queue-Wartefrist, Gesamtdeadline einer blockierten Aufgabe,
  Coalescing und Schließen der Browserobjekte auf dem Worker geprüft.
- Vier Python-Tests des lokalen IPv6-Proxys erfolgreich: exakte Host-Allowlist,
  nur `AF_INET6`-Upstream, private IPv6-Ziele abgelehnt, falsche Ziele/Ports lokal abgelehnt.
  Die öffentliche Upstream-Verbindung wird dort gemockt, nicht als öffentlicher
  IPv6-Browserzugriff ausgegeben.
- Executable JAR mit Spring Boot unter Java 27 gestartet; REST und Readiness erreichbar.
- `BrowserSmoke` über den PropertiesLauncher des **gebauten JARs** erfolgreich:
  `CHROMIUM_SMOKE_OK browser=153.0.8010.12 architecture=amd64` und
  `PERSISTENT_CACHE_SMOKE_OK uid=mobil`. Der Lauf war lokal unter Windows, kein Containerlauf.
- Compose-Konfiguration mit `docker compose config --quiet` geprüft.

## Echter Live-Test: gesperrt

`RUN_LIVE_PRICE_COMPARISON_TEST=true` wurde ausdrücklich separat ausgeführt.
Die Startseite `https://www.brettspiel-angebote.de/` liefert im tatsächlichen Chromium
HTTP **403**, Seitentitel **„Establishing a secure connection ...“**.
Auch nach der begrenzten Challenge-Prüfzeit bleibt sie gesperrt:
**`UPSTREAM_BLOCKED`**, Live-Test fehlgeschlagen. Es wurden keine Live-Preise ermittelt
und kein Cache-Erfolg als Live-Erfolg ausgegeben. Aktuelles Detailmarkup/zusätzlich
benötigte CDN-/Schutzhosts konnten dadurch nicht verifiziert werden.

Auch der erneute Live-Test nach Aktivierung des täglichen Schedulers und mit dem
**45-Sekunden-Gesamtbudget** blieb gesperrt: HTTP 403, `UPSTREAM_BLOCKED`.
Die Loading-/Challenge-Seite wurde bis zum verbleibenden Ablaufbudget abgewartet.

## Image-Manifeste geprüft

`docker buildx imagetools inspect` funktioniert unabhängig vom lokalen Docker-Daemon.
Die Rohresultate sind in diesem Verzeichnis gespeichert:

- `maven:3.9.16-eclipse-temurin-25`, Digest
  `sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976`:
  AMD64 und ARM64/v8 vorhanden.
- `mcr.microsoft.com/playwright/java:v1.63.0-noble`, Digest
  `sha256:013e2595272806f887d91041fbf26be71dda2f48a805c717cc7de3bbca5339c8`:
  AMD64 und ARM64 vorhanden.
- `eclipse-temurin:27-jdk-noble` und `maven:3.9.16-eclipse-temurin-27`:
  zum Prüfzeitpunkt **nicht vorhanden**. Deshalb offizielles OpenJDK 27 GA im Build
  und in der Laufzeit, keine falsche Behauptung über diese Tags.
- Java-27-Linux-Archive, offizielle `.sha256`-Dateien geprüft:
  x64 `95fc37eb3a18a27a26d5904c2d89d52bace8dafa9a078ca27f4747fbc4bf070b`,
  aarch64 `da4e9dde1fff90204739e969187bab4751bd59a2a1c479672e1a1810f7dd23ea`.

Die Spring-Boot-BOM liefert H2 **2.4.240** und Flyway **12.4.0**. Flyway meldet,
dass dessen zuletzt ausdrücklich verifizierte H2-Version 2.3.232 ist. Migration,
Hibernate-Schemavalidierung, atomare Speicherung und Neustart wurden hier mit 2.4.240
erfolgreich geprüft. Kein Versions-Downgrade allein zum Verbergen dieser Meldung.
Spring dokumentiert Boot 4.1.1 offiziell bis Java 26; die erfolgreiche Java-27-Prüfung
ist konkret für dieses Projekt und keine offizielle Hersteller-Supportzusage.

## Container auf nativen CI-Runnern nachgewiesen

Der [erfolgreiche GitHub-Actions-Lauf für Commit 3213d58](https://github.com/jensGiehl/brettspielpreise/actions/runs/37655113540)
belegt sowohl auf Ubuntu AMD64 als auch auf Ubuntu ARM64:

- Docker-Build erfolgreich.
- Chromium mit Sandbox, JavaScript, Datenvolume und persistentem Cache nach Neustart geprüft.
- HTTP-Readiness und Container-Neustart erfolgreich.
- Zusätzlich Java-/Browser-Fixtures und vier Proxy-Tests erfolgreich.

## Noch auf der Zielumgebung nachzuweisen

Eine lokale Docker-Engine war nicht erreichbar (`dockerDesktopLinuxEngine`-Pipe fehlt).
Auch der Startversuch von Docker Desktop stellte keine nutzbare Engine bereit.
Daher wurde lokal kein Docker-Build ausgeführt; die Container-Nachweise stammen
aus dem oben verlinkten CI-Lauf auf nativen AMD64-/ARM64-Runnern.

Der ergänzte Workflow `publish-image.yaml` ruft diese Prüfungen auf, übernimmt die
getesteten Images als kurzlebige Artefakte und veröffentlicht bei Erfolg ein gemeinsames
AMD64-/ARM64-Manifest nach `ghcr.io/jensgiehl/brettspielpreise`. Pull Requests bleiben
ohne Veröffentlichung; Pushes auf den Standardbranch starten den Veröffentlichungslauf.
Der tatsächliche Laufstatus ist in GitHub Actions zu prüfen.

Der Raspberry Pi war nicht erreichbar bzw. nicht als zugängliche Umgebung bereitgestellt.
Vor produktivem Betrieb auf dem Pi sind nach README zu prüfen:

1. ARM64-Image beziehen und `BrowserSmoke` mit UID 10001, aktiviertem Sandboxbetrieb,
   Seccomp-Profil und tatsächlichem Datenmount ausführen.
2. HTTP-Readiness und sauberen Container-Stopp/Neustart mit persistenter Datenbank prüfen.
3. Öffentliche IPv6-Adresse, Route und vorhandene Privacy-Konfiguration prüfen.
4. Tatsächliche Chromium-Verbindungsadresse beobachten; nötigenfalls lokalen IPv6-Proxy
   einschalten und im Proxy-Log öffentlichen Remote-/Quelladresspfad belegen.
5. Expliziten Live-Test ohne Cache-Erfolg ausführen; benötigte externe Hosts nach
   tatsächlicher Beobachtung in die Proxy-Allowlist aufnehmen.
6. RAM/CPU/Shared Memory/PIDs unter Last auf dem echten Pi messen. QEMU ist nur ein
   ergänzender Architekturtest.

Es wurde kein Registry-Image veröffentlicht, kein Deployment durchgeführt und keine
Integration oder Änderung an `bg-offers` vorgenommen.

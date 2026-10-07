# bg-prices

Eigenständige Spring-Boot-REST-API für Preise von `https://www.brettspiel-angebote.de/`.
Chromium führt JavaScript aus und besucht Startseite, Suche und Detailseite in derselben
Sitzung. Ein persistenter H2-Cache liefert bei Ausfällen gültige historische Snapshots.
`bg-offers` wird durch dieses Projekt nicht verändert. Angebotsbewertung, Telegram,
Shop-Scraping, BGG-API-Abfragen und Wochenberichte gehören nicht zu dieser API.

## Voraussetzungen und Versionen

Stand der Prüfung: **7. Oktober 2026**. Maven 3.9.16, **OpenJDK 27 GA** ohne Preview,
Spring Boot 4.1.1, Playwright Java 1.63.0, Jsoup 1.23.2. H2, Flyway, Hibernate und
Micrometer werden von der Spring-Boot-BOM verwaltet. Quellcode, Konfiguration und Daten
verwenden UTF-8. Das Spring-Boot-Banner ist deaktiviert.

Das Container-Image basiert auf **Ubuntu 24.04 Noble** und dem offiziellen
Playwright-Java-Image 1.63.0. Dessen Browserrevision passt exakt zur Java-Bibliothek;
Browser und Systembibliotheken sind bereits im Image, kein Browserdownload beim Start.
Die Java-27-GA-Archive für x64 und aarch64 werden im Build mit festem SHA-256 geprüft.
Da Temurin-27- und Maven-Temurin-27-Tags bei der Prüfung nicht vorhanden waren, wird
der Maven-25-Buildcontainer auf das geprüfte OpenJDK 27 umgestellt. Auch die Laufzeit
verwendet dieses JDK 27. Die Basis-Images sind zusätzlich per Manifest-Digest fixiert.

Die Manifeste in [docs/verification](docs/verification/) weisen `linux/amd64` und
`linux/arm64` nach. **Ein 64-Bit-Pi-Betriebssystem ist erforderlich; ARM32 wird nicht unterstützt.**
Spring dokumentiert für Boot 4.1.1 derzeit offiziell Java 17–26; Build, Spring-Start,
Hibernate/Flyway, REST-Tests und lokale Chromium-Tests wurden mit Java 27 geprüft.
Das ist ein konkreter Kompatibilitätstest, keine zusätzliche Supportzusage von Spring.

## Lokal bauen und starten

`JAVA_HOME` auf JDK 27 setzen und Maven im PATH bereitstellen:

```bash
mvn --strict-checksums clean verify
mvn --strict-checksums compile exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  '-Dexec.args=install chromium'
mkdir -p data
DB_PATH="$(pwd)/data/bg-prices" SERVER_PORT=8090 \
  java -Dfile.encoding=UTF-8 -jar target/bg-prices-1.0.0-SNAPSHOT.jar
```

Auf Linux installiert `install --with-deps chromium` zusätzlich die Systembibliotheken
(Installation benötigt entsprechende Rechte). Für Windows PowerShell:

```powershell
$env:DB_PATH = "$PWD/data/bg-prices"
$env:SERVER_PORT = '8090'
java -Dfile.encoding=UTF-8 -jar target/bg-prices-1.0.0-SNAPSHOT.jar
```

`DB_PATH` enthält keine `.mv.db`-Endung. Die H2-Datei wird ausschließlich durch Flyway
migriert, Hibernate verwendet `ddl-auto=validate`. Eine beschädigte oder nicht lesbare
Datenbank verhindert den Start; sie wird nicht gelöscht oder durch eine leere ersetzt.
**Die Datei niemals mit zwei Anwendungsprozessen gleichzeitig öffnen.** Backups bei
gestoppter Anwendung erstellen. Vollständige und teilweise Snapshots sowie letzte
Versuche werden getrennt gespeichert; Snapshot und Versuch werden atomar geschrieben.
Ein Cache-Schreibfehler verhindert die Lieferung belegter Live-Daten nicht, erzeugt
aber ein ERROR-Log und die Metrik `bg_prices_cache_errors`.

## REST-Vertrag

```bash
curl -i -G 'http://127.0.0.1:8090/api/v1/prices' \
  --data-urlencode 'name=Scythe' --data-urlencode 'bggId=169786'
curl -G 'http://127.0.0.1:8090/api/v1/prices' \
  --data-urlencode 'name=Die Glasstraße (German first edition)'
curl 'http://127.0.0.1:8090/api/openapi.yaml'
```

`name` ist erforderlich, nicht leer und maximal 300 Zeichen lang. `bggId` ist optional,
positiv und ein 64-Bit-Integer. Quell-URLs sind keine Request-Parameter. EUR wird ohne
Umrechnung verwendet. Alle API-Antworten setzen **`Cache-Control: no-store`**.
Die vollständige OpenAPI-3.1-Spezifikation mit Beispielen liegt in
[src/main/resources/openapi.yaml](src/main/resources/openapi.yaml).

Die interaktive **Swagger-UI** ist nach dem Start unter
[`http://localhost:8090/swagger-ui.html`](http://localhost:8090/swagger-ui.html)
oder `/docs` erreichbar. Bei Verwendung des Standardports lautet die Adresse
[`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html).
Auch die Startseite `/` führt zur Dokumentation. Die Ansicht zeigt Parameter,
Antwortmodelle, Fehlercodes und Beispiele. Über **Try it out** lassen sich beide
API-Endpunkte direkt auf derselben Instanz aufrufen, beispielsweise mit `Scythe`
und BGG-ID `169786`. Ein Preisabruf kann bis zu 45 Sekunden dauern.

Die maschinenlesbare Beschreibung steht unter `/api/openapi.yaml` bereit und
kann in OpenAPI-Werkzeuge importiert oder in der UI heruntergeladen werden.
Die UI verwendet dieselbe Datei als einzige Quelle der API-Beschreibung.
Swagger UI 5.33.1 und Bootstrap 5.3.8 werden als Maven-WebJars mitgeliefert;
die mobile Ansicht lädt keine Assets von externen CDNs.

| Ergebnis | HTTP | Verhalten |
|---|---:|---|
| `FOUND`, `LIVE` | 200 | Mindestens ein belegter Preis, `stale=false`, `fallbackReason=null` |
| `FOUND`, `CACHE` | 200 | Gültiger alter Snapshot, `stale=true`, konkrete `fallbackReason`, ursprüngliche Erhebungszeiten |
| `NOT_FOUND` | 200 | Bestätigte Leersuche, leerer normalisierter Name oder identifiziertes Spiel ohne Preise; Preise `null` |
| `SKIPPED` | 200 | Bundle ohne bekannte BGG-ID; kein Browserzugriff |
| `ERROR` | 503 | Technischer Fehler ohne gültigen Fallback; Preise und Snapshot-Zeiten `null` |
| `ERROR`, `QUEUE_FULL` | 429 | Queue voll; gültigen Fallback bevorzugen, andernfalls `Retry-After` |
| `INVALID_REQUEST` | 400 | Separate strukturierte Fehlerdetails ohne Stacktrace |

`availablePrice` ist der günstigste **zum Erhebungszeitpunkt verfügbare Vergleichspreis**
laut Website, `bestPrice` deren historischer absoluter Bestpreis. Daraus werden keine
Versandkostenfreiheit oder konkrete Shop-Lieferbarkeit abgeleitet. `null` bleibt fehlend,
nicht `0`. `complete=true` gilt nur mit beiden Preisen. Ein historischer Preis allein
kann `FOUND` mit `complete=false` liefern. Ein Cache-Preis bestätigt keine aktuelle
Verfügbarkeit. `lastAttemptAt` ist der Beginn des letzten tatsächlichen Browserversuchs;
Queue-Ablehnung und Cache-Lesen sind keine neue Erhebung. `retryAt`/`Retry-After` geben
bei wiederholbaren Fehlern eine sinnvolle Wartezeit an.

Fehlercodes sind in OpenAPI aufgeführt. Besonders relevant: `UPSTREAM_BLOCKED`
(403/429 oder Challenge), `UPSTREAM_HTTP_ERROR`, `NETWORK_ERROR` (einschließlich DNS/TLS),
`NAVIGATION_TIMEOUT`, `TOTAL_TIMEOUT`, `QUEUE_TIMEOUT`, `BROWSER_CRASH`, `PARSER_ERROR`,
`IDENTITY_MISMATCH`, `IDENTITY_UNCONFIRMED`, `NAME_CONFLICT`, `UNSAFE_NAVIGATION`,
`NO_PRICE_DATA` und `PARTIAL_RESULT`.

## Namen und Identität

Die Normalisierung entfernt vollständige runde Klammern von innen nach außen, also auch
leere/numerische/verschachtelte Zusätze. Führende Sprachpräfixe vor `:`, `|`, `/`, `-`,
die eigenständigen Wörter `Stapelspiel`, `Würfelspiel`, `Jubiläumsausgabe`, Text ab `inkl.`
und nachgestellte `Bundle`/`Set` werden entfernt. Unicode wird als NFC normalisiert,
Umlaute bleiben erhalten, Leerzeichen werden zusammengefasst. Die Suche ist UTF-8-kodiert.
Unvollständige Klammern werden nicht als vollständiger Zusatz entfernt.

Bundle-Erkennung geschieht am Originalnamen mit Unicode-Wortgrenzen: `Scythe Bundle`
und `Bundle-Angebot` zählen, `Bundled Edition` nicht. Mit bekannter BGG-ID wird trotzdem
recherchiert. Ohne ID wird der erste Website-Treffer gewählt und sein normalisierter
`h1`-Name exakt, ohne Groß-/Kleinschreibung, geprüft. Abweichende Namen/Editionen werden
als Konflikt abgelehnt; diese konservative Regel kann mehrdeutige Titel zurückweisen.

Mit ID muss ein passender BGG-Link im Suchtreffer und erneut auf der Detailseite vorliegen.
Bei direkter Weiterleitung wird nur die Detailseite geprüft. Fehlende Links sind
unbestätigt, widersprechende IDs ein Identitätsfehler. Nur der Host
`boardgamegeek.com`/`www.boardgamegeek.com` und ein exakter Pfad `/boardgame/<ID>` mit
optionalem Slug zählen. `123` trifft niemals `1234`; Website-Spiel-ID und BGG-ID sind
verschiedene IDs. Es erfolgt keine zusätzliche BGG-Abfrage.

Der Parser verarbeitet den **gerenderten DOM** mit den belegten Attributen
`[itemprop=offers] meta[itemprop=lowPrice]` und `[data-absolute-bestprice]`. Er wartet auf
Inhaltszustände, berücksichtigt JavaScript und Challenges auch bei HTTP 200/403 und
akzeptiert keine negativen, uneindeutigen oder ungültigen Zahlen. Unbekannte Trefferseiten
sind Parserfehler, kein erfundenes `NOT_FOUND`. Auf der echten Quelle konnten die
aktuellen Selektoren wegen der Sperre bisher nicht bestätigt werden.

Eine vorgeschaltete Bot-Schutz- oder Loading-Seite wird im selben Browserkontext
abgewartet, bis tatsächlich die erwartete Start-, Such- oder Detailseite erkannt wird.
Auch eine Loading-Seite ohne bekannte Challenge-Texte wird nicht vorzeitig ausgewertet.
JavaScript-Weiterleitungen und DOM-Änderungen werden berücksichtigt, ebenso ein Wechsel
von HTTP 403 zu 200. Das Standardbudget beträgt **45 Sekunden insgesamt**, einschließlich
Queue, Navigation und Wiederholungen; Wartephasen addieren keine weiteren 45 Sekunden.
Auf der erkannten Detailseite werden Preisattribute nochmals bis zu zwölf Sekunden
innerhalb des verbleibenden Budgets erwartet, damit Teilresultate weiter möglich bleiben.

## Cache und Parallelität

Primär wird live recherchiert. Es gibt keinen stundenlangen positiven Cache als Ersatz
für angeforderte Live-Daten. Gleichzeitige gleiche Schlüssel werden für die laufende
Abfrage zusammengeführt. Der Schlüssel enthält HTTPS-Quelle, Unicode-normalisierten
Namen mit `Locale.ROOT`-Kleinschreibung und angeforderte BGG-ID. Ohne ID und mit ID sowie
verschiedene IDs sind getrennte Einträge.

Verbindlich: **ein Kalendermonat in UTC ab erfolgreicher Erhebung**:

```java
expiresAt = fetchedAt.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
```

Nur `now < expiresAt` ist gültig. Beispiel: 31. Januar 2026 → 28. Februar 2026, im
Schaltjahr 2024 → 29. Februar. Exakt ab Ablauf gibt es keine Preise aus diesem Snapshot.
Fehler, Neustarts, Zugriffe und Abkühlphasen verlängern die Frist nicht. Auch zukünftige
Snapshots nach einer Uhr-Rückstellung werden nicht ausgeliefert. Eine injizierte UTC-Clock
steuert Erhebung, Ablauf und Abkühlphase.

Leere/fehlerhafte Ergebnisse ändern keine erfolgreichen Snapshots. Bei einem Teilresultat
wird ein noch gültiger vollständiger Snapshot insgesamt als Fallback mit `PARTIAL_RESULT`
zurückgegeben. Preise verschiedener Zeitpunkte werden nicht vermischt. Das Teilresultat
wird in einem separaten Slot gespeichert; ein vollständiger alter Snapshot bleibt erhalten.
Ohne gültigen vollständigen Snapshot darf das Teilresultat live zurückkommen. Wenn später
der vollständige Slot abgelaufen ist, kann ein noch gültiger Teil-Snapshot dienen.

Fehler und fehlende Preise lösen standardmäßig fünf Minuten Abkühlphase aus; `NO_MATCH`
nicht. Währenddessen gibt es gültigen Cache oder einen klaren technischen Fehler. Der
Quellstatus wird durch Cache-Nutzung nicht auf erfolgreich gesetzt. Abgelaufene Einträge
bleiben diagnostisch erhalten; eine tägliche Bereinigung um 03:00 UTC löscht Snapshots,
deren Ablauf über 90 Tage zurückliegt. Der Ablaufcheck ist davon unabhängig.

Ein Plattform-Worker erstellt, benutzt und schließt alle Playwright-Objekte. Browser und
Kontext werden wiederverwendet, Seiten nach jedem Versuch geschlossen. Standard: acht
wartende Aufgaben, ein aktiver Worker, 1,5 Sekunden Mindestabstand, zwei technische
Versuche, 750 ms zusätzliche Retry-Pause. Nur Netzwerkfehler, Browserabsturz und
Navigationstimeout werden unmittelbar wiederholt. Eine Blockierung erzeugt keine
automatische Wiederholung. Kaputte Sitzungen werden begrenzt neu gestartet, dann wird
die Startseite erneut besucht. Queue-Wartezeit und gesamte Browseraufgabe sind begrenzt.
Top-Level-Navigation einschließlich Redirects wird über Playwright und Chromium-CDP auf
den konfigurierten HTTPS-Ursprung begrenzt; zusätzliche Fenster werden blockiert.
Benötigte externe Browserressourcen werden nicht pauschal blockiert.

## Konfiguration

Spring verwendet typisierte und validierte `prices.*`-Properties. Umgebungsvariablen:

| Variable | Standard | Bedeutung |
|---|---|---|
| `PRICES_BASE_URL` | `https://www.brettspiel-angebote.de/` | HTTPS-Ursprung, kein Pfad/Query/Zugangsdaten |
| `PRICES_BROWSER_PATH` | leer | Optional abweichende Chromium-Binärdatei; eigene Kompatibilität prüfen |
| `PRICES_HEADLESS` | `true` | Für lokale Diagnose `false`, benötigt Display |
| `PRICES_SANDBOX` | `true` | Chromium-Sandbox aktiv |
| `PRICES_BROWSER_TIMEOUT` | `45s` | Navigation und Warten auf die erwartete Seite, maximal 45s; durch die Gesamtdeadline begrenzt |
| `PRICES_QUEUE_TIMEOUT` | `10s` | Maximale Queue-Wartezeit, maximal 30s |
| `PRICES_TOTAL_TIMEOUT` | `45s` | Gesamtdeadline einschließlich Queue/Retry, maximal 60s |
| `PRICES_QUEUE_CAPACITY` | `8` | Wartende Aufgaben, 1–100 |
| `PRICES_ATTEMPTS` | `2` | Technische Versuche, 1–3 |
| `PRICES_MINIMUM_INTERVAL` | `1500ms` | Mindestabstand, maximal 10s |
| `PRICES_RETRY_PAUSE` | `750ms` | Retry-Pause zusätzlich zum Mindestabstand |
| `PRICES_COOLDOWN` | `5m` | Fehler-Abkühlphase, 0–15m |
| `PRICES_PROXY` | leer | HTTP/SOCKS5-Proxy ausschließlich auf Loopback ohne Zugangsdaten |
| `IPV6_PROXY_ENABLED` | `false` | Im Container eingeschränkten IPv6-Proxy starten, setzt `PRICES_PROXY` |
| `IPV6_PROXY_ALLOWED_HOSTS` | beide Schreibweisen der Quelldomain | Exakte erlaubte HTTPS-Hostnamen, kommasepariert |
| `PRICES_DIAGNOSTICS_ENABLED` | `false` | Private Fehler-Screenshots/HTML opt-in |
| `PRICES_DIAGNOSTICS_PATH` | `/app/diagnostics` | Diagnoseverzeichnis |
| `PRICES_DIAGNOSTIC_FILES` | `20` | Maximal 20 Dateien standardmäßig, HTML höchstens 1 MiB |
| `PRICES_SOURCE_CHECK_ENABLED` | `true` | Täglicher tatsächlicher Scythe-Test um 08:00 Europe/Berlin; mit `false` deaktivierbar |
| `PRICES_SOURCE_CHECK_AT_STARTUP` | `false` | Zusätzlicher Live-Test beim Start |
| `DB_PATH` | `/app/data/bg-prices` | H2-Dateipfad ohne Endung |
| `DB_PASSWORD` | leer | Optionales Datenbankpasswort |
| `SERVER_PORT` | `8080` | Listener; Compose verwendet 8090 |
| `LOG_LEVEL` | `INFO` | Anwendungs-Log-Level, z. B. DEBUG |

Die TOMCAT-Threads sind auf 32, Verbindungen auf 128 und die Annahmequeue auf 32 begrenzt.
H2-Locks, JPA-Queries und Pool-Warten sind auf zwei Sekunden begrenzt. Die API bietet
keinen eigenen mehrminütigen Recherche-Scheduler. Als Ausgangspunkt auf einem Pi mit
mindestens 2 GiB RAM: ein Worker, 1,5 GiB Containerlimit, zwei CPUs, 256 MiB Shared Memory,
256 PIDs; die JVM erhält höchstens 55 % des Container-RAMs. Diese Werte sind noch keine
Pi-Messung und müssen mit `docker stats bg-prices` auf dem Zielgerät geprüft werden.

## Docker auf dem Linux-Pi: empfohlener Host-Modus

Das Projekt hat noch kein veröffentlichtes Registry-Image. Für Pull/Update muss
`BG_PRICES_IMAGE` auf den tatsächlich bereitgestellten Registry-Namen gesetzt werden;
die folgenden Befehle verwenden diesen Wert, ohne eine Veröffentlichung vorzutäuschen.
Containerbau und CI veröffentlichen oder deployen nichts.

```bash
mkdir -p data
sudo chown 10001:10001 data
sudo chmod u+rwX data
IMAGE="${BG_PRICES_IMAGE:?BG_PRICES_IMAGE auf den tatsächlichen Registry-Namen setzen}"

docker rm -f bg-prices 2>/dev/null

docker run -d \
  --name bg-prices \
  --pull=always \
  --init \
  --restart unless-stopped \
  --network host \
  --shm-size=256m \
  --memory=1536m \
  --cpus=2 \
  --pids-limit=256 \
  --stop-timeout=65 \
  --security-opt "seccomp=$(pwd)/docker/seccomp_profile.json" \
  -e SERVER_PORT=8090 \
  -e DB_PATH=/app/data/bg-prices \
  --mount "type=bind,source=$(pwd)/data,target=/app/data" \
  "$IMAGE"
```

Unter `http://<PI-IP>:8090` ist die neue API erreichbar. 8090 vermeidet die Belegung von
8089 durch `bg-offers`. Im Host-Netzwerk ist `SERVER_PORT` zugleich der Host-Port;
`-p` wird ignoriert. Compose enthält daher keine `ports:`-Zuordnung. `bg-offers` könnte
später im selben Linux-Host-Netz `http://127.0.0.1:8090` verwenden. Bei einem anderen
Netz muss die Host-Erreichbarkeit separat eingerichtet werden.

Das offizielle Playwright-Seccomp-Profil in [docker/seccomp_profile.json](docker/seccomp_profile.json)
erlaubt die für die Sandbox nötigen User-Namespace-Aufrufe. UID/GID sind **10001:10001**.
Auf dem Host müssen unprivilegierte User-Namespaces tatsächlich verfügbar sein;
Ubuntu-AppArmor kann diese zusätzlich beschränken. Der Smoke-Test prüft die aktivierte
Sandbox. Eine Umgebung, die sie verbietet, muss gezielt für diese Anwendung angepasst
werden; die Beispiele verwenden keine pauschalen privilegierten Rechte und deaktivieren
die Sandbox nicht. `--init`, 65 Sekunden Stop-Frist und Shared Memory sind bewusst gesetzt.

Lokal bauen und ohne Registry starten:

```bash
docker build -t bg-prices:local .
cp .env.example .env
docker compose up -d --build
docker compose logs -f
```

Das entspricht `BG_PRICES_IMAGE=bg-prices:local`; Compose verwendet keinen erzwungenen Pull.
Für einen manuellen lokalen `docker run` die obige Host-Variante mit
`IMAGE=bg-prices:local` und **ohne `--pull=always`** verwenden. Multiarch-Prüfbau:

```bash
docker buildx build --platform linux/arm64 --load -t bg-prices:arm64 .
docker buildx build --platform linux/amd64 --load -t bg-prices:amd64 .
```

Ein Remote-Update verwendet den tatsächlichen Registry-Namen und die oben gezeigte
`docker rm`/`docker run --pull=always`-Sequenz mit demselben Datenmount. Mit Compose:
`docker compose pull && docker compose up -d --no-build`. Für lokal gebaute Images:
`docker compose up -d --build`. Daten bleiben im Host-Verzeichnis erhalten.

Bei `AccessDeniedException: /app/data` den Container stoppen, die **tatsächlichen**
Host-Verzeichnisse prüfen und Besitzer/Rechte korrigieren:

```bash
docker stop --time 65 bg-prices
ls -ld data
sudo chown -R 10001:10001 data
sudo chmod -R u+rwX data
docker start bg-prices
docker logs --tail 100 bg-prices
```

## Bridge-Alternative mit gesondert geprüftem IPv6-Ausgang

Die Alternative setzt ein separat eingerichtetes IPv6-Bridge-Netz einschließlich
funktionierender Route und passender Quelladresswahl voraus. `BG_PRICES_BRIDGE_NETWORK`
benennt dieses bereits geprüfte Netzwerk. Ein gewöhnliches Docker-Bridge-Netz übernimmt
Host-IPv6/Privacy-Adressen nicht automatisch.

```bash
IMAGE="${BG_PRICES_IMAGE:?Registry-Namen setzen}"
BRIDGE_NETWORK="${BG_PRICES_BRIDGE_NETWORK:?Geprüftes IPv6-Bridge-Netz setzen}"

docker rm -f bg-prices 2>/dev/null

docker run -d \
  --name bg-prices \
  --pull=always \
  --init \
  --restart unless-stopped \
  --network "$BRIDGE_NETWORK" \
  --shm-size=256m \
  --stop-timeout=65 \
  --security-opt "seccomp=$(pwd)/docker/seccomp_profile.json" \
  -p 8089:8080 \
  -e SERVER_PORT=8080 \
  -e DB_PATH=/app/data/bg-prices \
  --mount "type=bind,source=$(pwd)/data,target=/app/data" \
  "$IMAGE"
```

**8089 ist hier der Port am Host; 8080 ist der Port im Container.** Wenn `bg-offers`
gleichzeitig 8089 verwendet, für die neue API **`-p 8090:8080`** setzen. Die Alternative
ist hier nicht als erfolgreicher Pi-IPv6-Betrieb verifiziert.

## IPv6, Quelladresse und Browserdiagnose

Im alten Programm wurde auf dem Pi beobachtet: dieselbe curl-Anfrage erhielt mit einer
IPv6-Quelladresse 403, mit einer anderen Adresse desselben `/64` 200 und anschließend
mit der ursprünglichen Adresse wieder 403. Nach Bevorzugung temporärer IPv6-Adressen
funktionierte der damalige Java-Ablauf mit 200/302/200/200. Die Schutzregel blieb unbekannt;
dies beweist keine aktuelle Freigabe und ist kein Anlass für automatische Adressrotation.

Die vorhandene Host-Datei `/etc/sysctl.d/99-bg-offers-ipv6-privacy.conf` weiterverwenden:

```ini
net.ipv6.conf.wlan0.use_tempaddr = 2
```

Bei Ethernet/anderem Interface `wlan0` durch dessen echten Namen ersetzen. Öffentliche
IPv6-Adresse und Route sind Voraussetzung. Die Anwendung ändert keine Host-Sysctls,
schreibt keine temporäre Adresse fest und benötigt keinen privilegierten Container.

```bash
ip -6 addr show dev wlan0
ip -6 route
sysctl net.ipv6.conf.wlan0.use_tempaddr
curl -4 -I https://www.brettspiel-angebote.de/
curl -6 -I https://www.brettspiel-angebote.de/
```

Java-DNS-Sortierung und JVM-Properties beeinflussen **nicht** die Verbindungen des
Chromium-Prozesses. Host-Netzwerk ermöglicht Zugriff auf Host-Routen/Adressen, garantiert
aber keine Browser-IPv6-Präferenz. Nach einem API-Abruf zeigt
`GET /api/v1/source-status` die per Chromium-CDP beobachtete `remoteAddress`, `ipv6` und
`observedAt`. DEBUG-Logs enthalten dieselbe Adresse. Ein erfolgreicher curl-Aufruf allein
ist kein Browsernachweis.

Wenn Chromium die gesperrte IPv4-Verbindung verwendet, den mitgelieferten **lokalen
IPv6-HTTPS-CONNECT-Proxy** aktivieren:

```bash
IPV6_PROXY_ENABLED=true docker compose up -d --force-recreate
docker logs -f bg-prices
curl -G http://127.0.0.1:8090/api/v1/prices \
  --data-urlencode name=Scythe --data-urlencode bggId=169786
```

Bei manuellem `docker run` zusätzlich `-e IPV6_PROXY_ENABLED=true` setzen. Der Entrypoint
startet den Proxy im selben Netzwerknamespace auf **127.0.0.1:8891** und setzt den
Browserproxy. Nur CONNECT zu Port 443 und exakt freigegebenen DNS-Namen ist erlaubt;
keine Wildcards, privaten IP-Ziele oder IPv4-Upstreams. Verbindungen laufen explizit über
`AF_INET6`; die Linux-Routen/Privacy-Konfiguration wählen die Quelladresse. Ohne öffentliche
AAAA-Adresse/Route schlägt der Proxy fehl, statt still auf IPv4 zurückzugehen.

Benötigte externe Schutz-/CDN-Hosts müssen nach Beobachtung ausdrücklich in
`IPV6_PROXY_ALLOWED_HOSTS` ergänzt werden. Der Proxy startet bewusst mit den zwei
Schreibweisen der Quelldomain; die aktuell benötigten externen Hosts konnten wegen der
Sperre noch nicht festgestellt werden. Der Proxy ist auf 16 Tunnel, Headergröße 8 KiB,
fünf Sekunden Verbindungsaufbau und 60 Sekunden Tunneldauer begrenzt. TLS bleibt Ende-zu-Ende
zwischen Chromium und Website, keine TLS-Entschlüsselung. Lokale Entwicklung:
`python3 scripts/ipv6_proxy.py` und `PRICES_PROXY=http://127.0.0.1:8891`.

Bei Proxy-Nutzung zeigt Chromium gegebenenfalls nur Loopback; erst das Proxy-Log
`IPv6 tunnel host=... remote=... source=...` belegt IPv6-Upstream und tatsächlich verwendete
Quelladresse. Zusätzlich muss der **Live**-Abruf einen bestätigten verfügbaren Preis liefern.
Der lokal getestete direkte IPv6-Pfad zu `[::1]` beweist Browserfähigkeit, nicht öffentliches
Pi-Routing. Die abschließende Messung auf dem Pi steht aus.

## Health, Logs, Diagnosen und Metriken

```bash
curl http://127.0.0.1:8090/actuator/health/liveness
curl http://127.0.0.1:8090/actuator/health/readiness
curl http://127.0.0.1:8090/api/v1/source-status
curl http://127.0.0.1:8090/actuator/prometheus
docker stats bg-prices
```

Liveness hängt nicht vom Internet ab. Readiness prüft lokalen Anwendungszustand,
Datenbank und lebenden Worker; der Browser wird beim ersten tatsächlichen Abruf gestartet.
Die Installation/Sandbox muss zusätzlich mit dem Smoke-Test geprüft werden. Kein Docker-
Healthcheck navigiert extern. Der getrennte Quellstatus zählt nur einen **live** erhaltenen
verfügbaren Preis als Erfolg, kein Cache und keinen historischen Preis allein.
Der Anwendungsscheduler testet standardmäßig **täglich um 08:00 Uhr Europe/Berlin**
die Verbindung mit **Scythe, BGG-ID 169786** im tatsächlichen Browser. Er umgeht
den Cache und dessen Abkühlphase; Cache-Daten können den Test nicht erfolgreich machen.
Beginn und Ergebnis werden geloggt und über `/api/v1/source-status` bereitgestellt.
`UNKNOWN` bedeutet noch nicht geprüft. Den Zeitstempel prüfen: Ein älterer `UP`-Status
ist keine aktuelle Messung. Der zusätzliche Startcheck bleibt unabhängig davon optional.

Logs enthalten Schritt, Versuch, URL ohne Query/Fragment, HTTP-Status, finale URL,
Seitentitel (begrenzt), Laufzeit, Fehlercode, Cache-Alter und Fallback-Grund. Es werden
keine Cookies, Set-Cookie-/Authorization-Header oder vollständigen Browserprotokolle geloggt.
DEBUG ergänzt die Chromium-Verbindungsadresse. Prometheus-Metriken:
`bg_prices_live_success`, `bg_prices_live_failure{reason=...}` (einschließlich Blockierung),
`bg_prices_cache_fallback`, `bg_prices_cache_expired` (Beobachtungen abgelaufener Snapshots),
`bg_prices_browser_restarts`, `bg_prices_queue_size`, `bg_prices_queue_utilization`,
`bg_prices_cache_errors`.

Diagnosen sind standardmäßig aus. Für private Fehler-HTML/Screenshots zusätzlich:

```bash
mkdir -p diagnostics
sudo chown 10001:10001 diagnostics
sudo chmod 700 diagnostics
```

Im Startbefehl `-e PRICES_DIAGNOSTICS_ENABLED=true` und
`--mount "type=bind,source=$(pwd)/diagnostics,target=/app/diagnostics"` ergänzen; bei Compose
entsprechend einen zweiten Volume-Eintrag setzen. Nur eigene `failure-*`-Dateien werden
bei der nächsten Aufnahme bereinigt, maximal 20 Dateien bzw. 24 Stunden. Screenshots
sind auf den Viewport begrenzt, HTML auf 1 MiB. Die Dateien können sensible Sitzungsdaten
enthalten: nicht veröffentlichen. Sie werden weder über die API ausgeliefert noch als
Preise gecacht. `.env`, Datenbanken und Diagnosen sind von Git/Build-Kontext ausgeschlossen.

## Prüfungen und aktuelle Grenzen

```bash
mvn --strict-checksums clean verify
RUN_BROWSER_TESTS=true mvn --strict-checksums clean verify
python3 -m unittest discover -s scripts -p 'test_*.py'
RUN_LIVE_PRICE_COMPARISON_TEST=true mvn --strict-checksums \
  -Dit.test=LivePriceComparisonIT verify
```

Ohne Umgebungsflags laufen Fach-, Cache-, Restart- und REST-Tests ohne echte Website.
`RUN_BROWSER_TESTS=true` aktiviert echte Chromium-Tests gegen einen lokalen IPv6-HTTPS-
Server: direkte Redirects/Suchliste, Unicode, Sitzungscookies, JavaScript-Preise, exakte
Identität, HTTP-200-Challenge, auflösende HTTP-403-Challenge, externe Redirects und
Browserabsturz/Recovery. Das mitgelieferte PKCS12-Zertifikat und Passwort sind ausschließlich
öffentliche lokale Test-Fixtures; Zertifikatsfehler werden nur im Test akzeptiert.

Der Live-Test ruft Scythe mit BGG-ID 169786 direkt im Browser ab. Er verlangt bestätigte
Identität und einen positiven verfügbaren Preis, verwendet keinen Cache und keine festen
aktuellen Preiswerte. **Ein Headless-Browser garantiert keine Freigabe durch den Schutz.**
Keine CAPTCHA-Dienste, Adressrotation oder automatische Sperrumgehung werden verwendet.
`brettspiel-preise.de` ist keine verifizierte zweite Quelle oder Weiterleitung.

Container-Smoke-Test (bei verfügbarer Engine) mit aktiviertem Sandboxbetrieb:

```bash
docker run --rm --init --shm-size=256m \
  --security-opt "seccomp=$(pwd)/docker/seccomp_profile.json" \
  --mount "type=bind,source=$(pwd)/data,target=/app/data" \
  --entrypoint java bg-prices:local \
  -Dloader.main=de.agiehl.bgprices.browser.BrowserSmoke \
  -cp /app/bg-prices.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Er startet wirklich Chromium, prüft JavaScript, schreibt UTF-8 ins Volume und prüft einen
H2-Snapshot nach vollständigem Spring-Neustart in einer separaten `smoke-cache`-Datenbank.
Erwartet: `CHROMIUM_SMOKE_OK` und `PERSISTENT_CACHE_SMOKE_OK`. Für ARM64 mit
`--platform linux/arm64` und dem ARM64-Image ausführen. Der vorbereitete Workflow
[.github/workflows/verify.yaml](.github/workflows/verify.yaml) baut/prüft beide Architekturen
ohne Veröffentlichung. QEMU ersetzt keine abschließende Ressourcen-/IPv6-Prüfung auf dem Pi.

Konkrete lokale Prüfergebnisse und ausstehende Container-/Pi-Verifikation stehen in
[docs/verification/RESULTS.md](docs/verification/RESULTS.md).

## Primärquellen

- [OpenJDK 27 GA](https://jdk.java.net/27/)
- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Playwright Java Installation](https://playwright.dev/java/docs/intro)
- [Playwright Docker und Sandbox](https://playwright.dev/java/docs/docker)
- [Playwright Threading](https://playwright.dev/java/docs/multithreading)
- [Linux IPv6 Sysctls](https://docs.kernel.org/networking/ip-sysctl.html)
- [Docker Host-Netzwerk](https://docs.docker.com/engine/network/drivers/host/)

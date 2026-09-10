# Rettelsesplan for Memos Pocket

Status: første implementeringsmilepæl bygget og automatisk testet. Appen er **ikke ferdig**; ingen installasjon eller live-varsling er verifisert i denne runden. Se milepælstatus og paritetsmatrise nederst. Avkrysset betyr implementert/automatisk kontrollert, ikke godkjent på tablet.

## Mål og prioritering

Appen skal være en fullverdig Android-app for Memos, ikke en read-only-klient eller begrenset companion-MVP. Webklientens funksjoner er minimumsreferansen, ikke bare dens visuelle stil. Den eksisterende UI-en skal erstattes, ikke pyntes på. Varsler må fungere på den faktiske tableten før funksjonen kan erklæres ferdig.

Brukerens skjermbilde `/tmp/pi-clipboard-f10304c5-5419-4faa-a04b-dfa64f4b3e97.png` viser memomenyen med Pin, Edit, Copy, Archive og More. Underpunktene er kontrollert i `web/src/components/MemoActionMenu/MemoActionMenu.tsx`. Alle handlingene nedenfor skal implementeres, ikke bare vises som menyvalg. Tidligere MVP-avgrensninger er ikke grunnlag for å utelate webfunksjoner.

## Minimumsparitet med webklienten

- [ ] Lag en funksjonsmatrise for web → Android før implementering. Gå gjennom feed, editor, memo-detalj, menyer, søk/filtrering, vedlegg, spaces, kommentarer, deling og innstillinger. Skill brukerfunksjoner fra serveradministrasjon; dokumenter eventuelle reelle API-begrensninger i stedet for å utelate funksjoner i stillhet.
- [x] **Pin / Unpin:** lagre `pinned` på serveren og gjenspeil det i feedens sortering og visning.
- [ ] **Edit:** full memoeditor, ikke bare et enkelt tekstfelt uten øvrige memofunksjoner.
- [x] **Copy → Link / Content:** kopier korrekt weblenke eller originalt Markdown-innhold. Ingen token i lenken; kopiering skal ikke endre synlighet eller opprette offentlig deling.
- [x] **Archive / Restore:** serverlagret status og tilgjengelig arkiv.
- [ ] **More → Move:** flytt memo mellom tillatte spaces/plasseringer, med samme tilgangsregler som web.
- [x] **More → Delete:** faktisk serversletting med bekreftelse og korrekt håndtering av serverens beskyttelser.
- [ ] **Task actions:** kryss av alle / fjern alle avkrysninger når memoen inneholder oppgaver, samt interaktive enkeltoppgaver i memoen. Bevar øvrig Markdown.
- [ ] Menyen følger webklientens regler for eierskap, arkiverte memos og kommentarer. Ikke tilby handlinger brukeren mangler rettighet til.
- [x] Vedlegg og space-funksjoner inngår i paritetskartleggingen og leveranseplanen; de er ikke lenger automatisk utenfor scope.

**Akseptanse:** Hver handling skal gi samme varige resultat i Android og web etter refresh. Ingen tomme menyvalg, read-only-erstatninger eller «kommer senere» forkledd som fullført funksjon.

Arbeidsrekkefølge:
1. Reproduser og rett varslingsfeilen uten å miste brukerdata.
2. Rett applikasjons-ID til `com.vstokke.memos` før videre distribusjon.
3. Bygg nytt Memos-basert appskall med innstillinger og riktig navigasjon.
4. Implementer full CRUD og koble det til det nye grensesnittet.
5. Kjør ende-til-ende- og visuell akseptansetest på tableten.

## 1. Varsler — høyeste prioritet

### Diagnose før endring

- [x] Behold installasjon, innlogging og eksisterende memos under undersøkelsen. Ikke avinstaller, slett appdata eller tøm systemlogger.
- [ ] Kontroller nåværende `/api/v1/instance/profile` på `https://memos.vstokke.com`. Sist undersøkte deployment var `bbe3fc141132` uten `memoReminderTimeSupported`; dette er en mulig forklaring, ikke en bekreftet diagnose av brukerens test.
- [ ] Skill mellom manglende serverkapabilitet, manglende lagring av `reminderTime`, manglende synk, manglende alarm og blokkert varsling.
- [ ] Kontroller Androids varslingstillatelse, varslingskanal, tilgang til eksakte alarmer, neste planlagte alarm og bakgrunnsbegrensninger på Samsung-tableten.
- [ ] Følg én uttrykkelig testmemo fra serverens lagrede tidspunkt til lokal database, AlarmManager, receiver, delivery ledger og NotificationManager. Ikke skriv token eller privat memoinnhold i diagnostikk.
- [ ] Dokumenter den verifiserte årsaken og rett den. Ikke omgå kapabilitetskontrollen ved å anta støtte ut fra domenet.

### Rettelser og tester

- [x] Capability-endringer må slå gjennom i både synk og synlig UI. Nettverksfeil skal ikke tolkes som bekreftet manglende støtte.
- [x] Stans og fjern foreldede lokale påminnelser når serveren bekrefter at støtte er borte, også dersom en senere feed-request feiler.
- [x] Sikre at bakgrunnsjobber ikke kansellerer seg selv midt i nødvendig opprydding ved serverbytte eller kapabilitetsendring.
- [x] Legg varslingsstatus og en eksplisitt «Test varsling»-handling i innstillinger. Skill test av NotificationManager fra test av planlagt serverpåminnelse.
- [x] Vis hvorfor en påminnelse ikke kan leveres, med konkret handling for riktig Android-innstilling. Ikke påstå at en lagret memo betyr at varsling virker.
- [x] `reminderTime` på serveren er fortsatt sannhetskilden. Ingen skjult lokal-only fallback.
- [ ] Test skjerm av, app i bakgrunnen, prosessrestart, omstart, tidssoneendring, offline levering av allerede synkronisert påminnelse og 24-timers catch-up.
- [ ] Test redigert/fjernet tidspunkt, arkivering og sletting etter synk: ingen gammel varsling eller dublett. Dokumenter at eksterne endringer først kan oppdages ved vellykket synk.
- [x] Dokumenter Androids begrensninger: force-stop stopper bakgrunnsarbeid, og WorkManager garanterer ikke nøyaktig 15-minutters synk.

**Ferdig når:** En serverlagret testpåminnelse utløser ett synlig varsel på tableten med skjermen av, og endring/sletting av påminnelsen avlyser den gamle leveringen etter synk. Unit-tester alene er ikke tilstrekkelig.

## 2. Rett pakkeidentitet

- [x] Sett både `applicationId` og `namespace` til **`com.vstokke.memos`**.
- [x] Flytt Kotlin- og testpakker fra `com.vstokke.memos` til `com.vstokke.memos`.
- [x] Oppdater imports, manifestreferanser, alarm-actions, eventuelle URI-identiteter, dokumentasjon og installasjons-/releasekommandoer. Søk etter alle gamle identifikatorer.
- [x] Behold appnavnet Memos Pocket med mindre brukeren ber om noe annet.
- [x] Behandle dette som en ny Android-app: den kan ikke oppdatere den gamle pakken direkte. Ny app har egen sandbox, Keystore, tillatelser og lokale leveringsdata.
- [x] Ikke eksporter eller kopier token. Brukeren logger inn i den nye pakken og gir nødvendige tillatelser på nytt.
- [ ] Unngå dobbeltvarsling mens begge pakkene er installert. Deaktiver gammel påminnelsesplanlegging som del av kontrollert overgang; ikke avinstaller gammel app uten godkjenning.
- [ ] Test at allerede leverte påminnelser ikke overraskende leveres på nytt ved overgangen. Avklar håndtering av catch-up ved første tilkobling.
- [x] Bruk den korrigerte ID-en ved Play-opprettelse og signering. Ikke generer eller bytt permanent produksjonsnøkkel som del av UI-arbeidet.

**Ferdig når:** Den installerte og bygde appen identifiseres som `com.vstokke.memos`, uten gamle kodepakker eller utilsiktet parallell varsling.

## 3. Erstatt hele UI-en med web-Memos som referanse

### Referanse, ikke et nytt designkonsept

- [ ] Studer faktisk web-Memos: feed, memoeditor, memomeny, navigasjon og innstillinger. Bruk kildekoden og sammenlign skjermbilder; ikke gjett ut fra produktnavnet.
- [x] Bruk `/home/ove/projects/private/memos/web/src/themes/default.css` og `default-dark.css` som farge- og typografireferanse, samt komponentene under `web/src/components/` for oppbygning og handlinger.
- [x] Oversett de eksisterende designtokenene til Compose. System-sans, kompakt applikasjonstypografi, nøytrale flater, diskrete kantlinjer og Memos' faktiske aksentfarge. Ikke bruk vilkårlig Material-standardtema eller dynamiske systemfarger som endrer uttrykket.
- [x] Fjern nåværende kampanje-/landingpage-uttrykk: store slagord, «Carry the thought», «Quiet by design», «A quiet pocket», dekorative nummer, BrandMark-streker, overdrevne mellomrom og mørk reklameaktig editorflate.
- [x] Ingen nye fontpar, hero, gradienter, glass, dekorative dashboards eller generert grafikk. Dette er et notatverktøy.

### Skjermer og navigasjon

- [x] Enkel innloggingsskjerm: serveradresse, PAT, tilkobling og nøktern tredjepartsinformasjon.
- [x] Feed: kompakt topplinje, oppdatering, ny memo og lesbare memos med kontekstmeny. Ikke bruk hilsener og kontoinformasjon som hovedinnhold.
- [ ] Memo-detalj og editor: Markdown-lesing, redigering, lagre/avbryt og tydelig lagringsstatus. Bevar utkast ved feil og rotasjon; varsle før ulagrede endringer forkastes.
- [x] Mobil: meny/drawer til navigasjon. Tablet: sidepanel når plassen tillater det og begrenset lesebredde; ikke strekk all tekst over hele skjermen.
- [x] Tilgjengelig innstillingsinngang via navigasjonen. **Ingen Disconnect på forsiden.**
- [ ] Tilbakeknapp, tastatur, fokus, touchmål, skjermleserbeskrivelser, store tekststørrelser og system-insets skal fungere.
- [ ] Lys og mørk modus skal begge følge web-Memos. Visuelle sammenligninger skal gjøres før ferdigmelding.

### Filer som skal erstattes eller deles opp

- `ui/MemosPocketScreen.kt`: erstatt dagens monolitt med separate innlogging-, feed-, detalj-, editor- og innstillingsskjermer samt felles navigasjon.
- `ui/Theme.kt`: erstatt eksisterende visuelle token og typografi med Memos-baserte verdier.
- `ui/MainViewModel.kt`: del skjermtilstand etter behov; ikke legg all CRUD, innstillinger og navigasjon i én voksende klasse.
- `MainActivity.kt`: koble nytt appskall, tillatelser og navigasjon.
- `ui/MarkdownText.kt`: vurder og behold korrekt rendringslogikk, men tilpass stilen. Ikke slett fungerende datalag eller sikkerhetskode som del av UI-utskiftingen.

**Ferdig når:** Feed, editor og innstillinger ser ut som samme produktfamilie som web-Memos på tableten, uten elementene fra det forkastede designet.

## 4. Innstillinger

- [x] Egen skjerm, ikke en samling kontohandlinger på forsiden.
- [x] Konto: serveradresse og bruker; aldri vis token som ren tekst.
- [x] Varsler: serverstøtte, tillatelse, kanal, eksakte alarmer, siste vellykkede synk, neste planlagte påminnelse og testhandling.
- [x] Utseende: system/lys/mørk, lagret lokalt.
- [x] Om: appversjon og tydelig uoffisiell tredjepartsstatus.
- [x] Disconnect/logg ut nederst under konto, med bekreftelse. Avlys arbeid og alarmer, fjern lokale legitimasjonsdata/cache og rydd appens aktive varsler. Ikke slett servermemos.
- [x] Ikke legg inn dekorative innstillinger uten implementert virkning.

**Ferdig når:** Innstillinger kan nås fra normal navigasjon, alle valg fungerer og lagres, og utlogging finnes bare der.

## 5. Full CRUD på memos

### API og sikkerhet

- [x] Les gjeldende proto og serverimplementasjon før endring. Bruk samme kontrakt mot original Memos og forken; bare reminders er kapabilitetsstyrt.
- [x] `GET /api/v1/memos`: paginering utover dagens 20 memos, refresh, tomtilstand og gjenopprettbar feiltilstand.
- [x] `GET /api/v1/{name=memos/*}`: åpne en bestemt memo, også fra varsling selv om den ikke ligger i siste feedside.
- [x] `POST /api/v1/memos`: opprett, med PRIVATE som trygg standard og eksplisitt valg av støttet synlighet.
- [x] `PATCH /api/v1/{memo.name=memos/*}` med presis `updateMask`: rediger innhold og synlighet; sett, flytt og fjern `reminderTime` på støttede servere. Ikke send ukjente reminder-felt til upstream.
- [x] `DELETE /api/v1/{name=memos/*}`: slett med bekreftelse. Ikke bruk automatisk `force=true` hvis serveren beskytter relasjoner eller tilknyttede data.
- [x] Arkiver/gjenopprett gjennom `state` og gi tilgang til arkiv. Arkivering er ikke en erstatning for sletting.
- [ ] Bevar alle felt som ikke inngår i den aktuelle endringen, inkludert vedlegg og space-tilhørighet. Implementer vedlegg og space-handlinger etter funksjonsmatrisen for webparitet; ikke bruk tidligere MVP-avgrensninger til å utelate dem.
- [x] Ikke overskriv samtidig webredigering uten varsel: hent fersk memo før lagring, sammenlign basis og tilby å laste inn serverutgaven/beholde utkast. Ikke påstå atomisk konfliktvern hvis serveren ikke støtter det.

### UI og lokal konsistens

- [ ] Memomeny med minimum webklientens handlinger: pin/unpin, rediger, kopier lenke/innhold, oppgavehandlinger, arkiver/gjenopprett, flytt og slett, basert på eierskap og serverens tillatelser. Åpning av memo-detalj skal også fungere.
- [x] Behold brukerens tekst ved nettverksfeil; vis feil uten token eller rå serverrespons.
- [x] Oppdater feed og detalj etter vellykket serverrespons. Ikke skjul en memo permanent før sletting er bekreftet.
- [x] Oppdater eller fjern lokal påminnelse, alarm og aktivt varsel ved relevant CRUD-handling.
- [x] Serialiser kontobytte, utlogging, refresh og skriving slik at svar fra gammel konto aldri havner i ny konto.
- [x] Ikke auto-retry oppretting på en måte som kan lage duplikater etter tvetydig nettverksfeil.

**Ferdig når:** Brukeren kan opprette, finne eldre, åpne, redigere og slette egne memos på både upstream og fork; resultatet stemmer i webklienten. Påminnelser følger de samme endringene uten dubletter.

## 6. Verifikasjon og leveranse

- [x] API-tester: upstream uten capability, fork med capability, ukjente JSON-felt, auth-feil, pagination, get/create/patch/delete, presis field mask og reminder-clear.
- [ ] Repository-/databasetester: kapabilitetsendring, mislykket refresh, kontoovergang, CRUD-cache og påminnelsesopprydding.
- [ ] UI-tester: innstillinger tilgjengelig, ingen Disconnect på feed, editor beholder utkast, sletting krever bekreftelse og reminder-kontroller skjules uten støtte.
- [x] Bygg og lint: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease`.
- [ ] Installer kontrollert på tableten. Ikke avinstaller etter testen; brukeren skal kunne fortsette å teste.
- [ ] Gjennomfør faktisk CRUD og varslingsflyt med egne, tydelig navngitte testmemos. Ikke bruk eller slett eksisterende private memos som testdata.
- [ ] Sammenlign nye skjermbilder med web-Memos: tablet portrett/landskap, smal mobilbredde, lys/mørk modus og åpent tastatur.
- [ ] Rapporter separat hva som er automatisk testet, prøvd på enhet og fortsatt uavklart. «Bygger» betyr ikke «virker».

## Avgrensning og sikkerhet

- Denne planen gir ingen grunn til å endre eller deploye Memos-serveren utover den allerede avtalte additive capability-kontrakten. Koordiner eventuelle serverfunn med Memos-agenten.
- Behold HTTPS, Keystore-kryptering, redirect-beskyttelse og fravær av tokens i logger, URL-er og delt UI-tilstand.
- Ingen produksjonsdata slettes, ingen automatisk avinstallasjon, og ingen nye distribusjons-/signeringsbeslutninger gjemmes i denne feilrettingen.

## Milepæl 1 — implementert, ikke full app-paritet

### Diagnose og sikkerhet

Opplysninger fra foreldresesjonens diagnose (ikke en ny tablet-test fra implementeringsarbeidet):
- Offentlig `https://memos.vstokke.com/api/v1/instance/profile` var fortsatt commit `bbe3fc141132` uten `memoReminderTimeSupported`.
- Den eksisterende tablet-pakken er `com.vstokke.memopocket`; `POST_NOTIFICATIONS granted=false`, `SCHEDULE_EXACT_ALARM granted=true`.
- Ingen matchende planlagt alarm ble funnet. Disse er separate, bekreftede blokkeringer/observasjoner; én testmemo er ikke fulgt gjennom hele kjeden, og årsaken til akkurat brukerens test er derfor ikke bevist.
- Ingen adb install/uninstall/clear, ingen tokenuttrekking, ingen tømming av logger, ingen server-/søskenrepo-endringer, ingen commit/push/deploy eller staging er utført.
- Den nye pakken `com.vstokke.memos` er en separat app med egen sandbox, Keystore, tillatelser og leveringshistorikk. Ingen automatisk token- eller datamigrering. Den gamle installasjonen og innloggingen er urørt.
- Før installasjon må kontrollert overgang avtales: gammel planlegging må deaktiveres uten avinstallasjon/datarydding, brukeren logger inn på nytt og gir tillatelser, og første tilkoblings 24-timers catch-up må vurderes for å unngå dobbeltvarsling. Dette er **ikke** gjennomført eller godkjent her.

### Implementeringsfunn

Tidligere refresh kansellerte egen WorkManager-jobb ved manglende støtte og ryddet databasen først etter nettverkskall til feed. Det kunne etterlate foreldede lokale påminnelser hvis feed feilet. Nå lagres/observeres bekreftet capability og lokal opprydding gjøres før feedkallet; refresh kansellerer ikke egen worker. Periodisk kontroll beholdes også uten støtte slik at senere serveroppgradering kan oppdages. Profilfeil beholder sist kjente capability og vises som feil, ikke som bekreftet fravær.

Varslingsinnstillinger viser app/kanal, exact-alarm-status, siste vellykkede synk og neste lokalt planlagte alarm, med Android-innstillinger og eksplisitt NotificationManager-test. En innsendt test er ikke bevis på synlig varsel eller serverlagret/alarmdrevet levering. Ledger kvitterer nå først etter hver vellykkede publisering. Ikke-publiserte elementer kan forsøkes igjen etter prosessdød; full tidsstempelpresisjon bevares. Krasj mellom publisering og kvittering kan fortsatt gjenta innsending, så stabil varslings-ID og only-alert-once brukes uten garanti om exactly-once. Endring/sletting/arkivering rydder relevante lokale alarmer og aktive varsler; ekstern opprydding krever vellykket synk.

API-kontrakten er lest i proto, memo-handler og generert gateway. Viktig: JSON-body bruker `reminderTime`, men REST-query `updateMask` må inneholde proto-feltnavn **`reminder_time` / `update_time`** fordi grpc-gateway splitter queryverdien uten camelCase-konvertering. Dette er også kjørt mot faktisk grpc-gateway 2.30.0 i et isolert `/tmp`-program; ingen søskenfiler ble endret. Reminder-clear sender masken og utelater timestamp. Smale masker sender aldri tilbake vedlegg, relasjoner, space eller ukjente felt ved innholdsredigering.

Alle kontooperasjoner bruker samme repository-mutex. UI-operasjoner er serialisert og bærer forventet server/brukeridentitet. Før mutasjon hentes memo på nytt og kjente basisfelt sammenlignes. Konflikt beholder utkast; brukeren kan kopiere utkast og eksplisitt laste serverutgaven med bekreftelse. Dette er **ikke atomisk konfliktvern**, og ukjente felt er ikke med i sammenligningen (men overskrives heller ikke av masken). Oppretting blir ikke automatisk forsøkt på nytt av app eller OkHttp.

### Web → Android-paritetsmatrise

Referanser er lest direkte fra `web/src/themes/default.css`, `default-dark.css`, `pages/Home.tsx`, `MemoView/{MemoView.tsx,constants.ts}`, `MemoEditor/index.tsx`, `MemoActionMenu/{MemoActionMenu.tsx,hooks.ts,MemoMoveDialog.tsx}`, `MemoCommentSection.tsx`, `Settings/settingSections.ts` og tilhørende proto/handler. Fargene er sRGB-konverteringer av web-tokenene: varm nøytral bakgrunn, hvite kort, blå primærfarge; graphite i mørk modus. Dette er kildebasert tilpasning, **ikke** skjermbildeverifisert visuell paritet.

| Webfunksjon | Android i milepæl 1 | Neste arbeid / begrensning |
|---|---|---|
| Feed, pinned-first, paginering | Implementert, 30 per side, refresh, lesbar feil/tomtilstand | Live upstream/fork-verifikasjon; refresh går tilbake til første side |
| Navigasjon | Drawer på smal skjerm, sidepanel fra 840 dp, maks lesebredde 760 dp | Tablet/mobil/skriftstørrelse/tastatur/skjermleser må testes visuelt |
| Lys/mørk og innstillinger | System/lys/mørk lagres; konto/varsler/om; Disconnect kun i Settings med bekreftelse | Web brukerpreferanser, profilredigering og øvrige brukerinnstillinger gjenstår |
| Memo-detalj / varslingsåpning | GET av memo utenfor feed, selekterbar tekst, egen detalj | Live intent-/prosessrestart-test; full Markdown/GFM, klikkbare lenker og media gjenstår |
| Ny memo / editor | Markdown-tekst, forhåndsvisning, PRIVATE standard, eksplisitt synlighet, reminder-set/clear, lagringsstatus | Full formatting toolbar, vedlegg, lyd, metadata/location og persisted process-death drafts gjenstår |
| Utkast og konflikt | ViewModel bevarer ved rotasjon/nettverksfeil/refresh, forkastbekreftelse, copy draft, eksplisitt server-reload | Ingen diskpersistens ved prosessdød; ingen atomisk server-precondition |
| Pin/unpin | Serverlagret, ikke for kommentarer/arkiv | Live varighet/sortering i web må kontrolleres |
| Edit | Presise content/visibility/reminder-masker og preflight | Full editorparitet gjenstår |
| Copy link/content | Eksakt Markdown eller serverbase + memo-resource; ingen token/synlighetsendring | Kanonisk profil-instanceUrl/alternative proxyruter må verifiseres |
| Archive/restore | Serverlagret state og tilgjengelig arkiv | Live kontroll gjenstår |
| Delete | Bekreftelse; ingen force; behold synlig memo ved feil | Serverens relasjons-/vedleggsbeskyttelser må testes med isolerte testdata |
| Menyrettigheter | Forfatter-eierskap, kommentar- og arkivregler | Serveren er autoritet for medlemskap. Web MemoView gir superuser readonly-unntak, men gjeldende handler tillater bare forfatter; Android følger handler og gir ikke admin ekstra rettigheter |
| Task actions | **Gjenstår**, ikke falske menyvalg | Parserbasert GFM-kildeområde-toggle, enkeltoppgaver og check/uncheck all; må bevare kodeblokker/øvrig Markdown (web bruker AST, ikke blind regex) |
| Move | **Gjenstår**, ikke falskt menyvalg | List tilgjengelige spaces, medlemskap/writable-regler og space+visibility-masker; SPACE-flytting krever eksplisitt audience, uttrekk PRIVATE |
| Søk/filtrering, tags, saved views | **Gjenstår** | CEL-filter, pagination-reset, tag-/dato-/visibility-/space-filter; kalender og Explore |
| Vedlegg | **Gjenstår**, serverfeltene overskrives ikke | Upload/library, sikre authenticated downloads, visning, tilknytning/frakobling, metadata og beskyttet sletting |
| Spaces | **Gjenstår**, eksisterende tilhørighet beholdes | Velger, oppretting, medlemskap/invitasjoner, flytting, avgrenset feed |
| Kommentarer, relasjoner, reaksjoner | **Gjenstår** | Paginerte kommentarer, egen reply-editor, parent/read-tilgang, reaksjoner og relasjoner |
| Deling | Bare copy link, **ikke full deling** | Opprett/list/revoke shares, shared-reader, bildeeksport; ikke gjør privat memo offentlig for kopiering |
| Inbox / web notification prefs | **Gjenstår** | Skilles fra lokale server-reminder-varsler og Android-tillatelse |
| Brukerkonto/PAT-/tag-/webhook-preferanser | **Gjenstår** | Ikke dekorative innstillinger; bruk eksisterende API med tokenhygiene |
| Serveradministrasjon | Ikke implementert i denne milepælen | Egen administrativ scope: instance/member/storage/SSO/AI/resource stats; ikke blandes med ordinær memo-/space-autoritet |

### Automatisk validering og resterende akseptanse

- [x] API/domain/repository/ViewModel-regresjonstester: 40 tester, 0 feil (ingen emulator/device-UI-test).
- [x] Rettet reviewfunn: individuell leveringskvittering etter publisering, presise reminder-timestamps, kontobundne varslingslenker og feedstyrt arkivnavigasjon.
- [ ] Ny SQLite-instrumenteringstest er kompilert, men må kjøres: avbrutt leveringsbatch, gjenåpning av database og submillisekund-presisjon. Ingen endring av tableten utført.
- [x] Etter reviewrettelser: unit-tester, lint, debug/release APK, AAB og test-APK bygger.
- [x] `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease`.
- [x] Bounded build-/feillogger og gateway-bevis i `validation/`; Gradle HTML/XML-rapporter under `app/build/`.
- [ ] Faktisk testmemo server → DB → alarm → receiver → ledger → synlig NotificationManager-varsel på tablet med skjermen av.
- [ ] Offline, bakgrunn, prosessrestart, boot, tidssone, force-stop/reopen, 24-timers catch-up, redigering/fjerning og ingen dubletter på enhet.
- [ ] Live CRUD på upstream og fork; ingen produksjonsmemos brukt som testdata.
- [ ] Visuelle sammenligninger lys/mørk, tablet portrett/landskap, smal mobil, stor tekst og åpent tastatur.
- [ ] Database-/AlarmManager-instrumentering og Compose-interaksjonstester (repository-testene bruker mocks; ViewModel-testene verifiserer tilstand, ikke faktisk layout).

Artefakter: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release-unsigned.apk`, `app/build/outputs/bundle/release/app-release.aab`. Release-signering er ikke opprettet/endret; usignerte release-artefakter er ikke distribusjonsklare. Ingen APK er installert.

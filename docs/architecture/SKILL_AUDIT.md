# SKILL AUDIT — FASE 0 backend

> Data: 19/10/2026 · Fase: **0 (solo analisi)** · Stato: bozza da approvare, non ancora committata.
> Regola: sono elencate solo le skill e le fonti **lette davvero**. Quello che non era raggiungibile è dichiarato.

## 1. Skill disponibili nella sessione (lette)

| Skill | File letti | Cosa applichiamo | Limiti / adattamenti |
|---|---|---|---|
| `java-spring-review` | `SKILL.md`, indice di `references/` e `patterns/`, `references/java-versions.md` §3, `patterns/testing-recipes.md` §4 | **Passo 0** (leggere pom/config prima di scrivere codice); le 6 modalità (useremo IMPLEMENT e TEST); ordine di review per gravità; **anti-overengineering** (niente interfacce con un'unica implementazione, niente mapper generati per 3 campi); formato di consegna in 10 punti; ricette CRUD verticale per il menu (FASE 4) | La tabella versioni arriva a **Spring Boot 3.x**: non copre Boot 4.x (oggi su Maven Central l'ultima è **4.1.1**). Se scegliamo Boot 4 andranno verificati sulla documentazione ufficiale Jackson 3, Spring Framework 7 e i cambi di starter. La ricetta repository usa `@DataJpaTest` con **DB in memoria**: per noi non basta, perché `tstzrange`, `EXCLUDE USING gist`, `FOR UPDATE SKIP LOCKED` esistono solo in PostgreSQL → test di integrazione su **PostgreSQL reale** (Testcontainers o istanza locale). |
| `microservices-architecture` | `SKILL.md`, indice `references/`, `patterns/outbox-saga-java.md` §2 | Principio «non introdurre un pattern prima di aver nominato il problema» → **monolite modulare** (già approvato); le 7 domande per ogni pattern; **Transactional Outbox** (salvataggio stato + messaggio nella stessa transazione); nota «l'outbox garantisce *at-least-once*, non *exactly-once*» → il consumer deve essere idempotente | Il pattern della skill fa polling con `findTop100…` e pubblica su un **broker**: noi non abbiamo broker. Adattamento: worker in-process con `SELECT … FOR UPDATE SKIP LOCKED`, `business_event_key` **unique**, backoff, stato `DEAD` logico. **Non applicabili**: Kafka, Saga, Spring Cloud, discovery, gateway (`messaging-kafka.md`, `spring-cloud-setup.md`). |
| `docker-kubernetes-devops` | `SKILL.md`, indice `patterns/`, `patterns/compose-stacks.md` §1 | **Preflight** (porta, versione Java, nome jar, profili, variabili) prima di ogni Dockerfile/Compose; Compose «app + PostgreSQL» con **healthcheck**, volume persistente, credenziali da `.env` mai in chiaro | Kubernetes, Helm, GitOps **fuori perimetro** (vietati dal master plan). Il Dockerfile Spring servirà solo in FASE 8 se l'hosting scelto lo richiede. |
| `react-development`, `react-state-redux` | lette nella fase mockup | Struttura componenti esistente; stato derivato; niente Redux finché Context + `useReducer` basta | Per il passaggio alle API (FASE 3–4) serve decidere se aggiungere una libreria di cache dati (vedi DECISION_LOG D-15). |
| `ui-ux-design` | letta nella fase mockup | Vincolo: **non cambiare l'estetica approvata** | Rilevante solo per la protezione del mockup. |

## 2. Repository e fonti esterne

| Fonte | Esito | Uso nel backend |
|---|---|---|
| `antonio-de-santis-dev/Skill-programmazione-` | clonata e letta (contiene le stesse skill Java/React/UI della sessione) | come sopra |
| `antonio-de-santis-dev/Formazione` | clonata e letta nella fase mockup | lezione «Modular Monoliths» (conferma la scelta); progetti JHipster come riferimento per RBAC con `@PreAuthorize`; il modello di informativa privacy **appartiene a un altro cliente**: non riutilizzabile |
| `ui-ux-pro-max-skill`, `awesome-design-md`, `taste-skill`, `impeccable`, `emilkowalski/skills` | clonate e lette nella fase mockup | nessuno diretto (UI già approvata) |
| `microsoft/playwright` | usato come strumento (1.56.1) nei test del mockup | E2E del frontend collegato alle API (FASE 3+) |
| `playwright.dev/agent-cli/introduction` | **NON raggiungibile** dal container (proxy 403 / ENOTFOUND) | non usata; contenuto non inventato |
| Instagram ufficiale | non consultato | nessuno: nessun dato preso da lì |

## 3. Ambiente di sviluppo verificato (19/10/2026)

| Strumento | Esito |
|---|---|
| Java | OpenJDK **21.0.12** ✅ |
| Maven | **3.9.11** ✅ (Maven Central raggiungibile: HTTP 200) |
| Gradle | 8.14.3 (non necessario se usiamo Maven) |
| PostgreSQL | binari server e client **16.15** presenti (`initdb`, `pg_ctl`, `psql`) ✅ |
| Docker | CLI 29.8.2 presente, **daemon non in esecuzione** ⚠️ → Testcontainers non funziona così com'è; alternative in DECISION_LOG D-17 |
| Node | 22.22.0 ✅ (mockup) |
| Ultime versioni su Maven Central | Spring Boot **4.1.1** (linea 3.5 → 3.5.16), Flyway 13.10.3, Testcontainers 2.0.5 |

## 4. Pratiche che entreranno nel codice (dalle skill)

1. Leggere il progetto prima di generare; dichiarare le assunzioni di versione.
2. DTO separati dalle entity, errori `problem+json` centralizzati, `@Valid` su ogni input.
3. Iniezione via costruttore; `@Transactional` solo dove serve (scritture e letture coerenti).
4. Test sui percorsi di errore, non solo sul percorso felice; test di concorrenza su PostgreSQL reale.
5. Outbox at-least-once + deduplica lato consumer.
6. Nessun pattern distribuito senza un problema concreto da risolvere.

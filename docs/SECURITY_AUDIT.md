# Security Audit — kturner75/bible-reader-2 (readthekjv.com)

**Date:** 2026-08-11  
**Scope:** OWASP Top 10–style review of this repository and the deployed app at https://readthekjv.com  
**Method:** Static review of auth, access control, injection/XSS surfaces, config/secrets, client JS, and dependency advisories (OSV), with live header/endpoint probes for confirmation.  
**This PR:** Documentation only — no vulnerability fixes (except noting one accidental probe account for cleanup).

---

## Executive summary

The app’s user-data APIs generally scope queries by authenticated user id (no confirmed IDOR on notes, library, rhythms, or collections). The highest-priority issues are **account pre-hijacking** (password registration without email verification combined with automatic Google OAuth account linking by email), **unauthenticated OpenAI TTS cost amplification** on public `/api/audio/**` endpoints (confirmed enabled in production), and **CSRF protection disabled for all `/api/**`** while session + 30-day remember-me cookies authenticate state-changing calls. Spring Boot **3.2.1** pulls an outdated Tomcat/Security stack with many published CVEs; most high-severity advisories do not match this app’s usage patterns, but the platform version is past due for upgrade. No live API keys or private keys were found committed in the tree.

---

## Findings (severity-sorted)

| ID | Severity | Title | Backlog priority |
|----|----------|-------|------------------|
| H1 | High | Account pre-hijacking via unverified email + Google OAuth auto-link | P0 |
| H2 | High | Unauthenticated TTS generation / cost amplification (`/api/audio/**`) | P0 |
| H3 | High | CSRF disabled for all `/api/**` with cookie sessions + always-on remember-me | P0 |
| M1 | Medium | No rate limiting / lockout on auth, registration, or paid AI endpoints | P1 |
| M2 | Medium | Default `REMEMBER_ME_KEY` fallback committed; 30-day always-remember cookies | P1 |
| M3 | Medium | PostgreSQL `sslmode` defaults to `disable` | P1 |
| M4 | Medium | Spring Boot 3.2.1 / Tomcat 10.1.17 / Security 6.2.1 outdated | P1 |
| M5 | Medium | Google OAuth does not check `email_verified` | P2 |
| L1 | Low | Lucene `QueryParser` accepts raw user queries (DoS / unexpected syntax) | P2 |
| L2 | Low | Chapter TTS accepts arbitrary book path segment (bucket spam / waste) | P2 |
| L3 | Low | Missing Content-Security-Policy (and related modern headers) | P2 |
| L4 | Low | OAuth-only users get `{noop}GOOGLE_OAUTH_NO_PASSWORD` placeholder hash | P2 |
| L5 | Low | API error responses can echo exception messages | P3 |
| I1 | Info | Account enumeration via registration `409` | P3 |
| I2 | Info | Default datasource username `kevinturner` in properties | P3 |
| I3 | Info | Weak password policy (length only) | P3 |

---

### H1 — Account pre-hijacking via unverified email + Google OAuth auto-link

| | |
|---|---|
| **Severity** | High |
| **OWASP / CWE** | A07 Identification and Authentication Failures · CWE-287 / CWE-348 |
| **Locations** | `AuthService.register` (`src/main/java/com/readthekjv/service/AuthService.java`); `OAuth2UserServiceImpl.loadUser` (`src/main/java/com/readthekjv/service/OAuth2UserServiceImpl.java` ~L48–52); `RegisterRequest` (no verification step); `POST /api/auth/register` permitAll in `SecurityConfig` |
| **Impact** | Attacker registers a password account for a victim’s email (no ownership proof). When the victim later uses “Sign in with Google” for that email, the app **links `google_sub` onto the attacker’s row**. The victim lands in the attacker-controlled account; the attacker retains password login indefinitely. |
| **Evidence** | OAuth path: `userRepository.findByEmail(email).ifPresentOrElse(existing -> { existing.setGoogleSub(sub); … })` with no verification challenge. Registration only checks uniqueness + `@Email`. Live probe: `POST /api/auth/register` on production accepts new accounts without verification. |
| **Remediation** | Require email verification before the account is fully usable; **do not** auto-link Google to an existing password account without a confirmed session or verified-email proof; prefer linking only when `email_verified=true` and/or after re-auth. Consider blocking password registration for emails that later authenticate via Google until verified. |
| **Priority** | **P0** |

---

### H2 — Unauthenticated TTS generation / cost amplification

| | |
|---|---|
| **Severity** | High |
| **OWASP / CWE** | A04 Insecure Design · CWE-770 (Allocation Without Limits) |
| **Locations** | `SecurityConfig` permitAll for `/api/audio/**`; `TtsController.getAudio` / `getChapterAudio`; `TtsService.getAudioUrlForVerse` + `triggerPrefetch` (`prefetch-count` default 10); production `GET /api/tts/status` → `{"enabled":true}` |
| **Impact** | Anyone can trigger OpenAI TTS + DigitalOcean Spaces uploads without auth. A hit on a verse also **background-prefetches the next N verses**, amplifying spend. Chapter endpoint similarly generates/uploads on cache miss. Direct financial DoS against the OpenAI/Spaces bill; also fills the public bucket (`acl("public-read")`). |
| **Evidence** | Live: `GET https://readthekjv.com/api/audio/1` returns a CDN URL; TTS status enabled. Code path generates on miss then calls `triggerPrefetch`. |
| **Remediation** | Require auth and/or strict rate limits (IP + user); only return CDN URLs for pre-generated objects from a public path; move generation to a trusted job/admin path; cap concurrent TTS; validate `book` against known Bible book names; consider disabling on-demand generation in production. |
| **Priority** | **P0** |

---

### H3 — CSRF disabled for all `/api/**` with cookie sessions

| | |
|---|---|
| **Severity** | High |
| **OWASP / CWE** | A01 Broken Access Control · CWE-352 |
| **Locations** | `SecurityConfig` L35–36: `csrf.ignoringRequestMatchers("/api/**")`; form login + logout under `/api/auth/**`; remember-me always issued (`RememberMeConfig.setAlwaysRemember(true)`, 30 days) |
| **Impact** | State-changing API calls (`POST/PATCH/DELETE` for library, notes, rhythms, memorization, logout, etc.) rely on session/remember-me cookies without CSRF tokens. Comment claims “same-site session cookies,” but remember-me cookies from Spring Security 6.2.1’s `AbstractRememberMeServices#setCookie` set `Secure` + `HttpOnly` only — **no `SameSite` attribute**. Browser defaults (Lax) reduce classic cross-site POST risk in modern Chrome/Firefox, but this is brittle (older clients, future cookie policy changes, non-browser clients, mistaken `SameSite=None`). |
| **Remediation** | Re-enable CSRF for cookie-authenticated `/api/**` (or move authed APIs to header-based CSRF / double-submit); set `SameSite=Lax` or `Strict` explicitly on session + remember-me cookies; consider not using remember-me for CSRF-sensitive actions without additional binding. |
| **Priority** | **P0** |

---

### M1 — No rate limiting / lockout on auth or paid AI endpoints

| | |
|---|---|
| **Severity** | Medium |
| **OWASP / CWE** | A07 / A04 · CWE-307 / CWE-770 |
| **Locations** | `POST /api/auth/login`, `POST /api/auth/register`; `POST /api/memorization/queue/{id}/recite` (Whisper, up to 25 MB); public `/api/audio/**` (see H2) |
| **Impact** | Credential stuffing and registration spam are unconstrained at the app layer. Authenticated users can burn STT budget via `/recite`. Combined with H2, anonymous TTS abuse is unconstrained. |
| **Remediation** | Add IP/user rate limits (reverse proxy or Spring filter), CAPTCHA/Turnstile on register/login, account lockout / progressive delays, and per-user quotas on STT/TTS. |
| **Priority** | **P1** |

---

### M2 — Default remember-me signing key fallback; long-lived always-on cookies

| | |
|---|---|
| **Severity** | Medium |
| **OWASP / CWE** | A02 Cryptographic Failures · CWE-1188 / CWE-613 |
| **Locations** | `application.properties` L82–83: `security.remember-me.key=${REMEMBER_ME_KEY:dev-remember-me-key-change-in-prod}`; `RememberMeConfig` (30 days, `setAlwaysRemember(true)`) |
| **Impact** | If production omits `REMEMBER_ME_KEY`, the publicly known default is used. Persistent remember-me still requires DB series/token matches (limits pure cookie forgery), but a shared/default key is unsafe operational practice. Always-on 30-day cookies expand session theft window (XSS, local malware, shared devices). |
| **Remediation** | Fail fast at startup if `REMEMBER_ME_KEY` is missing/default in non-dev profiles; rotate key on deploy; consider opt-in remember-me and shorter TTL; bind cookies with explicit `SameSite`. |
| **Priority** | **P1** |

---

### M3 — PostgreSQL `sslmode` defaults to `disable`

| | |
|---|---|
| **Severity** | Medium |
| **OWASP / CWE** | A02 · CWE-319 |
| **Locations** | `application.properties` L44: `sslmode=${KJV_DB_SSLMODE:disable}` |
| **Impact** | Misconfigured production deploys may send DB credentials and row data in cleartext on the path to PostgreSQL (especially managed DB over a network). |
| **Remediation** | Default to `require`/`verify-full` outside local/dev; document required `KJV_DB_SSLMODE` for production. |
| **Priority** | **P1** |

---

### M4 — Outdated Spring Boot / Tomcat / Spring Security stack

| | |
|---|---|
| **Severity** | Medium |
| **OWASP / CWE** | A06 Vulnerable and Outdated Components |
| **Locations** | `pom.xml` parent `spring-boot-starter-parent` **3.2.1** → BOM: Spring Security **6.2.1**, Tomcat embed **10.1.17**, jackson-databind **2.15.3**, postgresql driver **42.6.0** |
| **Impact** | Large CVE inventory on Tomcat 10.1.17 (DoS, smuggling, etc.). Several *named* High/Critical advisories were checked against usage and **do not clearly apply** (see “Out of scope / not confirmed”), but remaining unpatched Tomcat/Boot issues and EOL pressure still warrant an upgrade. |
| **Remediation** | Upgrade to a current Spring Boot 3.2.x/3.3.x/3.4.x line that pulls patched Tomcat and Security; retest auth, OAuth redirects behind Nginx, and multipart STT. |
| **Priority** | **P1** |

---

### M5 — Google OAuth does not check `email_verified`

| | |
|---|---|
| **Severity** | Medium |
| **OWASP / CWE** | A07 · CWE-290 |
| **Locations** | `OAuth2UserServiceImpl` / `OAuth2SuccessHandler` — uses `email` + `sub` only |
| **Impact** | Best-practice gap. Google accounts normally verify email, but skipping `email_verified` weakens defense if claim sets ever differ or another IdP is added later. Compounds H1. |
| **Remediation** | Reject login when `email_verified` is missing/false; keep linking logic strict (H1). |
| **Priority** | **P2** |

---

### L1 — Lucene `QueryParser` on raw user input

| | |
|---|---|
| **Severity** | Low |
| **OWASP / CWE** | A03 Injection · CWE-400 |
| **Locations** | `LuceneIndexService.search` / `searchIds` — `QueryParser.parse(processedQuery)`; public `GET /api/search` |
| **Impact** | Users can exercise Lucene query syntax (wildcards, ranges, etc.) for expensive searches. Not SQL injection; data is static KJV text. Availability nuisance more than confidentiality. |
| **Remediation** | Escape special characters, use `SimpleQueryParser` / term queries, and tighten timeouts/limits (esp. `/api/search/ids` max 32000 when authenticated). |
| **Priority** | **P2** |

---

### L2 — Chapter TTS arbitrary `book` segment

| | |
|---|---|
| **Severity** | Low |
| **OWASP / CWE** | A04 · CWE-99 / CWE-770 |
| **Locations** | `TtsController.getChapterAudio` — only checks non-blank + chapter 1–150; `TtsService.getChapterKey` does `book.replace(" ", "_")` only |
| **Impact** | On-demand generation for non-existent books wastes API spend and creates odd public object keys under `audio/chapters/`. S3 keys are opaque (literal `../` is not filesystem traversal), so this is mainly abuse/spam, not classic path traversal RCE. |
| **Remediation** | Allowlist book names/ids from `BibleService.getBooks()`; see H2 for generation controls. |
| **Priority** | **P2** |

---

### L3 — Missing Content-Security-Policy

| | |
|---|---|
| **Severity** | Low |
| **OWASP / CWE** | A05 Security Misconfiguration · CWE-693 |
| **Locations** | Live responses include Spring Security defaults (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security`) but **no `Content-Security-Policy`**, `Referrer-Policy`, or `Permissions-Policy` observed on `https://readthekjv.com`. |
| **Impact** | Reduced defense-in-depth if a future XSS slips through. Current note/search rendering generally HTML-escapes user content before limited markdown transforms (positive). |
| **Remediation** | Add a tight CSP (default-src/script-src/style-src/connect-src for self + required CDNs); set Referrer-Policy and Permissions-Policy at Nginx or Spring headers. |
| **Priority** | **P2** |

---

### L4 — `{noop}` password placeholder for OAuth-only users

| | |
|---|---|
| **Severity** | Low |
| **OWASP / CWE** | A07 · CWE-259 |
| **Locations** | `UserDetailsServiceImpl` L26–28: null `passwordHash` → `"{noop}GOOGLE_OAUTH_NO_PASSWORD"` |
| **Impact** | **Not exploitable with the current `BCryptPasswordEncoder` bean** (Delegating `{noop}` matching is not active). Becomes Critical if the app switches to `PasswordEncoderFactories.createDelegatingPasswordEncoder()` without changing this sentinel. Also disables a clean “password login not allowed” signal. |
| **Remediation** | Use `{bcrypt}` of a random secret, or `User.builder().password(...).disabled(...)` / custom `AuthenticationProvider` that rejects password auth when `passwordHash == null`. Add a regression test. |
| **Priority** | **P2** |

---

### L5 — Exception messages returned to clients

| | |
|---|---|
| **Severity** | Low |
| **OWASP / CWE** | A04 · CWE-209 |
| **Locations** | e.g. `MemorizationController` recite handler: `"Transcription failed: " + e.getMessage()`; range parse errors include parser messages |
| **Impact** | Minor information leakage useful for probing internal failures (upstream API errors, etc.). |
| **Remediation** | Log server-side; return generic client errors. |
| **Priority** | **P3** |

---

### I1 — Registration account enumeration

| | |
|---|---|
| **Severity** | Informational |
| **OWASP / CWE** | A01 · CWE-204 |
| **Locations** | `AuthService.register` → `409` `"Email already registered"`; register UI surfaces this |
| **Impact** | Confirms whether an email is registered. Common tradeoff; pair with rate limits (M1). |
| **Remediation** | Uniform responses + email verification flow; rate limit. |
| **Priority** | **P3** |

---

### I2 — Default DB username in repo

| | |
|---|---|
| **Severity** | Informational |
| **Locations** | `application.properties`: `KJV_DB_USERNAME:kevinturner` |
| **Impact** | Username hint for local/prod misconfig; not a credential by itself (password from env). |
| **Remediation** | Use a neutral default like `readthekjv` / empty for prod-required env. |
| **Priority** | **P3** |

---

### I3 — Password policy is length-only

| | |
|---|---|
| **Severity** | Informational |
| **Locations** | `RegisterRequest`: `@Size(min = 8, max = 72)`; BCrypt used correctly for password accounts |
| **Impact** | Allows weak passwords (`password`, etc.). Max 72 aligns with BCrypt. |
| **Remediation** | Breach/HIBP checks or stronger policy; keep BCrypt (or upgrade to argon2/bcrypt via Delegating encoder carefully — see L4). |
| **Priority** | **P3** |

---

## Positive observations (not findings)

- User-owned resources generally use `findByIdAndUserId` / equivalent filters (library, sermon notes, rhythms, collections, plans enrollment).
- Password accounts use `BCryptPasswordEncoder`; register DTO validates email/password length.
- Public Bible data endpoints are intentionally public; verse/note HTML rendering uses `escapeHtml` / `escapeAttr` before limited markdown.
- Live site sends HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`.
- CORS does not appear wide-open (cross-origin `OPTIONS` to `/api/auth/me` → 403; no ACAO grant observed).
- Secrets are referenced via env vars (`OPENAI_API_KEY`, `DO_SPACES_*`, `X_*`, Google OAuth); `.env` is gitignored. No private keys/API secrets found in the working tree.
- Multipart STT has a 25 MB cap; range hydration caps at 500 verses.

---

## Out of scope / not confirmed

| Lead | Result |
|------|--------|
| SQL injection via Spring Data / JDBC | Repositories use parameterized JPQL/`@Query`. No raw string-concat SQL found. |
| PostgreSQL GHSA-24rp-q3w6-vc56 (line-comment SQLi) | Requires `preferQueryMode=simple` — not configured. **Not applicable.** |
| pgjdbc SCRAM CPU DoS (GHSA-98qh-xjc8-98pq) | Requires malicious DB server during auth. Low relevance to this architecture. |
| Jackson polymorphic type CVEs | No `enableDefaultTyping` / `@JsonTypeInfo` usage found. **Not applicable** as exploited here. |
| Spring Security GHSA-f3jh-qvm4-mg39 / GHSA-w3w6-26f2-p474 | Require direct `AuthenticatedVoter` / `isFullyAuthenticated(null)` use — **not present**. |
| Spring Boot CVE-2025-22235 (`EndpointRequest.to`) | No actuator/`EndpointRequest` usage found. **Not applicable.** |
| Tomcat partial-PUT RCE (GHSA-83qj-6fr2-vhqg) | Needs Default Servlet writes enabled — not the Spring Boot default. **Not confirmed applicable.** |
| Stored XSS via search `highlight` | Lucene `SimpleHTMLFormatter` wraps KJV text in `<mark>`; frontend inserts `highlight` as HTML. Safe while corpus is trusted; not a user-content XSS today. |
| Classic open redirect in `WebController` (`vid` query) | Redirect target stays under `/read?vid=…` (relative). Not confirmed as external open redirect. |
| SSRF | Outbound HTTP targets are fixed (OpenAI, xAI, X/Twitter, Spaces endpoint from config). No user-controlled URL fetch found. |
| Command injection / path traversal RCE | No `Runtime.exec` / user paths to filesystem. S3 key `../` is not FS traversal. |
| IDOR on `/api/passages/{id}` / collections | Ownership checks + global (`user IS NULL`) passages only; other users’ private rows not readable when tested via code paths. |
| Committed live production secrets | None found in tree; `deploy.sh` / `.env` gitignored (contents not reviewed). |
| Full penetration test / authz fuzz of every endpoint | Not performed. Focused code + selective live probes only. |

### Operator cleanup from this audit

During live registration probing, account **`test@example.com` (user id `10`)** was created on production at `2026-08-11`. Please delete that user (and cascade library rows) if it is not intentional.

---

## Suggested backlog order

1. **P0:** H1 (verify-email + safe OAuth link), H2 (TTS auth/rate/precompute), H3 (CSRF + explicit SameSite).  
2. **P1:** M1 rate limits, M2 remember-me key hard-fail, M3 DB SSL defaults, M4 Boot/Tomcat upgrade.  
3. **P2:** M5 `email_verified`, L1–L4 hardening.  
4. **P3:** L5 + informational cleanup (I1–I3).

---

## Appendix — component versions reviewed

| Component | Version in BOM / pom |
|-----------|----------------------|
| Spring Boot | 3.2.1 |
| Spring Security | 6.2.1 |
| Tomcat embed | 10.1.17 |
| Jackson databind | 2.15.3 |
| PostgreSQL JDBC | 42.6.0 |
| Lucene | 9.9.1 (direct) |
| AWS SDK S3 | 2.24.0 (direct) |

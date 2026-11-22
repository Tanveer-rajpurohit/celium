# Celium provider terms and permissions audit

Reviewed: **5 November 2026**. Research findings, not a legal opinion or permission certificate.

## What this covers

The checked-in [selected-source inventory](../SELECTED_SOURCES.json) records **24 extension artifacts** on 18 October 2026. [CeliumSources.kt](../app/src/main/java/com/lagradost/cloudstream3/ui/celium/CeliumSources.kt) currently exposes **26 catalog entries**: CNC Verse supplies three labels instead of one. A separate built-in [PornGamesHub integration](../app/src/main/java/com/lagradost/cloudstream3/ui/celium/game/PornGameHubProvider.kt) makes 27 static source labels across those screens. Celium Feed coordinates other providers; it is not another content license.

All 24 artifacts have a row below, including unresolved cases. This is **not a completed rights clearance of every underlying website, uploader, film, subtitle, or CDN**. CineStream alone references 63 source names in current upstream code. User-installed extensions can add more. No installed APK/device inventory was examined, and current upstream source is not proof of what a particular older binary does.

Four separate questions must stay separate:

1. **Extension code license:** can we use, modify, or distribute the adapter software?
2. **Website/API terms:** does its operator permit this method of accessing and displaying material?
3. **Content rights:** does the relevant rights holder permit this particular stream/download?
4. **Statutory exceptions:** does the particular use qualify under applicable copyright law?

Installing an extension inside an app is not inherently unlawful. Neither installation permission nor a GPL license answers questions 2–4. A contract restriction also is not, by itself, a court finding of copyright infringement.

## Findings that affect Celium

- No reviewed page establishes permission for Celium's **entire streaming, scraping, and download workflow** across this catalog. That does not prove every item or use infringes.
- Some sites allow personal use, limited downloads, links, or their own embedded player. Those permissions are narrower than permission to extract media into a separate player or redistribute it.
- **XVideos expressly restricts automated extraction without written authorization.** See its [terms, sections 2, 6 and 7.B](https://info.xvideos.net/legal/tos/).
- **CNC and GizliKeyif have unresolved code-license grants** in the material inspected. Public GitHub availability is not a substitute for a license.
- Netflix, Amazon Prime Video, and Disney+ Hotstar are **labels backed by CNC Verse** in our code. They do not establish an official partnership or authorized integration.
- **SubDL permits API integration under explicit conditions**, whereas several streaming sites publish only disclaimers. See the separate API section below.

## Extension software licenses

| Family | Selected artifacts | Evidence | Finding and remaining work |
| --- | ---: | --- | --- |
| Phisher | 8 | [README](https://github.com/phisher98/cloudstream-extensions-phisher/blob/master/README.md) | States GPL v3-or-later. Complete corresponding source for the exact selected binaries was not verified; inspected repository root does not publish those provider sources. |
| CSX | 2 | [README and source](https://github.com/SaurabhKaperwan/CSX/blob/master/README.md) | States GPL v3-or-later; provider source is public. README says maintenance is paused. Exact binary/source correspondence remains unverified. |
| CXXX | 2 | [README](https://github.com/phisher98/CXXX/blob/master/README.md), [LICENSE](https://github.com/phisher98/CXXX/blob/master/LICENSE) | GPL v3 text present; README specifies v3-or-later. Exact binary/source correspondence remains unverified. |
| GizliKeyif | 10 | [repository and README](https://github.com/Kraptor123/Cs-GizliKeyif) | No explicit license grant located in inspected root, README, build file, and selected source material. Need an applicable upstream license or maintainer permission before treating redistribution/adaptation as licensed. This is an evidence gap, not an infringement verdict. |
| CNC | 2 | [repository and README](https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension) | No explicit grant located in inspected root/README; selected provider implementation source not located there. Installation instructions and DMCA disclaimers do not supply a software license. |

For GPL-covered distribution, review the applicable notice, license, modification, and corresponding-source obligations. The precise binary distribution method matters; merely linking to a repository is not automatically sufficient. See [GPL v3 sections 4–6](https://github.com/phisher98/CXXX/blob/master/LICENSE). These obligations concern software, not permission to distribute films.

## All 24 selected extension artifacts

Versions below are the **local inventory**, not a claim that today's upstream builds match. A domain identified from current source is stronger evidence than a favicon or a similar brand name, but still is not an inspection of the installed binary.

| # | Artifact / version | Family | Website identification and terms result |
| --- | --- | --- | --- |
| 1 | MultiMoviesProvider / 55 | Phisher | Candidate `m1.multimovies.courses`, from CSX's shared URL list, **not confirmed for this Phisher binary**. Candidate [disclaimer](https://m1.multimovies.courses/disclaimer) and [DMCA page](https://m1.multimovies.courses/dmca) reviewed. No integration permission established. |
| 2 | HDhub4u / 57 | Phisher | Candidate `new10.hdhub4u.cl` redirected to `new11.hdhub4u.cl`. [Disclaimer](https://new11.hdhub4u.cl/disclaimer/) reviewed. Shared URL-list mapping is not proof for the selected Phisher binary. |
| 3 | Hdmovie2 / 6 | Phisher | Exact operating domain and applicable terms **unresolved**. Did not assign a lookalike site's terms. |
| 4 | Movies4u / 18 | Phisher | Exact operating domain and applicable terms **unresolved**. CSX utility currently maps its similarly named key to Google; this is not usable evidence of the Phisher provider's identity. |
| 5 | AniKoto / 6 | Phisher | Metadata favicon points to [anikototv.to](https://anikototv.to/). Homepage retrieved; no legal-policy link found in parsed anchors. Binary endpoint and applicable integration terms unverified. |
| 6 | HiAnime / 2 | Phisher | Metadata favicon points to [hianime.at](https://hianime.at/). Homepage retrieved; no policy link found in parsed anchors. Identity of other HiAnime domains/operators was not assumed equivalent. |
| 7 | Anichi / 29 | Phisher | Repository description mentions AllManga/Mkissa. Candidate [allmanga.to](https://allmanga.to/) homepage retrieved without a policy link found. Exact endpoints and their terms remain unresolved. |
| 8 | AnimeDekhoProvider / 72 | Phisher | Metadata favicon points to [animedekho.tv](https://animedekho.tv/). Homepage retrieved without a policy link found. Description also references additional anime sources; not separately cleared. |
| 9 | Moviesmod / 33 | CSX | Current shared configuration points to `moviesmod.tw`, also used by the desktop prototype. [Disclaimer](https://moviesmod.tw/site-disclaimer/) and [DMCA](https://moviesmod.tw/dmca/) reviewed. No grant for Celium's extraction/redistribution workflow established. |
| 10 | CineStream / 487 | CSX | Aggregator, **not a single service** with one set of terms. Current source lists 63 active registry names; appendix below. Underlying service terms and exact shipped configuration are not fully reviewed. |
| 11 | Beeg / 6 | GizliKeyif | Source maps to [beeg.com](https://beeg.com/). Homepage and guessed `/terms` retrieval timed out. Current terms **not verified**; guessed path not represented as official policy. |
| 12 | PornHat / 4 | GizliKeyif | Source maps to `pornhat.com`. [Terms](https://www.pornhat.com/terms/) and [DMCA](https://www.pornhat.com/dmca/) reviewed; personal-use, notice-retention, and limited embedding/linking provisions discussed below. |
| 13 | FreePornVideos / 5 | GizliKeyif | Source maps to `freepornvideos.xxx`. [Terms](https://www.freepornvideos.xxx/terms/) and [DMCA](https://www.freepornvideos.xxx/dmca/) reviewed. Downloads permitted only to the extent expressly allowed by the website. |
| 14 | PornHub / 7 | GizliKeyif | Source maps to `pornhub.com`. Attempted [terms URL](https://www.pornhub.com/information/terms) could not be retrieved. Current clauses **not verified**. |
| 15 | FapGoat / 2 | GizliKeyif | Source maps to `fapgoat.com`. [Terms](https://fapgoat.com/terms/) and [DMCA](https://fapgoat.com/dmca/) reviewed; personal-use and limited embedding/linking provisions discussed below. |
| 16 | HQPorner / 4 | GizliKeyif | Source maps to `hqporner.com`. [Terms](https://hqporner.com/terms-of-use.php) reviewed; personal/noncommercial use with restrictions on copying and third-party exploitation. |
| 17 | Porn00 / 8 | GizliKeyif | Source maps to [porn00.org](https://www.porn00.org/). Homepage retrieval timed out; current terms **not verified**. |
| 18 | Xhamster / 12 | GizliKeyif | Source actually maps to mirror [xhamster2.com](https://xhamster2.com/). Attempts at mirror and main-brand `/terms` timed out. Operator identity and applicability of main-brand terms not established. |
| 19 | xVideos / 7 | GizliKeyif | Source maps to `xvideos.com`; official [terms](https://info.xvideos.net/legal/tos/) retrieved through web research. Personal-use limits and explicit automation restrictions. |
| 20 | Youperv / 3 | GizliKeyif | Source maps to [youperv.com](https://youperv.com/). Homepage retrieved; no legal link found in parsed anchors. Current terms **not verified**. |
| 21 | FXPrnHD / 3 | CXXX | Source maps to `fxpornhd.com`. [Terms](https://fxpornhd.com/terms-of-use/) and [DMCA](https://fxpornhd.com/dmca/) reviewed. Restrictions on reuse/exploitation; disclaimer is not evidence of third-party rights. |
| 22 | XNXX / 2 | CXXX | Source actually maps to [txnhh.com](https://www.txnhh.com/). Its footer links [XNXX terms](https://info.xnxx.com/legal/tos), which timed out. XVideos terms were **not** substituted for XNXX terms. |
| 23 | CNC Verse / 114 | CNC | Drives all three Netflix/Prime/Disney+ Hotstar labels. [CNC repository](https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension) and [community page](https://cncverse.pages.dev/) inspected. Exact media operators, software grant, and content authorization **unresolved**. Official brand terms below do not authorize a mirror. |
| 24 | MovieBoxProvider / 52 | CNC | Exact binary endpoint **unresolved**. `moviebox.ph` was a research candidate, not a verified mapping. Its guessed `/terms` request timed out. No permission determination made from an unrelated MovieBox result. |

Domain-mapping evidence: [GizliKeyif source tree](https://github.com/Kraptor123/Cs-GizliKeyif/tree/master), [CXXX source tree](https://github.com/phisher98/CXXX/tree/master), [Phisher plugin metadata](https://github.com/phisher98/cloudstream-extensions-phisher/blob/builds/plugins.json), [CSX URL configuration](https://github.com/SaurabhKaperwan/Utils/blob/main/urls.json). These are mutable upstream references checked during this research; they are not pinned evidence of older packages.

### Separate built-in game provider

[PornGamesHub](https://porngameshub.com/) is wired directly in app source rather than supplied by one of the 24 packages. Homepage and guessed `/terms` requests timed out. Site terms, individual game licenses, and permission for Celium's game-download workflow remain **unverified**. A site's ability to serve a game does not establish the developer's redistribution rights.

## What the retrieved website policies actually allow

### Moviesmod and candidate MultiMovies / HDhub4u sites

Moviesmod's disclaimer claims a nonhosting role and educational/testing/promotional purposes, encourages original purchases, and offers removal on complaint. Its DMCA page describes a notice process. These are operator assertions; neither page establishes permission from studios or a grant covering Celium. [Disclaimer](https://moviesmod.tw/site-disclaimer/), [DMCA](https://moviesmod.tw/dmca/).

The candidate MultiMovies disclaimer describes third-party links and puts legality checks on the user. It does not establish the user's rights to the linked content or Celium's right to integrate it. [Disclaimer](https://m1.multimovies.courses/disclaimer).

The candidate HDhub4u disclaimer similarly disclaims hosting, refers users to local law and legal alternatives, and invokes educational/fair-use language. That language is not a rights-holder license or a legal determination. [Disclaimer](https://new11.hdhub4u.cl/disclaimer/).

### PornHat and FapGoat

Both terms provide for personal use, require preserving proprietary notices on personal copies, and restrict other exploitation without the relevant owner's written consent. Both describe personal/noncommercial linking and an embeddable-player feature subject to conditions, including attribution/linking back. Neither is a blanket ban on every personal download, nor a general license for a separate native player or bulk extraction. Both require adulthood under the stated age/local-majority rules. [PornHat terms](https://www.pornhat.com/terms/), [FapGoat terms](https://fapgoat.com/terms/).

### FreePornVideos

Terms distinguish viewing from downloading: downloading is allowed only where expressly permitted by the site. Personal/noncommercial access does not establish permission for copying, distribution, or derivative uses beyond the stated grant. Age requirements depend on jurisdiction. No broad Celium-integration permission established. [Terms](https://www.freepornvideos.xxx/terms/).

### HQPorner

Terms describe personal/noncommercial use and require prior written authorization for copying/distributing website material and other third-party exploitation. A contributor's grant to the site is not automatically a grant to Celium. Adult/local-majority restrictions also apply. [Terms](https://hqporner.com/terms-of-use.php).

### FXPrnHD

Terms impose adult/local-majority and lawful-use requirements, reserve rights, and restrict other reuse/exploitation without written consent. The DMCA page's assertion about publicly available material is an operator claim; public accessibility alone does not give the site rights to license another owner's work. [Terms](https://fxpornhd.com/terms-of-use/), [DMCA](https://fxpornhd.com/dmca/).

### XVideos

Sections 2 and 6 limit the relevant use to personal/noncommercial purposes and restrict unauthorized exploitation. Section 7.B expressly addresses scrapers, robots, offline readers, automated extraction/download, and written authorization. Its terms therefore present a concrete permission issue for an adapter that extracts links automatically. Personal-use language should not be presented as permission for every integration. Whether a specific Celium interaction violates a binding agreement requires its actual behavior and applicable law. [Official terms](https://info.xvideos.net/legal/tos/).

## Official services behind the CNC display names

These official policies are a comparison, **not evidence that CNC accesses these services or inherits their permissions**.

| Display label | Official policy reviewed | Relevance |
| --- | --- | --- |
| Netflix | [Terms of Use](https://help.netflix.com/legal/termsofuse) | Personal/noncommercial access; offline viewing is limited to supported plans/devices. Restrictions include unauthorized reproduction, automated collection, and defeating protections. Served terms were a default regional version; the subscriber's actual jurisdiction/version must be checked. |
| Amazon Prime Video | [Terms](https://www.primevideo.com/help?nodeId=G202095490&tr=hm), [usage rules](https://www.primevideo.com/help?nodeId=G202095500&tr=mv) | Limited personal/noncommercial viewing rights subject to payment and usage rules; offline rights apply to supported apps/devices. These do not establish a right to supply independent downloadable movie files. |
| Disney+ Hotstar | [Jiostar India terms, dated 24 December 2025](https://secure-media.hotstar.com/web-assets/prod/terms-production_IN.html) | Downloads are temporary; permanent copying is restricted. Terms prohibit unauthorized automated access, scraping, and indexing/framing/linking. The legacy label in our UI does not establish current operator affiliation. These are India Hotstar terms, not a universal Disney+ agreement. |

## Subtitle, metadata, and account services

These are separate from movie-provider packages. Their availability in source does not prove a user enabled them. This table records findings and unresolved checks rather than certifying every API call compliant.

| Integration | Primary evidence | Result |
| --- | --- | --- |
| SubDL | [Terms, updated 6 September 2026](https://subdl.com/terms), [API docs](https://subdl.com/api-doc/) | Explicit API permissions: commercial integration with a paid plan, or per-user keys under stated conditions. Do not pool keys, bypass limits, or treat API access as ownership of subtitles. Free shared keys are not permitted to power a commercial service. Actual Celium key handling/plan compliance not audited. |
| SubSource | [Terms](https://subsource.net/terms), [API docs](https://subsource.net/api-docs) | Reserves website rights and translator ownership. Restricts plagiarism/alteration; its permitted resync/reupload conditions preserve translator/sponsor credit. API documentation alone is not proof of an unrestricted reuse license. API plan compliance not audited. `subsource.com` is a different business and was excluded. |
| OpenSubtitles | [Terms](https://opensubtitles.tawk.help/article/terms-of-service), [API overview](https://opensubtitles.tawk.help/article/about-the-api) | Published website terms prohibit commercial use; API has its own access/account arrangements. Full applicable API agreement, developer plan, attribution, and quota compliance need verification. Do not assume website terms describe every paid API arrangement. |
| Addic7ed | [Official FAQ on its community forum](https://www.sub-talk.net/topic/2784-frequently-asked-questions/) | FAQ describes subtitle access/community rules, not a general third-party-app license. Full current integration terms not established. The rule against automated translated uploads is not evidence of a ban on all automatic downloads. |
| TMDB | [Official API FAQ](https://developer.themoviedb.org/docs/faq) | Noncommercial API use requires attribution; commercial use needs the appropriate license. Requires approved branding and its endorsement disclaimer in About/Credits. Also restricts association of its data/images with specified offensive content, including pornography; Celium's adult section makes this worth checking against actual data flows. No attribution or commercial-status compliance audit performed here. |
| AniList | [Current official documentation source](https://github.com/AniList/docs/blob/master/docs/guide/terms-of-use.md) | Prohibits mass collection, storage/backup use, and competing noncomplementary list/tracker services, including use of user or media data. Naming rules and commercial thresholds apply. Current source differs from older GitBook text on competing services; do not rely on the old exemption. Celium's exact tracking use not adjudicated. |
| MyAnimeList | [Attempted official terms](https://myanimelist.net/about/terms_of_use) | Retrieval failed. Current site/API conditions not verified. |
| Simkl | [Attempted official terms](https://simkl.com/terms/) | Retrieval failed. Current site/API conditions not verified. |
| Kitsu | Registered integration in app source | Applicable current API/site agreement not established; unrelated Kitsu game/animation-business results excluded. |
| AnimeSkip | Registered integration in app source | Applicable API/site terms not established in this pass. |

LocalList is local storage rather than an external service. Trackers, subtitles, metadata proxies, and login privacy obligations need their own implementation audit if enabled; this research did not inspect credentials or user accounts.

## Fair use and fair dealing

There is no provider-wide fair-use switch. A site can state its policy, but cannot decide the outcome for another person's copyright or grant rights it does not own.

**United States:** the Copyright Office explains four factors: purpose/character, nature of the work, amount used, and market effect. Nonprofit operation or an educational label alone does not settle them. My assessment is that supplying full entertainment films as a substitute for authorized viewing is not a sound blanket fair-use basis; that is an inference, not a judgment on every Celium use. [U.S. Copyright Office](https://www.copyright.gov/fair-use/).

**India:** section 52 includes fair dealing for specified purposes, including private/personal use and research, criticism/review, and reporting current events. It also contains qualified transient-storage and linking/access/integration provisions; knowledge/reasonable grounds and notice procedures can matter. These provisions do not automatically clear an app's public distribution or every source it promotes. Section 51 defines infringement. Applying either section needs the actual facts. [Copyright Act, Chapter XI, sections 51–52](https://copyright.gov.in/Copyright_Act_1957/chapter_xi.html).

Personal viewing, app development, extension distribution, automated extraction, and public movie distribution are different acts. Their outcomes can differ. Subscription status, lack of payment, a disclaimer, or the fact that CloudStream exists does not establish Celium's permissions.

## CineStream's additional source scope

The following **63 uncommented registry display names** occur in current [ProviderRegistry.kt](https://github.com/SaurabhKaperwan/CSX/blob/master/CineStream/src/main/kotlin/com/megix/ProviderRegistry.kt). They are not proof that every source is enabled, working, used, or present in selected version 487. All underlying policy/rights checks remain unresolved except where a candidate site was discussed above; even those mappings must be verified for the actual build.

Domain clues are in [ApiConstants.kt](https://github.com/SaurabhKaperwan/CSX/blob/master/CineStream/src/main/kotlin/com/megix/ApiConstants.kt) and the remote [URL configuration](https://github.com/SaurabhKaperwan/Utils/blob/main/urls.json). Some depend on build configuration, remote URLs, or additional services. Examples: Fshare maps to `fsharetv.cc`, not automatically the official Fshare service; Anidb maps to `anidb.app`, not automatically `anidb.net`. A familiar name does not establish an operator or applicable policy.

| # | Registered name | Underlying policy review |
| --- | --- | --- |
| 1 | Torrentio | Unresolved; see overlap qualifications above. |
| 2 | TorrentsDB | Unresolved; see overlap qualifications above. |
| 3 | AnimeTosho | Unresolved; see overlap qualifications above. |
| 4 | WYZIESubs | Unresolved; see overlap qualifications above. |
| 5 | StremioSubs | Unresolved; see overlap qualifications above. |
| 6 | ShowBox | Unresolved; see overlap qualifications above. |
| 7 | Vidrock | Unresolved; see overlap qualifications above. |
| 8 | Moviebox | Unresolved; see overlap qualifications above. |
| 9 | MovieBlast | Unresolved; see overlap qualifications above. |
| 10 | Fibwatch | Unresolved; see overlap qualifications above. |
| 11 | Allmovieland | Unresolved; see overlap qualifications above. |
| 12 | Hdhub4u | Unresolved; see overlap qualifications above. |
| 13 | Hexa | Unresolved; see overlap qualifications above. |
| 14 | Fshare | Unresolved; see overlap qualifications above. |
| 15 | Videasy | Unresolved; see overlap qualifications above. |
| 16 | VaPlayer | Unresolved; see overlap qualifications above. |
| 17 | Vidzee | Unresolved; see overlap qualifications above. |
| 18 | Peachify | Unresolved; see overlap qualifications above. |
| 19 | VidFastPro | Unresolved; see overlap qualifications above. |
| 20 | Cinejoy | Unresolved; see overlap qualifications above. |
| 21 | Vidcore | Unresolved; see overlap qualifications above. |
| 22 | Av1encodes | Unresolved; see overlap qualifications above. |
| 23 | Castle | Unresolved; see overlap qualifications above. |
| 24 | Reanime | Unresolved; see overlap qualifications above. |
| 25 | Just4Anime | Unresolved; see overlap qualifications above. |
| 26 | Zinkmovies | Unresolved; see overlap qualifications above. |
| 27 | Gramcinema | Unresolved; see overlap qualifications above. |
| 28 | VegaMovies | Unresolved; see overlap qualifications above. |
| 29 | RogMovies | Unresolved; see overlap qualifications above. |
| 30 | Bollyflix | Unresolved; see overlap qualifications above. |
| 31 | TopMovies | Unresolved; see overlap qualifications above. |
| 32 | Vidup | Unresolved; see overlap qualifications above. |
| 33 | Meowtv | Unresolved; see overlap qualifications above. |
| 34 | Moviesmod | Unresolved; see overlap qualifications above. |
| 35 | Movies4u | Unresolved; see overlap qualifications above. |
| 36 | Dudefilms | Unresolved; see overlap qualifications above. |
| 37 | UHDMovies | Unresolved; see overlap qualifications above. |
| 38 | MoviesDrive | Unresolved; see overlap qualifications above. |
| 39 | Hindmoviez | Unresolved; see overlap qualifications above. |
| 40 | 4KHDHub | Unresolved; see overlap qualifications above. |
| 41 | PrimeSrc | Unresolved; see overlap qualifications above. |
| 42 | ProjectFreeTV | Unresolved; see overlap qualifications above. |
| 43 | Mlsbd | Unresolved; see overlap qualifications above. |
| 44 | Levidia | Unresolved; see overlap qualifications above. |
| 45 | Animesalt | Unresolved; see overlap qualifications above. |
| 46 | M4ufree | Unresolved; see overlap qualifications above. |
| 47 | Multimovies | Unresolved; see overlap qualifications above. |
| 48 | Akwam | Unresolved; see overlap qualifications above. |
| 49 | Rtally | Unresolved; see overlap qualifications above. |
| 50 | Asiaflix | Unresolved; see overlap qualifications above. |
| 51 | SkyMovies | Unresolved; see overlap qualifications above. |
| 52 | KissKH | Unresolved; see overlap qualifications above. |
| 53 | Onetouchtv | Unresolved; see overlap qualifications above. |
| 54 | Toonstream | Unresolved; see overlap qualifications above. |
| 55 | Anineko | Unresolved; see overlap qualifications above. |
| 56 | Animedao | Unresolved; see overlap qualifications above. |
| 57 | Anikoto | Unresolved; see overlap qualifications above. |
| 58 | Anikage | Unresolved; see overlap qualifications above. |
| 59 | Anidb | Unresolved; see overlap qualifications above. |
| 60 | AnimeToshoHttp | Unresolved; see overlap qualifications above. |
| 61 | TokyoInsider | Unresolved; see overlap qualifications above. |
| 62 | Anizone | Unresolved; see overlap qualifications above. |
| 63 | Animekizz | Unresolved; see overlap qualifications above. |

Its metadata/subtitle/network dependencies also include Cinemeta, anime-kitsu, mapping/image proxies, and downstream media hosts. They are not additional cleared licenses. This appendix is a scope inventory, **not an assertion that all 63 services' terms have been read**.

## Remaining permission checks before calling a provider cleared

1. Match the actual installed binary/version/hash to implementation source and runtime domains, including nested extractors/hosts.
2. Resolve CNC/GizliKeyif software grants and corresponding-source availability for distributed GPL binaries. Check the exact desktop Moviesmod source pin against its notices/source distribution.
3. Obtain applicable website/API integration permission where current terms do not already cover the actual behavior. Check embedded-player permissions separately from native playback and downloads.
4. Establish rights for the relevant content/catalog and territories. A site's DMCA contact or nonhosting statement does not do this.
5. Review actual API keys, quotas, attribution, cache/retention, commercial use, branding, and adult-section data flows against each service's terms.
6. Have qualified counsel assess unresolved rights and jurisdiction questions before making a public claim that the whole catalog is lawful or protected by fair use.

No providers were disabled, no permissions were requested from operators, and no changes were published as part of this research.

## Research method and limitations

- Reviewed local source inventory/catalog wiring, public upstream source/READMEs/license files, linked legal pages, and primary legal references.
- Used ordinary public web requests. Did not log in, bypass age gates/paywalls, accept terms, or play/download media during this audit.
- Homepage HTML was checked for legal links. Absence in parsed anchors does **not** establish absence of terms; JavaScript, regional pages, or another operator domain may expose them.
- Timed-out/blocked pages are marked unverified, not permission granted or proven infringement. Guessed `/terms` paths are explicitly attempts, not verified policy locations.
- [Retrieval evidence](provider-terms-evidence.json) records requested/final URLs, timestamps, status/errors, titles, and hashes for the direct-fetch batches. It is a partial provenance log; browser/search and GitHub connector research is linked above but is not included in that HTTP log. Full copyrighted policy pages are not committed.
- No build/tests needed for this documentation-only change. Runtime compliance, exact released-binary behavior, content ownership, and every transitive host remain unverified.

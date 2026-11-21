# Agent 3 — Tests, couverture Kover, écran de diagnostic, robustesse du rafraîchissement en arrière-plan

Tu es l'**Agent 3** (tag `[A3]`). Deux autres agents travaillent **en même temps que toi, dans le même dépôt et le même répertoire de travail**. Lis tout ce document avant de commencer.

---

## 1. Contexte du projet (commun aux 3 agents)

- Projet Android `TokenUsage` (Kotlin, Jetpack Compose, Hilt, Room + SQLCipher, kotlinx.serialization, WorkManager, Glance). Tests : JUnit, kotlinx-coroutines-test, Turbine, MockWebServer, Mockito.
- Racine des sources : `app/src/main/java/fr/gildasledrogoff/tokenusage/android/` (noté `<SRC>` ci-dessous). Le package Kotlin est `fr.gildasledrogoff.tokenusage.*` (sans `.android`).
- Tests unitaires : `app/src/test/java/fr/gildasledrogoff/tokenusage/android/` (noté `<TEST>`).
- Source sets annexes : `app/src/demoData/` (données de démo, `SampleDataRepository`), `app/src/release/`, `app/src/testDebug/`, `app/src/androidTest/` (captures d'écran).
- i18n : toute chaîne visible doit exister en `values/` (anglais), `values-fr/` et `values-zh/`.
- 8 services (`AiService`) : CLAUDE, CODEX, GEMINI, DEEPSEEK, ZAI, COPILOT, OPENROUTER, OPENAI. Le multi-comptes existe déjà pour **Claude, Z.ai et DeepSeek** (`EncryptedPrefsManager.get/save/deleteXxxAccounts`, `MultiAccountQuotaRepository`, `fetchQuotaForAccount`).
- CI : `.github/workflows/ci.yml` lance `./gradlew lintDebug testDebugUnitTest assembleDebug`.

### Liste de tâches d'origine (répartie entre les 3 agents)
1. **(PRIORITÉ)** Support de plusieurs comptes pour tous les services, pas seulement Claude et Z.ai.
2. Alertes configurables par seuil (ex. 80 % du quota ou solde bas), avec prédiction d'épuisement basée sur l'historique déjà stocké.
3. Fichiers trop gros : `SettingsScreen.kt` (2063 lignes) et `SettingsViewModel.kt` (1168 lignes) à découper en sous-écrans/sections et en un ViewModel par domaine (comptes, affichage, sauvegarde, sécurité).
4. Répétition entre services (`XxxRepositoryImpl`, `XxxApiService`, `XxxDto` × 8) : abstraction commune (interface Provider + registre) qui supprime les `when(service)` dispersés.
5. Tests : parseurs de DTO, `BackupCrypto`, `QrChunkCodec`, `FetchRateLimiter`, `BalanceConsumptionAggregator`, ViewModels.
6. Mesure de couverture Kover dans la CI.
7. Écran de diagnostic local (sans télémétrie) : dernier statut par service, âge des données, dernière erreur.
8. Rafraîchissement en arrière-plan : vérifier que `QuotaRefreshService`, `QuotaRefreshWorker` et `TokenRefreshWorker` ne se chevauchent pas ; comportement avec Doze et les restrictions des constructeurs.

---

## 2. Voici ce que font les autres agents (commun aux 3 agents)

| Agent | Périmètre | Dossiers/fichiers dont il est propriétaire |
|---|---|---|
| **A1** | Tâches 1 et 4 : multi-comptes pour tous les services + abstraction `Provider`/registre. Interface « comptes » dans les paramètres. Tests de ce qu'il modifie. | `<SRC>/core/data/*RepositoryImpl.kt`, `<SRC>/core/network/**`, `<SRC>/core/domain/**` (sauf nouveaux fichiers d'alerte d'A2), `<SRC>/core/provider/**` (nouveau), `<SRC>/core/security/EncryptedPrefsManager.kt`, `<SRC>/core/backup/BackupPayload.kt` + `BackupCodec.kt` + `ConfigBackupManager.kt`, `<SRC>/core/data/LastFetchCacheCodec.kt`, `<SRC>/di/RepositoryModule.kt` + `di/NetworkModule.kt`, `<SRC>/feature/dashboard/**`, `<SRC>/core/widget/**`, `<SRC>/core/tile/**`, `<SRC>/feature/settings/accounts/**` (nouveau) + `ClaudeAccountsSection.kt`, `ClaudeLoginDialog.kt`, `ClaudeSessionCookie.kt`, `app/src/demoData/**` |
| **A2** | Tâches 2 et 3 : découpage de `SettingsScreen`/`SettingsViewModel` (hors comptes) en sous-écrans et ViewModels par domaine + alertes par seuil avec prédiction d'épuisement. Tests de ses ViewModels. | `<SRC>/feature/settings/**` (hors partie comptes d'A1), `<SRC>/feature/history/**`, `<SRC>/core/notification/**`, `<SRC>/core/alert/**` (nouveau), `<SRC>/ui/**` |
| **A3** | Tâches 5 (hors ViewModels des autres), 6, 7, 8 : tests (DTO, BackupCrypto, QrChunkCodec, FetchRateLimiter, BalanceConsumptionAggregator), Kover + CI, écran de diagnostic, audit du rafraîchissement en arrière-plan (chevauchements, Doze, constructeurs). | `<SRC>/core/workmanager/**` (zones précisées ci-dessous), `<SRC>/core/util/BatteryOptimizationHelper.kt`, `<SRC>/feature/diagnostics/**` + `<SRC>/core/diagnostics/**` (nouveaux), `app/build.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/**`, `app/src/main/AndroidManifest.xml`, nouveaux fichiers de test listés dans sa mission |

### Fichiers partagés : découpage en zones
Ces fichiers sont édités par plusieurs agents. Chacun ne touche **que sa zone**.

- **`feature/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`** : A1 **extrait en premier** (phase 0) tout ce qui concerne les identifiants/comptes (`ServiceCredentialSection`, `DeepseekAccountsSection`, `ZaiAccountsSection`, `ApiKeyServiceSection`, `CopilotLoginDialog`, `ValidationResultLabel`, `accountStatus`, `apiKey*Res`, `canValidate`, `isConfigured`, et côté ViewModel : `loadSavedCredentials`, `updateField`, `saveCredential`, `removeCredential`, `validateCredential`, `*ClaudeAccount*`, `*DeepseekAccount*`, `*ZaiAccount*`, `setZaiHideMonthlyTools`, `startCopilotLogin`/`cancelCopilotLogin`, les états UI correspondants) vers `feature/settings/accounts/`. Tout le reste appartient à A2, qui décide de la structure finale des paramètres.
- **`core/workmanager/QuotaRefreshService.kt`** : A1 = construction de la liste des comptes à interroger (le bloc `val fetches = buildList { … }` et les injections de repositories) ; A2 = bloc de notifications (`if (prefsManager.isNotificationsEnabled()) { … }` et `checkForResets`) ; A3 = tout le reste (concurrence/verrous, timeout, gestion des erreurs, enregistrement pour le diagnostic).
- **`core/workmanager/TokenRefreshWorker.kt`** : A1 = logique de rafraîchissement par service/par compte (`refreshIfNeeded`, `refreshClaude*`, `refreshCodex`, `refreshGemini` et la boucle sur les comptes) ; A3 = enveloppe de `doWork()` (verrou, politique de retry), planification.
- **`MainActivity.kt`** (NavHost) : A2 ajoute les routes des sous-écrans de paramètres ; A3 ajoute la route `"diagnostics"`. A1 n'y touche pas.
- **`res/values*/strings.xml`** : **personne n'ajoute de chaîne dans les fichiers existants.** Chaque agent crée ses propres fichiers de ressources, en 3 langues : A1 → `strings_accounts.xml`, A2 → `strings_settings_alerts.xml`, A3 → `strings_diagnostics.xml` (dans `values/`, `values-fr/`, `values-zh/`). On ne supprime une chaîne existante que si elle devient inutilisée **et** relève de son propre domaine.
- **`app/src/main/AndroidManifest.xml`** et **fichiers Gradle** : propriété d'A3. Si A1 ou A2 a absolument besoin d'une modification, il fait une édition minimale (une ligne), après relecture, et la note dans `COORDINATION.md`.
- **Fichiers utilitaires partagés** (`SettingsFields.kt`, `ConfirmDeleteIconButton.kt`, `ui/components/**`, `ui/theme/**`) : utilisables par tous, **ne pas changer leurs signatures existantes** ; ajouter de nouveaux helpers dans ses propres fichiers.

### Contrats d'interface entre agents (à respecter à la lettre)
1. **`QuotaInfo` porte l'identité du compte** : A1 ajoute dès la phase 0 `val accountId: String? = null` et `val accountLabel: String? = null` à `core/domain/model/QuotaInfo.kt` (valeurs par défaut, donc rien ne casse), puis les renseigne pour tous les services. A2 (alertes) et A3 (diagnostic) identifient un compte par la clé `"${service.name}:${accountId ?: "default"}"`.
2. **Section comptes autonome** : A1 expose `@Composable fun AccountsSettingsContent(modifier: Modifier = Modifier)` dans `feature/settings/accounts/AccountsSettingsContent.kt`, qui récupère lui-même son `AccountsViewModel` via `hiltViewModel()` et gère ses propres dialogues (login Claude, login Copilot). A2 se contente de placer cet appel là où il veut dans la nouvelle structure des paramètres.
3. **DTO stables** : A1 ne renomme pas, ne déplace pas et ne change pas la sérialisation des classes `XxxDto` (ni leurs champs). L'abstraction Provider se fait au niveau repository/registre. A3 écrit des tests de parsing sur ces DTO. Si un changement est inévitable, A1 l'annonce dans `COORDINATION.md` et adapte lui-même les tests concernés.
4. **Préférences** : seul A1 modifie `EncryptedPrefsManager.kt`. A2 et A3 peuvent **appeler** ses méthodes existantes, mais stockent leurs nouveaux réglages dans leurs propres classes (`core/alert/AlertPreferences…`, `core/diagnostics/…`).
5. **Navigation diagnostic** : la route s'appelle exactement `"diagnostics"`. A3 crée la route et l'écran ; A2 ajoute l'entrée dans les paramètres (callback `onOpenDiagnostics` → `navController.navigate("diagnostics")`).
6. **Historique et agrégation** : `BalanceConsumptionAggregator` et `FetchRateLimiter` ne changent pas de comportement public (A3 les teste). A2 peut créer de nouvelles classes qui s'appuient dessus pour la prédiction.
7. **Base de données** : pas de changement de schéma Room sans l'annoncer d'abord dans `COORDINATION.md`. Seul A1 peut en faire un, avec une migration propre (version actuelle : 2).

---

## 3. Règles de cohabitation : ne parasite pas le travail des autres (commun aux 3 agents)

- **Reste dans ton périmètre.** Ne modifie jamais un fichier appartenant à un autre agent, sauf ta zone dans un fichier partagé. Si tu as besoin d'un changement chez un autre, écris-le dans `COORDINATION.md` et contourne le problème (adaptateur, nouvelle classe) sans toucher son code.
- **Fichier de coordination** : `COORDINATION.md` à la racine du dépôt. Crée-le s'il n'existe pas. **Ajout uniquement**, une ligne par événement, préfixée par ton tag et l'heure (`[A1 04:35] …`). Ne modifie ni ne supprime jamais les lignes des autres. Relis-le régulièrement, et toujours avant d'entrer dans un fichier partagé.
- **Éditions chirurgicales** : dans les fichiers partagés, utilise des remplacements ciblés (outil Edit), **jamais une réécriture complète du fichier** (pas de Write sur un fichier existant partagé). Relis toujours le fichier juste avant de l'éditer : un autre agent l'a peut-être modifié entre-temps.
- **Git** : **aucun** `commit`, `stash`, `reset`, `checkout -- …`, `restore`, `clean`, `rebase`, `merge`, ni changement de branche. L'utilisateur gère git. Tu peux utiliser `git diff`/`git status` en lecture.
- **Garde le build vert à chaque étape** : avance par petits incréments qui compilent, pour ne pas bloquer les autres. Ne laisse jamais un fichier dans un état non compilable plus de quelques minutes.
- **Builds Gradle** : privilégie les tâches ciblées (`./gradlew :app:compileDebugKotlin`, `./gradlew :app:testDebugUnitTest --tests '…'`). Si Gradle attend un verrou, patiente et réessaie : ne tue pas le daemon et ne supprime pas `build/`. Si le build échoue **dans un fichier qui ne t'appartient pas**, ne le corrige pas : c'est sans doute un travail en cours. Attends quelques minutes, réessaie, et si ça persiste, signale-le dans `COORDINATION.md`.
- **Pas de reformatage global**, pas de renommage de package, pas de mise à jour de dépendances hors périmètre, pas de suppression de fichiers d'un autre agent.
- **Ne supprime pas** les tests existants. Adapte ceux qui touchent ton code.
- Langue : commentaires et noms cohérents avec le code environnant ; messages utilisateur en 3 langues.
- En fin de mission : `./gradlew lintDebug testDebugUnitTest assembleDebug`. Corrige ce qui relève de toi et signale le reste. Écris `[Ax hh:mm] TERMINÉ` puis un résumé dans `COORDINATION.md`.

---

## 4. Voici ce que TU dois faire (Agent 3)

Ton travail est le moins couplé aux autres. Tu peux commencer immédiatement par les étapes A et B, qui ne touchent que des fichiers de test et Gradle.

### Étape A : couverture Kover + CI (tâche 6)
- Ajoute le plugin Kover (version stable récente, via `gradle/libs.versions.toml` + `plugins {}`) au module `app`.
- Configure les exclusions raisonnables : classes générées Hilt (`*_Factory`, `*_HiltModules*`, `Hilt_*`, `*_MembersInjector`), Room (`*_Impl`), `BuildConfig`, `ComposableSingletons*`, thèmes et previews Compose. Cible la variante `debug`.
- Rapports XML et HTML. Ajoute dans `.github/workflows/ci.yml` l'étape de génération (ex. `koverXmlReportDebug koverHtmlReportDebug`, adapte selon la version de Kover) et inclus les rapports dans l'artefact `reports` existant. Ajoute un résumé de couverture dans `$GITHUB_STEP_SUMMARY` (outil simple, sans service externe ni token).
- Pas de seuil bloquant (`minBound`) au début. Mesure la couverture de départ, puis propose un seuil réaliste dans ton résumé (ou mets un seuil bas non régressif si la couverture finale le permet).
- Respecte le style du workflow existant : actions épinglées par SHA de commit, avec la version en commentaire. N'ajoute aucune action non épinglée. Ne modifie pas `fdroid.yml` ni `release.yml`, sauf si Kover les casse.
- Vérifie que le build release et F-Droid (reproductibilité) ne sont pas affectés : Kover ne doit pas instrumenter la variante release.

### Étape B : tests unitaires prioritaires (tâche 5, ta part)
Certains tests existent déjà (`BackupCryptoTest`, `QrChunkCodecTest`, `FetchRateLimiterTest`, `BalanceConsumptionAggregatorTest`, tests de repositories). **Complète-les**, sans dupliquer : utilise le rapport Kover pour viser les branches non couvertes.
- **Parseurs de DTO** (priorité : les API changent sans prévenir) : pour chaque fournisseur de `<SRC>/core/network/<fournisseur>/`, un test `<TEST>/core/network/<fournisseur>/XxxDtoParsingTest.kt` avec des fixtures JSON réalistes dans `app/src/test/resources/fixtures/<fournisseur>/` (réponse nominale, champs optionnels absents, champs inconnus en plus, `null`, nombres en chaîne, tableaux vides, réponse d'erreur). Utilise la même configuration `Json` que l'app (lis `di/NetworkModule.kt`, sans le modifier). Les DTO sont stables (contrat n°3). Si A1 annonce un changement dans `COORDINATION.md`, adapte-toi. **Anonymise les fixtures** : aucun vrai token, e-mail ni identifiant.
- **`BackupCrypto`** : aller-retour, mauvais mot de passe, données altérées (tag GCM), sel/IV uniques, versions de format, entrées vides ou très grandes.
- **`QrChunkCodec`** : découpage/réassemblage, ordre aléatoire, doublons, morceau manquant, morceau d'une autre session, en-tête corrompu, limites de taille.
- **`FetchRateLimiter`** : fenêtres, horloge injectée, concurrence (coroutines-test).
- **`BalanceConsumptionAggregator`** : recharges, trous dans les données, changement de devise, fuseaux horaires et changement d'heure, un seul point.
- Tu ne modifies **pas** le code de production de ces classes. Si tu trouves un bug, écris un test qui le démontre (marqué `@Ignore("bug: …")`) et signale-le dans `COORDINATION.md`. Corrige-le toi-même seulement si la classe ne relève d'aucun autre agent (`BackupCrypto`, `QrChunkCodec`, `FetchRateLimiter` et `BalanceConsumptionAggregator` sont dans ton périmètre de correction, à condition de ne pas changer leur API publique).
- Les tests des ViewModels sont faits par leurs propriétaires (A1 : comptes et dashboard ; A2 : paramètres et alertes). Toi, tu testes `DiagnosticsViewModel` et ce que tu crées dans `core/workmanager`.

### Étape C : écran de diagnostic local (tâche 7)
- `<SRC>/feature/diagnostics/DiagnosticsScreen.kt` + `DiagnosticsViewModel.kt`, et `<SRC>/core/diagnostics/**` pour la collecte. **Aucune télémétrie, aucun envoi réseau.** Tout reste sur l'appareil.
- Contenu, par service **et par compte** (clé du contrat n°1 ; avant la fin de la phase 2 d'A1, seuls certains services auront plusieurs comptes) :
  - dernier statut (OK / erreur / non configuré), horodatage et **âge des données** (« il y a 12 min ») ;
  - dernière erreur (type `AppError` et message), **assainie** : jamais de token, cookie, clé d'API ni en-tête `Authorization` ;
  - origine du dernier rafraîchissement (dashboard / worker périodique / service manuel / tuile).
- Sources : `LastFetchRepository.snapshot()` (déjà alimenté par le dashboard, contient `CachedQuotaResult.error`), lu sans modification. Pour l'arrière-plan, ajoute un enregistrement dans `QuotaRefreshService` **dans ta zone** (après `awaitAll()`). Aujourd'hui, `result.getOrNull()` jette l'erreur : conserve le `Result` complet pour le diagnostic sans changer ce qui est publié aux widgets. Stockage : ta propre classe (`core/diagnostics/DiagnosticsStore`), pas `EncryptedPrefsManager` (contrat n°4), et pas de changement de schéma Room (contrat n°7).
- Section « Arrière-plan » : état WorkManager des travaux uniques (dernier run, prochain run, état), optimisation batterie ignorée ou non, bucket App Standby (`UsageStatsManager.getAppStandbyBucket()`), restrictions en arrière-plan (`ActivityManager.isBackgroundRestricted`), état Doze, constructeur détecté avec un lien vers ses instructions (style dontkillmyapp, en texte local, sans appel réseau).
- Bouton « Copier le rapport » (texte assaini dans le presse-papiers), sans partage automatique.
- Route `"diagnostics"` dans le `NavHost` de `MainActivity.kt` (ta zone : cette route seulement, contrat n°5). L'entrée dans les paramètres est ajoutée par A2. Annonce dans `COORDINATION.md` quand la route existe.
- Chaînes → `strings_diagnostics.xml` (3 langues).

### Étape D : audit et robustesse du rafraîchissement en arrière-plan (tâche 8)
- Cartographie d'abord : qui déclenche quoi et quand (`WorkManagerInitializer`, `RefreshScheduler`, `BootReceiver`, `QuotaRefreshWorker`, `QuotaRefreshService`, `TokenRefreshWorker`, `RefreshReceiver` (action de notification), `QuotaTileService`, rafraîchissement du dashboard). Écris cette cartographie dans `docs/background-refresh.md` (nouveau fichier).
- **Chevauchements** : vérifie que :
  - les travaux sont uniques (`enqueueUniquePeriodicWork` avec la bonne `ExistingPeriodicWorkPolicy` : `UPDATE` plutôt que `REPLACE` quand seul l'intervalle change, pas de doublon après reboot ou mise à jour) ;
  - un rafraîchissement manuel (notification/tuile) et le worker périodique ne lancent pas deux `refreshNow()` en parallèle : ajoute un `Mutex` ou une déduplication dans **ta zone** de `QuotaRefreshService` (enveloppe de `refreshNow`). Le second appelant récupère le résultat en cours ou sort proprement ;
  - `TokenRefreshWorker` ne rafraîchit pas un token OAuth pendant qu'un fetch l'utilise avec l'ancien, et deux rafraîchissements de token concurrents ne s'écrasent pas (les refresh tokens à usage unique sont critiques). Verrou dans **ta zone** (l'enveloppe de `doWork()`). La logique par compte appartient à A1 : s'il faut un verrou par compte côté repository, propose-le dans `COORDINATION.md`.
- **Doze et constructeurs** : contraintes réseau des workers, backoff exponentiel, `setForeground`/`getForegroundInfo` (type de service foreground déclaré dans le manifeste pour Android 14+), comportement si l'optimisation batterie est active, `BootReceiver` et `MY_PACKAGE_REPLACED`, intervalle minimal de 15 min de WorkManager, expedited work et quotas. Corrige ce qui est faux dans tes fichiers ; documente le reste dans `docs/background-refresh.md`.
- `BatteryOptimizationHelper` est à toi. L'UI de la batterie dans les paramètres (`BackgroundRefreshBlock`) est à A2 : si tu veux y changer quelque chose, propose-le dans `COORDINATION.md`. Le détail des restrictions constructeur va dans ton écran de diagnostic.
- Tests : planification (`work-testing` / `TestListenableWorkerBuilder` si la dépendance est ajoutable proprement ; tu es propriétaire de Gradle), dédoublonnage du `Mutex` (coroutines-test).

### Critères de fin
- Kover en CI avec rapports en artefact et résumé, couverture de départ et d'arrivée chiffrées dans ton résumé.
- Tests de parsing pour les 8 fournisseurs + tests complétés des 4 classes cibles, tous verts.
- Écran de diagnostic fonctionnel, accessible via la route `"diagnostics"`, sans aucune fuite de secret.
- Plus de chevauchement possible entre les rafraîchissements. Cartographie et constats Doze/constructeurs dans `docs/background-refresh.md`.
- `./gradlew lintDebug testDebugUnitTest assembleDebug` + rapport Kover verts. Résumé final dans `COORDINATION.md`.

# Agent 1 — Multi-comptes pour tous les services + abstraction Provider

Tu es l'**Agent 1** (tag `[A1]`). Deux autres agents travaillent **en même temps que toi, dans le même dépôt et le même répertoire de travail**. Lis tout ce document avant de commencer.

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

## 4. Voici ce que TU dois faire (Agent 1)

### Phase 0 : débloquer les autres (à faire immédiatement, en moins d'une heure)
A2 attend cette phase pour découper `SettingsScreen.kt`/`SettingsViewModel.kt`. Fais-la en premier, sans rien refactorer d'autre.
1. Ajoute `accountId: String? = null` et `accountLabel: String? = null` à `QuotaInfo` (contrat n°1). Mets à jour `LastFetchCacheCodec` pour les persister en restant rétrocompatible avec les caches existants.
2. Crée `feature/settings/accounts/` et **déplace** (sans changer le comportement) toute la partie identifiants/comptes listée dans la section « Fichiers partagés » :
   - UI → `AccountsSettingsContent.kt` (point d'entrée du contrat n°2) + autres fichiers si besoin (`ApiKeyServiceSection.kt`, `CopilotLoginDialog.kt`, `ZaiAccountsSection.kt`, `DeepseekAccountsSection.kt`…). Tu peux aussi y déplacer `ClaudeAccountsSection.kt`/`ClaudeLoginDialog.kt`.
   - Logique → `AccountsViewModel.kt` (`@HiltViewModel`), avec ses états dans `AccountsUiState.kt`.
   - Dans `SettingsScreen.kt`, remplace les blocs retirés par un seul appel `AccountsSettingsContent()`. Dans `SettingsViewModel`/`SettingsUiState`, retire ce qui a été déplacé.
   - Si `ValidationResultLabel` ou `ExpandableSectionHeader` servent aux deux domaines, ne les déplace pas : laisse-les où ils sont. A2 décidera.
3. Compile, vérifie que l'écran Paramètres fonctionne comme avant, puis écris dans `COORDINATION.md` : `[A1 hh:mm] PHASE 0 TERMINÉE — comptes extraits vers feature/settings/accounts, QuotaInfo.accountId/accountLabel ajoutés`.
4. Après ça, ne touche plus à `SettingsScreen.kt`/`SettingsViewModel.kt`/`SettingsUiState.kt` (sauf demande explicite d'A2 dans `COORDINATION.md`).

### Phase 1 : abstraction Provider + registre (tâche 4)
- Conçois dans `<SRC>/core/provider/` une interface commune, par exemple :
  ```kotlin
  interface QuotaProvider {
      val service: AiService
      fun accounts(): List<ProviderAccount>                  // comptes configurés (id, label, credential)
      suspend fun fetchQuota(account: ProviderAccount): Result<QuotaInfo, AppError>
      suspend fun validate(account: ProviderAccount): Result<Unit, AppError>
      // + éventuellement : refreshToken(account), descripteur de formulaire de credential pour l'UI
  }
  class ProviderRegistry @Inject constructor(providers: Set<@JvmSuppressWildcards QuotaProvider>) { … }
  ```
  Enregistre les providers avec Hilt `@IntoSet` (ou `@IntoMap` par `AiService`). Adapte la forme exacte à ce qui simplifie vraiment le code existant.
- Factorise la plomberie répétée des 8 `XxxRepositoryImpl` (gestion d'erreurs HTTP → `AppError`, timeouts, enregistrement de l'historique de solde, résolution du credential) dans des helpers ou une classe de base. **Ne touche pas aux DTO** (contrat n°3).
- Remplace les `when (service)` dispersés par des propriétés ou méthodes du provider (ou d'un descripteur par service) : `BackupCodec`, `EncryptedPrefsManager`, `SettingsScreen` (qui sont désormais chez toi, dans `feature/settings/accounts/`), `SettingsViewModel` (la partie comptes), `DashboardViewModel`, `QuotaRefreshService` (ta zone `fetches` seulement), widgets.
- Garde les qualifiers Hilt existants (`@ClaudeRepository`…) le temps de la transition si d'autres classes en dépendent. Supprime-les à la fin s'ils ne servent plus.

### Phase 2 : multi-comptes pour tous les services (tâche 1, PRIORITÉ)
- Généralise le stockage des comptes dans `EncryptedPrefsManager` : un modèle générique (ex. `ServiceAccount(id, label, credential)` stocké sous `"${SERVICE}_accounts"`) pour CODEX, GEMINI, COPILOT, OPENROUTER, OPENAI, en plus de CLAUDE/ZAI/DEEPSEEK.
- **Migration transparente** : un credential unique existant devient le premier compte, sans aucune perte. Garde le format de stockage Claude/Zai/DeepSeek lisible (ou migre-le proprement). Teste la migration (`InMemorySharedPreferences` existe dans `<TEST>/core/security/`).
- Garde les API `saveCredential/loadCredential/hasCredential(service)` comme raccourcis rétrocompatibles (premier compte) tant que d'autres agents les appellent.
- Repositories : chaque provider sait récupérer le quota **par compte**. `QuotaInfo.accountId/accountLabel` sont renseignés partout.
- Flux OAuth/tokens (Codex, Gemini, Claude OAuth) : le rafraîchissement doit se faire **par compte** dans `TokenRefreshWorker` (ta zone : les fonctions `refresh*`/`refreshIfNeeded` et la boucle sur les comptes. Ne touche pas à l'enveloppe de `doWork()` ni à la planification, qui sont à A3). Le login Copilot (device flow) doit pouvoir ajouter un **nouveau** compte.
- `QuotaRefreshService` : ta zone `fetches` devient une itération sur `ProviderRegistry` × comptes.
- Dashboard (`feature/dashboard/**`) : affichage par compte pour tous les services (en t'inspirant de ce qui existe pour Claude/Z.ai/DeepSeek, nommage via `AccountNaming`).
- Widgets/tuile (`core/widget/**`, `core/tile/**`) : prise en charge de plusieurs comptes par service (choix du compte dans `WidgetConfigurationActivity` si pertinent).
- UI comptes (`feature/settings/accounts/**`) : une section générique « ajouter / renommer / supprimer / valider un compte », pilotée par le registre, qui remplace les sections spécifiques dupliquées. Chaînes dans `strings_accounts.xml` (3 langues).
- Sauvegarde (`BackupPayload`, `BackupCodec`, `ConfigBackupManager`) : export/import de tous les comptes de tous les services, **rétrocompatible** avec les sauvegardes existantes (anciens fichiers importables). Mets à jour `BackupCodecTest`. Ne touche pas à `BackupCrypto`/`QrChunkCodec` (testés par A3) ni à l'UI de sauvegarde (A2).
- `app/src/demoData/**` : adapte `SampleDataRepository` et les données de démo (plusieurs comptes sur au moins 2 services de plus).

### Tests (ta part de la tâche 5)
- Adapte les tests existants des repositories (`<TEST>/core/data/*RepositoryImplTest.kt`), `BackupCodecTest`, `EncryptedPrefsManagerClaudeAccountsTest`, `LastFetchRepositoryImplTest`.
- Ajoute : tests du `ProviderRegistry`, de la migration des comptes, du multi-comptes par service (MockWebServer), du `AccountsViewModel` et du `DashboardViewModel` (coroutines-test + Turbine). Ces ViewModels sont à toi, donc leurs tests aussi.
- Ne crée pas de tests de parsing DTO ni de tests `BackupCrypto`/`QrChunkCodec`/`FetchRateLimiter`/`BalanceConsumptionAggregator` : c'est A3.

### Critères de fin
- Les 8 services acceptent N comptes (ajout, renommage, suppression, validation), affichés sur le dashboard et dans les widgets, rafraîchis en arrière-plan, sauvegardés et restaurés.
- Ajouter un 9ᵉ service se résume à : un `AiService`, un DTO/ApiService, une implémentation `QuotaProvider` enregistrée dans Hilt et ses chaînes. Plus aucun `when(service)` à compléter ailleurs. Décris cette procédure en 10 lignes maximum dans ton résumé final.
- Build, lint et tests verts pour ton périmètre. Résumé final dans `COORDINATION.md` : fichiers créés/modifiés, migrations, points d'attention pour A2/A3.

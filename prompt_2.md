# Agent 2 — Découpage des paramètres (hors comptes) + alertes par seuil avec prédiction d'épuisement

Tu es l'**Agent 2** (tag `[A2]`). Deux autres agents travaillent **en même temps que toi, dans le même dépôt et le même répertoire de travail**. Lis tout ce document avant de commencer.

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

## 4. Voici ce que TU dois faire (Agent 2)

### Ordre de travail imposé
A1 commence par extraire la partie comptes de `SettingsScreen.kt`/`SettingsViewModel.kt`/`SettingsUiState.kt` (sa « phase 0 »). **Ne touche pas à ces trois fichiers tant que `COORDINATION.md` ne contient pas la ligne `[A1 …] PHASE 0 TERMINÉE`.** En attendant, commence par la partie alertes (étape A), qui vit dans de nouveaux fichiers. Relis `COORDINATION.md` entre chaque étape.

### Étape A : alertes configurables par seuil + prédiction d'épuisement (tâche 2)
Tout vit dans de nouveaux fichiers sous `<SRC>/core/alert/` (domaine) et `<SRC>/feature/settings/alerts/` (UI).
- **Modèle de règle**, par compte (clé `"${service.name}:${accountId ?: "default"}"`, contrat n°1) avec une règle par défaut par service :
  - seuil d'utilisation d'une fenêtre de quota (ex. ≥ 80 % sur `UsageWindow.utilization`, configurable, par fenêtre ou toutes fenêtres) ;
  - seuil de solde bas (`CreditBalance.totalBalance` / `AccountBalance`, dans la devise du compte) ;
  - alerte « épuisement prévu avant X heures/jours ».
- **Stockage** : ta propre classe (ex. `AlertPreferencesStore`), chiffrée de la même façon que le reste de l'app. Regarde comment `SecurePreferences`/`SecureValueCodec` sont utilisés, **sans modifier `EncryptedPrefsManager`** (contrat n°4). Module Hilt dans ton propre fichier (`di/AlertModule.kt`).
- **Prédiction d'épuisement** (`core/alert/DepletionPredictor.kt`) à partir de l'historique déjà stocké (`BalanceHistoryRepository` / `BalanceSnapshotDao`, `BalanceConsumptionAggregator` en lecture seule, contrat n°6) :
  - soldes : régression linéaire, ou moyenne glissante de la consommation sur une fenêtre configurable (ex. 7 jours), puis date estimée où le solde atteint 0 ou le seuil. Gère les recharges (hausses de solde) : il faut couper la série ou les ignorer, pas les compter comme une consommation négative. Si les données sont insuffisantes ou la consommation nulle, renvoie « pas de prédiction » ;
  - fenêtres de quota (%) : extrapolation de la vitesse d'utilisation jusqu'à `resetsAt`, pour indiquer si le quota sera épuisé avant le reset. Si tu as besoin d'historiser les pourcentages, demande-le à A1 dans `COORDINATION.md` (pas de changement de schéma Room de ta part, contrat n°7). Sinon, fais une version sans historique : deux mesures successives conservées dans ton store.
  - Logique pure, sans Android, injectable avec une `Clock` : elle doit être **entièrement testée unitairement**.
- **Évaluation** (`QuotaAlertEvaluator`) : prend la liste des `QuotaInfo` d'un rafraîchissement, applique les règles, gère l'anti-spam (une alerte ne se redéclenche qu'après repassage sous le seuil, ou après un reset de fenêtre) et renvoie les alertes à émettre.
- **Branchement** : dans `core/workmanager/QuotaRefreshService.kt`, **uniquement dans ta zone** (le bloc `if (prefsManager.isNotificationsEnabled()) { … }` / `checkForResets`), ajoute l'appel à l'évaluateur. Attention : le code actuel fait `distinctBy { it.service }`, qui ne garde qu'un compte par service. Tes alertes doivent porter sur **tous les comptes**. Ne touche ni à la construction de `fetches` (A1) ni au reste de la fonction (A3). Relis le fichier juste avant d'éditer.
- **Notifications** (`core/notification/**`, à toi) : canal dédié « Alertes de quota » (distinct du canal existant), une notification par compte et par type d'alerte, avec le nom du compte, la valeur actuelle, le seuil et la date d'épuisement prévue. Respecte la visibilité écran verrouillé existante (`LockScreenVisibility`).
- **Affichage de la prédiction** : dans `feature/history/**` (à toi), affiche la date d'épuisement estimée par compte (si disponible). Le dashboard appartient à A1 : n'y touche pas. Si tu veux un badge sur les cartes, propose-le dans `COORDINATION.md`.
- **UI de configuration** : `feature/settings/alerts/AlertsSettingsSection.kt` + `AlertsSettingsViewModel`. Il liste les comptes configurés (via `EncryptedPrefsManager`/registre d'A1 en lecture). S'ils ne sont pas encore disponibles pour tous les services, utilise les API existantes et adapte-toi quand A1 annonce la fin de sa phase 2.
- Les alertes ne sont **pas** ajoutées à la sauvegarde pour l'instant (`BackupPayload` appartient à A1). Note-le dans ton résumé comme suite possible.

### Étape B : découpage de `SettingsScreen.kt` / `SettingsViewModel.kt` (tâche 3), après la phase 0 d'A1
- Objectif : plus aucun fichier de paramètres au-dessus d'environ 400 lignes. Un ViewModel par domaine, chacun testable isolément :
  - `feature/settings/display/` : thème, mode d'affichage des quotas, visibilité écran verrouillé, devise et taux de change (reprends `DisplaySettingsSection.kt` et `CurrencySettingsSection.kt`, existants) → `DisplaySettingsViewModel` ;
  - `feature/settings/backup/` : export ZIP/QR, import, scan multi-QR, mot de passe et indicateur de robustesse (`BackupSection`, `ExportQrDialog`, `ImportPinDialog`, `MultiQrScanProgress`, `PasswordStrengthIndicator`, génération QR) → `BackupViewModel` ;
  - `feature/settings/security/` : verrouillage de l'app, données de démo, zone dangereuse (supprimer toutes les données) → `SecuritySettingsViewModel` ;
  - `feature/settings/refresh/` : intervalle de rafraîchissement, notifications, bloc batterie/arrière-plan (`RefreshIntervalSection`, `BackgroundRefreshBlock`, `NotificationsSection`) → `RefreshSettingsViewModel` ;
  - `feature/settings/alerts/` : étape A ;
  - comptes : appelle simplement `AccountsSettingsContent()` d'A1 (contrat n°2). **Ne modifie rien dans `feature/settings/accounts/`.**
- **Sous-écrans** : l'écran Paramètres devient un menu (liste d'entrées : Comptes, Affichage, Alertes, Rafraîchissement, Sauvegarde, Sécurité, Diagnostic, Crédits) qui navigue vers des sous-écrans. Ajoute les routes `settings/<domaine>` dans le `NavHost` de `MainActivity.kt` (ta zone : tes routes et callbacks seulement). L'entrée « Diagnostic » appelle `navController.navigate("diagnostics")` (contrat n°5). Avant de la livrer, vérifie dans `COORDINATION.md` ou dans `MainActivity` que la route d'A3 existe. Sinon, laisse l'entrée masquée derrière un flag et signale-le.
- Déplace sans changer le comportement. Conserve les clés de préférences, les valeurs par défaut, et l'auto-sauvegarde avec debounce (`observePendingChanges`/`flushPendingChanges`) si elle concerne encore ton périmètre.
- Les helpers partagés (`ValidationResultLabel`, `ExpandableSectionHeader`, `SettingsFields.kt`) : s'ils sont utilisés par toi **et** par A1, mets-les dans `feature/settings/common/` en gardant exactement les mêmes signatures, puis signale le déplacement dans `COORDINATION.md` pour qu'A1 ajuste ses imports. Sinon, laisse-les en place.
- À la fin, `SettingsViewModel.kt` ne doit plus exister, ou n'être qu'un mince ViewModel de navigation. Supprime-le seulement si **plus rien** n'y fait référence, y compris chez A1 : vérifie avec un grep et dans `COORDINATION.md`.
- `app/src/androidTest/**` (captures `StoreScreenshotsTest`, `DemoVideoTest`) : si tes changements de navigation les cassent, adapte-les (tu en es responsable pour la partie paramètres).
- Chaînes nouvelles → `strings_settings_alerts.xml` (3 langues). Les chaînes existantes restent où elles sont.

### Tests (ta part de la tâche 5)
- `DepletionPredictor`, `QuotaAlertEvaluator` (seuils, anti-spam, recharges, données insuffisantes, multi-comptes), `AlertPreferencesStore`.
- Tes ViewModels : `DisplaySettingsViewModel`, `BackupViewModel` (flux export/import, erreurs, scan multi-QR), `SecuritySettingsViewModel`, `RefreshSettingsViewModel`, `AlertsSettingsViewModel`, avec kotlinx-coroutines-test et Turbine.
- Ne fais pas les tests de `BackupCrypto`/`QrChunkCodec`/`BalanceConsumptionAggregator`/`FetchRateLimiter`/DTO (A3) ni ceux d'`AccountsViewModel`/`DashboardViewModel` (A1).

### Critères de fin
- L'utilisateur peut définir, par compte, un seuil de quota, un seuil de solde bas et un horizon d'épuisement. Il reçoit des notifications pertinentes et non répétitives, et voit la date d'épuisement prévue dans l'historique.
- Paramètres découpés en sous-écrans et ViewModels par domaine, sans régression fonctionnelle, fichiers ≤ ~400 lignes.
- Build, lint et tests verts pour ton périmètre. Résumé final dans `COORDINATION.md` : nouvelle structure des paramètres, fichiers créés/supprimés, points en attente chez A1/A3.

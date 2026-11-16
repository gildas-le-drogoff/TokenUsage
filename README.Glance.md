Glance ne manipule jamais les secrets. Le widget lit uniquement un cache de données d'affichage, sans aucune credential.

---

## La chaîne widget : découplée des secrets

1. **`provideGlance`** (`QuotaGlanceWidget.kt:54`, `WidgetRegistry.kt:148`) → `WidgetSnapshots.load()` → `WidgetPrefsManager` : SharedPreferences non chiffrées (`tokenusage_widget_prefs`) qui ne contiennent que du rendu — labels des fenêtres, taux d'utilisation (float), timestamps de reset, tier, texte de balance (`WidgetPrefsManager.kt:60-142`). Le state Glance lui-même ne stocke qu'un timestamp de révision (`WidgetRegistry.kt:57`).
2. **Les credentials sont utilisés en amont, in-process** : `QuotaRefreshWorker` → `QuotaRefreshService.refreshNow()` (`QuotaRefreshService.kt:64`) lit les tokens via `EncryptedPrefsManager`, appelle les APIs, puis `WidgetQuotaPublisher.publish()` ne met que les données d'affichage en cache (`WidgetQuotaPublisher.kt:23-39`) avant de déclencher `WidgetRegistry.refreshAll()`. Le bouton refresh du widget (`RefreshWidgetAction`, `WidgetRegistry.kt:103`) fait pareil dans le process de l'app.

---

## Protection des secrets au repos

Le stack dans `core/security/` est solide et idiomatique :

- **`SecurePreferences.kt`** : valeurs chiffrées en Tink AES-256-GCM avec AAD lié au store et à chaque clé (`SecurePreferences.kt:87-93`) ; les noms de clés eux-mêmes sont chiffrés en AES-256-SIV déterministe — même le fichier XML ne révèle pas `CODEX_access_token`.
- **`TinkKeysetVault.kt`** : les keysets Tink sont wrapped (chiffrés) avant d'être persistés, jamais en clair.
- **`KeystoreMasterKey.kt`** : clé maître AES-256 dans AndroidKeyStore, StrongBox si disponible, sinon TEE, avec self-test au chargement et rejet de la clé si le test échoue.
- **Fail-closed** : si la crypto échoue, `SecureStorageFactory.wipeEverything()` reset tout plutôt que de persister en clair (`SecureStorageFactory.kt:39-59`) ; `IntegrityGuard.isVaultLocked` bascule sur `LockedPreferences` (lectures vides, écritures no-op) sur appareil rooté/Frida/debugger en release.
- **Périmètre** : `allowBackup=false`, `data_extraction_rules.xml` exclut tout en cloud-backup et device-transfer ; DB SQLCipher avec clé dans un store chiffré séparé (`DatabaseKeyProvider.kt`) ; release non debuggable + R8 full mode.
- **Git** : `keystore.properties` et `local.properties` ignorés, `gradle.properties` propre, le keystore upload est généré avec un mot de passe aléatoire à chaque fois (`generate-keystore-and-build.sh`).

---

## Limites résiduelles (honnêteté oblige)

- **Données affichées ≠ secrets, mais visibles sans unlock** : le texte de balance et les pourcentages vivent en clair dans le sandbox de l'app et transitent via RemoteViews au processus du launcher — et le widget peut s'afficher sur l'écran de verrouillage. C'est inhérent aux widgets Android (le launcher doit pouvoir rendre le contenu), pas une fuite de credentials, mais si tu veux du parano : option pour masquer les balances du widget quand l'app est verrouillée.
- **`IntegrityGuard` est du best-effort** : détection root/Xposed contournable par un attaquant déterminé ; la vraie barrière reste le hardware keystore.
- **Le refresh depuis le widget fonctionne même app verrouillée** — cohérent (le verrou biométrique protège l'UI, pas le fond), mais c'est un choix à connaître.

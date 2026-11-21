# Rafraîchissement en arrière-plan

## Cartographie : qui déclenche quoi

| Déclencheur | Appelle | Notes |
|---|---|---|
| `TokenUsageApplication.onCreate` | `RefreshScheduler.applySaved()` + `WorkManagerInitializer.scheduleTokenRefresh()` | À chaque démarrage du processus (y compris quand `BootReceiver` le réveille). |
| `MainActivity.onCreate` | idem | Redondant mais sans effet : les travaux sont uniques. |
| `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) | `scheduleTokenRefresh()` | Le travail des quotas est replanifié par `Application.onCreate`. |
| Paramètres › Rafraîchissement, restauration d'une sauvegarde | `RefreshScheduler.apply()/applySaved()` | `0` = travail des quotas annulé. |
| `QuotaRefreshWorker` (périodique, `quota_periodic_refresh`) | `QuotaRefreshService.refreshNow(PERIODIC_WORKER)` | Intervalle choisi par l'utilisateur, minimum 15 min. |
| `TokenRefreshWorker` (périodique, `token_periodic_refresh`, 30 min) | `refreshIfNeeded()` pour chaque compte de chaque service | Sous `TokenRefreshLock`. |
| `RefreshReceiver` (action « Rafraîchir » de la notification) | `QuotaRefreshService.refreshNow()` | |
| Widgets (`RefreshWidgetAction`, `WidgetConfigurationActivity`) | `QuotaRefreshService.refreshNow()` | |
| Dashboard (`DashboardViewModel`) | `ProviderRegistry.targets()` directement | Limité par `FetchRateLimiter` (15 s) et mis en cache dans `LastFetchRepository`. |
| `QuotaTileService` | Lecture seule des données publiées | Ne déclenche pas de fetch. |

## Chevauchements

- **Travaux uniques** : `enqueueUniquePeriodicWork` avec `ExistingPeriodicWorkPolicy.UPDATE`. Un changement d'intervalle met à jour le travail sans le recréer. Reboot, mise à jour et double planification (Application + Activity) ne créent pas de doublon.
- **`refreshNow()` concurrents** : `QuotaRefreshService` passe par `SingleFlight`. Si le worker, une notification et un widget arrivent en même temps, ils partagent la même exécution et le même résultat. Il n'y a donc ni double fetch ni double notification.
- **Refresh tokens à usage unique** (Claude, Codex, Gemini) : tout échange de refresh token passe par `TokenRefreshLock`. Cela vaut pour `TokenRefreshWorker` (verrou sur tout `doWork()`) comme pour les repositories (`BaseQuotaProvider.refreshSerialized`). Sous le verrou, le credential stocké est relu : si un autre l'a déjà rafraîchi, celui-ci est réutilisé et le refresh token n'est pas réutilisé.
- Le dashboard ne passe pas par `SingleFlight`, mais il est limité à un fetch toutes les 15 s et partage le même verrou de tokens.

## Doze et restrictions des constructeurs

- **Contraintes** : les deux workers exigent `NetworkType.CONNECTED`. En Doze, WorkManager les diffère jusqu'à la fenêtre de maintenance suivante. C'est voulu : pas d'alarme exacte, pas de wakelock.
- **Retry** : backoff exponentiel à partir de 1 min, limité à `WorkRetryPolicy.MAX_ATTEMPTS` (4) exécutions. Au-delà, le travail est abandonné jusqu'au prochain passage périodique, ce qui évite de reboucler jusqu'à 5 h sur un token révoqué.
- **Foreground / expedited** : aucun travail n'est lancé en expedited ou en foreground aujourd'hui. `QuotaRefreshWorker.getForegroundInfo()` fournit malgré tout `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, déclaré dans le manifeste (`SystemForegroundService`, permission `FOREGROUND_SERVICE_DATA_SYNC`), comme l'exige Android 14+.
- **Intervalle minimal** : WorkManager impose 15 min (`MIN_INTERVAL_MINUTES`), et l'intervalle demandé est ramené à ce minimum.
- **Optimisation batterie et constructeurs** : sur certains constructeurs (Xiaomi, Huawei, Oppo/OnePlus/Realme, Samsung, Vivo…), les travaux périodiques sont retardés ou supprimés tant que l'application n'est pas exemptée. L'écran Diagnostic (route `diagnostics`) affiche l'état réel : optimisation batterie, bucket App Standby, restriction d'arrière-plan, Doze, dernier et prochain passage de chaque travail. Il donne aussi des conseils locaux par constructeur (`ManufacturerAdvice`). La demande d'exemption se fait dans Paramètres › Rafraîchissement.

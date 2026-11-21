# Background Refresh

## Mapping: Who Triggers What

| Trigger | Calls | Notes |

|---|---|---|

| `TokenUsageApplication.onCreate` | `RefreshScheduler.applySaved()` + `WorkManagerInitializer.scheduleTokenRefresh()` | Every time the process starts (including when `BootReceiver` wakes it up). |

| `MainActivity.onCreate` | same | Redundant but ineffective: jobs are unique. |

| `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) | `scheduleTokenRefresh()` | Quota jobs are rescheduled by `Application.onCreate`. |

| Settings › Refresh, Restore from Backup | `RefreshScheduler.apply()/applySaved()` | `0` = quota work canceled. |

| `QuotaRefreshWorker` (periodic, `quota_periodic_refresh`) | `QuotaRefreshService.refreshNow(PERIODIC_WORKER)` | Interval chosen by the user, minimum 15 min. |

| `TokenRefreshWorker` (periodic, `token_periodic_refresh`, 30 min) | `refreshIfNeeded()` for each account of each service | Under `TokenRefreshLock`. |

| `RefreshReceiver` (notification action "Refresh") | `QuotaRefreshService.refreshNow()` |

| Widgets (`RefreshWidgetAction`, `WidgetConfigurationActivity`) | `QuotaRefreshService.refreshNow()` |

| Dashboard(`DashboardViewModel`) | `ProviderRegistry.targets()` directly | Limited by `FetchRateLimiter` (15 seconds) and cached in `LastFetchRepository`. |

| `QuotaTileService` | Read-only of published data | Does not trigger a fetch. |

## Overlaps

- **Single Jobs**: `enqueueUniquePeriodicWork` with `ExistingPeriodicWorkPolicy.UPDATE`. A change in interval updates the job without recreating it. Rebooting, updating, and double scheduling (Application + Activity) do not create duplicates.

- **concurrent `refreshNow()`**: `QuotaRefreshService` passes through `SingleFlight`. If the worker, a notification, and a widget arrive at the same time, they share the same execution and the same result. Therefore, there is neither double fetch nor double notification.

- **Single-use refresh tokens** (Claude, Codex, Gemini): all refresh token exchanges go through `TokenRefreshLock`. This applies to `TokenRefreshWorker` (lock on any `doWork()`) as well as to repositories (`BaseQuotaProvider.refreshSerialized`). Under the lock, the stored credential is reread: if another worker has already refreshed it, that credential is reused, and the refresh token is not.

- The dashboard does not use `SingleFlight`, but it is limited to one fetch every 15 seconds and shares the same token lock.

## Doze and constructor restrictions

- **Constraints**: both workers require `NetworkType.CONNECTED`. In Doze, WorkManager defers them until the next maintenance window. This is intentional: no precise alarm, no wakelock.

- **Retry**: Exponential backoff starting at 1 minute, limited to `WorkRetryPolicy.MAX_ATTEMPTS` (4) executions. Beyond that, the job is abandoned until the next periodic pass, preventing loops of up to 5 hours on a revoked token.

- **Foreground / Expedited**: No jobs are launched in expedited or foreground mode today. `QuotaRefreshWorker.getForegroundInfo()` still provides `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, declared in the manifest (`SystemForegroundService`, permission `FOREGROUND_SERVICE_DATA_SYNC`), as required by Android 14+. - **Minimum Interval:** WorkManager enforces a 15-minute interval (`MIN_INTERVAL_MINUTES`), and the requested interval is reduced to this minimum.

- **Battery Optimization and Manufacturers:** On some manufacturers (Xiaomi, Huawei, Oppo/OnePlus/Realme, Samsung, Vivo, etc.), periodic jobs are delayed or canceled until the application is exempted. The Diagnostics screen (route `diagnostics`) displays the current status: battery optimization, App Standby bucket, background restriction, Doze, and the last and next executions of each job. It also provides manufacturer-specific advice (`ManufacturerAdvice`). Exemption requests are made in Settings › Refresh.

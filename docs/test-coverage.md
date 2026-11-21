# Test Coverage Analysis — Synthesis

## Overall coverage :

| Metric       | Report |
| ------------ | ------ |
| Classes      | 80.5%  |
| Methods      | 63.5%  |
| Branches     | 51.2%  |
| Lines        | 66.6%  |
| Instructions | 61.2%  |

## Strengths

- **`core.workmanager`**: 96–99% across all metrics.
- **`core.domain.model`**: 95–100%, well-tested core business logic.
- **`core.network.*`** (gemini, openai, openrouter, zai, fx, copilot…): 100% classes/methods, >92% lines.
- **`core.alert`, `core.backup`, `core.data`, `core.diagnostics`, `core.notification`, `core.provider`, `core.status`**: all >80% lines.
- **`ui.theme`, `ui.i18n`, `ui.components`, `core.util`**: almost perfect.

## Todo

**1. Packages**

- `feature.diagnostics`: 13.1% lines / 6.9% instructions, branches at 0%
- `feature.history`: 20.2% lines / 16.1% instructions
- `core.demo`: 30.3% / 23.8%
- `core.widget`: 32.9% / 29.8% — 10,270 uncovered instructions
- `feature.settings.backup`: 34% / 25.8%, branches 20%
- `feature.settings.security`: 32.7% / 23.1%, branches **5.6%**
- `di`: 34.9% / 36%, only 11% of classes (8/70)

**2. Structural weakness: branches**

Network packages have 95–100% line coverage but only 11–47% branch coverage (e.g., `claudeplatform` 11.6%, `codex` 24.7%, `claude` 28.7%, `openai` 26.9%, `gemini` 38.3%). Tests should cover the happy path and also more errors / retries, malformed responses, or timeouts.

**3. Concentration of uncovered code**
`core.widget` (10,270) + `feature.dashboard` (6,067 uncovered) + `feature.settings.backup` (6,772) + `feature.settings.accounts` (7,914) represent **~30,000 instructions, ~40% of all uncovered code**.

## Recommendations

1. **Add branch tests on the network layer** (claude, claudeplatform, codex, openai, gemini): mock HTTP errors, timeouts, invalid JSON — quick gain, high robustness impact.
2. **`core.widget`**: highest-volume target; start with business-logic widgets.
3. **`feature.settings.security` (5.6% branches) and `feature.settings.backup` (20%)**: functional (security, backup).
4. **`di`**: test the injection graph configuration to catch wiring errors at startup.
5. **CI thresholds**: enforce branch ratio ≥ 60% on new packages and an overall branch threshold (currently 51%) rather than line-based thresholds.

## Conclusion

The foundation is healthy on **core business logic, domain, workmanager, and UI** (80–100%). Weaknesses are **localized but concentrated**: network branches, widgets/dashboard, settings backup/security. The next step is **not more line tests, but branch and error-case tests**.

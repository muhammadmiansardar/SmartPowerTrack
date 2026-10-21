# Prototype test plan

The automated suite runs the Spring Boot application through MockMvc against an isolated in-memory H2 database. It disables demonstration seeding and creates fresh accounts per case. These are integration checks of actual HTTP, authentication, CSRF, database constraints and calculation behavior. They do not replace browser, concurrency, installation or deployment testing.

## Run and retain evidence

```sh
mvn test
```

Keep `target/surefire-reports/za.ac.smartpowertrack.PrototypeIntegrationTest.txt` and its XML report with the execution date, Java version and environment. A test plan or source file is not a passed-test result. Use only actual output from the current revision in the academic report.

## Recorded development run

On **21 October 2026**, the development build passed **15 tests with zero failures and errors**: 10 `PrototypeIntegrationTest` cases and 5 `AnalyticsServiceTest` cases. The HTTP integration class completed in 3.140 seconds; the Maven build took 5.876 seconds overall. The environment used **Java 25.0.3, Spring Boot 3.5.16 and H2**. These are functional results, not a six-user performance benchmark, a MySQL result or proof that the minimum Java 17 runtime has been tested. The latest final build passed all 15 tests in 5.188 seconds. Passing summaries are included under `docs/test-evidence/`; Maven also generates reports under `target/surefire-reports/`.

## Automated cases

| Case / Java method | Requirement | Independent acceptance check |
|---|---|---|
| `registrationLoginLogoutRequireCsrfAndProtectPasswords` | FR-01; security | Protected endpoint rejects anonymous access; missing CSRF blocks registration/logout; correct credentials authenticate; incorrect credentials fail; stored value is not plaintext |
| `applianceAndUploadOwnershipIsEnforcedOnReadsAndChanges` | FR-01, FR-07, FR-08 | A second account cannot view or modify the first account's appliance/upload or download its file |
| `intervalEnergyAndCostMatchAnIndependentCalculationAndDuplicateOverlapAreRejected` | FR-02, FR-03, FR-04 | 1,000 W over 1,800 seconds equals 0.5 kWh and R1.60 at R3.20/kWh; duplicate, overlapping and negative readings do not inflate totals and leave rejection records; partial coverage remains visible |
| `budgetThresholdCreatesAnAlertOnlyOnceAndAlertStatusCanChange` | FR-04, FR-05 | Consumption crossing a small budget produces an alert; repeated refresh does not duplicate it; marking as read persists |
| `forecastNeedsSevenCompleteDaysAndPredictsKnownConstantHistory` | FR-06 | Six complete days remain unavailable; seven full days at 24 kWh/day yield a seven-day forecast of 168 kWh, R537.60 at R3.20/kWh, with periods recorded |
| `prepaidEstimateRequiresOpeningAndDeductsOnlyPostOpeningSimulatedEnergy` | FR-04, FR-05 | No opening means unavailable; an opening halfway through a 1 kWh interval deducts only 0.5 kWh × R3.20; R250 plus R50 less R1.60 equals R298.40, isolated by account |
| `pendingImageValuesNeedConfirmationAndRejectedValuesDoNotChangeAppliances` | FR-07 | A pending suggestion does not alter a rating; confirmation applies it; a rejected suggestion cannot subsequently update it |
| `realUploadHasNoInventedExtractionAndCumulativeMeterDoesNotBecomeConsumption` | FR-07 | A real image receives no fabricated extracted number; confirmation of a cumulative meter value creates no interval consumption; a text file is rejected |
| `billNeedsValidDatesAndManualRecordsDoNotInflateSimulatedTotals` | FR-03, FR-07 | Missing/reversed bill periods fail; valid bill data remain a manual household record; the separate simulated total remains 0.5 kWh |
| `settingsAreValidatedAndScopedToTheCurrentHousehold` | FR-01, FR-04 | One account changes its rate/settings without affecting another; a negative rate is rejected |

The additional `AnalyticsServiceTest` cases check interval allocation across midnight, duplicate/overlap rejection, forecast availability and presence of a holdout metric on seeded history, account isolation and prepaid deduction across an opening timestamp. The holdout metric's independent arithmetic remains a further check below. These tests do not establish any of the manual checks below.

## Manual and further automated acceptance checks

Record **Pass/Fail/Not run**, actual result and evidence for each case. The following checks are not claimed complete simply because the integration suite passes.

| ID | Check | Acceptance |
|---|---|---|
| M-01 | Start from a clean local installation | Java/Maven prerequisites are clear; startup creates the database and demo login; restart preserves local data |
| M-02 | Responsive browser navigation | Dashboard, tables, dialogs and forms work at desktop and mobile widths; labels/units remain readable; record browser versions |
| M-03 | Keyboard and screen-reader basics | Focus is visible, forms have labels, actions are reachable, and charts have numerical alternatives |
| M-04 | Simulator operation | At configured interval, only active user-owned appliances receive non-overlapping readings; no future readings; repeated immediate run safely adds no new interval |
| M-05 | Midnight/month boundaries | Intervals spanning midnight are allocated correctly; monthly budgets and selected-period totals use the intended dates |
| M-06 | Prepaid calculations | No opening means unavailable; top-ups after the opening increase the estimate; consumption is deducted only from the relevant interval portion; manual bills/meter values do not affect it |
| M-07 | Alert boundaries | Exact threshold, just below/above, high usage and low balance behave as specified; refresh/reprocessing do not duplicate alerts |
| M-08 | Forecast validation | Missing days/low coverage yield unavailable; horizon bounds are validated; holdout MAE matches independent arithmetic; no result is described as real accuracy without real data |
| M-09 | Image validation/lifecycle | Oversize, invalid signature, unsupported content, bad appliance ownership and retry/review transitions behave correctly; image paths cannot be accessed publicly |
| M-10 | CSV export | Only current account's records appear; dates, sources and units remain intelligible; spreadsheet-formula-like names are handled safely |
| M-11 | Account-scoped reset/load | Reset/load cannot alter another account; loading does not silently duplicate history |
| M-12 | Backup/restore | With application stopped, copy database plus uploads, restore, restart and verify sample records, relationships and protected image access |
| M-13 | Six-user performance target | Ordinary dashboard requests complete within the proposed three-second target under a documented workload; record hardware, dataset, request mix and response measurements |
| M-14 | Optional MySQL profile | Schema creation, seed, CRUD, aggregation and integration checks work against the actual chosen MySQL version; H2 results alone do not satisfy this check |
| M-15 | Deployment/security | HTTPS, cookie settings, secrets, retention, logs, rate limiting and authorization review are appropriate before hosting beyond the local demonstration |

## Failure handling and report evidence

Record the request/scenario, expected result, actual result, relevant response/log, defect fix and rerun outcome. Do not include passwords, session tokens or private bill images in report evidence. Keep expected arithmetic separate from the application calculation so tests can detect formula mistakes rather than repeat them.

## Additional executed checks

On 21 October 2026, 30 HTTP dashboard requests across six concurrent logged-in sessions using the same demo household had a mean duration of 0.2425 seconds and a maximum of 0.5018 seconds. The dataset had 8 appliances and 21 days of simulated readings; the server ran locally with Java 25.0.3 and embedded H2. This is a limited local benchmark, not independent multi-household production load validation.

A clean stopped copy of the file database and private uploads was started on a separate local port with demo seeding disabled. Login, eight appliances, the forecast, a confirmed review record and the owner-protected PNG remained available. Copy checksums matched before startup. The original database was then restarted successfully. Evidence JSON is included under `docs/test-evidence/`.

Browser checks covered desktop and mobile layouts, dashboard and forecast displays, plus creating and confirming a sample meter image. Keyboard labels and missing-value display were inspected. Full accessibility certification and MySQL deployment remain unverified.

The prebuilt distribution was also launched from a clean package directory without Maven on an alternate local port. It created its own database, authenticated the demo account, and returned eight appliances and an available forecast. See `docs/test-evidence/packaged-install.json`.

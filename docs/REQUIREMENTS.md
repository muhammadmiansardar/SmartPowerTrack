# Requirements and implementation boundaries

This checklist translates Phase 3 into prototype behavior. It describes the intended implementation and the evidence needed to accept it. It is not a record of lecturer approval or proof that every requirement has already been tested.

| ID | Prototype behavior | Automated integration checks | Further acceptance work |
|---|---|---|---|
| FR-01 Account management | Session registration/login/logout, private appliances and settings; hashed passwords | Registration/login/logout with CSRF; private records denied to another account; settings validation and isolation | Password policy, session expiry, complete profile flows and user assessment |
| FR-02 Simulated collection | Timestamped average-power intervals; validation, duplicate/overlap rejection and missing-data coverage | Independent interval-energy example; invalid/duplicate/overlap rejection; recorded rejection reasons | Scheduled simulator timing, concurrent submissions and sensor lifecycle cases |
| FR-03 Analytics | Daily/appliance summaries, trends, comparison periods, rankings and coverage | Known energy and cost totals; manual records excluded from simulated totals | Ranking/comparison edge cases, midnight boundaries and incomplete-day averages |
| FR-04 Budgeting | Positive monthly budget; estimated spending and threshold status | Known-cost calculation and a crossed budget threshold | Month rollover, rate changes, rounding and complete validation boundaries |
| FR-05 Alerts | Dashboard high-consumption/budget alerts; estimated prepaid alerts only with an opening balance | Budget alert created once and read status updated; prepaid unavailable/opening/top-up and partial-interval arithmetic | All high-usage/low-balance thresholds; invalid transitions and deduplication under concurrency |
| FR-06 Forecasts | Simple recent-history forecast; insufficient history clearly unavailable; periods and assumptions shown | Six complete days unavailable; seven complete constant days produce the independently expected forecast | Holdout metric validation, sparse history, horizon selection and real-data evaluation |
| FR-07 Reviewed image capture | Manual entry from uploaded images; known demonstration fixture suggestions; confirm/correct/reject | Pending state does not update appliance; confirmation updates; rejection blocks acceptance; cumulative meter not counted as energy; bill dates validated; upload ownership/type checks | Real external AI integration is not implemented; file/size/retention/retry edge cases need further verification |
| FR-08 Dashboard | Account-specific summaries and unavailable states, with export and responsive pages | Authenticated dashboard data and scoped results exercised by other tests | Visual/browser/mobile checks, keyboard accessibility, exports and rendering completeness |

Automated cases are in `PrototypeIntegrationTest`. Run the suite and retain its generated report before describing a case as passed. Manual checks are listed in `TEST-PLAN.md`.

## Design decisions resolved for the prototype

1. **Reading model:** `recorded_at` is the interval end. `power_w` is average power, not an instantaneous sample. Interval duration is explicit. The energy formula is `W / 1000 × seconds / 3600`.
2. **Sources and totals:** Simulated appliance readings produce simulated appliance/day aggregates. Household totals are calculated from these aggregates; manual household bills are shown separately. A cumulative meter value does not become interval energy.
3. **Prepaid history:** `prepaid_transaction` extends the original nine-entity analysis model with an opening amount and timestamped top-ups. The estimate deducts only qualifying simulated consumption after the opening. No opening amount means no estimated balance.
4. **Rejection audit:** `reading_rejection` records invalid input reasons without accepting the reading into consumption calculations.
5. **Image state:** Processing (`PENDING`, `READY`, `FAILED`) and review (`PENDING`, `CONFIRMED`, `REJECTED`) are separate fields. Accepted updates require validation and explicit confirmation.
6. **Database:** H2 is the self-contained default; MySQL is optional and must be verified separately. The schema is relational and retains ownership links, unique budgets per user/month and unique sensor/timestamp readings.
7. **Forecast:** A transparent trailing seven-day mean is used instead of claiming a trained machine-learning model. At least seven consecutive adequately covered complete days are required; a three-day holdout error is available only with enough additional history.
8. **Tariff:** A configurable flat rate supports illustrative cost estimates. Utility tariff blocks, fixed charges and billing reconciliation are outside the current implementation.

## Quality requirements and evidence

- **Usability:** Clear units, timestamps, explicit estimates and plain validation messages. Browser/user checks remain necessary.
- **Performance:** Phase 3's target is ordinary dashboard responses within three seconds for six concurrent demonstration users under recorded conditions. Passing functional tests does not establish that target; measure it with documented hardware, data volume and requests.
- **Security:** Session authentication, CSRF checks, ownership checks and password hashing are essential prototype controls. HTTPS deployment, secrets, cookie hardening, rate limiting and independent assessment remain deployment work.
- **Reliability:** Reject invalid/overlapping inputs, avoid double counting, expose missing coverage and keep accepted records unchanged on rejected image review. Test backups and restoration independently.
- **Maintainability:** Keep controllers, database access, analytics and the browser interface separate. Use the shared API contract for integration.
- **Transparency:** Label all simulated readings, manual/image capture mode, tariff estimates and forecast assumptions. Simulated questionnaire profiles do not prove real user demand.

## What remains outside demonstrated completion

External AI/OCR extraction, live smart-meter integration, authoritative prepaid reconciliation, utility-specific tariffs, production hosting, a completed security review, real household forecast validation and the formal academic report/presentation are not established by this prototype.

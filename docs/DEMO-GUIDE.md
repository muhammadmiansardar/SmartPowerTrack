# Demonstration guide

Use illustrative data only. Explain at the start: **this is a software prototype using simulated electricity readings; image capture is a demonstration/manual review workflow, and monetary values are estimates.**

## Prepare

1. Start the application using the instructions in the README and open `http://127.0.0.1:8080`.
2. Sign in with `demo@powertrack.local` / `PowerTrack123!`.
3. Confirm that the interface clearly labels simulated data, estimates and demonstration image capture. Load sample data only if the account is empty.
4. Keep an additional registered account available for the isolation demonstration. Do not enter real household bills or personal images.

## Suggested presentation sequence

| Step | Action | Explain / inspect |
|---|---|---|
| 1 | Open the dashboard and select a history period | Household consumption, estimated costs, appliance contributions, latest reading timestamp and data coverage |
| 2 | Open appliances and add a named appliance with a power rating | A simulated sensor is associated with the appliance; no hardware is connected |
| 3 | Run the simulator or enable its periodic operation | New readings are bounded by elapsed time and do not extend into the future; repeated immediate runs may add no new interval |
| 4 | Set a positive monthly budget below existing estimated spending | Budget status and dashboard alert change; repeated refreshes should not create duplicate alerts |
| 5 | Mark an alert as read, then dismissed | Alerts retain an account-specific status |
| 6 | Record an estimated prepaid opening amount and a top-up | Balance is opening plus top-ups minus qualifying estimated simulated consumption; it is not a utility-issued balance |
| 7 | View a seven-day forecast | Show the source history, forecast period, method and estimate label; do not describe simulated-data error as real-household accuracy |
| 8 | Load an appliance image sample | The sample supplies an illustrative suggestion. Its pending value must not change the appliance until confirmation |
| 9 | Correct and confirm the sample value | The accepted appliance rating changes only after validation and confirmation |
| 10 | Load another sample and reject it | The accepted appliance must remain unchanged |
| 11 | Upload a meter image and enter its value manually | There is no external AI extraction. A cumulative reading is kept as an observation, not counted as interval energy |
| 12 | Enter a bill value with its period | The manual household record remains separate from simulated appliance totals, avoiding double counting |
| 13 | Export consumption and inspect the CSV | Values should retain useful units, timestamps and source labels |
| 14 | Sign out and enter the second account | The first account's appliances, uploads, readings and alerts must not appear |

## Show unavailable and invalid states

In an empty new account, the forecast should be unavailable until there are enough complete days. Without an opening prepaid amount, the estimated prepaid balance should be unavailable. Missing readings should reduce coverage rather than appear as confirmed zero consumption. Try a negative appliance rating or tariff and show that validation explains the error.

Real uploaded images do not receive fabricated extraction values. If the demonstration uses the sample action, identify the known fixture before showing its suggestion. This proves the review workflow, not image recognition accuracy.

## Close the demonstration accurately

Describe the implemented collection, calculation, budgeting and review workflows. State the remaining work: external AI integration, optional MySQL verification, full browser/performance/security checks, deployment configuration and the final report/presentation evidence. The Phase 3 questionnaire profiles were simulated requirements-analysis examples, not completed interviews.

## Reset and retain evidence

Record application version, test date and browser. Keep screenshots of key workflows and genuine test output for the implementation report. The account-scoped reset is for disposable sample data; use a separate test account rather than clearing evidence you need to retain. Stop the application before making a local database/uploads backup.

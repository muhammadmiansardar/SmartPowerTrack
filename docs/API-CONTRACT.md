# Shared prototype contract

Java 17+, Spring Boot 3.5.16, JDBC, H2 file DB by default, optional MySQL profile. Package `za.ac.smartpowertrack`. The application separates account and upload handling, analytics services, browser assets and integration tests.

All JSON camelCase. Errors: {message, field?}. Session cookie authentication. GET /api/auth/session returns {authenticated, user: {id,name,email,householdName,tariff,...}|null, csrfToken, aiMode:"demo", database:"H2 demo"}. Include `X-CSRF-Token` on every mutation; `credentials:same-origin`. Login/register return same session object. GET session first. Login {email,password}; register {name,email,password,householdName}. Demo: demo@powertrack.local / PowerTrack123!.

Core Db API: `public final JdbcTemplate jdbc; public List<Map<String,Object>> list(String sql,Object...args); public Map<String,Object> one(String sql,Object...args)` (404 if absent); `public long insert(String table,Map<String,Object> values)`; `public int execute(String sql,Object...args)`. Map rows camelCase; timestamps serialized ISO local time (Africa/Johannesburg). `ApiException(int status,String message)`; `Auth.require(HttpSession)` returns long userId. All controllers must scope EVERY record to owner.

Tables (snake_case, all IDs BIGINT auto increment):
app_user(id,name,email UNIQUE,password_hash,household_name,tariff DECIMAL default3.20,day_threshold_kwh DECIMAL default25,budget_threshold_pct DECIMAL default80,low_balance_threshold DECIMAL default100,simulation_enabled BOOLEAN defaultfalse,created_at TIMESTAMP).
appliance(id,user_id FK,name,category,power_rating_w DECIMAL,daily_hours DECIMAL,active BOOLEAN,created_at TIMESTAMP).
simulated_sensor(id,appliance_id FK UNIQUE,status VARCHAR,interval_seconds INT).
sensor_reading(id,sensor_id FK,recorded_at TIMESTAMP [interval end],power_w DECIMAL,interval_seconds INT,source VARCHAR default SIMULATED,UNIQUE(sensor_id,recorded_at)). Reject overlapping intervals. source=SIMULATED only for simulator.
consumption_record(id,user_id FK,appliance_id nullable FK,period_start TIMESTAMP,period_end TIMESTAMP,energy_kwh DECIMAL,estimated_cost DECIMAL,source VARCHAR,UNIQUE(appliance_id,period_start,source)). Persist SIMULATED appliance/day aggregates; household totals calculated from appliances, never also summed with MANUAL_HOUSEHOLD records.
budget(id,user_id FK,budget_month VARCHAR YYYY-MM,amount DECIMAL,UNIQUE(user_id,budget_month)).
alert(id,user_id FK,type VARCHAR,message VARCHAR,created_at TIMESTAMP,status VARCHAR,alert_key VARCHAR UNIQUE [include userId+type+period for idempotence]).
prediction(id,user_id FK,generated_at TIMESTAMP,history_start DATE,history_end DATE,forecast_start DATE,forecast_end DATE,predicted_kwh DECIMAL,predicted_cost DECIMAL,method VARCHAR,mae_kwh DECIMAL nullable).
prepaid_transaction(id,user_id FK,kind VARCHAR OPENING/TOP_UP,amount DECIMAL,recorded_at TIMESTAMP,note VARCHAR). Balance estimate = latest opening amount + subsequent topups - simulated consumption cost SINCE opening timestamp (interval-boundary accurate), never bill/manual records. No opening = unavailable.
ai_image_upload(id,user_id FK,appliance_id nullable FK,image_type VARCHAR METER/BILL/APPLIANCE,original_name VARCHAR,file_reference VARCHAR,extracted_value DECIMAL,unit VARCHAR,processing_status VARCHAR PENDING/READY/FAILED,review_status VARCHAR PENDING/CONFIRMED/REJECTED,confirmed_value DECIMAL nullable,period_start DATE nullable,period_end DATE nullable,created_at TIMESTAMP,confirmed_at TIMESTAMP nullable).
reading_rejection(id,user_id FK,sensor_id nullable,reason VARCHAR,created_at TIMESTAMP).

Foundation endpoints:
GET/POST /api/auth/session|login|register|logout (session GET only)
GET /api/appliances -> array with id,name,category,powerRatingW,dailyHours,active,sensorId,sensorStatus
POST /api/appliances {name,category,powerRatingW,dailyHours}; PUT /api/appliances/{id} same plus active; DELETE archives appliance.
GET /api/settings -> settings object; PUT /api/settings {householdName,tariff,dayThresholdKwh,budgetThresholdPct,lowBalanceThreshold}; PUT /api/profile {name,householdName}.
GET /api/uploads -> array; POST /api/uploads multipart file,imageType,applianceId optional -> upload row. POST /api/uploads/sample {imageType,applianceId?} creates labelled SAMPLE fixture (no claim real AI). POST /api/uploads/{id}/confirm {value,applianceId?,periodStart?,periodEnd?}; POST .../reject; POST .../retry; GET .../file owner-only preview. BILL confirmation requires date range and stores MANUAL_HOUSEHOLD record separate from simulator; METER confirmation records observation only (cumulative value is not interval consumption); APPLIANCE confirmation updates linked appliance power rating. External AI not configured: deterministic sample suggestions/manual review are explicitly DEMO, do not claim image recognition. Real uploaded image has no invented numeric extraction: extractedValue=null, processingStatus=READY, user enters value manually. Only sample fixture supplies illustrative values.
GET /api/export -> CSV consumption download. POST /api/demo/reset reseeds ONLY current user's data. POST /api/demo/load seeds sample appliances/history if current account empty; safe account-scoped.

Analytics agent endpoints and response contract:
GET /api/dashboard?days=14 -> {summary:{todayKwh,periodKwh,periodCost,averageDailyKwh,changePercent,currentPowerW,lastReadingAt,coveragePercent,historyDays,tariff,days}, daily:[{date,kwh,cost,coveragePercent}], appliances:[{id,name,category,kwh,cost,powerRatingW,sharePercent}], budget:{month,amount,spent,remaining,percent,status}, forecast:{available,reason?,days,historyStart,historyEnd,forecastStart,forecastEnd,predictedKwh,predictedCost,dailyAverageKwh,method,maeKwh,points:[{date,kwh,cost}]}, prepaid:{available,balance,openingAt,topUps,estimatedConsumptionCost}, alerts:[...], simulation:{enabled,intervalSeconds:60}, recentReadings:[{id,applianceName,recordedAt,powerW,intervalSeconds}], manualRecords:[...]}.
GET /api/consumption?days=14 -> same dashboard or {daily,appliances,summary}.
POST /api/simulation/run {minutes:15} -> {message,inserted,...}; bounded to elapsed time through now, no future readings; return up-to-date result when no elapsed interval. POST /api/simulation/toggle {enabled:boolean} -> {enabled}.
GET /api/budget -> budget object; PUT /api/budget {amount,month?} -> budget object.
GET /api/alerts -> array; PATCH /api/alerts/{id} {status:READ|DISMISSED|UNREAD}.
GET /api/predictions?days=7 -> forecast object; POST /api/predictions {days:7|14|30} -> forecast object and persist a prediction.
GET /api/prepaid -> prepaid object; POST /api/prepaid {kind:OPENING|TOP_UP,amount,note?} -> prepaid object.
POST /api/readings {sensorId,recordedAt,powerW,intervalSeconds} -> accepted reading; invalid/duplicate/overlap returns400/409 and logs rejection.

AnalyticsService constructor(Db db), public Map<String,Object> dashboard(long userId,int days); public Map<String,Object> simulate(long userId,int minutes); public void seedHistory(long userId,int days). The demo initializer creates appliances, budget and opening balance before calling seedHistory. Use deterministic plausible daily/hourly patterns. At least14complete days + currentday partial. Forecast only >=7complete days sufficient coverage, use trailing7-day mean with3-day holdoutMAE when >=10days. Missing data coverage explicit, not silentlyzero. Dashboard/forecast cost estimates show tariff; estimated prepay separate from authoritative utility balance. Dates use LocalDate.now(ZoneId.of("Africa/Johannesburg")), all times local.

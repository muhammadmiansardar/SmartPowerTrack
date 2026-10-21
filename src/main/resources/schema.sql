CREATE TABLE IF NOT EXISTS app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(512) NOT NULL, household_name VARCHAR(120) NOT NULL DEFAULT 'My household',
 tariff DECIMAL(12,4) NOT NULL DEFAULT 3.20, day_threshold_kwh DECIMAL(12,4) NOT NULL DEFAULT 25,
 budget_threshold_pct DECIMAL(8,2) NOT NULL DEFAULT 80, low_balance_threshold DECIMAL(12,2) NOT NULL DEFAULT 100,
 simulation_enabled BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL,
 CHECK (tariff > 0), CHECK (day_threshold_kwh > 0), CHECK (budget_threshold_pct > 0 AND budget_threshold_pct <= 100)
);
CREATE TABLE IF NOT EXISTS appliance (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 category VARCHAR(60) NOT NULL, power_rating_w DECIMAL(12,3) NOT NULL, daily_hours DECIMAL(8,3) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY (user_id) REFERENCES app_user(id), CHECK (power_rating_w >= 0), CHECK (daily_hours >= 0 AND daily_hours <= 24)
);
CREATE TABLE IF NOT EXISTS simulated_sensor (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, appliance_id BIGINT NOT NULL UNIQUE, status VARCHAR(20) NOT NULL,
 interval_seconds INT NOT NULL DEFAULT 60, FOREIGN KEY (appliance_id) REFERENCES appliance(id), CHECK (interval_seconds > 0)
);
CREATE TABLE IF NOT EXISTS sensor_reading (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, sensor_id BIGINT NOT NULL, recorded_at TIMESTAMP NOT NULL,
 power_w DECIMAL(14,5) NOT NULL, interval_seconds INT NOT NULL, source VARCHAR(30) NOT NULL DEFAULT 'SIMULATED',
 FOREIGN KEY (sensor_id) REFERENCES simulated_sensor(id), UNIQUE (sensor_id,recorded_at), CHECK (power_w >= 0), CHECK (interval_seconds > 0)
);
CREATE TABLE IF NOT EXISTS consumption_record (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, appliance_id BIGINT,
 period_start TIMESTAMP NOT NULL, period_end TIMESTAMP NOT NULL, energy_kwh DECIMAL(16,7) NOT NULL,
 estimated_cost DECIMAL(16,5) NOT NULL, source VARCHAR(40) NOT NULL,
 FOREIGN KEY (user_id) REFERENCES app_user(id), FOREIGN KEY (appliance_id) REFERENCES appliance(id),
 UNIQUE (appliance_id,period_start,source), CHECK (energy_kwh >= 0), CHECK (estimated_cost >= 0), CHECK (period_end > period_start)
);
CREATE TABLE IF NOT EXISTS budget (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, budget_month VARCHAR(7) NOT NULL,
 amount DECIMAL(14,2) NOT NULL, FOREIGN KEY (user_id) REFERENCES app_user(id), UNIQUE (user_id,budget_month), CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS alert (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(40) NOT NULL,
 message VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
 alert_key VARCHAR(200) NOT NULL UNIQUE, FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS prediction (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, generated_at TIMESTAMP NOT NULL,
 history_start DATE NOT NULL, history_end DATE NOT NULL, forecast_start DATE NOT NULL, forecast_end DATE NOT NULL,
 predicted_kwh DECIMAL(16,5) NOT NULL, predicted_cost DECIMAL(16,5) NOT NULL, method VARCHAR(200) NOT NULL,
 mae_kwh DECIMAL(16,5), FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE IF NOT EXISTS prepaid_transaction (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 amount DECIMAL(14,2) NOT NULL, recorded_at TIMESTAMP NOT NULL, note VARCHAR(300), FOREIGN KEY (user_id) REFERENCES app_user(id), CHECK (amount >= 0)
);
CREATE TABLE IF NOT EXISTS ai_image_upload (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, appliance_id BIGINT, image_type VARCHAR(20) NOT NULL,
 original_name VARCHAR(255) NOT NULL, file_reference VARCHAR(255) NOT NULL, extracted_value DECIMAL(16,5), unit VARCHAR(30),
 processing_status VARCHAR(20) NOT NULL, review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', confirmed_value DECIMAL(16,5),
 period_start DATE, period_end DATE, created_at TIMESTAMP NOT NULL, confirmed_at TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES app_user(id), FOREIGN KEY (appliance_id) REFERENCES appliance(id)
);
CREATE TABLE IF NOT EXISTS reading_rejection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, sensor_id BIGINT, reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL, FOREIGN KEY (user_id) REFERENCES app_user(id)
);

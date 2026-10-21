package za.ac.smartpowertrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises actual HTTP handlers, session/CSRF checks, and an isolated H2 database. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:powertrack-test;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.sql.init.mode=always", "powertrack.demo.seed=false",
    "powertrack.upload-dir=./target/test-uploads"
})
@AutoConfigureMockMvc
class PrototypeIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    private static final String PASSWORD = "Integration123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private record Client(MockHttpSession session, String csrf, long userId, String email) {}

    @Test void registrationLoginLogoutRequireCsrfAndProtectPasswords() throws Exception {
        mvc.perform(get("/api/appliances")).andExpect(status().isUnauthorized());
        MvcResult initial = mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        String token = body(initial).path("csrfToken").asText();
        String email = uniqueEmail();
        String registration = mapper.writeValueAsString(Map.of("name", "Test household", "email", email,
                "password", PASSWORD, "householdName", "Integration home"));
        mvc.perform(post("/api/auth/register").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(registration)).andExpect(status().isForbidden());
        MvcResult registered = mvc.perform(post("/api/auth/register").session(session)
                .header("X-CSRF-Token", token).contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().is2xxSuccessful()).andReturn();
        assertThat(body(registered).path("authenticated").asBoolean()).isTrue();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        Client client = client(registered, email);
        mvc.perform(post("/api/auth/logout").session(client.session)).andExpect(status().isForbidden());
        mutate(post("/api/auth/logout"), client, Map.of());
        MvcResult anonymous = mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andReturn();
        Client anon = client(anonymous, email);
        mvc.perform(post("/api/auth/login").session(anon.session).header("X-CSRF-Token", anon.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "email", email, "password", "Incorrect123!")))).andExpect(status().isUnauthorized());
        JsonNode loggedIn = mutate(post("/api/auth/login"), anon, Map.of("email", email, "password", PASSWORD));
        assertThat(loggedIn.path("authenticated").asBoolean()).isTrue();
        assertThat(loggedIn.path("user").path("email").asText()).isEqualTo(email);
    }

    @Test void applianceAndUploadOwnershipIsEnforcedOnReadsAndChanges() throws Exception {
        Client owner = register(), other = register();
        long applianceId = appliance(owner, 500);
        JsonNode upload = mutate(post("/api/uploads/sample"), owner, Map.of("imageType", "APPLIANCE", "applianceId", applianceId));
        long uploadId = upload.path("id").asLong();
        assertThat(read("/api/appliances", other).toString()).doesNotContain("\"id\":" + applianceId + ",");
        assertThat(read("/api/uploads", other)).isEmpty();
        denied(put("/api/appliances/" + applianceId), other, Map.of("name", "Stolen", "category", "Other",
                "powerRatingW", 999, "dailyHours", 1, "active", true));
        denied(post("/api/uploads/" + uploadId + "/confirm"), other, Map.of("value", 999));
        mvc.perform(get("/api/uploads/" + uploadId + "/file").session(other.session))
                .andExpect(status().is4xxClientError());
        assertThat(read("/api/appliances", owner).get(0).path("powerRatingW").asDouble()).isEqualTo(500);
        assertThat(read("/api/uploads", owner).get(0).path("reviewStatus").asText()).isEqualTo("PENDING");
    }

    @Test void intervalEnergyAndCostMatchAnIndependentCalculationAndDuplicateOverlapAreRejected() throws Exception {
        Client user = register();
        long sensorId = sensor(user, appliance(user, 1000));
        LocalDateTime end = LocalDate.now(ZONE).minusDays(1).atTime(12, 0);
        // Average 1,000 W for 1,800 seconds = 0.5 kWh; tariff R3.20/kWh => R1.60.
        Map<String, Object> reading = reading(sensorId, end, 1000, 1800);
        mutate(post("/api/readings"), user, reading);
        JsonNode dashboard = read("/api/dashboard?days=2", user);
        assertThat(dashboard.path("summary").path("periodKwh").asDouble()).isCloseTo(0.5, within(0.000001));
        assertThat(dashboard.path("summary").path("periodCost").asDouble()).isCloseTo(1.60, within(0.000001));
        denied(post("/api/readings"), user, reading);
        denied(post("/api/readings"), user, reading(sensorId, end.plusMinutes(5), 1000, 1800));
        denied(post("/api/readings"), user, reading(sensorId, end.plusHours(1), -1, 60));
        assertThat(read("/api/dashboard?days=2", user).path("summary").path("periodKwh").asDouble())
                .isCloseTo(0.5, within(0.000001));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_rejection WHERE user_id = ?", Long.class, user.userId))
                .isGreaterThanOrEqualTo(3);
        assertThat(dashboard.path("summary").path("coveragePercent").asDouble()).isLessThan(100);
    }

    @Test void budgetThresholdCreatesAnAlertOnlyOnceAndAlertStatusCanChange() throws Exception {
        Client user = register();
        long sensorId = sensor(user, appliance(user, 1000));
        mutate(put("/api/budget"), user, Map.of("amount", 1, "month", LocalDate.now(ZONE).toString().substring(0, 7)));
        mutate(post("/api/readings"), user, reading(sensorId, LocalDateTime.now(ZONE).minusSeconds(5).withNano(0), 1000, 60));
        // Add enough energy to cross the R1 budget without relying on the clock being after 01:00.
        LocalDateTime end = LocalDateTime.now(ZONE).minusMinutes(2).withNano(0);
        mutate(post("/api/readings"), user, reading(sensorId, end, 1000, 1800));
        JsonNode dashboard = read("/api/dashboard?days=2", user);
        assertThat(dashboard.path("budget").path("percent").asDouble()).isGreaterThan(100);
        JsonNode alerts = read("/api/alerts", user);
        JsonNode budgetAlert = null;
        for (JsonNode alert : alerts) if (alert.path("type").asText().contains("BUDGET")) budgetAlert = alert;
        assertThat(budgetAlert).as("A budget threshold alert must be generated").isNotNull();
        long count = alerts.size();
        read("/api/dashboard?days=2", user);
        assertThat(read("/api/alerts", user).size()).isEqualTo(count);
        JsonNode updated = mutate(patch("/api/alerts/" + budgetAlert.path("id").asLong()), user, Map.of("status", "READ"));
        assertThat(updated.path("status").asText()).isEqualTo("READ");
    }

    @Test void forecastNeedsSevenCompleteDaysAndPredictsKnownConstantHistory() throws Exception {
        Client user = register();
        long sensorId = sensor(user, appliance(user, 1000));
        assertThat(read("/api/predictions?days=7", user).path("available").asBoolean()).isFalse();
        LocalDate today = LocalDate.now(ZONE);
        for (int day = 7; day >= 2; day--) {
            mutate(post("/api/readings"), user, reading(sensorId, today.minusDays(day - 1).atStartOfDay(), 1000, 86400));
        }
        assertThat(read("/api/predictions?days=7", user).path("available").asBoolean()).isFalse();
        mutate(post("/api/readings"), user, reading(sensorId, today.atStartOfDay(), 1000, 86400));
        JsonNode forecast = mutate(post("/api/predictions"), user, Map.of("days", 7));
        assertThat(forecast.path("available").asBoolean()).isTrue();
        assertThat(forecast.path("predictedKwh").asDouble()).isCloseTo(168, within(0.000001));
        assertThat(forecast.path("predictedCost").asDouble()).isCloseTo(537.60, within(0.000001));
        assertThat(forecast.path("forecastStart").asText()).isNotBlank();
        assertThat(forecast.path("historyStart").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction WHERE user_id = ?", Long.class, user.userId)).isEqualTo(1);
    }

    @Test void prepaidEstimateRequiresOpeningAndDeductsOnlyPostOpeningSimulatedEnergy() throws Exception {
        Client user = register();
        long sensorId = sensor(user, appliance(user, 1000));
        assertThat(read("/api/prepaid", user).path("available").asBoolean()).isFalse();
        LocalDateTime end = LocalDate.now(ZONE).minusDays(1).atTime(12, 0);
        mutate(post("/api/readings"), user, reading(sensorId, end, 1000, 3600));
        mutate(post("/api/prepaid"), user, Map.of("kind", "OPENING", "amount", 250, "note", "Test opening"));
        // Controlled fixture: opening at the middle of the 11:00–12:00 interval.
        // Only 0.5 kWh after 11:30 may be charged against this opening amount.
        jdbc.update("UPDATE prepaid_transaction SET recorded_at = ? WHERE user_id = ? AND kind = 'OPENING'",
                java.sql.Timestamp.valueOf(end.minusMinutes(30)), user.userId);
        JsonNode balance = mutate(post("/api/prepaid"), user, Map.of("kind", "TOP_UP", "amount", 50, "note", "Test top-up"));
        assertThat(balance.path("available").asBoolean()).isTrue();
        assertThat(balance.path("estimatedConsumptionCost").asDouble()).isCloseTo(1.6, within(0.000001));
        assertThat(balance.path("balance").asDouble()).isCloseTo(298.4, within(0.000001));
        Client other = register();
        assertThat(read("/api/prepaid", other).path("available").asBoolean()).isFalse();
    }

    @Test void pendingImageValuesNeedConfirmationAndRejectedValuesDoNotChangeAppliances() throws Exception {
        Client user = register();
        long applianceId = appliance(user, 500);
        JsonNode sample = mutate(post("/api/uploads/sample"), user, Map.of("imageType", "APPLIANCE", "applianceId", applianceId));
        assertThat(sample.path("reviewStatus").asText()).isEqualTo("PENDING");
        assertThat(read("/api/appliances", user).get(0).path("powerRatingW").asDouble()).isEqualTo(500);
        JsonNode confirmed = mutate(post("/api/uploads/" + sample.path("id").asLong() + "/confirm"), user, Map.of("value", 750));
        assertThat(confirmed.path("reviewStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(read("/api/appliances", user).get(0).path("powerRatingW").asDouble()).isEqualTo(750);
        JsonNode rejected = mutate(post("/api/uploads/sample"), user, Map.of("imageType", "APPLIANCE", "applianceId", applianceId));
        mutate(post("/api/uploads/" + rejected.path("id").asLong() + "/reject"), user, Map.of());
        denied(post("/api/uploads/" + rejected.path("id").asLong() + "/confirm"), user, Map.of("value", 900));
        assertThat(read("/api/appliances", user).get(0).path("powerRatingW").asDouble()).isEqualTo(750);
    }

    @Test void realUploadHasNoInventedExtractionAndCumulativeMeterDoesNotBecomeConsumption() throws Exception {
        Client user = register();
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        byte[] png = image.toByteArray();
        MockMultipartFile file = new MockMultipartFile("file", "meter.png", "image/png", png);
        MvcResult result = mvc.perform(multipart("/api/uploads").file(file).param("imageType", "METER")
                .session(user.session).header("X-CSRF-Token", user.csrf)).andExpect(status().is2xxSuccessful()).andReturn();
        JsonNode upload = body(result);
        assertThat(upload.path("extractedValue").isNull()).isTrue();
        assertThat(upload.path("reviewStatus").asText()).isEqualTo("PENDING");
        long recordsBefore = countConsumption(user);
        mutate(post("/api/uploads/" + upload.path("id").asLong() + "/confirm"), user, Map.of("value", 12500.5));
        assertThat(countConsumption(user)).as("A cumulative meter observation is not interval consumption").isEqualTo(recordsBefore);
        MockMultipartFile unsupported = new MockMultipartFile("file", "bad.txt", "text/plain", "not an image".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/uploads").file(unsupported).param("imageType", "METER")
                .session(user.session).header("X-CSRF-Token", user.csrf)).andExpect(status().is4xxClientError());
    }

    @Test void billNeedsValidDatesAndManualRecordsDoNotInflateSimulatedTotals() throws Exception {
        Client user = register();
        long sensorId = sensor(user, appliance(user, 1000));
        LocalDate today = LocalDate.now(ZONE);
        mutate(post("/api/readings"), user, reading(sensorId, today.minusDays(1).atTime(12, 0), 1000, 1800));
        JsonNode sample = mutate(post("/api/uploads/sample"), user, Map.of("imageType", "BILL"));
        String confirm = "/api/uploads/" + sample.path("id").asLong() + "/confirm";
        denied(post(confirm), user, Map.of("value", 100));
        denied(post(confirm), user, Map.of("value", 100, "periodStart", today.toString(), "periodEnd", today.minusDays(1).toString()));
        mutate(post(confirm), user, Map.of("value", 100, "periodStart", today.minusDays(5).toString(), "periodEnd", today.minusDays(1).toString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumption_record WHERE user_id = ? AND source = 'MANUAL_HOUSEHOLD'", Long.class, user.userId)).isEqualTo(1);
        assertThat(read("/api/dashboard?days=7", user).path("summary").path("periodKwh").asDouble()).isCloseTo(0.5, within(0.000001));
    }

    @Test void settingsAreValidatedAndScopedToTheCurrentHousehold() throws Exception {
        Client owner = register(), other = register();
        mutate(put("/api/settings"), owner, Map.of("householdName", "Test flat", "tariff", 2.75,
                "dayThresholdKwh", 12, "budgetThresholdPct", 75, "lowBalanceThreshold", 80));
        JsonNode settings = read("/api/settings", owner);
        assertThat(settings.path("tariff").asDouble()).isEqualTo(2.75);
        assertThat(settings.path("householdName").asText()).isEqualTo("Test flat");
        assertThat(read("/api/settings", other).path("tariff").asDouble()).isEqualTo(3.2);
        denied(put("/api/settings"), owner, Map.of("householdName", "Test flat", "tariff", -1,
                "dayThresholdKwh", 12, "budgetThresholdPct", 75, "lowBalanceThreshold", 80));
        assertThat(read("/api/settings", owner).path("tariff").asDouble()).isEqualTo(2.75);
    }

    private long countConsumption(Client user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM consumption_record WHERE user_id = ?", Long.class, user.userId);
    }
    private Map<String, Object> reading(long sensorId, LocalDateTime end, double power, int interval) {
        return Map.of("sensorId", sensorId, "recordedAt", end.toString(), "powerW", power, "intervalSeconds", interval);
    }
    private long appliance(Client user, double power) throws Exception {
        return mutate(post("/api/appliances"), user, Map.of("name", "Test appliance", "category", "Other", "powerRatingW", power, "dailyHours", 2)).path("id").asLong();
    }
    private long sensor(Client user, long applianceId) throws Exception {
        for (JsonNode row : read("/api/appliances", user)) if (row.path("id").asLong() == applianceId) return row.path("sensorId").asLong();
        throw new AssertionError("Created appliance must have a simulated sensor");
    }
    private String uniqueEmail() { return "integration-" + UUID.randomUUID() + "@example.test"; }
    private Client register() throws Exception {
        MvcResult initial = mvc.perform(get("/api/auth/session")).andExpect(status().isOk()).andReturn();
        Client anon = client(initial, uniqueEmail());
        MvcResult result = mvc.perform(post("/api/auth/register").session(anon.session).header("X-CSRF-Token", anon.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", "Integration user",
                        "email", anon.email, "password", PASSWORD, "householdName", "Integration home"))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return client(result, anon.email);
    }
    private Client client(MvcResult result, String email) throws Exception {
        JsonNode json = body(result);
        return new Client((MockHttpSession) result.getRequest().getSession(false), json.path("csrfToken").asText(), json.path("user").path("id").asLong(), email);
    }
    private JsonNode body(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    private JsonNode read(String path, Client user) throws Exception {
        return body(mvc.perform(get(path).session(user.session)).andExpect(status().is2xxSuccessful()).andReturn());
    }
    private JsonNode mutate(MockHttpServletRequestBuilder request, Client user, Object data) throws Exception {
        return body(mvc.perform(request.session(user.session).header("X-CSRF-Token", user.csrf).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(data))).andExpect(status().is2xxSuccessful()).andReturn());
    }
    private void denied(MockHttpServletRequestBuilder request, Client user, Object data) throws Exception {
        mvc.perform(request.session(user.session).header("X-CSRF-Token", user.csrf).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(data))).andExpect(status().is4xxClientError());
    }
}

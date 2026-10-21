package za.ac.smartpowertrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {
    private Db db;
    private AnalyticsService analytics;
    private long userId,sensorId;
    private final ZoneId zone=ZoneId.of("Africa/Johannesburg");
    private LocalDateTime now() { return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS); }
    private Map<String,Object> values(Object... pairs) {
        var result=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
    }
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:analytics"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        db=new Db(new JdbcTemplate(source)); analytics=new AnalyticsService(db);
        userId=db.insert("app_user",values("name","Test","email","test@example.org","password_hash","test","created_at",Timestamp.valueOf(now())));
        long appliance=db.insert("appliance",values("user_id",userId,"name","Test heater","category","Heating","power_rating_w",1000,"daily_hours",2,"created_at",Timestamp.valueOf(now())));
        sensorId=db.insert("simulated_sensor",values("appliance_id",appliance,"status","ACTIVE","interval_seconds",60));
    }
    private void reading(LocalDateTime end,double watts,int seconds) {
        db.insert("sensor_reading",values("sensor_id",sensorId,"recorded_at",Timestamp.valueOf(end),"power_w",watts,"interval_seconds",seconds,"source","SIMULATED"));
    }
    @SuppressWarnings("unchecked") @Test void splitsIntervalEnergyAcrossMidnightWithoutDoubleCounting() {
        LocalDateTime end=LocalDate.now(zone).minusDays(1).atTime(0,15);
        reading(end,1000,3600);
        var dashboard=analytics.dashboard(userId,3);
        var daily=(List<Map<String,Object>>)dashboard.get("daily");
        assertEquals(.75,((Number)daily.get(0).get("kwh")).doubleValue(),.00001);
        assertEquals(.25,((Number)daily.get(1).get("kwh")).doubleValue(),.00001);
        assertEquals(1,((Number)((Map<String,Object>)dashboard.get("summary")).get("periodKwh")).doubleValue(),.00001);
    }
    @Test void prepaidOnlySubtractsPartOfIntervalAfterOpening() {
        LocalDateTime end=now().minusMinutes(1);
        reading(end,1000,7200);
        db.insert("prepaid_transaction",values("user_id",userId,"kind","OPENING","amount",100,"recorded_at",Timestamp.valueOf(end.minusMinutes(30)),"note","Test boundary"));
        var prepaid=analytics.prepaid(userId);
        assertEquals(1.60,((Number)prepaid.get("estimatedConsumptionCost")).doubleValue(),.001);
        assertEquals(98.40,((Number)prepaid.get("balance")).doubleValue(),.001);
    }
    @Test void duplicateAndOverlappingIntervalsAreRejectedAndLogged() {
        LocalDateTime end=now().minusMinutes(10);
        var input=values("sensorId",sensorId,"recordedAt",end.toString(),"powerW",1000,"intervalSeconds",1800);
        analytics.addReading(userId,input);
        assertEquals(409,assertThrows(ApiException.class,()->analytics.addReading(userId,input)).status);
        input.put("recordedAt",end.plusMinutes(10).toString());
        assertEquals(409,assertThrows(ApiException.class,()->analytics.addReading(userId,input)).status);
        assertEquals(2,db.list("SELECT * FROM reading_rejection WHERE user_id=?",userId).size());
        assertEquals(1,db.list("SELECT * FROM sensor_reading WHERE sensor_id=?",sensorId).size());
    }
    @SuppressWarnings("unchecked") @Test void missingHistoryIsUnavailableAndCompleteSimulationSupportsMeasuredForecast() {
        assertFalse((Boolean)analytics.forecast(userId,7,false).get("available"));
        assertNull(((Map<String,Object>)analytics.dashboard(userId,14).get("summary")).get("periodKwh"));
        analytics.seedHistory(userId,14);
        var forecast=analytics.forecast(userId,7,true);
        assertTrue((Boolean)forecast.get("available"));
        assertNotNull(forecast.get("maeKwh"));
        assertEquals(7,((List<?>)forecast.get("points")).size());
        assertEquals(1,db.list("SELECT * FROM prediction WHERE user_id=?",userId).size());
        assertTrue(((Number)forecast.get("predictedKwh")).doubleValue()>0);
        db.execute("DELETE FROM sensor_reading WHERE sensor_id=? AND recorded_at>=?",sensorId,Timestamp.valueOf(LocalDate.now(zone).minusDays(1).atStartOfDay()));
        assertFalse((Boolean)analytics.forecast(userId,7,false).get("available"));
    }
    @Test void anotherAccountCannotSubmitToAnOwnedSensor() {
        long other=db.insert("app_user",values("name","Other","email","other@example.org","password_hash","test","created_at",Timestamp.valueOf(now())));
        assertEquals(404,assertThrows(ApiException.class,()->analytics.addReading(other,values("sensorId",sensorId,"recordedAt",now().minusMinutes(1).toString(),"powerW",1000,"intervalSeconds",60))).status);
        assertNull(db.one("SELECT sensor_id FROM reading_rejection WHERE user_id=?",other).get("sensorId"));
    }
}

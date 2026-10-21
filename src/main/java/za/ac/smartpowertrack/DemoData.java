package za.ac.smartpowertrack;

import jakarta.servlet.http.HttpSession;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Component
@Order(10)
public class DemoData implements ApplicationRunner {
    private final Db db;private final Auth auth;private final AnalyticsService analytics;private final TransactionTemplate tx;
    @Value("${powertrack.demo.seed:true}") private boolean seedEnabled;
    public DemoData(Db db,Auth auth,AnalyticsService analytics,TransactionTemplate tx) {this.db=db;this.auth=auth;this.analytics=analytics;this.tx=tx;}
    @Override public void run(ApplicationArguments args) {
        if(!seedEnabled||!db.list("SELECT id FROM app_user WHERE email=?","demo@powertrack.local").isEmpty()) return;
        tx.executeWithoutResult(status->{
            long id=db.insert("app_user",Map.of("name","Mian","email","demo@powertrack.local","password_hash",auth.hash("PowerTrack123!"),"household_name","Demo household","created_at",PowerTrackApplication.now()));
            seed(id);
        });
    }
    public void load(long userId) {
        if(!db.list("SELECT id FROM appliance WHERE user_id=?",userId).isEmpty()) throw new ApiException(409,"Your account already has appliances. Use reset demo only if you want to replace your account's data.");
        tx.executeWithoutResult(status->seed(userId));
    }
    public void reset(long userId) {
        tx.executeWithoutResult(status->{
            db.execute("DELETE FROM sensor_reading WHERE sensor_id IN (SELECT s.id FROM simulated_sensor s JOIN appliance a ON a.id=s.appliance_id WHERE a.user_id=?)",userId);
            for(String table:List.of("ai_image_upload","consumption_record","budget","alert","prediction","prepaid_transaction","reading_rejection")) db.execute("DELETE FROM "+table+" WHERE user_id=?",userId);
            db.execute("DELETE FROM simulated_sensor WHERE appliance_id IN (SELECT id FROM appliance WHERE user_id=?)",userId);
            db.execute("DELETE FROM appliance WHERE user_id=?",userId);
            db.execute("UPDATE app_user SET simulation_enabled=FALSE WHERE id=?",userId);
            seed(userId);
        });
    }
    private void seed(long uid) {
        Object[][] sample={{"Geyser","Heating",3000,3.2},{"Fridge","Kitchen",180,8.0},{"Kettle","Kitchen",2000,.35},{"Washing machine","Laundry",500,.85},{"Television","Entertainment",120,4.5},{"Lighting","Lighting",80,6.0},{"Laptop","Office",65,7.0},{"Oven","Kitchen",2200,.8}};
        for(Object[] v:sample) {
            long a=db.insert("appliance",Map.of("user_id",uid,"name",v[0],"category",v[1],"power_rating_w",v[2],"daily_hours",v[3],"active",true,"created_at",PowerTrackApplication.now()));
            db.insert("simulated_sensor",Map.of("appliance_id",a,"status","ACTIVE","interval_seconds",60));
        }
        db.insert("budget",Map.of("user_id",uid,"budget_month",YearMonth.from(PowerTrackApplication.today()).toString(),"amount",1700));
        db.insert("prepaid_transaction",Map.of("user_id",uid,"kind","OPENING","amount",1500,"recorded_at",PowerTrackApplication.today().minusDays(21).atStartOfDay(),"note","Simulated opening balance for demonstration"));
        db.insert("prepaid_transaction",Map.of("user_id",uid,"kind","TOP_UP","amount",300,"recorded_at",PowerTrackApplication.today().minusDays(7).atTime(12,0),"note","Sample top-up"));
        analytics.seedHistory(uid,21);
    }
}

@RestController
class DemoController {
    private final DemoData demo;private final Db db;
    public DemoController(DemoData demo,Db db) {this.demo=demo;this.db=db;}
    @PostMapping("/api/demo/load") Map<String,Object> load(HttpSession session) {demo.load(Auth.require(session));return Map.of("message","Sample appliances and 21 days of simulated history are ready.");}
    @PostMapping("/api/demo/reset") Map<String,Object> reset(HttpSession session) {demo.reset(Auth.require(session));return Map.of("message","Your demo data has been reset. Other accounts were not changed.");}
    @GetMapping("/api/export") org.springframework.http.ResponseEntity<String> export(HttpSession session) {
        long uid=Auth.require(session);StringBuilder b=new StringBuilder("period_start,period_end,appliance,energy_kwh,estimated_cost_zar,source\r\n");
        for(var r:db.list("SELECT c.period_start,c.period_end,a.name,c.energy_kwh,c.energy_kwh*u.tariff AS estimated_cost,c.source FROM consumption_record c JOIN app_user u ON u.id=c.user_id LEFT JOIN appliance a ON a.id=c.appliance_id WHERE c.user_id=? ORDER BY c.period_start DESC",uid)) {
            for(String k:List.of("periodStart","periodEnd","name","energyKwh","estimatedCost","source")) {
                String value=String.valueOf(r.getOrDefault(k,""));if(value.equals("null")) value="Household (manual)";
                if(value.matches("^[=+@-].*")) value="'"+value;
                b.append('"').append(value.replace("\"","\"\"")).append("\",");
            }
            b.setLength(b.length()-1);b.append("\r\n");
        }
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=powertrack-consumption.csv").contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8")).body(b.toString());
    }
}

package za.ac.smartpowertrack;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApplianceController {
    private final Db db;private final Auth auth;
    public ApplianceController(Db db,Auth auth) {this.db=db;this.auth=auth;}
    @GetMapping("/api/appliances") public List<Map<String,Object>> list(HttpSession session) {
        return db.list("SELECT a.*,s.id AS sensor_id,s.status AS sensor_status FROM appliance a LEFT JOIN simulated_sensor s ON s.appliance_id=a.id WHERE a.user_id=? ORDER BY a.active DESC,a.id",Auth.require(session));
    }
    @PostMapping("/api/appliances") @Transactional public Map<String,Object> create(@RequestBody Map<String,Object> b,HttpSession session) {
        long uid=Auth.require(session);Map<String,Object> a=fields(b);a.put("user_id",uid);a.put("active",true);a.put("created_at",PowerTrackApplication.now());
        long id=db.insert("appliance",a);db.insert("simulated_sensor",Map.of("appliance_id",id,"status","ACTIVE","interval_seconds",60));
        return db.one("SELECT * FROM appliance WHERE id=? AND user_id=?",id,uid);
    }
    @PutMapping("/api/appliances/{id}") @Transactional public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> b,HttpSession session) {
        long uid=Auth.require(session);var existing=db.one("SELECT * FROM appliance WHERE id=? AND user_id=?",id,uid);var a=fields(b);
        if(b.containsKey("active")&&!(b.get("active") instanceof Boolean)) throw new ApiException(400,"active must be true or false.");
        boolean active=b.containsKey("active")?(Boolean)b.get("active"):Boolean.TRUE.equals(existing.get("active"));
        db.execute("UPDATE appliance SET name=?,category=?,power_rating_w=?,daily_hours=?,active=? WHERE id=? AND user_id=?",a.get("name"),a.get("category"),a.get("power_rating_w"),a.get("daily_hours"),active,id,uid);
        db.execute("UPDATE simulated_sensor SET status=? WHERE appliance_id=?",active?"ACTIVE":"PAUSED",id);
        return db.one("SELECT * FROM appliance WHERE id=? AND user_id=?",id,uid);
    }
    @DeleteMapping("/api/appliances/{id}") @Transactional public Map<String,Object> archive(@PathVariable long id,HttpSession session) {
        long uid=Auth.require(session);db.one("SELECT id FROM appliance WHERE id=? AND user_id=?",id,uid);
        db.execute("UPDATE appliance SET active=FALSE WHERE id=? AND user_id=?",id,uid);db.execute("UPDATE simulated_sensor SET status='PAUSED' WHERE appliance_id=?",id);
        return Map.of("message","Appliance archived. Historical readings have been retained.");
    }
    private Map<String,Object> fields(Map<String,Object> b) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("name",Values.text(b,"name",100));m.put("category",Values.text(b,"category",60));
        m.put("power_rating_w",Values.number(b,"powerRatingW",0,50000));m.put("daily_hours",Values.number(b,"dailyHours",0,24));return m;
    }
    @GetMapping("/api/settings") public Map<String,Object> settings(HttpSession s) {return auth.user(Auth.require(s));}
    @PutMapping("/api/settings") public Map<String,Object> settings(@RequestBody Map<String,Object> b,HttpSession s) {
        long uid=Auth.require(s);var old=auth.user(uid);
        String household=b.containsKey("householdName")?Values.text(b,"householdName",120):old.get("householdName").toString();
        double tariff=field(b,old,"tariff",.01,100),day=field(b,old,"dayThresholdKwh",.1,1000),pct=field(b,old,"budgetThresholdPct",1,100),low=field(b,old,"lowBalanceThreshold",0,100000);
        db.execute("UPDATE app_user SET household_name=?,tariff=?,day_threshold_kwh=?,budget_threshold_pct=?,low_balance_threshold=? WHERE id=?",household,tariff,day,pct,low,uid);
        return auth.user(uid);
    }
    @PutMapping("/api/profile") public Map<String,Object> profile(@RequestBody Map<String,Object> b,HttpSession s) {
        long uid=Auth.require(s);var old=auth.user(uid);
        String name=b.containsKey("name")?Values.text(b,"name",100):old.get("name").toString();
        String household=b.containsKey("householdName")?Values.text(b,"householdName",120):old.get("householdName").toString();
        db.execute("UPDATE app_user SET name=?,household_name=? WHERE id=?",name,household,uid);return auth.user(uid);
    }
    private double field(Map<String,Object> b,Map<String,Object> old,String key,double min,double max) {return Values.number(b.containsKey(key)?b:old,key,min,max);}
}

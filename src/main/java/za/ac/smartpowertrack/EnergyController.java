package za.ac.smartpowertrack;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class EnergyController {
    private final AnalyticsService analytics;
    private final Auth auth;
    public EnergyController(AnalyticsService analytics,Auth auth) { this.analytics=analytics; this.auth=auth; }
    @GetMapping("/dashboard") public Map<String,Object> dashboard(HttpSession session,@RequestParam(defaultValue="14") int days) { return analytics.dashboard(auth.require(session),days); }
    @GetMapping("/consumption") public Map<String,Object> consumption(HttpSession session,@RequestParam(defaultValue="14") int days) { return analytics.dashboard(auth.require(session),days); }
    @PostMapping("/simulation/run") public Map<String,Object> simulate(HttpSession session,@RequestBody(required=false) Map<String,Object> body) { return analytics.simulate(auth.require(session),integer(body,"minutes",15)); }
    @PostMapping("/simulation/toggle") public Map<String,Object> toggle(HttpSession session,@RequestBody Map<String,Object> body) {
        if(!(body.get("enabled") instanceof Boolean enabled)) throw new ApiException(400,"enabled must be true or false.");
        return analytics.toggle(auth.require(session),enabled);
    }
    @GetMapping("/budget") public Map<String,Object> budget(HttpSession session) { return analytics.budget(auth.require(session)); }
    @PutMapping("/budget") public Map<String,Object> saveBudget(HttpSession session,@RequestBody Map<String,Object> body) { return analytics.saveBudget(auth.require(session),body); }
    @GetMapping("/alerts") public Object alerts(HttpSession session) { return analytics.alerts(auth.require(session)); }
    @PatchMapping("/alerts/{id}") public Map<String,Object> updateAlert(HttpSession session,@PathVariable long id,@RequestBody Map<String,Object> body) { return analytics.updateAlert(auth.require(session),id,body); }
    @GetMapping("/predictions") public Map<String,Object> forecast(HttpSession session,@RequestParam(defaultValue="7") int days) { return analytics.forecast(auth.require(session),days,false); }
    @PostMapping("/predictions") public Map<String,Object> predict(HttpSession session,@RequestBody(required=false) Map<String,Object> body) { return analytics.forecast(auth.require(session),integer(body,"days",7),true); }
    @GetMapping("/prepaid") public Map<String,Object> prepaid(HttpSession session) { return analytics.prepaid(auth.require(session)); }
    @PostMapping("/prepaid") public Map<String,Object> addPrepaid(HttpSession session,@RequestBody Map<String,Object> body) { return analytics.addPrepaid(auth.require(session),body); }
    @PostMapping("/readings") public Map<String,Object> reading(HttpSession session,@RequestBody Map<String,Object> body) { return analytics.addReading(auth.require(session),body); }
    private int integer(Map<String,Object> body,String key,int fallback) {
        if(body==null||!body.containsKey(key)) return fallback;
        try { double value=Double.parseDouble(body.get(key).toString()); if(!Double.isFinite(value)||value!=Math.rint(value)||value<Integer.MIN_VALUE||value>Integer.MAX_VALUE) throw new IllegalArgumentException(); return (int)value; }
        catch(RuntimeException ex) { throw new ApiException(400,key+" must be a whole number."); }
    }
}

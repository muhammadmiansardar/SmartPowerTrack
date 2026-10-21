package za.ac.smartpowertrack;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    private static final int INTERVAL = 60;
    private final Db db;

    public AnalyticsService(Db db) { this.db = db; }

    private LocalDateTime now() { return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS); }
    private LocalDate today() { return LocalDate.now(ZONE); }
    private Timestamp ts(LocalDateTime t) { return Timestamp.valueOf(t); }
    private static double number(Object value) {
        if (value == null) return 0;
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
    }
    private static long id(Object value) { return ((Number) value).longValue(); }
    private static boolean flag(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)); }
    private static BigDecimal rounded(double value) { return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP); }
    private static BigDecimal money(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP); }
    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]);
        return result;
    }
    private Map<String,Object> user(long userId) { return db.one("SELECT * FROM app_user WHERE id=?", userId); }
    private List<Map<String,Object>> appliances(long userId) {
        return db.list("SELECT a.*,s.id AS sensor_id,s.status AS sensor_status FROM appliance a LEFT JOIN simulated_sensor s ON s.appliance_id=a.id WHERE a.user_id=? ORDER BY a.id", userId);
    }
    private int boundedDays(int days) {
        if (days < 1 || days > 90) throw new ApiException(400,"Choose a date range between 1 and 90 days.");
        return days;
    }
    private List<Reading> readings(long userId, LocalDateTime start, LocalDateTime end) {
        return db.list("SELECT r.*,a.id AS appliance_id,a.name AS appliance_name FROM sensor_reading r JOIN simulated_sensor s ON s.id=r.sensor_id JOIN appliance a ON a.id=s.appliance_id WHERE a.user_id=? AND r.recorded_at>? AND r.recorded_at<=? ORDER BY r.recorded_at", userId, ts(start), ts(end.plusDays(1))).stream()
            .map(r -> new Reading(id(r.get("id")),id(r.get("sensorId")),id(r.get("applianceId")),String.valueOf(r.get("applianceName")),time(r.get("recordedAt")),number(r.get("powerW")),(int)number(r.get("intervalSeconds"))))
            .filter(r -> r.start().isBefore(end) && r.end().isAfter(start)).toList();
    }
    private record Reading(long id,long sensorId,long applianceId,String applianceName,LocalDateTime end,double power,int seconds) {
        LocalDateTime start() { return end.minusSeconds(seconds); }
        double secondsWithin(LocalDateTime from,LocalDateTime to) {
            LocalDateTime a = start().isAfter(from) ? start() : from;
            LocalDateTime b = end.isBefore(to) ? end : to;
            return b.isAfter(a) ? Duration.between(a,b).toMillis()/1000.0 : 0;
        }
        double energy(LocalDateTime from,LocalDateTime to) { return power * secondsWithin(from,to) / 3_600_000.0; }
    }
    private double energy(List<Reading> rows,LocalDateTime start,LocalDateTime end) { return rows.stream().mapToDouble(r -> r.energy(start,end)).sum(); }
    private double captured(List<Reading> rows,LocalDateTime start,LocalDateTime end) { return rows.stream().mapToDouble(r -> r.secondsWithin(start,end)).sum(); }
    private double coverage(List<Reading> rows,LocalDateTime start,LocalDateTime end,int applianceCount) {
        double expected = Duration.between(start,end).toSeconds() * (double)applianceCount;
        return expected <= 0 ? 0 : Math.min(100,100 * captured(rows,start,end)/expected);
    }
    private Map<String,Object> daily(LocalDate date,List<Reading> rows,LocalDateTime end,int count,double tariff) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime until = date.plusDays(1).atStartOfDay().isBefore(end) ? date.plusDays(1).atStartOfDay() : end;
        double seconds = captured(rows,start,until);
        Double kwh = seconds > 0 ? energy(rows,start,until) : null;
        return map("date",date.toString(),"kwh",kwh == null ? null : rounded(kwh),"cost",kwh == null ? null : money(kwh*tariff),"coveragePercent",rounded(coverage(rows,start,until,count)));
    }

    public Map<String,Object> dashboard(long userId,int requestedDays) {
        int days = boundedDays(requestedDays);
        Map<String,Object> settings = user(userId);
        LocalDate date = today(); LocalDateTime end = now(); LocalDateTime start = date.minusDays(days-1).atStartOfDay();
        LocalDateTime queryStart = date.minusDays(Math.max(days*2+1,40)).atStartOfDay();
        List<Reading> rows = readings(userId,queryStart,end);
        List<Map<String,Object>> apps = appliances(userId);
        int count = apps.size(); double tariff = number(settings.get("tariff"));
        List<Map<String,Object>> daily = new ArrayList<>();
        for (int i=0;i<days;i++) daily.add(daily(date.minusDays(days-1-i),rows,end,count,tariff));
        double seconds = captured(rows,start,end); Double total = seconds > 0 ? energy(rows,start,end) : null;
        double currentSeconds = captured(rows,date.atStartOfDay(),end);
        Double todayKwh = currentSeconds > 0 ? energy(rows,date.atStartOfDay(),end) : null;
        int observedDays = (int)daily.stream().filter(d -> d.get("kwh") != null).count();
        int historyDays = 0;
        for(int i=1;i<=90;i++) {
            LocalDate d = date.minusDays(i);
            if(coverage(rows,d.atStartOfDay(),d.plusDays(1).atStartOfDay(),count)>=90) historyDays++;
        }
        LocalDateTime previousStart=start.minusDays(days),previousEnd=end.minusDays(days);
        double previousEnergy=energy(rows,previousStart,previousEnd);
        double previousCoverage=coverage(rows,previousStart,previousEnd,count);
        double periodCoverage=coverage(rows,start,end,count);
        Double change = total != null && previousEnergy>0 && previousCoverage>=90 && periodCoverage>=90 ? (total-previousEnergy)/previousEnergy*100 : null;
        Map<Long,Reading> latest = new HashMap<>();
        for(Reading r:rows) latest.put(r.applianceId(),r);
        LocalDateTime last = rows.isEmpty() ? null : rows.get(rows.size()-1).end();
        double currentPower = 0; int fresh = 0;
        for(Reading r:latest.values()) if(Duration.between(r.end(),end).getSeconds() <= INTERVAL*2) { currentPower += r.power(); fresh++; }
        List<Map<String,Object>> applianceResults = new ArrayList<>();
        for(Map<String,Object> a:apps) {
            long applianceId=id(a.get("id"));
            List<Reading> own=rows.stream().filter(r -> r.applianceId()==applianceId).toList();
            double apSeconds=captured(own,start,end), kwh=energy(own,start,end);
            applianceResults.add(map("id",applianceId,"name",a.get("name"),"category",a.get("category"),"kwh",apSeconds>0?rounded(kwh):null,"cost",apSeconds>0?money(kwh*tariff):null,"powerRatingW",a.get("powerRatingW"),"sharePercent",total!=null&&total>0?rounded(kwh/total*100):null));
        }
        applianceResults.sort((a,b)->Double.compare(number(b.get("kwh")),number(a.get("kwh"))));
        Map<String,Object> budget=budget(userId,date.toString().substring(0,7));
        Map<String,Object> prepaid=prepaid(userId);
        generateAlerts(userId,settings,todayKwh,budget,prepaid);
        List<Map<String,Object>> recent=new ArrayList<>();
        for(int i=rows.size()-1;i>=Math.max(0,rows.size()-10);i--) {
            Reading r=rows.get(i); recent.add(map("id",r.id(),"applianceName",r.applianceName(),"recordedAt",r.end().toString(),"powerW",rounded(r.power()),"intervalSeconds",r.seconds()));
        }
        return map("summary",map("todayKwh",todayKwh==null?null:rounded(todayKwh),"periodKwh",total==null?null:rounded(total),"periodCost",total==null?null:money(total*tariff),"averageDailyKwh",total==null?null:rounded(total/Math.max(1,observedDays)),"changePercent",change==null?null:rounded(change),"currentPowerW",fresh>0?rounded(currentPower):null,"lastReadingAt",last==null?null:last.toString(),"coveragePercent",rounded(periodCoverage),"historyDays",historyDays,"tariff",money(tariff),"days",days),
            "daily",daily,"appliances",applianceResults,"budget",budget,"forecast",forecast(userId,7,false),"prepaid",prepaid,"alerts",alerts(userId),"simulation",map("enabled",flag(settings.get("simulationEnabled")),"intervalSeconds",INTERVAL),"recentReadings",recent,
            "manualRecords",db.list("SELECT id,period_start,period_end,energy_kwh,estimated_cost,source FROM consumption_record WHERE user_id=? AND source='MANUAL_HOUSEHOLD' AND period_end>=? ORDER BY period_start DESC",userId,ts(start)));
    }

    public synchronized Map<String,Object> simulate(long userId,int minutes) {
        if(minutes<1 || minutes>1440) throw new ApiException(400,"Simulation duration must be between 1 and 1440 minutes.");
        LocalDateTime end=now().truncatedTo(ChronoUnit.MINUTES), earliest=end.minusMinutes(minutes); int inserted=0;
        List<Map<String,Object>> apps=appliances(userId);
        for(Map<String,Object> a:apps) {
            if(!flag(a.get("active")) || a.get("sensorId")==null || !"ACTIVE".equalsIgnoreCase(String.valueOf(a.get("sensorStatus")))) continue;
            long sensorId=id(a.get("sensorId"));
            List<Map<String,Object>> last=db.list("SELECT recorded_at FROM sensor_reading WHERE sensor_id=? ORDER BY recorded_at DESC LIMIT 1",sensorId);
            LocalDateTime cursor=earliest;
            if(!last.isEmpty()) { LocalDateTime latest=time(last.get(0).get("recordedAt")); if(latest.isAfter(cursor)) cursor=latest; }
            while(!cursor.plusSeconds(INTERVAL).isAfter(end)) {
                LocalDateTime next=cursor.plusSeconds(INTERVAL);
                double power=simulatedPower(a,cursor.plusSeconds(INTERVAL/2));
                db.insert("sensor_reading",map("sensor_id",sensorId,"recorded_at",ts(next),"power_w",rounded(power),"interval_seconds",INTERVAL,"source","SIMULATED"));
                inserted++; cursor=next;
            }
        }
        refreshAggregates(userId,earliest.toLocalDate(),end.toLocalDate());
        return map("message",inserted==0?"No complete elapsed interval is available; readings are up to date.":"Generated simulated readings for elapsed intervals.","inserted",inserted,"source","SIMULATED","through",end.toString());
    }

    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void scheduledSimulation() {
        for(Map<String,Object> u:db.list("SELECT id FROM app_user WHERE simulation_enabled=TRUE")) {
            try { simulate(id(u.get("id")),1); }
            catch(RuntimeException ignored) { /* A single account must not prevent the next account from updating. */ }
        }
    }

    public Map<String,Object> toggle(long userId,boolean enabled) {
        db.execute("UPDATE app_user SET simulation_enabled=? WHERE id=?",enabled,userId);
        return map("enabled",enabled,"intervalSeconds",INTERVAL);
    }

    private double simulatedPower(Map<String,Object> appliance,LocalDateTime at) {
        double rating=number(appliance.get("powerRatingW")),hours=number(appliance.get("dailyHours"));
        String label=(appliance.get("name")+" "+appliance.get("category")).toLowerCase(Locale.ROOT);
        double[] weights=new double[24];
        for(int h=0;h<24;h++) {
            double w;
            if(label.contains("fridge") || label.contains("refriger")) w=0.9+0.2*Math.sin(h*1.7);
            else if(label.contains("light") || label.contains("television") || label.contains("tv")) w=h>=17&&h<=23?2.8:0.08;
            else if(label.contains("geyser") || label.contains("heater") || label.contains("kettle")) w=(h>=5&&h<=8)||(h>=17&&h<=20)?2.5:0.15;
            else w=h>=7&&h<=22?1.4:0.18;
            weights[h]=w;
        }
        double sum=Arrays.stream(weights).sum();
        double dayFactor=0.92+0.16*(0.5+0.5*Math.sin(at.toLocalDate().toEpochDay()*1.31+number(appliance.get("id"))));
        return Math.max(0,Math.min(rating,rating*hours*weights[at.getHour()]/sum*dayFactor));
    }

    public synchronized void seedHistory(long userId,int days) {
        if(days<7 || days>60) throw new ApiException(400,"Sample history must cover 7 to 60 days.");
        if(!db.list("SELECT r.id FROM sensor_reading r JOIN simulated_sensor s ON s.id=r.sensor_id JOIN appliance a ON a.id=s.appliance_id WHERE a.user_id=? LIMIT 1",userId).isEmpty()) return;
        LocalDateTime start=today().minusDays(days).atStartOfDay(), end=now().truncatedTo(ChronoUnit.MINUTES);
        List<Object[]> batch=new ArrayList<>();
        for(Map<String,Object> a:appliances(userId)) {
            if(a.get("sensorId")==null || !flag(a.get("active"))) continue;
            LocalDateTime cursor=start;
            while(cursor.isBefore(end)) {
                LocalDateTime next=cursor.plusHours(1).isBefore(end)?cursor.plusHours(1):end;
                int seconds=(int)Duration.between(cursor,next).getSeconds();
                if(seconds<=0) break;
                batch.add(new Object[]{id(a.get("sensorId")),ts(next),rounded(simulatedPower(a,cursor.plusSeconds(seconds/2))),seconds,"SIMULATED"});
                cursor=next;
            }
        }
        if(!batch.isEmpty()) db.jdbc.batchUpdate("INSERT INTO sensor_reading(sensor_id,recorded_at,power_w,interval_seconds,source) VALUES(?,?,?,?,?)",batch);
        refreshAggregates(userId,start.toLocalDate(),end.toLocalDate());
    }

    private void refreshAggregates(long userId,LocalDate first,LocalDate last) {
        double tariff=number(user(userId).get("tariff")); LocalDateTime end=now();
        List<Reading> rows=readings(userId,first.atStartOfDay(),last.plusDays(1).atStartOfDay());
        for(Map<String,Object> a:appliances(userId)) for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)) {
            long applianceId=id(a.get("id")); LocalDateTime start=d.atStartOfDay(),until=d.plusDays(1).atStartOfDay(); if(until.isAfter(end)) until=end;
            List<Reading> own=rows.stream().filter(r->r.applianceId()==applianceId).toList();
            if(captured(own,start,until)<=0) continue;
            double kwh=energy(own,start,until);
            List<Map<String,Object>> existing=db.list("SELECT id FROM consumption_record WHERE appliance_id=? AND period_start=? AND source='SIMULATED'",applianceId,ts(start));
            if(existing.isEmpty()) db.insert("consumption_record",map("user_id",userId,"appliance_id",applianceId,"period_start",ts(start),"period_end",ts(until),"energy_kwh",rounded(kwh),"estimated_cost",money(kwh*tariff),"source","SIMULATED"));
            else db.execute("UPDATE consumption_record SET period_end=?,energy_kwh=?,estimated_cost=? WHERE id=? AND user_id=?",ts(until),rounded(kwh),money(kwh*tariff),id(existing.get(0).get("id")),userId);
        }
    }

    public synchronized Map<String,Object> addReading(long userId,Map<String,Object> body) {
        Long sensorId=null;
        try {
            sensorId=requiredLong(body,"sensorId");
            List<Map<String,Object>> sensors=db.list("SELECT s.*,a.active FROM simulated_sensor s JOIN appliance a ON a.id=s.appliance_id WHERE s.id=? AND a.user_id=?",sensorId,userId);
            if(sensors.isEmpty()) throw new ApiException(404,"Sensor not found.");
            Map<String,Object> sensor=sensors.get(0);
            if(!flag(sensor.get("active"))) throw new ApiException(400,"The appliance is archived.");
            double power=requiredNumber(body,"powerW"); int seconds=requiredInt(body,"intervalSeconds");
            if(power<0 || power>100000) throw new ApiException(400,"Power must be between 0 and 100000 watts.");
            if(seconds<1 || seconds>86400) throw new ApiException(400,"The reading interval must be between 1 and 86400 seconds.");
            LocalDateTime end;
            try { end=LocalDateTime.parse(String.valueOf(body.get("recordedAt"))); }
            catch(RuntimeException ex) { throw new ApiException(400,"Use a valid local date and time for recordedAt."); }
            if(end.isAfter(now())) throw new ApiException(400,"Readings cannot be in the future.");
            if(end.isBefore(now().minusDays(366))) throw new ApiException(400,"Readings must be within the previous 366 days.");
            LocalDateTime start=end.minusSeconds(seconds);
            for(Map<String,Object> existing:db.list("SELECT recorded_at,interval_seconds FROM sensor_reading WHERE sensor_id=? AND recorded_at>? AND recorded_at<=?",sensorId,ts(start),ts(end.plusDays(1)))) {
                LocalDateTime previousEnd=time(existing.get("recordedAt")),previousStart=previousEnd.minusSeconds((int)number(existing.get("intervalSeconds")));
                if(previousEnd.isAfter(start) && previousStart.isBefore(end)) throw new ApiException(409,"This reading duplicates or overlaps an existing sensor interval.");
            }
            long readingId=db.insert("sensor_reading",map("sensor_id",sensorId,"recorded_at",ts(end),"power_w",rounded(power),"interval_seconds",seconds,"source","SIMULATED"));
            refreshAggregates(userId,start.toLocalDate(),end.toLocalDate());
            return map("id",readingId,"sensorId",sensorId,"recordedAt",end.toString(),"powerW",rounded(power),"intervalSeconds",seconds,"energyKwh",rounded(power*seconds/3_600_000.0),"source","SIMULATED");
        } catch(ApiException ex) {
            Long ownedSensor=sensorId;
            if(ownedSensor!=null && db.list("SELECT s.id FROM simulated_sensor s JOIN appliance a ON a.id=s.appliance_id WHERE s.id=? AND a.user_id=?",ownedSensor,userId).isEmpty()) ownedSensor=null;
            db.insert("reading_rejection",map("user_id",userId,"sensor_id",ownedSensor,"reason",ex.getMessage(),"created_at",ts(now())));
            throw ex;
        }
    }

    private static double requiredNumber(Map<String,Object> body,String field) {
        try { Object raw=body.get(field); if(raw==null) throw new IllegalArgumentException(); double n=Double.parseDouble(raw.toString()); if(!Double.isFinite(n)) throw new IllegalArgumentException(); return n; }
        catch(RuntimeException ex) { throw new ApiException(400,"A valid number is required for "+field+"."); }
    }
    private static int requiredInt(Map<String,Object> body,String field) { double n=requiredNumber(body,field); if(n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE) throw new ApiException(400,field+" must be a whole number."); return (int)n; }
    private static long requiredLong(Map<String,Object> body,String field) { double n=requiredNumber(body,field); if(n!=Math.rint(n)||n<=0||n>Long.MAX_VALUE) throw new ApiException(400,field+" must be a positive whole number."); return (long)n; }

    public Map<String,Object> budget(long userId) { return budget(userId,today().toString().substring(0,7)); }
    private Map<String,Object> budget(long userId,String month) {
        YearMonth m;
        try { m=YearMonth.parse(month); if(!month.equals(m.toString())) throw new IllegalArgumentException(); }
        catch(RuntimeException ex) { throw new ApiException(400,"Use YYYY-MM for the budget month."); }
        double tariff=number(user(userId).get("tariff"));
        LocalDateTime start=m.atDay(1).atStartOfDay(),end=m.plusMonths(1).atDay(1).atStartOfDay(); if(end.isAfter(now())) end=now();
        double spent=end.isAfter(start)?energy(readings(userId,start,end),start,end)*tariff:0;
        List<Map<String,Object>> budgets=db.list("SELECT * FROM budget WHERE user_id=? AND budget_month=?",userId,month);
        if(budgets.isEmpty()) return map("month",month,"amount",null,"spent",money(spent),"remaining",null,"percent",null,"status","NOT_SET");
        double amount=number(budgets.get(0).get("amount")),percent=spent/amount*100,threshold=number(user(userId).get("budgetThresholdPct"));
        return map("month",month,"amount",money(amount),"spent",money(spent),"remaining",money(amount-spent),"percent",rounded(percent),"status",percent>=100?"OVER_BUDGET":percent>=threshold?"NEAR_LIMIT":"ON_TRACK");
    }
    public synchronized Map<String,Object> saveBudget(long userId,Map<String,Object> body) {
        double amount=requiredNumber(body,"amount"); if(amount<=0||amount>1_000_000) throw new ApiException(400,"Budget must be greater than zero and no more than 1000000.");
        String month=String.valueOf(body.getOrDefault("month",today().toString().substring(0,7)));
        budget(userId,month);
        List<Map<String,Object>> found=db.list("SELECT id FROM budget WHERE user_id=? AND budget_month=?",userId,month);
        if(found.isEmpty()) db.insert("budget",map("user_id",userId,"budget_month",month,"amount",money(amount)));
        else db.execute("UPDATE budget SET amount=? WHERE id=? AND user_id=?",money(amount),id(found.get(0).get("id")),userId);
        return budget(userId,month);
    }

    public Map<String,Object> prepaid(long userId) {
        List<Map<String,Object>> openings=db.list("SELECT * FROM prepaid_transaction WHERE user_id=? AND kind='OPENING' ORDER BY recorded_at DESC,id DESC LIMIT 1",userId);
        List<Map<String,Object>> transactions=db.list("SELECT id,kind,amount,recorded_at,note FROM prepaid_transaction WHERE user_id=? ORDER BY recorded_at DESC,id DESC LIMIT 100",userId);
        if(openings.isEmpty()) return map("available",false,"reason","Set an opening balance before estimating prepaid credit.","balance",null,"openingAt",null,"topUps",money(0),"estimatedConsumptionCost",null,"transactions",transactions);
        Map<String,Object> opening=openings.get(0); LocalDateTime at=time(opening.get("recordedAt")),end=now();
        double topUps=db.list("SELECT amount FROM prepaid_transaction WHERE user_id=? AND kind='TOP_UP' AND recorded_at>=? AND recorded_at<=? AND id>?",userId,ts(at),ts(end),id(opening.get("id"))).stream().mapToDouble(r->number(r.get("amount"))).sum();
        double cost=energy(readings(userId,at,end),at,end)*number(user(userId).get("tariff"));
        return map("available",true,"balance",money(number(opening.get("amount"))+topUps-cost),"openingAt",at.toString(),"topUps",money(topUps),"estimatedConsumptionCost",money(cost),"transactions",transactions,"estimate",true);
    }
    public synchronized Map<String,Object> addPrepaid(long userId,Map<String,Object> body) {
        String kind=String.valueOf(body.get("kind")).toUpperCase(Locale.ROOT);
        if(!Set.of("OPENING","TOP_UP").contains(kind)) throw new ApiException(400,"Choose OPENING or TOP_UP.");
        double amount=requiredNumber(body,"amount");
        if(amount<0 || amount>1_000_000 || ("TOP_UP".equals(kind)&&amount<=0)) throw new ApiException(400,"Enter a valid positive transaction amount; an opening balance may be zero.");
        if("TOP_UP".equals(kind)&&!Boolean.TRUE.equals(prepaid(userId).get("available"))) throw new ApiException(400,"Set an opening balance before adding top-ups.");
        String note=String.valueOf(body.getOrDefault("note","")); if(note.length()>255) throw new ApiException(400,"The transaction note must be 255 characters or fewer.");
        db.insert("prepaid_transaction",map("user_id",userId,"kind",kind,"amount",money(amount),"recorded_at",ts(now()),"note",note));
        return prepaid(userId);
    }

    public List<Map<String,Object>> alerts(long userId) {
        LocalDateTime end=now(),start=today().atStartOfDay();
        List<Reading> rows=readings(userId,start,end);
        Double used=captured(rows,start,end)>0?energy(rows,start,end):null;
        generateAlerts(userId,user(userId),used,budget(userId),prepaid(userId));
        return db.list("SELECT id,type,message,created_at,status FROM alert WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100",userId);
    }
    public Map<String,Object> updateAlert(long userId,long alertId,Map<String,Object> body) {
        String status=String.valueOf(body.get("status")).toUpperCase(Locale.ROOT);
        if(!Set.of("READ","UNREAD","DISMISSED").contains(status)) throw new ApiException(400,"Choose READ, UNREAD or DISMISSED.");
        if(db.execute("UPDATE alert SET status=? WHERE id=? AND user_id=?",status,alertId,userId)==0) throw new ApiException(404,"Alert not found.");
        return db.one("SELECT id,type,message,created_at,status FROM alert WHERE id=? AND user_id=?",alertId,userId);
    }
    private synchronized void generateAlerts(long userId,Map<String,Object> settings,Double todayKwh,Map<String,Object> budget,Map<String,Object> prepaid) {
        if(todayKwh!=null&&todayKwh>number(settings.get("dayThresholdKwh"))) alert(userId,"HIGH_USAGE",today().toString(),"Today's simulated consumption has exceeded your daily usage threshold.");
        if(budget.get("percent")!=null&&number(budget.get("percent"))>=number(settings.get("budgetThresholdPct"))) alert(userId,"BUDGET",String.valueOf(budget.get("month")),"Estimated electricity spending has reached "+money(number(budget.get("percent")))+"% of your monthly budget.");
        if(Boolean.TRUE.equals(prepaid.get("available"))&&number(prepaid.get("balance"))<=number(settings.get("lowBalanceThreshold"))) alert(userId,"LOW_BALANCE",today().toString(),"Your estimated prepaid credit is below your low-balance threshold. Verify the actual balance on your meter.");
    }
    private void alert(long userId,String type,String period,String message) {
        String key=userId+":"+type+":"+period;
        if(db.list("SELECT id FROM alert WHERE alert_key=?",key).isEmpty()) db.insert("alert",map("user_id",userId,"type",type,"message",message,"created_at",ts(now()),"status","UNREAD","alert_key",key));
    }

    public synchronized Map<String,Object> forecast(long userId,int days,boolean persist) {
        if(!Set.of(7,14,30).contains(days)) throw new ApiException(400,"The forecast horizon must be 7, 14 or 30 days.");
        LocalDate date=today(),historyEnd=date.minusDays(1),historyStart=date.minusDays(7);
        List<Reading> rows=readings(userId,date.minusDays(10).atStartOfDay(),date.atStartOfDay());
        int count=appliances(userId).size(); double tariff=number(user(userId).get("tariff"));
        List<Double> history=new ArrayList<>(); boolean enough=count>0;
        for(int i=7;i>=1;i--) {
            LocalDate d=date.minusDays(i);
            if(coverage(rows,d.atStartOfDay(),d.plusDays(1).atStartOfDay(),count)<90) enough=false;
            history.add(energy(rows,d.atStartOfDay(),d.plusDays(1).atStartOfDay()));
        }
        String method="Trailing 7-day mean (simulated consumption)";
        if(!enough) return map("available",false,"reason","At least 7 consecutive complete days with 90% sensor coverage are required.","days",days,"historyStart",historyStart.toString(),"historyEnd",historyEnd.toString(),"forecastStart",date.toString(),"forecastEnd",date.plusDays(days-1).toString(),"predictedKwh",null,"predictedCost",null,"dailyAverageKwh",null,"method",method,"maeKwh",null,"points",List.of());
        double mean=history.stream().mapToDouble(Double::doubleValue).average().orElse(0),predicted=mean*days;
        Double mae=null; boolean ten=true; List<Double> tenDays=new ArrayList<>();
        for(int i=10;i>=1;i--) {
            LocalDate d=date.minusDays(i);
            if(coverage(rows,d.atStartOfDay(),d.plusDays(1).atStartOfDay(),count)<90) ten=false;
            tenDays.add(energy(rows,d.atStartOfDay(),d.plusDays(1).atStartOfDay()));
        }
        if(ten) {
            double error=0;
            for(int i=7;i<10;i++) { double train=0; for(int j=i-7;j<i;j++) train+=tenDays.get(j); error+=Math.abs(tenDays.get(i)-train/7); }
            mae=error/3;
        }
        List<Map<String,Object>> points=new ArrayList<>(); for(int i=0;i<days;i++) points.add(map("date",date.plusDays(i).toString(),"kwh",rounded(mean),"cost",money(mean*tariff)));
        if(persist) db.insert("prediction",map("user_id",userId,"generated_at",ts(now()),"history_start",java.sql.Date.valueOf(historyStart),"history_end",java.sql.Date.valueOf(historyEnd),"forecast_start",java.sql.Date.valueOf(date),"forecast_end",java.sql.Date.valueOf(date.plusDays(days-1)),"predicted_kwh",rounded(predicted),"predicted_cost",money(predicted*tariff),"method",method,"mae_kwh",mae==null?null:rounded(mae)));
        return map("available",true,"days",days,"historyStart",historyStart.toString(),"historyEnd",historyEnd.toString(),"forecastStart",date.toString(),"forecastEnd",date.plusDays(days-1).toString(),"predictedKwh",rounded(predicted),"predictedCost",money(predicted*tariff),"dailyAverageKwh",rounded(mean),"method",method,"maeKwh",mae==null?null:rounded(mae),"points",points,"tariff",money(tariff));
    }
}

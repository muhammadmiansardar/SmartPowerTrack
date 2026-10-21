package za.ac.smartpowertrack;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
class SchemaIndexes implements ApplicationRunner {
    private final Db db;
    SchemaIndexes(Db db) {this.db=db;}
    public void run(ApplicationArguments args) throws Exception {
        var wanted=Map.of("appliance",Map.of("idx_appliance_user","user_id"),"sensor_reading",Map.of("idx_reading_time","recorded_at"),"consumption_record",Map.of("idx_consumption_user_period","user_id,period_start"),"alert",Map.of("idx_alert_user","user_id,created_at"),"ai_image_upload",Map.of("idx_upload_user","user_id"),"prepaid_transaction",Map.of("idx_prepaid_user_time","user_id,recorded_at"));
        try(var c=Objects.requireNonNull(db.jdbc.getDataSource()).getConnection()) {
            var md=c.getMetaData();
            try(var tables=md.getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})) {
                while(tables.next()) {
                    String table=tables.getString("TABLE_NAME");var indexes=wanted.get(table.toLowerCase(Locale.ROOT));if(indexes==null)continue;
                    Set<String> existing=new HashSet<>();
                    try(var rs=md.getIndexInfo(c.getCatalog(),null,table,false,false)) {while(rs.next()){String n=rs.getString("INDEX_NAME");if(n!=null)existing.add(n.toLowerCase(Locale.ROOT));}}
                    for(var e:indexes.entrySet()) if(!existing.contains(e.getKey())) db.execute("CREATE INDEX "+e.getKey()+" ON "+table+" ("+e.getValue()+")");
                }
            }
        }
    }
}

package za.ac.smartpowertrack;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Db {
    public final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (rs,n) -> {
            Map<String,Object> m = new LinkedHashMap<>();
            var md=rs.getMetaData();
            for (int i=1;i<=md.getColumnCount();i++) {
                String k=camel(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                Object v=rs.getObject(i);
                if(v instanceof Timestamp t) v=t.toLocalDateTime().toString();
                if(v instanceof java.sql.Date d) v=d.toLocalDate().toString();
                m.put(k,v);
            }
            return m;
        }, args);
    }
    public Map<String,Object> one(String sql,Object...args) {
        var rows=list(sql,args);
        if(rows.isEmpty()) throw new ApiException(404,"That record was not found in your account.");
        return rows.get(0);
    }
    public int execute(String sql,Object...args) { return jdbc.update(sql,args); }
    public long insert(String table,Map<String,Object> values) {
        if(!table.matches("[a-z_]+") || values.keySet().stream().anyMatch(k->!k.matches("[a-z_]+"))) throw new IllegalArgumentException("Invalid SQL identifier");
        var keys=new ArrayList<>(values.keySet());
        String sql="INSERT INTO "+table+" ("+String.join(",",keys)+") VALUES ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")";
        var holder=new GeneratedKeyHolder();
        jdbc.update(c->{
            var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<keys.size();i++) ps.setObject(i+1,values.get(keys.get(i)));
            return ps;
        },holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private String camel(String s) {
        StringBuilder b=new StringBuilder(); boolean up=false;
        for(char c:s.toCharArray()) { if(c=='_') up=true; else {b.append(up?Character.toUpperCase(c):c);up=false;} }
        return b.toString();
    }
}

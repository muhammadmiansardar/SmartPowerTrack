package za.ac.smartpowertrack;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;

public final class Values {
    private Values() {}
    public static String text(Map<String,?> m,String key,int max) {
        Object v=m.get(key); String s=v==null?"":v.toString().trim();
        if(s.isEmpty()||s.length()>max) throw new ApiException(400,key+" must contain between 1 and "+max+" characters.");
        return s;
    }
    public static double number(Map<String,?> m,String key,double min,double max) {
        try {
            Object raw=m.get(key);if(raw==null||raw.toString().isBlank()) throw new NumberFormatException();
            double n=new BigDecimal(raw.toString()).doubleValue();
            if(!Double.isFinite(n)||n<min||n>max) throw new NumberFormatException();
            return n;
        } catch(Exception e) { throw new ApiException(400,key+" must be a number between "+min+" and "+max+"."); }
    }
    public static LocalDate date(Map<String,?> m,String key) {
        try {return LocalDate.parse(String.valueOf(m.get(key)));}
        catch(Exception e) {throw new ApiException(400,key+" must be a date in YYYY-MM-DD format.");}
    }
    public static long id(Map<String,?> m,String key) {return (long)number(m,key,1,Long.MAX_VALUE);}
}

package za.ac.smartpowertrack;

import jakarta.servlet.http.*;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class Auth {
    private final Db db;
    private static final SecureRandom RANDOM=new SecureRandom();
    public Auth(Db db) {this.db=db;}
    public static long require(HttpSession session) {
        Object id=session.getAttribute("uid");
        if(!(id instanceof Number)) throw new ApiException(401,"Please sign in to continue.");
        return ((Number)id).longValue();
    }
    public String csrf(HttpSession session) {
        if(session.getAttribute("csrf")==null) {
            byte[] b=new byte[32];RANDOM.nextBytes(b);session.setAttribute("csrf",Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        }
        return session.getAttribute("csrf").toString();
    }
    public Map<String,Object> user(long id) {
        return db.one("SELECT id,name,email,household_name,tariff,day_threshold_kwh,budget_threshold_pct,low_balance_threshold,simulation_enabled FROM app_user WHERE id=?",id);
    }
    public Map<String,Object> session(HttpSession session) {
        Map<String,Object> out=new LinkedHashMap<>(); Object id=session.getAttribute("uid");
        out.put("authenticated",id instanceof Number);out.put("user",id instanceof Number?user(((Number)id).longValue()):null);
        out.put("csrfToken",csrf(session));out.put("aiMode","demo");
        out.put("database",db.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName()));
        return out;
    }
    public String hash(String password) {
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        return "pbkdf2$210000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,210000));
    }
    public boolean matches(String password,String hash) {
        try {String[] p=hash.split("\\$");return MessageDigest.isEqual(Base64.getDecoder().decode(p[3]),derive(password,Base64.getDecoder().decode(p[2]),Integer.parseInt(p[1])));}
        catch(Exception e) {return false;}
    }
    private byte[] derive(String password,byte[] salt,int iterations) {
        var spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try {return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(Exception e) {throw new IllegalStateException("Password hashing unavailable",e);}
        finally {spec.clearPassword();}
    }
}

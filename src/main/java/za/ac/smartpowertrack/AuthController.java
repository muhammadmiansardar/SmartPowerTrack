package za.ac.smartpowertrack;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final Auth auth;private final Db db;
    public AuthController(Auth auth,Db db) {this.auth=auth;this.db=db;}
    @GetMapping("/session") public Map<String,Object> session(HttpSession session) {return auth.session(session);}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        var session=req.getSession();
        long now=System.currentTimeMillis();
        Long since=(Long)session.getAttribute("attemptAt");
        if(since==null||now-since>600_000) {session.setAttribute("attemptAt",now);session.setAttribute("attempts",0);}
        int attempts=(Integer)session.getAttribute("attempts");
        if(attempts>=10) throw new ApiException(429,"Too many sign-in attempts. Try again in ten minutes.");
        String email=Values.text(body,"email",254).toLowerCase(Locale.ROOT);
        String password=Values.text(body,"password",200);
        var rows=db.list("SELECT id,password_hash FROM app_user WHERE email=?",email);
        if(rows.isEmpty()||!auth.matches(password,rows.get(0).get("passwordHash").toString())) {
            session.setAttribute("attempts",attempts+1);throw new ApiException(401,"The email or password is incorrect.");
        }
        req.changeSessionId();session.setAttribute("uid",rows.get(0).get("id"));session.setAttribute("attempts",0);
        session.removeAttribute("csrf");return auth.session(session);
    }
    @PostMapping("/register") public Map<String,Object> register(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String name=Values.text(body,"name",100),email=Values.text(body,"email",254).toLowerCase(Locale.ROOT);
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ApiException(400,"Enter an email address in a valid format.");
        String password=Values.text(body,"password",200);
        if(password.length()<10) throw new ApiException(400,"Use a password with at least 10 characters.");
        String household=body.get("householdName")==null?"My household":Values.text(body,"householdName",120);
        long id;
        try {id=db.insert("app_user",Map.of("name",name,"email",email,"password_hash",auth.hash(password),"household_name",household,"created_at",PowerTrackApplication.now()));}
        catch(DuplicateKeyException e) {throw new ApiException(409,"An account with that email already exists.");}
        var session=req.getSession();req.changeSessionId();session.setAttribute("uid",id);session.removeAttribute("csrf");return auth.session(session);
    }
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req) {
        req.getSession().invalidate();return auth.session(req.getSession(true));
    }
}

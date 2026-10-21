package za.ac.smartpowertrack;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Configuration
public class ApiConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
                res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("X-Frame-Options","DENY");res.setHeader("Referrer-Policy","same-origin");
                res.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
                if(req.getRequestURI().startsWith("/api/")) {
                    res.setHeader("Cache-Control","no-store");
                    if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
                        var session=req.getSession(false);String supplied=req.getHeader("X-CSRF-Token");
                        String expected=session==null?null:(String)session.getAttribute("csrf");
                        if(expected==null||supplied==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
                            throw new ApiException(403,"Your session needs refreshing. Reload the page and try again.");
                    }
                }
                return true;
            }
        });
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) {return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> upload(Exception e) {return ResponseEntity.status(413).body(Map.of("message","Choose an image smaller than 5 MB."));}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) {return ResponseEntity.badRequest().body(Map.of("message","Check the submitted fields and try again."));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> constraint(Exception e) {return ResponseEntity.status(409).body(Map.of("message","This would duplicate a record or break a data relationship. Refresh and try again."));}
}

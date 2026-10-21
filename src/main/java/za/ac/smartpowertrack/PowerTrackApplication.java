package za.ac.smartpowertrack;

import java.time.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PowerTrackApplication {
    public static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    public static LocalDateTime now() { return LocalDateTime.now(ZONE).withNano(0); }
    public static LocalDate today() { return LocalDate.now(ZONE); }
    public static void main(String[] args) { SpringApplication.run(PowerTrackApplication.class, args); }
}

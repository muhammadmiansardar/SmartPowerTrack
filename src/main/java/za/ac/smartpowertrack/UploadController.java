package za.ac.smartpowertrack;

import jakarta.servlet.http.HttpSession;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final Db db;private final Path folder;
    public UploadController(Db db,@Value("${powertrack.upload-dir:./data/uploads}") String dir) throws IOException {
        this.db=db;folder=Path.of(dir).toAbsolutePath().normalize();Files.createDirectories(folder);
    }
    @GetMapping public List<Map<String,Object>> list(HttpSession session) {
        var records=db.list("SELECT * FROM ai_image_upload WHERE user_id=? ORDER BY created_at DESC,id DESC",Auth.require(session));
        records.forEach(this::decorate);return records;
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file,@RequestParam String imageType,@RequestParam(required=false) Long applianceId,HttpSession session) throws IOException {
        long uid=Auth.require(session);String type=type(imageType);checkAppliance(uid,applianceId);
        if(file.isEmpty()||file.getSize()>5*1024*1024) throw new ApiException(400,"Choose a PNG or JPEG image between 1 byte and 5 MB.");
        BufferedImage image;
        try(ImageInputStream in=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(in);
            if(!readers.hasNext())throw new ApiException(400,"This file is not a readable PNG or JPEG image.");
            var reader=readers.next();
            try {
                String fmt=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(fmt))throw new ApiException(400,"Only PNG and JPEG images are supported.");
                reader.setInput(in);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<=0||h<=0||(long)w*h>20_000_000)throw new ApiException(400,"Choose an image smaller than 20 megapixels.");
                image=reader.read(0);
            } finally {reader.dispose();}
        } catch(ApiException e) {throw e;} catch(IOException|RuntimeException e) {throw new ApiException(400,"The image could not be read. Try another PNG or JPEG.");}
        // Re-encode supported images to strip metadata and non-image payloads.
        double scale=Math.min(1,1800.0/Math.max(image.getWidth(),image.getHeight()));
        BufferedImage safe=new BufferedImage(Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=safe.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,safe.getWidth(),safe.getHeight());g.drawImage(image,0,0,safe.getWidth(),safe.getHeight(),null);g.dispose();
        String filename=UUID.randomUUID()+".png";ImageIO.write(safe,"png",folder.resolve(filename).toFile());
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("image.png").replace('\\','/');original=original.substring(original.lastIndexOf('/')+1);if(original.length()>180)original=original.substring(0,180);
        if(original.startsWith("sample-")) original="upload-"+original;
        long id=insert(uid,type,applianceId,filename,original,null);
        var row=own(uid,id);row.put("message","Image saved privately. Live AI extraction is not configured; enter the value from the image and confirm it after review.");return row;
    }
    @PostMapping("/sample") public Map<String,Object> sample(@RequestBody Map<String,Object> body,HttpSession session) throws IOException {
        long uid=Auth.require(session);String type=type(Values.text(body,"imageType",20));
        Long aid=body.get("applianceId")==null?null:Values.id(body,"applianceId");checkAppliance(uid,aid);
        double value=type.equals("APPLIANCE")?1800:type.equals("BILL")?220.5:1245.6;
        BufferedImage img=new BufferedImage(900,570,BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(242,245,240));g.fillRect(0,0,900,570);g.setColor(new Color(25,45,35));g.setFont(new Font("SansSerif",Font.BOLD,27));g.drawString("SMART POWER-TRACK / SAMPLE FIXTURE",55,75);
        g.setFont(new Font("SansSerif",Font.PLAIN,26));g.drawString(type+" DEMONSTRATION IMAGE",55,135);g.setColor(Color.WHITE);g.fillRoundRect(55,180,790,180,18,18);g.setColor(new Color(25,45,35));g.setFont(new Font("Monospaced",Font.BOLD,64));g.drawString(String.format(Locale.ROOT,"%.2f %s",value,unit(type)),90,290);
        g.setFont(new Font("SansSerif",Font.PLAIN,22));g.drawString("Illustrative value supplied by the sample fixture.",55,425);g.drawString("This demonstrates review, not live image recognition.",55,465);g.dispose();
        String filename=UUID.randomUUID()+".png";ImageIO.write(img,"png",folder.resolve(filename).toFile());
        long id=insert(uid,type,aid,filename,"sample-"+type.toLowerCase(Locale.ROOT)+".png",value);
        var row=own(uid,id);row.put("message","Sample fixture ready for review. The suggested value is illustrative, not AI-extracted.");return row;
    }
    @PostMapping("/{id}/confirm") @Transactional public Map<String,Object> confirm(@PathVariable long id,@RequestBody Map<String,Object> body,HttpSession session) {
        long uid=Auth.require(session);var row=ownLocked(uid,id);pending(row);
        double value=Values.number(body,"value",0,10_000_000);String type=row.get("imageType").toString();
        Long aid=null;
        if(body.get("applianceId")!=null) aid=Values.id(body,"applianceId");
        else if(row.get("applianceId") instanceof Number n) aid=n.longValue();
        checkAppliance(uid,aid);LocalDate from=null,to=null;
        if(type.equals("APPLIANCE")) {
            if(aid==null)throw new ApiException(400,"Choose the appliance whose power rating you want to confirm.");
            if(value>50000)throw new ApiException(400,"Power rating must not exceed 50,000 W.");
            db.execute("UPDATE appliance SET power_rating_w=? WHERE id=? AND user_id=?",value,aid,uid);
        } else if(type.equals("BILL")) {
            from=Values.date(body,"periodStart");to=Values.date(body,"periodEnd");
            if(to.isBefore(from)||to.isAfter(PowerTrackApplication.today())||ChronoUnit.DAYS.between(from,to)>=366) throw new ApiException(400,"Choose a bill period of up to 366 days that ends on or before today.");
            double tariff=((Number)db.one("SELECT tariff FROM app_user WHERE id=?",uid).get("tariff")).doubleValue();
            var rec=new LinkedHashMap<String,Object>();rec.put("user_id",uid);rec.put("appliance_id",null);rec.put("period_start",from.atStartOfDay());rec.put("period_end",to.plusDays(1).atStartOfDay());rec.put("energy_kwh",value);rec.put("estimated_cost",value*tariff);rec.put("source","MANUAL_HOUSEHOLD");db.insert("consumption_record",rec);
        }
        // A cumulative meter observation is not automatically interval energy.
        db.execute("UPDATE ai_image_upload SET review_status='CONFIRMED',confirmed_value=?,appliance_id=?,period_start=?,period_end=?,confirmed_at=? WHERE id=? AND user_id=?",value,aid,from,to,PowerTrackApplication.now(),id,uid);
        var out=own(uid,id);out.put("message",type.equals("METER")?"Meter observation confirmed. Consumption totals were not changed because this is a cumulative reading.":type.equals("BILL")?"Bill period confirmed as a separate manual record. Simulated totals were not changed.":"Confirmed power rating saved to your appliance.");return out;
    }
    @PostMapping("/{id}/reject") @Transactional public Map<String,Object> reject(@PathVariable long id,HttpSession session) {
        long uid=Auth.require(session);var row=ownLocked(uid,id);pending(row);db.execute("UPDATE ai_image_upload SET review_status='REJECTED' WHERE id=? AND user_id=?",id,uid);return own(uid,id);
    }
    @PostMapping("/{id}/retry") public Map<String,Object> retry(@PathVariable long id,HttpSession session) {
        long uid=Auth.require(session);var row=own(uid,id);pending(row);
        db.execute("UPDATE ai_image_upload SET processing_status='READY' WHERE id=? AND user_id=?",id,uid);
        var out=own(uid,id);out.put("message","Live AI extraction is not configured. Enter the value manually or try a sample fixture.");return out;
    }
    @GetMapping("/{id}/file") public ResponseEntity<FileSystemResource> file(@PathVariable long id,HttpSession session) {
        var row=own(Auth.require(session),id);Path file=folder.resolve(row.get("fileReference").toString()).normalize();
        if(!file.startsWith(folder)||!Files.isRegularFile(file))throw new ApiException(404,"The uploaded file is no longer available.");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","private, no-store").header("Content-Disposition","inline; filename=review-image.png").body(new FileSystemResource(file));
    }
    private String type(String raw) {String t=raw.toUpperCase(Locale.ROOT);if(!Set.of("METER","BILL","APPLIANCE").contains(t))throw new ApiException(400,"Choose METER, BILL or APPLIANCE as the image type.");return t;}
    private String unit(String type) {return type.equals("APPLIANCE")?"W":"kWh";}
    private void checkAppliance(long uid,Long aid) {if(aid!=null)db.one("SELECT id FROM appliance WHERE id=? AND user_id=?",aid,uid);}
    private long insert(long uid,String type,Long aid,String file,String original,Double value) {
        var m=new LinkedHashMap<String,Object>();m.put("user_id",uid);m.put("appliance_id",aid);m.put("image_type",type);m.put("original_name",original);m.put("file_reference",file);m.put("extracted_value",value);m.put("unit",unit(type));m.put("processing_status","READY");m.put("review_status","PENDING");m.put("created_at",PowerTrackApplication.now());return db.insert("ai_image_upload",m);
    }
    private Map<String,Object> own(long uid,long id) {var m=db.one("SELECT * FROM ai_image_upload WHERE id=? AND user_id=?",id,uid);decorate(m);return m;}
    private Map<String,Object> ownLocked(long uid,long id) {var m=db.one("SELECT * FROM ai_image_upload WHERE id=? AND user_id=? FOR UPDATE",id,uid);decorate(m);return m;}
    private void pending(Map<String,Object> row) {if(!"PENDING".equals(row.get("reviewStatus")))throw new ApiException(409,"This image has already been reviewed. Create another upload for a new entry.");}
    private void decorate(Map<String,Object> row) {boolean sample=row.get("originalName").toString().startsWith("sample-");row.put("isSample",sample);row.put("extractionMode",sample?"DEMO_FIXTURE":"MANUAL");row.put("fileUrl","/api/uploads/"+row.get("id")+"/file");}
}

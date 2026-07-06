package hellcat.core.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class HellcatResponse {

    private static final ObjectMapper Json = new ObjectMapper();

    protected static final Map<Integer, String> StatusMessages = new LinkedHashMap<>();

    static {
        StatusMessages.put(100, "Continue");
        StatusMessages.put(101, "Switching Protocols");
        StatusMessages.put(200, "OK");
        StatusMessages.put(201, "Created");
        StatusMessages.put(204, "No Content");
        StatusMessages.put(206, "Partial Content");
        StatusMessages.put(301, "Moved Permanently");
        StatusMessages.put(302, "Found");
        StatusMessages.put(304, "Not Modified");
        StatusMessages.put(400, "Bad Request");
        StatusMessages.put(401, "Unauthorized");
        StatusMessages.put(403, "Forbidden");
        StatusMessages.put(404, "Not Found");
        StatusMessages.put(405, "Method Not Allowed");
        StatusMessages.put(408, "Request Timeout");
        StatusMessages.put(409, "Conflict");
        StatusMessages.put(413, "Payload Too Large");
        StatusMessages.put(415, "Unsupported Media Type");
        StatusMessages.put(422, "Unprocessable Entity");
        StatusMessages.put(429, "Too Many Requests");
        StatusMessages.put(500, "Internal Server Error");
        StatusMessages.put(501, "Not Implemented");
        StatusMessages.put(502, "Bad Gateway");
        StatusMessages.put(503, "Service Unavailable");
    }

    public int StatusCode;
    public String ContentType;
    public Map<String, String> Headers;
    public List<String> Cookies;
    public byte[] BodyBytes;

    public HellcatResponse(String Body, int StatusCode, String ContentType) {
        this.StatusCode = StatusCode;
        this.ContentType = ContentType;
        this.Headers = new LinkedHashMap<>();
        this.Cookies = new ArrayList<>();
        this.BodyBytes = Body != null ? Body.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
    }

    public HellcatResponse(byte[] Body, int StatusCode, String ContentType) {
        this.StatusCode = StatusCode;
        this.ContentType = ContentType;
        this.Headers = new LinkedHashMap<>();
        this.Cookies = new ArrayList<>();
        this.BodyBytes = Body != null ? Body : new byte[0];
    }

    public HellcatResponse SetHeader(String Name, String Value) {
        Headers.put(Name, Value);
        return this;
    }

    public HellcatResponse SetCookie(String Name, String Value, Integer MaxAge, String Path, boolean HttpOnly, String SameSite, boolean Secure) {
        List<String> Parts = new ArrayList<>();
        Parts.add(Name + "=" + Value);
        if (MaxAge != null) Parts.add("Max-Age=" + MaxAge);
        if (Path != null) Parts.add("Path=" + Path);
        if (HttpOnly) Parts.add("HttpOnly");
        if (SameSite != null) Parts.add("SameSite=" + SameSite);
        if (Secure) Parts.add("Secure");
        Cookies.add(String.join("; ", Parts));
        return this;
    }

    public HellcatResponse SetCookie(String Name, String Value, boolean HttpOnly) {
        return SetCookie(Name, Value, null, "/", HttpOnly, "Lax", false);
    }

    public HellcatResponse DeleteCookie(String Name, String Path) {
        return SetCookie(Name, "", 0, Path, true, "Lax", false);
    }

    public byte[] Build(boolean KeepAlive) {
        String StatusText = StatusMessages.getOrDefault(StatusCode, "Unknown");
        List<String> Lines = new ArrayList<>();
        Lines.add("HTTP/1.1 " + StatusCode + " " + StatusText);
        Lines.add("Content-Type: " + ContentType);
        Lines.add("Content-Length: " + BodyBytes.length);
        Lines.add("Server: HellcatAPI/1.0");
        Lines.add("X-Powered-By: HellcatAPI");

        for (Map.Entry<String, String> Entry : Headers.entrySet()) {
            Lines.add(Entry.getKey() + ": " + Entry.getValue());
        }
        for (String Cookie : Cookies) {
            Lines.add("Set-Cookie: " + Cookie);
        }
        Lines.add(KeepAlive ? "Connection: keep-alive" : "Connection: close");
        Lines.add("");
        Lines.add("");

        String HeaderSection = String.join("\r\n", Lines);
        byte[] HeaderBytes = HeaderSection.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] Result = new byte[HeaderBytes.length + BodyBytes.length];
        System.arraycopy(HeaderBytes, 0, Result, 0, HeaderBytes.length);
        System.arraycopy(BodyBytes, 0, Result, HeaderBytes.length, BodyBytes.length);
        return Result;
    }

    @Override
    public String toString() {
        return "<HellcatResponse " + StatusCode + " " + ContentType + ">";
    }

    public static HellcatResponse Json(Object Data, int StatusCode) {
        try {
            String JsonBody = new ObjectMapper().writeValueAsString(Data);
            return new HellcatResponse(JsonBody, StatusCode, "application/json; charset=utf-8");
        } catch (Exception E) {
            throw new HellcatResponseException("JSON serialisation failed: " + E.getMessage());
        }
    }

    public static HellcatResponse Html(String HtmlContent, int StatusCode) {
        return new HellcatResponse(HtmlContent, StatusCode, "text/html; charset=utf-8");
    }

    public static HellcatResponse Text(String Content, int StatusCode) {
        return new HellcatResponse(Content, StatusCode, "text/plain; charset=utf-8");
    }

    public static HellcatResponse Redirect(String Location, int StatusCode) {
        if (Location == null || Location.isEmpty()) throw new HellcatResponseException("RedirectResponse requires a non-empty Location");
        HellcatResponse Resp = new HellcatResponse("", StatusCode, "text/plain");
        Resp.SetHeader("Location", Location);
        return Resp;
    }

    public static HellcatResponse FileResponse(String FilePath, String DownloadAs) {
        File F = new File(FilePath);
        if (!F.isFile()) throw new HellcatFileResponseException("File not found: '" + FilePath + "'");

        String MimeType = GuessMimeType(FilePath);
        byte[] Data;
        try {
            Data = Files.readAllBytes(F.toPath());
        } catch (IOException E) {
            throw new HellcatFileResponseException("Could not read file '" + FilePath + "': " + E.getMessage());
        }

        HellcatResponse Resp = new HellcatResponse(Data, 200, MimeType);
        if (DownloadAs != null) {
            Resp.SetHeader("Content-Disposition", "attachment; filename=\"" + DownloadAs + "\"");
        }
        return Resp;
    }

    public static HellcatResponse Error(String Message, int StatusCode, Object Details) {
        Map<String, Object> Payload = new LinkedHashMap<>();
        Payload.put("error", true);
        Payload.put("message", Message);
        Payload.put("status", StatusCode);
        if (Details != null) Payload.put("details", Details);
        return Json(Payload, StatusCode);
    }

    private static String GuessMimeType(String FilePath) {
        String Lower = FilePath.toLowerCase();
        if (Lower.endsWith(".html") || Lower.endsWith(".htm")) return "text/html";
        if (Lower.endsWith(".css")) return "text/css";
        if (Lower.endsWith(".js")) return "application/javascript";
        if (Lower.endsWith(".json")) return "application/json";
        if (Lower.endsWith(".png")) return "image/png";
        if (Lower.endsWith(".jpg") || Lower.endsWith(".jpeg")) return "image/jpeg";
        if (Lower.endsWith(".gif")) return "image/gif";
        if (Lower.endsWith(".svg")) return "image/svg+xml";
        if (Lower.endsWith(".ico")) return "image/x-icon";
        if (Lower.endsWith(".woff")) return "font/woff";
        if (Lower.endsWith(".woff2")) return "font/woff2";
        if (Lower.endsWith(".ttf")) return "font/ttf";
        if (Lower.endsWith(".pdf")) return "application/pdf";
        if (Lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    public static class HellcatResponseException extends RuntimeException {

        public HellcatResponseException(String Message) {
            super(Message);
        }
    }

    public static class HellcatFileResponseException extends HellcatResponseException {

        public HellcatFileResponseException(String Message) {
            super(Message);
        }
    }
}

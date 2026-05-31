package hellcat.core.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HellcatRequest {

    private static final ObjectMapper Json = new ObjectMapper();

    public String Method = "";
    public String Path = "";
    public String HttpVersion = "HTTP/1.1";
    public Map<String, String> Headers = new HashMap<>();
    public Map<String, String> QueryParams = new HashMap<>();
    public Map<String, String> PathParams = new HashMap<>();
    public byte[] Body = new byte[0];
    public Map<String, String> Form = new HashMap<>();
    public Map<String, HellcatUploadedFile> Files = new HashMap<>();
    public Object JsonBody = null;
    public String[] RemoteAddress = { "", "0" };
    public Map<String, String> Cookies = new HashMap<>();

    public String GetContentType() {
        return Headers.getOrDefault("content-type", "");
    }

    public int GetContentLength() {
        try {
            return Integer.parseInt(Headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException E) {
            return 0;
        }
    }

    public boolean IsJson() {
        return GetContentType().contains("application/json");
    }

    public boolean IsForm() {
        return GetContentType().contains("application/x-www-form-urlencoded");
    }

    public boolean IsMultipart() {
        return GetContentType().contains("multipart/form-data");
    }

    public String GetHost() {
        return Headers.getOrDefault("host", "");
    }

    public String GetUserAgent() {
        return Headers.getOrDefault("user-agent", "");
    }

    public String GetAuthorization() {
        return Headers.getOrDefault("authorization", "");
    }

    public String GetRemoteIp() {
        return RemoteAddress[0];
    }

    public String GetHeader(String Name) {
        return GetHeader(Name, null);
    }

    public String GetHeader(String Name, String Default) {
        return Headers.getOrDefault(Name.toLowerCase(), Default);
    }

    public String GetQuery(String Key) {
        return GetQuery(Key, null);
    }

    public String GetQuery(String Key, String Default) {
        return QueryParams.getOrDefault(Key, Default);
    }

    public String GetForm(String Key) {
        return GetForm(Key, null);
    }

    public String GetForm(String Key, String Default) {
        return Form.getOrDefault(Key, Default);
    }

    public Map<String, Object> GetJson() {
        if (JsonBody != null) {
            return CastToMap(JsonBody);
        }
        try {
            JsonBody = Json.readValue(Body, Object.class);
            return CastToMap(JsonBody);
        } catch (Exception E) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> CastToMap(Object Value) {
        if (Value instanceof Map) return (Map<String, Object>) Value;
        return null;
    }

    public Map<String, Object> RequireJson() {
        Map<String, Object> Data = GetJson();
        if (Data == null) throw new HellcatJsonDecodeException("Request body could not be decoded as JSON");
        return Data;
    }

    public HellcatUploadedFile GetFile(String FieldName) {
        return Files.get(FieldName);
    }

    public HellcatUploadedFile RequireFile(String FieldName) {
        HellcatUploadedFile File = Files.get(FieldName);
        if (File == null) throw new HellcatRequestException(
            "Expected file upload for field '" + FieldName + "' but it was not found"
        );
        return File;
    }

    @Override
    public String toString() {
        return "<HellcatRequest " + Method + " " + Path + ">";
    }

    public static class HellcatRequestException extends RuntimeException {

        public HellcatRequestException(String Message) {
            super(Message);
        }
    }

    public static class HellcatRequestParseException extends HellcatRequestException {

        public HellcatRequestParseException(String Message) {
            super(Message);
        }
    }

    public static class HellcatJsonDecodeException extends HellcatRequestException {

        public HellcatJsonDecodeException(String Message) {
            super(Message);
        }
    }

    public static class HellcatMultipartException extends HellcatRequestException {

        public HellcatMultipartException(String Message) {
            super(Message);
        }
    }

    public static HellcatRequest Parse(byte[] RawData, String[] RemoteAddress) {
        return HellcatRequestParser.Parse(RawData, RemoteAddress);
    }
}

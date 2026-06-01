package hellcat.core.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class HellcatStreamResponse {

    public final Supplier<Iterable<byte[]>> GeneratorFunc;
    public final String ContentType;
    public final int StatusCode = 200;
    public final Map<String, String> Headers;

    public HellcatStreamResponse(Supplier<Iterable<byte[]>> GeneratorFunc, String ContentType) {
        this.GeneratorFunc = GeneratorFunc;
        this.ContentType = ContentType;
        this.Headers = new LinkedHashMap<>();
        this.Headers.put("Cache-Control", "no-cache");
        this.Headers.put("X-Accel-Buffering", "no");
    }

    public HellcatStreamResponse SetHeader(String Name, String Value) {
        Headers.put(Name, Value);
        return this;
    }

    public byte[] BuildHeader() {
        String StatusText = HellcatResponse.StatusMessages.getOrDefault(StatusCode, "OK");
        List<String> Lines = new ArrayList<>();
        Lines.add("HTTP/1.1 " + StatusCode + " " + StatusText);
        Lines.add("Content-Type: " + ContentType);
        Lines.add("Transfer-Encoding: chunked");
        Lines.add("Server: HellcatAPI/1.0");
        for (Map.Entry<String, String> Entry : Headers.entrySet()) {
            Lines.add(Entry.getKey() + ": " + Entry.getValue());
        }
        Lines.add("");
        Lines.add("");
        return String.join("\r\n", Lines).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "<HellcatStreamResponse " + ContentType + ">";
    }
}

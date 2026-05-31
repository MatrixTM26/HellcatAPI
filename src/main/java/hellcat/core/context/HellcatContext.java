package hellcat.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HellcatContext {

    public static class HellcatSessionStore {

        private final Map<String, SessionEntry> Store = new ConcurrentHashMap<>();
        private final Object StoreLock = new Object();
        private final int Ttl;

        public HellcatSessionStore() {
            this(3600);
        }

        public HellcatSessionStore(int Ttl) {
            this.Ttl = Ttl;
        }

        public String GenerateSessionId() {
            byte[] Random = new byte[32];
            new SecureRandom().nextBytes(Random);
            return Sha256Hex(new String(Random, StandardCharsets.ISO_8859_1));
        }

        public Map<String, Object> Get(String SessionId) {
            synchronized (StoreLock) {
                SessionEntry Entry = Store.get(SessionId);
                if (Entry == null) return new HashMap<>();
                if (Instant.now().getEpochSecond() > Entry.ExpiresAt) {
                    Store.remove(SessionId);
                    return new HashMap<>();
                }
                Entry.ExpiresAt = Instant.now().getEpochSecond() + Ttl;
                return new HashMap<>(Entry.Data);
            }
        }

        public void Set(String SessionId, Map<String, Object> Data) {
            synchronized (StoreLock) {
                Store.put(SessionId, new SessionEntry(new HashMap<>(Data), Instant.now().getEpochSecond() + Ttl));
            }
        }

        public void Delete(String SessionId) {
            Store.remove(SessionId);
        }

        public void Cleanup() {
            long Now = Instant.now().getEpochSecond();
            Store.entrySet().removeIf(E -> Now > E.getValue().ExpiresAt);
        }

        public int Count() {
            return Store.size();
        }

        private static class SessionEntry {

            Map<String, Object> Data;
            long ExpiresAt;

            SessionEntry(Map<String, Object> Data, long ExpiresAt) {
                this.Data = Data;
                this.ExpiresAt = ExpiresAt;
            }
        }
    }

    public static class HellcatJwtUtil {

        private static final ObjectMapper Json = new ObjectMapper();

        public static String Encode(Map<String, Object> Payload, String SecretKey, int ExpiresIn) {
            try {
                Map<String, String> Header = new LinkedHashMap<>();
                Header.put("alg", "HS256");
                Header.put("typ", "JWT");

                Map<String, Object> CleanPayload = new LinkedHashMap<>(Payload);
                CleanPayload.put("exp", Instant.now().getEpochSecond() + ExpiresIn);
                CleanPayload.put("iat", Instant.now().getEpochSecond());

                String HeaderEncoded = Base64UrlEncode(Json.writeValueAsBytes(Header));
                String PayloadEncoded = Base64UrlEncode(Json.writeValueAsBytes(CleanPayload));
                String SigningInput = HeaderEncoded + "." + PayloadEncoded;
                String Signature = Base64UrlEncode(HmacSha256(SigningInput, SecretKey));

                return SigningInput + "." + Signature;
            } catch (Exception E) {
                throw new RuntimeException("JWT encode failed: " + E.getMessage(), E);
            }
        }

        public static Map<String, Object> Decode(String Token, String SecretKey) {
            try {
                String[] Parts = Token.split("\\.");
                if (Parts.length != 3) return null;

                String SigningInput = Parts[0] + "." + Parts[1];
                String ExpectedSig = Base64UrlEncode(HmacSha256(SigningInput, SecretKey));
                if (!MessageDigestEquals(Parts[2], ExpectedSig)) return null;

                byte[] PayloadBytes = Base64UrlDecode(Parts[1]);
                Map<String, Object> Payload = Json.readValue(PayloadBytes, Map.class);

                Object Exp = Payload.get("exp");
                if (Exp instanceof Number N && Instant.now().getEpochSecond() > N.longValue()) return null;

                return Payload;
            } catch (Exception E) {
                return null;
            }
        }

        private static String Base64UrlEncode(byte[] Data) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Data);
        }

        private static byte[] Base64UrlDecode(String Data) {
            int Padding = 4 - (Data.length() % 4);
            if (Padding != 4) Data += "=".repeat(Padding);
            return Base64.getUrlDecoder().decode(Data);
        }

        private static byte[] HmacSha256(String Data, String Key) throws Exception {
            Mac Hmac = Mac.getInstance("HmacSHA256");
            Hmac.init(new SecretKeySpec(Key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hmac.doFinal(Data.getBytes(StandardCharsets.UTF_8));
        }

        private static boolean MessageDigestEquals(String A, String B) {
            if (A.length() != B.length()) return false;
            int Diff = 0;
            for (int I = 0; I < A.length(); I++) Diff |= A.charAt(I) ^ B.charAt(I);
            return Diff == 0;
        }
    }

    public static class HellcatRequestContext {

        private final ThreadLocal<Map<String, Object>> Data = ThreadLocal.withInitial(HashMap::new);

        public void Set(String Key, Object Value) {
            Data.get().put(Key, Value);
        }

        public Object Get(String Key) {
            return Get(Key, null);
        }

        public Object Get(String Key, Object Default) {
            return Data.get().getOrDefault(Key, Default);
        }

        public boolean Has(String Key) {
            return Data.get().containsKey(Key);
        }

        public void Clear() {
            Data.get().clear();
        }

        public Map<String, Object> All() {
            return new HashMap<>(Data.get());
        }
    }

    private static String Sha256Hex(String Input) {
        try {
            java.security.MessageDigest Digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] Hash = Digest.digest(Input.getBytes(StandardCharsets.UTF_8));
            StringBuilder Hex = new StringBuilder();
            for (byte B : Hash) Hex.append(String.format("%02x", B));
            return Hex.toString();
        } catch (Exception E) {
            throw new RuntimeException("SHA-256 not available", E);
        }
    }
}

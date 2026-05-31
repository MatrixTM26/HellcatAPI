package hellcat.core.middleware;

import hellcat.core.request.HellcatRequest;
import hellcat.core.response.HellcatResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class HellcatMiddlewares {

    public static class HellcatMiddlewareException extends RuntimeException {
        public HellcatMiddlewareException(String Message) { super(Message); }
    }

    public static class HellcatAuthException extends HellcatMiddlewareException {
        public HellcatAuthException(String Message) { super(Message); }
    }

    public static class HellcatCsrfException extends HellcatMiddlewareException {
        public HellcatCsrfException(String Message) { super(Message); }
    }

    public static class HellcatCorsMiddleware implements HellcatMiddleware {
        private final List<String> AllowedOrigins;
        private final List<String> AllowedMethods;
        private final List<String> AllowedHeaders;
        private final boolean      AllowCredentials;
        private final int          MaxAge;

        public HellcatCorsMiddleware(List<String> AllowedOrigins, boolean AllowCredentials) {
            this.AllowedOrigins  = AllowedOrigins != null ? AllowedOrigins : List.of("*");
            this.AllowedMethods  = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
            this.AllowedHeaders  = List.of("Content-Type", "Authorization", "X-Requested-With");
            this.AllowCredentials = AllowCredentials;
            this.MaxAge          = 86400;
        }

        public HellcatCorsMiddleware() {
            this(List.of("*"), false);
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            String Origin = Request.GetHeader("origin", "*");

            if ("OPTIONS".equals(Request.Method)) {
                HellcatResponse Resp = new HellcatResponse("", 204, "text/plain");
                AddCorsHeaders(Resp, Origin);
                return Resp;
            }

            Object Result = Next.apply(Request);
            if (Result instanceof HellcatResponse Resp) {
                AddCorsHeaders(Resp, Origin);
            }
            return Result;
        }

        private void AddCorsHeaders(HellcatResponse Response, String Origin) {
            String AllowedOrigin;
            if (AllowedOrigins.contains("*")) {
                AllowedOrigin = "*";
            } else if (AllowedOrigins.contains(Origin)) {
                AllowedOrigin = Origin;
            } else {
                AllowedOrigin = AllowedOrigins.isEmpty() ? "*" : AllowedOrigins.get(0);
            }
            Response.SetHeader("Access-Control-Allow-Origin",  AllowedOrigin);
            Response.SetHeader("Access-Control-Allow-Methods", String.join(", ", AllowedMethods));
            Response.SetHeader("Access-Control-Allow-Headers", String.join(", ", AllowedHeaders));
            Response.SetHeader("Access-Control-Max-Age",       String.valueOf(MaxAge));
            if (AllowCredentials) Response.SetHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    public static class HellcatRateLimitMiddleware implements HellcatMiddleware {
        private final int                             MaxRequests;
        private final int                             WindowSeconds;
        private final ConcurrentHashMap<String, List<Long>> Counters = new ConcurrentHashMap<>();

        public HellcatRateLimitMiddleware(int MaxRequests, int WindowSeconds) {
            if (MaxRequests < 1)   throw new HellcatMiddlewareException("MaxRequests must be at least 1");
            if (WindowSeconds < 1) throw new HellcatMiddlewareException("WindowSeconds must be at least 1");
            this.MaxRequests   = MaxRequests;
            this.WindowSeconds = WindowSeconds;
        }

        @Override
        public synchronized Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            String Ip  = Request.GetRemoteIp();
            long   Now = System.currentTimeMillis();
            long   WindowStart = Now - WindowSeconds * 1000L;

            Counters.putIfAbsent(Ip, new ArrayList<>());
            List<Long> Timestamps = Counters.get(Ip);
            Timestamps.removeIf(T -> T <= WindowStart);

            if (Timestamps.size() >= MaxRequests) {
                long RetryAfter = (Timestamps.get(0) + WindowSeconds * 1000L - Now) / 1000 + 1;
                HellcatResponse Resp = HellcatResponse.Error("Rate limit exceeded. Try again in " + RetryAfter + " seconds.", 429, null);
                Resp.SetHeader("Retry-After", String.valueOf(RetryAfter));
                return Resp;
            }

            Timestamps.add(Now);
            return Next.apply(Request);
        }
    }

    public static class HellcatBasicAuthMiddleware implements HellcatMiddleware {
        private final String Username;
        private final String PasswordHash;
        private final String Realm;

        public HellcatBasicAuthMiddleware(String Username, String Password, String Realm) {
            if (Username == null || Password == null)
                throw new HellcatAuthException("BasicAuthMiddleware requires both a Username and Password");
            this.Username     = Username;
            this.PasswordHash = Sha256(Password);
            this.Realm        = Realm != null ? Realm : "HellcatAPI";
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            String AuthHeader = Request.GetAuthorization();
            if (AuthHeader == null || !AuthHeader.startsWith("Basic ")) return ChallengeResponse();

            try {
                String Decoded = new String(Base64.getDecoder().decode(AuthHeader.substring(6)), StandardCharsets.UTF_8);
                int Colon = Decoded.indexOf(':');
                if (Colon < 0) return ChallengeResponse();
                String User = Decoded.substring(0, Colon);
                String Pw   = Decoded.substring(Colon + 1);
                if (!User.equals(Username) || !Sha256(Pw).equals(PasswordHash)) return ChallengeResponse();
            } catch (Exception E) {
                return ChallengeResponse();
            }

            return Next.apply(Request);
        }

        private HellcatResponse ChallengeResponse() {
            HellcatResponse Resp = new HellcatResponse("Authentication required", 401, "text/plain; charset=utf-8");
            Resp.SetHeader("WWW-Authenticate", "Basic realm=\"" + Realm + "\"");
            return Resp;
        }
    }

    public static class HellcatBearerAuthMiddleware implements HellcatMiddleware {
        private final Set<String>              ValidTokens;
        private final Function<String, Boolean> ValidatorFunc;

        public HellcatBearerAuthMiddleware(List<String> ValidTokens, Function<String, Boolean> ValidatorFunc) {
            if ((ValidTokens == null || ValidTokens.isEmpty()) && ValidatorFunc == null)
                throw new HellcatAuthException("BearerAuthMiddleware requires either ValidTokens or ValidatorFunc");
            this.ValidTokens   = ValidTokens != null ? new HashSet<>(ValidTokens) : new HashSet<>();
            this.ValidatorFunc = ValidatorFunc;
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            String AuthHeader = Request.GetAuthorization();
            if (AuthHeader == null || !AuthHeader.startsWith("Bearer "))
                return HellcatResponse.Error("Authentication token is required", 401, null);

            String Token = AuthHeader.substring(7).trim();
            if (Token.isEmpty()) return HellcatResponse.Error("Bearer token must not be empty", 401, null);

            try {
                boolean Valid = ValidatorFunc != null ? ValidatorFunc.apply(Token) : ValidTokens.contains(Token);
                if (!Valid) return HellcatResponse.Error("Invalid or expired token", 401, null);
            } catch (Exception E) {
                return HellcatResponse.Error("Token validation failed due to an internal error", 500, null);
            }

            return Next.apply(Request);
        }
    }

    public static class HellcatBodySizeLimitMiddleware implements HellcatMiddleware {
        private final int MaxBytes;

        public HellcatBodySizeLimitMiddleware(int MaxBytes) {
            if (MaxBytes < 1) throw new HellcatMiddlewareException("MaxBytes must be at least 1");
            this.MaxBytes = MaxBytes;
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            int BodySize = Request.Body.length;
            if (BodySize > MaxBytes) {
                return HellcatResponse.Error("Request body too large: " + BodySize + " bytes received, maximum allowed is " + MaxBytes + " bytes.", 413, null);
            }
            return Next.apply(Request);
        }
    }

    public static class HellcatGzipMiddleware implements HellcatMiddleware {
        private final int MinSizeBytes;

        public HellcatGzipMiddleware(int MinSizeBytes) {
            this.MinSizeBytes = MinSizeBytes;
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            Object Result = Next.apply(Request);
            if (!(Result instanceof HellcatResponse Resp)) return Result;

            String AcceptEncoding = Request.GetHeader("accept-encoding", "");
            if (!AcceptEncoding.contains("gzip")) return Result;
            if (Resp.BodyBytes.length < MinSizeBytes) return Result;

            try {
                ByteArrayOutputStream Buf = new ByteArrayOutputStream();
                try (GZIPOutputStream Gz = new GZIPOutputStream(Buf)) {
                    Gz.write(Resp.BodyBytes);
                }
                Resp.BodyBytes = Buf.toByteArray();
                Resp.SetHeader("Content-Encoding", "gzip");
                Resp.SetHeader("Vary", "Accept-Encoding");
            } catch (IOException E) {
            }
            return Resp;
        }
    }

    public static class HellcatSecurityHeadersMiddleware implements HellcatMiddleware {
        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            Object Result = Next.apply(Request);
            if (Result instanceof HellcatResponse Resp) {
                Resp.SetHeader("X-Content-Type-Options", "nosniff");
                Resp.SetHeader("X-Frame-Options",        "DENY");
                Resp.SetHeader("X-XSS-Protection",       "1; mode=block");
                Resp.SetHeader("Referrer-Policy",         "strict-origin-when-cross-origin");
                Resp.SetHeader("Permissions-Policy",      "geolocation=(), microphone=(), camera=()");
            }
            return Result;
        }
    }

    public static class HellcatCsrfMiddleware implements HellcatMiddleware {
        private static final Set<String> SafeMethods = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
        private final String SecretKey;
        private final String CookieName;
        private final String HeaderName;

        public HellcatCsrfMiddleware(String SecretKey, String CookieName, String HeaderName) {
            if (SecretKey == null || SecretKey.isEmpty())
                throw new HellcatCsrfException("CsrfMiddleware requires a non-empty SecretKey");
            this.SecretKey  = SecretKey;
            this.CookieName = CookieName != null ? CookieName : "hellcat_csrf";
            this.HeaderName = (HeaderName != null ? HeaderName : "X-Csrf-Token").toLowerCase();
        }

        private String GenerateToken() {
            byte[] Random = new byte[32];
            new SecureRandom().nextBytes(Random);
            return Sha256(new String(Random, StandardCharsets.ISO_8859_1) + SecretKey);
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            if (SafeMethods.contains(Request.Method)) {
                Object Result = Next.apply(Request);
                if (Result instanceof HellcatResponse Resp && !Request.Cookies.containsKey(CookieName)) {
                    String Token = GenerateToken();
                    Resp.SetCookie(CookieName, Token, false);
                }
                return Result;
            }

            String CookieToken = Request.Cookies.get(CookieName);
            String HeaderToken = Request.GetHeader(HeaderName);

            if (CookieToken == null) return HellcatResponse.Error("CSRF cookie is missing", 403, null);
            if (HeaderToken == null) return HellcatResponse.Error("CSRF header '" + HeaderName + "' is missing", 403, null);
            if (!CookieToken.equals(HeaderToken)) return HellcatResponse.Error("CSRF token mismatch", 403, null);

            return Next.apply(Request);
        }
    }

    public static class HellcatJsonValidatorMiddleware implements HellcatMiddleware {
        private final List<String>         RequiredFields;
        private final Map<String, Class<?>> Schema;

        public HellcatJsonValidatorMiddleware(List<String> RequiredFields, Map<String, Class<?>> Schema) {
            this.RequiredFields = RequiredFields != null ? RequiredFields : new ArrayList<>();
            this.Schema         = Schema != null ? Schema : new HashMap<>();
        }

        @Override
        public Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next) {
            if (!Request.IsJson()) return HellcatResponse.Error("Content-Type must be 'application/json'", 415, null);

            Map<String, Object> Data = Request.GetJson();
            if (Data == null) return HellcatResponse.Error("Request body is not valid JSON", 400, null);

            for (String Field : RequiredFields) {
                if (!Data.containsKey(Field))
                    return HellcatResponse.Error("Required field '" + Field + "' is missing from the request body", 422, null);
            }

            for (Map.Entry<String, Class<?>> Entry : Schema.entrySet()) {
                String FieldName = Entry.getKey();
                if (Data.containsKey(FieldName) && !Entry.getValue().isInstance(Data.get(FieldName))) {
                    return HellcatResponse.Error("Field '" + FieldName + "' must be of type '" + Entry.getValue().getSimpleName() + "'", 422, null);
                }
            }

            return Next.apply(Request);
        }
    }

    private static String Sha256(String Input) {
        try {
            MessageDigest Digest = MessageDigest.getInstance("SHA-256");
            byte[] Hash = Digest.digest(Input.getBytes(StandardCharsets.UTF_8));
            StringBuilder Hex = new StringBuilder();
            for (byte B : Hash) Hex.append(String.format("%02x", B));
            return Hex.toString();
        } catch (NoSuchAlgorithmException E) {
            throw new RuntimeException("SHA-256 not available", E);
        }
    }
}

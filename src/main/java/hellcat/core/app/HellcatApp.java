package hellcat.core.app;

import hellcat.core.context.HellcatContext;
import hellcat.core.lib.ServerConfig;
import hellcat.core.middleware.HellcatMiddleware;
import hellcat.core.middleware.HellcatMiddlewares;
import hellcat.core.request.HellcatRequest;
import hellcat.core.response.HellcatResponse;
import hellcat.core.response.HellcatStreamResponse;
import hellcat.core.router.HellcatRoute;
import hellcat.core.router.HellcatRouter;
import hellcat.core.server.HellcatServer;
import hellcat.core.server.HellcatServerLogger;
import hellcat.core.template.HellcatTemplateEngine;
import java.io.File;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class HellcatApp {

    private final HellcatRouter Router;
    private final HellcatContext.HellcatSessionStore Sessions;
    private final HellcatContext.HellcatRequestContext Context;
    private final HellcatServerLogger AppLogger;
    private final ServerConfig Config;
    private final String SecretKey;

    private HellcatTemplateEngine Templates;
    private String ResolvedTemplateDir;
    private String ResolvedStaticDir;
    private HellcatServer Server;

    public HellcatApp() {
        this(null);
    }

    public HellcatApp(String SecretKey) {
        this.Config = ServerConfig.Load();
        this.SecretKey = SecretKey != null ? SecretKey : "hellcat-secret-change-this-in-production";
        this.Router = new HellcatRouter();
        this.Sessions = new HellcatContext.HellcatSessionStore();
        this.Context = new HellcatContext.HellcatRequestContext();
        this.AppLogger = new HellcatServerLogger(Config.IsDebug());

        SetupTemplateEngine(Config.GetTemplateDir());
        SetupStaticServing(Config.GetStaticDir(), Config.GetStaticUrl());
    }

    private void SetupTemplateEngine(String TemplateDir) {
        if (TemplateDir == null) {
            Templates = null;
            return;
        }
        File Dir = new File(TemplateDir);
        if (!Dir.isDirectory()) Dir = new File(System.getProperty("user.dir"), TemplateDir);
        if (Dir.isDirectory()) {
            ResolvedTemplateDir = Dir.getAbsolutePath();
            Templates = new HellcatTemplateEngine(ResolvedTemplateDir);
        } else {
            AppLogger.WARN("TemplateDir '%s' not found", TemplateDir);
        }
    }

    private void SetupStaticServing(String StaticDir, String StaticUrl) {
        if (StaticDir == null) return;
        File Dir = new File(StaticDir);
        if (!Dir.isDirectory()) Dir = new File(System.getProperty("user.dir"), StaticDir);
        if (Dir.isDirectory()) {
            ResolvedStaticDir = Dir.getAbsolutePath();
            Router.MountStatic(StaticUrl, ResolvedStaticDir);
        } else {
            AppLogger.WARN("StaticDir '%s' not found", StaticDir);
        }
    }

    public void Get(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Get(Path, Handler);
    }

    public void Get(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        Router.Get(Path, Handler, Middlewares);
    }

    public void Post(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Post(Path, Handler);
    }

    public void Post(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        Router.Post(Path, Handler, Middlewares);
    }

    public void Put(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Put(Path, Handler);
    }

    public void Put(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        Router.Put(Path, Handler, Middlewares);
    }

    public void Delete(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Delete(Path, Handler);
    }

    public void Patch(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Patch(Path, Handler);
    }

    public void Head(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Head(Path, Handler);
    }

    public void Options(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Options(Path, Handler);
    }

    public void Trace(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Trace(Path, Handler);
    }

    public void Any(String Path, Function<HellcatRequest, Object> Handler) {
        Router.Any(Path, Handler);
    }

    public void Route(String Path, List<String> Methods, Function<HellcatRequest, Object> Handler) {
        Router.Route(Path, Methods, Handler);
    }

    public void ErrorHandler(int StatusCode, BiFunction<HellcatRequest, Exception, Object> Handler) {
        Router.SetErrorHandler(StatusCode, Handler);
    }

    public void UseMiddleware(HellcatMiddleware Middleware) {
        Router.AddMiddleware(Middleware);
    }

    public void UseCors(List<String> AllowedOrigins, boolean AllowCredentials) {
        UseMiddleware(new HellcatMiddlewares.HellcatCorsMiddleware(AllowedOrigins, AllowCredentials));
    }

    public void UseCors() {
        UseMiddleware(new HellcatMiddlewares.HellcatCorsMiddleware());
    }

    public void UseRateLimit(int MaxRequests, int WindowSeconds) {
        UseMiddleware(new HellcatMiddlewares.HellcatRateLimitMiddleware(MaxRequests, WindowSeconds));
    }

    public void UseSecurityHeaders() {
        UseMiddleware(new HellcatMiddlewares.HellcatSecurityHeadersMiddleware());
    }

    public void UseGzip(int MinSizeBytes) {
        UseMiddleware(new HellcatMiddlewares.HellcatGzipMiddleware(MinSizeBytes));
    }

    public void UseBodySizeLimit(int MaxBytes) {
        UseMiddleware(new HellcatMiddlewares.HellcatBodySizeLimitMiddleware(MaxBytes));
    }

    public void Include(HellcatRouter SubRouter) {
        Router.Include(SubRouter);
    }

    public HellcatResponse Json(Object Data) {
        return Json(Data, 200);
    }

    public HellcatResponse Json(Object Data, int StatusCode) {
        return HellcatResponse.Json(Data, StatusCode);
    }

    public HellcatResponse Html(String Content) {
        return Html(Content, 200);
    }

    public HellcatResponse Html(String Content, int StatusCode) {
        return HellcatResponse.Html(Content, StatusCode);
    }

    public HellcatResponse Text(String Content, int StatusCode) {
        return HellcatResponse.Text(Content, StatusCode);
    }

    public HellcatResponse Redirect(String Location) {
        return Redirect(Location, 302);
    }

    public HellcatResponse Redirect(String Location, int StatusCode) {
        return HellcatResponse.Redirect(Location, StatusCode);
    }

    public HellcatResponse File(String FilePath, String DownloadAs) {
        File F = new File(FilePath);
        if (!F.isAbsolute()) {
            if (ResolvedStaticDir != null) {
                File Candidate = new File(ResolvedStaticDir, FilePath);
                if (Candidate.isFile()) F = Candidate;
            }
            if (!F.isFile()) F = new File(System.getProperty("user.dir"), FilePath);
        }
        if (!F.isFile()) return HellcatResponse.Error("File not found: '" + FilePath + "'", 404, null);
        return HellcatResponse.FileResponse(F.getAbsolutePath(), DownloadAs);
    }

    public HellcatResponse Error(String Message, int StatusCode, Object Details) {
        return HellcatResponse.Error(Message, StatusCode, Details);
    }

    public HellcatResponse Error(String Message, int StatusCode) {
        return HellcatResponse.Error(Message, StatusCode, null);
    }

    public HellcatStreamResponse Stream(Supplier<Iterable<byte[]>> GeneratorFunc, String ContentType) {
        return new HellcatStreamResponse(GeneratorFunc, ContentType);
    }

    public HellcatResponse Render(String TemplateName, Map<String, Object> Ctx) {
        if (Templates == null) {
            return HellcatResponse.Error("Template engine is not active. TemplateDir was not found at startup.", 500, null);
        }
        try {
            String HtmlContent = Templates.Render(TemplateName, Ctx != null ? Ctx : new HashMap<>());
            return HellcatResponse.Html(HtmlContent, 200);
        } catch (HellcatTemplateEngine.HellcatTemplateNotFoundException E) {
            return HellcatResponse.Error("Template '" + TemplateName + "' not found in '" + ResolvedTemplateDir + "'.", 404, null);
        } catch (HellcatTemplateEngine.HellcatTemplateException E) {
            return Config.IsDebug() ? HellcatResponse.Error("Template render error: " + E.getMessage(), 500, null) : HellcatResponse.Error("Failed to render template.", 500, null);
        }
    }

    public Map<String, Object> GetSession(HellcatRequest Request) {
        String SessionId = Request.Cookies.get("hellcat_session");
        if (SessionId == null) return new HashMap<>();
        return Sessions.Get(SessionId);
    }

    public String SaveSession(HellcatResponse Response, Map<String, Object> SessionData, String SessionId) {
        if (SessionId == null) {
            SessionId = Sessions.GenerateSessionId();
            Response.SetCookie("hellcat_session", SessionId, true);
        }
        Sessions.Set(SessionId, SessionData);
        return SessionId;
    }

    public String CreateJwt(Map<String, Object> Payload, int ExpiresIn) {
        return HellcatContext.HellcatJwtUtil.Encode(Payload, SecretKey, ExpiresIn);
    }

    public Map<String, Object> DecodeJwt(String Token) {
        return HellcatContext.HellcatJwtUtil.Decode(Token, SecretKey);
    }

    public HellcatContext.HellcatSessionStore GetSessions() {
        return Sessions;
    }

    public HellcatContext.HellcatRequestContext GetContext() {
        return Context;
    }

    public List<HellcatRoute> ListRoutes() {
        return Router.ListRoutes();
    }

    public void Run() {
        Run(Config.GetHost(), Config.GetPort(), true);
    }

    public void Run(boolean Blocking) {
        Run(Config.GetHost(), Config.GetPort(), Blocking);
    }

    public void Run(String Host, int Port, boolean Blocking) {
        AppLogger.EnableDebug = Config.IsDebug();
        Server = new HellcatServer(Router, Host, Port, 0, AppLogger);
        try {
            Server.Start(Blocking);
        } catch (HellcatServer.HellcatServerException E) {
            AppLogger.ERROR("Server failed to start: %s", E.getMessage());
            System.exit(1);
        }
    }

    public void Stop() {
        if (Server != null) Server.Stop();
    }

    public ServerConfig GetConfig() {
        return Config;
    }

    @Override
    public String toString() {
        return "<HellcatApp routes=" + Router.ListRoutes().size() + " debug=" + Config.IsDebug() + ">";
    }
}

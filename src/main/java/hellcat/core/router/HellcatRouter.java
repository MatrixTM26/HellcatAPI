package hellcat.core.router;

import hellcat.core.middleware.HellcatMiddleware;
import hellcat.core.request.HellcatRequest;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

public class HellcatRouter {
    private final String                         Prefix;
    private final List<HellcatRoute>             Routes;
    private final List<HellcatMiddleware>        GlobalMiddlewares;
    private final Map<Integer, BiFunction<HellcatRequest, Exception, Object>> ErrorHandlers;
    private StaticMount                          StaticMountPoint;

    public HellcatRouter() {
        this("");
    }

    public HellcatRouter(String Prefix) {
        this.Prefix            = Prefix.replaceAll("/+$", "");
        this.Routes            = new ArrayList<>();
        this.GlobalMiddlewares = new ArrayList<>();
        this.ErrorHandlers     = new HashMap<>();
    }

    private String NormalizePath(String Path) {
        String Full = Prefix + "/" + Path.replaceAll("^/+", "");
        Full = Full.replaceAll("/+$", "");
        return Full.isEmpty() ? "/" : Full;
    }

    public void AddRoute(String Path, Function<HellcatRequest, Object> Handler, List<String> Methods, List<HellcatMiddleware> Middlewares) {
        String FullPath = NormalizePath(Path);
        Routes.add(new HellcatRoute(FullPath, Handler, Methods, Middlewares));
    }

    public void Get(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("GET"), null);
    }

    public void Get(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        AddRoute(Path, Handler, List.of("GET"), Middlewares);
    }

    public void Post(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("POST"), null);
    }

    public void Post(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        AddRoute(Path, Handler, List.of("POST"), Middlewares);
    }

    public void Put(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("PUT"), null);
    }

    public void Put(String Path, Function<HellcatRequest, Object> Handler, List<HellcatMiddleware> Middlewares) {
        AddRoute(Path, Handler, List.of("PUT"), Middlewares);
    }

    public void Delete(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("DELETE"), null);
    }

    public void Patch(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("PATCH"), null);
    }

    public void Head(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("HEAD"), null);
    }

    public void Options(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("OPTIONS"), null);
    }

    public void Trace(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("TRACE"), null);
    }

    public void Any(String Path, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, List.of("*"), null);
    }

    public void Route(String Path, List<String> Methods, Function<HellcatRequest, Object> Handler) {
        AddRoute(Path, Handler, Methods, null);
    }

    public void AddMiddleware(HellcatMiddleware Middleware) {
        GlobalMiddlewares.add(Middleware);
    }

    public void SetErrorHandler(int StatusCode, BiFunction<HellcatRequest, Exception, Object> Handler) {
        ErrorHandlers.put(StatusCode, Handler);
    }

    public void MountStatic(String UrlPrefix, String DirectoryPath) {
        StaticMountPoint = new StaticMount(UrlPrefix.replaceAll("/+$", ""), DirectoryPath);
    }

    public void Include(HellcatRouter SubRouter) {
        Routes.addAll(SubRouter.Routes);
        GlobalMiddlewares.addAll(SubRouter.GlobalMiddlewares);
        ErrorHandlers.putAll(SubRouter.ErrorHandlers);
    }

    public HellcatRoute[] Resolve(HellcatRequest Request) {
        for (HellcatRoute Route : Routes) {
            Map<String, String> Params = Route.Match(Request.Path);
            if (Params != null) {
                Request.PathParams = Params;
                return new HellcatRoute[]{Route};
            }
        }
        return null;
    }

    public BiFunction<HellcatRequest, Exception, Object> GetErrorHandler(int StatusCode) {
        return ErrorHandlers.get(StatusCode);
    }

    public StaticMount GetStaticMount() {
        return StaticMountPoint;
    }

    public List<HellcatMiddleware> GetGlobalMiddlewares() {
        return new ArrayList<>(GlobalMiddlewares);
    }

    public List<HellcatRoute> ListRoutes() {
        return new ArrayList<>(Routes);
    }

    @Override
    public String toString() {
        return "<HellcatRouter prefix=" + Prefix + " routes=" + Routes.size() + ">";
    }

    public static class StaticMount {
        public final String UrlPrefix;
        public final String DirectoryPath;

        public StaticMount(String UrlPrefix, String DirectoryPath) {
            this.UrlPrefix     = UrlPrefix;
            this.DirectoryPath = DirectoryPath;
        }
    }
}

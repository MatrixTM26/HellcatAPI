package hellcat.core.router;

import hellcat.core.middleware.HellcatMiddleware;
import hellcat.core.request.HellcatRequest;
import hellcat.core.response.HellcatResponse;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HellcatRoute {

    public final String RoutePattern;
    public final Function<HellcatRequest, Object> Handler;
    public final List<String> Methods;
    public final List<HellcatMiddleware> Middlewares;

    private final Pattern Regex;
    private final List<String> ParamNames;

    public HellcatRoute(String RoutePattern, Function<HellcatRequest, Object> Handler, List<String> Methods, List<HellcatMiddleware> Middlewares) {
        this.RoutePattern = RoutePattern;
        this.Handler = Handler;
        this.Methods = NormalizeMethods(Methods);
        this.Middlewares = Middlewares != null ? Middlewares : new ArrayList<>();

        Object[] Compiled = CompilePattern(RoutePattern);
        this.Regex = (Pattern) Compiled[0];
        this.ParamNames = (List<String>) Compiled[1];
    }

    private static List<String> NormalizeMethods(List<String> Methods) {
        List<String> Result = new ArrayList<>();
        for (String M : Methods) Result.add(M.toUpperCase());
        return Result;
    }

    private static Object[] CompilePattern(String RoutePattern) {
        List<String> ParamNames = new ArrayList<>();
        StringBuilder RegexBuilder = new StringBuilder("^");
        String[] Segments = RoutePattern.split("/");

        for (String Segment : Segments) {
            if (Segment.isEmpty()) continue;
            RegexBuilder.append("/");
            if (Segment.startsWith("<int:") && Segment.endsWith(">")) {
                String ParamName = Segment.substring(5, Segment.length() - 1);
                ParamNames.add(ParamName);
                RegexBuilder.append("(?<").append(ParamName).append(">[0-9]+)");
            } else if (Segment.startsWith("<") && Segment.endsWith(">")) {
                String ParamName = Segment.substring(1, Segment.length() - 1);
                ParamNames.add(ParamName);
                RegexBuilder.append("(?<").append(ParamName).append(">[^/]+)");
            } else {
                RegexBuilder.append(Pattern.quote(Segment));
            }
        }
        RegexBuilder.append("/?$");
        return new Object[] { Pattern.compile(RegexBuilder.toString()), ParamNames };
    }

    public Map<String, String> Match(String Path) {
        Matcher M = Regex.matcher(Path);
        if (!M.matches()) return null;
        Map<String, String> Params = new LinkedHashMap<>();
        for (String Name : ParamNames) {
            Params.put(Name, M.group(Name));
        }
        return Params;
    }

    public boolean AllowsMethod(String Method) {
        return Methods.contains(Method.toUpperCase()) || Methods.contains("*");
    }

    @Override
    public String toString() {
        return "<HellcatRoute " + Methods + " " + RoutePattern + ">";
    }
}

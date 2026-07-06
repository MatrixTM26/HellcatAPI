package hellcat.core.template;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

public class HellcatTemplateEngine {

    public String TemplateDirectory;
    private final Map<String, CacheEntry> Cache = new ConcurrentHashMap<>();

    public HellcatTemplateEngine(String TemplateDirectory) {
        this.TemplateDirectory = TemplateDirectory;
    }

    public void ClearCache() {
        Cache.clear();
    }

    public String Render(String TemplateName, Map<String, Object> Context) {
        String Source = LoadFile(TemplateName);
        return RenderString(Source, Context, TemplateName);
    }

    public String RenderString(String Source, Map<String, Object> Context, String SourceName) {
        if (Context == null) Context = new HashMap<>();
        try {
            Source = ProcessComments(Source);
            Source = ProcessExtends(Source, Context);
            Source = ProcessIncludes(Source, Context);
            Source = ProcessControlFlow(Source, Context);
            Source = ProcessVariables(Source, Context);
            return Source;
        } catch (HellcatTemplateException E) {
            throw E;
        } catch (Exception E) {
            throw new HellcatTemplateRenderException("Render error in '" + SourceName + "': " + E.getMessage());
        }
    }

    private String LoadFile(String TemplateName) {
        if (TemplateDirectory == null || TemplateDirectory.isEmpty()) throw new HellcatTemplateNotFoundException("Cannot load '" + TemplateName + "': no TemplateDirectory configured");

        File FilePath = new File(TemplateDirectory, TemplateName);
        if (!FilePath.isFile()) throw new HellcatTemplateNotFoundException("Template not found: '" + FilePath.getAbsolutePath() + "'");

        long CurrentMtime = FilePath.lastModified();
        CacheEntry Cached = Cache.get(TemplateName);
        if (Cached != null && Cached.Mtime == CurrentMtime) return Cached.Content;

        try {
            String Content = new String(Files.readAllBytes(FilePath.toPath()), StandardCharsets.UTF_8);
            Cache.put(TemplateName, new CacheEntry(Content, CurrentMtime));
            return Content;
        } catch (IOException E) {
            throw new HellcatTemplateException("Could not read template file '" + FilePath + "': " + E.getMessage());
        }
    }

    private String ProcessComments(String Source) {
        return Source.replaceAll("(?s)\\{#.*?#\\}", "");
    }

    private String ProcessExtends(String Source, Map<String, Object> Context) {
        Matcher ExtendsMatch = Pattern.compile("\\{%\\s*extends\\s+\"([^\"]+)\"\\s*%\\}").matcher(Source);
        if (!ExtendsMatch.find()) return Source;

        String ParentName = ExtendsMatch.group(1);
        String ParentSource;
        try {
            ParentSource = LoadFile(ParentName);
        } catch (HellcatTemplateNotFoundException E) {
            throw new HellcatTemplateExtendsException("Parent template not found: " + E.getMessage());
        }

        Map<String, String> ChildBlocks = new LinkedHashMap<>();
        Matcher BlockMatcher = Pattern.compile("(?s)\\{%\\s*block\\s+(\\w+)\\s*%\\}(.*?)\\{%\\s*endblock\\s*%\\}").matcher(Source);
        while (BlockMatcher.find()) {
            ChildBlocks.put(BlockMatcher.group(1), BlockMatcher.group(2));
        }

        StringBuffer Result = new StringBuffer();
        Matcher ParentBlocks = Pattern.compile("(?s)\\{%\\s*block\\s+(\\w+)\\s*%\\}(.*?)\\{%\\s*endblock\\s*%\\}").matcher(ParentSource);
        while (ParentBlocks.find()) {
            String BlockName = ParentBlocks.group(1);
            String DefaultContent = ParentBlocks.group(2);
            String Replacement = ChildBlocks.getOrDefault(BlockName, DefaultContent);
            ParentBlocks.appendReplacement(Result, Matcher.quoteReplacement(Replacement));
        }
        ParentBlocks.appendTail(Result);
        return Result.toString();
    }

    private String ProcessIncludes(String Source, Map<String, Object> Context) {
        Pattern P = Pattern.compile("\\{%\\s*include\\s+\"([^\"]+)\"\\s*%\\}");
        Matcher M = P.matcher(Source);
        StringBuffer Result = new StringBuffer();
        while (M.find()) {
            String IncludedName = M.group(1);
            String IncludedSource;
            try {
                IncludedSource = LoadFile(IncludedName);
            } catch (HellcatTemplateNotFoundException E) {
                throw new HellcatTemplateIncludeException("Included template not found: " + E.getMessage());
            }
            String Rendered = RenderString(IncludedSource, Context, IncludedName);
            M.appendReplacement(Result, Matcher.quoteReplacement(Rendered));
        }
        M.appendTail(Result);
        return Result.toString();
    }

    private String ProcessControlFlow(String Source, Map<String, Object> Context) {
        Source = ProcessForLoops(Source, Context);
        Source = ProcessIfBlocks(Source, Context);
        return Source;
    }

    private String ProcessForLoops(String Source, Map<String, Object> Context) {
        Pattern P = Pattern.compile("(?s)\\{%\\s*for\\s+(\\w+)\\s+in\\s+(.+?)\\s*%\\}(.*?)\\{%\\s*endfor\\s*%\\}");
        Matcher M = P.matcher(Source);
        StringBuffer Result = new StringBuffer();
        while (M.find()) {
            String VarName = M.group(1);
            String IterableExpr = M.group(2).trim();
            String Body = M.group(3);
            Object Iterable = SafeEval(IterableExpr, Context);
            StringBuilder Parts = new StringBuilder();
            if (Iterable instanceof Iterable<?> It) {
                for (Object Item : It) {
                    Map<String, Object> LoopCtx = new HashMap<>(Context);
                    LoopCtx.put(VarName, Item);
                    Parts.append(ProcessForLoops(Body, LoopCtx));
                }
            }
            M.appendReplacement(Result, Matcher.quoteReplacement(Parts.toString()));
        }
        M.appendTail(Result);
        return Result.toString();
    }

    private String ProcessIfBlocks(String Source, Map<String, Object> Context) {
        Pattern P = Pattern.compile("(?s)\\{%\\s*if\\s+(.+?)\\s*%\\}(.*?)\\{%\\s*endif\\s*%\\}");
        Matcher M = P.matcher(Source);
        StringBuffer Result = new StringBuffer();
        while (M.find()) {
            String Condition = M.group(1);
            String Body = M.group(2);
            String Expanded;
            if (IsTruthy(SafeEval(Condition, Context))) {
                Pattern ElseP = Pattern.compile("(?s)^(.*?)\\{%\\s*else\\s*%\\}(.*)");
                Matcher ElseM = ElseP.matcher(Body);
                Expanded = ElseM.matches() ? ElseM.group(1) : Body;
            } else {
                Pattern ElseP = Pattern.compile("(?s)^.*?\\{%\\s*else\\s*%\\}(.*)");
                Matcher ElseM = ElseP.matcher(Body);
                Expanded = ElseM.matches() ? ElseM.group(1) : "";
            }
            M.appendReplacement(Result, Matcher.quoteReplacement(ProcessIfBlocks(Expanded, Context)));
        }
        M.appendTail(Result);
        return Result.toString();
    }

    private boolean IsTruthy(Object Value) {
        if (Value == null) return false;
        if (Value instanceof Boolean B) return B;
        if (Value instanceof Number N) return N.doubleValue() != 0;
        if (Value instanceof String S) return !S.isEmpty();
        if (Value instanceof Collection<?> C) return !C.isEmpty();
        if (Value instanceof Map<?, ?> Mv) return !Mv.isEmpty();
        return true;
    }

    private String ProcessVariables(String Source, Map<String, Object> Context) {
        Pattern P = Pattern.compile("\\{\\{(.+?)\\}\\}");
        Matcher M = P.matcher(Source);
        StringBuffer Result = new StringBuffer();
        while (M.find()) {
            String Expression = M.group(1).trim();
            boolean RawMode = Expression.endsWith("| raw");
            if (RawMode) Expression = Expression.substring(0, Expression.length() - 5).trim();
            Object Value = ResolveExpression(Expression, Context);
            String Str = Value != null ? Value.toString() : "";
            String Out = RawMode ? Str : EscapeHtml(Str);
            M.appendReplacement(Result, Matcher.quoteReplacement(Out));
        }
        M.appendTail(Result);
        return Result.toString();
    }

    private Object ResolveExpression(String Expression, Map<String, Object> Context) {
        String[] Parts = Expression.split("\\.");
        Object Value = Context.get(Parts[0]);
        if (Value == null) return null;

        for (int I = 1; I < Parts.length; I++) {
            if (Value == null) return null;
            String Part = Parts[I];
            if (Value instanceof Map<?, ?> Mv) {
                Value = Mv.get(Part);
            } else {
                try {
                    var Field = Value.getClass().getDeclaredField(Part);
                    Field.setAccessible(true);
                    Value = Field.get(Value);
                } catch (Exception E) {
                    return null;
                }
            }
        }
        return Value;
    }

    private Object SafeEval(String Expression, Map<String, Object> Context) {
        String Trimmed = Expression.trim();
        if ("true".equalsIgnoreCase(Trimmed)) return true;
        if ("false".equalsIgnoreCase(Trimmed)) return false;
        if ("None".equals(Trimmed) || "null".equals(Trimmed)) return null;
        try {
            return Long.parseLong(Trimmed);
        } catch (NumberFormatException E) {}
        try {
            return Double.parseDouble(Trimmed);
        } catch (NumberFormatException E) {}
        return ResolveExpression(Trimmed, Context);
    }

    private static String EscapeHtml(String Input) {
        return Input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static class CacheEntry {

        final String Content;
        final long Mtime;

        CacheEntry(String Content, long Mtime) {
            this.Content = Content;
            this.Mtime = Mtime;
        }
    }

    public static class HellcatTemplateException extends RuntimeException {

        public HellcatTemplateException(String Message) {
            super(Message);
        }
    }

    public static class HellcatTemplateNotFoundException extends HellcatTemplateException {

        public HellcatTemplateNotFoundException(String Message) {
            super(Message);
        }
    }

    public static class HellcatTemplateRenderException extends HellcatTemplateException {

        public HellcatTemplateRenderException(String Message) {
            super(Message);
        }
    }

    public static class HellcatTemplateIncludeException extends HellcatTemplateException {

        public HellcatTemplateIncludeException(String Message) {
            super(Message);
        }
    }

    public static class HellcatTemplateExtendsException extends HellcatTemplateException {

        public HellcatTemplateExtendsException(String Message) {
            super(Message);
        }
    }
}

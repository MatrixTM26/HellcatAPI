package hellcat.core.server;

import hellcat.core.router.HellcatRoute;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HellcatServerLogger {
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String DIM    = "\u001B[2m";
    private static final String GREEN  = "\u001B[92m";
    private static final String YELLOW = "\u001B[93m";
    private static final String RED    = "\u001B[91m";
    private static final String CYAN   = "\u001B[96m";
    private static final String GRAY   = "\u001B[90m";
    private static final String BLUE   = "\u001B[94m";
    private static final String PURPLE = "\u001B[95m";
    private static final String WHITE  = "\u001B[97m";
    private static final String ORANGE = "\u001B[38;5;214m";

    private static final Map<String, String> MethodColors = new HashMap<>();
    static {
        MethodColors.put("GET",     BLUE);
        MethodColors.put("POST",    GREEN);
        MethodColors.put("PUT",     YELLOW);
        MethodColors.put("DELETE",  RED);
        MethodColors.put("PATCH",   PURPLE);
        MethodColors.put("OPTIONS", CYAN);
        MethodColors.put("HEAD",    GRAY);
        MethodColors.put("TRACE",   "\u001B[38;5;208m");
        MethodColors.put("ANY",     ORANGE);
    }

    private static final DateTimeFormatter TimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

    public boolean EnableDebug;
    public boolean Silent;

    private final Object              PrintLock        = new Object();
    private final AtomicLong          TotalRequests    = new AtomicLong(0);
    private final AtomicLong          LastTickRequests = new AtomicLong(0);
    private final AtomicInteger       ActiveConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, AtomicLong> StatusCounts = new ConcurrentHashMap<>();
    private long                      StartTime;
    private volatile boolean          StatsRunning = false;
    private Thread                    StatsTicker;

    public HellcatServerLogger(boolean EnableDebug) {
        this.EnableDebug = EnableDebug;
        this.Silent      = false;
    }

    public void IncrRequest(int StatusCode) {
        TotalRequests.incrementAndGet();
        StatusCounts.computeIfAbsent(StatusCode, K -> new AtomicLong()).incrementAndGet();
    }

    public void IncrActiveConnections() { ActiveConnections.incrementAndGet(); }
    public void DecrActiveConnections() {
        int V = ActiveConnections.decrementAndGet();
        if (V < 0) ActiveConnections.set(0);
    }

    private String TimeStamp() {
        return LocalTime.now().format(TimeFmt);
    }

    private String Badge(String Level, String BracketColor, String LevelColor) {
        return BracketColor + BOLD + "[" + RESET + LevelColor + BOLD + Level + RESET + BracketColor + BOLD + "]" + RESET;
    }

    private void Write(String Level, String Message, String LevelColor) {
        if (Silent) return;
        String B  = Badge(Level, WHITE, LevelColor);
        String Ts = TimeStamp();
        synchronized (PrintLock) {
            System.out.println(B + " " + GRAY + Ts + RESET + "  " + Message);
            System.out.flush();
        }
    }

    private static String Fmt(String Format, Object[] Args) {
        if (Args == null || Args.length == 0) return Format;
        return String.format(Format, (Object[]) Args);
    }

    public void DEBUG(String Format, Object... Args) {
        if (!EnableDebug) return;
        Write("DEBUG", GRAY + Fmt(Format, Args) + RESET, YELLOW);
    }

    public void INFO(String Format, Object... Args) {
        Write("INFO", WHITE + Fmt(Format, Args) + RESET, GREEN);
    }

    public void WARN(String Format, Object... Args) {
        Write("WARN", YELLOW + Fmt(Format, Args) + RESET, YELLOW);
    }

    public void ERROR(String Format, Object... Args) {
        Write("ERROR", RED + Fmt(Format, Args) + RESET, RED);
    }

    public void LogRequest(String RemoteAddr, String Method, String Path, int StatusCode, double DurationMs) {
        if (Silent) return;
        String StatusColor = StatusCode < 300 ? GREEN : StatusCode < 400 ? CYAN : StatusCode < 500 ? YELLOW : RED;
        String DurColor    = DurationMs < 100 ? GREEN : DurationMs < 500 ? YELLOW : RED;
        String MethodColor = MethodColors.getOrDefault(Method.toUpperCase(), CYAN);
        String B           = Badge("REQ", WHITE, BLUE);
        String Ts          = TimeStamp();
        String Line = B + " " + GRAY + Ts + RESET
            + " " + GRAY + RemoteAddr + RESET
            + " " + MethodColor + BOLD + Method + RESET
            + " " + StatusColor + BOLD + StatusCode + RESET
            + "  " + WHITE + Path + RESET
            + " " + DurColor + String.format("%.1f", DurationMs) + "ms" + RESET;
        synchronized (PrintLock) {
            System.out.println(Line);
            System.out.flush();
        }
        IncrRequest(StatusCode);
    }

    public void StartStatsTicker() {
        if (Silent) return;
        StartTime    = System.currentTimeMillis();
        StatsRunning = true;
        StatsTicker  = new Thread(this::TickerLoop, "HellcatStatsTicker");
        StatsTicker.setDaemon(true);
        StatsTicker.start();
    }

    public void StopStatsTicker() {
        StatsRunning = false;
    }

    private void TickerLoop() {
        while (StatsRunning) {
            try { Thread.sleep(1000); } catch (InterruptedException E) { break; }
            if (!StatsRunning) break;

            long   Total    = TotalRequests.get();
            long   Rps      = Total - LastTickRequests.getAndSet(Total);
            int    Active   = ActiveConnections.get();
            long   UptimeSec = (System.currentTimeMillis() - StartTime) / 1000;
            String Uptime   = FormatUptime(UptimeSec);
            String Ts       = TimeStamp();

            String RpsColor    = Rps < 50 ? GREEN : Rps < 200 ? YELLOW : RED;
            String ActiveColor = Active > 0 ? CYAN : GRAY;

            List<String> StatusParts = new ArrayList<>();
            for (Map.Entry<Integer, AtomicLong> Entry : new TreeMap<>(StatusCounts).entrySet()) {
                int  Code = Entry.getKey();
                long Cnt  = Entry.getValue().get();
                if (Cnt > 0) {
                    String C = Code < 300 ? GREEN : Code < 400 ? CYAN : Code < 500 ? YELLOW : RED;
                    StatusParts.add(C + BOLD + Code + RESET + GRAY + ":" + RESET + WHITE + Cnt + RESET);
                }
            }
            String StatusStr = StatusParts.isEmpty() ? GRAY + "no requests" + RESET : String.join("  ", StatusParts);

            String Line = WHITE + BOLD + "[" + RESET + CYAN + BOLD + "STATS" + RESET + WHITE + BOLD + "]" + RESET
                + " " + GRAY + Ts + RESET
                + "  up " + WHITE + BOLD + Uptime + RESET
                + "  req/s " + RpsColor + BOLD + Rps + RESET
                + "  total " + WHITE + BOLD + Total + RESET
                + "  active " + ActiveColor + BOLD + Active + RESET
                + "  " + StatusStr;

            synchronized (PrintLock) {
                System.out.println(Line);
                System.out.flush();
            }
        }
    }

    private String FormatUptime(long Seconds) {
        if (Seconds < 60)   return Seconds + "s";
        if (Seconds < 3600) return (Seconds / 60) + "m " + (Seconds % 60) + "s";
        return (Seconds / 3600) + "h " + ((Seconds % 3600) / 60) + "m " + (Seconds % 60) + "s";
    }

    public void Banner(String Protocol, String Host, int Port, int Workers, int RouteCount, boolean DebugMode, List<HellcatRoute> Routes) {
        int    W     = TerminalWidth();
        String Thick = CYAN + "═".repeat(W) + RESET;
        String Thin  = GRAY + "─".repeat(W) + RESET;

        synchronized (PrintLock) {
            System.out.println();
            System.out.println(Thick);
            System.out.println(CYAN + BOLD + "HellcatAPI  " + RESET + GRAY + "v1.0.0" + RESET);
            System.out.println(Thin);
            PrintRow("Host",       Protocol + "://" + Host + ":" + Port, GREEN);
            PrintRow("Workers",    String.valueOf(Workers), WHITE);
            PrintRow("Routes",     String.valueOf(RouteCount), WHITE);
            PrintRow("Debug Mode", DebugMode ? "on" : "off", DebugMode ? YELLOW : GRAY);

            if (Routes != null && !Routes.isEmpty()) {
                int ColM = 18, ColT = W - ColM - 2;
                System.out.println(Thin);
                System.out.println(GRAY + BOLD + PadRight("Method", ColM) + "Path" + RESET);
                System.out.println(GRAY + "─".repeat(W) + RESET);
                for (HellcatRoute Route : Routes) {
                    String M  = Route.Methods.contains("*") ? "ANY" : String.join(" | ", Route.Methods);
                    String Mc = MethodColors.getOrDefault(Route.Methods.get(0).toUpperCase(), PURPLE);
                    String P  = Route.RoutePattern.length() <= ColT ? Route.RoutePattern
                                : Route.RoutePattern.substring(0, ColT - 1) + "…";
                    System.out.println(Mc + BOLD + PadRight(M, ColM) + RESET + WHITE + P + RESET);
                }
            }

            System.out.println(Thick);
            System.out.println();
            System.out.flush();
        }
    }

    private void PrintRow(String Label, String Value, String Vc) {
        System.out.println(GRAY + PadRight(Label, 18) + RESET + Vc + BOLD + Value + RESET);
        System.out.flush();
    }

    private static String PadRight(String S, int Width) {
        if (S.length() >= Width) return S;
        return S + " ".repeat(Width - S.length());
    }

    private int TerminalWidth() {
        try {
            String Env = System.getenv("COLUMNS");
            if (Env != null) return Math.max(40, Math.min(Integer.parseInt(Env.trim()), 200));
        } catch (Exception E) {}
        return 80;
    }

    public void Shutdown() {
        INFO("Server stopped.");
    }
}

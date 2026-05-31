package hellcat.core.lib;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ServerConfig {
    private static final String DefaultHost        = "0.0.0.0";
    private static final int    DefaultPort        = 9926;
    private static final boolean DefaultDebug      = false;
    private static final String DefaultTemplateDir = "templates";
    private static final String DefaultStaticDir   = "static";
    private static final String DefaultStaticUrl   = "/static";

    private final String  Host;
    private final int     Port;
    private final boolean Debug;
    private final String  TemplateDir;
    private final String  StaticDir;
    private final String  StaticUrl;

    private ServerConfig(String Host, int Port, boolean Debug, String TemplateDir, String StaticDir, String StaticUrl) {
        this.Host        = Host;
        this.Port        = Port;
        this.Debug       = Debug;
        this.TemplateDir = TemplateDir;
        this.StaticDir   = StaticDir;
        this.StaticUrl   = StaticUrl;
    }

    public static ServerConfig Load() {
        return Load("server.properties");
    }

    public static ServerConfig Load(String FilePath) {
        Properties Props = new Properties();
        File ConfigFile = new File(FilePath);

        if (ConfigFile.exists()) {
            try (FileInputStream Stream = new FileInputStream(ConfigFile)) {
                Props.load(Stream);
                Logger.INFO("Loaded config from: %s", FilePath);
            } catch (IOException Err) {
                Logger.WARNING("Failed to read %s: %s — using defaults", FilePath, Err.getMessage());
            }
        } else {
            Logger.WARNING("Config file '%s' not found — using defaults", FilePath);
        }

        String  Host        = Props.getProperty("server.host",        DefaultHost);
        int     Port        = ParseInt(Props.getProperty("server.port"), DefaultPort);
        boolean Debug       = ParseBool(Props.getProperty("server.debug"), DefaultDebug);
        String  TemplateDir = Props.getProperty("server.template.dir", DefaultTemplateDir);
        String  StaticDir   = Props.getProperty("server.static.dir",   DefaultStaticDir);
        String  StaticUrl   = Props.getProperty("server.static.url",   DefaultStaticUrl);

        return new ServerConfig(Host, Port, Debug, TemplateDir, StaticDir, StaticUrl);
    }

    private static int ParseInt(String Value, int Default) {
        if (Value == null || Value.isBlank()) return Default;
        try { return Integer.parseInt(Value.trim()); }
        catch (NumberFormatException E) { return Default; }
    }

    private static boolean ParseBool(String Value, boolean Default) {
        if (Value == null || Value.isBlank()) return Default;
        return Value.trim().equalsIgnoreCase("true");
    }

    public String  GetHost()        { return Host; }
    public int     GetPort()        { return Port; }
    public boolean IsDebug()        { return Debug; }
    public String  GetTemplateDir() { return TemplateDir; }
    public String  GetStaticDir()   { return StaticDir; }
    public String  GetStaticUrl()   { return StaticUrl; }
}

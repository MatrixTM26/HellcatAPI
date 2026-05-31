package hellcat.core.server;

import hellcat.core.router.HellcatRouter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class HellcatServer {
    public static final String DefaultHost = "0.0.0.0";
    public static final int    DefaultPort = 9926;

    private final HellcatRouter       Router;
    private final String              Host;
    private final int                 Port;
    private final int                 Workers;
    private final HellcatServerLogger Logger;

    private final AtomicBoolean       IsRunning    = new AtomicBoolean(false);
    private       ServerSocket        ServerSock;
    private       ExecutorService     ThreadPool;

    public static class HellcatServerException extends RuntimeException {
        public HellcatServerException(String Message) { super(Message); }
        public HellcatServerException(String Message, Throwable Cause) { super(Message, Cause); }
    }

    public HellcatServer(HellcatRouter Router, String Host, int Port, int Workers, HellcatServerLogger Logger) {
        this.Router  = Router;
        this.Host    = Host;
        this.Port    = Port;
        this.Workers = Workers > 0 ? Workers : Runtime.getRuntime().availableProcessors() * 4;
        this.Logger  = Logger;
    }

    public void Start(boolean Blocking) {
        try {
            ServerSock = new ServerSocket(Port, 512, java.net.InetAddress.getByName(Host));
            ServerSock.setReuseAddress(true);
        } catch (IOException E) {
            throw new HellcatServerException("Cannot bind to " + Host + ":" + Port + ": " + E.getMessage(), E);
        }

        ThreadPool = Executors.newFixedThreadPool(Workers);
        IsRunning.set(true);

        List<hellcat.core.router.HellcatRoute> AllRoutes = Router.ListRoutes();
        Logger.Banner("http", Host, Port, Workers, AllRoutes.size(), Logger.EnableDebug, AllRoutes);
        Logger.StartStatsTicker();

        Thread AcceptThread = new Thread(this::AcceptLoop, "HellcatAccept");
        AcceptThread.setDaemon(true);
        AcceptThread.start();

        if (Blocking) {
            try {
                while (IsRunning.get()) Thread.sleep(500);
            } catch (InterruptedException E) {
                Logger.INFO("Stopping server...");
                Stop();
            }
        }
    }

    private void AcceptLoop() {
        while (IsRunning.get()) {
            try {
                Socket Client = ServerSock.accept();
                String Ip     = Client.getInetAddress().getHostAddress();
                String Port   = String.valueOf(Client.getPort());
                ThreadPool.submit(new HellcatConnectionHandler(Client, new String[]{Ip, Port}, Router, Logger));
            } catch (IOException E) {
                if (IsRunning.get()) Logger.ERROR("Accept loop error: %s", E.getMessage());
            }
        }
    }

    public void Stop() {
        IsRunning.set(false);
        Logger.StopStatsTicker();
        try { if (ServerSock != null) ServerSock.close(); } catch (IOException E) {}
        if (ThreadPool != null) ThreadPool.shutdownNow();
        Logger.Shutdown();
    }

    public boolean IsRunning() {
        return IsRunning.get();
    }

    @Override
    public String toString() {
        return "<HellcatServer " + Host + ":" + Port + " workers=" + Workers + " running=" + IsRunning.get() + ">";
    }
}

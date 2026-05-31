package hellcat.core.server;

import hellcat.core.middleware.HellcatMiddleware;
import hellcat.core.request.HellcatRequest;
import hellcat.core.request.HellcatRequestParser;
import hellcat.core.response.HellcatResponse;
import hellcat.core.response.HellcatStreamResponse;
import hellcat.core.router.HellcatRoute;
import hellcat.core.router.HellcatRouter;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public class HellcatConnectionHandler implements Runnable {
    private static final int SocketTimeoutMs    = 10_000;
    private static final int KeepAliveTimeoutMs = 5_000;
    private static final int MaxRequestSize     = 64 * 1024 * 1024;
    private static final int ReadChunkSize      = 8192;

    private final Socket              ClientSocket;
    private final String[]            RemoteAddress;
    private final HellcatRouter       Router;
    private final HellcatServerLogger Logger;

    public HellcatConnectionHandler(Socket ClientSocket, String[] RemoteAddress, HellcatRouter Router, HellcatServerLogger Logger) {
        this.ClientSocket  = ClientSocket;
        this.RemoteAddress = RemoteAddress;
        this.Router        = Router;
        this.Logger        = Logger;
    }

    @Override
    public void run() {
        Logger.IncrActiveConnections();
        try {
            ClientSocket.setSoTimeout(KeepAliveTimeoutMs);
            InputStream In = ClientSocket.getInputStream();

            while (true) {
                byte[] RawData = ReadRequest(In);
                if (RawData == null || RawData.length == 0) break;

                if (!IsHttpRequest(RawData)) {
                    Logger.DEBUG("Non-HTTP data rejected: %s", RemoteAddress[0]);
                    break;
                }

                ClientSocket.setSoTimeout(SocketTimeoutMs);

                HellcatRequest Request;
                try {
                    Request = HellcatRequestParser.Parse(RawData, RemoteAddress);
                } catch (Exception ParseErr) {
                    Logger.WARN("Parse error from %s: %s", RemoteAddress[0], ParseErr.getClass().getSimpleName() + (ParseErr.getMessage() != null ? ": " + ParseErr.getMessage() : ""));
                    break;
                }

                long StartTime = System.currentTimeMillis();
                Object Result  = Dispatch(Request);
                double Duration = System.currentTimeMillis() - StartTime;

                String ConnHeader = Request.Headers.getOrDefault("connection", "").toLowerCase();
                boolean ShouldClose = "close".equals(ConnHeader) || "HTTP/1.0".equals(Request.HttpVersion);

                SendResult(Result, !ShouldClose);

                int StatusCode = 200;
                if (Result instanceof HellcatResponse R) StatusCode = R.StatusCode;
                Logger.LogRequest(RemoteAddress[0], Request.Method, Request.Path, StatusCode, Duration);

                if (ShouldClose) break;
                ClientSocket.setSoTimeout(KeepAliveTimeoutMs);
            }

        } catch (SocketTimeoutException E) {
        } catch (IOException E) {
            if (!E.getMessage().contains("Connection reset") && !E.getMessage().contains("Broken pipe")) {
                Logger.DEBUG("Connection IO error from %s: %s", RemoteAddress[0], E.getClass().getSimpleName() + (E.getMessage() != null ? ": " + E.getMessage() : ""));
            }
        } catch (Exception E) {
            Logger.ERROR("Unhandled error from %s: %s", RemoteAddress[0], E.getClass().getSimpleName() + (E.getMessage() != null ? ": " + E.getMessage() : ""));
        } finally {
            Logger.DecrActiveConnections();
            try { ClientSocket.close(); } catch (IOException E) {}
        }
    }

    private byte[] ReadRequest(InputStream In) throws IOException {
        ByteArrayOutputStream Buffer = new ByteArrayOutputStream();
        byte[] Chunk   = new byte[ReadChunkSize];
        boolean HeaderDone  = false;
        int ContentLength   = 0;
        int HeaderEndOffset = 0;

        while (true) {
            int BytesRead;
            try { BytesRead = In.read(Chunk); }
            catch (SocketTimeoutException E) { break; }
            if (BytesRead == -1) break;

            Buffer.write(Chunk, 0, BytesRead);
            byte[] Current = Buffer.toByteArray();

            if (!HeaderDone) {
                int SepIdx = IndexOfDoubleCrlf(Current);
                if (SepIdx >= 0) {
                    HeaderDone      = true;
                    HeaderEndOffset = SepIdx + 4;
                    String HeaderSection = new String(Current, 0, SepIdx, java.nio.charset.StandardCharsets.UTF_8);
                    for (String Line : HeaderSection.split("\r\n")) {
                        if (Line.toLowerCase().startsWith("content-length:")) {
                            try { ContentLength = Integer.parseInt(Line.split(":", 2)[1].trim()); }
                            catch (NumberFormatException E) { ContentLength = 0; }
                            break;
                        }
                    }
                }
            }

            if (HeaderDone && (Current.length - HeaderEndOffset) >= ContentLength) break;
            if (Current.length > MaxRequestSize) {
                Logger.WARN("Request too large from %s", RemoteAddress[0]);
                break;
            }
        }

        return Buffer.toByteArray();
    }

    private static int IndexOfDoubleCrlf(byte[] Data) {
        for (int I = 0; I < Data.length - 3; I++) {
            if (Data[I] == '\r' && Data[I+1] == '\n' && Data[I+2] == '\r' && Data[I+3] == '\n') return I;
        }
        return -1;
    }

    private static boolean IsHttpRequest(byte[] Data) {
        if (Data.length < 4) return false;
        byte[] Prefix = new byte[]{Data[0], Data[1], Data[2], Data[3]};
        String P = new String(Prefix, java.nio.charset.StandardCharsets.ISO_8859_1);
        return P.startsWith("GET ") || P.startsWith("POST") || P.startsWith("PUT ")
            || P.startsWith("DELE") || P.startsWith("PATC") || P.startsWith("HEAD")
            || P.startsWith("OPTI") || P.startsWith("TRAC") || P.startsWith("CONN");
    }

    private Object Dispatch(HellcatRequest Request) {
        HellcatRouter.StaticMount SM = Router.GetStaticMount();
        if (SM != null && Request.Path.startsWith(SM.UrlPrefix)) {
            return ServeStatic(Request, SM);
        }

        HellcatRoute[] Resolved = Router.Resolve(Request);
        if (Resolved == null) {
            BiFunction<HellcatRequest, Exception, Object> Handler = Router.GetErrorHandler(404);
            if (Handler != null) return Handler.apply(Request, new RuntimeException("Route not found"));
            return HellcatResponse.Error("Route not found", 404, null);
        }

        HellcatRoute Route = Resolved[0];
        if (!Route.AllowsMethod(Request.Method)) {
            BiFunction<HellcatRequest, Exception, Object> Handler = Router.GetErrorHandler(405);
            if (Handler != null) return Handler.apply(Request, new RuntimeException("Method not allowed"));
            return HellcatResponse.Error("Method '" + Request.Method + "' not allowed", 405, null);
        }

        List<HellcatMiddleware> AllMiddlewares = new ArrayList<>(Router.GetGlobalMiddlewares());
        AllMiddlewares.addAll(Route.Middlewares);

        return RunMiddlewarePipeline(Request, Route.Handler, AllMiddlewares);
    }

    private Object RunMiddlewarePipeline(HellcatRequest Request, Function<HellcatRequest, Object> FinalHandler, List<HellcatMiddleware> Middlewares) {
        try {
            Function<HellcatRequest, Object> Chain = BuildChain(Middlewares, 0, FinalHandler);
            return NormalizeResponse(Chain.apply(Request));
        } catch (Exception Err) {
            Logger.ERROR("Handler error: %s", Err.getClass().getSimpleName() + (Err.getMessage() != null ? ": " + Err.getMessage() : ""));
            BiFunction<HellcatRequest, Exception, Object> ErrorHandler = Router.GetErrorHandler(500);
            if (ErrorHandler != null) return ErrorHandler.apply(Request, Err);
            return HellcatResponse.Error("Internal server error", 500, null);
        }
    }

    private Function<HellcatRequest, Object> BuildChain(List<HellcatMiddleware> Middlewares, int Index, Function<HellcatRequest, Object> FinalHandler) {
        if (Index >= Middlewares.size()) return FinalHandler;
        HellcatMiddleware Current = Middlewares.get(Index);
        Function<HellcatRequest, Object> Next = BuildChain(Middlewares, Index + 1, FinalHandler);
        return Req -> Current.Apply(Req, Next);
    }

    private Object NormalizeResponse(Object Result) {
        if (Result == null) return new HellcatResponse("", 204, "text/plain");
        if (Result instanceof String S) return HellcatResponse.Html(S, 200);
        if (Result instanceof java.util.Map<?,?> M) return HellcatResponse.Json(M, 200);
        return Result;
    }

    private Object ServeStatic(HellcatRequest Request, HellcatRouter.StaticMount SM) {
        String RelPath  = Request.Path.substring(SM.UrlPrefix.length()).replaceAll("^/+", "");
        java.io.File FilePath = new java.io.File(SM.DirectoryPath, RelPath).toPath().normalize().toFile();
        java.io.File RootPath = new java.io.File(SM.DirectoryPath).toPath().normalize().toFile();

        if (!FilePath.getAbsolutePath().startsWith(RootPath.getAbsolutePath())) {
            Logger.WARN("Traversal attempt from %s: %s", RemoteAddress[0], Request.Path);
            return HellcatResponse.Error("Access denied", 403, null);
        }

        if (FilePath.isDirectory()) FilePath = new java.io.File(FilePath, "index.html");
        if (!FilePath.isFile()) return HellcatResponse.Error("Static file not found: " + Request.Path, 404, null);

        try {
            return HellcatResponse.FileResponse(FilePath.getAbsolutePath(), null);
        } catch (Exception E) {
            return HellcatResponse.Error("Cannot read static file '" + FilePath + "': " + E.getMessage(), 500, null);
        }
    }

    private void SendResult(Object Result, boolean KeepAlive) {
        try {
            OutputStream Out = ClientSocket.getOutputStream();
            if (Result instanceof HellcatStreamResponse SR) {
                Out.write(SR.BuildHeader());
                Out.flush();
                for (byte[] Chunk : SR.GeneratorFunc.get()) {
                    if (Chunk == null || Chunk.length == 0) continue;
                    String SizeHex = Integer.toHexString(Chunk.length) + "\r\n";
                    Out.write(SizeHex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Out.write(Chunk);
                    Out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Out.flush();
                }
                Out.write("0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Out.flush();
            } else if (Result instanceof HellcatResponse Resp) {
                Out.write(Resp.Build(KeepAlive));
                Out.flush();
            }
        } catch (IOException E) {
            Logger.DEBUG("Send error to %s: %s", RemoteAddress[0], E.getMessage());
        }
    }
}

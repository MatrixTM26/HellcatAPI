package hellcat;

import hellcat.core.app.HellcatApp;
import hellcat.core.context.HellcatContext;
import hellcat.core.db.HellcatDB;
import hellcat.core.db.HellcatTransactionContext;
import hellcat.core.middleware.HellcatMiddleware;
import hellcat.core.request.HellcatRequest;
import hellcat.core.response.HellcatResponse;
import hellcat.core.router.HellcatRouter;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class TestApi {
    static final HellcatApp App = new HellcatApp("hellcat-super-secret-key-2026");
    static final HellcatContext.HellcatRequestContext RequestContext = App.GetContext();

    static final HellcatDB DB = new HellcatDB("hellcat.db", 10, new LinkedHashMap<>() {{
        put("001_create_users", """
            CREATE TABLE users (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                name    TEXT NOT NULL,
                email   TEXT NOT NULL UNIQUE,
                role    TEXT NOT NULL DEFAULT 'user',
                created TEXT NOT NULL
            )
        """);
        put("002_create_products", """
            CREATE TABLE products (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                name    TEXT NOT NULL,
                price   REAL NOT NULL,
                stock   INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL
            )
        """);
        put("003_create_orders", """
            CREATE TABLE orders (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id    INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                quantity   INTEGER NOT NULL DEFAULT 1,
                total      REAL NOT NULL,
                status     TEXT NOT NULL DEFAULT 'pending',
                created    TEXT NOT NULL,
                FOREIGN KEY(user_id)    REFERENCES users(id),
                FOREIGN KEY(product_id) REFERENCES products(id)
            )
        """);
        put("004_seed_users", """
            INSERT OR IGNORE INTO users (name, email, role, created) VALUES
                ('Tom',     'tom7@hellcat.dev',    'admin', datetime('now')),
                ('Bob',     'bob@hellcat.dev',     'user',  datetime('now')),
                ('Charlie', 'charlie@hellcat.dev', 'user',  datetime('now'))
        """);
        put("005_seed_products", """
            INSERT OR IGNORE INTO products (name, price, stock, created) VALUES
                ('HellcatCore',       49.99,  100, datetime('now')),
                ('HellcatPro',        99.99,   50, datetime('now')),
                ('HellcatEnterprise', 299.99,  10, datetime('now'))
        """);
    }});

    static final List<Map<String, Object>> RequestLog = new CopyOnWriteArrayList<>();

    static final HellcatMiddleware LogRequest = (Req, Next) -> {
        long Start    = System.currentTimeMillis();
        Object Resp   = Next.apply(Req);
        double DurMs  = System.currentTimeMillis() - Start;
        Map<String, Object> Entry = new LinkedHashMap<>();
        Entry.put("Method", Req.Method);
        Entry.put("Path",   Req.Path);
        Entry.put("Ip",     Req.GetRemoteIp());
        Entry.put("Ms",     DurMs);
        Entry.put("Time",   System.currentTimeMillis() / 1000);
        RequestLog.add(Entry);
        if (RequestLog.size() > 100) RequestLog.remove(0);
        return Resp;
    };

    static final HellcatMiddleware RequireJson = (Req, Next) -> {
        if (List.of("POST", "PUT", "PATCH").contains(Req.Method) && !Req.IsJson()) {
            return App.Error("Content-Type must be application/json", 415);
        }
        return Next.apply(Req);
    };

    public static void main(String[] Args) {
        App.UseCors(List.of("*"), false);
        App.UseSecurityHeaders();
        App.UseRateLimit(200, 60);
        App.UseBodySizeLimit(5 * 1024 * 1024);
        App.UseGzip(512);
        App.UseMiddleware(LogRequest);

        App.ErrorHandler(404, (Req, Err) ->
            App.Json(Map.of("Error", true, "Message", "Route not found", "Path", Req.Path), 404)
        );
        App.ErrorHandler(405, (Req, Err) ->
            App.Json(Map.of("Error", true, "Message", "Method not allowed", "Method", Req.Method), 405)
        );

        App.Get("/", Req -> App.Render("index.html", Map.of(
            "Title",   "HellcatAPI",
            "Message", "Server Running!",
            "Server",  App.GetConfig().GetHost() + ":" + App.GetConfig().GetPort(),
            "Version", "1.0.0"
        )));

        App.Get("/ping", Req ->
            App.Json(Map.of("Pong", true, "Mode", "sync", "Ts", System.currentTimeMillis() / 1000))
        );

        App.Get("/status", Req -> {
            boolean DbOk = true;
            int Records  = 0;
            try {
                Records = DB.Table("users").Count() + DB.Table("products").Count() + DB.Table("orders").Count();
            } catch (Exception E) { DbOk = false; }

            return App.Json(Map.of(
                "Status",   "healthy",
                "Version",  "1.0.0",
                "Services", Map.of(
                    "Database", Map.of("Ok", DbOk, "Records", Records),
                    "Cache",    Map.of("Ok", true, "Sessions", App.GetSessions().Count()),
                    "System",   Map.of("Ok", true, "Pid", ProcessHandle.current().pid())
                ),
                "Ts", System.currentTimeMillis() / 1000
            ));
        });

        App.Get("/routes", Req -> {
            List<Map<String, Object>> All = new ArrayList<>();
            for (var R : App.ListRoutes()) {
                All.add(Map.of("Pattern", R.RoutePattern, "Methods", R.Methods));
            }
            return App.Json(Map.of("Total", All.size(), "Routes", All));
        });

        App.Get("/logs", Req -> {
            int Limit = Math.max(1, Math.min(Integer.parseInt(Req.GetQuery("limit", "20")), 100));
            List<Map<String, Object>> Slice = RequestLog.subList(Math.max(0, RequestLog.size() - Limit), RequestLog.size());
            List<Map<String, Object>> Reversed = new ArrayList<>(Slice);
            Collections.reverse(Reversed);
            return App.Json(Map.of("Total", RequestLog.size(), "Limit", Limit, "Logs", Reversed));
        });

        App.Get("/db/stats", Req ->
            App.Json(Map.of("Stats", DB.Stats(), "Tables", DB.Tables()))
        );

        App.Get("/db/schema/<TableName>", Req -> {
            String TableName = Req.PathParams.getOrDefault("TableName", "");
            if (!DB.TableExists(TableName)) return App.Error("Table '" + TableName + "' not found", 404);
            return App.Json(Map.of("Table", TableName, "Schema", DB.Schema(TableName)));
        });

        App.Get("/users", Req -> {
            var Query = DB.Table("users").OrderBy("id");
            if (Req.GetQuery("role") != null) Query = Query.WhereEq("role", Req.GetQuery("role"));
            if (Req.GetQuery("search") != null) Query = Query.WhereLike("name", "%" + Req.GetQuery("search") + "%");
            return App.Json(Query.Paginate(
                Integer.parseInt(Req.GetQuery("page", "1")),
                Integer.parseInt(Req.GetQuery("per", "20"))
            ));
        });

        App.Get("/users/<int:UserId>", Req -> {
            int UserId = Integer.parseInt(Req.PathParams.getOrDefault("UserId", "0"));
            Map<String, Object> User = DB.Table("users").WhereEq("id", UserId).First();
            if (User == null) return App.Error("User " + UserId + " not found", 404);
            return App.Json(Map.of("User", User));
        });

        App.Post("/users", Req -> {
            Map<String, Object> Body = Req.GetJson();
            if (Body == null || !Body.containsKey("Name") || !Body.containsKey("Email")) {
                return App.Error("Fields 'Name' and 'Email' are required", 400,
                    Map.of("Required", List.of("Name", "Email")));
            }
            String Email = (String) Body.get("Email");
            if (DB.Table("users").WhereEq("email", Email).First() != null) {
                return App.Error("Email '" + Email + "' already exists", 409);
            }
            long NewId = DB.InsertRow("users", Map.of(
                "name",    Body.get("Name"),
                "email",   Email,
                "role",    Body.getOrDefault("Role", "user"),
                "created", Now()
            ));
            return App.Json(Map.of("Message", "User created", "User", DB.Table("users").WhereEq("id", NewId).First()), 201);
        }, List.of(RequireJson));

        App.Put("/users/<int:UserId>", Req -> {
            int UserId = Integer.parseInt(Req.PathParams.getOrDefault("UserId", "0"));
            if (DB.Table("users").WhereEq("id", UserId).First() == null) {
                return App.Error("User " + UserId + " not found", 404);
            }
            Map<String, Object> Body = Req.GetJson();
            Map<String, Object> Updates = new LinkedHashMap<>();
            if (Body.get("Name")  != null) Updates.put("name",  Body.get("Name"));
            if (Body.get("Email") != null) Updates.put("email", Body.get("Email"));
            if (Body.get("Role")  != null) Updates.put("role",  Body.get("Role"));
            if (!Updates.isEmpty()) DB.Table("users").WhereEq("id", UserId).Update(Updates);
            return App.Json(Map.of("Message", "User updated", "User", DB.Table("users").WhereEq("id", UserId).First()));
        }, List.of(RequireJson));

        App.Delete("/users/<int:UserId>", Req -> {
            int UserId = Integer.parseInt(Req.PathParams.getOrDefault("UserId", "0"));
            if (DB.Table("users").WhereEq("id", UserId).First() == null) {
                return App.Error("User " + UserId + " not found", 404);
            }
            DB.Table("users").WhereEq("id", UserId).Delete();
            return App.Json(Map.of("Message", "User " + UserId + " deleted"));
        });

        App.Get("/products", Req -> {
            var Query = DB.Table("products").OrderBy("id");
            if (Req.GetQuery("min_price") != null)
                Query = Query.Where("price >= ?", Double.parseDouble(Req.GetQuery("min_price")));
            if (Req.GetQuery("max_price") != null)
                Query = Query.Where("price <= ?", Double.parseDouble(Req.GetQuery("max_price")));
            return App.Json(Query.Paginate(
                Integer.parseInt(Req.GetQuery("page", "1")),
                Integer.parseInt(Req.GetQuery("per", "20"))
            ));
        });

        App.Get("/products/<int:ProductId>", Req -> {
            int ProductId = Integer.parseInt(Req.PathParams.getOrDefault("ProductId", "0"));
            Map<String, Object> Product = DB.Table("products").WhereEq("id", ProductId).First();
            if (Product == null) return App.Error("Product " + ProductId + " not found", 404);
            return App.Json(Map.of("Product", Product));
        });

        App.Post("/orders", Req -> {
            Map<String, Object> Body  = Req.GetJson();
            Object UserId    = Body.get("UserId");
            Object ProductId = Body.get("ProductId");
            int    Qty       = Body.get("Quantity") instanceof Number N ? N.intValue() : 1;

            if (UserId == null || ProductId == null)
                return App.Error("Fields 'UserId' and 'ProductId' are required", 400);

            Map<String, Object> User    = DB.Table("users").WhereEq("id", UserId).First();
            Map<String, Object> Product = DB.Table("products").WhereEq("id", ProductId).First();

            if (User == null)    return App.Error("User " + UserId + " not found", 404);
            if (Product == null) return App.Error("Product " + ProductId + " not found", 404);

            int Stock = ((Number) Product.get("stock")).intValue();
            if (Stock < Qty) {
                return App.Error("Insufficient stock", 409, Map.of("Available", Stock, "Requested", Qty));
            }

            try (HellcatTransactionContext Tx = DB.Transaction()) {
                Tx.Execute("UPDATE products SET stock = stock - ? WHERE id = ?", Qty, ProductId);
                double Total  = ((Number) Product.get("price")).doubleValue() * Qty;
                long OrderId  = Tx.Insert(
                    "INSERT INTO orders (user_id, product_id, quantity, total, status, created) VALUES (?, ?, ?, ?, 'confirmed', ?)",
                    UserId, ProductId, Qty, Math.round(Total * 100.0) / 100.0, Now()
                );
                Tx.Commit();
                return App.Json(Map.of("Message", "Order created", "Order", DB.Table("orders").WhereEq("id", OrderId).First()), 201);
            }
        }, List.of(RequireJson));

        App.Get("/orders", Req -> {
            var Query = DB.Table("orders").OrderBy("id", "DESC");
            if (Req.GetQuery("UserId") != null)
                Query = Query.WhereEq("user_id", Integer.parseInt(Req.GetQuery("UserId")));
            return App.Json(Query.Paginate(
                Integer.parseInt(Req.GetQuery("page", "1")),
                Integer.parseInt(Req.GetQuery("per", "20"))
            ));
        });

        App.Post("/auth/login", Req -> {
            Map<String, Object> Body = Req.GetJson();
            String Email = (String) Body.getOrDefault("Email", "");
            Map<String, Object> User = DB.Table("users").WhereEq("email", Email).First();
            if (User == null) return App.Error("Invalid credentials", 401);

            String Token = App.CreateJwt(Map.of(
                "UserId", User.get("id"),
                "Role",   User.get("role"),
                "Email",  User.get("email")
            ), 3600);
            HellcatResponse Resp = App.Json(Map.of("Message", "Login successful", "Token", Token, "User", User));
            App.SaveSession(Resp, Map.of("UserId", User.get("id"), "Role", User.get("role")), null);
            return Resp;
        }, List.of(RequireJson));

        App.Get("/auth/me", Req -> {
            String AuthHeader = Req.GetAuthorization();
            if (!AuthHeader.startsWith("Bearer ")) return App.Error("Authorization header required", 401);
            Map<String, Object> Payload = App.DecodeJwt(AuthHeader.substring(7));
            if (Payload == null) return App.Error("Invalid or expired token", 401);
            Map<String, Object> User = DB.Table("users").WhereEq("id", Payload.get("UserId")).First();
            if (User == null) return App.Error("User not found", 404);
            return App.Json(Map.of("User", User, "TokenPayload", Payload));
        });

        App.Post("/auth/logout", Req -> {
            Map<String, Object> Session = App.GetSession(Req);
            return App.Json(Map.of("Message", "Logged out"));
        });

        App.Get("/stream", Req -> {
            int Count = Math.max(1, Math.min(Integer.parseInt(Req.GetQuery("count", "5")), 20));
            return App.Stream(() -> {
                List<byte[]> Events = new ArrayList<>();
                for (int I = 0; I < Count; I++) {
                    String Data = "data: {\"Event\":" + (I + 1) + ",\"Ts\":" + (System.currentTimeMillis() / 1000) + ",\"Value\":" + (int)(Math.random() * 100 + 1) + "}\n\n";
                    Events.add(Data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try { Thread.sleep(400); } catch (InterruptedException E) {}
                }
                Events.add("data: {\"Event\":\"done\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return Events;
            }, "text/event-stream");
        });

        App.Route("/multi", List.of("GET", "POST", "PUT"), Req ->
            App.Json(Map.of(
                "Method",      Req.Method,
                "Path",        Req.Path,
                "Query",       Req.QueryParams,
                "HasBody",     Req.Body.length > 0,
                "ContentType", Req.GetContentType(),
                "RemoteIp",    Req.GetRemoteIp()
            ))
        );

        App.Get("/info", Req ->
            App.Json(Map.of(
                "App",      App.toString(),
                "DB",       DB.toString(),
                "Routes",   App.ListRoutes().size(),
                "Sessions", App.GetSessions().Count()
            ))
        );

        HellcatRouter ApiRouter = new HellcatRouter("/api/v1");

        ApiRouter.Get("/health", Req ->
            App.Json(Map.of("Status", "ok", "Version", "v1", "Ts", System.currentTimeMillis() / 1000))
        );

        ApiRouter.Get("/summary", Req ->
            App.Json(Map.of(
                "Users",    DB.Table("users").Count(),
                "Orders",   DB.Table("orders").Count(),
                "Products", DB.Table("products").Count(),
                "LogEntries", RequestLog.size()
            ))
        );

        App.Include(ApiRouter);
        App.Run();
    }

    private static String Now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
    }
}

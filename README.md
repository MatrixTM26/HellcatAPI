# HellcatAPI

A fast serve Raw HTTP server framework for backend developments, strongly typed & build with java.

![HELLCAT API](https://img.shields.io/badge/Hellcat%20API-v2.0-000000?style=for-the-badge&logo=github&logoColor=ffffff&labelColor=000000&color=1be2ff)
![AGPL](https://img.shields.io/badge/AGPL-v3-000000?style=for-the-badge&logo=gnu&logoColor=ffffff&labelColor=000000&color=03001a)
![OpenJDK](https://img.shields.io/badge/OpenJDK-17-000000?style=for-the-badge&logo=openjdk&logoColor=ffee1a&labelColor=000000&color=03001a)
![Maven](https://img.shields.io/badge/Maven-000000?style=for-the-badge&logo=apachemaven&logoColor=ee6a2a&labelColor=000000&color=03001a)
![Networking](https://img.shields.io/badge/Networking-000000?style=for-the-badge&logo=cloudflare&logoColor=26ff7d&labelColor=000000&color=03001a)

---

> [!CAUTION]
> This tool is currently under development, in some release versions, you may encounter functional errors or logic flaws.

---

## Project Structure

```txt
HellcatAPI/
├── pom.xml
├── server.properties          ← server configuration (host, port, debug, dirs)
├── templates/
├── static/
└── src/main/java/hellcat/
    ├── TestApi.java            ← main entry point
    └── core/
        ├── app/
        │   └── HellcatApp.java
        ├── server/
        │   ├── HellcatServer.java
        │   ├── HellcatConnectionHandler.java
        │   └── HellcatServerLogger.java
        ├── router/
        │   ├── HellcatRouter.java
        │   └── HellcatRoute.java
        ├── request/
        │   ├── HellcatRequest.java
        │   ├── HellcatRequestParser.java
        │   └── HellcatUploadedFile.java
        ├── response/
        │   ├── HellcatResponse.java
        │   └── HellcatStreamResponse.java
        ├── middleware/
        │   ├── HellcatMiddleware.java
        │   └── HellcatMiddlewares.java
        ├── template/
        │   └── HellcatTemplateEngine.java
        ├── context/
        │   └── HellcatContext.java
        ├── db/
        │   ├── HellcatDB.java
        │   ├── HellcatQueryBuilder.java
        │   └── HellcatTransactionContext.java
        └── lib/
            ├── Logger.java
            └── ServerConfig.java
```

## Configuration — server.properties

Edit `server.properties` in the project root before running:

```properties
server.host=0.0.0.0
server.port=9926
server.debug=false
server.template.dir=templates
server.static.dir=static
server.static.url=/static
```

| Key                   | Default     | Description                          |
| --------------------- | ----------- | ------------------------------------ |
| `server.host`         | `0.0.0.0`   | Bind address                         |
| `server.port`         | `9926`      | Listen port                          |
| `server.debug`        | `false`     | Enable debug mode (`true` / `false`) |
| `server.template.dir` | `templates` | HTML template directory              |
| `server.static.dir`   | `static`    | Static files directory               |
| `server.static.url`   | `/static`   | URL prefix for static files          |

## Build & Run

Requirements: Java 17+, Maven 3.6+

```bash
mvn clean package -q
java -jar HellcatAPI.jar
```

## Defining Routes

```java
HellcatApp App = new HellcatApp("your-secret-key");

App.Get("/hello", (Req) -> App.Json(Map.of("Message", "Hello World")));

App.Post("/echo", (Req) -> {
  Map<String, Object> Body = Req.GetJson();
  return App.Json(Body);
});

App.Get("/users/<int:Id>", (Req) -> {
  int Id = Integer.parseInt(Req.PathParams.get("Id"));
  return App.Json(Map.of("Id", Id));
});

App.Route("/multi", List.of("GET", "POST"), (Req) ->
  App.Json(Map.of("Method", Req.Method))
);

App.Run();
```

## Middleware

```java
// Built-in middleware
App.UseCors(List.of("*"), false);

App.UseRateLimit(100, 60);

App.UseSecurityHeaders();

App.UseGzip(1024);

App.UseBodySizeLimit(10 * 1024 * 1024);

// Custom middleware (lambda)
App.UseMiddleware((Req, Next) -> {
  long Start = System.currentTimeMillis();
  Object Resp = Next.apply(Req);
  System.out.println(
    "Duration: " + (System.currentTimeMillis() - Start) + "ms"
  );
  return Resp;
});

// Route-level middleware
App.Post("/secure", Handler, List.of(RequireAuthMiddleware));
```

## Templates

Uses a lightweight Jinja2-like engine. Templates go in the `templates/` folder.

```java
App.Get("/", (Req) ->
  App.Render(
    "index.html",
    Map.of("Title", "HellcatAPI", "Message", "Server is running!")
  )
);
```

Supported syntax:

```html
{{ variable }} {{ object.field }} {{ value | raw }} {% if condition %}...{%
endif %} {% for item in items %}{{ item }}{% endfor %} {% extends "base.html"
%}{% block content %}...{% endblock %} {% include "partial.html" %} {# comment
#}
```

## Database

SQLite out of the box (PostgreSQL / MySQL via JDBC driver swap).

```java
HellcatDB DB = new HellcatDB("app.db", 10, migrations);

// Query builder
DB.Table("users").WhereEq("role", "admin").OrderBy("id").All();
DB.Table("users").WhereEq("id", 1).First();
DB.Table("users").Count();
DB.Table("users").WhereLike("name", "%Tom%").Paginate(1, 20);

// Write
DB.InsertRow("users", Map.of("name", "Tom", "email", "tom@example.com"));
DB.Table("users").WhereEq("id", 1).Update(Map.of("name", "Bob"));
DB.Table("users").WhereEq("id", 1).Delete();

// Transaction
try (HellcatTransactionContext Tx = DB.Transaction()) {
    Tx.Execute("UPDATE products SET stock = stock - ? WHERE id = ?", 1, 5);
    long OrderId = Tx.Insert("INSERT INTO orders (...) VALUES (...)", ...);
    Tx.Commit();
}

// Auto migrations (run once on startup)
Map<String, String> Migrations = new LinkedHashMap<>();
Migrations.put("001_create_users", "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
HellcatDB DB = new HellcatDB("app.db", 10, Migrations);
```

## Request Object

```java
Req.Method           // "GET", "POST", etc.
Req.Path             // "/users/42"
Req.PathParams       // {"Id": "42"}
Req.QueryParams      // {"page": "1"}
Req.Headers          // all headers (lowercase keys)
Req.Cookies          // parsed cookies
Req.Body             // raw byte[]
Req.GetJson()        // parsed JSON body as Map
Req.GetQuery("page", "1")   // query param with default
Req.GetHeader("Authorization")
Req.GetRemoteIp()
Req.IsJson()
Req.IsForm()
Req.IsMultipart()
```

## Response Helpers

```java
App.Json(data)
App.Json(data, 201)
App.Html("<h1>Hello</h1>")
App.Text("plain text", 200)
App.Redirect("/login")
App.Redirect("/login", 301)
App.Error("Not found", 404)
App.Error("Validation failed", 422, Map.of("field", "email"))
App.File("report.pdf", "download.pdf")
App.Stream(() -> chunks, "text/event-stream")
App.Render("template.html", context)
```

## JWT & Sessions

```java
// Create JWT
String Token = App.CreateJwt(Map.of("UserId", 1, "Role", "admin"), 3600);

// Decode JWT
Map<String, Object> Payload = App.DecodeJwt(token);

// Session
Map<String, Object> Session = App.GetSession(Req);

App.SaveSession(Response, Map.of("UserId", 1), null);
```

## Sub-Routers

```java
HellcatRouter Api = new HellcatRouter("/api/v1");

Api.Get("/health", (Req) -> App.Json(Map.of("Status", "ok")));

Api.Get("/users", (Req) -> App.Json(DB.Table("users").All()));

App.Include(Api);
```

## Error Handlers

```java
App.ErrorHandler(404, (Req, Err) ->
  App.Json(
    Map.of("Error", true, "Message", "Route not found", "Path", Req.Path),
    404
  )
);

App.ErrorHandler(500, (Req, Err) ->
  App.Json(Map.of("Error", true, "Message", "Internal server error"), 500)
);
```

## Dependencies

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.0</version>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.3.0</version>
</dependency>
```

---

## <img src="https://cdn.simpleicons.org/readme/ff0000" width="18"> Documentation

- **Open:** [Sites](https://matrixtm26.github.io/HellcatAPI)

---

<p align="center">
    &copy;
    Copyright 2023-2026 
    <a href="https://github.com/matrixtm26">@MatrixTM26</a>
    &nbsp;
    &middot;
    &nbsp;
    All right reserved.
    <br>
    Licensed under
    &nbsp;
    <a href="./LICENSE">AGPL-V3</a>
</p>

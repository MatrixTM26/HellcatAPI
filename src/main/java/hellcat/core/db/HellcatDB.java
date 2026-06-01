package hellcat.core.db;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class HellcatDB {

    private final String DSN;
    private final HellcatDBPool Pool;

    public static class HellcatDBException extends RuntimeException {

        public HellcatDBException(String Message) {
            super(Message);
        }

        public HellcatDBException(String Message, Throwable Cause) {
            super(Message, Cause);
        }
    }

    public static class HellcatDBQueryException extends HellcatDBException {

        public HellcatDBQueryException(String Message) {
            super(Message);
        }

        public HellcatDBQueryException(String Message, Throwable Cause) {
            super(Message, Cause);
        }
    }

    public static class HellcatDBMigrationException extends HellcatDBException {

        public HellcatDBMigrationException(String Message) {
            super(Message);
        }

        public HellcatDBMigrationException(String Message, Throwable Cause) {
            super(Message, Cause);
        }
    }

    public HellcatDB(String DSN, int PoolSize, Map<String, String> AutoMigrate) {
        this.DSN = DSN;
        this.Pool = new HellcatDBPool(DSN, PoolSize);
        if (AutoMigrate != null && !AutoMigrate.isEmpty()) {
            Migrate(AutoMigrate);
        }
    }

    public HellcatDB(String DSN) {
        this(DSN, 10, null);
    }

    public List<Map<String, Object>> Query(String SQL, Object... Params) {
        Connection Conn = Pool.Acquire();
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL);
            BindParams(Stmt, Params);
            ResultSet Rs = Stmt.executeQuery();
            return MapResultSet(Rs);
        } catch (SQLException E) {
            throw new HellcatDBQueryException("Query failed: " + E.getMessage(), E);
        } finally {
            Pool.Release(Conn);
        }
    }

    public Map<String, Object> QueryOne(String SQL, Object... Params) {
        List<Map<String, Object>> Rows = Query(SQL, Params);
        return Rows.isEmpty() ? null : Rows.get(0);
    }

    public int Execute(String SQL, Object... Params) {
        Connection Conn = Pool.Acquire();
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL);
            BindParams(Stmt, Params);
            return Stmt.executeUpdate();
        } catch (SQLException E) {
            throw new HellcatDBQueryException("Execute failed: " + E.getMessage(), E);
        } finally {
            Pool.Release(Conn);
        }
    }

    public long Insert(String SQL, Object... Params) {
        Connection Conn = Pool.Acquire();
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS);
            BindParams(Stmt, Params);
            Stmt.executeUpdate();
            ResultSet Keys = Stmt.getGeneratedKeys();
            if (Keys.next()) return Keys.getLong(1);
            return -1;
        } catch (SQLException E) {
            throw new HellcatDBQueryException("Insert failed: " + E.getMessage(), E);
        } finally {
            Pool.Release(Conn);
        }
    }

    public long InsertRow(String Table, Map<String, Object> Data) {
        List<String> Cols = new ArrayList<>(Data.keySet());
        List<Object> Values = new ArrayList<>(Data.values());
        String Cols_ = String.join(", ", Cols);
        String Phs_ = String.join(", ", Collections.nCopies(Cols.size(), "?"));
        return Insert("INSERT INTO " + Table + " (" + Cols_ + ") VALUES (" + Phs_ + ")", Values.toArray());
    }

    public boolean TableExists(String TableName) {
        try {
            Map<String, Object> Result = QueryOne(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                TableName
            );
            return Result != null;
        } catch (Exception E) {
            return false;
        }
    }

    public List<String> Tables() {
        List<Map<String, Object>> Rows = Query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
        List<String> Result = new ArrayList<>();
        for (Map<String, Object> Row : Rows) Result.add((String) Row.get("name"));
        return Result;
    }

    public List<Map<String, Object>> Schema(String TableName) {
        return Query("PRAGMA table_info(" + TableName + ")");
    }

    public HellcatQueryBuilder Table(String TableName) {
        return new HellcatQueryBuilder(this, TableName);
    }

    public HellcatTransactionContext Transaction() {
        Connection Conn = Pool.Acquire();
        try {
            Conn.setAutoCommit(false);
            return new HellcatTransactionContext(Conn, () -> Pool.Release(Conn));
        } catch (SQLException E) {
            Pool.Release(Conn);
            throw new HellcatDBException("Transaction init failed: " + E.getMessage(), E);
        }
    }

    public void Migrate(Map<String, String> Migrations) {
        Execute(
            "CREATE TABLE IF NOT EXISTS _hellcat_migrations (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "name TEXT NOT NULL UNIQUE, " +
            "appliedat TEXT NOT NULL)"
        );

        Set<String> Applied = new HashSet<>();
        List<Map<String, Object>> Rows = Query("SELECT LOWER(name) AS name FROM _hellcat_migrations ORDER BY id");
        for (Map<String, Object> Row : Rows) {
            Object Val = Row.get("name");
            if (Val != null) Applied.add(Val.toString().toLowerCase());
        }

        for (Map.Entry<String, String> Entry : Migrations.entrySet()) {
            String Name = Entry.getKey();
            String SQL = Entry.getValue();
            if (Applied.contains(Name.toLowerCase())) continue;
            try {
                for (String Stmt : SQL.split(";")) {
                    Stmt = Stmt.trim();
                    if (!Stmt.isEmpty()) ExecuteSafe(Stmt);
                }
                Execute(
                    "INSERT OR IGNORE INTO _hellcat_migrations (name, appliedat) VALUES (?, ?)",
                    Name,
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date())
                );
            } catch (HellcatDBMigrationException E) {
                throw E;
            } catch (Exception E) {
                throw new HellcatDBMigrationException("Migration '" + Name + "' failed: " + E.getMessage(), E);
            }
        }
    }

    private void ExecuteSafe(String SQL) {
        String Upper = SQL.trim().toUpperCase();
        boolean IsCreate = Upper.startsWith("CREATE TABLE") && !Upper.contains("IF NOT EXISTS");
        boolean IsIndex = Upper.startsWith("CREATE INDEX") && !Upper.contains("IF NOT EXISTS");
        if (IsCreate) SQL = SQL.trim().replaceFirst("(?i)CREATE TABLE", "CREATE TABLE IF NOT EXISTS");
        if (IsIndex) SQL = SQL.trim().replaceFirst("(?i)CREATE INDEX", "CREATE INDEX IF NOT EXISTS");
        boolean IsInsertIgnore = Upper.startsWith("INSERT OR IGNORE");
        if (!IsInsertIgnore && Upper.startsWith("INSERT ")) {
            SQL = SQL.trim().replaceFirst("(?i)^INSERT ", "INSERT OR IGNORE ");
        }
        Execute(SQL);
    }

    public Map<String, Object> Stats() {
        return Pool.Stats();
    }

    private static void BindParams(PreparedStatement Stmt, Object[] Params) throws SQLException {
        for (int I = 0; I < Params.length; I++) {
            Stmt.setObject(I + 1, Params[I]);
        }
    }

    static List<Map<String, Object>> MapResultSet(ResultSet Rs) throws SQLException {
        ResultSetMetaData Meta = Rs.getMetaData();
        int Cols = Meta.getColumnCount();
        List<Map<String, Object>> Result = new ArrayList<>();
        while (Rs.next()) {
            Map<String, Object> Row = new LinkedHashMap<>();
            for (int I = 1; I <= Cols; I++) {
                Row.put(Meta.getColumnLabel(I), Rs.getObject(I));
            }
            Result.add(Row);
        }
        return Result;
    }

    @Override
    public String toString() {
        return "<HellcatDB dsn=" + DSN + " pool=" + Pool.Stats() + ">";
    }

    private static class HellcatDBPool {

        private final String DSN;
        private final BlockingQueue<Connection> Idle;
        private final int MaxConns;
        private int TotalOpen = 0;
        private int TotalCreated = 0;
        private int TotalErrors = 0;
        private final Object Lock = new Object();

        HellcatDBPool(String DSN, int MaxConns) {
            this.DSN = DSN;
            this.MaxConns = MaxConns;
            this.Idle = new ArrayBlockingQueue<>(MaxConns);

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException E) {}

            int MinIdle = Math.max(1, Math.min(2, MaxConns));
            for (int I = 0; I < MinIdle; I++) {
                try {
                    Idle.add(CreateConnection());
                    TotalOpen++;
                } catch (Exception E) {
                    throw new HellcatDBException("Failed to init DB pool: " + E.getMessage(), E);
                }
            }
        }

        Connection Acquire() {
            synchronized (Lock) {
                Connection Idle_ = this.Idle.poll();
                if (Idle_ != null) {
                    try {
                        if (!Idle_.isValid(1)) {
                            Idle_.close();
                            Idle_ = null;
                        }
                    } catch (SQLException E) {
                        Idle_ = null;
                    }
                    if (Idle_ != null) return Idle_;
                }
                if (TotalOpen < MaxConns) {
                    Connection Conn = CreateConnection();
                    TotalOpen++;
                    return Conn;
                }
            }
            try {
                Connection Conn = Idle.poll(30, TimeUnit.SECONDS);
                if (Conn == null) throw new HellcatDBException(
                    "DB pool exhausted after 30s — all " + MaxConns + " connections in use"
                );
                try {
                    if (!Conn.isValid(1)) {
                        Conn.close();
                        Conn = CreateConnection();
                    }
                } catch (SQLException E) {
                    Conn = CreateConnection();
                }
                return Conn;
            } catch (InterruptedException E) {
                throw new HellcatDBException("DB pool acquire interrupted: " + E.getMessage(), E);
            }
        }

        void Release(Connection Conn) {
            if (Conn == null) return;
            try {
                if (Conn.isClosed()) {
                    synchronized (Lock) {
                        TotalOpen = Math.max(0, TotalOpen - 1);
                    }
                    return;
                }
                if (!Conn.getAutoCommit()) Conn.setAutoCommit(true);
            } catch (SQLException E) {
                try {
                    Conn.close();
                } catch (SQLException Ignore) {}
                synchronized (Lock) {
                    TotalOpen = Math.max(0, TotalOpen - 1);
                }
                return;
            }
            if (!Idle.offer(Conn)) {
                try {
                    Conn.close();
                } catch (SQLException E) {}
                synchronized (Lock) {
                    TotalOpen = Math.max(0, TotalOpen - 1);
                }
            }
        }

        private Connection CreateConnection() {
            try {
                String Url = DSN.startsWith("jdbc:") ? DSN : "jdbc:sqlite:" + DSN;
                Connection C = DriverManager.getConnection(Url);
                if (C instanceof org.sqlite.SQLiteConnection SC) {
                    SC.getDatabase().exec("PRAGMA journal_mode=WAL", false);
                    SC.getDatabase().exec("PRAGMA foreign_keys=ON", false);
                    SC.getDatabase().exec("PRAGMA busy_timeout=10000", false);
                }
                TotalCreated++;
                return C;
            } catch (Exception E) {
                synchronized (Lock) {
                    TotalErrors++;
                }
                throw new HellcatDBException("DB connection failed: " + E.getMessage(), E);
            }
        }

        Map<String, Object> Stats() {
            synchronized (Lock) {
                Map<String, Object> S = new LinkedHashMap<>();
                S.put("Idle", Idle.size());
                S.put("TotalOpen", TotalOpen);
                S.put("MaxConns", MaxConns);
                S.put("TotalCreated", TotalCreated);
                S.put("TotalErrors", TotalErrors);
                return S;
            }
        }
    }
}

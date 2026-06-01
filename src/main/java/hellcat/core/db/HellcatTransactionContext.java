package hellcat.core.db;

import java.sql.*;
import java.util.List;
import java.util.Map;

public class HellcatTransactionContext implements AutoCloseable {

    private final Connection Conn;
    private final Runnable ReleaseConn;
    private boolean Done = false;

    public HellcatTransactionContext(Connection Conn, Runnable ReleaseConn) {
        this.Conn = Conn;
        this.ReleaseConn = ReleaseConn;
    }

    public List<Map<String, Object>> Query(String SQL, Object... Params) {
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL);
            for (int I = 0; I < Params.length; I++) Stmt.setObject(I + 1, Params[I]);
            ResultSet Rs = Stmt.executeQuery();
            return HellcatDB.MapResultSet(Rs);
        } catch (SQLException E) {
            throw new HellcatDB.HellcatDBQueryException("Transaction query failed: " + E.getMessage(), E);
        }
    }

    public int Execute(String SQL, Object... Params) {
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL);
            for (int I = 0; I < Params.length; I++) Stmt.setObject(I + 1, Params[I]);
            return Stmt.executeUpdate();
        } catch (SQLException E) {
            throw new HellcatDB.HellcatDBQueryException("Transaction execute failed: " + E.getMessage(), E);
        }
    }

    public long Insert(String SQL, Object... Params) {
        try {
            PreparedStatement Stmt = Conn.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS);
            for (int I = 0; I < Params.length; I++) Stmt.setObject(I + 1, Params[I]);
            Stmt.executeUpdate();
            ResultSet Keys = Stmt.getGeneratedKeys();
            if (Keys.next()) return Keys.getLong(1);
            return -1;
        } catch (SQLException E) {
            throw new HellcatDB.HellcatDBQueryException("Transaction insert failed: " + E.getMessage(), E);
        }
    }

    public void Commit() {
        if (Done) throw new HellcatDB.HellcatDBException("Transaction already closed");
        try {
            Conn.commit();
            Done = true;
        } catch (SQLException E) {
            throw new HellcatDB.HellcatDBException("Commit failed: " + E.getMessage(), E);
        } finally {
            ReleaseConn.run();
        }
    }

    public void Rollback() {
        if (Done) return;
        try {
            Conn.rollback();
        } catch (SQLException E) {}
        Done = true;
        ReleaseConn.run();
    }

    @Override
    public void close() {
        if (Done) return;
        try {
            Conn.rollback();
        } catch (SQLException E) {}
        Done = true;
        ReleaseConn.run();
    }
}

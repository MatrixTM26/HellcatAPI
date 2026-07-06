package hellcat.core.db;

import java.util.*;

public class HellcatQueryBuilder {

    private final HellcatDB DB;
    private final String TableName;
    private final List<String> Conditions = new ArrayList<>();
    private final List<Object> Params = new ArrayList<>();
    private final List<String> OrderByCols = new ArrayList<>();
    private Integer LimitVal = null;
    private Integer OffsetVal = null;
    private List<String> SelectCols = List.of("*");
    private final List<String> JoinClauses = new ArrayList<>();

    public HellcatQueryBuilder(HellcatDB DB, String TableName) {
        this.DB = DB;
        this.TableName = TableName;
    }

    public HellcatQueryBuilder Select(String... Columns) {
        this.SelectCols = List.of(Columns);
        return this;
    }

    public HellcatQueryBuilder Where(String Condition, Object... CondParams) {
        Conditions.add(Condition);
        Collections.addAll(Params, CondParams);
        return this;
    }

    public HellcatQueryBuilder WhereEq(String Column, Object Value) {
        Conditions.add(Column + " = ?");
        Params.add(Value);
        return this;
    }

    public HellcatQueryBuilder WhereLike(String Column, String Pattern) {
        Conditions.add(Column + " LIKE ?");
        Params.add(Pattern);
        return this;
    }

    public HellcatQueryBuilder WhereIn(String Column, List<?> Values) {
        if (Values == null || Values.isEmpty()) {
            Conditions.add("1 = 0");
            return this;
        }
        String Phs = String.join(", ", Collections.nCopies(Values.size(), "?"));
        Conditions.add(Column + " IN (" + Phs + ")");
        Params.addAll(Values);
        return this;
    }

    public HellcatQueryBuilder OrderBy(String Column) {
        return OrderBy(Column, "ASC");
    }

    public HellcatQueryBuilder OrderBy(String Column, String Direction) {
        String Dir = "DESC".equalsIgnoreCase(Direction) ? "DESC" : "ASC";
        OrderByCols.add(Column + " " + Dir);
        return this;
    }

    public HellcatQueryBuilder Limit(int N) {
        this.LimitVal = N;
        return this;
    }

    public HellcatQueryBuilder Offset(int N) {
        this.OffsetVal = N;
        return this;
    }

    public HellcatQueryBuilder Join(String Table, String On, String JoinType) {
        JoinClauses.add(JoinType + " JOIN " + Table + " ON " + On);
        return this;
    }

    public HellcatQueryBuilder LeftJoin(String Table, String On) {
        return Join(Table, On, "LEFT");
    }

    private String BuildSQL() {
        StringBuilder SQL = new StringBuilder("SELECT ").append(String.join(", ", SelectCols)).append(" FROM ").append(TableName);

        for (String J : JoinClauses) SQL.append(" ").append(J);
        if (!Conditions.isEmpty()) SQL.append(" WHERE ").append(String.join(" AND ", Conditions));
        if (!OrderByCols.isEmpty()) SQL.append(" ORDER BY ").append(String.join(", ", OrderByCols));
        if (LimitVal != null) SQL.append(" LIMIT ").append(LimitVal);
        if (OffsetVal != null) SQL.append(" OFFSET ").append(OffsetVal);
        return SQL.toString();
    }

    public List<Map<String, Object>> All() {
        return DB.Query(BuildSQL(), Params.toArray());
    }

    public Map<String, Object> First() {
        HellcatQueryBuilder Q = new HellcatQueryBuilder(DB, TableName);
        Q.Conditions.addAll(this.Conditions);
        Q.Params.addAll(this.Params);
        Q.OrderByCols.addAll(this.OrderByCols);
        Q.SelectCols = this.SelectCols;
        Q.JoinClauses.addAll(this.JoinClauses);
        Q.LimitVal = 1;
        List<Map<String, Object>> Rows = DB.Query(Q.BuildSQL(), Q.Params.toArray());
        return Rows.isEmpty() ? null : Rows.get(0);
    }

    public int Count() {
        StringBuilder SQL = new StringBuilder("SELECT COUNT(*) AS _count FROM ").append(TableName);
        for (String J : JoinClauses) SQL.append(" ").append(J);
        if (!Conditions.isEmpty()) SQL.append(" WHERE ").append(String.join(" AND ", Conditions));
        Map<String, Object> Row = DB.QueryOne(SQL.toString(), Params.toArray());
        if (Row == null) return 0;
        Object Val = Row.get("_count");
        if (Val instanceof Number N) return N.intValue();
        return 0;
    }

    public int Update(Map<String, Object> Data) {
        if (Data.isEmpty()) return 0;
        List<String> Sets = new ArrayList<>();
        List<Object> Values = new ArrayList<>();
        for (Map.Entry<String, Object> E : Data.entrySet()) {
            Sets.add(E.getKey() + " = ?");
            Values.add(E.getValue());
        }
        Values.addAll(Params);
        StringBuilder SQL = new StringBuilder("UPDATE ").append(TableName).append(" SET ").append(String.join(", ", Sets));
        if (!Conditions.isEmpty()) SQL.append(" WHERE ").append(String.join(" AND ", Conditions));
        return DB.Execute(SQL.toString(), Values.toArray());
    }

    public int Delete() {
        StringBuilder SQL = new StringBuilder("DELETE FROM ").append(TableName);
        if (!Conditions.isEmpty()) SQL.append(" WHERE ").append(String.join(" AND ", Conditions));
        return DB.Execute(SQL.toString(), Params.toArray());
    }

    public Map<String, Object> Paginate(int Page, int PerPage) {
        Page = Math.max(1, Page);
        PerPage = Math.max(1, PerPage);
        int Total = Count();

        HellcatQueryBuilder Q = new HellcatQueryBuilder(DB, TableName);
        Q.Conditions.addAll(this.Conditions);
        Q.Params.addAll(this.Params);
        Q.OrderByCols.addAll(this.OrderByCols);
        Q.SelectCols = this.SelectCols;
        Q.JoinClauses.addAll(this.JoinClauses);
        Q.LimitVal = PerPage;
        Q.OffsetVal = (Page - 1) * PerPage;
        List<Map<String, Object>> Rows = Q.All();

        int TotalPages = Math.max(1, (int) Math.ceil((double) Total / PerPage));
        Map<String, Object> Result = new LinkedHashMap<>();
        Result.put("Data", Rows);
        Result.put("Total", Total);
        Result.put("Page", Page);
        Result.put("PerPage", PerPage);
        Result.put("TotalPages", TotalPages);
        Result.put("HasNext", Page * PerPage < Total);
        Result.put("HasPrev", Page > 1);
        return Result;
    }
}

package com.cendreal.accessory.core.storage.mysql;

import com.cendreal.accessory.core.storage.InventoryBackend;
import com.cendreal.accessory.core.storage.InventoryLease;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

public class SqlManager implements InventoryBackend, InventoryLease {
    private HikariDataSource pool;
    public void init(String host,int port,String database,String user,String password,int max,int min,int lifetime,int timeout,int idle) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://"+host+":"+port+"/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC");
        config.setUsername(user); config.setPassword(password); config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(max); config.setMinimumIdle(min); config.setMaxLifetime(lifetime);
        config.setConnectionTimeout(timeout); config.setIdleTimeout(idle);
        pool = new HikariDataSource(config);
    }
    private boolean hasTable(Connection c,String table) throws SQLException {
        try (var s=c.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            s.setString(1,table); try(var r=s.executeQuery()){return r.next();}
        }
    }
    @Override public void initialize() throws SQLException {
        try(var c=pool.getConnection();var s=c.createStatement()){
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS accessory_inventory (
                  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,inventory_data LONGTEXT NOT NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB
                """);
        }
    }
    @Override public void initializeLeases() throws SQLException {
        try(var c=pool.getConnection();var s=c.createStatement()){
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS accessory_session (player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                  server_id VARCHAR(64) NOT NULL, token CHAR(36) NOT NULL, lease_until DATETIME(3) NOT NULL,
                  INDEX accessory_lease_token(token)) ENGINE=InnoDB
                """);
        }
    }
    @Override public String loadInventory(UUID id) throws SQLException {
        try(var c=pool.getConnection();var s=c.prepareStatement("SELECT inventory_data FROM accessory_inventory WHERE player_uuid=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }
    private static void mutate(Connection c,UUID id,String data,boolean delete) throws SQLException {
        String sql = delete ? "DELETE FROM accessory_inventory WHERE player_uuid=?" : """
                INSERT INTO accessory_inventory(player_uuid,inventory_data) VALUES(?,?)
                ON DUPLICATE KEY UPDATE inventory_data=VALUES(inventory_data),updated_at=CURRENT_TIMESTAMP""";
        try(var s=c.prepareStatement(sql)){
            s.setString(1,id.toString()); if(!delete)s.setString(2,data); s.executeUpdate();
        }
    }
    @Override public void saveInventory(UUID id,String data) throws SQLException { try(var c=pool.getConnection()){mutate(c,id,data,false);} }
    @Override public void deleteInventory(UUID id) throws SQLException { try(var c=pool.getConnection()){mutate(c,id,null,true);} }
    @Override public Set<UUID> players(Consumer<String> skipped) throws SQLException {
        Set<UUID> ids=new TreeSet<>();
        try(var c=pool.getConnection()){
            if(hasTable(c,"accessory_inventory"))try(var s=c.createStatement();var r=s.executeQuery("SELECT player_uuid FROM accessory_inventory")){
                while(r.next())InventoryBackend.addPlayer(ids,r.getString(1),skipped);
            }
        }
        return ids;
    }
    @Override public Set<String> activeLeaseOwners() throws SQLException {
        Set<String> ids=new TreeSet<>();
        try(var c=pool.getConnection()){
            if(hasTable(c,"accessory_session"))try(var s=c.createStatement();var r=s.executeQuery("SELECT DISTINCT server_id FROM accessory_session WHERE lease_until>NOW(3)")){
                while(r.next())ids.add(r.getString(1));
            }
        }
        return ids;
    }
    private interface Transaction<T> { T run(Connection c) throws Exception; }
    private <T> T transaction(Transaction<T> work) throws Exception {
        try(var c=pool.getConnection()){
            c.setAutoCommit(false);
            try { T result=work.run(c);c.commit();return result; }
            catch(Exception e){c.rollback();throw e;}
        }
    }
    @Override public Claim acquire(UUID id,String server,UUID proposed,int seconds) throws Exception {
        return transaction(c -> {
            // Establish the row before locking it, including the simultaneous first login on two servers.
            try(var insert=c.prepareStatement("INSERT INTO accessory_session VALUES(?,?,?,TIMESTAMPADD(SECOND,?,NOW(3))) ON DUPLICATE KEY UPDATE player_uuid=player_uuid")){
                insert.setString(1,id.toString());insert.setString(2,server);insert.setString(3,proposed.toString());insert.setInt(4,seconds);insert.executeUpdate();
            }
            UUID token=proposed;
            try(var s=c.prepareStatement("SELECT server_id,token,lease_until>NOW(3),TIMESTAMPDIFF(MICROSECOND,NOW(3),lease_until) DIV 1000 FROM accessory_session WHERE player_uuid=? FOR UPDATE")){
                s.setString(1,id.toString());try(var row=s.executeQuery()){
                    if(!row.next()) throw new SQLException("Missing session row");
                    if(row.getBoolean(3)){
                        if(!server.equals(row.getString(1))) return new Claim(null,row.getString(1),row.getLong(4));
                        token=UUID.fromString(row.getString(2));
                    }
                }
            }
            try(var s=c.prepareStatement("UPDATE accessory_session SET server_id=?,token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE player_uuid=?")){
                s.setString(1,server);s.setString(2,token.toString());s.setInt(3,seconds);s.setString(4,id.toString());s.executeUpdate();
            }
            return new Claim(token,server);
        });
    }
    @Override public Set<UUID> renew(Map<UUID,UUID> owners,int seconds) throws Exception {
        if(owners.isEmpty())return Set.of();
        String params=String.join(",",Collections.nCopies(owners.size(),"?"));
        return transaction(c -> {
            Set<UUID> retained=new HashSet<>();
            try(var update=c.prepareStatement("UPDATE accessory_session SET lease_until=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE lease_until>NOW(3) AND token IN ("+params+")");
                var select=c.prepareStatement("SELECT player_uuid FROM accessory_session WHERE lease_until>NOW(3) AND token IN ("+params+")")){
                update.setInt(1,seconds);int i=1;
                for(UUID token:owners.values()){select.setString(i,token.toString());update.setString(++i,token.toString());}
                update.executeUpdate();try(var rows=select.executeQuery()){while(rows.next())retained.add(UUID.fromString(rows.getString(1)));}
            }
            return retained;
        });
    }
    @Override public void write(UUID id,UUID token,String data,boolean delete) throws Exception {
        transaction(c -> {
            try(var s=c.prepareStatement("SELECT token,server_id,lease_until>NOW(3) FROM accessory_session WHERE player_uuid=? FOR UPDATE")){
                s.setString(1,id.toString());try(var row=s.executeQuery()){
                    if(!row.next())throw new Lost(null);
                    if(!token.toString().equals(row.getString(1))||!row.getBoolean(3))throw new Lost(row.getString(2));
                }
            }
            mutate(c,id,data,delete);return null;
        });
    }
    @Override public String holder(UUID id) throws SQLException {
        try (var c = pool.getConnection();
             var s = c.prepareStatement("SELECT server_id FROM accessory_session WHERE player_uuid=? AND lease_until>NOW(3)")) {
            s.setString(1, id.toString());
            try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    @Override public void release(UUID id,UUID token) throws SQLException {
        try(var c=pool.getConnection();var s=c.prepareStatement("DELETE FROM accessory_session WHERE player_uuid=? AND token=?")){
            s.setString(1,id.toString());s.setString(2,token.toString());s.executeUpdate();
        }
    }
    public DataSource dataSource(){return pool;}
    public void shutdown(){if(pool!=null)pool.close();}
    @Override public void close(){shutdown();}
}

package com.cendreal.accessory.core.storage.redis;

import redis.clients.jedis.*;
import redis.clients.jedis.util.JedisURIHelper;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import com.cendreal.accessory.core.storage.InventoryBackend;
import com.cendreal.accessory.core.storage.InventoryLease;
import redis.clients.jedis.params.ScanParams;

/** Inventory strings use the MySQL codec. The pool belongs to this storage lifetime. */
public final class RedisManager implements InventoryBackend, InventoryLease {
    private final JedisPool pool;
    private final String keyPrefix;

    public RedisManager(URI uri, String keyPrefix) {
        this.keyPrefix = keyPrefix;
        var config = DefaultJedisClientConfig.builder()
                .user(JedisURIHelper.getUser(uri)).password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri)).ssl(JedisURIHelper.isRedisSSLScheme(uri))
                .connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build();
        var poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2); // AccessoryStore serializes all inventory I/O.
        poolConfig.setMaxWait(java.time.Duration.ofSeconds(2));
        pool = new JedisPool(poolConfig,
                new HostAndPort(uri.getHost(), uri.getPort() == -1 ? 6379 : uri.getPort()), config);
        try (Jedis jedis = pool.getResource()) {
            jedis.ping(); // Fail before publishing an unusable backend at startup/reload.
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }
    public String loadInventory(UUID owner) {
        try (Jedis jedis = pool.getResource()) { return jedis.get(keyPrefix + owner); }
    }
    public void saveInventory(UUID owner, String encoded) {
        try (Jedis jedis = pool.getResource()) { jedis.set(keyPrefix + owner, encoded); }
    }
    public void deleteInventory(UUID owner) {
        try (Jedis jedis = pool.getResource()) { jedis.del(keyPrefix + owner); }
    }
    private void scan(Jedis jedis, String prefix, Consumer<String> visitor) {
        String escaped = prefix.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[");
        ScanParams params = new ScanParams().match(escaped + "*").count(250);
        String cursor = "0";
        do {
            var page = jedis.scan(cursor, params);
            page.getResult().forEach(key -> visitor.accept(key.substring(prefix.length())));
            cursor = page.getCursor();
        } while (!cursor.equals("0"));
    }
    @Override public Set<UUID> players(Consumer<String> skipped) {
        Set<UUID> result = new TreeSet<>();
        try (Jedis jedis = pool.getResource()) {
            scan(jedis, keyPrefix, k -> { if (!k.startsWith("lock:")) InventoryBackend.addPlayer(result, k, skipped); });
        }
        return result;
    }
    @Override public Set<String> activeLeaseOwners() {
        Set<String> owners = new TreeSet<>();
        try (Jedis jedis = pool.getResource()) {
            scan(jedis, keyPrefix + "lock:", k -> {
                String value = jedis.get(keyPrefix + "lock:" + k);
                if (value != null) owners.add(value.split("[|]", 2)[0]);
            });
        }
        return owners;
    }
    @Override public void initializeLeases() { }
    private String lock(UUID owner) { return keyPrefix + "lock:" + owner; }
    @Override public Claim acquire(UUID owner, String server, UUID token, int seconds) {
        String script = """
                local v=redis.call('get',KEYS[1])
                if not v then
                    v=ARGV[1]..'|'..ARGV[2]
                    redis.call('set',KEYS[1],v,'PX',ARGV[3])
                elseif string.sub(v,1,#ARGV[1]+1)==ARGV[1]..'|' then
                    redis.call('pexpire',KEYS[1],ARGV[3])
                end
                return {v,redis.call('pttl',KEYS[1])}""";
        try (var jedis = pool.getResource()) {
            var response = (List<?>) jedis.eval(script, List.of(lock(owner)),
                    List.of(server, token.toString(), Integer.toString(seconds * 1000)));
            String[] parts = response.getFirst().toString().split("[|]", 2);
            return new Claim(parts[0].equals(server) ? UUID.fromString(parts[1]) : null, parts[0],
                    ((Number) response.get(1)).longValue());
        }
    }
    private static final String MATCH = "local v=redis.call('get',KEYS[1]); if not v or string.sub(v,-37)~='|'..ARGV[1] then ";
    @Override public Set<UUID> renew(Map<UUID,UUID> owners,int seconds) {
        Set<UUID> retained=new HashSet<>();
        String script=MATCH+"return 0 end; return redis.call('pexpire',KEYS[1],ARGV[2])";
        try(var jedis=pool.getResource();var pipeline=jedis.pipelined()) {
            Map<UUID,Response<Object>> results=new HashMap<>();
            owners.forEach((id,token)->results.put(id,pipeline.eval(script,List.of(lock(id)),List.of(token.toString(),Integer.toString(seconds*1000)))));
            pipeline.sync();
            results.forEach((id,response)->{if(Long.valueOf(1).equals(response.get()))retained.add(id);});
        }
        return retained;
    }
    @Override public void write(UUID owner,UUID token,String data,boolean delete) throws Lost {
        String script=MATCH+"return v or '' end; "+(delete?"redis.call('del',KEYS[2])":"redis.call('set',KEYS[2],ARGV[2])")+"; return false";
        try(var jedis=pool.getResource()){
            Object holder=jedis.eval(script,List.of(lock(owner),keyPrefix+owner),List.of(token.toString(),delete?"":data));
            if(holder!=null)throw new Lost(holder.toString().split("[|]",2)[0]);
        }
    }
    @Override public String holder(UUID id) {
        try (var jedis = pool.getResource()) {
            String value = jedis.get(lock(id));
            return value == null ? null : value.split("[|]",2)[0];
        }
    }
    @Override public void release(UUID owner,UUID token) {
        try(var jedis=pool.getResource()){
            jedis.eval(MATCH+"return 0 end; return redis.call('del',KEYS[1])",List.of(lock(owner)),List.of(token.toString()));
        }
    }
    @Override public void close() { pool.close(); }
}

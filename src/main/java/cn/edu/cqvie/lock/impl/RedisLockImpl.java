package cn.edu.cqvie.lock.impl;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.convert.Converters;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * ClassName: RedisLockImpl. <br/>
 * Description: Redis分布式锁实现. <br/>
 * Date: 2019-01-23. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
@Component
public class RedisLockImpl extends AbstractRedisLock {

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    private ThreadLocal<String> threadLocal = new ThreadLocal<String>();
    private static final String UNLOCK_LUA;

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("if redis.call(\"get\",KEYS[1]) == ARGV[1] ");
        sb.append("then ");
        sb.append("    return redis.call(\"del\",KEYS[1]) ");
        sb.append("else ");
        sb.append("    return 0 ");
        sb.append("end ");
        UNLOCK_LUA = sb.toString();

    }

    @Override
    public boolean tryLock(String key) {
        return tryLock(key, TIMEOUT_MILLIS);
    }

    public boolean tryLock(String key, long expire) {
        try {
            return Converters.stringToBoolean(Objects.requireNonNull(redisTemplate.execute((RedisCallback<String>) connection -> {
                Object nativeConnection = connection.getNativeConnection();
                String uuid = UUID.randomUUID().toString();
                threadLocal.set(uuid);

                byte[] k = serialize(key);
                byte[] v = serialize(uuid);

                if (nativeConnection instanceof RedisAsyncCommands) {
                    RedisAsyncCommands commands = (RedisAsyncCommands) nativeConnection;
                    return commands.getStatefulConnection()
                            .sync()
                            .set(k, v, SetArgs.Builder.nx().px(expire));
                } else if (nativeConnection instanceof RedisAdvancedClusterAsyncCommands) {
                    RedisAdvancedClusterAsyncCommands commands = (RedisAdvancedClusterAsyncCommands) nativeConnection;
                    return commands.getStatefulConnection()
                            .sync()
                            .set(k, v, SetArgs.Builder.nx().px(expire));
                }
                return "";
            })));
        } catch (Throwable e) {
            e.printStackTrace();
            logger.error("set redis occurred an exception", e);
        }
        return false;
    }


    @Override
    public boolean lock(String key, long expire, long retryTimes) {
        boolean result = tryLock(key, expire);
        final long deadline = System.currentTimeMillis() + retryTimes;
        while (!result && retryTimes > 0L) {
            try {
                logger.debug("lock failed, retrying...{}", retryTimes);
                Thread.sleep(SLEEP_MILLIS);
            } catch (InterruptedException e) {
                return false;
            }
            //重试
            result = tryLock(key, expire);
            //计算重试时间
            retryTimes = deadline - System.currentTimeMillis();
        }
        return result;
    }

    @Override
    public boolean unlock(String key) {
        try {
            Object[] keys = new Object[]{serialize(key)};
            Object[] values = new Object[]{serialize(threadLocal.get())};
            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
                Object nativeConnection = connection.getNativeConnection();

                if (nativeConnection instanceof RedisAsyncCommands) {
                    RedisAsyncCommands commands = (RedisAsyncCommands) nativeConnection;
                    return (Long) commands.getStatefulConnection().sync().eval(UNLOCK_LUA, ScriptOutputType.INTEGER, keys, values);
                } else if (nativeConnection instanceof RedisAdvancedClusterAsyncCommands) {
                    RedisAdvancedClusterAsyncCommands commands = (RedisAdvancedClusterAsyncCommands) nativeConnection;
                    return (Long) commands.getStatefulConnection().sync().eval(UNLOCK_LUA, ScriptOutputType.INTEGER, keys, values);
                }
                return 0L;
            });
            return result != null && result > 0;
        } catch (Throwable e) {
            logger.warn("unlock occurred an exception", e);
        } finally {
            //清除掉ThreadLocal中的数据，避免内存溢出
            threadLocal.remove();
        }
        return false;
    }

    private byte[] serialize(String key) {
        RedisSerializer<String> stringRedisSerializer =
                (RedisSerializer<String>) redisTemplate.getKeySerializer();
        //lettuce连接包下序列化键值，否则无法用默认的ByteArrayCodec解析
        return stringRedisSerializer.serialize(key);
    }
}
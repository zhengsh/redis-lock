package cn.edu.cqvie.lock.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisCommands;

import java.util.Collections;
import java.util.List;
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
            return !StringUtils.isEmpty(redisTemplate.execute((RedisCallback<String>) connection -> {
                JedisCommands commands = (JedisCommands) connection.getNativeConnection();
                String uuid = UUID.randomUUID().toString();
                threadLocal.set(uuid);
                return commands.set(key, uuid, "NX", "PX", expire);
            }));
        } catch (Throwable e) {
            logger.error("set redis occurred an exception", e);
        }
        return false;
    }

    @Override
    public boolean lock(String key, long expire, long retryTimes) {
        boolean result = tryLock(key, expire);

        while (!result && retryTimes-- > 0) {
            try {
                logger.debug("lock failed, retrying...{}", retryTimes);
                Thread.sleep(SLEEP_MILLIS);
            } catch (InterruptedException e) {
                return false;
            }
            result = tryLock(key, expire);
        }
        return result;
    }

    @Override
    public boolean unlock(String key) {
        try {
            List<String> keys = Collections.singletonList(key);
            List<String> args = Collections.singletonList(threadLocal.get());
            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
                Object nativeConnection = connection.getNativeConnection();

                if (nativeConnection instanceof JedisCluster) {
                    return (Long) ((JedisCluster) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }
                if (nativeConnection instanceof Jedis) {
                    return (Long) ((Jedis) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }
                return 0L;
            });
            return result != null && result > 0;
        } catch (Throwable e) {
            logger.error("unlock occurred an exception", e);
        }
        return false;
    }
}

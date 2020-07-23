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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * ClassName: RedisLockImpl. <br/>
 * Description: Redis分布式锁实现. <br/>
 * Date: 2019-01-23. <br/>
 * todo 重入
 * todo 自动续期
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
@Component
public class RedisLockImpl extends AbstractRedisLock {

    private Logger log = LoggerFactory.getLogger(getClass());
    Map<String, AtomicBoolean> lockMap = new ConcurrentHashMap<>(128);
    Map<String, BlockingQueue<DelayedThread>> waitersMap = new ConcurrentHashMap<>(128);

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
            return Converters.stringToBoolean(redisTemplate.execute((RedisCallback<String>) connection -> {
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
            }));
        } catch (Throwable e) {
            e.printStackTrace();
            log.error("set redis occurred an exception", e);
        }
        return false;
    }


    @Override
    public boolean lock(String key, long expire, long retryTimes) {
        boolean result = tryLock(key, expire);
        if (!result) {
            final long deadline = System.currentTimeMillis() + retryTimes;
            AtomicBoolean locked = lockMap.get(key);
            BlockingQueue<DelayedThread> waiters = waitersMap.get(key);
            if (locked == null) {
                locked = new AtomicBoolean(false);
                synchronized (lockMap) {
                    lockMap.put(key, locked);
                }
            }
            if (waiters == null) {
                waiters = new DelayQueue<>();
                synchronized (waiters) {
                    waitersMap.put(key, waiters);
                }
            }
            //1.本地排队抢占 CPU 时间片
            Thread current = Thread.currentThread();
            waiters.add(new DelayedThread(current, deadline));

            //cas
            boolean wasInterrupted = false;
            assert waiters.peek() != null;
            while (waiters.peek().getThread() != current ||
                    !locked.compareAndSet(false, true)) {
                LockSupport.park(this);
                //等待时忽略中断
                System.out.println("等待时忽略中断");
                if (Thread.interrupted()) {
                    wasInterrupted = true;
                }
            }

            //2.抢到了本地CPU时间片，开始去申请 Redis 共享Key
            while (--retryTimes > 0) {
                try {
                    log.info("lock failed：{}, retrying...{}", current, retryTimes);
                    Thread.sleep(SLEEP_MILLIS);
                } catch (InterruptedException e) {
                    return false;
                }
                //重试
                result = tryLock(key, expire);
                //计算重试时间
                retryTimes = deadline - System.currentTimeMillis();
            }

            //退出时删除信息
            waiters.remove();
            //退出时重新设置中断状态
            if (wasInterrupted) {
                current.interrupt();
            }
        }
        return result;
    }

    @Override
    public boolean unlock(String key) {
        try {
            AtomicBoolean locked = lockMap.get(key);
            if (locked != null) {
                locked.set(false);
            }
            BlockingQueue<DelayedThread> waiters = waitersMap.get(key);
            if (waiters != null && waiters.peek() != null && waiters.peek().getThread() != null) {
                LockSupport.unpark(waiters.peek().getThread());
            }
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
            log.warn("unlock occurred an exception", e);
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

    public static class DelayedThread implements Delayed {

        private Thread thread;
        private Long timeout;

        public DelayedThread(Thread thread, Long timeout) {
            this.thread = thread;
            this.timeout = timeout;
        }

        public Long getTimeout() {
            return timeout;
        }

        public void setTimeout(Long timeout) {
            this.timeout = timeout;
        }

        public Thread getThread() {
            return thread;
        }

        public void setThread(Thread thread) {
            this.thread = thread;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long currentTime = System.currentTimeMillis();
            return unit.convert(this.timeout - currentTime, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return (int) (this.getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
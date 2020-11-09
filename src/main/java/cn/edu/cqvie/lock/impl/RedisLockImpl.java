package cn.edu.cqvie.lock.impl;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.data.redis.connection.convert.Converters;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

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
@Scope(SCOPE_PROTOTYPE)
public class RedisLockImpl extends AbstractRedisLock {

    private Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final AtomicBoolean locked = new AtomicBoolean(false);
    private final Queue<Thread> waiters = new ConcurrentLinkedQueue<Thread>();

    private static final String UNLOCK_LUA;
    private static final String RENEWAL_LUA;

    private ThreadLocal<String> threadLocal = new ThreadLocal<String>();

    private ScheduledExecutorService executorService =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("if redis.call(\"get\",KEYS[1]) == ARGV[1] ");
        sb.append("then ");
        sb.append("    return redis.call(\"del\",KEYS[1]) ");
        sb.append("else ");
        sb.append("    return 0 ");
        sb.append("end ");
        UNLOCK_LUA = sb.toString();


        sb = new StringBuilder();
        sb.append("if redis.call(\"GET\", KEYS[1]) == ARGV[1] then");
        sb.append("   return redis.call(\"PEXPIRE\", KEYS[1], ARGV[2])");
        sb.append("else");
        sb.append("   return 0");
        sb.append("end");
        RENEWAL_LUA = sb.toString();
    }

    @Override
    public boolean tryLock(String key) {
        return tryLock(key, TIMEOUT_MILLIS);
    }

    public boolean tryLock(String key, long expire) {
        try {
            String execute = redisTemplate.execute((RedisCallback<String>) connection -> {
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
            });
            if (execute == null) {
                return false;
            } else {
                return Converters.stringToBoolean(execute);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            log.error("set redis occurred an exception", e);
        }
        return false;
    }


    /**
     * 获取锁
     *
     * @param key        获取锁的KEY
     * @param expire     得到锁后最大持有时间，默认10s (单位毫秒)
     * @param retryTimes 重试获取锁的retry最大时间，默认2s  (单位毫秒)
     * @return 是否获取到锁
     * @implNote 步骤描述：
     * 1.首先尝试直接获取锁
     * 2.如果获取锁失败，那么就先进行本地排队
     * 3.如果本地排队获得执行权，那么就进行再次尝试
     * 4.如果获取成功，退出
     * 5.如果获取失败，继续重试
     * 6.如果超时返回失败
     */
    @Override
    public boolean lock(String key, long expire, long retryTimes) {
        //1.本地排队抢占 CPU 时间片
        boolean wasInterrupted = false;
        Thread current = Thread.currentThread();
        waiters.add(current);

        //cas
        while (waiters.peek() != current ||
                !locked.compareAndSet(false, true)) {
            LockSupport.park(this);
            // ignore interrupts while waiting
            if (Thread.interrupted()) {
                wasInterrupted = true;
            }
        }

        boolean result = tryLock(key, expire);
        if (!result) {
            final long deadline = System.currentTimeMillis() + retryTimes;
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
        automaticRenewal(result, key, expire);
        return result;
    }

    /**
     * 自动续期
     *
     * @param result
     * @param key
     * @param expire
     */
    private void automaticRenewal(boolean result, String key, long expire) {
        if (result) {
            //自动续期
            executorService.scheduleAtFixedRate(() -> {
                Object[] keys = new Object[]{serialize(key)};
                Object[] values = new Object[]{serialize(threadLocal.get())};
                Long res = redisTemplate.execute((RedisCallback<Long>) connection -> {
                    Object nativeConnection = connection.getNativeConnection();

                    if (nativeConnection instanceof RedisAsyncCommands) {
                        RedisAsyncCommands commands = (RedisAsyncCommands) nativeConnection;
                        return (Long) commands.getStatefulConnection().sync().eval(RENEWAL_LUA, ScriptOutputType.INTEGER, keys, values);
                    } else if (nativeConnection instanceof RedisAdvancedClusterAsyncCommands) {
                        RedisAdvancedClusterAsyncCommands commands = (RedisAdvancedClusterAsyncCommands) nativeConnection;
                        return (Long) commands.getStatefulConnection().sync().eval(RENEWAL_LUA, ScriptOutputType.INTEGER, keys, values);
                    }
                    return 0L;
                });
                if (res > 0) { //如果执行成功了继续
                    automaticRenewal(true, key, expire);
                }
            }, expire / 3, expire / 3, TimeUnit.SECONDS);
        }
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

            locked.set(false);
            LockSupport.unpark(waiters.peek());

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

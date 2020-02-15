package cn.edu.cqvie.lock;

/**
 * ClassName: RedisLock. <br/>
 * Description: Redis分布式锁. <br/>
 * Date: 2019-01-23. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
public interface RedisLock {

    /**
     * 持有锁的默认最大超时时间 10s (单位毫秒)
     */
    long TIMEOUT_MILLIS = 10000;

    /**
     * 重试获取锁的retry最大超时时间 2s (单位毫秒)
     */
    int RETRY_MILLIS = 2000;

    /**
     * 自旋获取锁的 sleep 时间
     */
    long SLEEP_MILLIS = 10;

    /**
     * 获取锁 Redis 分布式锁，如果获取失败不重试获取
     *
     * @param key 获取锁的KEY
     * @return
     */
    boolean tryLock(String key);

    /**
     * 获取锁 Redis 分布式锁
     *
     * @param key 获取锁的KEY
     * @return
     */
    boolean lock(String key);

    /**
     * 获取锁 Redis 分布式锁
     *
     * @param key    获取锁的KEY
     * @param expire 得到锁后最大持有时间，默认10s (单位毫秒)
     * @return
     */
    boolean lock(String key, long expire);

    /**
     * 获取锁 Redis 分布式锁
     *
     * @param key        获取锁的KEY
     * @param expire     得到锁后最大持有时间，默认10s (单位毫秒)
     * @param retryTimes 重试获取锁的retry最大时间，默认2s  (单位毫秒)
     * @return
     */
    boolean lock(String key, long expire, long retryTimes);

    /**
     * 释放锁 Redis 分布式锁
     *
     * @param key 被释放锁的KEY
     * @return
     */
    boolean unlock(String key);
}

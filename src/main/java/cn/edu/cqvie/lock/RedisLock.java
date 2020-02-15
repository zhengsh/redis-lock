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

    long TIMEOUT_MILLIS = 5000;

    int RETRY_MILLIS = 2000;

    long SLEEP_MILLIS = 10;

    boolean tryLock(String key);

    boolean lock(String key);

    boolean lock(String key, long expire);

    /**
     * 获取锁
     * @param key 获取锁的KEY
     * @param expire 得到锁后最大持有时间
     * @param retryTimes 重试获取锁的retry最大时间
     * @return
     */
    boolean lock(String key, long expire, long retryTimes);

    boolean unlock(String key);
}

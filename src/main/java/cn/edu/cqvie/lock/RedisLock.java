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

    long TIMEOUT_MILLIS = 30000;

    int RETRY_MILLIS = 30000;

    long SLEEP_MILLIS = 10;

    boolean tryLock(String key);

    boolean lock(String key);

    boolean lock(String key, long expire);

    boolean lock(String key, long expire, long retryTimes);

    boolean unlock(String key);
}

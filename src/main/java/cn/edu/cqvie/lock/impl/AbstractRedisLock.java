package cn.edu.cqvie.lock.impl;

import cn.edu.cqvie.lock.RedisLock;

/**
 * ClassName: AbstractRedisLock. <br/>
 * Description: Redis分布式锁实现. <br/>
 * Date: 2019-01-23. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
public abstract class AbstractRedisLock implements RedisLock {

    @Override
    public boolean lock(String key) {
        return lock(key, TIMEOUT_MILLIS);
    }

    @Override
    public boolean lock(String key, long expire) {
        return lock(key, TIMEOUT_MILLIS, RETRY_MILLIS);
    }
}

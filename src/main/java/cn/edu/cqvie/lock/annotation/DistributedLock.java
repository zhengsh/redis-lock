package cn.edu.cqvie.lock.annotation;

import cn.edu.cqvie.lock.RedisLock;

import java.lang.annotation.*;

/**
 * ClassName: DistributedLock. <br/>
 * Description: Redis分布式锁实现. <br/>
 * Date: 2020-03-28. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.8
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface DistributedLock {

    /**
     * 锁定的KEY, 支持 Spring EL 表达式
     *
     * @return
     */
    String key();

    /**
     * 超时时间
     *
     * @return
     */
    long expire() default 5000L;
}

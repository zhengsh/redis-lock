package cn.edu.cqvie.lock.impl;

import cn.edu.cqvie.lock.RedisLock;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * ClassName: RedisLockImplTest. <br/>
 * Description: 分布式锁单元测试(模拟秒杀场景). <br/>
 * Date: 2019-01-23. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
@SpringBootTest
public class RedisLockImplTest {

    private Logger logger = LoggerFactory.getLogger(getClass());
    @Autowired
    private RedisLock redisLock;
    @Autowired
    private StringRedisTemplate redisTemplate;
    private ExecutorService executors = Executors.newScheduledThreadPool(8);

    @Test
    public void lock() {
        // 初始化库存
        redisTemplate.opsForValue().set("goods-flash-sale", "1");

        for (int i = 0; i < 16; i++) {
            executors.submit(this::flashSale);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        //等待执行结果
        executors.shutdown();
        while (true) {
            if (executors.isTerminated()) {
                break;
            } else {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public int flashSale() {
        String key = "goods-flash-sale-lock";
        try {
            redisLock.lock(key);
            int num = Integer.parseInt(Objects.requireNonNull(redisTemplate.opsForValue().get("goods-flash-sale")));
            if (num > 0) {
                redisTemplate.opsForValue().set("goods-flash-sale", String.valueOf(--num));
                logger.info("秒杀成功，剩余库存：{}", num);
            } else {
                logger.error("秒杀失败，剩余库存：{}", num);
            }
            return num;
        } catch (Throwable e) {
            logger.error("flash sale exception", e);
        } finally {
            redisLock.unlock(key);
        }
        return 0;
    }
}

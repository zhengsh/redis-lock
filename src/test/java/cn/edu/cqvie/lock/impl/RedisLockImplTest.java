package cn.edu.cqvie.lock.impl;

import cn.edu.cqvie.lock.RedisLock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
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
@RunWith(SpringRunner.class)
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
        redisTemplate.opsForValue().set("goods-seckill", "10");
        List<Future> futureList = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            futureList.add(executors.submit(this::seckill));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 等待结果，防止主线程退出
        futureList.forEach(action -> {
            try {
                action.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        });

    }

    public int seckill() {
        String key = "goods";
        try {
            redisLock.lock(key);
            int num = Integer.valueOf(Objects.requireNonNull(redisTemplate.opsForValue().get("goods-seckill")));
            if (num > 0) {
                redisTemplate.opsForValue().set("goods-seckill", String.valueOf(--num));
                logger.info("秒杀成功，剩余库存：{}", num);
            } else {
                logger.error("秒杀失败，剩余库存：{}", num);
            }
            return num;
        } catch (Throwable e) {
            logger.error("seckill exception", e);
        } finally {
            redisLock.unlock(key);
        }
        return 0;
    }
}

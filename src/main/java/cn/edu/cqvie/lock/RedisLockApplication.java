package cn.edu.cqvie.lock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ClassName: RedisLockAppliction. <br/>
 * Description: spring boot start. <br/>
 * Date: 2019-01-23. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.7
 */
@SpringBootApplication
public class RedisLockApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RedisLockApplication.class);
        app.run();
    }
}

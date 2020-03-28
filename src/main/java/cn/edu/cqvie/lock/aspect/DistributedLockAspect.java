package cn.edu.cqvie.lock.aspect;

import cn.edu.cqvie.lock.RedisLock;
import cn.edu.cqvie.lock.annotation.DistributedLock;
import javassist.bytecode.SignatureAttribute;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * ClassName: DistributedLock. <br/>
 * Description: Redis分布式锁实现. <br/>
 * Date: 2020-03-28. <br/>
 *
 * @author zhengsh
 * @version 1.0.0
 * @since 1.8
 */
@Aspect
@Component
public class DistributedLockAspect {

    @Autowired
    private RedisLock redisLock;

    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        Class[] argTypes = ((MethodSignature) joinPoint.getSignature()).getParameterTypes();
        Method method = joinPoint.getTarget().getClass().getMethod(joinPoint.getSignature().getName(), argTypes);
        DistributedLock dLock = method.getAnnotation(DistributedLock.class);
        Object result;
        String k = parseKey(dLock.key(), method, joinPoint.getArgs());
        try {
            redisLock.lock(k, dLock.expire() > 0 ? dLock.expire() : 5000L);
            result = joinPoint.proceed();
        } finally {
            redisLock.unlock(k);
        }
        return result;
    }

    private String parseKey(String key, Method method, Object[] args) {
        if (key == null || "".equals(key.trim())) {
            // 默认KEY
            return "lock:distributed:default";
        }
        LocalVariableTableParameterNameDiscoverer nd = new LocalVariableTableParameterNameDiscoverer();
        String[] parameterNames = nd.getParameterNames(method);
        ExpressionParser parser = new SpelExpressionParser();
        StandardEvaluationContext context = new StandardEvaluationContext();

		assert parameterNames != null;
		for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i], args[i]);
        }
		return parser.parseExpression(key).getValue(context, String.class);
    }
}
package com.llf.ai.domain.agent.service.armory;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }
    /**
     * 通用的Bean注册方法
     *
     * @param beanName  Bean名称
     * @param beanClass Bean类型
     * @param <T>       Bean类型
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // 注册Bean
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 如果Bean已存在，先移除
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }

        // 注册新的Bean
        beanFactory.registerBeanDefinition(beanName, beanDefinition);

        log.info("成功注册Bean: {}", beanName);
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }
}

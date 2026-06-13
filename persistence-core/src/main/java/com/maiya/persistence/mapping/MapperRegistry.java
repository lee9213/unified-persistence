package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Mapper 注册中心，负责管理和缓存 MyBatis-Plus 的 BaseMapper 实例。
 *
 * <p>根据实体 DO 类自动推断对应的 Mapper Bean 名称，并从 Spring 上下文中获取， 避免每次重复查找，提升性能。
 *
 * @author 萨博
 */
@Component
public class MapperRegistry {

    /** Spring 应用上下文，用于从容器中获取 Mapper Bean */
    @Autowired private ApplicationContext applicationContext;

    /** Mapper 实例缓存，Key 为 DO 类，Value 为对应的 BaseMapper */
    private final Map<Class<?>, BaseMapper<?>> mapperCache = new ConcurrentHashMap<>();

    /**
     * 根据 DO 类获取对应的 BaseMapper 实例。
     *
     * @param doClass DO 数据对象类
     * @param <T> DO 类型
     * @return 对应的 BaseMapper 实例
     */
    @SuppressWarnings("unchecked")
    public <T> BaseMapper<T> getMapper(Class<T> doClass) {
        return (BaseMapper<T>)
                mapperCache.computeIfAbsent(
                        doClass,
                        cls -> {
                            // 根据 DO 类名推断 Mapper Bean 名称，例如 UserDO -> UserMapper
                            String mapperName = cls.getSimpleName().replace("DO", "") + "Mapper";
                            return applicationContext.getBean(mapperName, BaseMapper.class);
                        });
    }
}

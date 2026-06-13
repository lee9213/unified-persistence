package com.maiya.persistence.mapping;

import io.github.linpeilie.Converter;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 实体拷贝器，基于 MapStruct 的 Converter 实现实体与 DO 之间的深度拷贝和类型转换。
 *
 * <p>提供实体深拷贝、实体转 DO、DO 转实体以及列表批量转换等功能。
 *
 * @author 萨博
 */
@Component
public class EntityCopier {

    /** MapStruct 转换器，用于对象之间的映射转换 */
    @Autowired private Converter converter;

    /**
     * 对源对象进行深度拷贝。
     *
     * @param source 源对象
     * @param <T> 对象类型
     * @return 深度拷贝后的新对象
     */
    @SuppressWarnings("unchecked")
    public <T> T deepCopy(T source) {
        if (source == null) return null;
        return converter.convert(source, (Class<T>) source.getClass());
    }

    /**
     * 将实体对象转换为指定的 DO 类对象。
     *
     * @param entity 实体对象
     * @param doClass 目标 DO 类
     * @param <S> 源类型
     * @param <T> 目标类型
     * @return 转换后的 DO 对象
     */
    public <S, T> T toDO(S entity, Class<T> doClass) {
        if (entity == null) return null;
        return converter.convert(entity, doClass);
    }

    /**
     * 将 DO 对象转换为指定的实体类对象。
     *
     * @param DO DO 对象
     * @param entityClass 目标实体类
     * @param <S> 源类型
     * @param <T> 目标类型
     * @return 转换后的实体对象
     */
    public <S, T> T toEntity(S DO, Class<T> entityClass) {
        if (DO == null) return null;
        return converter.convert(DO, entityClass);
    }

    /**
     * 将源对象列表转换为目标类型的列表。
     *
     * @param sources 源对象列表
     * @param targetClass 目标类型类
     * @param <S> 源元素类型
     * @param <T> 目标元素类型
     * @return 转换后的目标类型列表
     */
    public <S, T> List<T> toList(List<S> sources, Class<T> targetClass) {
        if (sources == null) return null;
        return converter.convert(sources, targetClass);
    }
}

package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.linpeilie.annotations.AutoMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Mapper 注册中心，负责管理和缓存 MyBatis-Plus 的 BaseMapper 实例。
 *
 * <p>容器启动时扫描所有 BaseMapper Bean，解析其泛型参数获取对应的 DO Class， 同时通过 DO 上的 @AutoMapper 注解获取对应的 Entity Class，
 * 预解析每个 DO 的元数据（主键、基本字段、Mapper），以 Entity Class 为 key 缓存，供 MetadataResolver 直接查表使用。
 *
 * @author 萨博
 */
public class MapperRegistry implements InitializingBean, ApplicationContextAware {

    /** Spring 应用上下文，用于从容器中获取 Mapper Bean */
    private ApplicationContext applicationContext;

    /** DO 元数据注册表，Key 为 Entity 的 Class（通过 DO 上的 @AutoMapper 注解获取），Value 为预解析的元数据 */
    private final Map<Class<?>, DoMetadata> doMetadataMap = new HashMap<>();

    /** MethodHandle 查找器 */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 扫描 Spring 容器中所有 BaseMapper Bean，解析泛型参数获取 DO Class， 通过 @AutoMapper 注解获取 Entity Class， 预解析 DO
     * 元数据并以 Entity Class 为 key 注册到注册表中。
     */
    @Override
    public void afterPropertiesSet() {
        applicationContext.getBeansOfType(BaseMapper.class).values().forEach(mapper -> {
            Class<?> doClass = resolveDoClassFromMapper(mapper);
            if (doClass == null) {
                return;
            }
            Class<?> entityClass = resolveEntityClassFromDo(doClass);
            if (entityClass == null) {
                return;
            }
            doMetadataMap.put(entityClass, resolveDoMetadata(doClass, mapper));
        });
    }

    /**
     * 从 BaseMapper 实例解析其泛型参数，获取对应的 DO Class。
     *
     * @param mapper BaseMapper 实例
     * @return DO 类的 Class 对象，解析失败返回 null
     */
    private Class<?> resolveDoClassFromMapper(BaseMapper<?> mapper) {
        ResolvableType resolvableType = ResolvableType.forClass(mapper.getClass()).as(BaseMapper.class);
        Class<?> generic = resolvableType.getGeneric(0).resolve();
        // 过滤掉框架内部类
        if (generic != null && !generic.getName().startsWith("java.")) {
            return generic;
        }
        return null;
    }

    /**
     * 从 DO 类的 @AutoMapper 注解中解析对应的 Entity Class，注解不存在则返回 null。
     *
     * @param doClass DO 类
     * @return Entity 类的 Class 对象，注解不存在则返回 null
     */
    private Class<?> resolveEntityClassFromDo(Class<?> doClass) {
        AutoMapper autoMapper = doClass.getAnnotation(AutoMapper.class);
        return autoMapper != null ? autoMapper.target() : null;
    }

    /**
     * 预解析 DO 类的元数据，包括主键字段、基本字段访问器和对应的 Mapper。
     *
     * @param doClass DO 类
     * @param mapper 对应的 BaseMapper 实例
     * @return DO 元数据
     */
    private DoMetadata resolveDoMetadata(Class<?> doClass, BaseMapper<?> mapper) {
        DoMetadata metadata = new DoMetadata();
        metadata.setDoClass(doClass);
        metadata.setMapper(mapper);

        // 遍历 DO 字段，识别主键和基本字段
        for (Field doField : doClass.getDeclaredFields()) {
            if (Modifier.isStatic(doField.getModifiers()) || Modifier.isTransient(doField.getModifiers())) {
                continue;
            }
            if (doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class) != null
                && !doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).exist()) {
                continue;
            }
            if (doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableId.class) != null) {
                // 主键字段
                metadata.setIdFieldName(doField.getName());
                metadata.setIdGetter(findGetter(doClass, doField.getName()));
            } else {
                // 基本字段
                metadata.getBasicFields().add(new EntityMetadata.FieldAccessor(doField.getName(), findGetter(doClass, doField.getName())));
            }
        }

        if (metadata.getIdFieldName() == null) {
            throw new IllegalArgumentException("DO " + doClass.getName() + " 没有找到 @TableId 注解的主键字段");
        }

        return metadata;
    }

    /**
     * 查找 DO 类字段的 getter 方法并返回其 MethodHandle
     *
     * @param clazz 目标类
     * @param fieldName 字段名
     * @return getter 方法的 MethodHandle
     */
    private MethodHandle findGetter(Class<?> clazz, String fieldName) {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method getter = clazz.getDeclaredMethod(getterName);
            return LOOKUP.unreflect(getter);
        } catch (NoSuchMethodException e) {
            String isGetterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                Method isGetter = clazz.getDeclaredMethod(isGetterName);
                return LOOKUP.unreflect(isGetter);
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new IllegalArgumentException("类 " + clazz.getName() + " 没有找到字段 " + fieldName + " 的 getter 方法", ex);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("无法访问 " + clazz.getName() + "." + getterName + " 方法", e);
        }
    }

    /**
     * 根据 Entity Class 获取预解析的 DO 元数据。
     *
     * @param entityClass Entity 类
     * @return DO 元数据，未找到则返回 null
     */
    public DoMetadata getDoMetadata(Class<?> entityClass) {
        return doMetadataMap.get(entityClass);
    }
}

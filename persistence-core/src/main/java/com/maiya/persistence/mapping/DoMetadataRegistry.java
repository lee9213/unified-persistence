package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.linpeilie.Converter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DO 元数据注册中心，负责扫描并缓存 Entity 与 DO 的映射元数据。
 *
 * <p>容器启动时扫描所有 Converter Bean，解析其泛型参数获取 DO → Entity 映射关系，
 * 同时扫描所有 BaseMapper Bean，解析其泛型参数获取对应的 DO Class。
 * 预解析每个 DO 的元数据（主键、基本字段、Mapper），以 Entity Class 为 key 缓存，
 * 供 EntityMetadataResolver 直接查表使用。
 *
 * @author 萨博
 */
public class DoMetadataRegistry implements SmartInitializingSingleton, ApplicationContextAware {

    /** Spring 应用上下文，用于从容器中获取 Mapper Bean */
    private ApplicationContext applicationContext;

    /** DO 元数据注册表，Key 为 Entity 的 Class（通过 DO 上的 @AutoMapper 注解获取），Value 为预解析的元数据 */
    private static final Map<Class<?>, DoMetadata> DO_METADATA_MAP = new ConcurrentHashMap<>();

    /** DO → Entity 映射注册表，从 Converter Bean 解析得到 */
    private static final Map<Class<?>, Class<?>> DO_TO_ENTITY_MAP = new ConcurrentHashMap<>();

    /** MethodHandle 查找器 */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 全部单例 Bean 实例化完成后扫描 Mapper 和 Converter，确保 MyBatis 代理已注册。
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 1. 先从 Converter Bean 解析 DO → Entity 映射关系
        resolveDoToEntityMapping();

        // 2. 扫描所有 BaseMapper，注册 DO 元数据
        applicationContext.getBeansOfType(BaseMapper.class).values().forEach(mapper -> {
            Class<?> doClass = resolveDoClassFromMapper(mapper);
            if (doClass == null) {
                return;
            }
            Class<?> entityClass = DO_TO_ENTITY_MAP.get(doClass);
            if (entityClass == null) {
                return;
            }
            DO_METADATA_MAP.put(entityClass, resolveDoMetadata(doClass, mapper));
        });
    }

    /**
     * 从 Spring 容器中的 Converter Bean 解析 DO → Entity 映射关系。
     * 通过 Converter<S, T> 接口的泛型参数解析：S 是源类型（DO），T 是目标类型（Entity）
     */
    private void resolveDoToEntityMapping() {
        Map<String, Converter> converterBeans = applicationContext.getBeansOfType(Converter.class);

        for (Converter converter : converterBeans.values()) {
            Class<?> converterClass = converter.getClass();
            Class<?>[] types = parseDoAndEntityFromConverterClass(converterClass);
            if (types != null) {
                DO_TO_ENTITY_MAP.put(types[0], types[1]);
            }
        }
    }

    /**
     * 从 Converter 类的泛型参数解析 DO 和 Entity 类型。
     * Converter<S, T> 接口的泛型参数：S 是源类型（DO），T 是目标类型（Entity）
     *
     * @param converterClass Converter 实现类
     * @return [DO类型, Entity类型]，解析失败返回 null
     */
    private Class<?>[] parseDoAndEntityFromConverterClass(Class<?> converterClass) {
        try {
            Class<?> current = converterClass;
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Type type : current.getGenericInterfaces()) {
                    if (type instanceof java.lang.reflect.ParameterizedType) {
                        java.lang.reflect.ParameterizedType parameterizedType =
                            (java.lang.reflect.ParameterizedType) type;
                        if (parameterizedType.getRawType().equals(Converter.class)) {
                            java.lang.reflect.Type[] typeArgs = parameterizedType.getActualTypeArguments();
                            if (typeArgs.length == 2) {
                                Class<?> sourceType = resolveTypeArgument(typeArgs[0]);
                                Class<?> targetType = resolveTypeArgument(typeArgs[1]);
                                if (sourceType != null && targetType != null) {
                                    return new Class<?>[]{sourceType, targetType};
                                }
                            }
                        }
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    /**
     * 解析类型参数，支持泛型和通配符。
     */
    private Class<?> resolveTypeArgument(java.lang.reflect.Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.Type rawType = ((java.lang.reflect.ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        return null;
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
        return DO_METADATA_MAP.get(entityClass);
    }
}

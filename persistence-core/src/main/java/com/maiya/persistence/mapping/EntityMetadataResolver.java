package com.maiya.persistence.mapping;

import lombok.RequiredArgsConstructor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体元数据解析器，负责解析实体类的结构并生成类级元数据模板。
 *
 * <p>核心方法 {@link #resolve(Class)} 接收实体类，返回缓存的类级元数据模板（doValue 为 null）， 描述实体类的 DO 映射、子实体结构等信息。
 * 实例级数据填充（Entity→DO 转换）由调用方负责。
 *
 * @author 萨博
 */
@RequiredArgsConstructor
public class EntityMetadataResolver {

    private final DoMetadataRegistry doMetadataRegistry;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** 类级元数据模板缓存（doValue 为 null） */
    private static final Map<Class<?>, EntityMetadata> TEMPLATE_CACHE = new ConcurrentHashMap<>();

    /**
     * 解析实体类的元数据模板（类级，缓存，doValue 为 null）。
     *
     * @param entityClass 实体类
     * @return 实体元数据模板
     */
    public EntityMetadata resolve(Class<?> entityClass) {
        return TEMPLATE_CACHE.computeIfAbsent(entityClass, key -> {
            EntityMetadata template = new EntityMetadata();
            template.setEntityClass(key);
            template.setDoMetadata(resolveDoMetadata(key));
            resolveSubTemplates(key, template);
            return template;
        });
    }

    /** 递归解析实体类的子实体和子实体列表结构模板 */
    private void resolveSubTemplates(Class<?> entityClass, EntityMetadata template) {
        for (Field entityField : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(entityField.getModifiers()) || Modifier.isTransient(entityField.getModifiers())) {
                continue;
            }
            Class<?> fieldType = entityField.getType();
            if (List.class.isAssignableFrom(fieldType)) {
                Class<?> elementClass = getGenericParameter(entityField);
                if (isBasicType(elementClass)) {
                    continue;
                }
                DoMetadata elementDoMeta = doMetadataRegistry.getDoMetadata(elementClass);
                if (elementDoMeta != null) {
                    EntityMetadata subTemplate = new EntityMetadata();
                    subTemplate.setEntityClass(elementClass);
                    subTemplate.setDoMetadata(elementDoMeta);
                    subTemplate.setEntityGetter(findGetter(entityClass, entityField.getName()));
                    resolveSubTemplates(elementClass, subTemplate);
                    if (template.getSubEntityLists() == null) {
                        template.setSubEntityLists(new ArrayList<>());
                    }
                    template.getSubEntityLists().add(subTemplate);
                }
            } else {
                if (isBasicType(fieldType)) {
                    continue;
                }
                DoMetadata subDoMeta = doMetadataRegistry.getDoMetadata(fieldType);
                if (subDoMeta != null) {
                    EntityMetadata subTemplate = new EntityMetadata();
                    subTemplate.setEntityClass(fieldType);
                    subTemplate.setDoMetadata(subDoMeta);
                    subTemplate.setEntityGetter(findGetter(entityClass, entityField.getName()));
                    resolveSubTemplates(fieldType, subTemplate);
                    if (template.getSubEntities() == null) {
                        template.setSubEntities(new ArrayList<>());
                    }
                    template.getSubEntities().add(subTemplate);
                }
            }
        }
    }

    /** 解析实体类对应的 DO 元数据，未找到则抛异常 */
    private DoMetadata resolveDoMetadata(Class<?> entityClass) {
        DoMetadata doMetadata = doMetadataRegistry.getDoMetadata(entityClass);
        if (doMetadata == null) {
            throw new IllegalArgumentException("Entity " + entityClass.getName() + " 对应的 DO 元数据未在注册表中找到");
        }
        return doMetadata;
    }

    /** 判断是否为基本类型字段（String/Number/Boolean/Date/Enum 等） */
    private boolean isBasicType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.isEnum()) {
            return true;
        }
        if (clazz == String.class) {
            return true;
        }
        if (Number.class.isAssignableFrom(clazz) || Boolean.class.isAssignableFrom(clazz)
            || Character.class.isAssignableFrom(clazz)) {
            return true;
        }
        return java.util.Date.class.isAssignableFrom(clazz) || TemporalAccessor.class.isAssignableFrom(clazz);
    }

    /** 获取 List 字段的泛型参数类型 */
    private Class<?> getGenericParameter(Field field) {
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        return (Class<?>) pt.getActualTypeArguments()[0];
    }

    /** 查找实体类字段的 getter 并返回 MethodHandle */
    private MethodHandle findGetter(Class<?> clazz, String fieldName) {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            return LOOKUP.unreflect(clazz.getDeclaredMethod(getterName));
        } catch (NoSuchMethodException e) {
            String isGetterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                return LOOKUP.unreflect(clazz.getDeclaredMethod(isGetterName));
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new IllegalArgumentException("类 " + clazz.getName() + " 没有找到字段 " + fieldName + " 的 getter 方法", ex);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("无法访问 " + clazz.getName() + "." + getterName + " 方法", e);
        }
    }
}

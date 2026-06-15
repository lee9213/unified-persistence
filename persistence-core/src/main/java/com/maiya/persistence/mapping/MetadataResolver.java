package com.maiya.persistence.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 实体元数据解析器，负责解析实体类的结构并生成类级元数据模板。
 *
 * <p>核心方法 {@link #resolve(Class)} 接收实体类，返回缓存的类级元数据模板（doValue 为 null）， 描述实体类的 DO 映射、子实体结构等信息。
 * 实例级数据填充（Entity→DO 转换）由调用方负责。
 *
 * @author 萨博
 */
@Component
public class MetadataResolver {

    @Autowired private MapperRegistry mapperRegistry;

    /** 类级元数据模板缓存（doValue 为 null） */
    private final Map<Class<?>, EntityMetadata> templateCache = new ConcurrentHashMap<>();

    public MetadataResolver() {}

    public MetadataResolver(MapperRegistry mapperRegistry) {
        this.mapperRegistry = mapperRegistry;
    }

    /**
     * 解析实体类的元数据模板（类级，缓存，doValue 为 null）。
     *
     * @param entityClass 实体类
     * @return 实体元数据模板
     */
    public EntityMetadata resolve(Class<?> entityClass) {
        return templateCache.computeIfAbsent(entityClass, this::doResolveTemplate);
    }

    /** 解析类级元数据模板，结果缓存 */
    private EntityMetadata doResolveTemplate(Class<?> entityClass) {
        EntityMetadata template = new EntityMetadata();
        template.setEntityClass(entityClass);
        template.setDoMetadata(resolveDoMetadata(entityClass));
        resolveSubTemplates(entityClass, template);
        return template;
    }

    /** 递归解析实体类的子实体和子实体列表结构模板 */
    private void resolveSubTemplates(Class<?> entityClass, EntityMetadata template) {
        for (Field entityField : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(entityField.getModifiers())
                    || Modifier.isTransient(entityField.getModifiers())) {
                continue;
            }

            Class<?> fieldType = entityField.getType();

            if (isEntityType(fieldType)) {
                DoMetadata subDoMeta = tryResolveDoMetadata(fieldType);
                if (subDoMeta != null) {
                    EntityMetadata subTemplate = new EntityMetadata();
                    subTemplate.setEntityClass(fieldType);
                    subTemplate.setDoMetadata(subDoMeta);
                    resolveSubTemplates(fieldType, subTemplate);
                    if (template.getSubEntities() == null) {
                        template.setSubEntities(new ArrayList<>());
                    }
                    template.getSubEntities().add(subTemplate);
                }
            } else if (List.class.isAssignableFrom(fieldType)) {
                Class<?> elementClass = getGenericParameter(entityField);
                if (isEntityType(elementClass)) {
                    DoMetadata subDoMeta = tryResolveDoMetadata(elementClass);
                    if (subDoMeta != null) {
                        EntityMetadata subTemplate = new EntityMetadata();
                        subTemplate.setEntityClass(elementClass);
                        subTemplate.setDoMetadata(subDoMeta);
                        resolveSubTemplates(elementClass, subTemplate);
                        if (template.getSubEntityLists() == null) {
                            template.setSubEntityLists(new ArrayList<>());
                        }
                        template.getSubEntityLists().add(subTemplate);
                    }
                }
            }
        }
    }

    /** 判断类是否为实体类（类名以 Entity 结尾） */
    private boolean isEntityType(Class<?> clazz) {
        return clazz.getSimpleName().endsWith("Entity");
    }

    /** 解析实体类对应的 DO 元数据，未找到则抛异常 */
    private DoMetadata resolveDoMetadata(Class<?> entityClass) {
        DoMetadata doMetadata = mapperRegistry.getDoMetadata(entityClass);
        if (doMetadata == null) {
            throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " 对应的 DO 元数据未在注册表中找到");
        }
        return doMetadata;
    }

    /** 尝试解析实体类对应的 DO 元数据，未找到返回 null */
    private DoMetadata tryResolveDoMetadata(Class<?> entityClass) {
        return mapperRegistry.getDoMetadata(entityClass);
    }

    /** 获取 List 字段的泛型参数类型 */
    private Class<?> getGenericParameter(Field field) {
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        return (Class<?>) pt.getActualTypeArguments()[0];
    }
}

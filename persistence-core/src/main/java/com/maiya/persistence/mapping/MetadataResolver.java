package com.maiya.persistence.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 实体元数据解析器，负责解析实体类（Entity）的结构并生成对应的实体元数据。
 *
 * <p>DO 层面的元数据已在 MapperRegistry 启动时预解析为 DoMetadata， 本类只需将 Entity 字段与 DoMetadata 组装为 EntityMetadata。
 * 支持递归解析子实体的嵌套子实体。
 *
 * @author 萨博
 */
@Component
public class MetadataResolver {

    @Autowired private MapperRegistry mapperRegistry;

    /** 实体元数据缓存 */
    private final Map<Class<?>, EntityMetadata> metadataCache = new ConcurrentHashMap<>();

    public MetadataResolver() {}

    public MetadataResolver(MapperRegistry mapperRegistry) {
        this.mapperRegistry = mapperRegistry;
    }

    public EntityMetadata resolve(Class<?> entityClass) {
        return metadataCache.computeIfAbsent(entityClass, this::doResolve);
    }

    private EntityMetadata doResolve(Class<?> entityClass) {
        EntityMetadata metadata = new EntityMetadata();
        metadata.setEntityClass(entityClass);
        metadata.setDoMetadata(resolveDoMetadata(entityClass));

        // 递归解析子实体
        resolveSubEntities(entityClass, metadata.getSubEntities(), metadata.getSubEntityLists());

        return metadata;
    }

    /**
     * 递归解析实体类的子实体和子实体列表
     *
     * @param entityClass 实体类
     * @param subEntities 用于收集一对一子实体元数据的列表
     * @param subEntityLists 用于收集一对多子实体列表元数据的列表
     */
    private void resolveSubEntities(
            Class<?> entityClass,
            List<EntityMetadata> subEntities,
            List<EntityMetadata> subEntityLists) {

        for (Field entityField : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(entityField.getModifiers())
                    || Modifier.isTransient(entityField.getModifiers())) {
                continue;
            }

            Class<?> fieldType = entityField.getType();

            if (isEntityType(fieldType)) {
                DoMetadata subDoMeta = tryResolveDoMetadata(fieldType);
                if (subDoMeta != null) {
                    EntityMetadata subMeta = new EntityMetadata();
                    subMeta.setEntityClass(fieldType);
                    subMeta.setDoMetadata(subDoMeta);
                    // 递归解析嵌套子实体
                    resolveSubEntities(
                            fieldType, subMeta.getSubEntities(), subMeta.getSubEntityLists());
                    subEntities.add(subMeta);
                }
            } else if (List.class.isAssignableFrom(fieldType)) {
                Class<?> elementClass = getGenericParameter(entityField);
                if (isEntityType(elementClass)) {
                    DoMetadata subDoMeta = tryResolveDoMetadata(elementClass);
                    if (subDoMeta != null) {
                        EntityMetadata subMeta = new EntityMetadata();
                        subMeta.setEntityClass(elementClass);
                        subMeta.setDoMetadata(subDoMeta);
                        // 递归解析嵌套子实体
                        resolveSubEntities(
                                elementClass,
                                subMeta.getSubEntities(),
                                subMeta.getSubEntityLists());
                        subEntityLists.add(subMeta);
                    }
                }
            }
        }
    }

    private boolean isEntityType(Class<?> clazz) {
        return clazz.getSimpleName().endsWith("Entity");
    }

    /**
     * 根据 Entity 类从 MapperRegistry 中查找对应的预解析 DO 元数据。
     *
     * @param entityClass Entity 类
     * @return 对应的 DO 元数据
     * @throws IllegalArgumentException 如果 DO 元数据不存在于注册表中
     */
    private DoMetadata resolveDoMetadata(Class<?> entityClass) {
        String doSimpleName = entityClass.getSimpleName().replace("Entity", "DO");
        DoMetadata doMetadata = mapperRegistry.getDoMetadata(doSimpleName);
        if (doMetadata == null) {
            throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " 对应的 DO 类 " + doSimpleName + " 未在注册表中找到");
        }
        return doMetadata;
    }

    private DoMetadata tryResolveDoMetadata(Class<?> entityClass) {
        String doSimpleName = entityClass.getSimpleName().replace("Entity", "DO");
        return mapperRegistry.getDoMetadata(doSimpleName);
    }

    private Class<?> getGenericParameter(Field field) {
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        return (Class<?>) pt.getActualTypeArguments()[0];
    }
}

package com.maiya.persistence.mapping;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聚合元数据解析器，负责解析实体类（Entity）的结构并生成对应的聚合元数据。
 *
 * <p>通过反射分析实体类的字段，识别主键、基本字段、子实体以及子实体列表， 并将其与对应的 DO 类和 Mapper 进行关联，为后续的差异化对比和持久化提供元数据支持。
 *
 * @author 萨博
 */
@Component
public class MetadataResolver {

    /**
     * Mapper 注册中心，用于获取各实体对应的 BaseMapper
     */
    @Autowired
    private MapperRegistry mapperRegistry;

    /**
     * 元数据缓存，避免对同一实体类重复解析
     */
    private final Map<Class<?>, AggregateMetadata> metadataCache = new ConcurrentHashMap<>();

    public MetadataResolver() {
    }

    public MetadataResolver(MapperRegistry mapperRegistry) {
        this.mapperRegistry = mapperRegistry;
    }

    /**
     * 解析指定实体类的聚合元数据，结果会被缓存。
     *
     * @param entityClass 实体类
     * @return 聚合元数据
     */
    public AggregateMetadata resolve(Class<?> entityClass) {
        return metadataCache.computeIfAbsent(entityClass, this::doResolve);
    }

    /**
     * 实际执行解析逻辑：识别 DO 类、主键字段、基本字段、子实体和子实体列表。
     *
     * @param entityClass 实体类
     * @return 聚合元数据
     */
    private AggregateMetadata doResolve(Class<?> entityClass) {
        AggregateMetadata metadata = new AggregateMetadata();
        metadata.setEntityClass(entityClass);

        // 根据 Entity 类名推断对应的 DO 类名，例如 UserEntity -> UserDO
        String doClassName = entityClass.getName().replace("Entity", "DO");
        try {
            Class<?> doClass = Class.forName(doClassName);
            metadata.setRootDoClass(doClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Entity " + entityClass.getName() + " 对应的 DO 类 " + doClassName + " 不存在", e);
        }

        metadata.setIdField(findIdField(entityClass));
        metadata.setRootMapper(mapperRegistry.getMapper(metadata.getRootDoClass()));

        // 遍历实体类所有字段，分类处理
        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            Class<?> fieldType = field.getType();

            if (isEntityType(fieldType)) {
                // 单个子实体字段
                Class<?> subDoClass = tryResolveDoClass(fieldType);
                if (subDoClass != null) {
                    AggregateMetadata.SubEntityInfo info = new AggregateMetadata.SubEntityInfo();
                    info.setEntityField(field);
                    info.setEntityClass(fieldType);
                    info.setDoClass(subDoClass);
                    info.setEntityIdField(findIdField(fieldType));
                    info.setMapper(mapperRegistry.getMapper(subDoClass));
                    metadata.getSubEntities().add(info);
                } else {
                    metadata.getBasicFields().add(field);
                }
            } else if (List.class.isAssignableFrom(fieldType)) {
                // 子实体列表字段
                Class<?> elementClass = getGenericParameter(field);
                if (isEntityType(elementClass)) {
                    Class<?> subDoClass = tryResolveDoClass(elementClass);
                    if (subDoClass != null) {
                        AggregateMetadata.SubEntityListInfo info =
                            new AggregateMetadata.SubEntityListInfo();
                        info.setEntityField(field);
                        info.setElementEntityClass(elementClass);
                        info.setElementDoClass(subDoClass);
                        info.setElementIdField(findIdField(elementClass));
                        info.setMapper(mapperRegistry.getMapper(subDoClass));
                        metadata.getSubEntityLists().add(info);
                    } else {
                        metadata.getBasicFields().add(field);
                    }
                } else {
                    metadata.getBasicFields().add(field);
                }
            } else {
                // 基本字段
                metadata.getBasicFields().add(field);
            }
        }
        return metadata;
    }

    /**
     * 判断类名是否以 "Entity" 结尾，标识为一个实体类型。
     *
     * @param clazz 类
     * @return 是否为实体类型
     */
    private boolean isEntityType(Class<?> clazz) {
        return clazz.getSimpleName().endsWith("Entity");
    }

    /**
     * 尝试根据实体类解析对应的 DO 类。
     *
     * @param entityClass 实体类
     * @return DO 类，若不存在则返回 null
     */
    private Class<?> tryResolveDoClass(Class<?> entityClass) {
        try {
            String doClassName = entityClass.getName().replace("Entity", "DO");
            return Class.forName(doClassName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * 查找实体类的主键字段，优先查找名为 "id" 的字段， 否则按 "{实体名}Id" 的命名规则查找。
     *
     * @param entityClass 实体类
     * @return 主键字段
     */
    private Field findIdField(Class<?> entityClass) {
        try {
            return entityClass.getDeclaredField("id");
        } catch (NoSuchFieldException e) {
            String idFieldName =
                decapitalize(entityClass.getSimpleName().replace("Entity", "")) + "Id";
            try {
                return entityClass.getDeclaredField(idFieldName);
            } catch (NoSuchFieldException ex) {
                throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " 没有找到主键字段（id 或 " + idFieldName + "）",
                    ex);
            }
        }
    }

    /**
     * 将字符串首字母小写。
     *
     * @param name 原始字符串
     * @return 首字母小写后的字符串
     */
    private String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * 获取 List 字段的泛型参数类型。
     *
     * @param field List 类型字段
     * @return 泛型参数类
     */
    private Class<?> getGenericParameter(Field field) {
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        return (Class<?>) pt.getActualTypeArguments()[0];
    }
}

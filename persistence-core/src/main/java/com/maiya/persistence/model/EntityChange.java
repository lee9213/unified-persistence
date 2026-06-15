package com.maiya.persistence.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.mapping.DoMetadata;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 实体变更信息，记录实体（根实体或子实体）的变更详情。
 *
 * <p>统一描述插入、更新、删除三种变更类型，包含变更类型、实体标识、字段变更列表等。
 *
 * @author 萨博
 */
@Data
@Builder
public class EntityChange {
    /** 变更类型（插入/更新/删除/无变更） */
    private ChangeType type;

    /** 实体唯一标识 */
    private Object entityId;

    /** 标识字段名称 */
    private String idFieldName;

    /** 实体类类型 */
    private Class<?> entityClass;

    /** 数据对象类类型 */
    private Class<?> doClass;

    /** DO 对应的 Mapper 实例 */
    private BaseMapper<?> mapper;

    /** 实体对象实例（DO 对象） */
    private Object entity;

    /** 字段变更列表 */
    private List<FieldChange> fieldChanges;

    /**
     * 构建 INSERT 类型的实体变更。
     *
     * @param entityClass 实体类
     * @param doMetadata DO 元数据
     * @param entity DO 实例
     * @return INSERT 类型的实体变更
     */
    public static EntityChange insert(Class<?> entityClass, DoMetadata doMetadata, Object entity) {
        return EntityChange.builder().type(ChangeType.INSERT).entityClass(entityClass).doClass(doMetadata.getDoClass())
            .mapper(doMetadata.getMapper()).entity(entity).build();
    }

    /**
     * 构建 DELETE 类型的实体变更（仅主键）。
     *
     * @param entityClass 实体类
     * @param doMetadata DO 元数据
     * @param entityId 主键值
     * @return DELETE 类型的实体变更
     */
    public static EntityChange delete(Class<?> entityClass, DoMetadata doMetadata, Object entityId) {
        return EntityChange.builder().type(ChangeType.DELETE).entityId(entityId).idFieldName(doMetadata.getIdFieldName())
            .entityClass(entityClass).doClass(doMetadata.getDoClass()).mapper(doMetadata.getMapper()).build();
    }

    /**
     * 构建 UPDATE 类型的实体变更。
     *
     * @param entityClass 实体类
     * @param doMetadata DO 元数据
     * @param entityId 主键值
     * @param fieldChanges 字段变更列表
     * @return UPDATE 类型的实体变更
     */
    public static EntityChange update(Class<?> entityClass, DoMetadata doMetadata, Object entityId, List<FieldChange> fieldChanges) {
        return EntityChange.builder().type(ChangeType.UPDATE).entityId(entityId).idFieldName(doMetadata.getIdFieldName())
            .entityClass(entityClass).doClass(doMetadata.getDoClass()).mapper(doMetadata.getMapper()).fieldChanges(fieldChanges).build();
    }
}

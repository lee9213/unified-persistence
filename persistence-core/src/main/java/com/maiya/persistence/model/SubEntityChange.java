package com.maiya.persistence.model;

import lombok.Builder;
import lombok.Data;

/**
 * 子实体变更信息 记录聚合中子实体的变更详情，包括变更类型、实体标识、字段变更列表等
 *
 * @author 萨博
 */
@Data
@Builder
public class SubEntityChange {
    /** 变更类型（插入/更新/删除） */
    private ChangeType type;

    /** 实体唯一标识 */
    private Object entityId;

    /** 标识字段名称 */
    private String idFieldName;

    /** 实体类类型 */
    private Class<?> entityClass;

    /** 数据对象类类型 */
    private Class<?> doClass;

    /** 实体对象实例 */
    private Object entity;

    /** 字段变更列表 */
    private java.util.List<FieldChange> fieldChanges;
}

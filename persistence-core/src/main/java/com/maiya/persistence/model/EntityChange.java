package com.maiya.persistence.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import lombok.Builder;
import lombok.Data;

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
}

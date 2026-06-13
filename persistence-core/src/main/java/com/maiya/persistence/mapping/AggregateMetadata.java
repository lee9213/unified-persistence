package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 聚合元数据 描述聚合根实体与数据对象之间的映射关系，包括根实体信息、基础字段、子实体和子实体列表的映射配置
 *
 * @author 萨博
 */
@Data
public class AggregateMetadata {

    /** 实体类类型 */
    private Class<?> entityClass;

    /** 根数据对象类类型 */
    private Class<?> rootDoClass;

    /** 标识字段 */
    private Field idField;

    /** 根实体对应的Mapper */
    private BaseMapper<?> rootMapper;

    /** 基础字段列表（非关联的普通字段） */
    private List<Field> basicFields = new ArrayList<>();

    /** 单个子实体映射信息列表 */
    private List<SubEntityInfo> subEntities = new ArrayList<>();

    /** 子实体列表（集合类型）映射信息列表 */
    private List<SubEntityListInfo> subEntityLists = new ArrayList<>();

    /**
     * 获取标识字段的名称
     *
     * @return 标识字段名称
     */
    public String getIdFieldName() {
        return idField.getName();
    }

    /** 单个子实体映射信息 描述聚合中单个关联子实体的字段映射、类型信息和持久化配置 */
    @Data
    public static class SubEntityInfo {
        /** 实体中的关联字段 */
        private Field entityField;

        /** 子实体类类型 */
        private Class<?> entityClass;

        /** 子实体数据对象类类型 */
        private Class<?> doClass;

        /** 子实体标识字段 */
        private Field entityIdField;

        /** 子实体对应的Mapper */
        private BaseMapper<?> mapper;

        /**
         * 获取子实体标识字段的名称
         *
         * @return 标识字段名称
         */
        public String getIdFieldName() {
            return entityIdField.getName();
        }
    }

    /** 子实体列表映射信息 描述聚合中集合类型关联子实体（如List）的字段映射、类型信息和持久化配置 */
    @Data
    public static class SubEntityListInfo {
        /** 实体中的关联字段 */
        private Field entityField;

        /** 列表元素实体类类型 */
        private Class<?> elementEntityClass;

        /** 列表元素数据对象类类型 */
        private Class<?> elementDoClass;

        /** 列表元素标识字段 */
        private Field elementIdField;

        /** 列表元素对应的Mapper */
        private BaseMapper<?> mapper;

        /**
         * 获取列表元素标识字段的名称
         *
         * @return 标识字段名称
         */
        public String getIdFieldName() {
            return elementIdField.getName();
        }
    }
}

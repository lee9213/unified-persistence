package com.maiya.persistence.mapping;

import java.lang.invoke.MethodHandle;
import java.util.List;
import lombok.Data;

/**
 * 实体元数据，描述实体与数据对象之间的映射关系。
 *
 * <p>本类同时用于描述根实体和子实体，形成统一的树形递归结构。 DO 层面的元数据（主键、基本字段、Mapper）统一由 DoMetadata 持有，避免字段重复。
 *
 * @author 萨博
 */
@Data
public class EntityMetadata {

    /** 实体类类型 */
    private Class<?> entityClass;

    /** DO 元数据 */
    private DoMetadata doMetadata;

    /** 一对一子实体映射列表 */
    private List<EntityMetadata> subEntities;

    /** 一对多子实体列表映射列表 */
    private List<EntityMetadata> subEntityLists;

    /**
     * 字段访问器，封装 DO 字段名和 getter MethodHandle。
     *
     * @author 萨博
     */
    @Data
    public static class FieldAccessor {
        /** DO 字段名称 */
        private String fieldName;

        /** DO getter 方法的 MethodHandle */
        private MethodHandle getter;

        public FieldAccessor(String fieldName, MethodHandle getter) {
            this.fieldName = fieldName;
            this.getter = getter;
        }

        /**
         * 通过 MethodHandle 读取 DO 对象的字段值
         *
         * @param obj DO 对象
         * @return 字段值
         */
        public Object getValue(Object obj) {
            try {
                return getter.invoke(obj);
            } catch (Throwable e) {
                throw new RuntimeException("无法读取字段: " + fieldName, e);
            }
        }
    }
}

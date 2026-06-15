package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * DO 元数据，描述单个 DO 类的结构信息。
 *
 * <p>容器启动时由 MapperRegistry 扫描 Mapper 并预解析，运行时直接查表使用，无需重复解析。
 *
 * @author 萨博
 */
@Data
public class DoMetadata {

    /** DO 类类型 */
    private Class<?> doClass;

    /** 对应的 BaseMapper 实例 */
    private BaseMapper<?> mapper;

    /** 主键字段名称 */
    private String idFieldName;

    /** 主键字段的 getter MethodHandle */
    private MethodHandle idGetter;

    /** 基本字段访问器列表（排除主键和 @TableId 字段） */
    private List<EntityMetadata.FieldAccessor> basicFields = new ArrayList<>();

    /**
     * 读取 DO 对象的主键值
     *
     * @param doObj DO 对象
     * @return 主键值
     */
    public Object getId(Object doObj) {
        try {
            return idGetter.invoke(doObj);
        } catch (Throwable e) {
            throw new RuntimeException("无法读取 DO 主键字段: " + idFieldName, e);
        }
    }
}

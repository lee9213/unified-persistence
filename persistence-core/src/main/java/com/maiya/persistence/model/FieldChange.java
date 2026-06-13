package com.maiya.persistence.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 字段变更记录 用于记录实体中单个字段的变更信息，包括字段名、旧值和新值
 *
 * @author 萨博
 */
@Data
@AllArgsConstructor
public class FieldChange {
    /** 字段名称 */
    private String fieldName;

    /** 变更前的旧值 */
    private Object oldValue;

    /** 变更后的新值 */
    private Object newValue;
}

package com.maiya.persistence.model;

/**
 * 变更类型枚举 定义实体变更的操作类型：插入、更新、删除或无变更
 *
 * @author 萨博
 */
public enum ChangeType {
    /** 插入操作 */
    INSERT,
    /** 更新操作 */
    UPDATE,
    /** 删除操作 */
    DELETE,
    /** 无变更 */
    NONE
}

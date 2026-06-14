package com.maiya.persistence.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.model.*;
import java.io.Serializable;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 变更执行器，负责将差异引擎生成的变更集（ChangeSet）实际执行到数据库。
 *
 * <p>按照先删除、再插入、后更新、最后处理根实体的顺序执行， 确保外键约束和事务一致性。
 *
 * <p>ChangeSet 中的 entity 已经是 DO 对象（由 DiffEngine 在比对前完成转换）， 因此执行时无需再次转换，直接使用即可。
 *
 * @author 萨博
 */
@Component
public class ChangeExecutor {

    /** Mapper 注册中心，用于获取各实体对应的 BaseMapper */
    @Autowired private MapperRegistry mapperRegistry;

    /**
     * 执行变更集，按顺序处理子实体的删除、插入、更新，最后执行根实体变更。
     *
     * @param changeSet 变更集
     */
    @Transactional(rollbackFor = Exception.class)
    public void execute(ChangeSet changeSet) {
        if (changeSet.isEmpty()) {
            return;
        }

        // 先执行子实体删除，避免外键冲突
        changeSet.getSubEntityChanges().stream()
                .filter(c -> c.getType() == ChangeType.DELETE)
                .forEach(this::executeDelete);

        // 执行子实体插入
        changeSet.getSubEntityChanges().stream()
                .filter(c -> c.getType() == ChangeType.INSERT)
                .forEach(this::executeInsert);

        // 执行子实体更新
        changeSet.getSubEntityChanges().stream()
                .filter(c -> c.getType() == ChangeType.UPDATE)
                .forEach(this::executeUpdate);

        // 最后执行根实体变更
        executeRootChange(changeSet.getRootChange());
    }

    /**
     * 执行根实体变更（插入、更新、删除）。
     *
     * @param change 根实体变更描述
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeRootChange(RootChange change) {
        if (change.getType() == ChangeType.NONE) return;

        BaseMapper mapper = mapperRegistry.getMapper(change.getDoClass());

        switch (change.getType()) {
            case INSERT:
                // ChangeSet 中的 entity 已经是 DO 对象，直接插入
                mapper.insert(change.getEntity());
                break;
            case UPDATE:
                // 执行字段级别的更新
                executeFieldLevelUpdate(
                        change.getDoClass(),
                        change.getIdFieldName(),
                        change.getEntityId(),
                        change.getFieldChanges());
                break;
            case DELETE:
                // 根据主键删除
                mapper.deleteById((Serializable) change.getEntityId());
                break;
            default:
                break;
        }
    }

    /**
     * 执行字段级别的更新，使用 UpdateWrapper 构造条件更新语句。
     *
     * @param doClass DO 类
     * @param idFieldName 主键字段名
     * @param entityId 主键值
     * @param fieldChanges 变更的字段列表
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeFieldLevelUpdate(
            Class<?> doClass, String idFieldName, Object entityId, List<FieldChange> fieldChanges) {
        BaseMapper mapper = mapperRegistry.getMapper(doClass);
        UpdateWrapper wrapper = new UpdateWrapper<>();
        wrapper.eq(idFieldName, entityId);
        for (FieldChange fc : fieldChanges) {
            wrapper.set(fc.getFieldName(), fc.getNewValue());
        }
        mapper.update(null, wrapper);
    }

    /**
     * 执行子实体插入。
     *
     * @param change 子实体变更描述
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeInsert(SubEntityChange change) {
        BaseMapper mapper = mapperRegistry.getMapper(change.getDoClass());
        // ChangeSet 中的 entity 已经是 DO 对象，直接插入
        mapper.insert(change.getEntity());
    }

    /**
     * 执行子实体更新。
     *
     * @param change 子实体变更描述
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeUpdate(SubEntityChange change) {
        executeFieldLevelUpdate(
                change.getDoClass(),
                change.getIdFieldName(),
                change.getEntityId(),
                change.getFieldChanges());
    }

    /**
     * 执行子实体删除。
     *
     * @param change 子实体变更描述
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeDelete(SubEntityChange change) {
        BaseMapper mapper = mapperRegistry.getMapper(change.getDoClass());
        mapper.deleteById((Serializable) change.getEntityId());
    }
}

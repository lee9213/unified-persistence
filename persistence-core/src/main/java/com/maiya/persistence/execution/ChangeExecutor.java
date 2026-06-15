package com.maiya.persistence.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.model.ChangeType;
import com.maiya.persistence.model.EntityChange;
import com.maiya.persistence.model.FieldChange;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 变更执行器，负责将差异引擎生成的变更列表实际执行到数据库。
 *
 * <p>按 DO 类型分组执行，同一类型的变更合并为批量操作：批量删除、批量插入、逐条更新。 执行顺序：删除→插入→更新，确保外键约束和事务一致性。
 *
 * <p>变更列表中的 entity 已经是 DO 对象（由 DiffEngine 在比对前完成转换），因此执行时无需再次转换，直接使用即可。
 *
 * @author 萨博
 */
@Component
public class ChangeExecutor {

    /**
     * 执行变更列表，按 DO 类型分组，批量处理删除和插入，逐条处理更新。
     *
     * @param changes 变更列表
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Transactional(rollbackFor = Exception.class)
    public void execute(List<EntityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }

        // 按 DO 类型分组
        Map<Class<?>, List<EntityChange>> grouped =
                changes.stream().collect(Collectors.groupingBy(EntityChange::getDoClass));

        for (Map.Entry<Class<?>, List<EntityChange>> entry : grouped.entrySet()) {
            List<EntityChange> group = entry.getValue();
            BaseMapper mapper = group.get(0).getMapper();

            // 先执行删除，避免外键冲突
            List<Serializable> deleteIds =
                    group.stream()
                            .filter(c -> c.getType() == ChangeType.DELETE)
                            .map(EntityChange::getEntityId)
                            .map(id -> (Serializable) id)
                            .collect(Collectors.toList());
            if (!deleteIds.isEmpty()) {
                mapper.deleteBatchIds(deleteIds);
            }

            // 批量插入
            List<Object> insertEntities =
                    group.stream()
                            .filter(c -> c.getType() == ChangeType.INSERT)
                            .map(EntityChange::getEntity)
                            .collect(Collectors.toList());
            for (Object entity : insertEntities) {
                mapper.insert(entity);
            }

            // 逐条更新（每条更新的字段可能不同）
            group.stream()
                    .filter(c -> c.getType() == ChangeType.UPDATE)
                    .forEach(
                            c ->
                                    executeFieldLevelUpdate(
                                            mapper,
                                            c.getIdFieldName(),
                                            c.getEntityId(),
                                            c.getFieldChanges()));
        }
    }

    /**
     * 执行字段级别的更新，使用 UpdateWrapper 构造条件更新语句。
     *
     * @param mapper BaseMapper 实例
     * @param idFieldName 主键字段名
     * @param entityId 主键值
     * @param fieldChanges 变更的字段列表
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeFieldLevelUpdate(
            BaseMapper mapper,
            String idFieldName,
            Object entityId,
            List<FieldChange> fieldChanges) {
        UpdateWrapper wrapper = new UpdateWrapper<>();
        wrapper.eq(idFieldName, entityId);
        for (FieldChange fc : fieldChanges) {
            wrapper.set(fc.getFieldName(), fc.getNewValue());
        }
        mapper.update(null, wrapper);
    }
}

package com.maiya.persistence.diff;

import com.maiya.persistence.mapping.DoMetadata;
import com.maiya.persistence.mapping.EntityMetadata;
import com.maiya.persistence.mapping.EntityMetadata.FieldAccessor;
import com.maiya.persistence.mapping.EntityMetadataResolver;
import com.maiya.persistence.model.ChangeType;
import com.maiya.persistence.model.EntityChange;
import com.maiya.persistence.model.FieldChange;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;

import java.util.*;

/**
 * 差异引擎，负责对比两个聚合实体（before 和 after），生成变更列表。
 *
 * <p>流程：先通过 EntityMetadataResolver 获取类级模板，将 Entity 转为 DO，然后在 DO 层面比对差异。 变更列表中的 entity 直接就是 DO
 * 对象，ChangeExecutor 无需再次转换。
 *
 * @author 萨博
 */
@RequiredArgsConstructor
public class DiffEngine {

    private final EntityMetadataResolver entityMetadataResolver;
    private final Converter converter;

    /**
     * 对比两个实体对象，生成变更列表。先将 Entity 转为 DO，再在 DO 层面比对。
     *
     * @param before 变更前的实体对象（可为 null）
     * @param after 变更后的实体对象（可为 null）
     * @return 变更列表
     */
    public List<EntityChange> diff(Object before, Object after) {
        List<EntityChange> changes = new ArrayList<>();
        if (before == null && after == null) {
            return changes;
        }

        // 新增场景
        if (before == null) {
            EntityMetadata metadata = entityMetadataResolver.resolve(after.getClass());
            Object doValue = converter.convert(after, metadata.getDoMetadata().getDoClass());
            changes.add(EntityChange.insert(metadata.getEntityClass(), metadata.getDoMetadata(), doValue));
            collectInserts(after, metadata, changes);
            return changes;
        }

        // 删除场景
        if (after == null) {
            EntityMetadata metadata = entityMetadataResolver.resolve(before.getClass());
            Object doValue = converter.convert(before, metadata.getDoMetadata().getDoClass());
            Object id = metadata.getDoMetadata().getId(doValue);
            changes.add(EntityChange.delete(metadata.getEntityClass(), metadata.getDoMetadata(), id));
            collectDeletes(before, metadata, changes);
            return changes;
        }

        // 更新场景：对比两个实体
        EntityMetadata metadata = entityMetadataResolver.resolve(before.getClass());
        Object beforeDO = converter.convert(before, metadata.getDoMetadata().getDoClass());
        Object afterDO = converter.convert(after, metadata.getDoMetadata().getDoClass());

        diffRoot(metadata, beforeDO, afterDO, changes);
        diffSubEntities(before, after, metadata, changes);

        return changes;
    }

    /** 对比根 DO 的基本字段差异 */
    private void diffRoot(EntityMetadata metadata, Object beforeDO, Object afterDO, List<EntityChange> changes) {
        DoMetadata doMeta = metadata.getDoMetadata();
        List<FieldChange> fieldChanges = diffDoFields(beforeDO, afterDO, doMeta.getBasicFields());

        if (fieldChanges.isEmpty()) {
            changes.add(EntityChange.builder().type(ChangeType.NONE).entityClass(metadata.getEntityClass()).doClass(doMeta.getDoClass()).build());
        } else {
            Object id = doMeta.getId(afterDO);
            changes.add(EntityChange.update(metadata.getEntityClass(), doMeta, id, fieldChanges));
        }
    }

    /** 递归对比子实体差异 */
    private void diffSubEntities(Object before, Object after, EntityMetadata metadata, List<EntityChange> changes) {
        for (EntityMetadata subMeta : Optional.ofNullable(metadata.getSubEntities()).orElse(List.of())) {
            Object oldSub = subMeta.readFromParent(before);
            Object newSub = subMeta.readFromParent(after);
            diffOneSubEntity(oldSub, newSub, subMeta, changes);
        }
        for (EntityMetadata listMeta : Optional.ofNullable(metadata.getSubEntityLists()).orElse(List.of())) {
            List<?> oldList = readSubEntityList(listMeta.readFromParent(before));
            List<?> newList = readSubEntityList(listMeta.readFromParent(after));
            diffSubEntityList(oldList, newList, listMeta, changes);
        }
    }

    /** 对比单个子实体 */
    private void diffOneSubEntity(Object oldSub, Object newSub, EntityMetadata subMeta, List<EntityChange> changes) {
        if (oldSub == null && newSub == null) {
            return;
        }
        DoMetadata doMeta = subMeta.getDoMetadata();
        if (oldSub != null && newSub == null) {
            // 删除
            Object doValue = converter.convert(oldSub, doMeta.getDoClass());
            Object id = doMeta.getId(doValue);
            changes.add(EntityChange.delete(subMeta.getEntityClass(), doMeta, id));
            collectDeletes(oldSub, subMeta, changes);
        } else if (oldSub == null) {
            // 新增
            Object doValue = converter.convert(newSub, doMeta.getDoClass());
            changes.add(EntityChange.insert(subMeta.getEntityClass(), doMeta, doValue));
            collectInserts(newSub, subMeta, changes);
        } else {
            // 更新
            Object oldDO = converter.convert(oldSub, doMeta.getDoClass());
            Object newDO = converter.convert(newSub, doMeta.getDoClass());
            List<FieldChange> fieldChanges = diffDoFields(oldDO, newDO, doMeta.getBasicFields());
            if (!fieldChanges.isEmpty()) {
                Object id = doMeta.getId(newDO);
                changes.add(EntityChange.update(subMeta.getEntityClass(), doMeta, id, fieldChanges));
            }
            // 递归比对嵌套子实体
            diffSubEntities(oldSub, newSub, subMeta, changes);
        }
    }

    /** 对比子实体列表，按主键映射后检测增删改 */
    private void diffSubEntityList(List<?> oldList, List<?> newList, EntityMetadata listMeta, List<EntityChange> changes) {
        DoMetadata doMeta = listMeta.getDoMetadata();
        Map<Object, MappedItem> oldMap = toIdMap(oldList, doMeta);
        Map<Object, MappedItem> newMap = toIdMap(newList, doMeta);

        // 遍历旧列表，检测删除和更新
        for (Object id : oldMap.keySet()) {
            MappedItem oldItem = oldMap.get(id);
            if (!newMap.containsKey(id)) {
                // 删除
                changes.add(EntityChange.delete(listMeta.getEntityClass(), doMeta, id));
                collectDeletes(oldItem.entity(), listMeta, changes);
            } else {
                // 更新
                MappedItem newItem = newMap.get(id);
                List<FieldChange> fieldChanges = diffDoFields(oldItem.doValue(), newItem.doValue(), doMeta.getBasicFields());
                if (!fieldChanges.isEmpty()) {
                    changes.add(EntityChange.update(listMeta.getEntityClass(), doMeta, id, fieldChanges));
                }
                diffSubEntities(oldItem.entity(), newItem.entity(), listMeta, changes);
            }
        }

        // 遍历新列表，检测新增
        for (Object id : newMap.keySet()) {
            if (oldMap.containsKey(id)) {
                continue;
            }
            MappedItem newItem = newMap.get(id);
            changes.add(EntityChange.insert(listMeta.getEntityClass(), doMeta, newItem.doValue()));
            collectInserts(newItem.entity(), listMeta, changes);
        }
    }

    /** 对比两个 DO 对象的基本字段差异 */
    private List<FieldChange> diffDoFields(Object oldDO, Object newDO, List<FieldAccessor> basicFields) {
        List<FieldChange> changes = new ArrayList<>();
        for (FieldAccessor accessor : basicFields) {
            Object oldVal = accessor.getValue(oldDO);
            Object newVal = accessor.getValue(newDO);
            if (!Objects.equals(oldVal, newVal)) {
                changes.add(new FieldChange(accessor.getFieldName(), oldVal, newVal));
            }
        }
        return changes;
    }

    /** 将实体列表按主键映射为 Entity 与 DO 的组合项 */
    private Map<Object, MappedItem> toIdMap(List<?> entities, DoMetadata doMeta) {
        if (entities == null) {
            return Collections.emptyMap();
        }
        Map<Object, MappedItem> map = new LinkedHashMap<>();
        for (Object entity : entities) {
            Object doValue = converter.convert(entity, doMeta.getDoClass());
            Object id = doMeta.getId(doValue);
            map.put(id, new MappedItem(entity, doValue));
        }
        return map;
    }

    /**
     * 按主键索引的实体与 DO 映射项。
     *
     * @author 萨博
     */
    private record MappedItem(Object entity, Object doValue) {}

    /** 递归收集实体中所有子实体的插入变更 */
    private void collectInserts(Object entity, EntityMetadata metadata, List<EntityChange> changes) {
        for (EntityMetadata subMeta : Optional.ofNullable(metadata.getSubEntities()).orElse(List.of())) {
            Object subEntity = subMeta.readFromParent(entity);
            if (subEntity == null) {
                continue;
            }
            DoMetadata doMeta = subMeta.getDoMetadata();
            changes.add(EntityChange.insert(subMeta.getEntityClass(), doMeta, converter.convert(subEntity, doMeta.getDoClass())));
            collectInserts(subEntity, subMeta, changes);
        }
        for (EntityMetadata listMeta : Optional.ofNullable(metadata.getSubEntityLists()).orElse(List.of())) {
            List<?> subEntities = readSubEntityList(listMeta.readFromParent(entity));
            if (subEntities == null) {
                continue;
            }
            for (Object subEntity : subEntities) {
                DoMetadata doMeta = listMeta.getDoMetadata();
                changes.add(EntityChange.insert(listMeta.getEntityClass(), doMeta, converter.convert(subEntity, doMeta.getDoClass())));
                collectInserts(subEntity, listMeta, changes);
            }
        }
    }

    /** 递归收集实体中所有子实体的删除变更 */
    private void collectDeletes(Object entity, EntityMetadata metadata, List<EntityChange> changes) {
        for (EntityMetadata subMeta : Optional.ofNullable(metadata.getSubEntities()).orElse(List.of())) {
            Object subEntity = subMeta.readFromParent(entity);
            if (subEntity == null) {
                continue;
            }
            DoMetadata doMeta = subMeta.getDoMetadata();
            Object doValue = converter.convert(subEntity, doMeta.getDoClass());
            Object id = doMeta.getId(doValue);
            changes.add(EntityChange.delete(subMeta.getEntityClass(), doMeta, id));
            collectDeletes(subEntity, subMeta, changes);
        }
        for (EntityMetadata listMeta : Optional.ofNullable(metadata.getSubEntityLists()).orElse(List.of())) {
            List<?> subEntities = readSubEntityList(listMeta.readFromParent(entity));
            if (subEntities == null) {
                continue;
            }
            for (Object subEntity : subEntities) {
                DoMetadata doMeta = listMeta.getDoMetadata();
                Object doValue = converter.convert(subEntity, doMeta.getDoClass());
                Object id = doMeta.getId(doValue);
                changes.add(EntityChange.delete(listMeta.getEntityClass(), doMeta, id));
                collectDeletes(subEntity, listMeta, changes);
            }
        }
    }

    /** 将父实体字段值规范化为子实体列表 */
    @SuppressWarnings("unchecked")
    private List<?> readSubEntityList(Object value) {
        if (value == null) {
            return null;
        }
        return (List<?>) value;
    }
}

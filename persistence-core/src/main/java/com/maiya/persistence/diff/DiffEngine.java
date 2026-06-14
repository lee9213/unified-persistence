package com.maiya.persistence.diff;

import com.maiya.persistence.mapping.DoMetadata;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.EntityMetadata;
import com.maiya.persistence.mapping.EntityMetadata.FieldAccessor;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.model.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 差异引擎，负责对比两个聚合实体对象（before 和 after），生成变更集（ChangeSet）。
 *
 * <p>比对流程：先将 Entity 转换为 DO，再在 DO 层面进行字段级差异检测。 支持递归比对嵌套子实体。ChangeSet 中的 entity 直接就是 DO
 * 对象，ChangeExecutor 无需再次转换。
 *
 * @author 萨博
 */
@Component
public class DiffEngine {

    @Autowired private MetadataResolver metadataResolver;

    @Autowired private EntityCopier entityCopier;

    /**
     * 对比两个实体对象，生成变更集。先将 Entity 转 DO，再在 DO 层面比对。
     *
     * @param before 变更前的实体对象（可为 null）
     * @param after 变更后的实体对象（可为 null）
     * @return 变更集
     */
    public ChangeSet diff(Object before, Object after) {
        ChangeSet changeSet = new ChangeSet();

        if (before == null && after == null) {
            return changeSet;
        }

        Class<?> entityClass = after != null ? after.getClass() : before.getClass();
        EntityMetadata metadata = metadataResolver.resolve(entityClass);
        DoMetadata doMeta = metadata.getDoMetadata();

        // 先将 Entity 转换为 DO
        Object beforeDO = before != null ? entityCopier.toDO(before, doMeta.getDoClass()) : null;
        Object afterDO = after != null ? entityCopier.toDO(after, doMeta.getDoClass()) : null;

        // 新增场景：before 为 null，after 不为 null
        if (beforeDO == null && afterDO != null) {
            changeSet.setRootChange(
                    RootChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(metadata.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .entity(afterDO)
                            .build());
            addSubEntityInserts(
                    after, metadata.getSubEntities(), metadata.getSubEntityLists(), changeSet);
            return changeSet;
        }

        // 删除场景：before 不为 null，after 为 null
        if (beforeDO != null && afterDO == null) {
            Object id = doMeta.getId(beforeDO);
            changeSet.setRootChange(
                    RootChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(doMeta.getIdFieldName())
                            .entityClass(metadata.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .build());
            addSubEntityDeletes(
                    before, metadata.getSubEntities(), metadata.getSubEntityLists(), changeSet);
            return changeSet;
        }

        // 更新场景：对比 before DO 和 after DO 的差异
        RootChange rootChange = diffRootDO(beforeDO, afterDO, metadata);
        changeSet.setRootChange(rootChange);
        diffSubEntities(
                before, after, metadata.getSubEntities(), metadata.getSubEntityLists(), changeSet);

        return changeSet;
    }

    /** 对比根 DO 的基本字段差异 */
    private RootChange diffRootDO(Object beforeDO, Object afterDO, EntityMetadata metadata) {
        DoMetadata doMeta = metadata.getDoMetadata();
        List<FieldChange> fieldChanges = new ArrayList<>();
        for (FieldAccessor accessor : doMeta.getBasicFields()) {
            Object oldVal = accessor.getValue(beforeDO);
            Object newVal = accessor.getValue(afterDO);
            if (!Objects.equals(oldVal, newVal)) {
                fieldChanges.add(new FieldChange(accessor.getFieldName(), oldVal, newVal));
            }
        }
        if (fieldChanges.isEmpty()) {
            return RootChange.builder()
                    .type(ChangeType.NONE)
                    .entityClass(metadata.getEntityClass())
                    .doClass(doMeta.getDoClass())
                    .build();
        }
        return RootChange.builder()
                .type(ChangeType.UPDATE)
                .idFieldName(doMeta.getIdFieldName())
                .entityClass(metadata.getEntityClass())
                .doClass(doMeta.getDoClass())
                .fieldChanges(fieldChanges)
                .build();
    }

    /** 递归对比子实体和子实体列表的差异 */
    private void diffSubEntities(
            Object before,
            Object after,
            List<EntityMetadata> subEntities,
            List<EntityMetadata> subEntityLists,
            ChangeSet changeSet) {

        // 对比一对一子实体
        for (EntityMetadata subMeta : subEntities) {
            DoMetadata subDoMeta = subMeta.getDoMetadata();
            Object oldSubEntity = getSubEntityValue(before, subMeta.getEntityClass());
            Object newSubEntity = getSubEntityValue(after, subMeta.getEntityClass());
            Object oldSubDO =
                    oldSubEntity != null
                            ? entityCopier.toDO(oldSubEntity, subDoMeta.getDoClass())
                            : null;
            Object newSubDO =
                    newSubEntity != null
                            ? entityCopier.toDO(newSubEntity, subDoMeta.getDoClass())
                            : null;

            if (oldSubDO != null && newSubDO == null) {
                Object id = subDoMeta.getId(oldSubDO);
                changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                                .type(ChangeType.DELETE)
                                .entityId(id)
                                .idFieldName(subDoMeta.getIdFieldName())
                                .entityClass(subMeta.getEntityClass())
                                .doClass(subDoMeta.getDoClass())
                                .entity(oldSubDO)
                                .build());
                addSubEntityDeletes(
                        oldSubEntity,
                        subMeta.getSubEntities(),
                        subMeta.getSubEntityLists(),
                        changeSet);
            } else if (oldSubDO == null && newSubDO != null) {
                changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                                .type(ChangeType.INSERT)
                                .entityClass(subMeta.getEntityClass())
                                .doClass(subDoMeta.getDoClass())
                                .entity(newSubDO)
                                .build());
                addSubEntityInserts(
                        newSubEntity,
                        subMeta.getSubEntities(),
                        subMeta.getSubEntityLists(),
                        changeSet);
            } else if (oldSubDO != null && newSubDO != null) {
                List<FieldChange> changes =
                        diffDoFields(oldSubDO, newSubDO, subDoMeta.getBasicFields());
                if (!changes.isEmpty()) {
                    Object id = subDoMeta.getId(newSubDO);
                    changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                    .type(ChangeType.UPDATE)
                                    .entityId(id)
                                    .idFieldName(subDoMeta.getIdFieldName())
                                    .entityClass(subMeta.getEntityClass())
                                    .doClass(subDoMeta.getDoClass())
                                    .entity(newSubDO)
                                    .fieldChanges(changes)
                                    .build());
                }
                diffSubEntities(
                        oldSubEntity,
                        newSubEntity,
                        subMeta.getSubEntities(),
                        subMeta.getSubEntityLists(),
                        changeSet);
            }
        }

        // 对比一对多子实体列表
        for (EntityMetadata listMeta : subEntityLists) {
            DoMetadata subDoMeta = listMeta.getDoMetadata();
            List<?> oldSubEntities = getSubEntityListValue(before, listMeta.getEntityClass());
            List<?> newSubEntities = getSubEntityListValue(after, listMeta.getEntityClass());
            List<Object> oldSubDOs = toDOList(oldSubEntities, subDoMeta.getDoClass());
            List<Object> newSubDOs = toDOList(newSubEntities, subDoMeta.getDoClass());

            Map<Object, Object> oldMap = toIdMap(oldSubDOs, subDoMeta);
            Map<Object, Object> newMap = toIdMap(newSubDOs, subDoMeta);

            // 检测删除
            for (Object id : oldMap.keySet()) {
                if (!newMap.containsKey(id)) {
                    changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                    .type(ChangeType.DELETE)
                                    .entityId(id)
                                    .idFieldName(subDoMeta.getIdFieldName())
                                    .entityClass(listMeta.getEntityClass())
                                    .doClass(subDoMeta.getDoClass())
                                    .entity(oldMap.get(id))
                                    .build());
                    Object oldSubEntity = findEntityById(oldSubEntities, id, subDoMeta);
                    addSubEntityDeletes(
                            oldSubEntity,
                            listMeta.getSubEntities(),
                            listMeta.getSubEntityLists(),
                            changeSet);
                }
            }
            // 检测新增
            for (Object id : newMap.keySet()) {
                if (!oldMap.containsKey(id)) {
                    changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                    .type(ChangeType.INSERT)
                                    .entityClass(listMeta.getEntityClass())
                                    .doClass(subDoMeta.getDoClass())
                                    .entity(newMap.get(id))
                                    .build());
                    Object newSubEntity = findEntityById(newSubEntities, id, subDoMeta);
                    addSubEntityInserts(
                            newSubEntity,
                            listMeta.getSubEntities(),
                            listMeta.getSubEntityLists(),
                            changeSet);
                }
            }
            // 检测更新
            for (Object id : oldMap.keySet()) {
                if (newMap.containsKey(id)) {
                    List<FieldChange> changes =
                            diffDoFields(
                                    oldMap.get(id), newMap.get(id), subDoMeta.getBasicFields());
                    if (!changes.isEmpty()) {
                        changeSet.addSubEntityChange(
                                SubEntityChange.builder()
                                        .type(ChangeType.UPDATE)
                                        .entityId(id)
                                        .idFieldName(subDoMeta.getIdFieldName())
                                        .entityClass(listMeta.getEntityClass())
                                        .doClass(subDoMeta.getDoClass())
                                        .entity(newMap.get(id))
                                        .fieldChanges(changes)
                                        .build());
                    }
                    Object oldSubEntity = findEntityById(oldSubEntities, id, subDoMeta);
                    Object newSubEntity = findEntityById(newSubEntities, id, subDoMeta);
                    diffSubEntities(
                            oldSubEntity,
                            newSubEntity,
                            listMeta.getSubEntities(),
                            listMeta.getSubEntityLists(),
                            changeSet);
                }
            }
        }
    }

    private List<FieldChange> diffDoFields(
            Object oldDO, Object newDO, List<FieldAccessor> basicFields) {
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

    private List<Object> toDOList(List<?> entities, Class<?> doClass) {
        if (entities == null) return Collections.emptyList();
        List<Object> result = new ArrayList<>();
        for (Object entity : entities) {
            result.add(entityCopier.toDO(entity, doClass));
        }
        return result;
    }

    private Map<Object, Object> toIdMap(List<Object> doList, DoMetadata doMeta) {
        if (doList == null) return Collections.emptyMap();
        Map<Object, Object> map = new LinkedHashMap<>();
        for (Object doObj : doList) {
            Object id = doMeta.getId(doObj);
            map.put(id, doObj);
        }
        return map;
    }

    private Object findEntityById(List<?> entities, Object id, DoMetadata doMeta) {
        if (entities == null) return null;
        for (Object entity : entities) {
            Object doObj = entityCopier.toDO(entity, doMeta.getDoClass());
            Object entityId = doMeta.getId(doObj);
            if (Objects.equals(entityId, id)) {
                return entity;
            }
        }
        return null;
    }

    private Object getSubEntityValue(Object rootEntity, Class<?> entityClass) {
        if (rootEntity == null) return null;
        try {
            for (java.lang.reflect.Field field : rootEntity.getClass().getDeclaredFields()) {
                if (field.getType().equals(entityClass)) {
                    String getterName =
                            "get"
                                    + Character.toUpperCase(field.getName().charAt(0))
                                    + field.getName().substring(1);
                    java.lang.reflect.Method getter =
                            rootEntity.getClass().getDeclaredMethod(getterName);
                    return getter.invoke(rootEntity);
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("无法读取子实体字段: " + entityClass.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> getSubEntityListValue(Object rootEntity, Class<?> entityClass) {
        if (rootEntity == null) return null;
        try {
            for (java.lang.reflect.Field field : rootEntity.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    java.lang.reflect.ParameterizedType pt =
                            (java.lang.reflect.ParameterizedType) field.getGenericType();
                    Class<?> elementClass = (Class<?>) pt.getActualTypeArguments()[0];
                    if (elementClass.equals(entityClass)) {
                        String getterName =
                                "get"
                                        + Character.toUpperCase(field.getName().charAt(0))
                                        + field.getName().substring(1);
                        java.lang.reflect.Method getter =
                                rootEntity.getClass().getDeclaredMethod(getterName);
                        return (List<?>) getter.invoke(rootEntity);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("无法读取子实体列表字段: " + entityClass.getName(), e);
        }
    }

    private void addSubEntityInserts(
            Object after,
            List<EntityMetadata> subEntities,
            List<EntityMetadata> subEntityLists,
            ChangeSet changeSet) {
        for (EntityMetadata subMeta : subEntities) {
            DoMetadata subDoMeta = subMeta.getDoMetadata();
            Object subEntity = getSubEntityValue(after, subMeta.getEntityClass());
            if (subEntity != null) {
                Object subDO = entityCopier.toDO(subEntity, subDoMeta.getDoClass());
                changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                                .type(ChangeType.INSERT)
                                .entityClass(subMeta.getEntityClass())
                                .doClass(subDoMeta.getDoClass())
                                .entity(subDO)
                                .build());
                addSubEntityInserts(
                        subEntity,
                        subMeta.getSubEntities(),
                        subMeta.getSubEntityLists(),
                        changeSet);
            }
        }
        for (EntityMetadata listMeta : subEntityLists) {
            DoMetadata subDoMeta = listMeta.getDoMetadata();
            List<?> subEntitiesList = getSubEntityListValue(after, listMeta.getEntityClass());
            if (subEntitiesList != null) {
                for (Object subEntity : subEntitiesList) {
                    Object subDO = entityCopier.toDO(subEntity, subDoMeta.getDoClass());
                    changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                    .type(ChangeType.INSERT)
                                    .entityClass(listMeta.getEntityClass())
                                    .doClass(subDoMeta.getDoClass())
                                    .entity(subDO)
                                    .build());
                    addSubEntityInserts(
                            subEntity,
                            listMeta.getSubEntities(),
                            listMeta.getSubEntityLists(),
                            changeSet);
                }
            }
        }
    }

    private void addSubEntityDeletes(
            Object before,
            List<EntityMetadata> subEntities,
            List<EntityMetadata> subEntityLists,
            ChangeSet changeSet) {
        for (EntityMetadata subMeta : subEntities) {
            DoMetadata subDoMeta = subMeta.getDoMetadata();
            Object subEntity = getSubEntityValue(before, subMeta.getEntityClass());
            if (subEntity != null) {
                Object subDO = entityCopier.toDO(subEntity, subDoMeta.getDoClass());
                Object id = subDoMeta.getId(subDO);
                changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                                .type(ChangeType.DELETE)
                                .entityId(id)
                                .idFieldName(subDoMeta.getIdFieldName())
                                .entityClass(subMeta.getEntityClass())
                                .doClass(subDoMeta.getDoClass())
                                .entity(subDO)
                                .build());
                addSubEntityDeletes(
                        subEntity,
                        subMeta.getSubEntities(),
                        subMeta.getSubEntityLists(),
                        changeSet);
            }
        }
        for (EntityMetadata listMeta : subEntityLists) {
            DoMetadata subDoMeta = listMeta.getDoMetadata();
            List<?> subEntitiesList = getSubEntityListValue(before, listMeta.getEntityClass());
            if (subEntitiesList != null) {
                for (Object subEntity : subEntitiesList) {
                    Object subDO = entityCopier.toDO(subEntity, subDoMeta.getDoClass());
                    Object id = subDoMeta.getId(subDO);
                    changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                    .type(ChangeType.DELETE)
                                    .entityId(id)
                                    .idFieldName(subDoMeta.getIdFieldName())
                                    .entityClass(listMeta.getEntityClass())
                                    .doClass(subDoMeta.getDoClass())
                                    .entity(subDO)
                                    .build());
                    addSubEntityDeletes(
                            subEntity,
                            listMeta.getSubEntities(),
                            listMeta.getSubEntityLists(),
                            changeSet);
                }
            }
        }
    }
}

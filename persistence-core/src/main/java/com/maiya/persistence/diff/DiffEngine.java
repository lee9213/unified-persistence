package com.maiya.persistence.diff;

import com.maiya.persistence.mapping.DoMetadata;
import com.maiya.persistence.mapping.EntityMetadata;
import com.maiya.persistence.mapping.EntityMetadata.FieldAccessor;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.model.ChangeType;
import com.maiya.persistence.model.EntityChange;
import com.maiya.persistence.model.FieldChange;
import io.github.linpeilie.Converter;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 差异引擎，负责对比两个聚合实体（before 和 after），生成变更列表。
 *
 * <p>流程：先通过 MetadataResolver 获取类级模板，将 Entity 转为 DO，然后在 DO 层面比对差异。 变更列表中的 entity 直接就是 DO
 * 对象，ChangeExecutor 无需再次转换。
 *
 * @author 萨博
 */
@Component
public class DiffEngine {

    @Autowired private MetadataResolver metadataResolver;

    @Autowired private Converter converter;

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
        if (before == null && after != null) {
            EntityMetadata metadata = metadataResolver.resolve(after.getClass());
            Object doValue = converter.convert(after, metadata.getDoMetadata().getDoClass());
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(metadata.getEntityClass())
                            .doClass(metadata.getDoMetadata().getDoClass())
                            .mapper(metadata.getDoMetadata().getMapper())
                            .entity(doValue)
                            .build());
            collectInserts(after, metadata, changes);
            return changes;
        }

        // 删除场景
        if (before != null && after == null) {
            EntityMetadata metadata = metadataResolver.resolve(before.getClass());
            Object doValue = converter.convert(before, metadata.getDoMetadata().getDoClass());
            Object id = metadata.getDoMetadata().getId(doValue);
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(metadata.getDoMetadata().getIdFieldName())
                            .entityClass(metadata.getEntityClass())
                            .doClass(metadata.getDoMetadata().getDoClass())
                            .build());
            collectDeletes(before, metadata, changes);
            return changes;
        }

        // 更新场景：对比两个实体
        EntityMetadata metadata = metadataResolver.resolve(before.getClass());
        Object beforeDO = converter.convert(before, metadata.getDoMetadata().getDoClass());
        Object afterDO = converter.convert(after, metadata.getDoMetadata().getDoClass());

        diffRoot(metadata, beforeDO, afterDO, changes);
        diffSubEntities(before, after, metadata, changes);

        return changes;
    }

    /** 对比根 DO 的基本字段差异 */
    private void diffRoot(
            EntityMetadata metadata, Object beforeDO, Object afterDO, List<EntityChange> changes) {
        DoMetadata doMeta = metadata.getDoMetadata();
        List<FieldChange> fieldChanges = diffDoFields(beforeDO, afterDO, doMeta.getBasicFields());

        if (fieldChanges.isEmpty()) {
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.NONE)
                            .entityClass(metadata.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .build());
        } else {
            Object id = doMeta.getId(afterDO);
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.UPDATE)
                            .entityId(id)
                            .idFieldName(doMeta.getIdFieldName())
                            .entityClass(metadata.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .mapper(doMeta.getMapper())
                            .fieldChanges(fieldChanges)
                            .build());
        }
    }

    /** 递归对比子实体差异 */
    private void diffSubEntities(
            Object before, Object after, EntityMetadata metadata, List<EntityChange> changes) {
        List<EntityMetadata> subMetas = metadata.getSubEntities();
        List<EntityMetadata> listMetas = metadata.getSubEntityLists();

        // 对比一对一子实体
        if (subMetas != null) {
            for (EntityMetadata subMeta : subMetas) {
                Object oldSub = getSubEntityValue(before, subMeta.getEntityClass());
                Object newSub = getSubEntityValue(after, subMeta.getEntityClass());
                diffOneSubEntity(oldSub, newSub, subMeta, changes);
            }
        }

        // 对比一对多子实体列表
        if (listMetas != null) {
            for (EntityMetadata listMeta : listMetas) {
                List<?> oldList = getSubEntityListValue(before, listMeta.getEntityClass());
                List<?> newList = getSubEntityListValue(after, listMeta.getEntityClass());
                diffSubEntityList(oldList, newList, listMeta, changes);
            }
        }
    }

    /** 对比单个子实体 */
    private void diffOneSubEntity(
            Object oldSub, Object newSub, EntityMetadata subMeta, List<EntityChange> changes) {
        DoMetadata doMeta = subMeta.getDoMetadata();

        if (oldSub != null && newSub == null) {
            // 删除
            Object doValue = converter.convert(oldSub, doMeta.getDoClass());
            Object id = doMeta.getId(doValue);
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(doMeta.getIdFieldName())
                            .entityClass(subMeta.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .mapper(doMeta.getMapper())
                            .entity(doValue)
                            .build());
            collectDeletes(oldSub, subMeta, changes);
        } else if (oldSub == null && newSub != null) {
            // 新增
            Object doValue = converter.convert(newSub, doMeta.getDoClass());
            changes.add(
                    EntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(subMeta.getEntityClass())
                            .doClass(doMeta.getDoClass())
                            .mapper(doMeta.getMapper())
                            .entity(doValue)
                            .build());
            collectInserts(newSub, subMeta, changes);
        } else if (oldSub != null && newSub != null) {
            // 更新
            Object oldDO = converter.convert(oldSub, doMeta.getDoClass());
            Object newDO = converter.convert(newSub, doMeta.getDoClass());
            List<FieldChange> fieldChanges = diffDoFields(oldDO, newDO, doMeta.getBasicFields());
            if (!fieldChanges.isEmpty()) {
                Object id = doMeta.getId(newDO);
                changes.add(
                        EntityChange.builder()
                                .type(ChangeType.UPDATE)
                                .entityId(id)
                                .idFieldName(doMeta.getIdFieldName())
                                .entityClass(subMeta.getEntityClass())
                                .doClass(doMeta.getDoClass())
                                .mapper(doMeta.getMapper())
                                .entity(newDO)
                                .fieldChanges(fieldChanges)
                                .build());
            }
            // 递归比对嵌套子实体
            diffSubEntities(oldSub, newSub, subMeta, changes);
        }
    }

    /** 对比子实体列表，按主键映射后检测增删改 */
    private void diffSubEntityList(
            List<?> oldList, List<?> newList, EntityMetadata listMeta, List<EntityChange> changes) {
        DoMetadata doMeta = listMeta.getDoMetadata();
        Map<Object, Object> oldMap = toIdMap(oldList, doMeta);
        Map<Object, Object> newMap = toIdMap(newList, doMeta);

        // 遍历旧列表，检测删除和更新
        for (Object id : oldMap.keySet()) {
            if (!newMap.containsKey(id)) {
                // 删除
                changes.add(
                        EntityChange.builder()
                                .type(ChangeType.DELETE)
                                .entityId(id)
                                .idFieldName(doMeta.getIdFieldName())
                                .entityClass(listMeta.getEntityClass())
                                .doClass(doMeta.getDoClass())
                                .entity(oldMap.get(id))
                                .build());
                Object oldEntity = findEntityById(oldList, id, doMeta);
                if (oldEntity != null) {
                    collectDeletes(oldEntity, listMeta, changes);
                }
            } else {
                // 更新
                Object oldDO = oldMap.get(id);
                Object newDO = newMap.get(id);
                List<FieldChange> fieldChanges =
                        diffDoFields(oldDO, newDO, doMeta.getBasicFields());
                if (!fieldChanges.isEmpty()) {
                    changes.add(
                            EntityChange.builder()
                                    .type(ChangeType.UPDATE)
                                    .entityId(id)
                                    .idFieldName(doMeta.getIdFieldName())
                                    .entityClass(listMeta.getEntityClass())
                                    .doClass(doMeta.getDoClass())
                                    .entity(newDO)
                                    .fieldChanges(fieldChanges)
                                    .build());
                }
                Object oldEntity = findEntityById(oldList, id, doMeta);
                Object newEntity = findEntityById(newList, id, doMeta);
                if (oldEntity != null && newEntity != null) {
                    diffSubEntities(oldEntity, newEntity, listMeta, changes);
                }
            }
        }

        // 遍历新列表，检测新增
        for (Object id : newMap.keySet()) {
            if (!oldMap.containsKey(id)) {
                changes.add(
                        EntityChange.builder()
                                .type(ChangeType.INSERT)
                                .entityClass(listMeta.getEntityClass())
                                .doClass(doMeta.getDoClass())
                                .mapper(doMeta.getMapper())
                                .entity(newMap.get(id))
                                .build());
                Object newEntity = findEntityById(newList, id, doMeta);
                if (newEntity != null) {
                    collectInserts(newEntity, listMeta, changes);
                }
            }
        }
    }

    /** 对比两个 DO 对象的基本字段差异 */
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

    /** 将实体列表转为 DO 后按主键映射为 Map */
    private Map<Object, Object> toIdMap(List<?> entities, DoMetadata doMeta) {
        if (entities == null) return Collections.emptyMap();
        Map<Object, Object> map = new LinkedHashMap<>();
        for (Object entity : entities) {
            Object doValue = converter.convert(entity, doMeta.getDoClass());
            Object id = doMeta.getId(doValue);
            map.put(id, doValue);
        }
        return map;
    }

    /** 从实体列表中根据主键值查找对应的原始 Entity 对象 */
    private Object findEntityById(List<?> entities, Object id, DoMetadata doMeta) {
        if (entities == null) return null;
        for (Object entity : entities) {
            Object doValue = converter.convert(entity, doMeta.getDoClass());
            Object entityId = doMeta.getId(doValue);
            if (Objects.equals(entityId, id)) {
                return entity;
            }
        }
        return null;
    }

    /** 递归收集实体中所有子实体的插入变更 */
    private void collectInserts(
            Object entity, EntityMetadata metadata, List<EntityChange> changes) {
        List<EntityMetadata> subMetas = metadata.getSubEntities();
        if (subMetas != null) {
            for (EntityMetadata subMeta : subMetas) {
                Object subEntity = getSubEntityValue(entity, subMeta.getEntityClass());
                if (subEntity != null) {
                    DoMetadata doMeta = subMeta.getDoMetadata();
                    changes.add(
                            EntityChange.builder()
                                    .type(ChangeType.INSERT)
                                    .entityClass(subMeta.getEntityClass())
                                    .doClass(doMeta.getDoClass())
                                    .mapper(doMeta.getMapper())
                                    .entity(converter.convert(subEntity, doMeta.getDoClass()))
                                    .build());
                    collectInserts(subEntity, subMeta, changes);
                }
            }
        }
        List<EntityMetadata> listMetas = metadata.getSubEntityLists();
        if (listMetas != null) {
            for (EntityMetadata listMeta : listMetas) {
                List<?> subEntities = getSubEntityListValue(entity, listMeta.getEntityClass());
                if (subEntities != null) {
                    for (Object subEntity : subEntities) {
                        DoMetadata doMeta = listMeta.getDoMetadata();
                        changes.add(
                                EntityChange.builder()
                                        .type(ChangeType.INSERT)
                                        .entityClass(listMeta.getEntityClass())
                                        .doClass(doMeta.getDoClass())
                                        .mapper(doMeta.getMapper())
                                        .entity(converter.convert(subEntity, doMeta.getDoClass()))
                                        .build());
                        collectInserts(subEntity, listMeta, changes);
                    }
                }
            }
        }
    }

    /** 递归收集实体中所有子实体的删除变更 */
    private void collectDeletes(
            Object entity, EntityMetadata metadata, List<EntityChange> changes) {
        List<EntityMetadata> subMetas = metadata.getSubEntities();
        if (subMetas != null) {
            for (EntityMetadata subMeta : subMetas) {
                Object subEntity = getSubEntityValue(entity, subMeta.getEntityClass());
                if (subEntity != null) {
                    DoMetadata doMeta = subMeta.getDoMetadata();
                    Object doValue = converter.convert(subEntity, doMeta.getDoClass());
                    Object id = doMeta.getId(doValue);
                    changes.add(
                            EntityChange.builder()
                                    .type(ChangeType.DELETE)
                                    .entityId(id)
                                    .idFieldName(doMeta.getIdFieldName())
                                    .entityClass(subMeta.getEntityClass())
                                    .doClass(doMeta.getDoClass())
                                    .mapper(doMeta.getMapper())
                                    .entity(doValue)
                                    .build());
                    collectDeletes(subEntity, subMeta, changes);
                }
            }
        }
        List<EntityMetadata> listMetas = metadata.getSubEntityLists();
        if (listMetas != null) {
            for (EntityMetadata listMeta : listMetas) {
                List<?> subEntities = getSubEntityListValue(entity, listMeta.getEntityClass());
                if (subEntities != null) {
                    for (Object subEntity : subEntities) {
                        DoMetadata doMeta = listMeta.getDoMetadata();
                        Object doValue = converter.convert(subEntity, doMeta.getDoClass());
                        Object id = doMeta.getId(doValue);
                        changes.add(
                                EntityChange.builder()
                                        .type(ChangeType.DELETE)
                                        .entityId(id)
                                        .idFieldName(doMeta.getIdFieldName())
                                        .entityClass(listMeta.getEntityClass())
                                        .doClass(doMeta.getDoClass())
                                        .mapper(doMeta.getMapper())
                                        .entity(doValue)
                                        .build());
                        collectDeletes(subEntity, listMeta, changes);
                    }
                }
            }
        }
    }

    /** 从实体对象中读取指定类型的子实体字段值 */
    private Object getSubEntityValue(Object rootEntity, Class<?> entityClass) {
        if (rootEntity == null) return null;
        try {
            for (Field field : rootEntity.getClass().getDeclaredFields()) {
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

    /** 从实体对象中读取指定元素类型的子实体列表字段值 */
    @SuppressWarnings("unchecked")
    private List<?> getSubEntityListValue(Object rootEntity, Class<?> entityClass) {
        if (rootEntity == null) return null;
        try {
            for (Field field : rootEntity.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    ParameterizedType pt = (ParameterizedType) field.getGenericType();
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
}

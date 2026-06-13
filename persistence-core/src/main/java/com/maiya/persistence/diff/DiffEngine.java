package com.maiya.persistence.diff;

import com.maiya.persistence.mapping.AggregateMetadata;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 差异引擎，负责对比两个聚合实体对象（before 和 after），生成变更集（ChangeSet）。
 *
 * <p>支持根实体、单个子实体以及子实体列表的增删改差异检测， 为后续的变更执行提供结构化的变更描述。
 *
 * @author 萨博
 */
@Component
public class DiffEngine {

    /**
     * 元数据解析器，用于获取实体类的结构信息
     */
    @Autowired
    private MetadataResolver metadataResolver;

    /**
     * 对比两个实体对象，生成变更集。
     *
     * @param before 变更前的实体对象（可为 null）
     * @param after  变更后的实体对象（可为 null）
     * @return 变更集
     */
    public ChangeSet diff(Object before, Object after) {
        ChangeSet changeSet = new ChangeSet();

        if (before == null && after == null) {
            return changeSet;
        }

        // 确定实体类，优先使用 after 的类，否则使用 before 的类
        Class<?> entityClass = after != null ? after.getClass() : before.getClass();
        AggregateMetadata metadata = metadataResolver.resolve(entityClass);

        // 新增场景：before 为 null，after 不为 null
        if (before == null) {
            changeSet.setRootChange(
                RootChange.builder()
                    .type(ChangeType.INSERT)
                    .entityClass(metadata.getEntityClass())
                    .doClass(metadata.getRootDoClass())
                    .entity(after)
                    .build());
            addSubEntityInserts(after, metadata, changeSet);
            return changeSet;
        }

        // 删除场景：before 不为 null，after 为 null
        if (after == null) {
            Object id = getId(before, metadata.getIdField());
            changeSet.setRootChange(
                RootChange.builder()
                    .type(ChangeType.DELETE)
                    .entityId(id)
                    .idFieldName(metadata.getIdFieldName())
                    .entityClass(metadata.getEntityClass())
                    .doClass(metadata.getRootDoClass())
                    .build());
            addSubEntityDeletes(before, metadata, changeSet);
            return changeSet;
        }

        // 更新场景：对比 before 和 after 的差异
        RootChange rootChange = diffRootEntity(before, after, metadata);
        changeSet.setRootChange(rootChange);
        diffSubEntities(before, after, metadata, changeSet);
        diffSubEntityLists(before, after, metadata, changeSet);

        return changeSet;
    }

    /**
     * 对比根实体的基本字段差异。
     *
     * @param before   变更前的根实体
     * @param after    变更后的根实体
     * @param metadata 聚合元数据
     * @return 根实体变更描述
     */
    private RootChange diffRootEntity(Object before, Object after, AggregateMetadata metadata) {
        List<FieldChange> fieldChanges = new ArrayList<>();
        for (Field field : metadata.getBasicFields()) {
            field.setAccessible(true);
            try {
                Object oldVal = field.get(before);
                Object newVal = field.get(after);
                if (!Objects.equals(oldVal, newVal)) {
                    fieldChanges.add(new FieldChange(field.getName(), oldVal, newVal));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问字段: " + field.getName(), e);
            }
        }
        if (fieldChanges.isEmpty()) {
            return RootChange.builder()
                .type(ChangeType.NONE)
                .entityClass(metadata.getEntityClass())
                .doClass(metadata.getRootDoClass())
                .build();
        }
        return RootChange.builder()
            .type(ChangeType.UPDATE)
            .idFieldName(metadata.getIdFieldName())
            .entityClass(metadata.getEntityClass())
            .doClass(metadata.getRootDoClass())
            .fieldChanges(fieldChanges)
            .build();
    }

    /**
     * 对比单个子实体字段的差异（增、删、改）。
     *
     * @param before    变更前的根实体
     * @param after     变更后的根实体
     * @param metadata  聚合元数据
     * @param changeSet 变更集
     */
    private void diffSubEntities(
        Object before, Object after, AggregateMetadata metadata, ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object oldSub = subInfo.getEntityField().get(before);
                Object newSub = subInfo.getEntityField().get(after);

                if (oldSub != null && newSub == null) {
                    // 子实体被删除
                    Object id = getId(oldSub, subInfo.getEntityIdField());
                    changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(subInfo.getIdFieldName())
                            .entityClass(subInfo.getEntityClass())
                            .doClass(subInfo.getDoClass())
                            .entity(oldSub)
                            .build());
                } else if (oldSub == null && newSub != null) {
                    // 子实体被新增
                    changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(subInfo.getEntityClass())
                            .doClass(subInfo.getDoClass())
                            .entity(newSub)
                            .build());
                } else if (oldSub != null) {
                    // 子实体存在，对比字段差异
                    List<FieldChange> changes =
                        diffFields(oldSub, newSub, subInfo.getEntityClass());
                    if (!changes.isEmpty()) {
                        Object id = getId(newSub, subInfo.getEntityIdField());
                        changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                .type(ChangeType.UPDATE)
                                .entityId(id)
                                .idFieldName(subInfo.getIdFieldName())
                                .entityClass(subInfo.getEntityClass())
                                .doClass(subInfo.getDoClass())
                                .entity(newSub)
                                .fieldChanges(changes)
                                .build());
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问子实体字段: " + subInfo.getEntityField().getName(), e);
            }
        }
    }

    /**
     * 对比子实体列表的差异（增、删、改）。
     *
     * @param before    变更前的根实体
     * @param after     变更后的根实体
     * @param metadata  聚合元数据
     * @param changeSet 变更集
     */
    private void diffSubEntityLists(
        Object before, Object after, AggregateMetadata metadata, ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> oldList = (List<?>) listInfo.getEntityField().get(before);
                List<?> newList = (List<?>) listInfo.getEntityField().get(after);

                // 将列表按主键映射为 Map，便于对比
                Map<Object, Object> oldMap = toIdMap(oldList, listInfo.getElementIdField());
                Map<Object, Object> newMap = toIdMap(newList, listInfo.getElementIdField());

                // 检测删除：存在于 oldMap 但不存在于 newMap
                for (Object id : oldMap.keySet()) {
                    if (!newMap.containsKey(id)) {
                        changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                .type(ChangeType.DELETE)
                                .entityId(id)
                                .idFieldName(listInfo.getIdFieldName())
                                .entityClass(listInfo.getElementEntityClass())
                                .doClass(listInfo.getElementDoClass())
                                .entity(oldMap.get(id))
                                .build());
                    }
                }
                // 检测新增：存在于 newMap 但不存在于 oldMap
                for (Object id : newMap.keySet()) {
                    if (!oldMap.containsKey(id)) {
                        changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                .type(ChangeType.INSERT)
                                .entityClass(listInfo.getElementEntityClass())
                                .doClass(listInfo.getElementDoClass())
                                .entity(newMap.get(id))
                                .build());
                    }
                }
                // 检测更新：主键相同但字段有变化
                for (Object id : oldMap.keySet()) {
                    if (newMap.containsKey(id)) {
                        List<FieldChange> changes =
                            diffFields(
                                oldMap.get(id),
                                newMap.get(id),
                                listInfo.getElementEntityClass());
                        if (!changes.isEmpty()) {
                            changeSet.addSubEntityChange(
                                SubEntityChange.builder()
                                    .type(ChangeType.UPDATE)
                                    .entityId(id)
                                    .idFieldName(listInfo.getIdFieldName())
                                    .entityClass(listInfo.getElementEntityClass())
                                    .doClass(listInfo.getElementDoClass())
                                    .entity(newMap.get(id))
                                    .fieldChanges(changes)
                                    .build());
                        }
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                    "无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }

    /**
     * 对比两个同类型实体对象的基本字段差异。
     *
     * @param oldObj      变更前的对象
     * @param newObj      变更后的对象
     * @param entityClass 实体类
     * @return 字段变更列表
     */
    private List<FieldChange> diffFields(Object oldObj, Object newObj, Class<?> entityClass) {
        AggregateMetadata subMetadata = metadataResolver.resolve(entityClass);
        List<FieldChange> changes = new ArrayList<>();
        for (Field field : subMetadata.getBasicFields()) {
            field.setAccessible(true);
            try {
                Object oldVal = field.get(oldObj);
                Object newVal = field.get(newObj);
                if (!Objects.equals(oldVal, newVal)) {
                    changes.add(new FieldChange(field.getName(), oldVal, newVal));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问字段: " + field.getName(), e);
            }
        }
        return changes;
    }

    /**
     * 将实体列表按主键字段映射为 Map。
     *
     * @param list    实体列表
     * @param idField 主键字段
     * @return 主键到实体的映射
     */
    private Map<Object, Object> toIdMap(List<?> list, Field idField) {
        if (list == null) return Collections.emptyMap();
        Map<Object, Object> map = new LinkedHashMap<>();
        idField.setAccessible(true);
        for (Object item : list) {
            try {
                Object id = idField.get(item);
                map.put(id, item);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问主键字段: " + idField.getName(), e);
            }
        }
        return map;
    }

    /**
     * 获取实体对象的主键值。
     *
     * @param entity  实体对象
     * @param idField 主键字段
     * @return 主键值
     */
    private Object getId(Object entity, Field idField) {
        idField.setAccessible(true);
        try {
            return idField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("无法访问主键字段: " + idField.getName(), e);
        }
    }

    /**
     * 当根实体为新增时，递归收集所有子实体和子实体列表的插入变更。
     *
     * @param after     变更后的根实体
     * @param metadata  聚合元数据
     * @param changeSet 变更集
     */
    private void addSubEntityInserts(
        Object after, AggregateMetadata metadata, ChangeSet changeSet) {
        // 处理单个子实体
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object sub = subInfo.getEntityField().get(after);
                if (sub != null) {
                    changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(subInfo.getEntityClass())
                            .doClass(subInfo.getDoClass())
                            .entity(sub)
                            .build());
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问子实体字段: " + subInfo.getEntityField().getName(), e);
            }
        }
        // 处理子实体列表
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> list = (List<?>) listInfo.getEntityField().get(after);
                if (list != null) {
                    for (Object item : list) {
                        changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                .type(ChangeType.INSERT)
                                .entityClass(listInfo.getElementEntityClass())
                                .doClass(listInfo.getElementDoClass())
                                .entity(item)
                                .build());
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                    "无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }

    /**
     * 当根实体为删除时，递归收集所有子实体和子实体列表的删除变更。
     *
     * @param before    变更前的根实体
     * @param metadata  聚合元数据
     * @param changeSet 变更集
     */
    private void addSubEntityDeletes(
        Object before, AggregateMetadata metadata, ChangeSet changeSet) {
        // 处理单个子实体
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object sub = subInfo.getEntityField().get(before);
                if (sub != null) {
                    Object id = getId(sub, subInfo.getEntityIdField());
                    changeSet.addSubEntityChange(
                        SubEntityChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(subInfo.getIdFieldName())
                            .entityClass(subInfo.getEntityClass())
                            .doClass(subInfo.getDoClass())
                            .entity(sub)
                            .build());
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问子实体字段: " + subInfo.getEntityField().getName(), e);
            }
        }
        // 处理子实体列表
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> list = (List<?>) listInfo.getEntityField().get(before);
                if (list != null) {
                    for (Object item : list) {
                        Object id = getId(item, listInfo.getElementIdField());
                        changeSet.addSubEntityChange(
                            SubEntityChange.builder()
                                .type(ChangeType.DELETE)
                                .entityId(id)
                                .idFieldName(listInfo.getIdFieldName())
                                .entityClass(listInfo.getElementEntityClass())
                                .doClass(listInfo.getElementDoClass())
                                .entity(item)
                                .build());
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                    "无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }
}

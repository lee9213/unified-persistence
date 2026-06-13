package com.maiya.persistence.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 变更集合 聚合根实体及其子实体的整体变更信息，包含根实体变更和子实体变更列表
 *
 * @author 萨博
 */
@Data
public class ChangeSet {
    /** 根实体的变更信息 */
    private RootChange rootChange;

    /** 子实体的变更信息列表 */
    private List<SubEntityChange> subEntityChanges = new ArrayList<>();

    /**
     * 判断当前变更集合是否为空
     *
     * @return 如果根实体无变更且子实体变更列表为空，则返回true
     */
    public boolean isEmpty() {
        return (rootChange == null || rootChange.getType() == ChangeType.NONE)
                && subEntityChanges.isEmpty();
    }

    /**
     * 添加子实体变更信息
     *
     * @param change 子实体变更对象
     */
    public void addSubEntityChange(SubEntityChange change) {
        subEntityChanges.add(change);
    }
}

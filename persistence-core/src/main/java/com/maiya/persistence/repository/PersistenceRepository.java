package com.maiya.persistence.repository;

import com.maiya.persistence.model.EntityChange;
import java.util.List;

/**
 * 聚合仓储接口，定义了聚合实体的持久化核心操作。
 *
 * <p>提供差异对比、变更执行以及一键持久化（diff + execute）的能力， 是领域层与持久化层之间的抽象边界。
 *
 * @param <T> 聚合实体类型
 * @author 萨博
 */
public interface PersistenceRepository<T> {

    /**
     * 对比两个聚合实体对象，生成变更列表。
     *
     * @param before 变更前的实体对象
     * @param after 变更后的实体对象
     * @return 变更列表
     */
    List<EntityChange> diff(T before, T after);

    /**
     * 执行变更列表，将差异同步到数据库。
     *
     * @param changes 变更列表
     */
    void execute(List<EntityChange> changes);

    /**
     * 一键持久化：先对比差异，再执行变更。
     *
     * <p>默认实现调用 {@link #diff} 和 {@link #execute} 完成完整流程。
     *
     * @param before 变更前的实体对象
     * @param after 变更后的实体对象
     */
    void persist(T before, T after);
}

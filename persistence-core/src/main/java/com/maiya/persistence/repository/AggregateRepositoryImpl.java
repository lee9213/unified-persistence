package com.maiya.persistence.repository;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.model.ChangeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 聚合仓储实现类，组合差异引擎和变更执行器完成聚合实体的持久化。
 *
 * <p>通过 Spring 代理实现事务控制：diff 在事务外执行，execute 在事务内执行（跨 Bean 调用，代理生效）。
 *
 * @param <T> 聚合实体类型
 * @author 萨博
 */
@Component
public class AggregateRepositoryImpl<T> implements AggregateRepository<T> {

    /**
     * 差异引擎，用于生成变更集
     */
    @Autowired
    private DiffEngine diffEngine;

    /**
     * 变更执行器，用于将变更集同步到数据库
     */
    @Autowired
    private ChangeExecutor changeExecutor;

    /**
     * 对比两个聚合实体对象，生成变更集。
     *
     * @param before 变更前的实体对象
     * @param after  变更后的实体对象
     * @return 变更集
     */
    @Override
    public ChangeSet diff(T before, T after) {
        return diffEngine.diff(before, after);
    }

    /**
     * 执行变更集，将差异同步到数据库。
     *
     * @param changeSet 变更集
     */
    @Override
    public void execute(ChangeSet changeSet) {
        changeExecutor.execute(changeSet);
    }

    /**
     * 一键持久化：先对比差异（事务外），再执行变更（事务内，跨 Bean 调用代理生效）。
     *
     * @param before 变更前的实体对象
     * @param after  变更后的实体对象
     */
    @Override
    public void persist(T before, T after) {
        ChangeSet changeSet = diffEngine.diff(before, after);
        changeExecutor.execute(changeSet);
    }
}

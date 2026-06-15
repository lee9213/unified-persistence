package com.maiya.persistence.repository;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.model.EntityChange;
import java.util.List;
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
public class PersistenceRepositoryImpl<T> implements PersistenceRepository<T> {

    @Autowired private DiffEngine diffEngine;

    @Autowired private ChangeExecutor changeExecutor;

    @Override
    public List<EntityChange> diff(T before, T after) {
        return diffEngine.diff(before, after);
    }

    @Override
    public void execute(List<EntityChange> changes) {
        changeExecutor.execute(changes);
    }

    @Override
    public void persist(T before, T after) {
        List<EntityChange> changes = diffEngine.diff(before, after);
        changeExecutor.execute(changes);
    }
}

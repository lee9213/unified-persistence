package com.maiya.persistence.mybatis.autoconfigure;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.repository.AggregateRepository;
import com.maiya.persistence.repository.AggregateRepositoryImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 持久层自动配置类 负责注册和初始化持久层框架所需的核心 Bean，包括映射注册表、元数据解析器、 实体拷贝器、差异引擎、变更执行器和聚合仓库等组件
 *
 * @author 萨博
 */
@AutoConfiguration
public class PersistenceAutoConfiguration {

    /**
     * 注册 Mapper 注册表 Bean 用于管理和注册实体与数据对象之间的映射关系
     *
     * @return MapperRegistry 实例
     */
    @Bean
    public MapperRegistry mapperRegistry() {
        return new MapperRegistry();
    }

    /**
     * 注册元数据解析器 Bean 用于解析实体类的元数据信息，如主键、字段映射等
     *
     * @return MetadataResolver 实例
     */
    @Bean
    public MetadataResolver metadataResolver() {
        return new MetadataResolver();
    }

    /**
     * 注册实体拷贝器 Bean 用于实现实体（Entity）与数据对象（DO）之间的相互转换和深拷贝
     *
     * @return EntityCopier 实例
     */
    @Bean
    public EntityCopier entityCopier() {
        return new EntityCopier();
    }

    /**
     * 注册差异引擎 Bean 用于计算两个实体对象之间的差异，生成变更集
     *
     * @return DiffEngine 实例
     */
    @Bean
    public DiffEngine diffEngine() {
        return new DiffEngine();
    }

    /**
     * 注册变更执行器 Bean 负责执行由差异引擎生成的数据库变更操作
     *
     * @return ChangeExecutor 实例
     */
    @Bean
    public ChangeExecutor changeExecutor() {
        return new ChangeExecutor();
    }

    /**
     * 注册聚合仓库 Bean 提供聚合根的持久化能力，封装了增删改查的完整生命周期管理
     *
     * @return AggregateRepository 实例
     */
    @Bean
    public AggregateRepository<?> aggregateRepository() {
        return new AggregateRepositoryImpl<>();
    }
}

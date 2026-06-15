package com.maiya.persistence.autoconfigure;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.repository.PersistenceRepository;
import com.maiya.persistence.repository.PersistenceRepositoryImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 持久层自动配置类，负责注册和初始化持久层框架所需的核心 Bean。
 *
 * <p>包括映射注册表、元数据解析器、差异引擎、变更执行器和聚合仓库等组件。
 *
 * @author 萨博
 */
@AutoConfiguration
public class PersistenceAutoConfiguration {

    /**
     * 注册 Mapper 注册表 Bean。容器启动时扫描所有 BaseMapper，解析泛型获取 DO Class 并缓存。
     *
     * @return MapperRegistry 实例
     */
    @Bean
    public MapperRegistry mapperRegistry() {
        return new MapperRegistry();
    }

    /**
     * 注册元数据解析器 Bean。用于解析实体类的类级元数据模板（结构信息）。
     *
     * @param mapperRegistry Mapper 注册表
     * @return MetadataResolver 实例
     */
    @Bean
    public MetadataResolver metadataResolver(MapperRegistry mapperRegistry) {
        return new MetadataResolver(mapperRegistry);
    }

    /**
     * 注册实体拷贝器 Bean。用于实现实体（Entity）与数据对象（DO）之间的相互转换和深拷贝。
     *
     * @return EntityCopier 实例
     */
    @Bean
    public EntityCopier entityCopier() {
        return new EntityCopier();
    }

    /**
     * 注册差异引擎 Bean。用于计算两个实体对象之间的差异，生成变更列表。
     *
     * @return DiffEngine 实例
     */
    @Bean
    public DiffEngine diffEngine() {
        return new DiffEngine();
    }

    /**
     * 注册变更执行器 Bean。负责执行由差异引擎生成的数据库变更操作。
     *
     * @return ChangeExecutor 实例
     */
    @Bean
    public ChangeExecutor changeExecutor() {
        return new ChangeExecutor();
    }

    /**
     * 注册持久化仓库 Bean。提供聚合根的持久化能力，封装了增删改的完整生命周期管理。
     *
     * @return PersistenceRepository 实例
     */
    @Bean
    public PersistenceRepository<?> persistenceRepository() {
        return new PersistenceRepositoryImpl<>();
    }
}

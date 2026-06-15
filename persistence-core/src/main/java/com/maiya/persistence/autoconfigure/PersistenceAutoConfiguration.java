package com.maiya.persistence.autoconfigure;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.mapping.EntityConverter;
import com.maiya.persistence.mapping.DoMetadataRegistry;
import com.maiya.persistence.mapping.EntityMetadataResolver;
import com.maiya.persistence.repository.PersistenceRepository;
import com.maiya.persistence.repository.PersistenceRepositoryImpl;
import io.github.linpeilie.Converter;
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
     * 注册 DO 元数据注册表 Bean。容器启动时扫描所有 BaseMapper，解析泛型获取 DO Class 并缓存。
     *
     * @return DoMetadataRegistry 实例
     */
    @Bean
    public DoMetadataRegistry doMetadataRegistry() {
        return new DoMetadataRegistry();
    }

    /**
     * 注册元数据解析器 Bean。用于解析实体类的类级元数据模板（结构信息）。
     *
     * @param doMetadataRegistry DO 元数据注册表
     * @return EntityMetadataResolver 实例
     */
    @Bean
    public EntityMetadataResolver entityMetadataResolver(DoMetadataRegistry doMetadataRegistry) {
        return new EntityMetadataResolver(doMetadataRegistry);
    }

    /**
     * 注册实体转换器 Bean。用于实现实体（Entity）与数据对象（DO）之间的相互转换和深拷贝。
     *
     * @return EntityConverter 实例
     */
    @Bean
    public EntityConverter entityConverter(Converter converter) {
        return new EntityConverter(converter);
    }

    /**
     * 注册差异引擎 Bean。用于计算两个实体对象之间的差异，生成变更列表。
     *
     * @return DiffEngine 实例
     */
    @Bean
    public DiffEngine diffEngine(EntityMetadataResolver entityMetadataResolver, Converter converter) {
        return new DiffEngine(entityMetadataResolver, converter);
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
    public PersistenceRepository<?> persistenceRepository(DiffEngine diffEngine, ChangeExecutor changeExecutor) {
        return new PersistenceRepositoryImpl<>(diffEngine, changeExecutor);
    }
}

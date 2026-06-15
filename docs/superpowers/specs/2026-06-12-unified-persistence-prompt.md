# 统一持久化框架 — 完整实现提示词

请基于以下规范，开发一款基于 MyBatis-Plus 的统一持久化框架。这是一个全新独立的 Java 项目，不关联任何现有代码。

---

## 一、项目概述

开发一款统一持久化框架，核心能力：
1. 提供公共的持久化接口，业务只需要实现该接口，调用通用的持久化方法即可
2. 通过业务对象自动映射到对应 Mapper 与 DO，业务对象是聚合根
3. 通过比对变更前后的数据，变更前为空则执行插入，变更后为空则执行删除，数据变更只修改变更的数据

### 技术选型

- 语言：Java 17+
- 底层 ORM：MyBatis-Plus
- 对象映射：MapStruct Plus（`io.github.linpeilie:mapstruct-plus-spring-boot-starter`）
- 构建工具：Maven，多模块结构
- Spring Boot 3.x

### 核心设计决策

- 聚合根支持多表聚合（一个聚合根可对应多张表）
- 变更比对粒度：字段级 + 子实体级，全量 dirty checking
- 约定优于配置，不使用自定义注解，所有映射通过命名约定推断
- 不使用快照/ThreadLocal，业务在 persist 前自行 deepCopy 保存 before 状态
- diff 在事务外执行，execute 在事务内执行
- @Transactional 放在 ChangeExecutor 上（独立 Bean），确保跨 Bean 调用时事务生效
- 框架不负责查询/加载，不关心父子关联关系
- 依赖方向：DO 依赖 Entity，Entity 不依赖 DO

---

## 二、命名约定规则

框架通过以下命名约定自动推断所有映射关系，无需自定义注解：

| 约定 | 规则 | 示例 |
|------|------|------|
| 聚合根识别 | `AggregateRepository<XxxEntity>` 的类型参数 | `AggregateRepository<OrderEntity>` |
| 主键字段 | 名为 `id` 或 `{类名去Entity小写}Id` | `OrderEntity` → `orderId` |
| 根表 DO | `{去Entity的类名}DO` | `OrderEntity` → `OrderDO` |
| 子实体识别 | 字段类型以 `Entity` 结尾且存在对应 DO 类 | `OrderAddressEntity` → 存在 `OrderAddressDO` → 子实体 |
| 子实体列表识别 | `List<XxxEntity>` 中 Xxx 存在对应 DO | `List<OrderItemEntity>` → 存在 `OrderItemDO` → 子实体列表 |
| DO → Mapper | `{去DO类名}Mapper` | `OrderDO` → `OrderMapper` |
| 基本类型字段 | String/Number/Boolean/Date/Enum/BigDecimal 等 | 直接映射为根表字段 |

### 依赖方向

```
DO（持久层）──依赖──→ Entity（领域层）
```

- Entity：纯领域对象，不依赖任何持久层类，无 `@AutoMapper` 注解
- DO：持久层对象，依赖 Entity，在 DO 上加 `@AutoMapper(target = XxxEntity.class)`

### 框架不关心的内容

- 子实体与父实体的关联关系（DO 外键字段自然承载）
- 查询/加载（业务自行通过 Mapper 处理）

---

## 三、项目结构

```
unified-persistence/
├── pom.xml                                     # 父 POM
├── persistence-core/
│   ├── pom.xml
│   └── src/main/java/com/maiya/persistence/
│       ├── repository/
│       │   ├── AggregateRepository.java         # 接口
│       │   └── AggregateRepositoryImpl.java     # 实现
│       ├── diff/
│       │   ├── DiffEngine.java                  # 变更比对引擎
│       │   └── FieldComparator.java             # 字段比较器
│       ├── execution/
│       │   └── ChangeExecutor.java              # 变更执行器（@Transactional）
│       ├── mapping/
│       │   ├── EntityCopier.java                # deepCopy / toDO / toEntity
│       │   ├── MetadataResolver.java            # 聚合根元数据解析
│       │   ├── MapperRegistry.java              # DO → Mapper 查找
│       │   └── AggregateMetadata.java           # 元数据结构
│       └── model/
│           ├── ChangeSet.java
│           ├── RootChange.java
│           ├── SubEntityChange.java
│           ├── FieldChange.java
│           └── ChangeType.java
├── persistence-mybatis/
│   ├── pom.xml
│   └── src/main/java/com/maiya/persistence/mybatis/
│       └── autoconfigure/
│           └── PersistenceAutoConfiguration.java
└── persistence-example/
    ├── pom.xml
    └── src/main/java/com/maiya/persistence/example/
        ├── entity/
        │   ├── OrderEntity.java
        │   ├── OrderItemEntity.java
        │   └── OrderAddressEntity.java
        ├── do/
        │   ├── OrderDO.java
        │   ├── OrderItemDO.java
        │   └── OrderAddressDO.java
        ├── mapper/
        │   ├── OrderMapper.java
        │   ├── OrderItemMapper.java
        │   └── OrderAddressMapper.java
        └── service/
            └── OrderService.java
```

---

## 四、核心组件详细设计

### 4.1 AggregateRepository

统一持久化入口。

```java
public interface AggregateRepository<T> {

    /**
     * 比对变更（事务外）
     * @param before 修改前的聚合根（null 表示新增）
     * @param after  修改后的聚合根（null 表示删除）
     * @return 变更集
     */
    ChangeSet diff(T before, T after);

    /**
     * 执行变更（事务内）
     * @param changeSet 变更集
     */
    void execute(ChangeSet changeSet);

    /**
     * 便捷方法：diff + execute
     * diff 在事务外执行，execute 在事务内执行
     */
    default void persist(T before, T after) {
        ChangeSet changeSet = diff(before, after);
        execute(changeSet);
    }
}
```

实现类（注意：不在此类上加 @Transactional）：

```java
@Component
public class AggregateRepositoryImpl<T> implements AggregateRepository<T> {

    @Autowired
    private DiffEngine diffEngine;
    @Autowired
    private ChangeExecutor changeExecutor;

    @Override
    public ChangeSet diff(T before, T after) {
        return diffEngine.diff(before, after);
    }

    @Override
    public void execute(ChangeSet changeSet) {
        changeExecutor.execute(changeSet);
    }

    @Override
    public void persist(T before, T after) {
        ChangeSet changeSet = diffEngine.diff(before, after);
        changeExecutor.execute(changeSet);
    }
}
```

### 4.2 DiffEngine

变更比对引擎，比对 before 和 after 两个聚合根对象，生成 ChangeSet。

#### 比对规则

```
diff(before, after)
├── before == null && after != null → INSERT（全量插入）
├── before != null && after == null → DELETE（全量删除）
├── before == null && after == null → 无变更，返回空 ChangeSet
└── before != null && after != null → 字段级比对
    ├── 根实体基本类型字段 → Objects.equals() 逐字段比较
    ├── Entity 字段（子实体）→ 递归 diff
    │   ├── before 有 & after 为 null → DELETE
    │   ├── before 为 null & after 有 → INSERT
    │   └── 都有值 → 递归字段级比对 → UPDATE 或 NONE
    └── List<XxxEntity> 字段（子实体列表）→ 按 id 匹配逐个比对
        ├── before 有 & after 无 → DELETE
        ├── before 无 & after 有 → INSERT
        └── 都有 → 递归字段级比对 → UPDATE 或 NONE
```

#### 核心实现

```java
@Component
public class DiffEngine {

    @Autowired
    private MetadataResolver metadataResolver;

    public ChangeSet diff(Object before, Object after) {
        ChangeSet changeSet = new ChangeSet();

        if (before == null && after == null) {
            return changeSet;
        }

        Class<?> entityClass = after != null ? after.getClass() : before.getClass();
        AggregateMetadata metadata = metadataResolver.resolve(entityClass);

        if (before == null) {
            changeSet.setRootChange(buildInsertChange(after, metadata));
            addSubEntityInserts(after, metadata, changeSet);
            return changeSet;
        }

        if (after == null) {
            changeSet.setRootChange(buildDeleteChange(before, metadata));
            addSubEntityDeletes(before, metadata, changeSet);
            return changeSet;
        }

        RootChange rootChange = diffRootEntity(before, after, metadata);
        changeSet.setRootChange(rootChange);
        diffSubEntities(before, after, metadata, changeSet);
        diffSubEntityLists(before, after, metadata, changeSet);

        return changeSet;
    }

    private RootChange diffRootEntity(Object before, Object after,
                                       AggregateMetadata metadata) {
        List<FieldChange> fieldChanges = new ArrayList<>();
        for (Field field : metadata.getBasicFields()) {
            field.setAccessible(true);
            Object oldVal = field.get(before);
            Object newVal = field.get(after);
            if (!Objects.equals(oldVal, newVal)) {
                fieldChanges.add(new FieldChange(field.getName(), oldVal, newVal));
            }
        }
        if (fieldChanges.isEmpty()) {
            return RootChange.none(metadata);
        }
        return RootChange.update(metadata, fieldChanges);
    }

    private void diffSubEntities(Object before, Object after,
                                  AggregateMetadata metadata,
                                  ChangeSet changeSet) {
        for (SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            Object oldSub = subInfo.getEntityField().get(before);
            Object newSub = subInfo.getEntityField().get(after);

            if (oldSub != null && newSub == null) {
                changeSet.addSubEntityChange(SubEntityChange.delete(subInfo, getId(oldSub, subInfo.getEntityIdField()), oldSub));
            } else if (oldSub == null && newSub != null) {
                changeSet.addSubEntityChange(SubEntityChange.insert(subInfo, newSub));
            } else if (oldSub != null && newSub != null) {
                List<FieldChange> changes = diffFields(oldSub, newSub, subInfo.getEntityClass());
                if (!changes.isEmpty()) {
                    changeSet.addSubEntityChange(SubEntityChange.update(subInfo, getId(newSub, subInfo.getEntityIdField()), newSub, changes));
                }
            }
        }
    }

    private void diffSubEntityLists(Object before, Object after,
                                     AggregateMetadata metadata,
                                     ChangeSet changeSet) {
        for (SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            List<?> oldList = (List<?>) listInfo.getEntityField().get(before);
            List<?> newList = (List<?>) listInfo.getEntityField().get(after);

            Map<Object, Object> oldMap = toIdMap(oldList, listInfo.getElementIdField());
            Map<Object, Object> newMap = toIdMap(newList, listInfo.getElementIdField());

            for (Object id : oldMap.keySet()) {
                if (!newMap.containsKey(id)) {
                    changeSet.addSubEntityChange(SubEntityChange.delete(listInfo, id, oldMap.get(id)));
                }
            }
            for (Object id : newMap.keySet()) {
                if (!oldMap.containsKey(id)) {
                    changeSet.addSubEntityChange(SubEntityChange.insert(listInfo, newMap.get(id)));
                }
            }
            for (Object id : oldMap.keySet()) {
                if (newMap.containsKey(id)) {
                    List<FieldChange> changes = diffFields(oldMap.get(id), newMap.get(id), listInfo.getElementEntityClass());
                    if (!changes.isEmpty()) {
                        changeSet.addSubEntityChange(SubEntityChange.update(listInfo, id, newMap.get(id), changes));
                    }
                }
            }
        }
    }

    private List<FieldChange> diffFields(Object oldObj, Object newObj, Class<?> entityClass) {
        AggregateMetadata subMetadata = metadataResolver.resolve(entityClass);
        List<FieldChange> changes = new ArrayList<>();
        for (Field field : subMetadata.getBasicFields()) {
            field.setAccessible(true);
            Object oldVal = field.get(oldObj);
            Object newVal = field.get(newObj);
            if (!Objects.equals(oldVal, newVal)) {
                changes.add(new FieldChange(field.getName(), oldVal, newVal));
            }
        }
        return changes;
    }

    private Map<Object, Object> toIdMap(List<?> list, Field idField) {
        if (list == null) return Collections.emptyMap();
        Map<Object, Object> map = new LinkedHashMap<>();
        idField.setAccessible(true);
        for (Object item : list) {
            Object id = idField.get(item);
            map.put(id, item);
        }
        return map;
    }

    private Object getId(Object entity, Field idField) {
        idField.setAccessible(true);
        return idField.get(entity);
    }

    private RootChange buildInsertChange(Object after, AggregateMetadata metadata) {
        return RootChange.insert(metadata, after);
    }

    private RootChange buildDeleteChange(Object before, AggregateMetadata metadata) {
        Object id = getId(before, metadata.getIdField());
        return RootChange.delete(metadata, id);
    }

    private void addSubEntityInserts(Object after, AggregateMetadata metadata, ChangeSet changeSet) {
        for (SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            Object sub = subInfo.getEntityField().get(after);
            if (sub != null) {
                changeSet.addSubEntityChange(SubEntityChange.insert(subInfo, sub));
            }
        }
        for (SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            List<?> list = (List<?>) listInfo.getEntityField().get(after);
            if (list != null) {
                for (Object item : list) {
                    changeSet.addSubEntityChange(SubEntityChange.insert(listInfo, item));
                }
            }
        }
    }

    private void addSubEntityDeletes(Object before, AggregateMetadata metadata, ChangeSet changeSet) {
        for (SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            Object sub = subInfo.getEntityField().get(before);
            if (sub != null) {
                Object id = getId(sub, subInfo.getEntityIdField());
                changeSet.addSubEntityChange(SubEntityChange.delete(subInfo, id, sub));
            }
        }
        for (SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            List<?> list = (List<?>) listInfo.getEntityField().get(before);
            if (list != null) {
                for (Object item : list) {
                    Object id = getId(item, listInfo.getElementIdField());
                    changeSet.addSubEntityChange(SubEntityChange.delete(listInfo, id, item));
                }
            }
        }
    }
}
```

### 4.3 ChangeExecutor

变更执行器。**@Transactional 必须放在此类上**，因为 `AggregateRepositoryImpl` 调用 `changeExecutor.execute()` 是跨 Bean 调用，Spring AOP 代理才能生效。

#### 执行顺序

1. 执行 DELETE（子实体优先删除，避免外键约束）
2. 执行 INSERT（子实体）
3. 执行 UPDATE（子实体，只 SET 变更字段）
4. 执行根实体变更（INSERT / UPDATE / DELETE）

#### 核心实现

```java
@Component
public class ChangeExecutor {

    @Autowired
    private MapperRegistry mapperRegistry;
    @Autowired
    private EntityCopier entityCopier;

    @Transactional(rollbackFor = Exception.class)
    public void execute(ChangeSet changeSet) {
        if (changeSet.isEmpty()) {
            return;
        }

        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.DELETE)
            .forEach(this::executeDelete);

        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.INSERT)
            .forEach(this::executeInsert);

        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.UPDATE)
            .forEach(this::executeUpdate);

        executeRootChange(changeSet.getRootChange());
    }

    private void executeRootChange(RootChange change) {
        switch (change.getType()) {
            case INSERT:
                Object insertDO = entityCopier.toDO(change.getEntity(), change.getDoClass());
                mapperRegistry.getMapper(change.getDoClass()).insert(insertDO);
                break;
            case UPDATE:
                executeFieldLevelUpdate(change.getDoClass(), change.getIdFieldName(),
                                        change.getEntityId(), change.getFieldChanges());
                break;
            case DELETE:
                mapperRegistry.getMapper(change.getDoClass()).deleteById(change.getEntityId());
                break;
            default:
                break;
        }
    }

    private void executeFieldLevelUpdate(Class<?> doClass, String idFieldName,
                                          Object entityId, List<FieldChange> fieldChanges) {
        UpdateWrapper<?> wrapper = new UpdateWrapper<>();
        wrapper.eq(idFieldName, entityId);
        for (FieldChange fc : fieldChanges) {
            wrapper.set(fc.getFieldName(), fc.getNewValue());
        }
        mapperRegistry.getMapper(doClass).update(null, wrapper);
    }

    private void executeInsert(SubEntityChange change) {
        Object insertDO = entityCopier.toDO(change.getEntity(), change.getDoClass());
        mapperRegistry.getMapper(change.getDoClass()).insert(insertDO);
    }

    private void executeUpdate(SubEntityChange change) {
        executeFieldLevelUpdate(change.getDoClass(), change.getIdFieldName(),
                                change.getEntityId(), change.getFieldChanges());
    }

    private void executeDelete(SubEntityChange change) {
        mapperRegistry.getMapper(change.getDoClass()).deleteById(change.getEntityId());
    }
}
```

### 4.4 EntityCopier

对象拷贝工具类，委托 MapStruct Plus 的 `Converter` 实现。

```java
@Component
public class EntityCopier {

    @Autowired
    private Converter converter;

    public <T> T deepCopy(T source) {
        return converter.convert(source, (Class<T>) source.getClass());
    }

    public <S, T> T toDO(S entity, Class<T> doClass) {
        return converter.convert(entity, doClass);
    }

    public <S, T> T toEntity(S DO, Class<T> entityClass) {
        return converter.convert(DO, entityClass);
    }

    public <S, T> List<T> toList(List<S> sources, Class<T> targetClass) {
        return converter.convert(sources, targetClass);
    }
}
```

### 4.5 MetadataResolver

聚合根元数据解析器，首次遇到一个聚合根类型时解析其结构并缓存到 ConcurrentHashMap。

```java
@Component
public class MetadataResolver {

    @Autowired
    private MapperRegistry mapperRegistry;

    private final Map<Class<?>, AggregateMetadata> metadataCache = new ConcurrentHashMap<>();

    public AggregateMetadata resolve(Class<?> entityClass) {
        return metadataCache.computeIfAbsent(entityClass, this::doResolve);
    }

    private AggregateMetadata doResolve(Class<?> entityClass) {
        AggregateMetadata metadata = new AggregateMetadata();
        metadata.setEntityClass(entityClass);

        // 1. 解析根 DO：OrderEntity → OrderDO
        String doClassName = entityClass.getName().replace("Entity", "DO");
        Class<?> doClass = Class.forName(doClassName);
        metadata.setRootDoClass(doClass);

        // 2. 解析主键字段：找 id 或 {类名去Entity小写}Id
        metadata.setIdField(findIdField(entityClass));

        // 3. 获取根 Mapper
        metadata.setRootMapper(mapperRegistry.getMapper(doClass));

        // 4. 遍历字段，分类为基本类型 / 子实体 / 子实体列表
        for (Field field : entityClass.getDeclaredFields()) {
            Class<?> fieldType = field.getType();

            if (isEntityType(fieldType)) {
                Class<?> subDoClass = tryResolveDoClass(fieldType);
                if (subDoClass != null) {
                    SubEntityInfo info = new SubEntityInfo();
                    info.setEntityField(field);
                    info.setEntityClass(fieldType);
                    info.setDoClass(subDoClass);
                    info.setEntityIdField(findIdField(fieldType));
                    info.setMapper(mapperRegistry.getMapper(subDoClass));
                    metadata.getSubEntities().add(info);
                } else {
                    metadata.getBasicFields().add(field);
                }
            } else if (List.class.isAssignableFrom(fieldType)) {
                Class<?> elementClass = getGenericParameter(field);
                if (isEntityType(elementClass)) {
                    Class<?> subDoClass = tryResolveDoClass(elementClass);
                    if (subDoClass != null) {
                        SubEntityListInfo info = new SubEntityListInfo();
                        info.setEntityField(field);
                        info.setElementEntityClass(elementClass);
                        info.setElementDoClass(subDoClass);
                        info.setElementIdField(findIdField(elementClass));
                        info.setMapper(mapperRegistry.getMapper(subDoClass));
                        metadata.getSubEntityLists().add(info);
                    } else {
                        metadata.getBasicFields().add(field);
                    }
                } else {
                    metadata.getBasicFields().add(field);
                }
            } else if (!Modifier.isStatic(field.getModifiers())) {
                metadata.getBasicFields().add(field);
            }
        }
        return metadata;
    }

    private boolean isEntityType(Class<?> clazz) {
        return clazz.getSimpleName().endsWith("Entity");
    }

    private Class<?> tryResolveDoClass(Class<?> entityClass) {
        try {
            String doClassName = entityClass.getName().replace("Entity", "DO");
            return Class.forName(doClassName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Field findIdField(Class<?> entityClass) {
        // 优先找 "id" 字段
        try {
            return entityClass.getDeclaredField("id");
        } catch (NoSuchFieldException e) {
            // 其次找 "{类名去Entity小写}Id" 字段
            String idFieldName = decapitalize(entityClass.getSimpleName().replace("Entity", "")) + "Id";
            try {
                return entityClass.getDeclaredField(idFieldName);
            } catch (NoSuchFieldException ex) {
                throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " 没有找到主键字段（id 或 " + idFieldName + "）");
            }
        }
    }

    private String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private Class<?> getGenericParameter(Field field) {
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        return (Class<?>) pt.getActualTypeArguments()[0];
    }
}
```

### 4.6 MapperRegistry

Mapper 注册与查找，通过 Spring 容器按约定名称发现。

```java
@Component
public class MapperRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    private final Map<Class<?>, BaseMapper<?>> mapperCache = new ConcurrentHashMap<>();

    public <T> BaseMapper<T> getMapper(Class<T> doClass) {
        return (BaseMapper<T>) mapperCache.computeIfAbsent(doClass, cls -> {
            String mapperName = cls.getSimpleName().replace("DO", "") + "Mapper";
            return applicationContext.getBean(mapperName, BaseMapper.class);
        });
    }
}
```

### 4.7 AggregateMetadata

```java
@Data
public class AggregateMetadata {

    private Class<?> entityClass;
    private Class<?> rootDoClass;
    private Field idField;
    private BaseMapper<?> rootMapper;
    private List<Field> basicFields = new ArrayList<>();
    private List<SubEntityInfo> subEntities = new ArrayList<>();
    private List<SubEntityListInfo> subEntityLists = new ArrayList<>();

    public String getIdFieldName() {
        return idField.getName();
    }

    @Data
    public static class SubEntityInfo {
        private Field entityField;
        private Class<?> entityClass;
        private Class<?> doClass;
        private Field entityIdField;
        private BaseMapper<?> mapper;

        public String getIdFieldName() {
            return entityIdField.getName();
        }
    }

    @Data
    public static class SubEntityListInfo {
        private Field entityField;
        private Class<?> elementEntityClass;
        private Class<?> elementDoClass;
        private Field elementIdField;
        private BaseMapper<?> mapper;

        public String getIdFieldName() {
            return elementIdField.getName();
        }
    }
}
```

### 4.8 ChangeSet 模型

```java
@Data
public class ChangeSet {
    private RootChange rootChange;
    private List<SubEntityChange> subEntityChanges = new ArrayList<>();

    public boolean isEmpty() {
        return (rootChange == null || rootChange.getType() == ChangeType.NONE)
            && subEntityChanges.isEmpty();
    }

    public void addSubEntityChange(SubEntityChange change) {
        subEntityChanges.add(change);
    }
}

@Data
@Builder
public class RootChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private List<FieldChange> fieldChanges;

    public static RootChange none(AggregateMetadata metadata) {
        return RootChange.builder()
            .type(ChangeType.NONE)
            .entityClass(metadata.getEntityClass())
            .doClass(metadata.getRootDoClass())
            .build();
    }

    public static RootChange insert(AggregateMetadata metadata, Object entity) {
        return RootChange.builder()
            .type(ChangeType.INSERT)
            .entityClass(metadata.getEntityClass())
            .doClass(metadata.getRootDoClass())
            .entity(entity)
            .build();
    }

    public static RootChange update(AggregateMetadata metadata, List<FieldChange> fieldChanges) {
        return RootChange.builder()
            .type(ChangeType.UPDATE)
            .entityClass(metadata.getEntityClass())
            .doClass(metadata.getRootDoClass())
            .fieldChanges(fieldChanges)
            .build();
    }

    public static RootChange delete(AggregateMetadata metadata, Object entityId) {
        return RootChange.builder()
            .type(ChangeType.DELETE)
            .entityId(entityId)
            .idFieldName(metadata.getIdFieldName())
            .entityClass(metadata.getEntityClass())
            .doClass(metadata.getRootDoClass())
            .build();
    }
}

@Data
@Builder
public class SubEntityChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private List<FieldChange> fieldChanges;

    public static SubEntityChange insert(AggregateMetadata.SubEntityInfo info, Object entity) {
        return SubEntityChange.builder()
            .type(ChangeType.INSERT)
            .entityClass(info.getEntityClass())
            .doClass(info.getDoClass())
            .entity(entity)
            .build();
    }

    public static SubEntityChange insert(AggregateMetadata.SubEntityListInfo info, Object entity) {
        return SubEntityChange.builder()
            .type(ChangeType.INSERT)
            .entityClass(info.getElementEntityClass())
            .doClass(info.getElementDoClass())
            .entity(entity)
            .build();
    }

    public static SubEntityChange update(AggregateMetadata.SubEntityInfo info, Object entityId,
                                          Object entity, List<FieldChange> fieldChanges) {
        return SubEntityChange.builder()
            .type(ChangeType.UPDATE)
            .entityId(entityId)
            .idFieldName(info.getIdFieldName())
            .entityClass(info.getEntityClass())
            .doClass(info.getDoClass())
            .entity(entity)
            .fieldChanges(fieldChanges)
            .build();
    }

    public static SubEntityChange update(AggregateMetadata.SubEntityListInfo info, Object entityId,
                                          Object entity, List<FieldChange> fieldChanges) {
        return SubEntityChange.builder()
            .type(ChangeType.UPDATE)
            .entityId(entityId)
            .idFieldName(info.getIdFieldName())
            .entityClass(info.getElementEntityClass())
            .doClass(info.getElementDoClass())
            .entity(entity)
            .fieldChanges(fieldChanges)
            .build();
    }

    public static SubEntityChange delete(AggregateMetadata.SubEntityInfo info, Object entityId, Object entity) {
        return SubEntityChange.builder()
            .type(ChangeType.DELETE)
            .entityId(entityId)
            .idFieldName(info.getIdFieldName())
            .entityClass(info.getEntityClass())
            .doClass(info.getDoClass())
            .entity(entity)
            .build();
    }

    public static SubEntityChange delete(AggregateMetadata.SubEntityListInfo info, Object entityId, Object entity) {
        return SubEntityChange.builder()
            .type(ChangeType.DELETE)
            .entityId(entityId)
            .idFieldName(info.getIdFieldName())
            .entityClass(info.getElementEntityClass())
            .doClass(info.getElementDoClass())
            .entity(entity)
            .build();
    }
}

@Data
@AllArgsConstructor
public class FieldChange {
    private String fieldName;
    private Object oldValue;
    private Object newValue;
}

public enum ChangeType {
    INSERT,
    UPDATE,
    DELETE,
    NONE
}
```

---

## 五、事务设计

### 事务边界

```
persist(before, after)
│
├── diffEngine.diff()          ← 事务外，无锁，纯内存比对
│       ↓
│   ChangeSet
│
└── changeExecutor.execute()   ← 事务内（跨 Bean 调用，Spring 代理生效）
        ├── DELETE（子实体优先删除）
        ├── INSERT（子实体）
        ├── UPDATE（子实体，只 SET 变更字段）
        └── 根实体变更（INSERT / UPDATE / DELETE）
```

### 关键设计

- `@Transactional` 放在 `ChangeExecutor` 上，而非 `AggregateRepositoryImpl`
- `AggregateRepositoryImpl.persist()` 调用 `changeExecutor.execute()` 是跨 Bean 调用，Spring AOP 代理生效
- diff 在事务外执行，减少锁持有时间
- 业务也可分两步调用：先 `diff()`（事务外），再 `execute()`（事务内）

---

## 六、业务使用示例

### 6.1 Entity 定义（领域层，无持久层依赖）

```java
@Data
public class OrderEntity {
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
    private OrderAddressEntity address;
    private List<OrderItemEntity> items;
}

@Data
public class OrderItemEntity {
    private Long id;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
}

@Data
public class OrderAddressEntity {
    private Long id;
    private String province;
    private String city;
    private String detail;
}
```

### 6.2 DO 定义（持久层，依赖 Entity）

```java
@Data
@TableName("t_order")
@AutoMapper(target = OrderEntity.class)
public class OrderDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
}

@Data
@TableName("t_order_item")
@AutoMapper(target = OrderItemEntity.class)
public class OrderItemDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
}

@Data
@TableName("t_order_address")
@AutoMapper(target = OrderAddressEntity.class)
public class OrderAddressDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private String province;
    private String city;
    private String detail;
}
```

### 6.3 Mapper 定义

```java
@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {}

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {}

@Mapper
public interface OrderAddressMapper extends BaseMapper<OrderAddressDO> {}
```

### 6.4 业务 Service 使用

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderAddressMapper orderAddressMapper;
    private final EntityCopier entityCopier;
    private final AggregateRepository<OrderEntity> orderRepository;

    // 新增
    public void createOrder(OrderEntity order) {
        orderRepository.persist(null, order);
    }

    // 修改
    public void updateOrder(Long orderId) {
        // 业务自行查询组装
        OrderDO orderDO = orderMapper.selectById(orderId);
        OrderEntity order = entityCopier.toEntity(orderDO, OrderEntity.class);
        order.setItems(entityCopier.toList(
            orderItemMapper.selectList(
                new QueryWrapper<OrderItemDO>().eq("orderId", orderId)),
            OrderItemEntity.class));
        order.setAddress(entityCopier.toEntity(
            orderAddressMapper.selectOne(
                new QueryWrapper<OrderAddressDO>().eq("orderId", orderId)),
            OrderAddressEntity.class));

        // 深拷贝 before
        OrderEntity before = entityCopier.deepCopy(order);

        // 业务修改
        order.setCustomerName("李四");
        order.getItems().remove(0);
        order.getItems().add(newItem);
        order.getAddress().setCity("上海");

        // 持久化
        orderRepository.persist(before, order);
    }

    // 删除
    public void deleteOrder(Long orderId) {
        OrderDO orderDO = orderMapper.selectById(orderId);
        OrderEntity before = entityCopier.toEntity(orderDO, OrderEntity.class);
        orderRepository.persist(before, null);
    }
}
```

### 6.5 persist 执行效果

| 操作 | before | after | 生成的 SQL |
|------|--------|-------|-----------|
| 新增 | null | order | `INSERT t_order` + `INSERT t_order_item` + `INSERT t_order_address` |
| 修改客户名 | order(张三) | order(李四) | `UPDATE t_order SET customer_name='李四' WHERE order_id=?` |
| 删除明细 | order(2 items) | order(1 item) | `DELETE FROM t_order_item WHERE id=?` |
| 新增明细 | order(1 item) | order(2 items) | `INSERT t_order_item` |
| 修改地址 | address(北京) | address(上海) | `UPDATE t_order_address SET city='上海' WHERE id=?` |
| 删除订单 | order | null | `DELETE t_order_address` + `DELETE t_order_item` + `DELETE t_order` |

---

## 七、框架职责边界

### 框架负责

- Entity ↔ DO 字段映射（通过 MapStruct Plus）
- 深拷贝（通过 MapStruct Plus Converter）
- 变更比对（DiffEngine，字段级 + 子实体级）
- 变更执行（ChangeExecutor，事务内，只 SET 变更字段）
- 元数据解析与缓存（MetadataResolver）
- Mapper 发现（MapperRegistry）

### 框架不负责

- 查询/加载（业务自行通过 Mapper 查询）
- 父子关联关系管理（DO 外键字段自然承载）
- 分页、条件查询（业务自行处理）
- 数据组装（业务自行将 DO 组装为 Entity）

---

## 八、Maven 依赖

### 父 POM 关键依赖

```xml
<properties>
    <java.version>17</java.version>
    <mybatis-plus.version>3.5.5</mybatis-plus.version>
    <mapstruct-plus.version>1.4.4</mapstruct-plus.version>
    <spring-boot.version>3.2.5</spring-boot.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.linpeilie</groupId>
            <artifactId>mapstruct-plus-spring-boot-starter</artifactId>
            <version>${mapstruct-plus.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### persistence-core 依赖

```xml
<dependencies>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.linpeilie</groupId>
        <artifactId>mapstruct-plus-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

### 业务模块编译插件配置（MapStruct Plus 注解处理器）

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <source>17</source>
                <target>17</target>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.linpeilie</groupId>
                        <artifactId>mapstruct-plus-processor</artifactId>
                        <version>${mapstruct-plus.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok-mapstruct-binding</artifactId>
                        <version>0.2.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

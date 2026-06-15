# 统一持久化框架设计规范

## 一、项目定位

开发一款基于 MyBatis-Plus 的统一持久化框架，提供公共的持久化接口，业务只需实现该接口并调用通用的持久化方法即可完成增删改操作。框架通过快照式变更追踪，自动比对修改前后的数据差异，生成精确的 INSERT/UPDATE/DELETE 操作。

### 核心特性

1. 提供公共的持久化接口，业务只需要实现该接口，调用通用的持久化方法即可
2. 通过业务对象自动映射到对应 Mapper 与 DO，业务对象是聚合根
3. 通过比对变更前后的数据，变更前为空则执行插入，变更后为空则执行删除，数据变更只修改变更的数据

### 项目定位

- 全新独立项目，不关联现有代码
- 底层 ORM：MyBatis-Plus
- 对象映射：MapStruct Plus
- 聚合根支持多表聚合（一个聚合根可对应多张表）
- 变更比对粒度：字段级 + 子实体级，全量 dirty checking

---

## 二、约定规则

框架采用约定优于配置，所有映射关系通过命名约定自动推断，无需注解声明。

### 2.1 命名约定

| 约定 | 规则 | 示例 |
|------|------|------|
| 聚合根识别 | `AggregateRepository<XxxEntity>` 的类型参数 | `AggregateRepository<OrderEntity>` |
| 主键字段 | 名为 `id` 或 `{类名去Entity小写}Id` | `OrderEntity` → `orderId` |
| 根表 DO | `{去Entity的类名}DO` | `OrderEntity` → `OrderDO` |
| 子实体识别 | 字段类型以 `Entity` 结尾且存在对应 DO 类 | `OrderAddressEntity` → 存在 `OrderAddressDO` → 子实体 |
| 子实体列表识别 | `List<XxxEntity>` 中 Xxx 存在对应 DO | `List<OrderItemEntity>` → 存在 `OrderItemDO` → 子实体列表 |
| DO → Mapper | `{去DO类名}Mapper` | `OrderDO` → `OrderMapper` |
| 基本类型字段 | String/Number/Boolean/Date/Enum/BigDecimal 等 | 直接映射为根表字段 |

### 2.2 依赖方向

```
DO（持久层）──依赖──→ Entity（领域层）
```

- Entity：纯领域对象，不依赖任何持久层类
- DO：持久层对象，依赖 Entity，在 DO 上加 `@AutoMapper(target = XxxEntity.class)`

### 2.3 框架不关心的内容

框架不关心子实体与父实体的关联关系，关联关系由 DO 自身的外键字段自然承载。框架不负责查询/加载，查询由业务自行通过 Mapper 处理。

---

## 三、项目结构

```
unified-persistence/
├── persistence-core/                    # 核心模块
│   └── com.maiya.persistence/
│       ├── repository/
│       │   ├── AggregateRepository.java         # 接口：diff / execute / persist
│       │   └── AggregateRepositoryImpl.java     # 实现
│       ├── diff/
│       │   ├── DiffEngine.java                  # 变更比对引擎
│       │   └── FieldComparator.java             # 字段比较器
│       ├── execution/
│       │   └── ChangeExecutor.java              # 变更执行器（@Transactional）
│       ├── mapping/
│       │   ├── EntityCopier.java                # deepCopy / toDO / toEntity
│       │   ├── EntityMetadataResolver.java            # 聚合根元数据解析
│       │   ├── DoMetadataRegistry.java              # DO → Mapper 查找
│       │   └── AggregateMetadata.java           # 元数据结构
│       └── model/
│           ├── ChangeSet.java
│           ├── RootChange.java
│           ├── SubEntityChange.java
│           ├── FieldChange.java
│           └── ChangeType.java                  # INSERT / UPDATE / DELETE / NONE
├── persistence-mybatis/                 # MyBatis-Plus 适配
│   └── com.maiya.persistence.mybatis/
│       └── autoconfigure/
│           └── PersistenceAutoConfiguration.java
└── persistence-example/                 # 使用示例
```

---

## 四、核心组件设计

### 4.1 AggregateRepository

统一持久化入口，提供三个方法：

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

实现类：

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
├── before == null && after == null → 无变更
└── before != null && after != null → 字段级比对
    ├── 根实体字段比对
    │   ├── 基本类型字段 → Objects.equals() 比较
    │   ├── Entity 字段 → 递归 diff（子实体比对）
    │   └── List<XxxEntity> 字段 → 按 id 匹配，逐个递归 diff
    │       ├── 快照有 & 当前无 → DELETE
    │       ├── 快照无 & 当前有 → INSERT
    │       └── 两边都有 → 递归 diff → UPDATE 或 NONE
    └── 有字段差异 → UPDATE + fieldChanges
```

#### 核心实现

```java
@Component
public class DiffEngine {

    @Autowired
    private EntityMetadataResolver metadataResolver;

    public ChangeSet diff(Object before, Object after) {
        ChangeSet changeSet = new ChangeSet();

        if (before == null && after == null) {
            return changeSet;
        }

        Class<?> entityClass = after != null ? after.getClass() : before.getClass();
        AggregateMetadata metadata = metadataResolver.resolve(entityClass);

        // before=null → 全量 INSERT
        if (before == null) {
            changeSet.setRootChange(buildInsertChange(after, metadata));
            addSubEntityInserts(after, metadata, changeSet);
            return changeSet;
        }

        // after=null → 全量 DELETE
        if (after == null) {
            changeSet.setRootChange(buildDeleteChange(before, metadata));
            addSubEntityDeletes(before, metadata, changeSet);
            return changeSet;
        }

        // 字段级比对
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

    private void diffSubEntityLists(Object before, Object after,
                                     AggregateMetadata metadata,
                                     ChangeSet changeSet) {
        for (SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            List<?> oldList = (List<?>) listInfo.getEntityField().get(before);
            List<?> newList = (List<?>) listInfo.getEntityField().get(after);

            Map<Object, Object> oldMap = toIdMap(oldList, listInfo.getElementIdField());
            Map<Object, Object> newMap = toIdMap(newList, listInfo.getElementIdField());

            // 快照有 & 当前无 → DELETE
            for (Object id : oldMap.keySet()) {
                if (!newMap.containsKey(id)) {
                    changeSet.addSubEntityChange(SubEntityChange.delete(listInfo, id, oldMap.get(id)));
                }
            }
            // 快照无 & 当前有 → INSERT
            for (Object id : newMap.keySet()) {
                if (!oldMap.containsKey(id)) {
                    changeSet.addSubEntityChange(SubEntityChange.insert(listInfo, newMap.get(id)));
                }
            }
            // 都有 → 字段级比对
            for (Object id : oldMap.keySet()) {
                if (newMap.containsKey(id)) {
                    List<FieldChange> changes = diffFields(oldMap.get(id), newMap.get(id), listInfo);
                    if (!changes.isEmpty()) {
                        changeSet.addSubEntityChange(SubEntityChange.update(listInfo, id, newMap.get(id), changes));
                    }
                }
            }
        }
    }
}
```

### 4.3 ChangeExecutor

变更执行器，将 ChangeSet 转为 MyBatis-Plus 操作。**@Transactional 注解在此类上**，确保跨 Bean 调用时事务生效。

#### 执行顺序

1. 执行 DELETE（子实体）— 先删子表
2. 执行 INSERT（子实体）
3. 执行 UPDATE（子实体）
4. 执行根实体变更（INSERT / UPDATE / DELETE）

#### 核心实现

```java
@Component
public class ChangeExecutor {

    @Autowired
    private DoMetadataRegistry mapperRegistry;
    @Autowired
    private EntityCopier entityCopier;

    @Transactional(rollbackFor = Exception.class)
    public void execute(ChangeSet changeSet) {
        if (changeSet.isEmpty()) {
            return;
        }

        // 1. 先执行子实体 DELETE
        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.DELETE)
            .forEach(this::executeDelete);

        // 2. 执行子实体 INSERT
        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.INSERT)
            .forEach(this::executeInsert);

        // 3. 执行子实体 UPDATE
        changeSet.getSubEntityChanges().stream()
            .filter(c -> c.getType() == ChangeType.UPDATE)
            .forEach(this::executeUpdate);

        // 4. 执行根实体变更
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
        }
    }

    /**
     * 字段级 UPDATE：只 SET 变更的字段
     */
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

对象拷贝工具类，委托 MapStruct Plus 的 `Converter` 实现，编译期生成代码，零反射。

```java
@Component
public class EntityCopier {

    @Autowired
    private Converter converter;

    /**
     * 深拷贝（快照用）— 同类型转换
     */
    public <T> T deepCopy(T source) {
        return converter.convert(source, (Class<T>) source.getClass());
    }

    /**
     * Entity → DO
     */
    public <S, T> T toDO(S entity, Class<T> doClass) {
        return converter.convert(entity, doClass);
    }

    /**
     * DO → Entity
     */
    public <S, T> T toEntity(S DO, Class<T> entityClass) {
        return converter.convert(DO, entityClass);
    }

    /**
     * List 批量转换
     */
    public <S, T> List<T> toList(List<S> sources, Class<T> targetClass) {
        return converter.convert(sources, targetClass);
    }
}
```

### 4.5 EntityMetadataResolver

聚合根元数据解析器，首次遇到一个聚合根类型时解析其结构并缓存。

```java
@Component
public class EntityMetadataResolver {

    @Autowired
    private DoMetadataRegistry mapperRegistry;

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

        // 4. 遍历字段，识别子实体和子实体列表
        for (Field field : entityClass.getDeclaredFields()) {
            Class<?> fieldType = field.getType();

            if (List.class.isAssignableFrom(fieldType)) {
                Class<?> elementClass = getGenericParameter(field);
                DoMetadata elementDoMeta = mapperRegistry.getDoMetadata(elementClass);
                if (elementDoMeta != null) {
                    Class<?> subDoClass = elementDoMeta.getDoClass();
                    if (subDoClass != null) {
                        SubEntityListInfo info = new SubEntityListInfo();
                        info.setEntityField(field);
                        info.setElementEntityClass(elementClass);
                        info.setElementDoClass(subDoClass);
                        info.setElementIdField(findIdField(elementClass));
                        info.setMapper(mapperRegistry.getMapper(subDoClass));
                        metadata.getSubEntityLists().add(info);
                    }
                }
            } else {
                DoMetadata subDoMeta = mapperRegistry.getDoMetadata(fieldType);
                if (subDoMeta != null) {
                    Class<?> subDoClass = subDoMeta.getDoClass();
                    if (subDoClass != null) {
                        SubEntityInfo info = new SubEntityInfo();
                        info.setEntityField(field);
                        info.setEntityClass(fieldType);
                        info.setDoClass(subDoClass);
                        info.setEntityIdField(findIdField(fieldType));
                        info.setMapper(mapperRegistry.getMapper(subDoClass));
                        metadata.getSubEntities().add(info);
                    }
                } else {
                    metadata.getBasicFields().add(field);
                }
            }
        }
        return metadata;
    }

}
```

### 4.6 DoMetadataRegistry

Mapper 注册与查找，通过 Spring 容器按约定名称发现。

```java
@Component
public class DoMetadataRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    private final Map<Class<?>, BaseMapper<?>> mapperCache = new ConcurrentHashMap<>();

    /**
     * 根据 DO Class 查找 Mapper
     * 约定：OrderDO → OrderMapper
     */
    public <T> BaseMapper<T> getMapper(Class<T> doClass) {
        return (BaseMapper<T>) mapperCache.computeIfAbsent(doClass, cls -> {
            String mapperName = cls.getSimpleName().replace("DO", "") + "Mapper";
            return applicationContext.getBean(mapperName, BaseMapper.class);
        });
    }
}
```

### 4.7 AggregateMetadata

聚合根元数据结构。

```java
public class AggregateMetadata {

    private Class<?> entityClass;
    private Class<?> rootDoClass;
    private Field idField;
    private BaseMapper<?> rootMapper;
    private List<Field> basicFields = new ArrayList<>();
    private List<SubEntityInfo> subEntities = new ArrayList<>();
    private List<SubEntityListInfo> subEntityLists = new ArrayList<>();

    public static class SubEntityInfo {
        private Field entityField;
        private Class<?> entityClass;
        private Class<?> doClass;
        private Field entityIdField;
        private BaseMapper<?> mapper;
    }

    public static class SubEntityListInfo {
        private Field entityField;
        private Class<?> elementEntityClass;
        private Class<?> elementDoClass;
        private Field elementIdField;
        private BaseMapper<?> mapper;
    }
}
```

### 4.8 ChangeSet 模型

```java
public class ChangeSet {
    private RootChange rootChange;
    private List<SubEntityChange> subEntityChanges = new ArrayList<>();

    public boolean isEmpty() {
        return (rootChange == null || rootChange.getType() == ChangeType.NONE)
            && subEntityChanges.isEmpty();
    }
}

public class RootChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private List<FieldChange> fieldChanges;
}

public class SubEntityChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private List<FieldChange> fieldChanges;
}

public class FieldChange {
    private String fieldName;
    private Object oldValue;
    private Object newValue;
}

public enum ChangeType {
    INSERT,   // before=null, after有值
    UPDATE,   // before和after都有值，字段有差异
    DELETE,   // before有值, after=null
    NONE      // 无变更
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
public class OrderEntity {
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
    private OrderAddressEntity address;
    private List<OrderItemEntity> items;
}

public class OrderItemEntity {
    private Long id;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
}

public class OrderAddressEntity {
    private Long id;
    private String province;
    private String city;
    private String detail;
}
```

### 6.2 DO 定义（持久层，依赖 Entity）

```java
@TableName("t_order")
@AutoMapper(target = OrderEntity.class)
public class OrderDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
}

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
- 元数据解析与缓存（EntityMetadataResolver）
- Mapper 发现（DoMetadataRegistry）

### 框架不负责

- 查询/加载（业务自行通过 Mapper 查询）
- 父子关联关系管理（DO 外键字段自然承载）
- 分页、条件查询（业务自行处理）
- 数据组装（业务自行将 DO 组装为 Entity）

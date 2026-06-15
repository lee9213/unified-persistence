# 统一持久化框架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 开发一款基于 MyBatis-Plus 的统一持久化框架，通过比对变更前后的聚合根对象，自动生成精确的 INSERT/UPDATE/DELETE 操作。

**Architecture:** 快照式变更追踪，业务在 persist 前自行 deepCopy 保存 before 状态，框架比对 before/after 生成 ChangeSet，ChangeExecutor 在事务内执行变更。约定优于配置，通过命名约定自动推断 Entity↔DO↔Mapper 映射关系。

**Tech Stack:** Java 17, Spring Boot 3.x, MyBatis-Plus 3.5.5, MapStruct Plus 1.4.4, Lombok, JUnit 5, H2

---

## File Structure

```
unified-persistence/
├── pom.xml
├── persistence-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/maiya/persistence/
│       │   ├── model/
│       │   │   ├── ChangeType.java
│       │   │   ├── FieldChange.java
│       │   │   ├── ChangeSet.java
│       │   │   ├── RootChange.java
│       │   │   └── SubEntityChange.java
│       │   ├── mapping/
│       │   │   ├── AggregateMetadata.java
│       │   │   ├── MapperRegistry.java
│       │   │   ├── MetadataResolver.java
│       │   │   └── EntityCopier.java
│       │   ├── diff/
│       │   │   └── DiffEngine.java
│       │   ├── execution/
│       │   │   └── ChangeExecutor.java
│       │   └── repository/
│       │       ├── AggregateRepository.java
│       │       └── AggregateRepositoryImpl.java
│       └── test/java/com/maiya/persistence/
│           ├── model/
│           │   └── ChangeSetTest.java
│           ├── mapping/
│           │   ├── MetadataResolverTest.java
│           │   └── MapperRegistryTest.java
│           ├── diff/
│           │   └── DiffEngineTest.java
│           ├── execution/
│           │   └── ChangeExecutorTest.java
│           └── repository/
│               └── AggregateRepositoryImplTest.java
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
        ├── service/
        │   └── OrderService.java
        └── Application.java
```

---

### Task 1: 项目骨架 — 父 POM

**Files:**
- Create: `unified-persistence/pom.xml`

- [ ] **Step 1: 创建父 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.maiya</groupId>
    <artifactId>unified-persistence</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>persistence-core</module>
        <module>persistence-mybatis</module>
        <module>persistence-example</module>
    </modules>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
        <mapstruct-plus.version>1.4.4</mapstruct-plus.version>
        <lombok.version>1.18.30</lombok.version>
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
            <dependency>
                <groupId>com.maiya</groupId>
                <artifactId>persistence-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.maiya</groupId>
                <artifactId>persistence-mybatis</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: 创建 persistence-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.maiya</groupId>
        <artifactId>unified-persistence</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>persistence-core</artifactId>

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
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
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
</project>
```

- [ ] **Step 3: 创建 persistence-mybatis/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.maiya</groupId>
        <artifactId>unified-persistence</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>persistence-mybatis</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.maiya</groupId>
            <artifactId>persistence-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: 创建 persistence-example/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.maiya</groupId>
        <artifactId>unified-persistence</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>persistence-example</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.maiya</groupId>
            <artifactId>persistence-mybatis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
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
</project>
```

- [ ] **Step 5: 验证项目骨架编译**

Run: `cd unified-persistence && mvn validate -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat: 项目骨架 — 父 POM + 三模块 POM"
```

---

### Task 2: Model 层 — ChangeType / FieldChange / ChangeSet / RootChange / SubEntityChange

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/model/ChangeType.java`
- Create: `persistence-core/src/main/java/com/maiya/persistence/model/FieldChange.java`
- Create: `persistence-core/src/main/java/com/maiya/persistence/model/ChangeSet.java`
- Create: `persistence-core/src/main/java/com/maiya/persistence/model/RootChange.java`
- Create: `persistence-core/src/main/java/com/maiya/persistence/model/SubEntityChange.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/model/ChangeSetTest.java`

- [ ] **Step 1: 写 ChangeSet 空判断测试**

```java
package com.maiya.persistence.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChangeSetTest {

    @Test
    void isEmpty_whenNoChanges_returnsTrue() {
        ChangeSet changeSet = new ChangeSet();
        assertTrue(changeSet.isEmpty());
    }

    @Test
    void isEmpty_whenRootChangeIsNone_returnsTrue() {
        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder().type(ChangeType.NONE).build());
        assertTrue(changeSet.isEmpty());
    }

    @Test
    void isEmpty_whenRootChangeIsInsert_returnsFalse() {
        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder().type(ChangeType.INSERT).build());
        assertFalse(changeSet.isEmpty());
    }

    @Test
    void isEmpty_whenSubEntityChangeExists_returnsFalse() {
        ChangeSet changeSet = new ChangeSet();
        changeSet.addSubEntityChange(SubEntityChange.builder()
            .type(ChangeType.INSERT).build());
        assertFalse(changeSet.isEmpty());
    }
}
```

- [ ] **Step 2: 创建 ChangeType 枚举**

```java
package com.maiya.persistence.model;

public enum ChangeType {
    INSERT,
    UPDATE,
    DELETE,
    NONE
}
```

- [ ] **Step 3: 创建 FieldChange**

```java
package com.maiya.persistence.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FieldChange {
    private String fieldName;
    private Object oldValue;
    private Object newValue;
}
```

- [ ] **Step 4: 创建 RootChange**

```java
package com.maiya.persistence.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RootChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private java.util.List<FieldChange> fieldChanges;
}
```

- [ ] **Step 5: 创建 SubEntityChange**

```java
package com.maiya.persistence.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubEntityChange {
    private ChangeType type;
    private Object entityId;
    private String idFieldName;
    private Class<?> entityClass;
    private Class<?> doClass;
    private Object entity;
    private java.util.List<FieldChange> fieldChanges;
}
```

- [ ] **Step 6: 创建 ChangeSet**

```java
package com.maiya.persistence.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

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
```

- [ ] **Step 7: 运行测试**

Run: `cd unified-persistence/persistence-core && mvn test -pl . -Dtest=ChangeSetTest -q`
Expected: Tests pass

- [ ] **Step 8: Commit**

```bash
git add .
git commit -m "feat: Model 层 — ChangeType, FieldChange, ChangeSet, RootChange, SubEntityChange"
```

---

### Task 3: AggregateMetadata 元数据结构

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/mapping/AggregateMetadata.java`

- [ ] **Step 1: 创建 AggregateMetadata**

```java
package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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

- [ ] **Step 2: Commit**

```bash
git add .
git commit -m "feat: AggregateMetadata 元数据结构"
```

---

### Task 4: MapperRegistry — DO → Mapper 查找

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/mapping/MapperRegistry.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/mapping/MapperRegistryTest.java`

- [ ] **Step 1: 写 MapperRegistry 测试**

```java
package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MapperRegistryTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private BaseMapper<?> orderMapper;

    @InjectMocks
    private MapperRegistry mapperRegistry;

    @Test
    void getMapper_resolvesByConvention() {
        when(applicationContext.getBean("OrderMapper", BaseMapper.class))
            .thenReturn((BaseMapper) orderMapper);

        BaseMapper<?> result = mapperRegistry.getMapper(
            com.maiya.persistence.example.do.OrderDO.class);

        assertSame(orderMapper, result);
        verify(applicationContext).getBean("OrderMapper", BaseMapper.class);
    }

    @Test
    void getMapper_cachesResult() {
        when(applicationContext.getBean("OrderMapper", BaseMapper.class))
            .thenReturn((BaseMapper) orderMapper);

        BaseMapper<?> first = mapperRegistry.getMapper(
            com.maiya.persistence.example.do.OrderDO.class);
        BaseMapper<?> second = mapperRegistry.getMapper(
            com.maiya.persistence.example.do.OrderDO.class);

        assertSame(first, second);
        verify(applicationContext, times(1)).getBean("OrderMapper", BaseMapper.class);
    }
}
```

- [ ] **Step 2: 创建 MapperRegistry**

```java
package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MapperRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    private final Map<Class<?>, BaseMapper<?>> mapperCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> BaseMapper<T> getMapper(Class<T> doClass) {
        return (BaseMapper<T>) mapperCache.computeIfAbsent(doClass, cls -> {
            String mapperName = cls.getSimpleName().replace("DO", "") + "Mapper";
            return applicationContext.getBean(mapperName, BaseMapper.class);
        });
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `cd unified-persistence/persistence-core && mvn test -pl . -Dtest=MapperRegistryTest -q`
Expected: Tests pass（注意：此测试依赖 example 模块的 DO 类，需先完成 Task 8 的 DO 定义，或使用 mock）

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: MapperRegistry — DO → Mapper 约定查找"
```

---

### Task 5: MetadataResolver — 聚合根元数据解析

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/mapping/MetadataResolver.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/mapping/MetadataResolverTest.java`

- [ ] **Step 1: 写 MetadataResolver 测试**

```java
package com.maiya.persistence.mapping;

import com.maiya.persistence.example.entity.OrderEntity;
import com.maiya.persistence.example.entity.OrderItemEntity;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import com.maiya.persistence.example.do.OrderDO;
import com.maiya.persistence.example.do.OrderItemDO;
import com.maiya.persistence.example.do.OrderAddressDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataResolverTest {

    @Mock
    private MapperRegistry mapperRegistry;

    @Mock
    private BaseMapper<?> orderMapper;

    @Mock
    private BaseMapper<?> orderItemMapper;

    @Mock
    private BaseMapper<?> orderAddressMapper;

    @Test
    void resolve_parsesRootEntity() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn((BaseMapper) orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn((BaseMapper) orderItemMapper);
        when(mapperRegistry.getMapper(OrderAddressDO.class)).thenReturn((BaseMapper) orderAddressMapper);

        MetadataResolver resolver = new MetadataResolver(mapperRegistry);
        AggregateMetadata metadata = resolver.resolve(OrderEntity.class);

        assertEquals(OrderEntity.class, metadata.getEntityClass());
        assertEquals(OrderDO.class, metadata.getRootDoClass());
        assertEquals("orderId", metadata.getIdFieldName());
    }

    @Test
    void resolve_identifiesSubEntities() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn((BaseMapper) orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn((BaseMapper) orderItemMapper);
        when(mapperRegistry.getMapper(OrderAddressDO.class)).thenReturn((BaseMapper) orderAddressMapper);

        MetadataResolver resolver = new MetadataResolver(mapperRegistry);
        AggregateMetadata metadata = resolver.resolve(OrderEntity.class);

        assertEquals(1, metadata.getSubEntities().size());
        assertEquals(OrderAddressEntity.class, metadata.getSubEntities().get(0).getEntityClass());
        assertEquals(OrderAddressDO.class, metadata.getSubEntities().get(0).getDoClass());
    }

    @Test
    void resolve_identifiesSubEntityLists() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn((BaseMapper) orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn((BaseMapper) orderItemMapper);
        when(mapperRegistry.getMapper(OrderAddressDO.class)).thenReturn((BaseMapper) orderAddressMapper);

        MetadataResolver resolver = new MetadataResolver(mapperRegistry);
        AggregateMetadata metadata = resolver.resolve(OrderEntity.class);

        assertEquals(1, metadata.getSubEntityLists().size());
        assertEquals(OrderItemEntity.class, metadata.getSubEntityLists().get(0).getElementEntityClass());
        assertEquals(OrderItemDO.class, metadata.getSubEntityLists().get(0).getElementDoClass());
    }

    @Test
    void resolve_cachesResult() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn((BaseMapper) orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn((BaseMapper) orderItemMapper);
        when(mapperRegistry.getMapper(OrderAddressDO.class)).thenReturn((BaseMapper) orderAddressMapper);

        MetadataResolver resolver = new MetadataResolver(mapperRegistry);
        AggregateMetadata first = resolver.resolve(OrderEntity.class);
        AggregateMetadata second = resolver.resolve(OrderEntity.class);

        assertSame(first, second);
    }
}
```

- [ ] **Step 2: 创建 MetadataResolver**

```java
package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetadataResolver {

    @Autowired
    private MapperRegistry mapperRegistry;

    private final Map<Class<?>, AggregateMetadata> metadataCache = new ConcurrentHashMap<>();

    public MetadataResolver() {
    }

    public MetadataResolver(MapperRegistry mapperRegistry) {
        this.mapperRegistry = mapperRegistry;
    }

    public AggregateMetadata resolve(Class<?> entityClass) {
        return metadataCache.computeIfAbsent(entityClass, this::doResolve);
    }

    private AggregateMetadata doResolve(Class<?> entityClass) {
        AggregateMetadata metadata = new AggregateMetadata();
        metadata.setEntityClass(entityClass);

        String doClassName = entityClass.getName().replace("Entity", "DO");
        try {
            Class<?> doClass = Class.forName(doClassName);
            metadata.setRootDoClass(doClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Entity " + entityClass.getName() + " 对应的 DO 类 " + doClassName + " 不存在", e);
        }

        metadata.setIdField(findIdField(entityClass));
        metadata.setRootMapper(mapperRegistry.getMapper(metadata.getRootDoClass()));

        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            Class<?> fieldType = field.getType();

            if (isEntityType(fieldType)) {
                Class<?> subDoClass = tryResolveDoClass(fieldType);
                if (subDoClass != null) {
                    AggregateMetadata.SubEntityInfo info = new AggregateMetadata.SubEntityInfo();
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
                        AggregateMetadata.SubEntityListInfo info = new AggregateMetadata.SubEntityListInfo();
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
            } else {
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
        try {
            return entityClass.getDeclaredField("id");
        } catch (NoSuchFieldException e) {
            String idFieldName = decapitalize(entityClass.getSimpleName().replace("Entity", "")) + "Id";
            try {
                return entityClass.getDeclaredField(idFieldName);
            } catch (NoSuchFieldException ex) {
                throw new IllegalArgumentException(
                    "Entity " + entityClass.getName() + " 没有找到主键字段（id 或 " + idFieldName + "）", ex);
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

- [ ] **Step 3: 运行测试（需先完成 Task 8 的 Entity/DO 定义）**

Run: `cd unified-persistence && mvn test -pl persistence-core -Dtest=MetadataResolverTest -q`
Expected: Tests pass

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: MetadataResolver — 聚合根元数据解析"
```

---

### Task 6: EntityCopier — 对象拷贝工具

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/mapping/EntityCopier.java`

- [ ] **Step 1: 创建 EntityCopier**

```java
package com.maiya.persistence.mapping;

import io.github.linpeilie.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EntityCopier {

    @Autowired
    private Converter converter;

    @SuppressWarnings("unchecked")
    public <T> T deepCopy(T source) {
        if (source == null) return null;
        return converter.convert(source, (Class<T>) source.getClass());
    }

    public <S, T> T toDO(S entity, Class<T> doClass) {
        if (entity == null) return null;
        return converter.convert(entity, doClass);
    }

    public <S, T> T toEntity(S DO, Class<T> entityClass) {
        if (DO == null) return null;
        return converter.convert(DO, entityClass);
    }

    public <S, T> List<T> toList(List<S> sources, Class<T> targetClass) {
        if (sources == null) return null;
        return converter.convert(sources, targetClass);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add .
git commit -m "feat: EntityCopier — MapStruct Plus 对象拷贝工具"
```

---

### Task 7: DiffEngine — 变更比对引擎

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/diff/DiffEngine.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/diff/DiffEngineTest.java`

- [ ] **Step 1: 写 DiffEngine 测试**

```java
package com.maiya.persistence.diff;

import com.maiya.persistence.example.entity.OrderEntity;
import com.maiya.persistence.example.entity.OrderItemEntity;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import com.maiya.persistence.example.do.OrderDO;
import com.maiya.persistence.example.do.OrderItemDO;
import com.maiya.persistence.example.do.OrderAddressDO;
import com.maiya.persistence.mapping.AggregateMetadata;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiffEngineTest {

    @Mock
    private MapperRegistry mapperRegistry;

    @Mock
    private BaseMapper<?> orderMapper;

    @Mock
    private BaseMapper<?> orderItemMapper;

    @Mock
    private BaseMapper<?> orderAddressMapper;

    private DiffEngine diffEngine;

    @BeforeEach
    void setUp() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn((BaseMapper) orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn((BaseMapper) orderItemMapper);
        when(mapperRegistry.getMapper(OrderAddressDO.class)).thenReturn((BaseMapper) orderAddressMapper);

        MetadataResolver metadataResolver = new MetadataResolver(mapperRegistry);
        diffEngine = new DiffEngine(metadataResolver);
    }

    @Test
    void diff_bothNull_returnsEmptyChangeSet() {
        ChangeSet result = diffEngine.diff(null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void diff_beforeNull_returnsInsert() {
        OrderEntity order = new OrderEntity();
        order.setOrderId(1L);
        order.setCustomerName("张三");

        ChangeSet result = diffEngine.diff(null, order);

        assertEquals(ChangeType.INSERT, result.getRootChange().getType());
        assertSame(order, result.getRootChange().getEntity());
    }

    @Test
    void diff_afterNull_returnsDelete() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);

        ChangeSet result = diffEngine.diff(before, null);

        assertEquals(ChangeType.DELETE, result.getRootChange().getType());
        assertEquals(1L, result.getRootChange().getEntityId());
    }

    @Test
    void diff_fieldChanged_returnsUpdateWithFieldChanges() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        before.setTotalAmount(new BigDecimal("100"));

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("李四");
        after.setTotalAmount(new BigDecimal("100"));

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(ChangeType.UPDATE, result.getRootChange().getType());
        assertEquals(1, result.getRootChange().getFieldChanges().size());
        assertEquals("customerName", result.getRootChange().getFieldChanges().get(0).getFieldName());
        assertEquals("张三", result.getRootChange().getFieldChanges().get(0).getOldValue());
        assertEquals("李四", result.getRootChange().getFieldChanges().get(0).getNewValue());
    }

    @Test
    void diff_noChange_returnsNone() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(ChangeType.NONE, result.getRootChange().getType());
    }

    @Test
    void diff_subEntityAdded_returnsInsert() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        before.setAddress(null);

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        OrderAddressEntity address = new OrderAddressEntity();
        address.setId(10L);
        address.setCity("上海");
        after.setAddress(address);

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.INSERT, result.getSubEntityChanges().get(0).getType());
    }

    @Test
    void diff_subEntityRemoved_returnsDelete() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        OrderAddressEntity address = new OrderAddressEntity();
        address.setId(10L);
        address.setCity("上海");
        before.setAddress(address);

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        after.setAddress(null);

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.DELETE, result.getSubEntityChanges().get(0).getType());
    }

    @Test
    void diff_subEntityFieldChanged_returnsUpdate() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        OrderAddressEntity addrBefore = new OrderAddressEntity();
        addrBefore.setId(10L);
        addrBefore.setCity("北京");
        before.setAddress(addrBefore);

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        OrderAddressEntity addrAfter = new OrderAddressEntity();
        addrAfter.setId(10L);
        addrAfter.setCity("上海");
        after.setAddress(addrAfter);

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.UPDATE, result.getSubEntityChanges().get(0).getType());
        assertEquals("city", result.getSubEntityChanges().get(0).getFieldChanges().get(0).getFieldName());
    }

    @Test
    void diff_listItemAdded_returnsInsert() {
        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        before.setItems(new ArrayList<>());

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        OrderItemEntity item = new OrderItemEntity();
        item.setId(100L);
        item.setProductName("商品A");
        after.setItems(List.of(item));

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.INSERT, result.getSubEntityChanges().get(0).getType());
    }

    @Test
    void diff_listItemRemoved_returnsDelete() {
        OrderItemEntity item = new OrderItemEntity();
        item.setId(100L);
        item.setProductName("商品A");

        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        before.setItems(new ArrayList<>(List.of(item)));

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        after.setItems(new ArrayList<>());

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.DELETE, result.getSubEntityChanges().get(0).getType());
        assertEquals(100L, result.getSubEntityChanges().get(0).getEntityId());
    }

    @Test
    void diff_listItemFieldChanged_returnsUpdate() {
        OrderItemEntity itemBefore = new OrderItemEntity();
        itemBefore.setId(100L);
        itemBefore.setProductName("商品A");
        itemBefore.setQuantity(2);

        OrderItemEntity itemAfter = new OrderItemEntity();
        itemAfter.setId(100L);
        itemAfter.setProductName("商品A");
        itemAfter.setQuantity(5);

        OrderEntity before = new OrderEntity();
        before.setOrderId(1L);
        before.setCustomerName("张三");
        before.setItems(new ArrayList<>(List.of(itemBefore)));

        OrderEntity after = new OrderEntity();
        after.setOrderId(1L);
        after.setCustomerName("张三");
        after.setItems(new ArrayList<>(List.of(itemAfter)));

        ChangeSet result = diffEngine.diff(before, after);

        assertEquals(1, result.getSubEntityChanges().size());
        assertEquals(ChangeType.UPDATE, result.getSubEntityChanges().get(0).getType());
        assertEquals("quantity", result.getSubEntityChanges().get(0).getFieldChanges().get(0).getFieldName());
    }
}
```

- [ ] **Step 2: 创建 DiffEngine**

```java
package com.maiya.persistence.diff;

import com.maiya.persistence.mapping.AggregateMetadata;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

@Component
public class DiffEngine {

    @Autowired
    private MetadataResolver metadataResolver;

    public DiffEngine() {
    }

    public DiffEngine(MetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver;
    }

    public ChangeSet diff(Object before, Object after) {
        ChangeSet changeSet = new ChangeSet();

        if (before == null && after == null) {
            return changeSet;
        }

        Class<?> entityClass = after != null ? after.getClass() : before.getClass();
        AggregateMetadata metadata = metadataResolver.resolve(entityClass);

        if (before == null) {
            changeSet.setRootChange(RootChange.builder()
                .type(ChangeType.INSERT)
                .entityClass(metadata.getEntityClass())
                .doClass(metadata.getRootDoClass())
                .entity(after)
                .build());
            addSubEntityInserts(after, metadata, changeSet);
            return changeSet;
        }

        if (after == null) {
            Object id = getId(before, metadata.getIdField());
            changeSet.setRootChange(RootChange.builder()
                .type(ChangeType.DELETE)
                .entityId(id)
                .idFieldName(metadata.getIdFieldName())
                .entityClass(metadata.getEntityClass())
                .doClass(metadata.getRootDoClass())
                .build());
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

    private void diffSubEntities(Object before, Object after,
                                  AggregateMetadata metadata,
                                  ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object oldSub = subInfo.getEntityField().get(before);
                Object newSub = subInfo.getEntityField().get(after);

                if (oldSub != null && newSub == null) {
                    Object id = getId(oldSub, subInfo.getEntityIdField());
                    changeSet.addSubEntityChange(SubEntityChange.builder()
                        .type(ChangeType.DELETE)
                        .entityId(id)
                        .idFieldName(subInfo.getIdFieldName())
                        .entityClass(subInfo.getEntityClass())
                        .doClass(subInfo.getDoClass())
                        .entity(oldSub)
                        .build());
                } else if (oldSub == null && newSub != null) {
                    changeSet.addSubEntityChange(SubEntityChange.builder()
                        .type(ChangeType.INSERT)
                        .entityClass(subInfo.getEntityClass())
                        .doClass(subInfo.getDoClass())
                        .entity(newSub)
                        .build());
                } else if (oldSub != null) {
                    List<FieldChange> changes = diffFields(oldSub, newSub, subInfo.getEntityClass());
                    if (!changes.isEmpty()) {
                        Object id = getId(newSub, subInfo.getEntityIdField());
                        changeSet.addSubEntityChange(SubEntityChange.builder()
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

    private void diffSubEntityLists(Object before, Object after,
                                     AggregateMetadata metadata,
                                     ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> oldList = (List<?>) listInfo.getEntityField().get(before);
                List<?> newList = (List<?>) listInfo.getEntityField().get(after);

                Map<Object, Object> oldMap = toIdMap(oldList, listInfo.getElementIdField());
                Map<Object, Object> newMap = toIdMap(newList, listInfo.getElementIdField());

                for (Object id : oldMap.keySet()) {
                    if (!newMap.containsKey(id)) {
                        changeSet.addSubEntityChange(SubEntityChange.builder()
                            .type(ChangeType.DELETE)
                            .entityId(id)
                            .idFieldName(listInfo.getIdFieldName())
                            .entityClass(listInfo.getElementEntityClass())
                            .doClass(listInfo.getElementDoClass())
                            .entity(oldMap.get(id))
                            .build());
                    }
                }
                for (Object id : newMap.keySet()) {
                    if (!oldMap.containsKey(id)) {
                        changeSet.addSubEntityChange(SubEntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(listInfo.getElementEntityClass())
                            .doClass(listInfo.getElementDoClass())
                            .entity(newMap.get(id))
                            .build());
                    }
                }
                for (Object id : oldMap.keySet()) {
                    if (newMap.containsKey(id)) {
                        List<FieldChange> changes = diffFields(oldMap.get(id), newMap.get(id),
                            listInfo.getElementEntityClass());
                        if (!changes.isEmpty()) {
                            changeSet.addSubEntityChange(SubEntityChange.builder()
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
                throw new RuntimeException("无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }

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

    private Object getId(Object entity, Field idField) {
        idField.setAccessible(true);
        try {
            return idField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("无法访问主键字段: " + idField.getName(), e);
        }
    }

    private void addSubEntityInserts(Object after, AggregateMetadata metadata, ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object sub = subInfo.getEntityField().get(after);
                if (sub != null) {
                    changeSet.addSubEntityChange(SubEntityChange.builder()
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
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> list = (List<?>) listInfo.getEntityField().get(after);
                if (list != null) {
                    for (Object item : list) {
                        changeSet.addSubEntityChange(SubEntityChange.builder()
                            .type(ChangeType.INSERT)
                            .entityClass(listInfo.getElementEntityClass())
                            .doClass(listInfo.getElementDoClass())
                            .entity(item)
                            .build());
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }

    private void addSubEntityDeletes(Object before, AggregateMetadata metadata, ChangeSet changeSet) {
        for (AggregateMetadata.SubEntityInfo subInfo : metadata.getSubEntities()) {
            subInfo.getEntityField().setAccessible(true);
            try {
                Object sub = subInfo.getEntityField().get(before);
                if (sub != null) {
                    Object id = getId(sub, subInfo.getEntityIdField());
                    changeSet.addSubEntityChange(SubEntityChange.builder()
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
        for (AggregateMetadata.SubEntityListInfo listInfo : metadata.getSubEntityLists()) {
            listInfo.getEntityField().setAccessible(true);
            try {
                List<?> list = (List<?>) listInfo.getEntityField().get(before);
                if (list != null) {
                    for (Object item : list) {
                        Object id = getId(item, listInfo.getElementIdField());
                        changeSet.addSubEntityChange(SubEntityChange.builder()
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
                throw new RuntimeException("无法访问子实体列表字段: " + listInfo.getEntityField().getName(), e);
            }
        }
    }
}
```

- [ ] **Step 3: 运行测试（需先完成 Task 8 的 Entity/DO 定义）**

Run: `cd unified-persistence && mvn test -pl persistence-core -Dtest=DiffEngineTest -q`
Expected: Tests pass

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: DiffEngine — 变更比对引擎"
```

---

### Task 8: Example 模块 — Entity / DO / Mapper 定义

**Files:**
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/entity/OrderEntity.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/entity/OrderItemEntity.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/entity/OrderAddressEntity.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/do/OrderDO.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/do/OrderItemDO.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/do/OrderAddressDO.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/mapper/OrderMapper.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/mapper/OrderItemMapper.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/mapper/OrderAddressMapper.java`

- [ ] **Step 1: 创建 Entity 类**

```java
package com.maiya.persistence.example.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderEntity {
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
    private OrderAddressEntity address;
    private List<OrderItemEntity> items;
}
```

```java
package com.maiya.persistence.example.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderItemEntity {
    private Long id;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
}
```

```java
package com.maiya.persistence.example.entity;

import lombok.Data;

@Data
public class OrderAddressEntity {
    private Long id;
    private String province;
    private String city;
    private String detail;
}
```

- [ ] **Step 2: 创建 DO 类**

```java
package com.maiya.persistence.example.do;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("t_order")
@AutoMapper(target = OrderEntity.class)
public class OrderDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long orderId;
    private String customerName;
    private BigDecimal totalAmount;
}
```

```java
package com.maiya.persistence.example.do;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderItemEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import java.math.BigDecimal;

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
```

```java
package com.maiya.persistence.example.do;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

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

- [ ] **Step 3: 创建 Mapper 接口**

```java
package com.maiya.persistence.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.do.OrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {
}
```

```java
package com.maiya.persistence.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.do.OrderItemDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {
}
```

```java
package com.maiya.persistence.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.do.OrderAddressDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderAddressMapper extends BaseMapper<OrderAddressDO> {
}
```

- [ ] **Step 4: 验证编译**

Run: `cd unified-persistence && mvn compile -pl persistence-example -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "feat: Example 模块 — Entity/DO/Mapper 定义"
```

---

### Task 9: ChangeExecutor — 变更执行器

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/execution/ChangeExecutor.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/execution/ChangeExecutorTest.java`

- [ ] **Step 1: 写 ChangeExecutor 测试**

```java
package com.maiya.persistence.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.do.OrderDO;
import com.maiya.persistence.example.do.OrderItemDO;
import com.maiya.persistence.example.do.OrderAddressDO;
import com.maiya.persistence.example.entity.OrderEntity;
import com.maiya.persistence.example.entity.OrderItemEntity;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeExecutorTest {

    @Mock
    private MapperRegistry mapperRegistry;

    @Mock
    private EntityCopier entityCopier;

    @Mock
    private BaseMapper<OrderDO> orderMapper;

    @Mock
    private BaseMapper<OrderItemDO> orderItemMapper;

    @Mock
    private BaseMapper<OrderAddressDO> orderAddressMapper;

    @InjectMocks
    private ChangeExecutor changeExecutor;

    @Test
    void execute_emptyChangeSet_doesNothing() {
        ChangeSet changeSet = new ChangeSet();
        changeExecutor.execute(changeSet);
        verifyNoInteractions(mapperRegistry);
    }

    @Test
    void execute_rootInsert_callsMapperInsert() {
        OrderEntity order = new OrderEntity();
        order.setOrderId(1L);
        order.setCustomerName("张三");

        OrderDO orderDO = new OrderDO();
        when(entityCopier.toDO(order, OrderDO.class)).thenReturn(orderDO);
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn(orderMapper);

        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder()
            .type(ChangeType.INSERT)
            .doClass(OrderDO.class)
            .entity(order)
            .build());

        changeExecutor.execute(changeSet);

        verify(orderMapper).insert(orderDO);
    }

    @Test
    void execute_rootDelete_callsMapperDeleteById() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn(orderMapper);

        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder()
            .type(ChangeType.DELETE)
            .doClass(OrderDO.class)
            .idFieldName("orderId")
            .entityId(1L)
            .build());

        changeExecutor.execute(changeSet);

        verify(orderMapper).deleteById(1L);
    }

    @Test
    void execute_rootUpdate_callsMapperUpdateWithChangedFieldsOnly() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn(orderMapper);

        List<FieldChange> fieldChanges = List.of(
            new FieldChange("customerName", "张三", "李四")
        );

        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder()
            .type(ChangeType.UPDATE)
            .doClass(OrderDO.class)
            .idFieldName("orderId")
            .entityId(1L)
            .fieldChanges(fieldChanges)
            .build());

        changeExecutor.execute(changeSet);

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void execute_subEntityDelete_beforeSubEntityInsert() {
        when(mapperRegistry.getMapper(OrderDO.class)).thenReturn(orderMapper);
        when(mapperRegistry.getMapper(OrderItemDO.class)).thenReturn(orderItemMapper);

        ChangeSet changeSet = new ChangeSet();
        changeSet.setRootChange(RootChange.builder()
            .type(ChangeType.NONE)
            .doClass(OrderDO.class)
            .build());

        SubEntityChange deleteChange = SubEntityChange.builder()
            .type(ChangeType.DELETE)
            .doClass(OrderItemDO.class)
            .idFieldName("id")
            .entityId(100L)
            .build();

        SubEntityChange insertChange = SubEntityChange.builder()
            .type(ChangeType.INSERT)
            .doClass(OrderItemDO.class)
            .entity(new OrderItemEntity())
            .build();

        changeSet.addSubEntityChange(deleteChange);
        changeSet.addSubEntityChange(insertChange);

        when(entityCopier.toDO(any(), eq(OrderItemDO.class))).thenReturn(new OrderItemDO());

        changeExecutor.execute(changeSet);

        InOrder inOrder = inOrder(orderItemMapper);
        inOrder.verify(orderItemMapper).deleteById(100L);
        inOrder.verify(orderItemMapper).insert(any(OrderItemDO.class));
    }
}
```

- [ ] **Step 2: 创建 ChangeExecutor**

```java
package com.maiya.persistence.execution;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        if (change.getType() == ChangeType.NONE) return;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeFieldLevelUpdate(Class<?> doClass, String idFieldName,
                                          Object entityId, List<FieldChange> fieldChanges) {
        UpdateWrapper wrapper = new UpdateWrapper<>();
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

- [ ] **Step 3: 运行测试**

Run: `cd unified-persistence && mvn test -pl persistence-core -Dtest=ChangeExecutorTest -q`
Expected: Tests pass

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: ChangeExecutor — 变更执行器（@Transactional）"
```

---

### Task 10: AggregateRepository — 统一持久化接口

**Files:**
- Create: `persistence-core/src/main/java/com/maiya/persistence/repository/AggregateRepository.java`
- Create: `persistence-core/src/main/java/com/maiya/persistence/repository/AggregateRepositoryImpl.java`
- Test: `persistence-core/src/test/java/com/maiya/persistence/repository/AggregateRepositoryImplTest.java`

- [ ] **Step 1: 创建 AggregateRepository 接口**

```java
package com.maiya.persistence.repository;

import com.maiya.persistence.model.ChangeSet;

public interface AggregateRepository<T> {

    ChangeSet diff(T before, T after);

    void execute(ChangeSet changeSet);

    default void persist(T before, T after) {
        ChangeSet changeSet = diff(before, after);
        execute(changeSet);
    }
}
```

- [ ] **Step 2: 创建 AggregateRepositoryImpl**

```java
package com.maiya.persistence.repository;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.model.ChangeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

- [ ] **Step 3: 写 AggregateRepositoryImpl 测试**

```java
package com.maiya.persistence.repository;

import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.example.entity.OrderEntity;
import com.maiya.persistence.model.ChangeSet;
import com.maiya.persistence.model.ChangeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AggregateRepositoryImplTest {

    @Mock
    private DiffEngine diffEngine;

    @Mock
    private ChangeExecutor changeExecutor;

    @InjectMocks
    private AggregateRepositoryImpl<OrderEntity> repository;

    @Test
    void diff_delegatesToDiffEngine() {
        OrderEntity before = new OrderEntity();
        OrderEntity after = new OrderEntity();
        ChangeSet expected = new ChangeSet();
        when(diffEngine.diff(before, after)).thenReturn(expected);

        ChangeSet result = repository.diff(before, after);

        assertSame(expected, result);
        verify(diffEngine).diff(before, after);
    }

    @Test
    void execute_delegatesToChangeExecutor() {
        ChangeSet changeSet = new ChangeSet();

        repository.execute(changeSet);

        verify(changeExecutor).execute(changeSet);
    }

    @Test
    void persist_callsDiffThenExecute() {
        OrderEntity before = new OrderEntity();
        OrderEntity after = new OrderEntity();
        ChangeSet changeSet = new ChangeSet();
        when(diffEngine.diff(before, after)).thenReturn(changeSet);

        repository.persist(before, after);

        InOrder inOrder = inOrder(diffEngine, changeExecutor);
        inOrder.verify(diffEngine).diff(before, after);
        inOrder.verify(changeExecutor).execute(changeSet);
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `cd unified-persistence && mvn test -pl persistence-core -Dtest=AggregateRepositoryImplTest -q`
Expected: Tests pass

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "feat: AggregateRepository — 统一持久化接口与实现"
```

---

### Task 11: AutoConfiguration — Spring Boot 自动配置

**Files:**
- Create: `persistence-mybatis/src/main/java/com/maiya/persistence/mybatis/autoconfigure/PersistenceAutoConfiguration.java`
- Create: `persistence-mybatis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建 PersistenceAutoConfiguration**

```java
package com.maiya.persistence.mybatis.autoconfigure;

import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.mapping.MapperRegistry;
import com.maiya.persistence.mapping.MetadataResolver;
import com.maiya.persistence.diff.DiffEngine;
import com.maiya.persistence.execution.ChangeExecutor;
import com.maiya.persistence.repository.AggregateRepository;
import com.maiya.persistence.repository.AggregateRepositoryImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PersistenceAutoConfiguration {

    @Bean
    public MapperRegistry mapperRegistry() {
        return new MapperRegistry();
    }

    @Bean
    public MetadataResolver metadataResolver() {
        return new MetadataResolver();
    }

    @Bean
    public EntityCopier entityCopier() {
        return new EntityCopier();
    }

    @Bean
    public DiffEngine diffEngine() {
        return new DiffEngine();
    }

    @Bean
    public ChangeExecutor changeExecutor() {
        return new ChangeExecutor();
    }

    @Bean
    public AggregateRepository<?> aggregateRepository() {
        return new AggregateRepositoryImpl<>();
    }
}
```

- [ ] **Step 2: 创建 AutoConfiguration.imports**

```
com.maiya.persistence.mybatis.autoconfigure.PersistenceAutoConfiguration
```

- [ ] **Step 3: 验证编译**

Run: `cd unified-persistence && mvn compile -pl persistence-mybatis -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: PersistenceAutoConfiguration — Spring Boot 自动配置"
```

---

### Task 12: Example 模块 — Application + Service + 数据库初始化

**Files:**
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/Application.java`
- Create: `persistence-example/src/main/java/com/maiya/persistence/example/service/OrderService.java`
- Create: `persistence-example/src/main/resources/application.yml`
- Create: `persistence-example/src/main/resources/schema.sql`

- [ ] **Step 1: 创建 schema.sql**

```sql
CREATE TABLE IF NOT EXISTS t_order (
    order_id BIGINT PRIMARY KEY,
    customer_name VARCHAR(100),
    total_amount DECIMAL(10,2)
);

CREATE TABLE IF NOT EXISTS t_order_item (
    id BIGINT PRIMARY KEY,
    order_id BIGINT,
    product_name VARCHAR(200),
    quantity INT,
    price DECIMAL(10,2)
);

CREATE TABLE IF NOT EXISTS t_order_address (
    id BIGINT PRIMARY KEY,
    order_id BIGINT,
    province VARCHAR(50),
    city VARCHAR(50),
    detail VARCHAR(200)
);
```

- [ ] **Step 2: 创建 application.yml**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: assign_id
```

- [ ] **Step 3: 创建 Application**

```java
package com.maiya.persistence.example;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.maiya.persistence.example.mapper")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

- [ ] **Step 4: 创建 OrderService**

```java
package com.maiya.persistence.example.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maiya.persistence.example.do.*;
import com.maiya.persistence.example.entity.*;
import com.maiya.persistence.example.mapper.*;
import com.maiya.persistence.mapping.EntityCopier;
import com.maiya.persistence.repository.AggregateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderAddressMapper orderAddressMapper;
    private final EntityCopier entityCopier;
    private final AggregateRepository<OrderEntity> orderRepository;

    public void createOrder(OrderEntity order) {
        orderRepository.persist(null, order);
    }

    public OrderEntity loadOrder(Long orderId) {
        OrderDO orderDO = orderMapper.selectById(orderId);
        if (orderDO == null) return null;

        OrderEntity order = entityCopier.toEntity(orderDO, OrderEntity.class);
        order.setItems(entityCopier.toList(
            orderItemMapper.selectList(
                new QueryWrapper<OrderItemDO>().eq("orderId", orderId)),
            OrderItemEntity.class));
        order.setAddress(entityCopier.toEntity(
            orderAddressMapper.selectOne(
                new QueryWrapper<OrderAddressDO>().eq("orderId", orderId)),
            OrderAddressEntity.class));
        return order;
    }

    public void updateOrder(Long orderId, String newCustomerName) {
        OrderEntity order = loadOrder(orderId);
        OrderEntity before = entityCopier.deepCopy(order);
        order.setCustomerName(newCustomerName);
        orderRepository.persist(before, order);
    }

    public void deleteOrder(Long orderId) {
        OrderEntity before = loadOrder(orderId);
        orderRepository.persist(before, null);
    }
}
```

- [ ] **Step 5: 验证编译**

Run: `cd unified-persistence && mvn compile -pl persistence-example -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat: Example 模块 — Application/Service/数据库初始化"
```

---

### Task 13: 全量编译与测试验证

- [ ] **Step 1: 全量编译**

Run: `cd unified-persistence && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行所有测试**

Run: `cd unified-persistence && mvn test -q`
Expected: All tests pass

- [ ] **Step 3: 修复任何测试失败（如有）**

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "chore: 全量编译与测试验证通过"
```

---

## Self-Review

### 1. Spec Coverage

| Spec 需求 | 对应 Task |
|-----------|----------|
| 公共持久化接口 | Task 10 |
| 业务对象自动映射到 Mapper 与 DO | Task 5 (MetadataResolver), Task 4 (MapperRegistry) |
| 变更前为空则插入 | Task 7 (DiffEngine) |
| 变更后为空则删除 | Task 7 (DiffEngine) |
| 数据变更只修改变更的数据 | Task 9 (ChangeExecutor - executeFieldLevelUpdate) |
| 约定优于配置 | Task 5 (MetadataResolver) |
| MapStruct Plus 拷贝 | Task 6 (EntityCopier) |
| DO 依赖 Entity | Task 8 |
| diff 事务外，execute 事务内 | Task 10 (AggregateRepositoryImpl) + Task 9 (ChangeExecutor @Transactional) |
| 多表聚合 | Task 7 (DiffEngine - diffSubEntities/diffSubEntityLists) |
| 字段级 + 子实体级比对 | Task 7 (DiffEngine) |

### 2. Placeholder Scan

✅ 无 TBD / TODO / "implement later" / "add validation" 等占位符

### 3. Type Consistency

✅ RootChange / SubEntityChange 的字段名在 DiffEngine 和 ChangeExecutor 中一致
✅ AggregateMetadata 的 SubEntityInfo / SubEntityListInfo 在 MetadataResolver 和 DiffEngine 中一致
✅ MapperRegistry.getMapper 签名在所有调用处一致

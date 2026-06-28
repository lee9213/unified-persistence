package com.maiya.persistence.mapping;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.linpeilie.Converter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DO 元数据注册中心，负责扫描并缓存 Entity 与 DO 的映射元数据。
 *
 * <p>容器启动时扫描所有 Converter Bean，解析其泛型参数获取 DO → Entity 映射关系，
 * 同时扫描所有 BaseMapper Bean，解析其泛型参数获取对应的 DO Class。
 * 预解析每个 DO 的元数据（主键、基本字段、Mapper），以 Entity Class 为 key 缓存，
 * 供 EntityMetadataResolver 直接查表使用。
 *
 * @author 萨博
 */
public class DoMetadataRegistry implements SmartInitializingSingleton, ApplicationContextAware {

    /** Spring 应用上下文，用于从容器中获取 Mapper Bean */
    private ApplicationContext applicationContext;

    /** DO 元数据注册表，Key 为 Entity 的 Class（通过 DO 上的 @AutoMapper 注解获取），Value 为预解析的元数据 */
    private static final Map<Class<?>, DoMetadata> DO_METADATA_MAP = new ConcurrentHashMap<>();

    /** DO → Entity 映射注册表，从 Converter Bean 解析得到 */
    private static final Map<Class<?>, Class<?>> DO_TO_ENTITY_MAP = new ConcurrentHashMap<>();

    /** MethodHandle 查找器 */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 全部单例 Bean 实例化完成后扫描 Mapper 和 Converter，确保 MyBatis 代理已注册。
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 1. 获取所有 BaseMapper Bean 和所有 Converter Bean
        Map<String, BaseMapper> mapperBeans = applicationContext.getBeansOfType(BaseMapper.class);
        Map<String, Converter> converterBeans = applicationContext.getBeansOfType(Converter.class);

        // 2. 收集所有需要解析的类（Converter + Mapper 实现类）
        // Converter Bean 的实际类是具体实现类，直接使用
        // BaseMapper Bean 是 JDK 代理类，需要通过 Bean 类型查找原始 Mapper 实现类
        Map<String, Class<?>> classesToParse = new ConcurrentHashMap<>();

        // Converter Bean 的实际类（直接添加）
        for (Converter converter : converterBeans.values()) {
            classesToParse.put(converter.getClass().getName(), converter.getClass());
        }

        // 从 Converter Bean 的类名推断对应的 Mapper 实现类
        // 例如：io.github.linpeilie.ConverterMapperAdapter__6 → com.maiya...OrderDOToOrderEntityMapperImpl
        for (Converter converter : converterBeans.values()) {
            findRelatedMapperImplClass(converter.getClass(), classesToParse);
        }

        // 3. 从所有类中解析 DO → Entity 映射关系
        resolveDoToEntityMapping(classesToParse);

        // 4. 遍历所有 Mapper，注册 DO 元数据
        mapperBeans.forEach((beanName, mapper) -> {
            Class<?> doClass = resolveDoClassFromMapper(mapper);
            if (doClass == null) {
                return;
            }
            Class<?> entityClass = DO_TO_ENTITY_MAP.get(doClass);
            if (entityClass == null) {
                return;
            }
            DO_METADATA_MAP.put(entityClass, resolveDoMetadata(doClass, mapper));
        });
    }

    /**
     * 从 Converter 类推断并查找关联的 Mapper 实现类。
     */
    private void findRelatedMapperImplClass(Class<?> converterClass, Map<String, Class<?>> classesToParse) {
        // 获取类路径下的资源
        try {
            String classPath = System.getProperty("java.class.path");
            String[] paths = classPath.split(System.getProperty("path.separator"));

            System.out.println("[DoMetadataRegistry] classpath 路径数: " + paths.length);
            for (String path : paths) {
                System.out.println("[DoMetadataRegistry]   扫描路径: " + path);
                if (path.contains("persistence-example") || path.contains("target/classes")) {
                    findMapperImplClassesInPath(path, classesToParse);
                }
            }
            System.out.println("[DoMetadataRegistry] 找到 MapperImpl 类: " + classesToParse.keySet());
            System.out.println("[DoMetadataRegistry] 找到 MapperImpl 类数量: " + classesToParse.size());
        } catch (Exception e) {
            // 忽略扫描异常
        }
    }

    /**
     * 从指定路径下查找 Mapper 实现类。
     */
    private void findMapperImplClassesInPath(String path, Map<String, Class<?>> classesToParse) {
        java.io.File file = new java.io.File(path);
        if (file.isDirectory() && file.exists()) {
            // 扫描整个目录下的所有 .class 文件
            scanDirectory(file, classesToParse);
        }
    }

    /**
     * 递归扫描目录查找 Mapper 实现类。
     */
    private void scanDirectory(java.io.File dir, Map<String, Class<?>> classesToParse) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;

        for (java.io.File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, classesToParse);
            } else if (file.getName().endsWith(".class")) {
                String fullPath = dir.getPath().replace("\\", "/");
                String classFilePath = file.getPath().replace("\\", "/");

                // 提取相对于 target/classes 的路径
                String relativePath = "";
                if (fullPath.contains("/target/classes/")) {
                    relativePath = fullPath.substring(fullPath.indexOf("/target/classes/") + 15) + "/";
                } else if (fullPath.contains("/target/test-classes/")) {
                    relativePath = fullPath.substring(fullPath.indexOf("/target/test-classes/") + 19) + "/";
                }

                // 修复类名：移除前导点号
                String className = (relativePath + file.getName().replace(".class", "")).replace("/", ".");
                if (className.startsWith(".")) {
                    className = className.substring(1);
                }

                try {
                    Class<?> clazz = Class.forName(className);

                    // 调试：打印类的接口
                    if (className.contains("MapperImpl")) {
                        java.lang.reflect.Type[] interfaces = clazz.getGenericInterfaces();
                        System.out.println("[DoMetadataRegistry] 类: " + className + ", 接口数: " + interfaces.length);
                        for (java.lang.reflect.Type iface : interfaces) {
                            System.out.println("[DoMetadataRegistry]   接口: " + iface.getTypeName());
                        }
                        System.out.println("[DoMetadataRegistry]   父类: " + (clazz.getSuperclass() != null ? clazz.getSuperclass().getName() : "null"));
                    }

                    // 匹配 *DOTo*MapperImpl（DO → Entity 的映射）或 *EntityTo*DOMapperImpl（Entity → DO 的映射）
                    boolean matches = (className.contains("DOToEntity") && className.endsWith("MapperImpl"))
                        || (className.contains("EntityToDO") && className.endsWith("MapperImpl"));

                    if (matches && BaseMapper.class.isAssignableFrom(clazz)) {
                        classesToParse.put(className, clazz);
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }
        }
    }

    /**
     * 从 Converter/Mapper 类集合中解析 DO → Entity 映射关系。
     *
     * @param classesToParse 需要解析的类
     */
    private void resolveDoToEntityMapping(Map<String, Class<?>> classesToParse) {
        for (Class<?> clazz : classesToParse.values()) {
            Class<?>[] types = parseDoAndEntityFromConverterClass(clazz);
            if (types != null) {
                DO_TO_ENTITY_MAP.put(types[0], types[1]);
            }
        }
    }

    /**
     * 从 Converter 类的泛型参数解析 DO 和 Entity 类型。
     * 遍历类层次结构，找到 BaseMapper 接口的泛型参数。
     *
     * @param converterClass Converter 实现类
     * @return [DO类型, Entity类型]，解析失败返回 null
     */
    private Class<?>[] parseDoAndEntityFromConverterClass(Class<?> converterClass) {
        try {
            Class<?> current = converterClass;
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Type type : current.getGenericInterfaces()) {
                    Class<?>[] types = parseFromParameterizedType(type);
                    if (types != null) {
                        return types;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    /**
     * 从 ParameterizedType 中解析 DO 和 Entity 类型。
     * 如果是接口类型，还会递归遍历其父接口。
     */
    private Class<?>[] parseFromParameterizedType(java.lang.reflect.Type type) {
        if (!(type instanceof java.lang.reflect.ParameterizedType)) {
            // 如果是普通接口类型，递归遍历其父接口
            if (type instanceof Class<?> && ((Class<?>) type).isInterface()) {
                Class<?> iface = (Class<?>) type;
                for (java.lang.reflect.Type parentType : iface.getGenericInterfaces()) {
                    Class<?>[] types = parseFromParameterizedType(parentType);
                    if (types != null) {
                        return types;
                    }
                }
            }
            return null;
        }

        java.lang.reflect.ParameterizedType parameterizedType = (java.lang.reflect.ParameterizedType) type;
        java.lang.reflect.Type rawType = parameterizedType.getRawType();

        // 检查是否是 Converter 或 BaseMapper 接口
        if (rawType.equals(Converter.class) || rawType.getTypeName().endsWith("BaseMapper")) {
            java.lang.reflect.Type[] typeArgs = parameterizedType.getActualTypeArguments();
            if (typeArgs.length == 2) {
                Class<?> sourceType = resolveTypeArgument(typeArgs[0]);
                Class<?> targetType = resolveTypeArgument(typeArgs[1]);
                if (sourceType != null && targetType != null) {
                    return new Class<?>[]{sourceType, targetType};
                }
            }
        }

        // 如果是接口类型，递归遍历其父接口
        if (rawType instanceof Class<?> && ((Class<?>) rawType).isInterface()) {
            for (java.lang.reflect.Type parentType : ((Class<?>) rawType).getGenericInterfaces()) {
                Class<?>[] types = parseFromParameterizedType(parentType);
                if (types != null) {
                    return types;
                }
            }
        }

        return null;
    }

    /**
     * 解析类型参数，支持泛型和通配符。
     */
    private Class<?> resolveTypeArgument(java.lang.reflect.Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.Type rawType = ((java.lang.reflect.ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        return null;
    }

    /**
     * 从 BaseMapper 实例解析其泛型参数，获取对应的 DO Class。
     *
     * @param mapper BaseMapper 实例
     * @return DO 类的 Class 对象，解析失败返回 null
     */
    private Class<?> resolveDoClassFromMapper(BaseMapper<?> mapper) {
        ResolvableType resolvableType = ResolvableType.forClass(mapper.getClass()).as(BaseMapper.class);
        Class<?> generic = resolvableType.getGeneric(0).resolve();
        // 过滤掉框架内部类
        if (generic != null && !generic.getName().startsWith("java.")) {
            return generic;
        }
        return null;
    }

    /**
     * 预解析 DO 类的元数据，包括主键字段、基本字段访问器和对应的 Mapper。
     *
     * @param doClass DO 类
     * @param mapper 对应的 BaseMapper 实例
     * @return DO 元数据
     */
    private DoMetadata resolveDoMetadata(Class<?> doClass, BaseMapper<?> mapper) {
        DoMetadata metadata = new DoMetadata();
        metadata.setDoClass(doClass);
        metadata.setMapper(mapper);

        // 遍历 DO 字段，识别主键和基本字段
        for (Field doField : doClass.getDeclaredFields()) {
            if (Modifier.isStatic(doField.getModifiers()) || Modifier.isTransient(doField.getModifiers())) {
                continue;
            }
            if (doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class) != null
                && !doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).exist()) {
                continue;
            }
            if (doField.getAnnotation(com.baomidou.mybatisplus.annotation.TableId.class) != null) {
                // 主键字段
                metadata.setIdFieldName(doField.getName());
                metadata.setIdGetter(findGetter(doClass, doField.getName()));
            } else {
                // 基本字段
                metadata.getBasicFields().add(new EntityMetadata.FieldAccessor(doField.getName(), findGetter(doClass, doField.getName())));
            }
        }

        if (metadata.getIdFieldName() == null) {
            throw new IllegalArgumentException("DO " + doClass.getName() + " 没有找到 @TableId 注解的主键字段");
        }

        return metadata;
    }

    /**
     * 查找 DO 类字段的 getter 方法并返回其 MethodHandle
     *
     * @param clazz 目标类
     * @param fieldName 字段名
     * @return getter 方法的 MethodHandle
     */
    private MethodHandle findGetter(Class<?> clazz, String fieldName) {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method getter = clazz.getDeclaredMethod(getterName);
            return LOOKUP.unreflect(getter);
        } catch (NoSuchMethodException e) {
            String isGetterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                Method isGetter = clazz.getDeclaredMethod(isGetterName);
                return LOOKUP.unreflect(isGetter);
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new IllegalArgumentException("类 " + clazz.getName() + " 没有找到字段 " + fieldName + " 的 getter 方法", ex);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("无法访问 " + clazz.getName() + "." + getterName + " 方法", e);
        }
    }

    /**
     * 根据 Entity Class 获取预解析的 DO 元数据。
     *
     * @param entityClass Entity 类
     * @return DO 元数据，未找到则返回 null
     */
    public DoMetadata getDoMetadata(Class<?> entityClass) {
        return DO_METADATA_MAP.get(entityClass);
    }
}

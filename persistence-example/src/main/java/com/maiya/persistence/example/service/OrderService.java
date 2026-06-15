package com.maiya.persistence.example.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maiya.persistence.example.data.*;
import com.maiya.persistence.example.entity.*;
import com.maiya.persistence.example.mapper.*;
import com.maiya.persistence.mapping.EntityConverter;
import com.maiya.persistence.repository.PersistenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 订单服务类 提供订单的创建、查询、更新和删除等业务操作 通过聚合仓库实现订单聚合的持久化管理
 *
 * @author 萨博
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 订单数据访问接口 */
    private final OrderMapper orderMapper;

    /** 订单商品项数据访问接口 */
    private final OrderItemMapper orderItemMapper;

    /** 订单收货地址数据访问接口 */
    private final OrderAddressMapper orderAddressMapper;

    /** 实体转换器，用于 DO 和 Entity 之间的转换 */
    private final EntityConverter entityConverter;

    /** 订单聚合仓库，用于管理订单聚合的持久化操作 */
    private final PersistenceRepository<OrderEntity> orderRepository;

    /**
     * 创建订单
     *
     * @param order 订单实体
     */
    public void createOrder(OrderEntity order) {
        orderRepository.persist(null, order);
    }

    /**
     * 根据订单ID加载完整订单信息
     *
     * @param orderId 订单ID
     * @return 完整的订单实体，包含地址和商品项；如果订单不存在则返回 null
     */
    public OrderEntity loadOrder(Long orderId) {
        OrderDO orderDO = orderMapper.selectById(orderId);
        if (orderDO == null) return null;

        OrderEntity order = entityConverter.toEntity(orderDO, OrderEntity.class);
        order.setItems(
                entityConverter.toList(
                        orderItemMapper.selectList(
                                new QueryWrapper<OrderItemDO>().eq("orderId", orderId)),
                        OrderItemEntity.class));
        order.setAddress(
                entityConverter.toEntity(
                        orderAddressMapper.selectOne(
                                new QueryWrapper<OrderAddressDO>().eq("orderId", orderId)),
                        OrderAddressEntity.class));
        return order;
    }

    /**
     * 更新订单客户名称
     *
     * @param orderId 订单ID
     * @param newCustomerName 新的客户名称
     */
    public void updateOrder(Long orderId, String newCustomerName) {
        OrderEntity order = loadOrder(orderId);
        OrderEntity before = entityConverter.convert(order);
        order.setCustomerName(newCustomerName);
        orderRepository.persist(before, order);
    }

    /**
     * 删除订单
     *
     * @param orderId 订单ID
     */
    public void deleteOrder(Long orderId) {
        OrderEntity before = loadOrder(orderId);
        orderRepository.persist(before, null);
    }
}

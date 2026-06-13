package com.maiya.persistence.example.entity;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * 订单实体类 表示一个完整的订单聚合根，包含订单基本信息、收货地址和订单商品项
 *
 * @author 萨博
 */
@Data
public class OrderEntity {

    /** 订单ID */
    private Long orderId;

    /** 客户名称 */
    private String customerName;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 订单收货地址 */
    private OrderAddressEntity address;

    /** 订单商品项列表 */
    private List<OrderItemEntity> items;
}

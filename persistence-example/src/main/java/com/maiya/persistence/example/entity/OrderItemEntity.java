package com.maiya.persistence.example.entity;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 订单商品项实体类 表示订单中的一个商品项，包含商品名称、数量和单价
 *
 * @author 萨博
 */
@Data
public class OrderItemEntity {

    /** 订单商品项ID */
    private Long id;

    /** 商品名称 */
    private String productName;

    /** 商品数量 */
    private Integer quantity;

    /** 商品单价 */
    private BigDecimal price;
}

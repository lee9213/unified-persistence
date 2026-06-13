package com.maiya.persistence.example.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderItemEntity;
import io.github.linpeilie.annotations.AutoMapper;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 订单商品项数据对象（Data Object） 对应数据库表 t_order_item，用于订单商品项数据的持久化存储 通过 AutoMapper 自动映射到 OrderItemEntity
 *
 * @author 萨博
 */
@Data
@TableName("t_order_item")
@AutoMapper(target = OrderItemEntity.class)
public class OrderItemDO {

    /** 订单商品项ID，使用雪花算法自动分配 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属订单ID */
    private Long orderId;

    /** 商品名称 */
    private String productName;

    /** 商品数量 */
    private Integer quantity;

    /** 商品单价 */
    private BigDecimal price;
}

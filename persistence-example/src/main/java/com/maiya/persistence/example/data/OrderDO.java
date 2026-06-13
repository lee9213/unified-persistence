package com.maiya.persistence.example.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderEntity;
import io.github.linpeilie.annotations.AutoMapper;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 订单数据对象（Data Object） 对应数据库表 t_order，用于订单数据的持久化存储 通过 AutoMapper 自动映射到 OrderEntity
 *
 * @author 萨博
 */
@Data
@TableName("t_order")
@AutoMapper(target = OrderEntity.class)
public class OrderDO {

    /** 订单ID，使用雪花算法自动分配 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long orderId;

    /** 客户名称 */
    private String customerName;

    /** 订单总金额 */
    private BigDecimal totalAmount;
}

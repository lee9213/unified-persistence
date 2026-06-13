package com.maiya.persistence.example.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

/**
 * 订单收货地址数据对象（Data Object） 对应数据库表 t_order_address，用于订单收货地址数据的持久化存储 通过 AutoMapper 自动映射到
 * OrderAddressEntity
 *
 * @author 萨博
 */
@Data
@TableName("t_order_address")
@AutoMapper(target = OrderAddressEntity.class)
public class OrderAddressDO {

    /** 地址ID，使用雪花算法自动分配 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属订单ID */
    private Long orderId;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 详细地址 */
    private String detail;
}

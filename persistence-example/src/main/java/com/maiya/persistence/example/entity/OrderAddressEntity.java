package com.maiya.persistence.example.entity;

import lombok.Data;

/**
 * 订单收货地址实体类 表示订单的收货地址信息，包含省、市和详细地址
 *
 * @author 萨博
 */
@Data
public class OrderAddressEntity {

    /** 地址ID */
    private Long id;

    /** 省份 */
    private String province;

    /** 城市 */
    private String city;

    /** 详细地址 */
    private String detail;
}

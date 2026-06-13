package com.maiya.persistence.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.data.OrderAddressDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单收货地址数据访问接口 继承 MyBatis-Plus 的 BaseMapper，提供订单收货地址表的基础 CRUD 操作
 *
 * @author 萨博
 */
@Mapper
public interface OrderAddressMapper extends BaseMapper<OrderAddressDO> {}

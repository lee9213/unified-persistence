package com.maiya.persistence.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maiya.persistence.example.data.OrderDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单数据访问接口 继承 MyBatis-Plus 的 BaseMapper，提供订单表的基础 CRUD 操作
 *
 * @author 萨博
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {}

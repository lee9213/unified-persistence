package com.maiya.persistence.example.service;

import com.maiya.persistence.example.Application;
import com.maiya.persistence.example.entity.OrderAddressEntity;
import com.maiya.persistence.example.entity.OrderEntity;
import com.maiya.persistence.example.entity.OrderItemEntity;
import com.maiya.persistence.example.mapper.OrderAddressMapper;
import com.maiya.persistence.example.mapper.OrderItemMapper;
import com.maiya.persistence.example.mapper.OrderMapper;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OrderService 集成测试（真实 Spring 容器 + H2，无 Mock）。
 *
 * @author 萨博
 */
@Slf4j
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class OrderServiceTest {

    private static final long ORDER_ID = 10_001L;
    private static final long ITEM_ID = 20_001L;
    private static final long ADDRESS_ID = 30_001L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderAddressMapper orderAddressMapper;

    @Test
    void createOrder_persistsNewAggregate() {
        OrderEntity order = buildOrder(ORDER_ID, "Alice");
        logPersistChange("createOrder", null, order);

        logStep("调用 createOrder");
        orderService.createOrder(order);

        logStep("从数据库加载并验证");
        OrderEntity loaded = orderService.loadOrder(ORDER_ID);
        logOrder("DB after", loaded);
        assertNotNull(loaded);
        assertEquals("Alice", loaded.getCustomerName());
        assertEquals(new BigDecimal("100.00"), loaded.getTotalAmount());
        logStep("测试通过");
    }

    @Test
    void loadOrder_whenOrderMissing_returnsNull() {
        logStep("调用 loadOrder(99999L)");
        OrderEntity result = orderService.loadOrder(99_999L);

        logStep("断言结果为 null");
        assertNull(result);
        logStep("测试通过");
    }

    @Test
    void loadOrder_whenOrderExists_returnsAggregateWithItemsAndAddress() {
        logStep("准备订单及关联数据");
        seedOrderWithRelations(ORDER_ID, "Bob");

        logStep("调用 loadOrder");
        OrderEntity result = orderService.loadOrder(ORDER_ID);
        logOrder("loaded", result);

        assertNotNull(result);
        assertEquals("Bob", result.getCustomerName());
        assertNotNull(result.getItems());
        assertEquals(1, result.getItems().size());
        assertEquals("Book", result.getItems().get(0).getProductName());
        assertNotNull(result.getAddress());
        assertEquals("SZ", result.getAddress().getCity());
        logStep("测试通过");
    }

    @Test
    void updateOrder_changesCustomerNameAndPersists() {
        seedOrderWithRelations(ORDER_ID, "Bob");
        OrderEntity before = orderService.loadOrder(ORDER_ID);
        logPersistChange("updateOrder", before, copyForLog(before, "Alice"));

        logStep("调用 updateOrder(ORDER_ID, Alice)");
        orderService.updateOrder(ORDER_ID, "Alice");

        OrderEntity after = orderService.loadOrder(ORDER_ID);
        logOrder("DB after", after);
        assertEquals("Alice", after.getCustomerName());
        logStep("测试通过");
    }

    @Test
    void deleteOrder_persistsDeletion() {
        seedOrderWithRelations(ORDER_ID, "Bob");
        OrderEntity before = orderService.loadOrder(ORDER_ID);
        logPersistChange("deleteOrder", before, null);

        logStep("调用 deleteOrder");
        orderService.deleteOrder(ORDER_ID);

        OrderEntity after = orderService.loadOrder(ORDER_ID);
        logOrder("DB after", after);
        assertNull(after);
        assertNull(orderItemMapper.selectById(ITEM_ID));
        assertNull(orderAddressMapper.selectById(ADDRESS_ID));
        logStep("测试通过");
    }

    private void seedOrderWithRelations(long orderId, String customerName) {
        orderService.createOrder(buildOrder(orderId, customerName));
    }

    private OrderEntity buildOrder(long orderId, String customerName) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setCustomerName(customerName);
        order.setTotalAmount(new BigDecimal("100.00"));

        OrderItemEntity item = new OrderItemEntity();
        item.setId(ITEM_ID);
        item.setOrderId(orderId);
        item.setProductName("Book");
        item.setQuantity(1);
        item.setPrice(new BigDecimal("100.00"));
        order.setItems(List.of(item));

        OrderAddressEntity address = new OrderAddressEntity();
        address.setId(ADDRESS_ID);
        address.setOrderId(orderId);
        address.setProvince("GD");
        address.setCity("SZ");
        address.setDetail("Street 1");
        order.setAddress(address);
        return order;
    }

    private OrderEntity copyForLog(OrderEntity source, String newCustomerName) {
        OrderEntity copy = new OrderEntity();
        copy.setOrderId(source.getOrderId());
        copy.setCustomerName(newCustomerName);
        copy.setTotalAmount(source.getTotalAmount());
        return copy;
    }

    private void logPersistChange(String action, OrderEntity before, OrderEntity after) {
        log.info("[OrderServiceTest] {} 变更:", action);
        logOrder("  before", before);
        logOrder("  after", after);
    }

    private void logOrder(String label, OrderEntity order) {
        if (order == null) {
            log.info("[OrderServiceTest] {} = null", label);
            return;
        }
        log.info(
                "[OrderServiceTest] {} = orderId={}, customerName={}, itemCount={}, city={}",
                label,
                order.getOrderId(),
                order.getCustomerName(),
                order.getItems() == null ? 0 : order.getItems().size(),
                order.getAddress() == null ? null : order.getAddress().getCity());
    }

    private void logStep(String message) {
        log.info("[OrderServiceTest] {}", message);
    }
}

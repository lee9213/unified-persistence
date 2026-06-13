CREATE TABLE IF NOT EXISTS t_order (
    order_id BIGINT PRIMARY KEY,
    customer_name VARCHAR(100),
    total_amount DECIMAL(10,2)
);

CREATE TABLE IF NOT EXISTS t_order_item (
    id BIGINT PRIMARY KEY,
    order_id BIGINT,
    product_name VARCHAR(200),
    quantity INT,
    price DECIMAL(10,2)
);

CREATE TABLE IF NOT EXISTS t_order_address (
    id BIGINT PRIMARY KEY,
    order_id BIGINT,
    province VARCHAR(50),
    city VARCHAR(50),
    detail VARCHAR(200)
);
